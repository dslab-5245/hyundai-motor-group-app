package io.drivesymphony.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import io.drivesymphony.app.MainActivity
import io.drivesymphony.app.R
import io.drivesymphony.app.Settings
import io.drivesymphony.app.remix.LocalTrackPlayer
import io.drivesymphony.app.signals.PleosNaviSource
import io.drivesymphony.app.signals.PleosVehicleSource
import io.drivesymphony.app.signals.ScenarioSource
import io.drivesymphony.app.signals.SignalStore
import io.drivesymphony.app.ux.DrivingStateObserver
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.SymphonyEngine
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.remix.TrackProcessor
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

enum class PlayMode { GENERATIVE, REMIX }

data class ServiceUiState(
    val playing: Boolean = false,
    val mode: PlayMode = PlayMode.GENERATIVE,
    val genre: Genre = Genre.LOFI,
    val demo: Boolean = false,
    val demoScenarioId: String = "story",
    val vehicleLive: Boolean = false,
    val naviLive: Boolean = false,
    val restricted: Boolean = false,
    val feedbackLevel: Int = 1,
    val remixTrackTitle: String? = null,
    val remixError: String? = null,
    val lastSummary: TripSummary? = null,
    val tripActive: Boolean = false,
)

/**
 * Foreground media service that owns the engine, the audio output, the signal sources and the MediaSession
 * (so the car's media center shows "Drive Symphony" with play/pause/next-genre controls).
 */
class SymphonyService : MediaBrowserServiceCompat() {

    inner class LocalBinder : Binder() { val service: SymphonyService get() = this@SymphonyService }
    private val binder = LocalBinder()

    private lateinit var settings: Settings
    private lateinit var engine: SymphonyEngine
    private lateinit var output: AudioOutput
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val store = SignalStore()
    private val vehicleSource by lazy { PleosVehicleSource(this) }
    private val naviSource by lazy { PleosNaviSource(this) }
    private val scenarioSource = ScenarioSource()
    private val drivingState by lazy { DrivingStateObserver(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controlJob: Job? = null

    private val _engineState = MutableStateFlow<EngineState?>(null)
    val engineState: StateFlow<EngineState?> = _engineState
    private val _ui = MutableStateFlow(ServiceUiState())
    val ui: StateFlow<ServiceUiState> = _ui

    // remix
    @Volatile private var trackPlayer: LocalTrackPlayer? = null
    private val trackProcessor by lazy { TrackProcessor(SAMPLE_RATE) }
    @Volatile private var mode = PlayMode.GENERATIVE

    // trip bookkeeping
    private var movingSinceMs = 0L
    private var wasMoving = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        engine = SymphonyEngine(SAMPLE_RATE, settings.genre, seed = (System.currentTimeMillis() % 100_000).toInt(), hourOfDay = hour)
        engine.setFeedbackLevel(settings.feedbackLevel)
        output = AudioOutput(SAMPLE_RATE, BLOCK_FRAMES, ::renderBlock)
        createChannel()
        setupSession()
        _ui.update { it.copy(genre = settings.genre, feedbackLevel = settings.feedbackLevel, demo = settings.demoMode, demoScenarioId = settings.demoScenarioId) }
        drivingState.start()
        scope.launch { drivingState.restricted.collect { r -> _ui.update { it.copy(restricted = r) } } }
        startSources()
        settings.remixTrackUri?.let { runCatching { setRemixTrack(Uri.parse(it)) } }
    }

    private fun startSources() {
        runCatching { vehicleSource.start(store) }.onFailure { Log.w(TAG, "vehicle source failed: ${it.message}") }
        runCatching { naviSource.start(store) }.onFailure { Log.w(TAG, "navi source failed: ${it.message}") }
        if (settings.demoMode) { scenarioSource.setScenario(Scenarios.byId(settings.demoScenarioId)); scenarioSource.start(store) }
    }

    private fun renderBlock(buf: FloatArray, frames: Int) {
        if (mode == PlayMode.REMIX) {
            val tp = trackPlayer
            if (tp != null) { tp.read(buf, frames); trackProcessor.process(buf, buf, frames, engine.params) }
            else java.util.Arrays.fill(buf, 0, frames * 2, 0f)
        } else {
            engine.render(buf, frames)
        }
    }

    // ---------------------------------------------------------------- control loop
    private fun startControlLoop() {
        controlJob?.cancel()
        controlJob = scope.launch {
            val t0 = System.nanoTime()
            var last = t0
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9).toFloat().coerceIn(0.005f, 0.2f)
                last = now
                val tSec = (now - t0) / 1e9
                val snap = store.snapshot(tSec)
                engine.update(snap, dt)
                trackTrip(snap.gear, snap.speedKmh)
                val st = engine.state
                _engineState.value = st
                val vLive = vehicleSource.isLive(store)
                val nLive = naviSource.isLive(store)
                // real vehicle data wins over the demo script
                if (vLive && scenarioSource.isLive(store) && !settings.demoMode) scenarioSource.stop()
                _ui.update { if (it.vehicleLive != vLive || it.naviLive != nLive) it.copy(vehicleLive = vLive, naviLive = nLive) else it }
                delay(40)
            }
        }
    }

    private fun trackTrip(gear: Gear, speed: Float?) {
        val moving = gear == Gear.DRIVE || (gear == Gear.UNKNOWN && (speed ?: 0f) > 5f)
        val now = System.currentTimeMillis()
        if (moving && !wasMoving) {
            movingSinceMs = now
            _ui.update { it.copy(tripActive = true) }
        }
        if (!moving && wasMoving && gear == Gear.PARK && now - movingSinceMs > 45_000) {
            val summary = engine.summary()
            _ui.update { it.copy(lastSummary = summary, tripActive = false) }
            engine.newTrip(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), store.roadName)
            updateMetadata()
        }
        wasMoving = moving
    }

    // ---------------------------------------------------------------- public API (UI)
    fun play() {
        if (_ui.value.playing) return
        if (!requestFocus()) { Log.w(TAG, "audio focus denied"); return }
        startForeground(NOTIF_ID, buildNotification(), foregroundType())
        output.start()
        startControlLoop()
        session.isActive = true
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        _ui.update { it.copy(playing = true) }
        updateMetadata()
    }

    fun pause() {
        if (!_ui.value.playing) return
        output.stop()
        controlJob?.cancel(); controlJob = null
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        _ui.update { it.copy(playing = false) }
        stopForeground(STOP_FOREGROUND_DETACH)
        abandonFocus()
    }

    fun toggle() = if (_ui.value.playing) pause() else play()

    fun setGenre(g: Genre) {
        settings.genre = g
        engine.setGenre(g)
        _ui.update { it.copy(genre = g) }
        updateMetadata()
    }

    fun nextGenre() {
        val all = Genre.values()
        setGenre(all[(all.indexOf(_ui.value.genre) + 1) % all.size])
    }

    fun setFeedbackLevel(level: Int) {
        settings.feedbackLevel = level
        engine.setFeedbackLevel(level)
        _ui.update { it.copy(feedbackLevel = level) }
    }

    fun setDemo(enabled: Boolean, scenarioId: String = settings.demoScenarioId) {
        settings.demoMode = enabled
        settings.demoScenarioId = scenarioId
        if (enabled) { scenarioSource.setScenario(Scenarios.byId(scenarioId)); scenarioSource.start(store) } else scenarioSource.stop()
        _ui.update { it.copy(demo = enabled, demoScenarioId = scenarioId) }
    }

    fun setRemixTrack(uri: Uri?) {
        trackPlayer?.stop(); trackPlayer = null
        if (uri == null) {
            mode = PlayMode.GENERATIVE
            settings.remixTrackUri = null
            _ui.update { it.copy(mode = mode, remixTrackTitle = null, remixError = null) }
        } else {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val p = LocalTrackPlayer(this, uri, SAMPLE_RATE)
            p.start()
            trackPlayer = p
            mode = PlayMode.REMIX
            settings.remixTrackUri = uri.toString()
            _ui.update { it.copy(mode = mode, remixTrackTitle = p.title, remixError = null) }
            scope.launch { delay(1500); _ui.update { it.copy(remixTrackTitle = p.title, remixError = p.error) } }
        }
        updateMetadata()
    }

    fun currentSummary(): TripSummary = engine.summary()

    // ---------------------------------------------------------------- audio focus
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> output.duckGain = 0f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> output.duckGain = 0.25f // navigation prompt: duck, keep playing
            AudioManager.AUDIOFOCUS_GAIN -> output.duckGain = 1f
        }
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } }

    // ---------------------------------------------------------------- media session / browser
    private fun setupSession() {
        session = MediaSessionCompat(this, "DriveSymphony").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = nextGenre()
                override fun onSkipToPrevious() { val all = Genre.values(); setGenre(all[(all.indexOf(_ui.value.genre) + all.size - 1) % all.size]) }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Genre.values().firstOrNull { it.name == mediaId }?.let { setGenre(it) }
                    play()
                }
            })
        }
        sessionToken = session.sessionToken
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        updateMetadata()
    }

    private fun setPlaybackState(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun updateMetadata() {
        val st = engine.state
        val title = if (mode == PlayMode.REMIX) (_ui.value.remixTrackTitle ?: "Remix") else st.title
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Drive Symphony · ${_ui.value.genre.label}")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "${st.keyLabel} · ${st.bpm.toInt()} BPM")
                .build()
        )
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        if (parentId != ROOT_ID) { result.sendResult(mutableListOf()); return }
        val items = Genre.values().map { g ->
            MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder().setMediaId(g.name).setTitle(g.label).setSubtitle(getString(R.string.genre_subtitle)).build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
            )
        }.toMutableList()
        result.sendResult(items)
    }

    // ---------------------------------------------------------------- lifecycle / notification
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT_GENRE -> nextGenre()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pause()
        controlJob?.cancel()
        scope.cancel()
        runCatching { vehicleSource.stop() }
        runCatching { naviSource.stop() }
        scenarioSource.destroy()
        trackPlayer?.stop()
        drivingState.stop()
        session.release()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val toggle = PendingIntent.getService(this, 1, Intent(this, SymphonyService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_symphony)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(engine.state.title)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_symphony, getString(R.string.action_toggle), toggle)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val BLOCK_FRAMES = 256
        const val ROOT_ID = "drive_symphony_root"
        const val CHANNEL_ID = "drive_symphony_playback"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "io.drivesymphony.action.PLAY"
        const val ACTION_PAUSE = "io.drivesymphony.action.PAUSE"
        const val ACTION_TOGGLE = "io.drivesymphony.action.TOGGLE"
        const val ACTION_NEXT_GENRE = "io.drivesymphony.action.NEXT_GENRE"
        private const val TAG = "SymphonyService"
    }
}
