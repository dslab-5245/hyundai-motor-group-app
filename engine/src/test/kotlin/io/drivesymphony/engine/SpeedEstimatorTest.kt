package io.drivesymphony.engine

import io.drivesymphony.engine.director.SpeedEstimator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Measures the position-derived speed estimate on synthetic trajectories: 1 Hz fixes heading north with
 * Gaussian position noise. The numbers printed here are the ones quoted in the proposal.
 */
class SpeedEstimatorTest {
    private data class Sample(val t: Double, val trueKmh: Double, val est: Float?)

    private fun simulate(sigmaM: Double, seed: Int, seconds: Int = 120, v: (Double) -> Double): List<Sample> {
        val rng = java.util.Random(seed.toLong())
        val est = SpeedEstimator()
        var north = 0.0
        val out = ArrayList<Sample>()
        val step = 0.05
        var t = 0.0; var nextFix = 0.0
        while (t <= seconds) {
            north += v(t) / 3.6 * step
            if (t >= nextFix - 1e-9) {
                val lat = 37.5 + (north + rng.nextGaussian() * sigmaM) / 111_320.0
                val lon = 127.0 + (rng.nextGaussian() * sigmaM) / (111_320.0 * cos(Math.toRadians(37.5)))
                out += Sample(t, v(t), est.push(lat, lon, t))
                nextFix += 1.0
            }
            t += step
        }
        return out
    }

    private fun errorStats(samples: List<Sample>, from: Double): Pair<Double, Double> {
        val e = samples.filter { it.t >= from && it.est != null }.map { it.est!! - it.trueKmh }
        val bias = e.average()
        val rms = sqrt(e.map { it * it }.average())
        return bias to rms
    }

    @Test
    fun constantSpeedErrorStaysSmallUnderPositionNoise() {
        for (sigma in listOf(0.0, 3.0, 5.0)) {
            val (bias, rms) = errorStats(simulate(sigma, 11) { 60.0 }, from = 10.0)
            println("speed estimate @60 km/h, position noise sigma=${sigma} m: bias ${"%.2f".format(bias)} km/h, rms ${"%.2f".format(rms)} km/h")
            val limit = if (sigma == 0.0) 0.5 else if (sigma <= 3.0) 4.0 else 6.5
            assertTrue(rms < limit, "sigma $sigma m: rms $rms km/h should be below $limit")
            assertTrue(abs(bias) < 1.5, "sigma $sigma m: bias $bias km/h")
        }
    }

    @Test
    fun hardStopIsSeenWithinAFewSeconds() {
        // 60 km/h, then a 5 s stop (t=40..45), then standing still
        val s = simulate(3.0, 5) { t -> if (t < 40) 60.0 else if (t < 45) 60.0 * (45 - t) / 5 else 0.0 }
        val under10 = s.first { it.t >= 45 && (it.est ?: 99f) < 10f }.t
        val zero = s.first { it.t >= 45 && it.est == 0f }.t
        println("speed estimate: stop completed at 45 s -> below 10 km/h at ${under10} s, reads 0 at ${zero} s")
        assertTrue(under10 - 45.0 <= 5.0, "should read < 10 km/h within 5 s of stopping, took ${under10 - 45.0} s")
        assertTrue(zero - 45.0 <= 8.0, "should settle at 0 within 8 s of stopping, took ${zero - 45.0} s")
        val still = s.filter { it.t >= 55 }
        assertTrue(still.all { (it.est ?: 0f) < 6f }, "standing still must not read as driving (max ${still.maxOf { it.est ?: 0f }} km/h)")
        assertTrue(still.count { it.est == 0f } > still.size * 0.9, "standing still should read 0 almost always, not GPS jitter")
    }

    @Test
    fun gapAndJumpRestartTheEstimate() {
        val est = SpeedEstimator()
        est.push(37.5, 127.0, 0.0); est.push(37.50015, 127.0, 1.0); est.push(37.50030, 127.0, 2.0)
        assertTrue((est.speedKmh ?: 0f) > 40f, "moving: ${est.speedKmh}")
        est.push(37.50045, 127.0, 6.0) // 4 s gap (> maxGapSec)
        assertNull(est.speedKmh, "a gap must clear the estimate rather than fabricate a speed")
        est.push(37.50060, 127.0, 7.0); est.push(37.50075, 127.0, 8.0)
        assertTrue((est.speedKmh ?: 0f) > 40f, "recovers after the gap: ${est.speedKmh}")
        est.push(37.51, 127.0, 9.0) // ~1.1 km in 1 s: map re-match jump
        assertNull(est.speedKmh, "an implausible jump must restart, not spike")
    }
}
