package io.drivesymphony.engine.dsp

import kotlin.math.sin

/** Phase accumulator oscillator with PolyBLEP anti-aliased saw/square and a plain sine. */
class Osc(private val sampleRate: Float) {
    private var phase = 0f // 0..1
    private var inc = 0f
    var frequency: Float = 110f
        set(v) { field = v; inc = v / sampleRate }

    fun reset(phase0: Float = 0f) { phase = phase0 }

    private fun step() { phase += inc; if (phase >= 1f) phase -= 1f }

    private fun polyBlep(t: Float): Float {
        val dt = inc
        return when {
            t < dt -> { val x = t / dt; x + x - x * x - 1f }
            t > 1f - dt -> { val x = (t - 1f) / dt; x * x + x + x + 1f }
            else -> 0f
        }
    }

    fun sine(): Float { val v = sin((phase * TWO_PI).toFloat()); step(); return v }

    fun triangle(): Float {
        val v = if (phase < 0.5f) 4f * phase - 1f else 3f - 4f * phase
        step(); return v
    }

    fun saw(): Float {
        var v = 2f * phase - 1f
        v -= polyBlep(phase)
        step(); return v
    }

    fun square(pulseWidth: Float = 0.5f): Float {
        var v = if (phase < pulseWidth) 1f else -1f
        v += polyBlep(phase)
        var t2 = phase + (1f - pulseWidth); if (t2 >= 1f) t2 -= 1f
        v -= polyBlep(t2)
        step(); return v
    }
}

/**
 * Two-operator FM "tine" electric piano operator pair.
 * carrier = sin(ph_c + index * sin(ph_m)); plus a bright bell partial for the hammer "tine".
 */
class FmTine(private val sampleRate: Float) {
    private var phC = 0f
    private var phM = 0f
    private var phT = 0f
    private var incC = 0f
    private var incM = 0f
    private var incT = 0f
    fun setFrequency(hz: Float, modRatio: Float = 1f, tineRatio: Float = 7f) {
        incC = hz / sampleRate
        incM = hz * modRatio / sampleRate
        incT = hz * tineRatio / sampleRate
    }
    fun reset() { phC = 0f; phM = 0f; phT = 0f }
    /** @param index modulation index (0..3) @param tine tine partial amount (0..1) */
    fun process(index: Float, tine: Float): Float {
        val m = sin((phM * TWO_PI).toFloat())
        val c = sin(((phC + index * 0.15f * m) * TWO_PI).toFloat())
        val t = sin((phT * TWO_PI).toFloat()) * tine
        phC += incC; if (phC >= 1f) phC -= 1f
        phM += incM; if (phM >= 1f) phM -= 1f
        phT += incT; if (phT >= 1f) phT -= 1f
        return c + t
    }
}

/** Karplus-Strong plucked string (used for the upright-bass pluck transient and the lo-fi guitar). */
class PluckString(private val sampleRate: Float, seed: Int) {
    private val line = DelayLine((sampleRate / 30f).toInt() + 4)
    private val rng = Rng(seed)
    private var delay = 100f
    private var damp = 0.5f
    private var last = 0f
    var brightness = 0.5f
    fun pluck(hz: Float, amplitude: Float) {
        delay = (sampleRate / hz).coerceIn(2f, (line.size - 3).toFloat())
        damp = 0.5f
        // excite with filtered noise burst of one period
        var lp = 0f
        val n = delay.toInt()
        for (i in 0 until n) {
            val w = rng.nextBipolar()
            lp += (w - lp) * (0.2f + brightness * 0.7f)
            line.write(lp * amplitude)
        }
    }
    fun process(decay: Float): Float {
        val y = line.read(delay)
        val out = (y + last) * 0.5f * decay
        last = y
        line.write(out)
        return out
    }
}
