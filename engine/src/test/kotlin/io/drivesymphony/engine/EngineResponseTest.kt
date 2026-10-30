package io.drivesymphony.engine

import io.drivesymphony.engine.scenario.DriveScenario
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How fast does the music answer a bad event? Renders the A/B scenario pair with the same seed (sample-identical
 * until the event at 20.3 s) and measures, on the audio itself, when the event version drops 3 dB below the
 * clean version, and on the control stream when the master cutoff has halved.
 */
class EngineResponseTest {
    private val sr = 48000
    private val block = 256
    private val eventSec = 20.3

    private class Run(val audio: FloatArray, val cutoff: FloatArray, val gainDb: FloatArray, val controlDt: Float)

    private fun render(scenario: DriveScenario, seconds: Float): Run {
        val engine = SymphonyEngine(sr, Genre.LOFI, 777, 18, "올림픽대로")
        val frames = (seconds * sr).toInt()
        val audio = FloatArray(frames * 2)
        val blk = FloatArray(block * 2)
        val dt = 0.04f
        val cut = ArrayList<Float>(); val gain = ArrayList<Float>()
        var f = 0; var nextControl = 0.0
        while (f < frames) {
            val t = f.toDouble() / sr
            while (nextControl <= t) {
                engine.update(scenario.signalsAt(nextControl), dt)
                cut += engine.params.cutoffHz; gain += engine.params.gainDb
                nextControl += dt
            }
            val n = minOf(block, frames - f)
            engine.render(blk, n)
            System.arraycopy(blk, 0, audio, f * 2, n * 2)
            f += n
        }
        return Run(audio, cut.toFloatArray(), gain.toFloatArray(), dt)
    }

    private fun rmsWindows(a: FloatArray, winFrames: Int): FloatArray {
        val n = a.size / 2 / winFrames
        val out = FloatArray(n)
        for (w in 0 until n) {
            var s = 0.0
            for (i in w * winFrames * 2 until (w + 1) * winFrames * 2) s += a[i] * a[i].toDouble()
            out[w] = sqrt(s / (winFrames * 2)).toFloat()
        }
        return out
    }

    @Test
    fun musicDropsThreeDbWithinAFractionOfASecondAfterABadEvent() {
        val clean = render(Scenarios.abClean, 24f)
        val event = render(Scenarios.abEvent, 24f)
        // identical before the brake is first touched at 20.0 s
        val pre = (20.0 * sr).toInt() * 2
        assertTrue(clean.audio.copyOf(pre).contentEquals(event.audio.copyOf(pre)), "A/B must be sample-identical before the event")

        val win = sr / 50 // 20 ms
        val rc = rmsWindows(clean.audio, win); val re = rmsWindows(event.audio, win)
        val startW = (eventSec * 50).toInt()
        var hit = -1
        var w = startW
        while (w < rc.size - 5) {
            var ok = true
            for (k in 0 until 5) { // 100 ms sustained
                val c = rc[w + k]; val e = re[w + k]
                if (c < 1e-4f || 20f * log10(e / c) > -3f) { ok = false; break }
            }
            if (ok) { hit = w; break }
            w++
        }
        assertTrue(hit >= 0, "the event version should drop at least 3 dB below the clean version")
        val latencyMs = (hit / 50.0 - eventSec) * 1000
        // control-side: cutoff halved relative to the value just before the event
        val ci = (eventSec / clean.controlDt).toInt()
        val before = event.cutoff[ci - 1]
        val halved = (ci until event.cutoff.size).first { event.cutoff[it] < before * 0.5f }
        val cutoffMs = (halved - ci) * clean.controlDt * 1000
        val minGain = event.gainDb.drop(ci).take((2.0 / clean.controlDt).toInt()).minOrNull()!!
        println("response: audio -3 dB after ${"%.0f".format(latencyMs)} ms, cutoff halved after ${"%.0f".format(cutoffMs)} ms " +
            "(${"%.0f".format(before)} -> ${"%.0f".format(event.cutoff[halved])} Hz), min gain ${"%.1f".format(minGain)} dB within 2 s")
        assertTrue(latencyMs <= 300.0, "audio should be 3 dB down within 300 ms, took $latencyMs ms")
        assertTrue(cutoffMs <= 300.0, "cutoff should halve within 300 ms, took $cutoffMs ms")
    }
}
