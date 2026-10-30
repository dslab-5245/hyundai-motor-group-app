package io.drivesymphony.engine.music

import io.drivesymphony.engine.dsp.Adsr
import io.drivesymphony.engine.dsp.FmTine
import io.drivesymphony.engine.dsp.Lfo
import io.drivesymphony.engine.dsp.OnePole
import io.drivesymphony.engine.dsp.Osc
import io.drivesymphony.engine.dsp.PinkNoise
import io.drivesymphony.engine.dsp.PluckString
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.midiToHz
import io.drivesymphony.engine.dsp.panGains
import io.drivesymphony.engine.dsp.softClip
import kotlin.math.exp
import kotlin.math.sin

/** Stereo sample pair used by instruments that render stereo. */
class StereoOut { var l = 0f; var r = 0f }

/**
 * FM electric piano ("tine" piano). 2-op FM body + bell partial, per-voice tone lowpass,
 * instrument-level stereo tremolo like a classic suitcase EP. Notes auto-release after their duration.
 */
class EPiano(private val sr: Float, seed: Int, private val maxVoices: Int = 6, private val bright: Float = 1f) {
    private inner class Voice {
        val op = FmTine(sr)
        val env = Adsr(sr).apply { attack = 0.002f; decay = 1.6f; sustain = 0.18f; release = 0.5f }
        val tone = OnePole(sr)
        var midi = -1
        var vel = 0f
        var remaining = 0L
        var age = 0L
        fun on(m: Int, v: Float, durSamples: Long) {
            midi = m; vel = v; remaining = durSamples; age = 0
            op.setFrequency(midiToHz(m), 1f, if (m > 72) 4f else 7f)
            tone.setCutoff(1800f + v * 5000f * bright)
            env.noteOn()
        }
        fun render(): Float {
            if (!env.isActive) return 0f
            val e = env.process()
            if (remaining > 0) { remaining--; if (remaining == 0L) env.noteOff() }
            age++
            val index = (0.35f + vel * 1.4f * bright) * (0.3f + 0.7f * e)
            val tine = 0.22f * vel * e * e
            return tone.process(op.process(index, tine)) * e * (0.5f + 0.5f * vel)
        }
    }

    private val voices = Array(maxVoices) { Voice() }
    private val rng = Rng(seed)
    private val trem = Lfo(sr, 0.9f, rng.nextFloat())
    var tremoloDepth = 0.35f

    fun noteOn(midi: Int, vel: Float, durSamples: Long) {
        var pick = voices.firstOrNull { !it.env.isActive }
        if (pick == null) pick = voices.maxByOrNull { it.age }
        pick!!.on(midi, vel.coerceIn(0.05f, 1f), durSamples)
    }

    fun allOff() { voices.forEach { it.env.noteOff() } }

    fun render(out: StereoOut, gain: Float) {
        var sum = 0f
        for (v in voices) sum += v.render()
        val t = trem.process()
        val gl = 1f - tremoloDepth * 0.5f * (1f + t)
        val gr = 1f - tremoloDepth * 0.5f * (1f - t)
        out.l += sum * gain * gl
        out.r += sum * gain * gr
    }
}

/** Upright-ish bass: sine fundamental + filtered saw body + Karplus-Strong pluck transient, soft saturation. */
class Bass(sr: Float, seed: Int) {
    private val osc1 = Osc(sr)
    private val osc2 = Osc(sr)
    private val filt = Svf(sr)
    private val env = Adsr(sr).apply { attack = 0.004f; decay = 0.35f; sustain = 0.55f; release = 0.18f }
    private val pluck = PluckString(sr, seed).apply { brightness = 0.35f }
    private var vel = 0f
    private var remaining = 0L
    private var pluckLife = 0L

    fun noteOn(midi: Int, v: Float, durSamples: Long) {
        val hz = midiToHz(midi)
        osc1.frequency = hz; osc2.frequency = hz
        vel = v.coerceIn(0.1f, 1f)
        remaining = durSamples
        env.noteOn()
        pluck.pluck(hz * 2f, vel * 0.5f)
        pluckLife = (0.25f * 48000f).toLong()
    }

    fun render(out: StereoOut, gain: Float) {
        val active = env.isActive || pluckLife > 0
        if (!active) return
        val e = env.process()
        if (remaining > 0) { remaining--; if (remaining == 0L) env.noteOff() }
        filt.set(140f + e * 700f * vel, 0.12f)
        val raw = osc1.sine() * 0.75f + osc2.saw() * 0.3f
        val body = filt.process(raw) * e * vel
        var p = 0f
        if (pluckLife > 0) { pluckLife--; p = pluck.process(0.992f) * 0.5f }
        val s = softClip((body + p * (0.3f + e)) * 1.5f) * gain
        out.l += s
        out.r += s
    }
}

/** Warm pad: 3 detuned saws per voice, slow filter envelope, long attack/release, stereo spread. */
class Pad(private val sr: Float, seed: Int, private val maxVoices: Int = 5) {
    private inner class Voice(idx: Int) {
        val a = Osc(sr); val b = Osc(sr); val c = Osc(sr)
        val filt = Svf(sr)
        val env = Adsr(sr).apply { attack = 1.6f; decay = 1.5f; sustain = 0.8f; release = 3.5f }
        val pan = -0.6f + 1.2f * (idx.toFloat() / (maxVoices - 1).coerceAtLeast(1))
        private val gl: Float
        private val gr: Float
        init { val g = panGains(pan); gl = g.first; gr = g.second }
        var midi = -1
        var remaining = 0L
        var age = 0L
        fun on(m: Int, durSamples: Long) {
            midi = m; remaining = durSamples; age = 0
            val hz = midiToHz(m)
            a.frequency = hz * 0.996f; b.frequency = hz; c.frequency = hz * 1.0045f
            env.noteOn()
        }
        fun render(out: StereoOut, cutoffBase: Float, gain: Float) {
            if (!env.isActive) return
            val e = env.process()
            if (remaining > 0) { remaining--; if (remaining == 0L) env.noteOff() }
            age++
            filt.set(cutoffBase + e * 1400f, 0.1f)
            val raw = (a.saw() + b.saw() + c.saw()) * 0.33f
            val s = filt.process(raw) * e * gain
            out.l += s * gl
            out.r += s * gr
        }
    }

    private val voices = Array(maxVoices) { Voice(it) }
    var cutoffBase = 500f

    fun chord(notes: IntArray, durSamples: Long) {
        // release voices not in the new chord, start the new ones
        for (v in voices) if (v.env.isActive && notes.none { it == v.midi }) v.env.noteOff()
        for (n in notes) {
            if (voices.any { it.env.isActive && !it.env.isReleasing && it.midi == n }) { voices.first { it.midi == n }.remaining = durSamples; continue }
            var pick = voices.firstOrNull { !it.env.isActive } ?: voices.firstOrNull { it.env.isReleasing } ?: voices.maxByOrNull { it.age }
            pick!!.on(n, durSamples)
        }
    }

    fun allOff() { voices.forEach { it.env.noteOff() } }

    fun render(out: StereoOut, gain: Float) {
        for (v in voices) v.render(out, cutoffBase, gain)
    }
}

/** Synthesized drum kit: kick, lo-fi snare, closed/open hat, ride, shaker, rim. Brushed variants use softer envelopes. */
class Drums(private val sr: Float, seed: Int) {
    private val rng = Rng(seed)
    private val pink = PinkNoise(seed + 11)

    // kick
    private var kickT = -1f; private var kickVel = 0f; private var kickPh = 0f
    // snare
    private var snT = -1f; private var snVel = 0f; private var snPh = 0f; private var snBrush = false
    private val snBp = Svf(sr).apply { set(1800f, 0.6f) }
    // hats
    private var hatT = -1f; private var hatVel = 0f; private var hatOpen = false
    private val hatHp = Svf(sr).apply { set(7000f, 0.3f) }
    // ride
    private var rideT = -1f; private var rideVel = 0f; private var ridePh = 0f
    private val rideBp = Svf(sr).apply { set(5200f, 0.75f) }
    // shaker
    private var shT = -1f; private var shVel = 0f
    private val shHp = Svf(sr).apply { set(5000f, 0.2f) }
    // rim
    private var rimT = -1f; private var rimVel = 0f; private var rimPh = 0f

    var brushed = false

    fun kick(v: Float) { kickT = 0f; kickVel = v; kickPh = 0f }
    fun snare(v: Float) { snT = 0f; snVel = v; snPh = 0f; snBrush = brushed }
    fun hat(v: Float, open: Boolean) { hatT = 0f; hatVel = v; hatOpen = open }
    fun ride(v: Float) { rideT = 0f; rideVel = v; ridePh = rng.nextFloat() }
    fun shaker(v: Float) { shT = 0f; shVel = v }
    fun rim(v: Float) { rimT = 0f; rimVel = v; rimPh = 0f }

    fun render(out: StereoOut, gain: Float) {
        var l = 0f; var r = 0f
        val inv = 1f / sr
        if (kickT >= 0f) {
            val t = kickT
            val f = 48f + 110f * exp(-t * 28f)
            kickPh += f * inv; if (kickPh >= 1f) kickPh -= 1f
            val body = sin((kickPh * 6.2831853f)) * exp(-t * 6.5f)
            val click = rng.nextBipolar() * exp(-t * 400f) * 0.25f
            val s = softClip((body + click) * 1.3f) * kickVel
            l += s; r += s
            kickT += inv; if (kickT > 0.6f) kickT = -1f
        }
        if (snT >= 0f) {
            val t = snT
            val dec = if (snBrush) 9f else 14f
            val n = snBp.process(rng.nextBipolar()) * exp(-t * dec) * (if (snBrush) 1.2f else 0.9f)
            snPh += 185f * inv; if (snPh >= 1f) snPh -= 1f
            val tone = sin(snPh * 6.2831853f) * exp(-t * 30f) * (if (snBrush) 0.15f else 0.5f)
            val s = (n + tone) * snVel * 0.8f
            l += s * 0.95f; r += s * 1.05f
            snT += inv; if (snT > 0.5f) snT = -1f
        }
        if (hatT >= 0f) {
            val t = hatT
            val dec = if (hatOpen) 5f else 38f
            hatHp.process(rng.nextBipolar())
            val s = hatHp.high * exp(-t * dec) * hatVel * 0.35f
            l += s * 1.1f; r += s * 0.9f
            hatT += inv; if (hatT > 0.7f) hatT = -1f
        }
        if (rideT >= 0f) {
            val t = rideT
            ridePh += 3150f * inv; if (ridePh >= 1f) ridePh -= 1f
            val ping = sin(ridePh * 6.2831853f) * exp(-t * 7f) * 0.18f
            val wash = rideBp.bandpass.let { rideBp.process(rng.nextBipolar()); rideBp.bandpass } * exp(-t * 3.2f) * 0.45f
            val s = (ping + wash) * rideVel
            l += s * 0.8f; r += s * 1.2f
            rideT += inv; if (rideT > 1.2f) rideT = -1f
        }
        if (shT >= 0f) {
            val t = shT
            shHp.process(pink.process())
            val s = shHp.high * exp(-t * 26f) * shVel * 0.5f
            l += s * 0.9f; r += s * 1.1f
            shT += inv; if (shT > 0.3f) shT = -1f
        }
        if (rimT >= 0f) {
            val t = rimT
            rimPh += 1200f * inv; if (rimPh >= 1f) rimPh -= 1f
            val s = (sin(rimPh * 6.2831853f) * 0.6f + rng.nextBipolar() * 0.3f) * exp(-t * 60f) * rimVel * 0.6f
            l += s; r += s
            rimT += inv; if (rimT > 0.15f) rimT = -1f
        }
        out.l += l * gain
        out.r += r * gain
    }
}

/** Background textures: vinyl crackle + pink floor, rain layer (wiper), slow swell pulse (ambient). */
class Texture(private val sr: Float, seed: Int) {
    private val rng = Rng(seed)
    private val pink = PinkNoise(seed + 3)
    private val crackLp = OnePole(sr).apply { setCutoff(3200f) }
    private val rainBp = Svf(sr).apply { set(3800f, 0.35f) }
    private val rainLp = OnePole(sr).apply { setCutoff(900f) }
    private var crackleHold = 0f
    private var swellT = -1f
    private val swellBp = Svf(sr).apply { set(900f, 0.5f) }
    private var hum = 0f

    fun pulse() { swellT = 0f }

    /** @param crackle 0..1 @param rain 0..1 */
    fun render(out: StereoOut, crackle: Float, rain: Float, gain: Float) {
        val inv = 1f / sr
        // crackle: sparse random impulses, density scales with amount
        var c = 0f
        if (crackle > 0.01f) {
            val p = 0.00025f + crackle * 0.0012f
            if (rng.chance(p)) crackleHold = rng.nextBipolar() * (0.3f + rng.nextFloat() * 0.7f)
            crackleHold *= 0.82f
            c = crackLp.process(crackleHold) * crackle * 0.9f + pink.process() * crackle * 0.045f
        }
        var rn = 0f
        if (rain > 0.01f) {
            val p = 0.004f * rain
            val drop = if (rng.chance(p)) rng.nextBipolar() else 0f
            rainBp.process(drop)
            rn = (rainBp.bandpass * 2.2f + rainLp.process(rng.nextBipolar()) * 0.25f) * rain * 0.7f
        }
        var sw = 0f
        if (swellT >= 0f) {
            val t = swellT
            val env = if (t < 1.2f) t / 1.2f else exp(-(t - 1.2f) * 1.1f)
            swellBp.process(pink.process())
            sw = swellBp.bandpass * env * 0.6f
            swellT += inv; if (swellT > 6f) swellT = -1f
        }
        hum += 50f * inv; if (hum >= 1f) hum -= 1f
        val h = sin(hum * 6.2831853f) * crackle * 0.01f
        out.l += (c * 1.05f + rn * 0.9f + sw + h) * gain
        out.r += (c * 0.95f + rn * 1.1f + sw + h) * gain
    }
}
