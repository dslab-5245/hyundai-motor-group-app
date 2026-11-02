package io.drivesymphony.engine

import io.drivesymphony.engine.director.DriveEvent
import io.drivesymphony.engine.director.DrivingScorer
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.MusicDirector
import io.drivesymphony.engine.director.MusicParams
import io.drivesymphony.engine.director.ScoreState
import io.drivesymphony.engine.dsp.Crush
import io.drivesymphony.engine.dsp.DcBlock
import io.drivesymphony.engine.dsp.DelayLine
import io.drivesymphony.engine.dsp.Ducker
import io.drivesymphony.engine.dsp.GlueComp
import io.drivesymphony.engine.dsp.Limiter
import io.drivesymphony.engine.dsp.OnePole
import io.drivesymphony.engine.dsp.Reverb
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.dsp.SampleBank
import io.drivesymphony.engine.dsp.Sampler
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.TapeSag
import io.drivesymphony.engine.dsp.TapeSat
import io.drivesymphony.engine.dsp.TapeWobble
import io.drivesymphony.engine.dsp.clamp01
import io.drivesymphony.engine.dsp.dbToGain
import kotlin.math.cos
import kotlin.math.sin
import io.drivesymphony.engine.music.Arranger
import io.drivesymphony.engine.music.Drone
import io.drivesymphony.engine.music.BarContext
import io.drivesymphony.engine.music.Bass
import io.drivesymphony.engine.music.Clock
import io.drivesymphony.engine.music.DrumKit
import io.drivesymphony.engine.music.Drums
import io.drivesymphony.engine.music.KeyVoice
import io.drivesymphony.engine.music.LayeredVoice
import io.drivesymphony.engine.music.SampledKit
import io.drivesymphony.engine.music.EPiano
import io.drivesymphony.engine.music.HitKind
import io.drivesymphony.engine.music.Pad
import io.drivesymphony.engine.music.StepEvent
import io.drivesymphony.engine.music.StereoOut
import io.drivesymphony.engine.music.Texture
import io.drivesymphony.engine.theory.Chord
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.theory.Pitch
import io.drivesymphony.engine.theory.Progressions

/**
 * Synthesis cost profile. LIGHT keeps the composition, the lo-fi stage and the master tone, but renders one shared
 * (mid) reverb instead of two and drops the melody micro-doubler; the app switches to it when the audio thread's own
 * load passes the Pleos CPU budget. Switching crossfades over ~0.3 s on the audio thread, so it never clicks.
 */
enum class RenderProfile { FULL, LIGHT }

/** Snapshot for the UI (HUD-style glance screen, composer screen). Immutable; replaced per block. */
data class EngineState(
    val genre: Genre,
    val keyLabel: String,
    val chordLabel: String,
    val nextChordLabel: String,
    val bpm: Float,
    val bar: Int,
    val beatInBar: Float,
    val flow: Float,
    val instant: Float,
    val pulse: Float,
    val streakSec: Float,
    val moving: Boolean,
    val lastEvent: DriveEvent?,
    val lastEventAgeSec: Float,
    val density: Float,
    val bloom: Float,
    val layers: List<String>,
    val title: String,
    val elapsedSec: Float,
    val peak: Float,
    /** last penalty was read as an emergency manoeuvre (half penalty, short breath). */
    val graced: Boolean = false,
    /** rendering with the light (CPU-saving) profile. */
    val light: Boolean = false,
    /** tape sags so far (one per non-graced penalty): the audible "sink" gesture. */
    val sags: Int = 0,
)

data class TripSummary(
    val title: String,
    val genre: Genre,
    val keyLabel: String,
    val bpm: Float,
    val durationSec: Float,
    val bars: Int,
    val averageFlow: Float,
    val bestStreakSec: Float,
    val eventCounts: Map<DriveEvent, Int>,
    val bloomSec: Float,
)

/**
 * The Drive Symphony generative engine.
 *
 * Threading contract:
 *  - [update] is called from a control thread (20-50 Hz) with the latest driving signals.
 *  - [render] is called from the audio thread with an interleaved stereo float buffer.
 *  The two communicate through a single volatile [MusicParams] reference; no locks on the audio path.
 */
class SymphonyEngine(
    val sampleRate: Int = 48000,
    genre: Genre = Genre.LOFI,
    private val seed: Int = 1,
    hourOfDay: Int = 12,
    roadName: String? = null,
) {
    private val sr = sampleRate.toFloat()
    private var rng = Rng(seed)
    private var tripCount = 0
    private val scorer = DrivingScorer()
    private val director = MusicDirector(genre)

    @Volatile private var targetParams: MusicParams = MusicParams()
    @Volatile private var pendingGenre: Genre? = null
    /** Any thread. The audio thread crossfades toward it; see [RenderProfile]. */
    @Volatile var profile: RenderProfile = RenderProfile.FULL
    @Volatile var state: EngineState = EngineState(genre, "", "", "", 80f, 0, 0f, 0.6f, 0.6f, 0f, 0f, false, null, 999f, 1.5f, 0f, emptyList(), "", 0f, 0f)
        private set

    var genre: Genre = genre
        private set
    private var keyRoot = Progressions.pickKey(hourOfDay, rng)
    private var progA: List<Chord> = Progressions.pick(genre, keyRoot, rng)
    private var progB: List<Chord> = progA
    private var progression: List<Chord> = progA
    private var section = 0
    private var barsSinceGenre = 0
    private var cadenceBars = 0
    private val ctx = BarContext()
    private var chordIdx = 0
    private var chordBeatsLeft = progression[0].beats
    private var arranger = Arranger(genre, rng)
    private val clock = Clock(sampleRate)
    private var baseBpm = 80f   // the genre's tempo; the live tempo drags up to 10 % below it while the flow is low

    // Instruments. With the bundled sample bank (FluidR3_GM, MIT): Rhodes keys, vibraphone melody and sparkle, acoustic
    // bass (jazz, ambient) or fingered electric bass (lo-fi), standard / brush kits. Without it (resource missing) the
    // original synths play, so tests and the preview tool never depend on the asset. Levels follow SamplerDemoMain's
    // measurements so a bank swap does not move the mix.
    private val bank: SampleBank? = sampleBank
    private val ep: KeyVoice = bank?.get("ep")?.let { Sampler(sr, it, 6, seed + 1).apply { levelDb = 8f; humanizeCents = 2f } } ?: EPiano(sr, seed + 1)
    private val lead: KeyVoice = bank?.get("vibes")?.let { Sampler(sr, it, 3, seed + 2).apply { levelDb = 6f; humanizeCents = 2f } }
        ?: EPiano(sr, seed + 2, maxVoices = 3, bright = 1.4f).apply { tremoloDepth = 0.15f }
    private val bassSynth = Bass(sr, seed + 3)   // alone without the bank; the sub layer under the sampled basses with it
    private val bassAcoustic: KeyVoice = bank?.get("bass")?.let { LayeredVoice(Sampler(sr, it, 2, seed + 3).apply { levelDb = 4f }, bassSynth, 0.5f) } ?: bassSynth
    private val bassElectric: KeyVoice = bank?.get("fbass")?.let { LayeredVoice(Sampler(sr, it, 2, seed + 13).apply { levelDb = 4f }, bassSynth, 0.6f) } ?: bassAcoustic
    private var bass: KeyVoice = bassAcoustic
    private val pad = Pad(sr, seed + 4)
    private val drums: DrumKit = bank?.get("drums")?.let { SampledKit(sr, it, bank["brush"], sub = Drums(sr, seed + 5)) } ?: Drums(sr, seed + 5)
    private val texture = Texture(sr, seed + 6)
    private val sparkle: KeyVoice = bank?.get("vibes")?.let { Sampler(sr, it, 7, seed + 9).apply { levelDb = 7f } }
        ?: EPiano(sr, seed + 9, maxVoices = 7, bright = 1.8f).apply { tremoloDepth = 0.05f }   // reward arpeggios
    /** true when the sampled instruments are playing (UI / README evidence). */
    val sampled: Boolean get() = bank != null

    // master chain (stereo)
    private val lpL = Svf(sr); private val lpR = Svf(sr)
    private val crushL = Crush(sr); private val crushR = Crush(sr)
    private val wobL = TapeWobble(sr, seed + 7); private val wobR = TapeWobble(sr, seed + 8)
    private val revL = Reverb(sr, 0); private val revR = Reverb(sr, 23)
    private val limL = Limiter(sr); private val limR = Limiter(sr)
    private val dcL = DcBlock(); private val dcR = DcBlock()
    // musical gestures (see render()): tape sag on a penalty, kick-driven pumping, tape warmth + glue on the master
    private val sag = TapeSag(sr)
    private val duck = Ducker(sr)
    private val sat = TapeSat()
    private val glue = GlueComp(sr)
    private var sideAmt = 0f          // how much the kick pumps the other layers (lo-fi 1, others 0)
    private var lastPulse = 0f
    private var bloomAnnounced = false
    private var sagCount = 0
    // The stumble: a non-graced penalty mutes drums, hats and melody at once for at least STUMBLE_SEC; they may only
    // come back on a downbeat. Layer entrances in general are quantised to a bar and announced by a riser (armEntrance).
    private var grooveMuteUntilSample = 0L
    private var drumsEnterBar = -1
    private var hatsEnterBar = -1
    private var announcedBar = -1
    @Volatile private var sparkleRequest = 0   // 1 = signaled-turn reward (ascending), 2 = smooth-streak milestone (up and down)
    private val keysMix = StereoOut()          // keys, melody and sparkle: the layers that pan with the wheel
    private val smoothCut = OnePole(sr).apply { setTimeConstant(0.03f); reset(8000f) }
    private val smoothGain = OnePole(sr).apply { setTimeConstant(0.03f); reset(1f) }
    private val smoothRev = OnePole(sr).apply { setTimeConstant(0.1f); reset(0.25f) }
    private val smoothWidth = OnePole(sr).apply { setTimeConstant(0.1f); reset(0.8f) }
    private val smoothPanL = OnePole(sr).apply { setTimeConstant(0.2f); reset(0.70710677f) }
    private val smoothPanR = OnePole(sr).apply { setTimeConstant(0.2f); reset(0.70710677f) }
    private val smoothLight = OnePole(sr).apply { setTimeConstant(0.3f) }   // 0 = FULL .. 1 = LIGHT, audio thread
    private val mix = StereoOut()
    private val leadMix = StereoOut()
    private val leadDly = DelayLine(2048)   // micro-doubler: two short delays widen the melody without a chorus

    // per-layer gains (smoothed per block)
    private var gDrums = 0f; private var gHats = 0f; private var gEp = 0.8f; private var gBass = 0.8f; private var gPad = 0.6f; private var gLead = 0f; private var gTex = 0.3f

    // current bar events
    private var barEvents: List<StepEvent> = emptyList()
    // hits delayed by a sub-step offset (humanisation); fired sample-accurately inside render()
    private val pendKind = arrayOfNulls<HitKind>(PENDING_MAX)
    private val pendNote = IntArray(PENDING_MAX)
    private val pendVel = FloatArray(PENDING_MAX)
    private val pendLen = LongArray(PENDING_MAX)
    private val pendWait = IntArray(PENDING_MAX)
    private var pendCount = 0
    private var currentChord: Chord = progression[0]
    private var nextChord: Chord = progression[1 % progression.size]
    private val drone = Drone(sr)
    private var gDrone = 0f
    private var rainSamples = 0L
    private var rainy = false
    @Volatile private var rewardUntilBar = -1   // signaled-turn reward: one extra layer until this bar
    private var densityEff = 1.5f

    // trip stats
    private var elapsedSamples = 0L
    private var flowAccum = 0.0
    private var flowSamples = 0L
    private var bestStreak = 0f
    private var bloomSamples = 0L
    private val eventCounts = HashMap<DriveEvent, Int>()
    private var lastCountedEvent: DriveEvent? = null
    private var lastCountedAt = -1.0
    private var roadName: String? = roadName
    private var title: String = TripTitles.make(roadName, hourOfDay, keyRoot, genre)
    private var lastSignals: DrivingSignals = DrivingSignals(0.0, hourOfDay = hourOfDay)
    private var wasMoving = false
    private var peak = 0f

    val scoreState: ScoreState get() = scorer.state
    val params: MusicParams get() = targetParams

    /** Publishes the current state without rendering: for the control thread while playback is stopped (no audio thread then). */
    fun publishIdleState() { publishState(targetParams, scorer.state) }

    /** Control thread. */
    fun update(signals: DrivingSignals, dtSec: Float) {
        lastSignals = signals
        if (signals.roadName != null && signals.roadName != roadName) {
            roadName = signals.roadName
            title = TripTitles.make(roadName, signals.hourOfDay, keyRoot, genre, rainy)
        }
        val score = scorer.update(signals, dtSec)
        val ev = score.lastEvent
        // lastEventAgeSec is exactly 0 only on the update that fired the event
        if (ev != null && score.lastEventAgeSec < 1e-4f && signals.timeSec != lastCountedAt) {
            eventCounts[ev] = (eventCounts[ev] ?: 0) + 1
            lastCountedEvent = ev
            lastCountedAt = signals.timeSec
        }
        if (score.streakSec > bestStreak) bestStreak = score.streakSec
        // signaled turn: an immediate, audible reward -> one extra layer for the next two bars
        if (ev == DriveEvent.SIGNALED_TURN && score.lastEventAgeSec < 1e-4f) { rewardUntilBar = clock.bar + 2; sparkleRequest = 1 }
        if (ev == DriveEvent.SMOOTH_STREAK && score.lastEventAgeSec < 1e-4f) sparkleRequest = 2
        targetParams = director.update(score, signals, dtSec)
        wasMoving = score.moving
    }

    /** Requests a genre change; applied at the next bar boundary on the audio thread. */
    fun setGenre(g: Genre) { if (g != genre) pendingGenre = g }

    /** 0 gentle / 1 standard / 2 strong. */
    fun setFeedbackLevel(level: Int) { director.intensity = when (level.coerceIn(0, 2)) { 0 -> 0.6f; 1 -> 1f; else -> 1.4f } }

    /** Starts a fresh composition (new key/progression) without changing genre; used at trip start. */
    fun newTrip(hourOfDay: Int, roadName: String?) {
        tripCount++
        // "이 길의 테마": the same road always gets the same key, progression and motif seed; unknown roads stay fresh.
        rng = Rng(if (!roadName.isNullOrBlank()) (roadName.hashCode() xor 0x5bd1e995) else seed + tripCount * 7919)
        keyRoot = Progressions.pickKey(hourOfDay, rng)
        this.roadName = roadName
        scorer.reset()
        elapsedSamples = 0; flowAccum = 0.0; flowSamples = 0; bestStreak = 0f; bloomSamples = 0; eventCounts.clear()
        rainSamples = 0; rainy = false; rewardUntilBar = -1
        cadenceBars = 1      // one resolution bar first, then ...
        pendingGenre = genre // ... applyGenre at the following bar -> new progression in the new key
    }

    fun setRoadName(name: String?) { roadName = name; title = TripTitles.make(name, lastSignals.hourOfDay, keyRoot, genre, rainy) }

    private fun applyGenre(g: Genre) {
        genre = g
        director.genre = g
        val sections = Progressions.pickSections(g, keyRoot, rng)
        progA = sections.first; progB = sections.second
        progression = progA
        section = 0
        barsSinceGenre = 0
        chordIdx = 0
        chordBeatsLeft = progression[0].beats
        arranger = Arranger(g, rng)
        baseBpm = g.bpmMin + rng.nextFloat() * (g.bpmMax - g.bpmMin)
        clock.bpm = baseBpm
        clock.swing = when (g) { Genre.LOFI -> 0.57f + rng.nextFloat() * 0.04f; Genre.JAZZ -> 0.64f; Genre.AMBIENT -> 0.5f }
        drums.brushed = g == Genre.JAZZ
        bass.allOff(); bass = if (g == Genre.LOFI) bassElectric else bassAcoustic
        pad.cutoffBase = when (g) { Genre.AMBIENT -> 420f; Genre.LOFI -> 520f; Genre.JAZZ -> 700f }
        ep.allOff(); lead.allOff(); pad.allOff(); sparkle.allOff()
        pendCount = 0
        drumsEnterBar = -1; hatsEnterBar = -1; announcedBar = -1; grooveMuteUntilSample = 0L
        title = TripTitles.make(roadName, lastSignals.hourOfDay, keyRoot, g, rainy)
    }

    init { applyGenre(genre) }

    private fun onBar(bar: Int) {
        val p = targetParams
        if (cadenceBars > 0) {
            // trip ended: resolve on the tonic for one bar before the next composition starts
            cadenceBars--
            val tonic = progression[0]
            currentChord = tonic; nextChord = tonic
            barEvents = arranger.cadence(tonic, p)
            return
        }
        var first = bar == 0
        pendingGenre?.let { pendingGenre = null; applyGenre(it); first = true }
        if (!first) {
            barsSinceGenre++
            val newSection = (barsSinceGenre / SECTION_BARS) % 4   // A A B A
            if (newSection != section) {
                section = newSection
                progression = if (section == 2) progB else progA
                chordIdx = 0
                chordBeatsLeft = progression[0].beats
            } else {
                chordBeatsLeft -= 4
                if (chordBeatsLeft <= 0) {
                    chordIdx = (chordIdx + 1) % progression.size
                    chordBeatsLeft = progression[chordIdx].beats
                }
            }
        }
        currentChord = progression[chordIdx]
        nextChord = progression[(chordIdx + 1) % progression.size]
        ctx.bar = barsSinceGenre
        ctx.barInSection = barsSinceGenre % SECTION_BARS
        ctx.section = section
        ctx.intro = barsSinceGenre < 2
        ctx.fill = ctx.barInSection == SECTION_BARS - 1
        drone.setRoot(currentChord.rootPc)
        // tempo follows the flow per bar (takes effect from the next bar): erratic driving drags, smooth driving sits at the genre's tempo
        clock.bpm = baseBpm * (0.90f + 0.10f * clamp01(p.flow))
        // quantised entrances: a layer armed during the previous bar comes in on this downbeat at full gain
        if (drumsEnterBar in 0..bar) { gDrums = drumsTarget(p); drumsEnterBar = -1; if (genre == Genre.LOFI && gDrums > 0.3f) drums.hat(0.5f, true) }
        if (hatsEnterBar in 0..bar) { gHats = hatsTarget(p); hatsEnterBar = -1 }
        val pr = if (bar < rewardUntilBar) p.copy(density = minOf(3f, p.density + 0.6f)) else p
        barEvents = arranger.bar(currentChord, nextChord, pr, ctx)
        // section-closing riser (lo-fi, full groove only): a noise swell over the fill bar into the next downbeat
        if (genre == Genre.LOFI && ctx.fill && !ctx.intro && pr.density >= 1.8f) texture.riser(clock.samplesForSteps(16f) / sr)
        // melody arrival: one soft swell in the bar the lead first opens (re-armed once the bloom has faded)
        if (p.bloom > 0.5f && !bloomAnnounced) { bloomAnnounced = true; if (genre != Genre.AMBIENT) texture.pulse() }
        if (p.bloom < 0.3f) bloomAnnounced = false
    }

    private fun densityOf(p: MusicParams) = if (clock.bar < rewardUntilBar) minOf(3f, p.density + 0.6f) else p.density
    private fun drumsTarget(p: MusicParams) = clamp01((densityOf(p) - 0.7f) / 0.6f)
    private fun hatsTarget(p: MusicParams) = clamp01((densityOf(p) - 1.6f) / 0.6f)

    /**
     * Decides the bar on which a waiting layer enters (the next downbeat, or the one after when that is less than
     * 0.6 s away) and starts one riser that ends exactly there, so the entrance is announced instead of faded in.
     */
    private fun armEntrance(): Int {
        val barSec = clock.stepSamples * 16f / sr
        val toBar = (4f - clock.beatInBar) / 4f * barSec
        val bars = if (toBar < 0.6f) 2 else 1
        val enter = clock.bar + bars
        if (announcedBar != enter) { announcedBar = enter; texture.riser(toBar + (bars - 1) * barSec) }
        return enter
    }

    /** Reward arpeggio on the chord's safe tones, one 16th apart: 5 notes up (signaled turn) or 7 up-and-down (milestone). */
    private fun playSparkle(kind: Int) {
        val tones = currentChord.safeTones()
        val base = 72 + currentChord.rootPc
        val count = if (kind == 2) 7 else 5
        val step = clock.stepSamples.toInt().coerceAtLeast(1)
        val len = clock.samplesForSteps(3f)
        var i = 0
        while (i < count && pendCount < PENDING_MAX) {
            val up = if (kind == 2 && i >= 4) 6 - i else i
            val note = base + tones[up % tones.size] + 12 * (up / tones.size)
            val j = pendCount++
            pendKind[j] = HitKind.SPARKLE; pendNote[j] = note; pendVel[j] = 0.45f + 0.08f * i; pendLen[j] = len
            pendWait[j] = i * step + 1
            i++
        }
    }

    private fun onStep(step: Int) {
        val p = targetParams
        for (e in barEvents) {
            if (e.step != step) continue
            val len = clock.samplesForSteps(e.lenSteps)
            if (e.offsetSteps > 0f && e.kind != HitKind.PAD_CHORD && pendCount < PENDING_MAX) {
                val i = pendCount++
                pendKind[i] = e.kind; pendNote[i] = e.note; pendVel[i] = e.vel; pendLen[i] = len
                pendWait[i] = (clock.stepSamples * e.offsetSteps).toInt().coerceAtLeast(1)
                continue
            }
            fire(e.kind, e.note, e.vel, len, p)
        }
        // pad chords: gather all PAD_CHORD notes on this step and send as one chord
        var count = 0
        for (e in barEvents) if (e.step == step && e.kind == HitKind.PAD_CHORD) count++
        if (count > 0) {
            val notes = IntArray(count)
            var i = 0
            var len = 16f
            for (e in barEvents) if (e.step == step && e.kind == HitKind.PAD_CHORD) { notes[i++] = e.note; len = e.lenSteps }
            pad.chord(notes, clock.samplesForSteps(len))
        }
        // blinker: a soft rim tick on the beat while the turn signal is on (musical, low level)
        if (p.blinker && step % 4 == 0 && genre != Genre.AMBIENT) drums.rim(0.25f)
    }

    /** Called once per sample while delayed hits are pending; fires those whose wait reached zero. */
    private fun tickPending(p: MusicParams) {
        var i = 0
        while (i < pendCount) {
            if (--pendWait[i] <= 0) {
                fire(pendKind[i]!!, pendNote[i], pendVel[i], pendLen[i], p)
                pendCount--
                if (i != pendCount) { // swap-remove
                    pendKind[i] = pendKind[pendCount]; pendNote[i] = pendNote[pendCount]; pendVel[i] = pendVel[pendCount]; pendLen[i] = pendLen[pendCount]; pendWait[i] = pendWait[pendCount]
                }
            } else i++
        }
    }

    private fun fire(kind: HitKind, note: Int, vel: Float, len: Long, p: MusicParams) {
        run {
            val e = kind
            when (e) {
                HitKind.KICK -> if (gDrums > 0.02f) { drums.kick(vel); if (sideAmt > 0f) duck.hit() }
                HitKind.SNARE -> if (gDrums > 0.02f) drums.snare(vel)
                HitKind.HAT_C -> if (gHats > 0.02f) drums.hat(vel, false)
                HitKind.HAT_O -> if (gHats > 0.02f) drums.hat(vel, true)
                HitKind.RIDE -> if (gDrums > 0.02f) drums.ride(vel)
                HitKind.SHAKER -> drums.shaker(vel)
                HitKind.RIM -> if (gHats > 0.02f) drums.rim(vel)
                HitKind.EP -> ep.noteOn(note, vel, len)
                HitKind.BASS -> bass.noteOn(note, vel, len)
                HitKind.LEAD -> if (p.bloom > 0.3f) lead.noteOn(note, vel * p.bloom, len)
                HitKind.PAD_CHORD -> { /* pad chords are grouped in onStep */ }
                HitKind.PULSE -> texture.pulse()
                HitKind.SPARKLE -> sparkle.noteOn(note, vel, len)
            }
        }
    }

    /** Audio thread. Writes interleaved stereo into [out] for [frames] frames. */
    fun render(out: FloatArray, frames: Int) {
        val p = targetParams
        val score = scorer.state
        // Penalty gesture: a new, non-graced penalty pulse sags the tape once (pitch and tempo dip for ~0.35 s, then
        // snap back). Belt reminders (pulse 0.15) and graced manoeuvres (half pulse) never trigger it.
        if (p.pulse > lastPulse + 0.3f && p.pulse >= 0.45f && !score.graced) {
            val k = when (genre) { Genre.LOFI -> 1f; Genre.JAZZ -> 0.8f; Genre.AMBIENT -> 0.6f }
            sag.trigger((0.22f + 0.25f * clamp01(p.pulse)) * k, 0.45f + 0.15f * p.pulse)
            sagCount++
            // the stumble: groove and melody stop now and may only return on a downbeat (see the gains below)
            grooveMuteUntilSample = clock.samplePos + (STUMBLE_SEC * sr).toLong()
            drumsEnterBar = -1; hatsEnterBar = -1
        }
        lastPulse = p.pulse
        val sq = sparkleRequest
        if (sq != 0) { sparkleRequest = 0; playSparkle(sq) }
        sideAmt = if (genre == Genre.LOFI) 1f else 0f
        sat.drive = when (genre) { Genre.LOFI -> 1.5f; Genre.JAZZ -> 1.25f; Genre.AMBIENT -> 1.15f }
        // Layer gains follow density continuously, with two musical rules: a drop is immediate (heard mid-bar), an
        // entrance from silence waits for a downbeat and is announced by a riser; during a stumble the groove is held off.
        val d = densityOf(p)
        densityEff = d
        val muted = clock.samplePos < grooveMuteUntilSample
        var tDrums = drumsTarget(p)
        var tHats = hatsTarget(p)
        var tLead = p.bloom
        if (muted) { tDrums = 0f; tHats = 0f; tLead = 0f; drumsEnterBar = -1; hatsEnterBar = -1 }
        else if (genre != Genre.AMBIENT) {
            if (tDrums > 0.3f && gDrums < 0.08f) { if (drumsEnterBar < 0) drumsEnterBar = armEntrance(); tDrums = 0f }
            if (tHats > 0.3f && gHats < 0.08f) { if (hatsEnterBar < 0) hatsEnterBar = armEntrance(); tHats = 0f }
        }
        val tPad = when (genre) { Genre.AMBIENT -> 0.75f; Genre.JAZZ -> 0.35f; Genre.LOFI -> 0.5f }
        val tEp = when (genre) { Genre.AMBIENT -> 0.45f; else -> 0.8f }
        val tBass = when (genre) { Genre.AMBIENT -> 0.55f; else -> 0.85f }
        val k = 0.25f
        val kf = 0.5f   // falls are twice as fast as rises
        gDrums += (tDrums - gDrums) * (if (tDrums < gDrums) kf else k)
        gHats += (tHats - gHats) * (if (tHats < gHats) kf else k)
        gLead += (tLead - gLead) * (if (tLead < gLead) kf else k)
        gPad += (tPad - gPad) * k; gEp += (tEp - gEp) * k; gBass += (tBass - gBass) * k
        gTex += ((0.25f + p.crackle * 0.5f) - gTex) * k
        gDrone += (p.drone * (when (genre) { Genre.JAZZ -> 0.5f; Genre.AMBIENT -> 1.1f; Genre.LOFI -> 0.85f }) - gDrone) * k

        clock.advance(frames, ::onBar, ::onStep)

        lpL.set(p.cutoffHz, 0.05f); lpR.set(p.cutoffHz, 0.05f)
        crushL.amount = p.crush; crushR.amount = p.crush
        wobL.depth = p.wobble; wobR.depth = p.wobble
        revL.roomSize = if (genre == Genre.AMBIENT) 0.9f else 0.8f; revR.roomSize = revL.roomSize
        val targetGain = dbToGain(p.gainDb)
        val drumsGain = 0.9f * (if (genre == Genre.JAZZ) 0.75f else 1f)
        // light profile crossfade; when coming back to FULL the idle right reverb / doubler must not replay stale audio
        val lightTarget = if (profile == RenderProfile.LIGHT) 1f else 0f
        if (lightTarget == 0f && smoothLight.value > 0.999f) { revR.clear(); leadDly.clear() }
        // musical pan from steering: the keys, the melody and the sparkle move with the wheel (equal-power gains computed
        // once per block, smoothed per sample); bass, drums and pad stay in place so the groove never leans
        val pp = (p.pan.coerceIn(-1f, 1f) * 0.5f + 1f) * 0.25f * 3.1415927f
        val panL = cos(pp); val panR = sin(pp)
        var blockPeak = 0f
        var o = 0
        for (i in 0 until frames) {
            if (pendCount > 0) tickPending(p)
            val lt = smoothLight.process(lightTarget)
            mix.l = 0f; mix.r = 0f
            val du = duck.process() * sideAmt   // kick-driven pumping: pads and keys breathe with the groove
            keysMix.l = 0f; keysMix.r = 0f
            ep.render(keysMix, gEp * 0.55f * (1f - 0.3f * du))
            sparkle.render(keysMix, 0.5f)
            leadMix.l = 0f; leadMix.r = 0f
            lead.render(leadMix, gLead * 0.4f * (1f - 0.2f * du))
            if (lt < 0.999f) {
                leadDly.write((leadMix.l + leadMix.r) * 0.5f)
                val dbl = 0.3f * (1f - lt)
                keysMix.l += leadMix.l + leadDly.readInt(430) * dbl
                keysMix.r += leadMix.r + leadDly.readInt(290) * dbl
            } else { keysMix.l += leadMix.l; keysMix.r += leadMix.r }
            val gl = smoothPanL.process(panL); val gr = smoothPanR.process(panR)
            mix.l += keysMix.l * (0.55f + 0.6f * gl)
            mix.r += keysMix.r * (0.55f + 0.6f * gr)
            bass.render(mix, gBass * 0.7f * (1f - 0.15f * du))
            drone.render(mix, gDrone * 0.32f * (1f - 0.4f * du))
            pad.render(mix, gPad * 0.5f * (1f - 0.45f * du))
            drums.render(mix, drumsGain * (if (gDrums < 0.02f) 0f else 1f) * (0.4f + 0.6f * gDrums))
            texture.render(mix, p.crackle * gTex, p.rain, 0.8f)

            var l = mix.l
            var r = mix.r

            // lo-fi stage
            l = lpL.process(l); r = lpR.process(r)
            l = crushL.process(l); r = crushR.process(r)
            l = wobL.process(l); r = wobR.process(r)
            sag.process(l, r); l = sag.outL; r = sag.outR   // pre-reverb: the tail stays in real time and masks the snap back

            // reverb send + stereo width (LIGHT: one reverb on the mid signal feeds both channels)
            val rv = smoothRev.process(p.reverbMix)
            val wl: Float; val wr: Float
            if (lt < 0.001f) { wl = revL.process(l); wr = revR.process(r) }
            else if (lt > 0.999f) { wl = revL.process((l + r) * 0.5f); wr = wl }
            else { wl = revL.process(l + (r - l) * 0.5f * lt); wr = revR.process(r) * (1f - lt) + wl * lt }
            l += wl * rv; r += wr * rv
            val w = smoothWidth.process(p.width)
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * w
            l = mid + side; r = mid - side

            // master: tape warmth -> glue (at a fixed programme level, so the driving trim below is heard in full)
            // -> the director's trim (the "sink") -> DC block -> safety limiter (mostly idle at ~ -14 dBFS RMS)
            l = sat.process(l * 0.72f); r = sat.process(r * 0.72f)
            val al = if (l < 0f) -l else l
            val ar = if (r < 0f) -r else r
            val gc = glue.gainFor(if (al > ar) al else ar) * smoothGain.process(targetGain)
            l = limL.process(dcL.process(l * gc))
            r = limR.process(dcR.process(r * gc))
            out[o++] = l; out[o++] = r
            val a = if (l < 0f) -l else l
            if (a > blockPeak) blockPeak = a
            val b = if (r < 0f) -r else r
            if (b > blockPeak) blockPeak = b
        }
        peak = blockPeak
        elapsedSamples += frames
        if (score.moving) { flowAccum += score.flow.toDouble() * frames; flowSamples += frames }
        if (p.bloom > 0.5f) bloomSamples += frames
        if (p.rain > 0.5f) rainSamples += frames
        val r = elapsedSamples > sr * 20 && rainSamples > elapsedSamples * 0.3
        if (r != rainy) { rainy = r; title = TripTitles.make(roadName, lastSignals.hourOfDay, keyRoot, genre, rainy) }
        // publish at most every ~1024 samples (~21 ms): the UI polls at 25 Hz, and this keeps per-block allocation off the audio thread
        samplesSincePublish += frames
        if (samplesSincePublish >= PUBLISH_INTERVAL) { samplesSincePublish = 0; publishState(p, score) }
    }

    private var samplesSincePublish = PUBLISH_INTERVAL   // first render publishes immediately

    private fun publishState(p: MusicParams, score: ScoreState) {
        val layersActive = ArrayList<String>(8)   // local: publishState runs on the audio thread, or on the control thread while idle
        layersActive.add("pad")
        if (gBass > 0.05f) layersActive.add("bass")
        if (gEp > 0.05f) layersActive.add("keys")
        if (gDrums > 0.05f) layersActive.add("drums")
        if (gHats > 0.05f) layersActive.add("hats")
        if (gLead > 0.1f) layersActive.add("melody")
        if (p.rain > 0.3f) layersActive.add("rain")
        if (p.crackle > 0.4f) layersActive.add("vinyl")
        if (gDrone > 0.25f) layersActive.add("drone")
        state = EngineState(
            genre = genre,
            keyLabel = Pitch.name(keyRoot),
            chordLabel = currentChord.label,
            nextChordLabel = nextChord.label,
            bpm = clock.bpm,
            bar = clock.bar,
            beatInBar = clock.beatInBar,
            flow = score.flow,
            instant = score.instant,
            pulse = score.penaltyPulse,
            streakSec = score.streakSec,
            moving = score.moving,
            lastEvent = score.lastEvent,
            lastEventAgeSec = score.lastEventAgeSec,
            graced = score.graced,
            light = smoothLight.value > 0.5f,
            density = densityEff,
            bloom = p.bloom,
            layers = layersActive,
            title = title,
            elapsedSec = elapsedSamples / sr,
            peak = peak,
            sags = sagCount,
        )
    }

    fun summary(): TripSummary = TripSummary(
        title = title,
        genre = genre,
        keyLabel = Pitch.name(keyRoot),
        bpm = clock.bpm,
        durationSec = elapsedSamples / sr,
        bars = clock.bar,
        averageFlow = if (flowSamples > 0) (flowAccum / flowSamples).toFloat() else scorer.state.flow,
        bestStreakSec = bestStreak,
        eventCounts = HashMap(eventCounts),
        bloomSec = bloomSamples / sr,
    )

    fun isParked(): Boolean = lastSignals.gear == Gear.PARK

    companion object {
        /** The bundled sample bank, loaded once per process and shared by every engine (4.8 MB of PCM). */
        val sampleBank: SampleBank? by lazy { SampleBank.loadDefault() }
        private const val PUBLISH_INTERVAL = 1024
        private const val SECTION_BARS = 8
        private const val PENDING_MAX = 48
        /** Minimum time the groove stays out after a non-graced penalty (it then waits for a downbeat). */
        private const val STUMBLE_SEC = 1.2f
    }
}

/** Trip title: time of day + road name + key/genre, e.g. "저녁의 올림픽대로 (Bb Lo-fi)". */
object TripTitles {
    fun make(roadName: String?, hourOfDay: Int, keyRoot: Int, genre: Genre, rainy: Boolean = false): String {
        val when_ = when (hourOfDay) {
            in 0..4 -> "새벽의"
            in 5..9 -> "아침의"
            in 10..15 -> "낮의"
            in 16..19 -> "저녁의"
            else -> "밤의"
        }
        val road = roadName?.takeIf { it.isNotBlank() } ?: "이름 없는 길"
        return "${if (rainy) "비 오는 " else ""}$when_ $road (${Pitch.name(keyRoot)} ${genre.label})"
    }

}
