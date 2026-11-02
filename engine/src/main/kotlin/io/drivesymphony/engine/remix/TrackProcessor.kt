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

/**
 * "Remix" mode: live variation of a track the app itself plays (local files / bundled stems).
 * Smooth driving = the track opens up (full band, wide stereo, light air). Erratic driving =
 * the track goes "underwater": lowpass, tape wobble, narrower, quieter. Never harsh distortion.
 *
 * Input/output are interleaved stereo float buffers. Stateless with respect to the song: it can be
 * dropped in front of any decoded PCM stream.
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
    private val sag = TapeSag(sr)   // the same stumble as the generative engine, a little shallower
    private var lastPulse = 0f

    fun process(input: FloatArray, output: FloatArray, frames: Int, p: MusicParams) {
        // remix mapping is gentler than the generative one: a song should stay recognizable
        val cutoff = (p.cutoffHz * 1.3f).coerceIn(600f, 18000f)
        // a non-graced penalty (pulse 1) sags the tape once; a graced one (pulse 0.5) only dips the filter
        if (p.pulse > lastPulse + 0.3f && p.pulse >= 0.7f) sag.trigger(0.16f + 0.2f * p.pulse, 0.45f)
        lastPulse = p.pulse
        lpL.set(cutoff, 0.02f); lpR.set(cutoff, 0.02f)
        wobL.depth = p.wobble * 0.7f; wobR.depth = p.wobble * 0.7f
        crushL.amount = p.crush * 0.5f; crushR.amount = p.crush * 0.5f
        val targetGain = dbToGain(p.gainDb * 0.8f)
        var o = 0
        for (i in 0 until frames) {
            var l = input[o]; var r = input[o + 1]
            l = lpL.process(l); r = lpR.process(r)
            l = crushL.process(l); r = crushR.process(r)
            l = wobL.process(l); r = wobR.process(r)
            sag.process(l, r); l = sag.outL; r = sag.outR
            val rv = sRev.process(p.reverbMix * 0.35f)
            l += revL.process(l) * rv; r += revR.process(r) * rv
            val w = sWidth.process(0.6f + p.width * 0.4f)
            val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f * w
            l = mid + side; r = mid - side
            val g = sGain.process(targetGain)
            output[o] = limL.process(l * g); output[o + 1] = limR.process(r * g)
            o += 2
        }
    }
}
