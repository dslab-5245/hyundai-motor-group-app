package io.drivesymphony.engine.music

import io.drivesymphony.engine.dsp.Adsr
import io.drivesymphony.engine.dsp.DrumSampler
import io.drivesymphony.engine.dsp.SampleInstrument
import io.drivesymphony.engine.dsp.FmTine
import io.drivesymphony.engine.dsp.Lfo
import io.drivesymphony.engine.dsp.OnePole
import io.drivesymphony.engine.dsp.Osc
import io.drivesymphony.engine.dsp.PinkNoise
import io.drivesymphony.engine.dsp.PluckString
import io.drivesymphony.engine.dsp.Rng
import io.drivesymphony.engine.dsp.SineTable
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.midiToHz
import io.drivesymphony.engine.dsp.panGains
import io.drivesymphony.engine.dsp.softClip
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Stereo sample pair used by instruments that render stereo. */
class StereoOut { var l = 0f; var r = 0f }

/** A keyed voice the engine can play: the FM/analogue synths below or a [io.drivesymphony.engine.dsp.Sampler]. */
interface KeyVoice {
    fun noteOn(midi: Int, vel: Float, durSamples: Long)
    fun allOff()
    /** Adds [gain]-scaled output to [out]; one call per sample frame. */
    fun render(out: StereoOut, gain: Float)
}

/** A drum kit the engine can hit: the synth [Drums] or a [SampledKit]. */
interface DrumKit {
    var brushed: Boolean
    fun kick(v: Float)
    fun snare(v: Float)
    fun hat(v: Float, open: Boolean)
    fun ride(v: Float)
    fun shaker(v: Float)
    fun rim(v: Float)
    fun render(out: StereoOut, gain: Float)
}

/**
 * Sampled kits from the bundled bank (standard kit + optional brush kit, GM note numbers). Placement and levels are
 * matched to the synth [Drums] so the arranger and the mix do not know which one is playing. The brush kit alternates
 * the brush snare (38) with the swirl (40) so repeated hits do not sound like a machine gun; the shaker always comes
 * from the standard kit (the brush kit has none).
 */
class SampledKit(sr: Float, standard: SampleInstrument, brush: SampleInstrument?, private val sub: Drums? = null) : DrumKit {
    // trims measured against the synth kit (SamplerDemoMain): the SoundFont's ride and hats sit far below its snare
    private val std = DrumSampler(sr, standard, 10).apply { levelDb = 10f; setNoteGainDb(36, 6f); setNoteGainDb(42, 11f); setNoteGainDb(46, 5f); setNoteGainDb(51, 11f); setNoteGainDb(70, -6f) }
    private val br = brush?.let { DrumSampler(sr, it, 10).apply { levelDb = 10f; setNoteGainDb(36, 6f); setNoteGainDb(42, 11f); setNoteGainDb(46, 5f); setNoteGainDb(51, 9f); setNoteGainDb(40, -2f) } }
    override var brushed = false
    private var swirl = false
    private val kit: DrumSampler get() = if (brushed && br != null) br else std
    /** the sampled kick carries the click and the skin; the synth kick underneath adds the sub weight a lo-fi beat lives on */
    override fun kick(v: Float) { kit.hit(36, v); if (!brushed) sub?.kick(v * 0.85f) }
    override fun snare(v: Float) {
        val b = br
        if (brushed && b != null) { swirl = !swirl; b.hit(if (swirl && b.has(40)) 40 else 38, v) } else std.hit(38, v)
    }
    override fun hat(v: Float, open: Boolean) = kit.hit(if (open) 46 else 42, v)
    override fun ride(v: Float) = kit.hit(51, v)
    override fun shaker(v: Float) = std.hit(70, v)
    override fun rim(v: Float) = kit.hit(37, v)
    override fun render(out: StereoOut, gain: Float) { std.render(out, gain); br?.render(out, gain); sub?.render(out, gain * 0.9f) }
}

/** Two voices played as one: a sampled instrument on top, a synth underneath for weight (sub bass under a sampled bass). */
class LayeredVoice(private val main: KeyVoice, private val under: KeyVoice, private val underGain: Float) : KeyVoice {
    override fun noteOn(midi: Int, vel: Float, durSamples: Long) { main.noteOn(midi, vel, durSamples); under.noteOn(midi, vel, durSamples) }
    override fun allOff() { main.allOff(); under.allOff() }
    override fun render(out: StereoOut, gain: Float) { main.render(out, gain); under.render(out, gain * underGain) }
}

/**
 * FM electric piano ("tine" piano). 2-op FM body + bell partial, per-voice tone lowpass,
 * instrument-level stereo tremolo like a classic suitcase EP. Notes auto-release after their duration.
 */
class EPiano(private val sr: Float, seed: Int, private val maxVoices: Int = 6, private val bright: Float = 1f) : KeyVoice {
    private inner class Voice {
        val op = FmTine(sr)
        val env = Adsr(sr).apply { attack = 0.002f; decay = 1.6f; sustain = 0.18f; release = 0.5f }
        val tone = OnePole(sr)
        var midi = -1
        var vel = 0f
        var remaining = 0L
        var age = 0L
        var gl = 0.7071f
        var gr = 0.7071f
        fun on(m: Int, v: Float, durSamples: Long) {
            midi = m; vel = v; remaining = durSamples; age = 0
            op.setFrequency(midiToHz(m), 1f, if (m > 72) 4f else 7f)
            tone.setCutoff(1400f + v * 4200f * bright)   // darker than a stage EP: the lo-fi/jazz register, still velocity-bright
            // keyboard stereo image: low notes left, high notes right (like a miked EP / piano)
            val pan = ((m - 64) / 22f).coerceIn(-0.6f, 0.6f)
            val pp = (pan + 1f) * 0.25f * 3.1415927f
            gl = cos(pp); gr = sin(pp)
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

    override fun noteOn(midi: Int, vel: Float, durSamples: Long) {
        var pick = voices.firstOrNull { !it.env.isActive }
        if (pick == null) pick = voices.maxByOrNull { it.age }
        pick!!.on(midi, vel.coerceIn(0.05f, 1f), durSamples)
    }

    override fun allOff() { voices.forEach { it.env.noteOff() } }

    override fun render(out: StereoOut, gain: Float) {
        var sl = 0f; var sr2 = 0f
        for (v in voices) { val s = v.render(); if (s != 0f) { sl += s * v.gl; sr2 += s * v.gr } }
        val t = trem.process()
        val gl = 1f - tremoloDepth * 0.5f * (1f + t)
        val gr = 1f - tremoloDepth * 0.5f * (1f - t)
        out.l += sl * gain * gl
        out.r += sr2 * gain * gr
    }
}

/** Upright-ish bass: sine fundamental + filtered saw body + Karplus-Strong pluck transient, soft saturation. */
class Bass(sr: Float, seed: Int) : KeyVoice {
    private val osc1 = Osc(sr)
    private val osc2 = Osc(sr)
    private val filt = Svf(sr)
    private val env = Adsr(sr).apply { attack = 0.004f; decay = 0.35f; sustain = 0.55f; release = 0.18f }
    private val pluck = PluckString(sr, seed).apply { brightness = 0.35f }
    private var vel = 0f
    private var remaining = 0L
    private var pluckLife = 0L

    override fun noteOn(midi: Int, velIn: Float, durSamples: Long) {
        val v = velIn
        val hz = midiToHz(midi)
        osc1.frequency = hz; osc2.frequency = hz
        vel = v.coerceIn(0.1f, 1f)
        remaining = durSamples
        env.noteOn()
        pluck.pluck(hz * 2f, vel * 0.5f)
        pluckLife = (0.25f * 48000f).toLong()
    }

    override fun allOff() { env.noteOff() }

    override fun render(out: StereoOut, gain: Float) {
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
class Drums(private val sr: Float, seed: Int) : DrumKit {
    private val rng = Rng(seed)
    private val pink = PinkNoise(seed + 11)

    // Decays are exp(-k*t); with t advancing by 1/sr they are advanced by one multiply per sample (exactly the same
    // curve as calling exp() each sample, minus the libm call). One state per decaying component.
    private fun decay(k: Float) = exp(-k / sr)
    // kick
    private var kickT = -1f; private var kickVel = 0f; private var kickPh = 0f
    private val dKickF = decay(28f); private val dKickBody = decay(6.5f); private val dKickClick = decay(400f)
    private var eKickF = 1f; private var eKickBody = 1f; private var eKickClick = 1f
    // snare
    private var snT = -1f; private var snVel = 0f; private var snPh = 0f; private var snBrush = false
    private val snBp = Svf(sr).apply { set(1800f, 0.6f) }
    private val dSnBrush = decay(9f); private val dSnStick = decay(14f); private val dSnTone = decay(30f)
    private var dSnNoise = dSnStick; private var eSnNoise = 1f; private var eSnTone = 1f
    // hats
    private var hatT = -1f; private var hatVel = 0f; private var hatOpen = false
    private val hatHp = Svf(sr).apply { set(7000f, 0.3f) }
    private val dHatOpen = decay(5f); private val dHatClosed = decay(38f)
    private var dHat = dHatClosed; private var eHat = 1f
    // ride
    private var rideT = -1f; private var rideVel = 0f; private var ridePh = 0f
    private val rideBp = Svf(sr).apply { set(5200f, 0.75f) }
    private val dRidePing = decay(7f); private val dRideWash = decay(3.2f)
    private var eRidePing = 1f; private var eRideWash = 1f
    // shaker
    private var shT = -1f; private var shVel = 0f
    private val shHp = Svf(sr).apply { set(5000f, 0.2f) }
    private val dSh = decay(26f); private var eSh = 1f
    // rim
    private var rimT = -1f; private var rimVel = 0f; private var rimPh = 0f
    private val dRim = decay(60f); private var eRim = 1f

    override var brushed = false

    override fun kick(v: Float) { kickT = 0f; kickVel = v; kickPh = 0f; eKickF = 1f; eKickBody = 1f; eKickClick = 1f }
    override fun snare(v: Float) { snT = 0f; snVel = v; snPh = 0f; snBrush = brushed; dSnNoise = if (snBrush) dSnBrush else dSnStick; eSnNoise = 1f; eSnTone = 1f }
    override fun hat(v: Float, open: Boolean) { hatT = 0f; hatVel = v; hatOpen = open; dHat = if (open) dHatOpen else dHatClosed; eHat = 1f }
    override fun ride(v: Float) { rideT = 0f; rideVel = v; ridePh = rng.nextFloat(); eRidePing = 1f; eRideWash = 1f }
    override fun shaker(v: Float) { shT = 0f; shVel = v; eSh = 1f }
    override fun rim(v: Float) { rimT = 0f; rimVel = v; rimPh = 0f; eRim = 1f }

    override fun render(out: StereoOut, gain: Float) {
        var l = 0f; var r = 0f
        val inv = 1f / sr
        if (kickT >= 0f) {
            val f = 48f + 110f * eKickF
            kickPh += f * inv; if (kickPh >= 1f) kickPh -= 1f
            val body = SineTable.sin01(kickPh) * eKickBody
            val click = rng.nextBipolar() * eKickClick * 0.25f
            val s = softClip((body + click) * 1.3f) * kickVel
            l += s; r += s
            eKickF *= dKickF; eKickBody *= dKickBody; eKickClick *= dKickClick
            kickT += inv; if (kickT > 0.6f) kickT = -1f
        }
        if (snT >= 0f) {
            val n = snBp.process(rng.nextBipolar()) * eSnNoise * (if (snBrush) 1.2f else 0.9f)
            snPh += 185f * inv; if (snPh >= 1f) snPh -= 1f
            val tone = SineTable.sin01(snPh) * eSnTone * (if (snBrush) 0.15f else 0.5f)
            val s = (n + tone) * snVel * 0.8f
            l += s * 0.95f; r += s * 1.05f
            eSnNoise *= dSnNoise; eSnTone *= dSnTone
            snT += inv; if (snT > 0.5f) snT = -1f
        }
        if (hatT >= 0f) {
            hatHp.process(rng.nextBipolar())
            val s = hatHp.high * eHat * hatVel * 0.35f
            l += s * 1.5f; r += s * 0.5f   // hats sit left of the drummer
            eHat *= dHat
            hatT += inv; if (hatT > 0.7f) hatT = -1f
        }
        if (rideT >= 0f) {
            ridePh += 3150f * inv; if (ridePh >= 1f) ridePh -= 1f
            val ping = SineTable.sin01(ridePh) * eRidePing * 0.18f
            val wash = rideBp.bandpass.let { rideBp.process(rng.nextBipolar()); rideBp.bandpass } * eRideWash * 0.45f
            val s = (ping + wash) * rideVel
            l += s * 0.5f; r += s * 1.5f   // ride on the right
            eRidePing *= dRidePing; eRideWash *= dRideWash
            rideT += inv; if (rideT > 1.2f) rideT = -1f
        }
        if (shT >= 0f) {
            shHp.process(pink.process())
            val s = shHp.high * eSh * shVel * 0.5f
            l += s * 0.7f; r += s * 1.3f
            eSh *= dSh
            shT += inv; if (shT > 0.3f) shT = -1f
        }
        if (rimT >= 0f) {
            rimPh += 1200f * inv; if (rimPh >= 1f) rimPh -= 1f
            val s = (SineTable.sin01(rimPh) * 0.6f + rng.nextBipolar() * 0.3f) * eRim * rimVel * 0.6f
            l += s; r += s
            eRim *= dRim
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
    private var riserT = -1f
    private var riserLen = 1f
    private val riserBp = Svf(sr)

    fun pulse() { swellT = 0f }

    /** Noise riser that builds over [seconds] (filter sweeping up) and stops exactly at the end: the next downbeat. */
    fun riser(seconds: Float) { riserT = 0f; riserLen = seconds.coerceAtLeast(0.2f) }

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
        var rs = 0f
        if (riserT >= 0f) {
            val u = riserT / riserLen
            riserBp.set(400f + 5600f * u * u, 0.6f)
            riserBp.process(rng.nextBipolar())
            val tail = (riserLen - riserT) / 0.006f   // 6 ms fade so the stop itself never clicks
            rs = riserBp.bandpass * u * u * (if (tail < 1f) tail.coerceAtLeast(0f) else 1f) * 0.3f
            riserT += inv; if (riserT >= riserLen) riserT = -1f
        }
        hum += 50f * inv; if (hum >= 1f) hum -= 1f
        val h = SineTable.sin01(hum) * crackle * 0.01f
        out.l += (c * 1.05f + rn * 0.9f + sw + h + rs * 0.9f) * gain
        out.r += (c * 0.95f + rn * 1.1f + sw + h + rs * 1.1f) * gain
    }
}

/**
 * Cruise-control drone: two detuned saws on the chord root (C2..B2) through a slowly breathing lowpass.
 * A sustained bed under the bass while cruise control is active; fades in and out over about two seconds.
 */
class Drone(private val sr: Float) {
    private val a = Osc(sr); private val b = Osc(sr)
    private val filt = Svf(sr)
    private var level = 0f
    private var hz = 65.4f
    private var targetHz = 65.4f
    private var lfoPhase = 0f
    private val lfoInc = 0.07f / sr

    /** Pitch class 0..11 -> C2..B2. */
    fun setRoot(pc: Int) { targetHz = midiToHz(36 + ((pc % 12) + 12) % 12) }

    fun render(out: StereoOut, gain: Float) {
        if (gain < 0.001f && level < 0.001f) return
        level += (gain - level) * 1e-5f               // ~2 s fade at 48 kHz
        hz += (targetHz - hz) * 2e-4f                 // glide between chord roots
        a.frequency = hz * 0.996f; b.frequency = hz * 1.004f
        lfoPhase += lfoInc; if (lfoPhase >= 1f) lfoPhase -= 1f
        filt.set(220f + 90f * SineTable.sin01(lfoPhase), 0.15f)
        val v = filt.process((a.saw() + b.saw()) * 0.5f) * level
        out.l += v * 0.92f; out.r += v * 1.08f
    }
}
