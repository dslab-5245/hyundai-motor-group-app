package io.drivesymphony.engine.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Small, allocation-free DSP toolkit used by the Drive Symphony engine.
 * Everything works on mono float samples; stereo is handled by instantiating per channel.
 * All classes are deterministic: given the same seed and inputs they render the same audio,
 * which lets unit tests check the engine numerically.
 */
const val TWO_PI = 2.0 * PI

fun clamp(x: Float, lo: Float, hi: Float): Float = if (x < lo) lo else if (x > hi) hi else x
fun clamp01(x: Float): Float = clamp(x, 0f, 1f)
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
fun midiToHz(midi: Float): Float = 440f * 2f.pow((midi - 69f) / 12f)
fun midiToHz(midi: Int): Float = midiToHz(midi.toFloat())

/** Cheap, smooth soft clipper (tanh-like, no library call). Keeps peaks under +-1. */
fun softClip(x: Float): Float {
    val a = abs(x)
    return if (a < 0.5f) x else {
        val s = if (x < 0f) -1f else 1f
        s * (0.5f + (1f - exp(-(a - 0.5f) * 2f)) * 0.5f)
    }
}

/**
 * 4096-point sine table with linear interpolation: sin(2*pi*phase) for any phase (wrapped into [0,1)).
 * Max error ~3e-7, i.e. far below the 24-bit noise floor, at a fraction of the cost of a libm sin() per sample.
 * Used by every per-sample oscillator; coefficient maths (filters, LFO rates) keep the exact sin().
 */
object SineTable {
    private const val N = 4096
    private val tbl = FloatArray(N + 2) { sin(it * TWO_PI / N).toFloat() }
    fun sin01(phase: Float): Float {
        var p = phase - phase.toInt()
        if (p < 0f) p += 1f
        val x = p * N
        val i = x.toInt()
        val a = tbl[i]
        return a + (tbl[i + 1] - a) * (x - i)
    }
}

/** xorshift32 PRNG: fast, deterministic, good enough for noise and musical choices. */
class Rng(seed: Int) {
    private var s: Int = if (seed == 0) 0x9E3779B9.toInt() else seed
    fun nextInt(): Int { var x = s; x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); s = x; return x }
    /** Uniform in [0,1). */
    fun nextFloat(): Float = (nextInt() ushr 8) * (1f / 16777216f)
    /** Uniform in [-1,1). */
    fun nextBipolar(): Float = nextFloat() * 2f - 1f
    fun nextInt(bound: Int): Int = (nextFloat() * bound).toInt().coerceIn(0, bound - 1)
    fun chance(p: Float): Boolean = nextFloat() < p
}

/** One-pole lowpass used for parameter smoothing and gentle tone shaping. */
class OnePole(private val sampleRate: Float) {
    private var a = 0f
    private var z = 0f
    fun setCutoff(hz: Float) { a = (1f - exp((-TWO_PI * hz / sampleRate).toFloat())).coerceIn(0f, 1f) }
    fun setTimeConstant(seconds: Float) { a = if (seconds <= 0f) 1f else (1f - exp(-1f / (seconds * sampleRate))) }
    fun process(x: Float): Float { z += a * (x - z); return z }
    fun reset(v: Float = 0f) { z = v }
    val value: Float get() = z
}

/** Chamberlin state-variable filter (12 dB/oct). Stable for cutoff < sampleRate/6. */
class Svf(private val sampleRate: Float) {
    private var low = 0f
    private var band = 0f
    private var f = 0f
    private var q = 1f
    private var lastHigh = 0f
    private var lastC = -1f
    private var lastR = -1f

    fun set(cutoffHz: Float, resonance: Float) {
        val c = cutoffHz.coerceIn(20f, sampleRate / 6f)
        // the coefficient costs a sin(); instruments sweep the cutoff per sample, so skip moves under 0.1 % (inaudible)
        val d = c - lastC
        if (resonance == lastR && d < c * 0.001f && d > -c * 0.001f) return
        lastC = c; lastR = resonance
        f = (2f * sin((PI * c / sampleRate).toFloat())).coerceIn(0.0001f, 1.2f)
        q = (1f - resonance.coerceIn(0f, 0.98f))
        if (q < 0.05f) q = 0.05f
    }

    /** Returns lowpass output; use [high]/[bandpass] for the others after calling process. */
    fun process(x: Float): Float {
        // two passes (oversampled) for better high-cutoff stability
        var out = 0f
        for (i in 0 until 2) {
            low += f * band
            lastHigh = x - low - q * band
            band += f * lastHigh
            out = low
        }
        return out
    }
    val high: Float get() = lastHigh
    val bandpass: Float get() = band
    fun reset() { low = 0f; band = 0f; lastHigh = 0f }
}

/** Linear ADSR envelope with exponential-ish release. Values in seconds. */
class Adsr(private val sampleRate: Float) {
    var attack = 0.01f
    var decay = 0.1f
    var sustain = 0.7f
    var release = 0.3f
    private var stage = 0 // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release
    private var level = 0f
    val isActive: Boolean get() = stage != 0
    val isReleasing: Boolean get() = stage == 4
    val current: Float get() = level

    fun noteOn() { stage = 1 }
    fun noteOff() { if (stage != 0) stage = 4 }
    fun kill() { stage = 0; level = 0f }

    fun process(): Float {
        when (stage) {
            1 -> { level += 1f / (attack * sampleRate).coerceAtLeast(1f); if (level >= 1f) { level = 1f; stage = 2 } }
            2 -> { level -= (1f - sustain) / (decay * sampleRate).coerceAtLeast(1f); if (level <= sustain) { level = sustain; stage = 3 } }
            3 -> level = sustain
            4 -> { level -= level / (release * sampleRate * 0.25f).coerceAtLeast(1f); if (level < 0.0005f) { level = 0f; stage = 0 } }
        }
        return level
    }
}

/**
 * Low-frequency oscillator producing a sine in [-1,1]. Coupled-form ("magic circle") recursion: one multiply-add
 * pair per sample and no trig call. The recursion is symplectic, so the amplitude does not drift.
 */
class Lfo(private val sampleRate: Float, hz: Float, phase: Float = 0f) {
    private var s = sin((phase * TWO_PI).toFloat())
    private var c = cos((phase * TWO_PI).toFloat())
    private var eps = 0f
    var rateHz: Float = hz
        set(v) { field = v; eps = 2f * sin((PI * v / sampleRate).toFloat()) }
    init { rateHz = hz }
    fun process(): Float {
        val out = s
        s += eps * c
        c -= eps * s
        return out
    }
}

/** Fractional delay line with linear interpolation (used for chorus, tape wobble, reverb). */
class DelayLine(maxSamples: Int) {
    private val buf = FloatArray(maxSamples.coerceAtLeast(2))
    private var w = 0
    val size: Int get() = buf.size
    fun write(x: Float) { buf[w] = x; w++; if (w >= buf.size) w = 0 }
    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(1f, (buf.size - 2).toFloat())
        val rp = w - d
        var i = rp.toInt()
        val frac = rp - i
        if (i < 0) i += buf.size
        var j = i - 1
        if (j < 0) j += buf.size
        // interpolate between sample at i (older by frac) and j
        val a = buf[i]
        val b = buf[j]
        return a + (b - a) * frac
    }
    fun readInt(delaySamples: Int): Float {
        var i = w - delaySamples.coerceIn(1, buf.size - 1)
        if (i < 0) i += buf.size
        return buf[i]
    }
    fun clear() { buf.fill(0f); w = 0 }
}

/**
 * Tape wobble / chorus: modulated short delay. depth 0..1 maps to +-(0..6 ms) pitch wobble at a slow rate.
 * At depth 0 it is a plain 8 ms delay with dry mixing, so it is transparent.
 */
class TapeWobble(private val sampleRate: Float, seed: Int) {
    private val line = DelayLine((sampleRate * 0.05f).toInt())
    private val lfo = Lfo(sampleRate, 0.35f, Rng(seed).nextFloat())
    private val lfo2 = Lfo(sampleRate, 2.7f, Rng(seed + 7).nextFloat())
    private val smooth = OnePole(sampleRate).apply { setTimeConstant(0.2f) }
    var depth = 0f
    fun process(x: Float): Float {
        line.write(x)
        val d = smooth.process(depth)
        val base = 0.008f * sampleRate
        val mod = (lfo.process() * 0.75f + lfo2.process() * 0.25f) * d * 0.006f * sampleRate
        // Always return the delayed signal: at depth 0 this is a constant 8 ms latency (transparent),
        // at higher depths the modulated read position produces the pitch wobble. No dry mix -> no comb filtering.
        return line.read(base + mod)
    }
}

/** Light bit/sample-rate reduction for the "dusty" lo-fi character. amount 0..1. */
class Crush(private val sampleRate: Float) {
    var amount = 0f
    private var hold = 0f
    private var phase = 0f
    fun process(x: Float): Float {
        if (amount <= 0.001f) return x
        val step = 1f + amount * 5f // decimation factor 1..6
        phase += 1f
        if (phase >= step) {
            phase -= step
            val levels = (4096f - amount * 3800f)
            hold = Math.round(x * levels) / levels
        }
        return x + (hold - x) * amount
    }
}

/** Freeverb-style reverb (8 combs + 4 allpasses) for one channel. */
class Reverb(sampleRate: Float, stereoOffset: Int = 0) {
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTunings = intArrayOf(556, 441, 341, 225)
    private val scale = sampleRate / 44100f
    private val combs = Array(8) { DelayLine((combTunings[it] * scale).toInt() + stereoOffset + 2) }
    private val combLen = IntArray(8) { (combTunings[it] * scale).toInt() + stereoOffset }
    private val combStore = FloatArray(8)
    private val aps = Array(4) { DelayLine((apTunings[it] * scale).toInt() + stereoOffset + 2) }
    private val apLen = IntArray(4) { (apTunings[it] * scale).toInt() + stereoOffset }
    var roomSize = 0.82f
    var damping = 0.35f

    /** Silences the tail; used when a channel's reverb was idle (light profile) and must not replay stale audio. */
    fun clear() { for (c in combs) c.clear(); for (a in aps) a.clear(); combStore.fill(0f) }

    fun process(x: Float): Float {
        val input = x * 0.015f
        var out = 0f
        for (i in 0 until 8) {
            val y = combs[i].readInt(combLen[i])
            combStore[i] = y * (1f - damping) + combStore[i] * damping
            combs[i].write(input + combStore[i] * roomSize)
            out += y
        }
        for (i in 0 until 4) {
            val bufOut = aps[i].readInt(apLen[i])
            val v = out
            aps[i].write(v + bufOut * 0.5f)
            out = bufOut - v
        }
        return out
    }
}

/** Simple peak limiter / compressor to keep the master out of clipping without pumping. */
class Limiter(private val sampleRate: Float) {
    private var env = 0f
    private val attack = exp(-1f / (0.002f * sampleRate))
    private val release = exp(-1f / (0.12f * sampleRate))
    var threshold = 0.85f
    fun process(x: Float): Float {
        val a = abs(x)
        env = if (a > env) attack * env + (1f - attack) * a else release * env + (1f - release) * a
        val g = if (env > threshold) threshold / env else 1f
        return softClip(x * g)
    }
}

/** Pink-ish noise (Paul Kellet approximation) for textures. */
class PinkNoise(seed: Int) {
    private val rng = Rng(seed)
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    fun process(): Float {
        val w = rng.nextBipolar()
        b0 = 0.99765f * b0 + w * 0.0990460f
        b1 = 0.96300f * b1 + w * 0.2965164f
        b2 = 0.57000f * b2 + w * 1.0526913f
        return (b0 + b1 + b2 + w * 0.1848f) * 0.25f
    }
}

/** DC blocker to keep subs clean after saturation. */
class DcBlock {
    private var x1 = 0f; private var y1 = 0f
    fun process(x: Float): Float { val y = x - x1 + 0.995f * y1; x1 = x; y1 = y; return y }
}

/** Equal-power pan: returns (left, right) gains for pan in [-1,1]. */
fun panGains(pan: Float): Pair<Float, Float> {
    val p = (clamp(pan, -1f, 1f) + 1f) * 0.25f * PI.toFloat()
    return kotlin.math.cos(p) to sin(p)
}

/** Converts a time constant to a per-sample coefficient; helper for hand-rolled smoothing. */
fun smoothingCoef(seconds: Float, sampleRate: Float): Float =
    if (seconds <= 0f) 1f else (1f - exp(-1f / (seconds * sampleRate)))

/** Natural log helper for log-scaled parameter mapping. */
fun logMap(t: Float, lo: Float, hi: Float): Float = exp(ln(lo) + (ln(hi) - ln(lo)) * clamp01(t))

/** sqrt helper kept here to avoid repeated imports in instrument code. */
fun fsqrt(x: Float): Float = sqrt(x)

/** Bilinear warp for one-pole highpass. */
fun highpassCoef(hz: Float, sampleRate: Float): Float {
    val k = tan((PI * hz / sampleRate).toFloat())
    return (1f - k) / (1f + k)
}
