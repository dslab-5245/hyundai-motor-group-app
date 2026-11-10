package io.drivesymphony.engine.remix

import io.drivesymphony.engine.director.MusicParams
import io.drivesymphony.engine.dsp.Crush
import io.drivesymphony.engine.dsp.Limiter
import io.drivesymphony.engine.dsp.OnePole
import io.drivesymphony.engine.dsp.Reverb
import io.drivesymphony.engine.dsp.Svf
import io.drivesymphony.engine.dsp.TapeSag
import io.drivesymphony.engine.dsp.TapeWobble
import io.drivesymphony.engine.dsp.dbToGain
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * "Remix" mode: live variation of a track the app itself plays (local files / the bundled sample).
 * Smooth driving = the song exactly as recorded (full band, full width, original speed). Rough driving = the song
 * goes under water and the tape drags: the lowpass closes geometrically from 18 kHz down to 500 Hz, the tape slows
 * by up to [MAX_DRAG] (pitch and tempo sink together), the level drops up to ~9 dB, the stereo image narrows and
 * a wash of reverb comes up. A fresh penalty sags the tape once on top of that. Never harsh distortion.
 * The dive is immediate but the song resurfaces slowly ([RELEASE_SEC]): the flow score recovers within seconds, and a
 * song that popped straight back would read as nothing having happened.
 *
 * The amount of "rough" is the director's effectiveness (read back from [MusicParams.width], which the director maps
 * as 0.45 + eff * 0.55 with the driver's feedback intensity already applied), plus the penalty pulse.
 *
 * Input/output are interleaved stereo float buffers. Stateless with respect to the song: it can be dropped in front
 * of any decoded PCM stream. The player reads the song at [speed] before calling [process].
 */
class TrackProcessor(sampleRate: Int, seed: Int = 7) {
    private val sr = sampleRate.toFloat()
    private val lpL = Svf(sr); private val lpR = Svf(sr)
    private val wobL = TapeWobble(sr, seed); private val wobR = TapeWobble(sr, seed + 1)
    private val crushL = Crush(sr); private val crushR = Crush(sr)
    private val revL = Reverb(sr, 0); private val revR = Reverb(sr, 19)
    private val limL = Limiter(sr); private val limR = Limiter(sr)
    private val sGain = OnePole(sr).apply { setTimeConstant(0.03f); reset(1f) }
    private val sWidth = OnePole(sr).apply { setTimeConstant(0.1f); reset(1f) }
    private val sRev = OnePole(sr).apply { setTimeConstant(0.1f) }
    private val sag = TapeSag(sr)   // the same stumble gesture as the generative engine
    private var lastPulse = 0f

    /** Tape speed the player should read the song at: 1 = as recorded, down to 1 - [MAX_DRAG]. Updated once per block. */
    var speed = 1f
        private set

    /** How far under water the song is (0 = as recorded, 1 = fully erratic): instant attack, [RELEASE_SEC] release. */
    var dark = 0f
        private set

    fun process(input: FloatArray, output: FloatArray, frames: Int, p: MusicParams) {
        val instant = 1f - ((p.width - 0.45f) / 0.55f).coerceIn(0f, 1f)
        dark = if (instant >= dark) instant else (dark - frames / (RELEASE_SEC * sr)).coerceAtLeast(instant)
        val eff = 1f - dark
        // lowpass: 18 kHz when smooth, geometrically down to 500 Hz when erratic; a penalty pulse dives it further
        // toward DIVE_HZ, the same shape as the generative mix, and it resurfaces with the pulse
        var cutoff = LP_MIN_HZ * (LP_MAX_HZ / LP_MIN_HZ).pow(eff)
        cutoff = exp(ln(cutoff) * (1f - p.pulse) + ln(DIVE_HZ) * p.pulse)
        // a fresh non-graced penalty (pulse >= 0.7) sags the tape once: pitch and tempo dip for ~0.6 s, then snap back
        if (p.pulse > lastPulse + 0.3f && p.pulse >= 0.7f) sag.trigger(0.24f + 0.3f * p.pulse, 0.6f)
        lastPulse = p.pulse
        lpL.set(cutoff.coerceIn(LP_MIN_HZ * 0.6f, LP_MAX_HZ), 0.02f); lpR.set(cutoff.coerceIn(LP_MIN_HZ * 0.6f, LP_MAX_HZ), 0.02f)
        wobL.depth = p.wobble; wobR.depth = p.wobble
        crushL.amount = p.crush * 0.5f; crushR.amount = p.crush * 0.5f
        val targetGain = dbToGain(-5f * dark - 3.5f * p.pulse)
        val revTarget = 0.06f + 0.32f * dark
        val widthTarget = 0.45f + 0.55f * eff
        // tape speed follows the drive with a ~0.4 s time constant, stepped once per block (no zipper: the player
        // interpolates and a block is ~5 ms)
        val speedTarget = 1f - MAX_DRAG * dark
        speed += (speedTarget - speed) * (frames / (0.4f * sr)).coerceIn(0f, 1f)
        var o = 0
        for (i in 0 until frames) {
            var l = input[o]; var r = input[o + 1]
            l = lpL.process(l); r = lpR.process(r)
            l = crushL.process(l); r = crushR.process(r)
            l = wobL.process(l); r = wobR.process(r)
            sag.process(l, r); l = sag.outL; r = sag.outR
            val rv = sRev.process(revTarget)
            l += revL.process(l) * rv; r += revR.process(r) * rv
            val w = sWidth.process(widthTarget)
            val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f * w
            l = mid + side; r = mid - side
            val g = sGain.process(targetGain)
            output[o] = limL.process(l * g); output[o + 1] = limR.process(r * g)
            o += 2
        }
    }

    companion object {
        /** Maximum tape slow-down while driving erratically: 8 % = about 1.4 semitones lower and slower. */
        const val MAX_DRAG = 0.08f
        const val LP_MAX_HZ = 18000f
        const val LP_MIN_HZ = 500f
        /** How long the song takes to surface again after the drive has calmed down. */
        const val RELEASE_SEC = 6f
        private const val DIVE_HZ = 420f
    }
}
