package io.drivesymphony.engine

import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-time factor of the synthesis path on this JVM. Pleos budgets < 5% CPU for a non-navigation app while
 * driving; a desktop JVM figure well under 10% leaves headroom for a slower automotive SoC + ART.
 */
class EnginePerfTest {
    @Test
    fun renderIsManyTimesFasterThanRealtime() {
        val sr = 48000
        val block = 256
        val buf = FloatArray(block * 2)
        for (g in Genre.values()) {
            val engine = SymphonyEngine(sr, g, seed = 11, hourOfDay = 15)
            // warm up (JIT) for 5 s of audio
            repeat(sr * 5 / block) { engine.render(buf, block) }
            val seconds = 30
            val blocks = sr * seconds / block
            var t = 0.0
            val t0 = System.nanoTime()
            for (i in 0 until blocks) {
                if (i % 8 == 0) { engine.update(Scenarios.safe.signalsAt(t), 0.0427f); t += 0.0427 }
                engine.render(buf, block)
            }
            val ms = (System.nanoTime() - t0) / 1e6
            val rtf = ms / (seconds * 1000.0)
            println("PERF $g: ${"%.0f".format(ms)} ms for $seconds s of audio -> real-time factor ${"%.3f".format(rtf)} (${"%.1f".format(rtf * 100)}% of one core)")
            assertTrue(rtf < 0.25, "$g render too slow: rtf=$rtf")
        }
    }
}
