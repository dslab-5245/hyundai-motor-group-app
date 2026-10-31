package io.drivesymphony.engine

import io.drivesymphony.engine.dsp.Ducker
import io.drivesymphony.engine.dsp.GlueComp
import io.drivesymphony.engine.dsp.TapeSag
import io.drivesymphony.engine.dsp.TapeSat
import io.drivesymphony.engine.music.StereoOut
import io.drivesymphony.engine.music.Texture
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The musical gestures behind "the music sinks and recovers": tape sag, kick pumping, riser, master glue. */
class EngineGestureTest {
    private val sr = 48000

    private fun zeroCrossings(a: FloatArray, from: Int, to: Int): Int {
        var n = 0
        for (i in from + 1 until to) if ((a[i - 1] < 0f) != (a[i] < 0f)) n++
        return n
    }

    @Test
    fun tapeSagDipsThePitchAndSnapsBackToRealTime() {
        val sag = TapeSag(sr.toFloat())
        val n = sr * 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val x = sin(2 * Math.PI * 440.0 * i / sr).toFloat()
            if (i == sr / 2) sag.trigger(0.25f, 0.4f)
            sag.process(x, x); out[i] = sag.outL
        }
        val nominal = zeroCrossings(out, 0, sr / 2)                              // 0.5 s untouched: ~440 crossings
        val dip = zeroCrossings(out, sr / 2 + sr / 10, sr / 2 + sr * 3 / 10)     // 0.1..0.3 s into the dip
        val after = zeroCrossings(out, sr * 3 / 2, n)                             // last 0.5 s: real time again
        assertTrue(dip < nominal * 0.4 * 0.9, "pitch should drop during the sag: dip=$dip per 0.2 s vs nominal=$nominal per 0.5 s")
        assertTrue(abs(after - nominal) <= 2, "pitch must be back to nominal after the sag: after=$after nominal=$nominal")
        assertTrue(!sag.active, "the sag must end on its own")
        for (i in 10 until sr / 2) assertTrue(abs(out[i] - sin(2 * Math.PI * 440.0 * i / sr).toFloat()) < 1e-5f, "idle sag must be transparent")
    }

    @Test
    fun duckerRisesInMillisecondsAndReleasesWithinAFractionOfASecond() {
        val d = Ducker(sr.toFloat())
        d.hit()
        var l = 0f
        for (i in 0 until (0.003f * sr).toInt()) l = d.process()
        assertTrue(l > 0.8f, "duck should reach 80 % within 3 ms: $l")
        for (i in 0 until (0.15f * sr).toInt()) l = d.process()
        assertTrue(l < 0.4f, "duck should mostly release within 150 ms: $l")
        for (i in 0 until (0.4f * sr).toInt()) l = d.process()
        assertTrue(l < 0.03f, "duck should be gone after half a second: $l")
    }

    @Test
    fun glueCompressorOnlyActsAboveItsThreshold() {
        val gq = GlueComp(sr.toFloat())
        var g = 1f
        for (i in 0 until sr / 10) g = gq.gainFor(0.1f)
        assertEquals(gq.makeup, g, 1e-4f, "quiet programme passes with makeup only")
        for (i in 0 until sr / 10) g = gq.gainFor(0.8f)
        assertTrue(g < gq.makeup * 0.75f, "loud programme should be turned down: $g")
        assertTrue(g > gq.makeup * 0.3f, "but gently (soft ratio): $g")
    }

    @Test
    fun tapeSaturationIsTransparentWhenQuietAndRoundsPeaks() {
        val s = TapeSat(1.5f)
        assertTrue(abs(s.process(0.2f) - 0.2f) < 0.02f, "quiet signal passes")
        val hot = s.process(1.2f)
        assertTrue(hot < 1.0f && hot > 0.6f, "hot peak is rounded, not clipped flat: $hot")
        assertTrue(abs(s.process(-0.2f) + 0.2f) < 0.02f, "negative quiet signal passes")
    }

    @Test
    fun riserBuildsAcrossItsLengthAndStopsAtTheEnd() {
        val tex = Texture(sr.toFloat(), 9)
        val out = StereoOut()
        val len = 1.0f
        tex.riser(len)
        val n = (len * sr).toInt()
        var early = 0.0; var late = 0.0; var after = 0.0
        for (i in 0 until n + sr / 10) {
            out.l = 0f; out.r = 0f
            tex.render(out, 0f, 0f, 1f)
            val e = (out.l * out.l).toDouble()
            when {
                i < n / 10 -> early += e
                i >= n - n / 10 - 300 && i < n - 300 -> late += e
                i >= n -> after += e
            }
        }
        assertTrue(late > early * 25, "riser should build (early=$early late=$late)")
        assertTrue(after < late * 0.001, "and stop at the end (after=$after)")
    }

    @Test
    fun erraticLofiStaysCleanWithTheGesturesActive() {
        // the erratic scenario fires several non-graced penalties: each sags the tape; nothing may clip or blow up
        val engine = SymphonyEngine(sr, Genre.LOFI, 41, 18, "test")
        val block = FloatArray(1920 * 2)
        var t = 0.0
        var peak = 0f
        while (t < 40.0) {
            engine.update(Scenarios.erratic.signalsAt(t), 0.04f)
            engine.render(block, 1920)
            for (v in block) { assertTrue(!v.isNaN() && !v.isInfinite()); val a = if (v < 0f) -v else v; if (a > peak) peak = a }
            t += 0.04
        }
        assertTrue(peak <= 1f, "peak $peak")
        assertTrue(peak > 0.1f, "still audible: peak $peak")
        assertTrue(engine.summary().eventCounts.values.sum() >= 3, "erratic scenario should log penalties: ${engine.summary().eventCounts}")
        assertTrue(engine.state.sags >= 1, "non-graced penalties should sag the tape: sags=${engine.state.sags}")
    }
}
