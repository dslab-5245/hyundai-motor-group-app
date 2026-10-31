package io.drivesymphony.engine

import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-time factor of the synthesis path on this JVM. Pleos budgets < 5% CPU for a non-navigation app while
 * driving; a desktop JVM figure well under 10% leaves headroom for a slower automotive SoC + ART. The light
 * profile (one shared reverb, no doubler) is what the app falls back to when the budget is exceeded.
 */
class EnginePerfTest {
    private val sr = 48000
    private val block = 256

    /** Real-time factor (render time / audio time) for 30 s of the safe scenario after a 5 s JIT warm-up. */
    private fun rtf(g: Genre, profile: RenderProfile): Double {
        val buf = FloatArray(block * 2)
        val engine = SymphonyEngine(sr, g, seed = 11, hourOfDay = 15)
        engine.profile = profile
        repeat(sr * 5 / block) { engine.render(buf, block) }
        // two 15 s passes, best of two: a busy host (an emulator, a build) inflates one pass but rarely both
        val seconds = 15
        val blocks = sr * seconds / block
        var t = 0.0
        var best = Double.MAX_VALUE
        repeat(2) {
            val t0 = System.nanoTime()
            for (i in 0 until blocks) {
                if (i % 8 == 0) { engine.update(Scenarios.safe.signalsAt(t), 0.0427f); t += 0.0427 }
                engine.render(buf, block)
            }
            best = minOf(best, (System.nanoTime() - t0) / 1e6 / (seconds * 1000.0))
        }
        return best
    }

    @Test
    fun renderIsManyTimesFasterThanRealtime() {
        for (g in Genre.values()) {
            val r = rtf(g, RenderProfile.FULL)
            println("PERF $g FULL: real-time factor ${"%.3f".format(r)} (${"%.1f".format(r * 100)}% of one core)")
            assertTrue(r < 0.25, "$g render too slow: rtf=$r")
        }
    }

    @Test
    fun lightProfileIsNotMoreExpensive() {
        for (g in Genre.values()) {
            // interleaved, best of four each: the host also runs the emulator and the build while this measures
            val full = minOf(rtf(g, RenderProfile.FULL), rtf(g, RenderProfile.FULL))
            val light = minOf(rtf(g, RenderProfile.LIGHT), rtf(g, RenderProfile.LIGHT))
            println("PERF $g LIGHT: ${"%.1f".format(light * 100)}% vs FULL ${"%.1f".format(full * 100)}% of one core (${"%.0f".format(light / full * 100)}%)")
            assertTrue(light < 0.25, "$g light render too slow: rtf=$light")
            assertTrue(light <= full * 1.1, "$g light profile must not cost more than full (light $light, full $full)")
        }
    }
}
