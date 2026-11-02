package io.drivesymphony.engine.dsp

import io.drivesymphony.engine.music.StereoOut
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * SoundFont-style volume envelope: linear attack, hold, then exponential decay to the sustain level and
 * exponential release, both measured as "seconds to fall 96 dB" (the SF2 convention, linear in dB). Pure state
 * machine, no allocation, deterministic.
 */
class SfEnvelope(private val sr: Float) {
    private var stage = 0          // 0 idle, 1 attack, 2 hold, 3 decay, 4 sustain, 5 release
    private var level = 0f
    private var attackInc = 1f
    private var holdLeft = 0L
    private var holdSamples = 0L
    private var decayMul = 1f
    private var releaseMul = 1f
    private var sustainLevel = 1f
    val isActive: Boolean get() = stage != 0
    val isReleasing: Boolean get() = stage == 5
    val current: Float get() = level

    fun set(attackSec: Float, holdSec: Float, decaySec: Float, sustain: Float, releaseSec: Float) {
        attackInc = 1f / (attackSec * sr).coerceAtLeast(1f)
        holdSamples = (holdSec * sr).toLong().coerceAtLeast(0L)
        decayMul = mul96dB(decaySec)
        releaseMul = mul96dB(releaseSec)
        sustainLevel = sustain.coerceIn(0f, 1f)
    }

    /** Per-sample multiplier that falls 96 dB over `seconds`. */
    private fun mul96dB(seconds: Float): Float {
        val n = (seconds * sr).coerceAtLeast(1f)
        return kotlin.math.exp((-96f / 20f * LN10) / n)
    }

    fun noteOn() { stage = 1; level = 0f; holdLeft = holdSamples }
    fun noteOff() { if (stage != 0) stage = 5 }
    fun kill() { stage = 0; level = 0f }

    fun process(): Float {
        when (stage) {
            1 -> { level += attackInc; if (level >= 1f) { level = 1f; stage = if (holdLeft > 0) 2 else 3 } }
            2 -> { holdLeft--; if (holdLeft <= 0) stage = 3 }
            3 -> {
                level *= decayMul
                if (level <= sustainLevel) { level = sustainLevel; stage = 4 }
                if (level < SILENCE) { level = 0f; stage = 0 }
            }
            4 -> if (sustainLevel < SILENCE) { level = 0f; stage = 0 }
            5 -> { level *= releaseMul; if (level < SILENCE) { level = 0f; stage = 0 } }
        }
        return level
    }

    private companion object {
        const val SILENCE = 1e-5f      // -100 dBFS
        val LN10 = ln(10f)
    }
}

/**
 * Polyphonic sample player for one [SampleInstrument]. Same external shape as the synth `EPiano` so the engine can
 * swap one for the other: `noteOn(midi, vel, durSamples)` auto-releases after the duration (0 = hold until
 * [noteOff]/[allOff]), `render` ADDS into the stereo output. Zone lookup by key and velocity, 4-point Hermite
 * interpolation, forward loops, SF2 volume envelope from the instrument, oldest-voice stealing, keyboard stereo
 * spread (low keys left, high keys right, like a miked piano) and an optional per-voice tone lowpass.
 * Deterministic: the only randomness is the seeded [Rng] used for [humanizeCents].
 */
class Sampler(private val sr: Float, private val inst: SampleInstrument, private val maxVoices: Int = 8, seed: Int = 0) : io.drivesymphony.engine.music.KeyVoice {
    /** Per-voice one-pole lowpass cutoff set at note-on; 0 = bypass. Like EPiano the cutoff can track velocity via [toneVelHz]. */
    var toneHz = 0f
    /** Added to [toneHz] times velocity, so loud notes open up (EPiano uses 1400 + vel * 4200). */
    var toneVelHz = 0f
    /** Velocity (0..1) to gain exponent: 1 = linear like FluidSynth's default curve, <1 compresses the dynamics. */
    var velExp = 1f
    /** Random detune per note in cents (0 = exact pitch). */
    var humanizeCents = 0f
    /** Extra stereo width of the keyboard spread, 0..1 (EPiano: pan = ((m - 64) / 22).coerceIn(-0.6, 0.6)). */
    var spread = 1f
    /** Master level in dB applied on top of the zone gains, so the caller can match the synth instruments. */
    var levelDb = 0f
        set(v) { field = v; level = dbToGain(v) }
    private var level = 1f

    private val rng = Rng(seed)

    private inner class Voice {
        val env = SfEnvelope(sr).apply { set(inst.attackSec, inst.holdSec, inst.decaySec, inst.sustain, inst.releaseSec) }
        val tone = OnePole(sr)
        var zone: SampleZone? = null
        var pcm: ShortArray = EMPTY
        var pos = 0.0
        var inc = 0.0
        var loopStart = 0
        var loopEnd = 0
        var loopLen = 0
        var looping = false
        var inLoop = false
        var ended = false
        var midi = -1
        var vel = 0f
        var vgain = 0f
        var remaining = 0L
        var age = 0L
        var gl = 0.7071f
        var gr = 0.7071f
        var useTone = false

        fun on(m: Int, v: Float, durSamples: Long, z: SampleZone) {
            midi = m; vel = v; remaining = durSamples; age = 0; zone = z; pcm = z.pcm
            val detune = if (humanizeCents > 0f) rng.nextBipolar() * humanizeCents else 0f
            val semis = (m - z.rootKey) + (z.fineCents + detune) / 100f
            inc = 2.0.pow(semis / 12.0) * z.sampleRate / sr
            pos = 0.0
            looping = z.looped
            loopStart = z.loopStart; loopEnd = z.loopEnd; loopLen = loopEnd - loopStart
            inLoop = false; ended = false
            vgain = v.pow(velExp) * dbToGain(z.gainDb) * (1f / 32768f)
            useTone = toneHz > 0f
            if (useTone) { tone.setCutoff(toneHz + v * toneVelHz); tone.reset() }
            val pan = ((m - 64) / 22f).coerceIn(-0.6f, 0.6f) * spread
            val pp = (pan + 1f) * 0.25f * 3.1415927f
            gl = cos(pp); gr = sin(pp)
            env.noteOn()
        }

        private fun tap(k: Int): Float {
            var j = k
            if (inLoop && j >= loopEnd) j -= loopLen
            if (j < 0 || j >= pcm.size) return 0f
            return pcm[j].toFloat()
        }

        fun render(): Float {
            if (!env.isActive) return 0f
            val e = env.process()
            if (remaining > 0) { remaining--; if (remaining == 0L) env.noteOff() }
            age++
            if (ended) { env.kill(); return 0f }
            val i = pos.toInt()
            val t = (pos - i).toFloat()
            val y0 = tap(i - 1); val y1 = tap(i); val y2 = tap(i + 1); val y3 = tap(i + 2)
            // 4-point, 3rd-order Hermite
            val c1 = 0.5f * (y2 - y0)
            val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
            val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
            var s = ((c3 * t + c2) * t + c1) * t + y1
            pos += inc
            if (looping) {
                if (pos >= loopEnd) { pos -= loopLen; inLoop = true }
                else if (!inLoop && pos >= loopStart) inLoop = true
            } else if (pos >= pcm.size) {
                ended = true
            }
            s *= e * vgain
            if (useTone) s = tone.process(s)
            return s
        }
    }

    private val voices = Array(maxVoices) { Voice() }

    /** Number of voices currently sounding (for tests and diagnostics). */
    val activeVoices: Int get() { var n = 0; for (v in voices) if (v.env.isActive) n++; return n }

    override fun noteOn(midi: Int, vel: Float, durSamples: Long) {
        val v = vel.coerceIn(0.05f, 1f)
        val zone = inst.zoneFor(midi, (v * 127f).toInt().coerceIn(1, 127)) ?: return
        var pick: Voice? = null
        for (c in voices) if (!c.env.isActive) { pick = c; break }
        if (pick == null) {
            // steal: prefer a releasing voice, else the oldest
            for (c in voices) if (c.env.isReleasing && (pick == null || c.age > pick.age)) pick = c
            if (pick == null) for (c in voices) if (pick == null || c.age > pick.age) pick = c
        }
        pick!!.on(midi, v, durSamples, zone)
    }

    /** Releases every voice playing [midi] (no-op when none does). */
    fun noteOff(midi: Int) { for (v in voices) if (v.env.isActive && v.midi == midi && !v.env.isReleasing) v.env.noteOff() }

    override fun allOff() { for (v in voices) v.env.noteOff() }

    override fun render(out: StereoOut, gain: Float) {
        var sl = 0f; var sr2 = 0f
        for (v in voices) { val s = v.render(); if (s != 0f) { sl += s * v.gl; sr2 += s * v.gr } }
        val g = gain * level
        out.l += sl * g
        out.r += sr2 * g
    }

    private companion object { val EMPTY = ShortArray(0) }
}

/**
 * One-shot drum sampler over a kit [SampleInstrument] whose zones are keyed by GM drum note. `hit(note, vel)` starts
 * the zone for that note (ignored when the kit has no such note: check [has]); every hit plays through to the end of
 * its sample. Placement mirrors the synth `Drums`: hats sit left of the drummer, ride and shakers right, snare a
 * touch right, kick centred. Closed/pedal hats choke an open hat (GM exclusive class). Deterministic.
 */
class DrumSampler(private val sr: Float, private val inst: SampleInstrument, private val maxVoices: Int = 10) {
    /** Velocity (0..1) to gain exponent, 1 = linear. */
    var velExp = 1f
    /** Master level in dB on top of the zone gains. */
    var levelDb = 0f
        set(v) { field = v; level = dbToGain(v) }
    private var level = 1f
    private val releaseMul = kotlin.math.exp((-96f / 20f * ln(10f)) / (0.02f * sr))   // 20 ms choke/steal fade

    private inner class Voice {
        var pcm: ShortArray = EMPTY
        var pos = 0.0
        var inc = 0.0
        var gain = 0f
        var fade = 1f
        var fading = false
        var note = -1
        var active = false
        var age = 0L
        var gl = 1f
        var gr = 1f

        fun on(n: Int, v: Float, z: SampleZone) {
            note = n; pcm = z.pcm; pos = 0.0; age = 0; fade = 1f; fading = false; active = true
            inc = 2.0.pow(((n - z.rootKey) * 100 + z.fineCents) / 1200.0) * z.sampleRate / sr
            gain = v.pow(velExp) * dbToGain(z.gainDb) * (1f / 32768f)
            when (n) {
                42, 44, 46 -> { gl = 1.5f; gr = 0.5f }           // hats left
                51, 53, 59 -> { gl = 0.5f; gr = 1.5f }           // ride right
                38, 40 -> { gl = 0.95f; gr = 1.05f }             // snare a touch right
                70, 69, 82, 54 -> { gl = 0.7f; gr = 1.3f }       // maracas / cabasa / shaker / tambourine right
                else -> { gl = 1f; gr = 1f }
            }
        }

        fun choke() { fading = true }

        fun render(): Float {
            if (!active) return 0f
            age++
            val i = pos.toInt()
            if (i + 2 >= pcm.size) { active = false; return 0f }
            val t = (pos - i).toFloat()
            val y0 = if (i > 0) pcm[i - 1].toFloat() else 0f
            val y1 = pcm[i].toFloat(); val y2 = pcm[i + 1].toFloat(); val y3 = pcm[i + 2].toFloat()
            val c1 = 0.5f * (y2 - y0)
            val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
            val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
            val s = ((c3 * t + c2) * t + c1) * t + y1
            pos += inc
            if (fading) { fade *= releaseMul; if (fade < 1e-4f) { active = false; return 0f } }
            return s * gain * fade
        }
    }

    private val voices = Array(maxVoices) { Voice() }

    val activeVoices: Int get() { var n = 0; for (v in voices) if (v.active) n++; return n }

    /** True when the kit has a sample for this GM note. */
    fun has(note: Int): Boolean = inst.hasKey(note)

    /** Per-note trim in dB on top of the kit's own balance (e.g. lift the kick, tame the hats). */
    fun setNoteGainDb(note: Int, db: Float) { if (note in 0..127) noteGain[note] = dbToGain(db) }
    private val noteGain = FloatArray(128) { 1f }

    fun hit(note: Int, vel: Float) {
        if (note !in 0..127 || !inst.hasKey(note)) return
        val v = vel.coerceIn(0.05f, 1f)
        val zone = inst.zoneFor(note, (v * 127f).toInt().coerceIn(1, 127)) ?: return
        if (note == 42 || note == 44) for (c in voices) if (c.active && c.note == 46) c.choke()
        var pick: Voice? = null
        for (c in voices) if (!c.active) { pick = c; break }
        if (pick == null) for (c in voices) if (pick == null || c.age > pick.age) pick = c
        pick!!.on(note, v, zone)
        pick.gain *= noteGain[note]
    }

    /** Fades every sounding hit out over 20 ms. */
    fun allOff() { for (v in voices) if (v.active) v.choke() }

    fun render(out: StereoOut, gain: Float) {
        var l = 0f; var r = 0f
        for (v in voices) { val s = v.render(); if (s != 0f) { l += s * v.gl; r += s * v.gr } }
        val g = gain * level
        out.l += l * g
        out.r += r * g
    }

    private companion object { val EMPTY = ShortArray(0) }
}
