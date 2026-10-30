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
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.MediaBrowserServiceCompat
import io.drivesymphony.app.MainActivity
import io.drivesymphony.app.R
import io.drivesymphony.app.Settings
import io.drivesymphony.app.history.TripHistory
import io.drivesymphony.app.history.TripRecord
import io.drivesymphony.app.remix.LocalTrackPlayer
import io.drivesymphony.app.share.ShareCard
import io.drivesymphony.app.signals.PleosNaviSource
import io.drivesymphony.app.signals.PleosVehicleSource
import io.drivesymphony.app.signals.CarPropertyGearSource
import io.drivesymphony.app.signals.ScenarioSource
import io.drivesymphony.app.signals.SignalStore
import io.drivesymphony.app.ux.DrivingStateObserver
import io.drivesymphony.engine.EngineState
import io.drivesymphony.engine.RenderProfile
import io.drivesymphony.engine.SymphonyEngine
import io.drivesymphony.engine.TripSummary
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.remix.TrackProcessor
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.theory.GenreParser
import kotlinx.coroutines.CoroutineExceptionHandler
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
    val history: List<TripRecord> = emptyList(),
    val autoStart: Boolean = false,
    /** engine fell back to the light render profile (CPU budget guard) */
    val light: Boolean = false,
    /** audio-thread render load as a fraction of one core */
    val engineLoad: Float = 0f,
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
    private val carGearSource = CarPropertyGearSource(this)
    private val drivingState by lazy { DrivingStateObserver(this) }
    private val history by lazy { TripHistory(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "service coroutine", e) })
    private var controlJob: Job? = null

    private val _engineState = MutableStateFlow<EngineState?>(null)
    val engineState: StateFlow<EngineState?> = _engineState
    private val _ui = MutableStateFlow(ServiceUiState())
    val ui: StateFlow<ServiceUiState> = _ui

    // remix
    @Volatile private var trackPlayer: LocalTrackPlayer? = null
    private val trackProcessor = TrackProcessor(SAMPLE_RATE)   // eager: no allocation on the audio thread at the first remix block
    @Volatile private var mode = PlayMode.GENERATIVE

    // trip bookkeeping
    private var movingSinceMs = 0L
    private var wasMoving = false
    private var lastMovingAtMs = 0L
    private var rearOccupiedApplied = false   // rear passengers -> feedback level forced to '약'

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
        _ui.update { it.copy(genre = settings.genre, feedbackLevel = settings.feedbackLevel, demo = settings.demoMode, demoScenarioId = settings.demoScenarioId, history = history.all(), autoStart = settings.autoStartOnDrive) }
        drivingState.start()
        scope.launch { drivingState.restricted.collect { r -> _ui.update { it.copy(restricted = r) } } }
        startSources()
        settings.remixTrackUri?.let { runCatching { setRemixTrack(Uri.parse(it)) } }
        // Scoring, trip tracking and signal status run for the whole service lifetime, not only while playing.
        startControlLoop()
    }

    /** API 31+: a foreground start from the background (Gleo / adb intent with no visible activity) can be refused. */
    private fun startForegroundSafely() {
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onFailure { Log.w(TAG, "startForeground refused: ${it.message}") }
    }

    private fun startSources() {
        runCatching { vehicleSource.start(store) }.onFailure { Log.w(TAG, "vehicle source failed: ${it.message}") }
        runCatching { naviSource.start(store) }.onFailure { Log.w(TAG, "navi source failed: ${it.message}") }
        runCatching { carGearSource.start(store) }.onFailure { Log.w(TAG, "car gear source failed: ${it.message}") }
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
                if (!_ui.value.playing) engine.publishIdleState()   // render() is idle: publish for the UI and Gleo
                // rear-seat passengers: the feedback drops to '약' automatically and comes back when they leave
                val rear = store.rearSeatOccupied
                if (rear != rearOccupiedApplied) {
                    rearOccupiedApplied = rear
                    engine.setFeedbackLevel(if (rear) 0 else settings.feedbackLevel)
                }
                trackTrip(snap.gear, snap.speedKmh)
                guardLoad(dt)
                val st = engine.state
                _engineState.value = st
                val vLive = vehicleSource.isLive(store)
                val nLive = naviSource.isLive(store)
                _ui.update { if (it.vehicleLive != vLive || it.naviLive != nLive) it.copy(vehicleLive = vLive, naviLive = nLive) else it }
                delay(40)
            }
        }
    }

    // ---------------------------------------------------------------- CPU budget guard
    private var overBudgetSec = 0f
    private var underBudgetSec = 0f

    /**
     * Pleos budgets < 5% CPU for a non-navigation app while driving. The audio thread reports its own render load;
     * when synthesis alone stays above [LIGHT_ENTER_LOAD] for [LIGHT_ENTER_SEC] the engine drops to the light profile
     * (shared mid reverb, no melody doubler; crossfaded, no click) and comes back only after [LIGHT_EXIT_SEC] under
     * [LIGHT_EXIT_LOAD], so it never flaps. Remix mode has no synthesis and is left alone.
     */
    private fun guardLoad(dt: Float) {
        val load = output.load
        val synth = _ui.value.playing && mode == PlayMode.GENERATIVE
        val profile = engine.profile
        overBudgetSec = if (synth && profile == RenderProfile.FULL && load > LIGHT_ENTER_LOAD) overBudgetSec + dt else 0f
        underBudgetSec = if (synth && profile == RenderProfile.LIGHT && load < LIGHT_EXIT_LOAD) underBudgetSec + dt else 0f
        if (overBudgetSec >= LIGHT_ENTER_SEC) {
            overBudgetSec = 0f; engine.profile = RenderProfile.LIGHT
            Log.i(TAG, "engine load ${"%.1f".format(load * 100)}% over budget -> light profile")
        } else if (underBudgetSec >= LIGHT_EXIT_SEC) {
            underBudgetSec = 0f; engine.profile = RenderProfile.FULL
            Log.i(TAG, "engine load ${"%.1f".format(load * 100)}% -> full profile")
        }
        val light = engine.profile == RenderProfile.LIGHT
        _ui.update { if (it.light != light || kotlin.math.abs(it.engineLoad - load) > 0.002f) it.copy(light = light, engineLoad = load) else it }
    }

    private fun trackTrip(gear: Gear, speed: Float?) {
        val moving = gear == Gear.DRIVE || (gear == Gear.UNKNOWN && (speed ?: 0f) > 5f)
        val now = System.currentTimeMillis()
        if (moving) {
            if (!wasMoving && now - lastMovingAtMs > 5_000) {   // a new trip, not a brief N/R blip
                movingSinceMs = now
                _ui.update { it.copy(tripActive = true) }
                // opt-in: start playing when the car starts moving (off by default; never auto-plays on connection)
                if (settings.autoStartOnDrive && !_ui.value.playing) play()
            }
            lastMovingAtMs = now
        } else if (gear == Gear.PARK && _ui.value.tripActive && now - lastMovingAtMs < 10_000 && now - movingSinceMs > 45_000) {
            val summary = engine.summary()
            if (summary.durationSec > 30f) history.add(summary)   // nothing was played: no song to remember
            _ui.update { it.copy(lastSummary = summary, tripActive = false, history = history.all()) }
            engine.newTrip(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), store.roadName)
            updateMetadata()
        }
        wasMoving = moving
    }

    // ---------------------------------------------------------------- public API (UI)
    fun play() {
        if (_ui.value.playing) return
        if (!requestFocus()) { Log.w(TAG, "audio focus denied"); return }
        startForegroundSafely()
        output.start()
        session.isActive = true
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        _ui.update { it.copy(playing = true) }
        updateMetadata()
    }

    fun pause() {
        if (!_ui.value.playing) return
        output.stop()
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        _ui.update { it.copy(playing = false) }
        // paused: the notification stays but becomes dismissable
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) }
        stopForeground(STOP_FOREGROUND_DETACH)
        abandonFocus()
    }

    fun toggle() = if (_ui.value.playing) pause() else play()

    /** Hard stop: AUDIOFOCUS_LOSS, MediaSession onStop (3-finger gesture) and task removal. */
    fun stop() {
        pause()
        resumeOnGain = false
        session.isActive = false
        setPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

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
        engine.setFeedbackLevel(if (rearOccupiedApplied) 0 else level)
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
            scope.launch {
                delay(1500)
                if (trackPlayer !== p) return@launch
                val err = p.error
                if (err != null) {
                    // unreadable at start (USB removed, grant revoked): fall back instead of playing silence
                    Log.w(TAG, "remix track unreadable, back to generative: $err")
                    p.stop(); trackPlayer = null; mode = PlayMode.GENERATIVE; settings.remixTrackUri = null
                    _ui.update { it.copy(mode = mode, remixTrackTitle = null, remixError = err) }
                    updateMetadata()
                } else _ui.update { it.copy(remixTrackTitle = p.title, remixError = null) }
            }
        }
        updateMetadata()
    }

    fun currentSummary(): TripSummary = engine.summary()

    fun setAutoStart(on: Boolean) {
        settings.autoStartOnDrive = on
        _ui.update { it.copy(autoStart = on) }
    }

    // media-center artwork, regenerated only when title or genre changes
    private var artKey = ""
    private var artBitmap: android.graphics.Bitmap? = null
    private fun artworkFor(title: String, genre: Genre): android.graphics.Bitmap? {
        val key = "$title|$genre"
        if (key != artKey || artBitmap == null) { artBitmap = runCatching { ShareCard.artwork(title, genre) }.getOrNull(); artKey = key }
        return artBitmap
    }

    // ---------------------------------------------------------------- audio focus
    // Pleos audio-focus policy (Interaction Matrix v1.0): LOSS -> stop immediately and abandon focus;
    // LOSS_TRANSIENT (Gleo voice, call) -> stop immediately, resume only on GAIN; CAN_DUCK (navigation prompt) -> app-side attenuation.
    @Volatile private var resumeOnGain = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> suspendTransient()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> output.duckGain = 0.25f // about -12 dB; AudioOutput ramps the change
            AudioManager.AUDIOFOCUS_GAIN -> { output.duckGain = 1f; if (resumeOnGain) resumeFromTransient() }
        }
    }

    private fun suspendTransient() {
        if (!_ui.value.playing) return
        resumeOnGain = true
        output.stop()
        setPlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    private fun resumeFromTransient() {
        resumeOnGain = false
        if (!_ui.value.playing) return
        output.start()
        setPlaybackState(PlaybackStateCompat.STATE_PLAYING)
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
                override fun onStop() = stop()
                override fun onSkipToNext() = nextGenre()
                override fun onSkipToPrevious() { val all = Genre.values(); setGenre(all[(all.indexOf(_ui.value.genre) + all.size - 1) % all.size]) }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Genre.values().firstOrNull { it.name == mediaId }?.let { setGenre(it) }
                    play()
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    genreFromText(query)?.let { setGenre(it) }
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
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
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
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkFor(title, _ui.value.genre))
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
        // Gleo / adb / notification intents may be delivered with startForegroundService (API 31+: startForeground within 5 s)
        val external = intent?.action != null
        if (external && !_ui.value.playing) startForegroundSafely()
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT_GENRE -> nextGenre()
            // adb-friendly demo switch for headless/PT runs:
            //   adb shell am startservice -n io.drivesymphony.app/.audio.SymphonyService -a io.drivesymphony.action.DEMO --ez on true --es scenario story
            ACTION_DEMO -> setDemo(intent.getBooleanExtra("on", true), intent.getStringExtra("scenario") ?: settings.demoScenarioId)
            GLEO_START, GLEO_SET_GENRE, GLEO_HOW_IS_MY_DRIVING, GLEO_PAUSE -> handleGleoIntent(intent)
        }
        // Pleos media-app rule: keep the foreground notification current whenever the service is (re)started while playing.
        if (_ui.value.playing) startForegroundSafely()
        else if (external) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        return START_STICKY
    }

    /** Pleos media-app rule: when the task is swiped away -> stop media, stopSelf, then super. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ---------------------------------------------------------------- Gleo AI (assets/schema.json)
    /**
     * Gleo delivers a function call as an Intent whose action is the function name and whose "ACTION_PARAM" extra holds
     * the JSON arguments (Pleos app-integration guide, radio example). The reply object (functionName/status/message/data)
     * follows the same guide; its return channel is not documented publicly, so the reply is broadcast on [GLEO_CALLBACK]
     * and logged until the official channel is confirmed.
     */
    private fun handleGleoIntent(intent: Intent) {
        val param = runCatching { org.json.JSONObject(intent.getStringExtra(GLEO_PARAM_EXTRA) ?: "{}") }.getOrDefault(org.json.JSONObject())
        val genre = genreFromText(param.optString("genre"))
        when (intent.action) {
            GLEO_START -> { genre?.let { setGenre(it) }; play(); gleoReply(GLEO_START, "success", "${_ui.value.genre.label} 연주를 시작했어요") }
            GLEO_SET_GENRE -> if (genre != null) { setGenre(genre); gleoReply(GLEO_SET_GENRE, "success", "${genre.label}로 바꿨어요") } else gleoReply(GLEO_SET_GENRE, "fail", "장르를 알아듣지 못했어요. 로파이, 재즈, 앰비언트 중에 골라 주세요")
            GLEO_PAUSE -> { pause(); gleoReply(GLEO_PAUSE, "success", "연주를 멈췄어요") }
            GLEO_HOW_IS_MY_DRIVING -> {
                val st = engine.state
                val ev = st.lastEvent
                val flowPct = (st.flow * 100).toInt()
                val data = org.json.JSONObject().put("flow", flowPct).put("lastEvent", ev?.label ?: "").put("streakSec", st.streakSec.toInt())
                val msg = if (ev != null && ev.isPenalty && st.lastEventAgeSec < 10f) "방금 ${ev.label}이 있었어요. 지금 Flow ${flowPct}점이에요"
                          else {
                              val sec = st.streakSec.toInt()
                              val since = if (sec >= 60) "${sec / 60}분째" else "${sec}초째"
                              "지금 흐름 ${flowPct}점, $since 부드럽게 달리고 있어요"
                          }
                gleoReply(GLEO_HOW_IS_MY_DRIVING, "success", msg, data)
            }
        }
    }

    private fun gleoReply(functionName: String, status: String, message: String, data: org.json.JSONObject? = null) {
        val json = org.json.JSONObject().put("functionName", functionName).put("status", status).put("message", message)
        if (data != null) json.put("data", data.toString())
        Log.i(TAG, "gleo reply: $json")
        sendBroadcast(Intent(GLEO_CALLBACK).setPackage(packageName).putExtra("callback", json.toString()))
    }

    private fun genreFromText(text: String?): Genre? = GenreParser.parse(text)

    override fun onDestroy() {
        pause()
        controlJob?.cancel()
        scope.cancel()
        runCatching { vehicleSource.stop() }
        runCatching { naviSource.destroy() }
        runCatching { carGearSource.stop() }
        scenarioSource.destroy()
        trackPlayer?.stop()
        drivingState.stop()
        session.release()
        super.onDestroy()
    }

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
            .setOngoing(_ui.value.playing)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val BLOCK_FRAMES = 256
        /** light-profile guard: enter above 4% of one core for 3 s, leave below 2% for 60 s */
        const val LIGHT_ENTER_LOAD = 0.040f
        const val LIGHT_EXIT_LOAD = 0.020f
        const val LIGHT_ENTER_SEC = 3f
        const val LIGHT_EXIT_SEC = 60f
        const val ROOT_ID = "drive_symphony_root"
        const val CHANNEL_ID = "drive_symphony_playback"
        const val NOTIF_ID = 1001
        const val ACTION_PLAY = "io.drivesymphony.action.PLAY"
        const val ACTION_PAUSE = "io.drivesymphony.action.PAUSE"
        const val ACTION_TOGGLE = "io.drivesymphony.action.TOGGLE"
        const val ACTION_NEXT_GENRE = "io.drivesymphony.action.NEXT_GENRE"
        const val ACTION_DEMO = "io.drivesymphony.action.DEMO"
        // Gleo function names == intent actions (assets/schema.json)
        const val GLEO_START = "startSymphony"
        const val GLEO_SET_GENRE = "setGenre"
        const val GLEO_HOW_IS_MY_DRIVING = "howIsMyDriving"
        const val GLEO_PAUSE = "pauseSymphony"
        const val GLEO_PARAM_EXTRA = "ACTION_PARAM"
        const val GLEO_CALLBACK = "io.drivesymphony.GLEO_CALLBACK"
        private const val TAG = "SymphonyService"
    }
}
