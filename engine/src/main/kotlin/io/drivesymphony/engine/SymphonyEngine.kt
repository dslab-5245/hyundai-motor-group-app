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
import io.drivesymphony.engine.dsp.Limiter
import io.drivesymphony.engine.dsp.OnePole
import io.drivesymphony.engine.dsp.Reverb
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.TapeWobble
import io.drivesymphony.engine.dsp.clamp01
import io.drivesymphony.engine.dsp.dbToGain
import kotlin.math.cos
import kotlin.math.sin
import io.drivesymphony.engine.music.Arranger
import io.drivesymphony.engine.music.BarContext
import io.drivesymphony.engine.music.Bass
import io.drivesymphony.engine.music.Clock
import io.drivesymphony.engine.music.Drums
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
    seed: Int = 1,
    hourOfDay: Int = 12,
    roadName: String? = null,
) {
    private val sr = sampleRate.toFloat()
    private val rng = Rng(seed)
    private val scorer = DrivingScorer()
    private val director = MusicDirector(genre)

    @Volatile private var targetParams: MusicParams = MusicParams()
    @Volatile private var pendingGenre: Genre? = null
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

    // instruments
    private val ep = EPiano(sr, seed + 1)
    private val lead = EPiano(sr, seed + 2, maxVoices = 3, bright = 1.4f).apply { tremoloDepth = 0.15f }
    private val bass = Bass(sr, seed + 3)
    private val pad = Pad(sr, seed + 4)
    private val drums = Drums(sr, seed + 5)
    private val texture = Texture(sr, seed + 6)

    // master chain (stereo)
    private val lpL = Svf(sr); private val lpR = Svf(sr)
    private val crushL = Crush(sr); private val crushR = Crush(sr)
    private val wobL = TapeWobble(sr, seed + 7); private val wobR = TapeWobble(sr, seed + 8)
    private val revL = Reverb(sr, 0); private val revR = Reverb(sr, 23)
    private val limL = Limiter(sr); private val limR = Limiter(sr)
    private val dcL = DcBlock(); private val dcR = DcBlock()
    private val smoothCut = OnePole(sr).apply { setTimeConstant(0.03f); reset(8000f) }
    private val smoothGain = OnePole(sr).apply { setTimeConstant(0.03f); reset(1f) }
    private val smoothRev = OnePole(sr).apply { setTimeConstant(0.1f); reset(0.25f) }
    private val smoothWidth = OnePole(sr).apply { setTimeConstant(0.1f); reset(0.8f) }
    private val smoothPan = OnePole(sr).apply { setTimeConstant(0.2f) }
    private val mix = StereoOut()
    private val leadMix = StereoOut()
    private val leadDly = DelayLine(2048)   // micro-doubler: two short delays widen the melody without a chorus

    // per-layer gains (smoothed per block)
    private var gDrums = 0f; private var gHats = 0f; private var gEp = 0.8f; private var gBass = 0.8f; private var gPad = 0.6f; private var gLead = 0f; private var gTex = 0.3f

    // current bar events
    private var barEvents: List<StepEvent> = emptyList()
    private var currentChord: Chord = progression[0]
    private var nextChord: Chord = progression[1 % progression.size]
    private val layersActive = ArrayList<String>(8)

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

    /** Control thread. */
    fun update(signals: DrivingSignals, dtSec: Float) {
        lastSignals = signals
        if (signals.roadName != null && signals.roadName != roadName) {
            roadName = signals.roadName
            title = TripTitles.make(roadName, signals.hourOfDay, keyRoot, genre)
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
        targetParams = director.update(score, signals, dtSec)
        wasMoving = score.moving
    }

    /** Requests a genre change; applied at the next bar boundary on the audio thread. */
    fun setGenre(g: Genre) { if (g != genre) pendingGenre = g }

    /** 0 gentle / 1 standard / 2 strong. */
    fun setFeedbackLevel(level: Int) { director.intensity = when (level.coerceIn(0, 2)) { 0 -> 0.6f; 1 -> 1f; else -> 1.4f } }

    /** Starts a fresh composition (new key/progression) without changing genre; used at trip start. */
    fun newTrip(hourOfDay: Int, roadName: String?) {
        keyRoot = Progressions.pickKey(hourOfDay, rng)
        this.roadName = roadName
        scorer.reset()
        elapsedSamples = 0; flowAccum = 0.0; flowSamples = 0; bestStreak = 0f; bloomSamples = 0; eventCounts.clear()
        cadenceBars = 1      // one resolution bar first, then ...
        pendingGenre = genre // ... applyGenre at the following bar -> new progression in the new key
    }

    fun setRoadName(name: String?) { roadName = name; title = TripTitles.make(name, lastSignals.hourOfDay, keyRoot, genre) }

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
        clock.bpm = g.bpmMin + rng.nextFloat() * (g.bpmMax - g.bpmMin)
        clock.swing = when (g) { Genre.LOFI -> 0.57f + rng.nextFloat() * 0.04f; Genre.JAZZ -> 0.64f; Genre.AMBIENT -> 0.5f }
        drums.brushed = g == Genre.JAZZ
        pad.cutoffBase = when (g) { Genre.AMBIENT -> 420f; Genre.LOFI -> 520f; Genre.JAZZ -> 700f }
        ep.allOff(); lead.allOff(); pad.allOff()
        title = TripTitles.make(roadName, lastSignals.hourOfDay, keyRoot, g)
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
        barEvents = arranger.bar(currentChord, nextChord, p, ctx)
    }

    private fun onStep(step: Int) {
        val p = targetParams
        for (e in barEvents) {
            if (e.step != step) continue
            val len = clock.samplesForSteps(e.lenSteps)
            when (e.kind) {
                HitKind.KICK -> if (gDrums > 0.02f) drums.kick(e.vel)
                HitKind.SNARE -> if (gDrums > 0.02f) drums.snare(e.vel)
                HitKind.HAT_C -> if (gHats > 0.02f) drums.hat(e.vel, false)
                HitKind.HAT_O -> if (gHats > 0.02f) drums.hat(e.vel, true)
                HitKind.RIDE -> if (gDrums > 0.02f) drums.ride(e.vel)
                HitKind.SHAKER -> drums.shaker(e.vel)
                HitKind.RIM -> if (gHats > 0.02f) drums.rim(e.vel)
                HitKind.EP -> ep.noteOn(e.note, e.vel, len)
                HitKind.BASS -> bass.noteOn(e.note, e.vel, len)
                HitKind.LEAD -> if (p.bloom > 0.3f) lead.noteOn(e.note, e.vel * p.bloom, len)
                HitKind.PAD_CHORD -> { /* handled as a group below */ }
                HitKind.PULSE -> texture.pulse()
            }
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

    /** Audio thread. Writes interleaved stereo into [out] for [frames] frames. */
    fun render(out: FloatArray, frames: Int) {
        val p = targetParams
        // layer gains follow density continuously (so a dip is heard mid-bar, not only at the next bar)
        val d = p.density
        val tDrums = clamp01((d - 0.7f) / 0.6f)
        val tHats = clamp01((d - 1.6f) / 0.6f)
        val tLead = p.bloom
        val tPad = when (genre) { Genre.AMBIENT -> 0.75f; Genre.JAZZ -> 0.35f; Genre.LOFI -> 0.5f }
        val tEp = when (genre) { Genre.AMBIENT -> 0.45f; else -> 0.8f }
        val tBass = when (genre) { Genre.AMBIENT -> 0.55f; else -> 0.85f }
        val k = 0.25f
        gDrums += (tDrums - gDrums) * k; gHats += (tHats - gHats) * k; gLead += (tLead - gLead) * k
        gPad += (tPad - gPad) * k; gEp += (tEp - gEp) * k; gBass += (tBass - gBass) * k
        gTex += ((0.25f + p.crackle * 0.5f) - gTex) * k

        clock.advance(frames, ::onBar, ::onStep)

        lpL.set(p.cutoffHz, 0.05f); lpR.set(p.cutoffHz, 0.05f)
        crushL.amount = p.crush; crushR.amount = p.crush
        wobL.depth = p.wobble; wobR.depth = p.wobble
        revL.roomSize = if (genre == Genre.AMBIENT) 0.9f else 0.8f; revR.roomSize = revL.roomSize
        val targetGain = dbToGain(p.gainDb)
        val drumsGain = 0.9f * (if (genre == Genre.JAZZ) 0.75f else 1f)
        var blockPeak = 0f
        var o = 0
        for (i in 0 until frames) {
            mix.l = 0f; mix.r = 0f
            ep.render(mix, gEp * 0.55f)
            leadMix.l = 0f; leadMix.r = 0f
            lead.render(leadMix, gLead * 0.4f)
            leadDly.write((leadMix.l + leadMix.r) * 0.5f)
            mix.l += leadMix.l + leadDly.readInt(430) * 0.3f
            mix.r += leadMix.r + leadDly.readInt(290) * 0.3f
            bass.render(mix, gBass * 0.7f)
            pad.render(mix, gPad * 0.5f)
            drums.render(mix, drumsGain * (if (gDrums < 0.02f) 0f else 1f) * (0.4f + 0.6f * gDrums))
            texture.render(mix, p.crackle * gTex, p.rain, 0.8f)

            // musical pan from steering (gentle) applied to the dry mix
            val pan = smoothPan.process(p.pan)
            val pp = (pan * 0.5f + 1f) * 0.25f * 3.1415927f
            val gl = cos(pp); val gr = sin(pp)
            var l = mix.l * (0.75f + gl * 0.35f)
            var r = mix.r * (0.75f + gr * 0.35f)

            // lo-fi stage
            l = lpL.process(l); r = lpR.process(r)
            l = crushL.process(l); r = crushR.process(r)
            l = wobL.process(l); r = wobR.process(r)

            // reverb send + stereo width
            val rv = smoothRev.process(p.reverbMix)
            val wl = revL.process(l); val wr = revR.process(r)
            l += wl * rv; r += wr * rv
            val w = smoothWidth.process(p.width)
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * w
            l = mid + side; r = mid - side

            val g = smoothGain.process(targetGain) * 0.72f   // ~ -14 dBFS RMS on a smooth drive; limiter mostly idle
            l = limL.process(dcL.process(l * g))
            r = limR.process(dcR.process(r * g))
            out[o++] = l; out[o++] = r
            val a = if (l < 0f) -l else l
            if (a > blockPeak) blockPeak = a
            val b = if (r < 0f) -r else r
            if (b > blockPeak) blockPeak = b
        }
        peak = blockPeak
        elapsedSamples += frames
        val score = scorer.state
        if (score.moving) { flowAccum += score.flow.toDouble() * frames; flowSamples += frames }
        if (p.bloom > 0.5f) bloomSamples += frames
        // publish at most every ~1024 samples (~21 ms): the UI polls at 25 Hz, and this keeps per-block allocation off the audio thread
        samplesSincePublish += frames
        if (samplesSincePublish >= PUBLISH_INTERVAL) { samplesSincePublish = 0; publishState(p, score) }
    }

    private var samplesSincePublish = PUBLISH_INTERVAL   // first render publishes immediately

    private fun publishState(p: MusicParams, score: ScoreState) {
        layersActive.clear()
        layersActive.add("pad")
        if (gBass > 0.05f) layersActive.add("bass")
        if (gEp > 0.05f) layersActive.add("keys")
        if (gDrums > 0.05f) layersActive.add("drums")
        if (gHats > 0.05f) layersActive.add("hats")
        if (gLead > 0.1f) layersActive.add("melody")
        if (p.rain > 0.3f) layersActive.add("rain")
        if (p.crackle > 0.4f) layersActive.add("vinyl")
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
            density = p.density,
            bloom = p.bloom,
            layers = ArrayList(layersActive),
            title = title,
            elapsedSec = elapsedSamples / sr,
            peak = peak,
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
        private const val PUBLISH_INTERVAL = 1024
        private const val SECTION_BARS = 8
    }
}

/** Trip title: time of day + road name + key/genre, e.g. "저녁의 올림픽대로 (Bb Lo-fi)". */
object TripTitles {
    fun make(roadName: String?, hourOfDay: Int, keyRoot: Int, genre: Genre): String {
        val when_ = when (hourOfDay) {
            in 0..4 -> "새벽의"
            in 5..9 -> "아침의"
            in 10..15 -> "낮의"
            in 16..19 -> "저녁의"
            else -> "밤의"
        }
        val road = roadName?.takeIf { it.isNotBlank() } ?: "이름 없는 길"
        return "$when_ $road (${Pitch.name(keyRoot)} ${genre.label})"
    }

}
