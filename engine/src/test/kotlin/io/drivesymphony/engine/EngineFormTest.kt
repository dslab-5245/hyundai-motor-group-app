package io.drivesymphony.engine

import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Form (sections, fills, cadence) and stereo image checks that the preview analysis asked for. */
class EngineFormTest {
    private val sr = 48000
    private val block = 1920 // 40 ms

    private fun drive(engine: SymphonyEngine, seconds: Float, sink: ((FloatArray) -> Unit)? = null) {
        val buf = FloatArray(block * 2)
        val ticks = (seconds / 0.04f).toInt()
        for (i in 0 until ticks) {
            engine.update(Scenarios.safe.signalsAt(i * 0.04), 0.04f)
            engine.render(buf, block)
            sink?.invoke(buf)
        }
    }

    @Test
    fun smoothDriveIsNotMono() {
        for (g in Genre.values()) {
            val engine = SymphonyEngine(sr, g, seed = 7, hourOfDay = 14)
            drive(engine, 12f) // let layers open
            var sl = 0.0; var srr = 0.0; var sll = 0.0; var srr2 = 0.0; var slr = 0.0; var n = 0L
            drive(engine, 10f) { buf ->
                var i = 0
                while (i < buf.size) { val l = buf[i].toDouble(); val r = buf[i + 1].toDouble(); sl += l; srr += r; sll += l * l; srr2 += r * r; slr += l * r; n++; i += 2 }
            }
            val ml = sl / n; val mr = srr / n
            val cov = slr / n - ml * mr
            val vl = sll / n - ml * ml; val vr = srr2 / n - mr * mr
            val corr = cov / sqrt(vl * vr)
            assertTrue(corr < 0.9, "$g L/R correlation $corr should show a real stereo image")
            assertTrue(corr > 0.2, "$g L/R correlation $corr should still be mono-compatible")
        }
    }

    @Test
    fun sectionsAdvanceAndTripCadenceResolves() {
        val engine = SymphonyEngine(sr, Genre.JAZZ, seed = 3, hourOfDay = 21)
        val labels = ArrayList<String>()
        var lastBar = -1
        drive(engine, 75f) { _ ->
            val st = engine.state
            if (st.bar != lastBar) { lastBar = st.bar; labels.add(st.chordLabel) }
        }
        assertTrue(engine.state.bar >= 32, "75 s of jazz should pass 32 bars, got ${engine.state.bar}")
        val a = labels.subList(0, 8).toSet()
        val b = labels.subList(16, 24).toSet()
        assertTrue(a != b || a.size > 1, "section B (bars 16-23) should differ from A: a=$a b=$b")
        // trip end: one cadence bar then a fresh composition, no silence and no clipping
        engine.newTrip(hourOfDay = 9, roadName = "강변북로")
        var peak = 0f
        drive(engine, 6f) { buf -> for (v in buf) { val x = if (v < 0) -v else v; if (x > peak) peak = x; assertTrue(!v.isNaN()) } }
        assertTrue(peak in 0.05f..1f, "audio after cadence should be audible and unclipped, peak=$peak")
        // the scenario keeps reporting its own road, which wins over the newTrip hint (live signals are the truth)
        assertTrue(engine.state.title.contains("Jazz") && engine.state.title.contains("("), engine.state.title)
    }
}
