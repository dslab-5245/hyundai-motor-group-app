package io.drivesymphony.engine

import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The light (CPU-saving) profile must keep the music and switch both ways without a click. */
class EngineProfileTest {
    private val sr = 48000
    private val block = 256

    /** Renders [seconds] of the safe scenario; [switches] maps a block index to the profile to request there. */
    private fun render(seconds: Float, switches: Map<Int, RenderProfile>): Pair<FloatArray, SymphonyEngine> {
        val e = SymphonyEngine(sr, Genre.LOFI, 7, 15, "올림픽대로")
        val frames = (seconds * sr).toInt() / block * block
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var pos = 0; var t = 0.0; var idx = 0
        while (pos < frames) {
            if (idx % 8 == 0) { e.update(Scenarios.safe.signalsAt(t), 0.0427f); t += 0.0427 }
            switches[idx]?.let { e.profile = it }
            e.render(buf, block)
            System.arraycopy(buf, 0, out, pos * 2, block * 2)
            pos += block; idx++
        }
        return out to e
    }

    private fun blockAt(sec: Float) = (sec * sr).toInt() / block
    private fun rmsDb(x: FloatArray, fromSec: Float, toSec: Float): Float {
        val a = (fromSec * sr).toInt() * 2; val b = (toSec * sr).toInt() * 2
        var acc = 0.0
        for (i in a until b) acc += x[i].toDouble() * x[i]
        return 20f * log10(sqrt(acc / (b - a)).toFloat() + 1e-9f)
    }

    @Test
    fun lightProfileKeepsTheMusic() {
        val (full, _) = render(8f, emptyMap())
        val (light, e) = render(8f, mapOf(blockAt(3f) to RenderProfile.LIGHT))
        // identical until the switch: the profile is deterministic and inert while FULL
        var maxDiff = 0f
        for (i in 0 until (2.9f * sr).toInt() * 2) maxDiff = maxOf(maxDiff, abs(full[i] - light[i]))
        assertEquals(0f, maxDiff, "FULL renders must be bit-identical before the switch")
        val dbFull = rmsDb(full, 4.5f, 8f); val dbLight = rmsDb(light, 4.5f, 8f)
        println("profile: FULL ${"%.2f".format(dbFull)} dBFS vs LIGHT ${"%.2f".format(dbLight)} dBFS after the switch")
        assertTrue(abs(dbFull - dbLight) < 2f, "light profile should keep loudness (full $dbFull, light $dbLight)")
        assertTrue(e.state.light, "state should report the light profile")
    }

    @Test
    fun profileSwitchesDoNotClick() {
        val (full, _) = render(8f, emptyMap())
        val (sw, e) = render(8f, mapOf(blockAt(2f) to RenderProfile.LIGHT, blockAt(5f) to RenderProfile.FULL))
        // the two renders share every note and drum hit; a click would be a sample-to-sample jump the reference
        // does not have in the same 10 ms window
        val win = sr / 100
        var worst = 0f; var worstAt = 0f
        var w = 0
        while ((w + 1) * win < full.size / 2) {
            var dRef = 0f; var dSw = 0f
            for (i in w * win + 1 until (w + 1) * win) {
                for (ch in 0..1) {
                    dRef = maxOf(dRef, abs(full[i * 2 + ch] - full[(i - 1) * 2 + ch]))
                    dSw = maxOf(dSw, abs(sw[i * 2 + ch] - sw[(i - 1) * 2 + ch]))
                }
            }
            val excess = dSw - (dRef * 1.5f + 0.004f)
            if (excess > worst) { worst = excess; worstAt = w * win / sr.toFloat() }
            assertTrue(excess <= 0f, "profile switch clicked at ${w * win / sr.toFloat()} s: delta $dSw vs reference $dRef")
            w++
        }
        println("profile switches: no window exceeds the reference (worst excess ${"%.4f".format(worst)} at ${"%.2f".format(worstAt)} s)")
        assertTrue(!e.state.light, "state should be back on the full profile")
    }
}
