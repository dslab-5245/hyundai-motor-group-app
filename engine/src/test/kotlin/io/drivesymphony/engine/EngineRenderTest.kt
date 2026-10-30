package io.drivesymphony.engine

import io.drivesymphony.engine.director.DrivingScorer
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.MusicDirector
import io.drivesymphony.engine.director.ScoreState
import io.drivesymphony.engine.remix.TrackProcessor
import io.drivesymphony.engine.scenario.Scenarios
import io.drivesymphony.engine.theory.Genre
import io.drivesymphony.engine.tools.PreviewMain
import java.io.File
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineRenderTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "drive-symphony-test").apply { mkdirs() }

    private fun renderStats(genre: Genre, seconds: Float, seed: Int = 3): Triple<Float, Float, FloatArray> {
        val engine = SymphonyEngine(48000, genre, seed, 18, "test")
        val frames = (seconds * 48000).toInt()
        val out = FloatArray(frames * 2)
        val block = FloatArray(512)
        var f = 0
        var t = 0.0
        var peak = 0f; var sumSq = 0.0
        while (f < frames) {
            engine.update(Scenarios.safe.signalsAt(t), 256f / 48000f)
            val n = minOf(256, frames - f)
            engine.render(block, n)
            for (i in 0 until n * 2) {
                val v = block[i]
                assertTrue(!v.isNaN() && !v.isInfinite(), "NaN/Inf at frame $f")
                out[f * 2 + i] = v
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                sumSq += (v * v).toDouble()
            }
            f += n; t += 256.0 / 48000.0
        }
        return Triple(peak, sqrt(sumSq / (frames * 2)).toFloat(), out)
    }

    @Test
    fun everyGenreRendersAudibleCleanAudio() {
        for (g in Genre.values()) {
            val (peak, rms, _) = renderStats(g, 12f)
            assertTrue(peak <= 1.0f, "$g peak $peak must not clip")
            assertTrue(peak > 0.1f, "$g should be audible, peak=$peak")
            assertTrue(rms > 0.01f, "$g should not be silent, rms=$rms")
        }
    }

    @Test
    fun renderingIsDeterministicForSameSeed() {
        val (_, _, a) = renderStats(Genre.LOFI, 4f, seed = 11)
        val (_, _, b) = renderStats(Genre.LOFI, 4f, seed = 11)
        assertTrue(a.contentEquals(b), "same seed must produce identical audio")
        val (_, _, c) = renderStats(Genre.LOFI, 4f, seed = 12)
        assertTrue(!a.contentEquals(c), "different seeds should differ")
    }

    @Test
    fun erraticDrivingSoundsDarkerAndThinnerThanSafe() {
        val safe = PreviewMain.render(Genre.LOFI, Scenarios.safe, 40f, tmp)
        val err = PreviewMain.render(Genre.LOFI, Scenarios.erratic, 40f, tmp)
        assertTrue(safe.meanFlow > err.meanFlow + 0.15f, "flow safe=${safe.meanFlow} erratic=${err.meanFlow}")
        assertTrue(safe.meanCutoff > err.meanCutoff * 1.3f, "cutoff safe=${safe.meanCutoff} erratic=${err.meanCutoff}")
        assertTrue(safe.meanDensity > err.meanDensity + 0.3f, "density safe=${safe.meanDensity} erratic=${err.meanDensity}")
        assertTrue(safe.file.length() > 1000 && err.file.length() > 1000)
    }

    @Test
    fun directorFallsFastAndRisesSlow() {
        val d = MusicDirector(Genre.LOFI)
        val sig = DrivingSignals(0.0, gear = Gear.DRIVE)
        d.update(ScoreState(flow = 1f, instant = 1f), sig, 0.04f)
        val open = d.params.cutoffHz
        d.update(ScoreState(flow = 0.3f, instant = 0.3f, penaltyPulse = 1f), sig, 0.2f)
        val dipped = d.params.cutoffHz
        assertTrue(dipped < open * 0.5f, "cutoff should drop quickly: open=$open dipped=$dipped")
        d.update(ScoreState(flow = 1f, instant = 1f), sig, 0.2f)
        val partial = d.params.cutoffHz
        assertTrue(partial < open * 0.8f, "cutoff should rise slowly: partial=$partial open=$open")
    }

    @Test
    fun trackProcessorDarkensOnLowFlowAndIsTransparentOnHighFlow() {
        val sr = 48000
        val frames = sr
        val input = FloatArray(frames * 2) { i -> (sin(2 * Math.PI * 6000.0 * (i / 2) / sr) * 0.5).toFloat() }
        val out = FloatArray(frames * 2)
        fun rmsOf(buf: FloatArray, from: Int): Float { var s = 0.0; var n = 0; for (i in from until buf.size) { s += (buf[i] * buf[i]).toDouble(); n++ }; return sqrt(s / n).toFloat() }
        val d = MusicDirector(Genre.LOFI)
        val sig = DrivingSignals(0.0, gear = Gear.DRIVE)
        val high = d.update(ScoreState(flow = 1f, instant = 1f), sig, 1f)
        val p1 = TrackProcessor(sr)
        p1.process(input, out, frames, high)
        val rmsHigh = rmsOf(out, frames) // second half: filters settled
        val d2 = MusicDirector(Genre.LOFI)
        val low = d2.update(ScoreState(flow = 0.15f, instant = 0.15f, penaltyPulse = 0.8f), sig, 1f)
        val p2 = TrackProcessor(sr)
        p2.process(input, out, frames, low)
        val rmsLow = rmsOf(out, frames)
        assertTrue(rmsLow < rmsHigh * 0.6f, "6 kHz content should be attenuated on low flow: high=$rmsHigh low=$rmsLow")
        assertTrue(rmsHigh > 0.25f, "high flow should keep the track nearly intact: $rmsHigh")
    }

    @Test
    fun summaryCountsEventsAndTracksStreak() {
        val engine = SymphonyEngine(48000, Genre.JAZZ, 5, 9, "테헤란로")
        val block = FloatArray(1920 * 2) // 40 ms of audio per 40 ms control tick -> real-time ratio
        var t = 0.0
        while (t < 60.0) { engine.update(Scenarios.erratic.signalsAt(t), 0.04f); engine.render(block, 1920); t += 0.04 }
        val s = engine.summary()
        assertTrue(s.eventCounts.values.sum() >= 3, "erratic scenario should log events: ${s.eventCounts}")
        // the scenario reports the navigation road name, which overrides the constructor hint
        assertTrue(s.title.contains("올림픽대로"), "title should carry the road name from navigation: ${s.title}")
        assertEquals(Genre.JAZZ, s.genre)
        assertTrue(s.bars > 10)
    }

    @Test
    fun genreSwitchHappensAtBarBoundaryWithoutGlitch() {
        val engine = SymphonyEngine(48000, Genre.LOFI, 2, 12, null)
        val block = FloatArray(1920 * 2)
        var t = 0.0
        while (t < 6.0) { engine.update(Scenarios.safe.signalsAt(t), 0.04f); engine.render(block, 1920); t += 0.04 }
        engine.setGenre(Genre.AMBIENT)
        var peak = 0f
        while (t < 14.0) {
            engine.update(Scenarios.safe.signalsAt(t), 0.04f); engine.render(block, 1920); t += 0.04
            for (v in block) { val a = if (v < 0) -v else v; if (a > peak) peak = a; assertTrue(!v.isNaN()) }
        }
        assertEquals(Genre.AMBIENT, engine.state.genre)
        assertTrue(peak <= 1f)
    }

    @Test
    fun scorerRunsFastEnoughForRealtime() {
        val s = DrivingScorer()
        val t0 = System.nanoTime()
        var t = 0.0
        repeat(50_000) { s.update(Scenarios.story.signalsAt(t), 0.02f); t += 0.02 }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 2000, "50k scorer updates took $ms ms")
    }
}
