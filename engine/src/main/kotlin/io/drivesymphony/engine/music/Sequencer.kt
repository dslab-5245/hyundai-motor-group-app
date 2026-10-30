package io.drivesymphony.engine.music

import io.drivesymphony.engine.director.MusicParams
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.theory.Chord
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.theory.Voicing
import kotlin.math.abs

/** What the sequencer asks the engine to play at a given 16th-note step. */
enum class HitKind { KICK, SNARE, HAT_C, HAT_O, RIDE, SHAKER, RIM, EP, BASS, LEAD, PAD_CHORD, PULSE }

class StepEvent(val step: Int, val kind: HitKind, val note: Int = 0, val vel: Float = 0.8f, val lenSteps: Float = 1f)

/**
 * 16-step bar clock with swing. Events are fired at block granularity (<= 256 samples jitter),
 * which is inaudible for these genres and keeps the audio thread allocation-free.
 */
class Clock(private val sr: Int) {
    var bpm = 80f
    var swing = 0.58f // position of the off-beat 8th within a beat (0.5 straight .. 0.67 triplet)
    var samplePos = 0L
        private set
    var bar = 0
        private set
    private var barStart = 0L
    private var nextStep = 0
    private val offsets = LongArray(16)
    private var barLen = 0L

    init { computeBar() }

    private fun computeBar() {
        val spb = sr * 60.0 / bpm // samples per beat
        barLen = (spb * 4).toLong()
        for (k in 0 until 16) {
            val beat = k / 4
            val pos = when (k % 4) {
                0 -> 0.0
                1 -> swing / 2.0
                2 -> swing.toDouble()
                else -> (swing + 1.0) / 2.0
            }
            offsets[k] = ((beat + pos) * spb).toLong()
        }
    }

    val beatInBar: Float get() = ((samplePos - barStart).toFloat() / barLen.toFloat() * 4f).coerceIn(0f, 3.999f)
    val stepSamples: Float get() = (barLen / 16f)
    fun samplesForSteps(steps: Float): Long = (barLen / 16f * steps).toLong().coerceAtLeast(1L)

    /**
     * Advances the clock by [frames] samples; calls [onBar] when a new bar starts (before its steps),
     * then [onStep] for every step whose scheduled time falls inside this block.
     */
    fun advance(frames: Int, onBar: (bar: Int) -> Unit, onStep: (step: Int) -> Unit) {
        val end = samplePos + frames
        if (samplePos == 0L && nextStep == 0 && bar == 0 && barStart == 0L) onBar(0)
        while (true) {
            if (nextStep >= 16) {
                bar++
                barStart += barLen
                nextStep = 0
                computeBar()
                onBar(bar)
            }
            val t = barStart + offsets[nextStep]
            if (t < end) { onStep(nextStep); nextStep++ } else break
        }
        samplePos = end
    }
}

/**
 * Generates one bar of [StepEvent]s per genre from the current chord and musical parameters.
 * Density gates layers in the order they become musically "earned":
 *   LOFI   : pad/bass (0) -> kick+snare (1) -> hats+EP comping (2) -> ghosts, open hats, melody (3)
 *   JAZZ   : bass+EP (0) -> ride+hi-hat (1) -> brush comping, kick feather (2) -> melody (3)
 *   AMBIENT: pad (0) -> bass + slow EP arpeggio (1) -> swell pulses, high arpeggio (2)
 */
class Arranger(private val genre: Genre, private val rng: Rng) {
    private val events = ArrayList<StepEvent>(64)
    private var lastLeadNote = -1

    fun bar(chord: Chord, next: Chord, p: MusicParams, barIndex: Int): List<StepEvent> {
        events.clear()
        when (genre) {
            Genre.LOFI -> lofi(chord, next, p, barIndex)
            Genre.JAZZ -> jazz(chord, next, p, barIndex)
            Genre.AMBIENT -> ambient(chord, next, p, barIndex)
        }
        return events
    }

    private fun add(step: Int, kind: HitKind, note: Int = 0, vel: Float = 0.8f, len: Float = 1f) {
        events.add(StepEvent(step, kind, note, vel.coerceIn(0.05f, 1f), len))
    }

    private fun bassRoot(chord: Chord): Int = 36 + chord.rootPc
    private fun fifth(chord: Chord): Int = bassRoot(chord) + 7
    private fun third(chord: Chord): Int = bassRoot(chord) + chord.quality.intervals[1]
    private fun seventh(chord: Chord): Int = bassRoot(chord) + chord.quality.intervals.getOrElse(3) { 10 }.coerceAtMost(11)

    private fun lofi(chord: Chord, next: Chord, p: MusicParams, barIndex: Int) {
        val d = p.density
        val hum = 0.85f + rng.nextFloat() * 0.15f // human velocity
        // drums
        if (d >= 0.9f) {
            add(0, HitKind.KICK, vel = 0.95f * hum)
            add(10, HitKind.KICK, vel = 0.8f * hum)
            if (d >= 1.6f && rng.chance(0.55f)) add(7, HitKind.KICK, vel = 0.6f)
            if (d >= 2.6f && rng.chance(0.4f)) add(3, HitKind.KICK, vel = 0.35f)
            add(4, HitKind.SNARE, vel = 0.85f * hum)
            add(12, HitKind.SNARE, vel = 0.9f * hum)
            if (d >= 2.4f && rng.chance(0.5f)) add(15, HitKind.SNARE, vel = 0.3f)
            if (d >= 2.2f && rng.chance(0.35f)) add(9, HitKind.RIM, vel = 0.4f)
        }
        if (d >= 1.8f) {
            for (k in 0 until 16 step 2) {
                val v = if (k % 4 == 0) 0.6f else 0.42f
                add(k, HitKind.HAT_C, vel = v * hum)
            }
            if (d >= 2.5f) {
                if (rng.chance(0.6f)) add(14, HitKind.HAT_O, vel = 0.4f)
                for (k in intArrayOf(3, 7, 11, 13)) if (rng.chance(0.3f)) add(k, HitKind.HAT_C, vel = 0.25f)
            }
        }
        if (p.rain > 0.5f && d >= 1.2f) for (k in 0 until 16 step 4) add(k + 2, HitKind.SHAKER, vel = 0.3f)

        // bass
        add(0, HitKind.BASS, bassRoot(chord), 0.9f, if (d >= 1f) 6f else 12f)
        if (d >= 1.0f && rng.chance(0.6f)) add(8, HitKind.BASS, if (rng.chance(0.5f)) fifth(chord) else bassRoot(chord) + 12, 0.65f, 4f)
        if (d >= 2.0f) {
            val approach = bassRoot(next) + if (rng.chance(0.5f)) -1 else 2
            add(14, HitKind.BASS, approach, 0.55f, 2f)
        }

        // electric piano comping
        val notes = Voicing.comp(chord, p.richness, rng)
        if (d < 0.9f) {
            for (n in notes) add(0, HitKind.EP, n, 0.55f, 15f)
        } else {
            for (n in notes) add(0, HitKind.EP, n, 0.7f, 6f)
            val second = if (rng.chance(0.5f)) 6 else 7
            if (rng.chance(0.75f)) for (n in notes) add(second, HitKind.EP, n, 0.5f, 4f)
            if (d >= 1.8f && rng.chance(0.5f)) for (n in notes) add(11, HitKind.EP, n, 0.45f, 3f)
            if (d >= 2.6f && rng.chance(0.4f)) add(13, HitKind.EP, notes.last() + 12, 0.35f, 2f)
        }

        // pad: wide chord (held across the bar)
        val padNotes = Voicing.pad(chord, p.richness)
        for (n in padNotes) add(0, HitKind.PAD_CHORD, n, 0.5f, 16f)

        // melody on bloom
        if (p.bloom > 0.5f) melody(chord, start = if (rng.chance(0.5f)) 8 else 4, maxNotes = 3 + (p.bloom * 3).toInt(), swingy = true)
    }

    private fun jazz(chord: Chord, next: Chord, p: MusicParams, barIndex: Int) {
        val d = p.density
        val hum = 0.85f + rng.nextFloat() * 0.15f
        // ride & hi-hat
        if (d >= 0.9f) {
            for (beat in 0 until 4) {
                add(beat * 4, HitKind.RIDE, vel = (if (beat % 2 == 0) 0.75f else 0.62f) * hum)
                if (beat % 2 == 1) add(beat * 4 + 2, HitKind.RIDE, vel = 0.5f * hum) // "spang-a-LANG"
            }
            add(4, HitKind.HAT_C, vel = 0.55f)
            add(12, HitKind.HAT_C, vel = 0.55f)
        }
        if (d >= 1.7f) {
            for (beat in 0 until 4) if (rng.chance(0.8f)) add(beat * 4, HitKind.KICK, vel = 0.22f) // feathered kick
            for (k in intArrayOf(2, 6, 10, 14, 7, 13)) if (rng.chance(0.28f * (d - 1f))) add(k, HitKind.SNARE, vel = 0.35f + rng.nextFloat() * 0.25f)
        }
        if (p.rain > 0.5f && d >= 1.2f) for (k in intArrayOf(2, 6, 10, 14)) add(k, HitKind.SHAKER, vel = 0.25f)

        // walking bass: root, 3rd/5th, 5th/7th, chromatic approach to the next root
        val r = bassRoot(chord)
        val nr = bassRoot(next)
        val b2 = if (rng.chance(0.5f)) third(chord) else fifth(chord)
        val b3 = if (rng.chance(0.5f)) fifth(chord) else seventh(chord)
        val b4 = if (abs(nr - r) < 3) nr + (if (rng.chance(0.5f)) 1 else -1) else if (nr > r) nr - 1 else nr + 1
        val walk = if (d >= 0.6f) intArrayOf(r, b2, b3, b4) else intArrayOf(r, r, fifth(chord), fifth(chord))
        for (i in 0 until 4) add(i * 4, HitKind.BASS, walk[i], 0.78f + rng.nextFloat() * 0.1f, 3.6f)

        // comping: Charleston or off-beat cells
        val notes = Voicing.comp(chord, p.richness, rng)
        val cell = rng.nextInt(4)
        val stabs = when (cell) {
            0 -> intArrayOf(0, 6)
            1 -> intArrayOf(3, 10)
            2 -> intArrayOf(2, 7, 13)
            else -> intArrayOf(0, 11)
        }
        val stabCount = if (d < 1.2f) 1 else stabs.size
        for (i in 0 until stabCount) {
            val st = stabs[i]
            for (n in notes) add(st, HitKind.EP, n, 0.5f + rng.nextFloat() * 0.2f, 2.5f)
        }
        // soft pad only at low density (keeps the trio clean when the groove is full)
        if (d < 1.4f) for (n in Voicing.pad(chord, p.richness * 0.5f)) add(0, HitKind.PAD_CHORD, n, 0.3f, 16f)

        if (p.bloom > 0.5f) melody(chord, start = rng.nextInt(4) * 2, maxNotes = 4 + (p.bloom * 4).toInt(), swingy = true)
    }

    private fun ambient(chord: Chord, next: Chord, p: MusicParams, barIndex: Int) {
        val d = p.density
        for (n in Voicing.pad(chord, p.richness)) add(0, HitKind.PAD_CHORD, n, 0.6f, 16f)
        if (barIndex % 2 == 0) add(0, HitKind.BASS, bassRoot(chord), 0.5f, 30f)
        if (d >= 0.8f) {
            val tones = chord.safeTones()
            val base = 60 + chord.rootPc
            val steps = intArrayOf(0, 6, 12)
            for ((i, st) in steps.withIndex()) {
                if (rng.chance(0.7f)) add(st, HitKind.EP, base + tones[(i + barIndex) % tones.size] + (if (i == 2) 12 else 0), 0.35f, 8f)
            }
        }
        if (d >= 1.6f && barIndex % 2 == 0) add(0, HitKind.PULSE)
        if (d >= 1.8f && rng.chance(0.5f)) add(10, HitKind.EP, 72 + chord.rootPc + chord.safeTones()[rng.nextInt(chord.safeTones().size)], 0.3f, 10f)
        if (p.bloom > 0.5f) melody(chord, start = 8, maxNotes = 2 + (p.bloom * 2).toInt(), swingy = false)
    }

    /** Stepwise melody from the chord's safe tones; rests keep it airy. */
    private fun melody(chord: Chord, start: Int, maxNotes: Int, swingy: Boolean) {
        val tones = chord.safeTones()
        val base = 72 + chord.rootPc
        var idx = if (lastLeadNote < 0) rng.nextInt(tones.size) else {
            // continue near the previous note
            var best = 0; var bestDist = 99
            for (i in tones.indices) { val dist = abs((base + tones[i]) - lastLeadNote); if (dist < bestDist) { bestDist = dist; best = i } }
            best
        }
        var step = start
        var count = 0
        while (step < 16 && count < maxNotes) {
            if (rng.chance(0.8f)) {
                val note = base + tones[idx]
                add(step, HitKind.LEAD, note, 0.45f + rng.nextFloat() * 0.25f, if (swingy) 1.6f else 3f)
                lastLeadNote = note
                count++
            }
            idx = (idx + (if (rng.chance(0.5f)) 1 else -1) + tones.size) % tones.size
            step += if (swingy) 2 else 4
        }
    }
}
