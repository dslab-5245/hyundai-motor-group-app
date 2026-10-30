package io.drivesymphony.engine.director

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vehicle speed estimated from navigation positions, because the Vehicle SDK exposes no speed signal.
 *
 * Chord distance between the oldest and newest fix inside a sliding window (default 4 s) divided by the window
 * time, then a light EMA. A per-fix difference at 1 Hz would turn a 3 m position error into about ±15 km/h;
 * over a 4 s window the same error is about ±4 km/h, and the EMA halves that again. Cost: ~2-3 s of lag, which
 * is why [DrivingScorer] additionally requires over-speed to be sustained before it counts.
 *
 * Gaps longer than [maxGapSec] (navigation app paused, tunnel) and implausible jumps (> 250 km/h) restart the
 * estimate instead of producing a spike. A chord shorter than [stillChordM] over a full window reads as 0 km/h.
 */
class SpeedEstimator(
    private val windowSec: Double = 4.0,
    private val alpha: Float = 0.5f,
    private val maxGapSec: Double = 3.0,
    private val stillChordM: Double = 12.0, // 12 m over 4 s = 11 km/h: below the scorer's 15 km/h low-speed gate anyway
) {
    private class Fix(val t: Double, val lat: Double, val lon: Double)
    private val fixes = ArrayDeque<Fix>()

    /** Latest estimate in km/h, null until two fixes at least 0.5 s apart have been seen (or after a gap). */
    var speedKmh: Float? = null
        private set

    fun reset() { fixes.clear(); speedKmh = null }

    /** Feeds one position fix (degrees, seconds on any monotonic clock) and returns the updated estimate. */
    fun push(lat: Double, lon: Double, tSec: Double): Float? {
        val last = fixes.lastOrNull()
        if (last != null) {
            val dt = tSec - last.t
            if (dt <= 0.0) return speedKmh
            val jump = haversineM(last.lat, last.lon, lat, lon) / dt * 3.6
            if (dt > maxGapSec || jump > 250.0) reset()
        }
        fixes.addLast(Fix(tSec, lat, lon))
        while (fixes.size > 2 && tSec - fixes[1].t >= windowSec) fixes.removeFirst()
        val first = fixes.first()
        val span = tSec - first.t
        if (fixes.size >= 2 && span >= 0.5) {
            val chord = haversineM(first.lat, first.lon, lat, lon)
            val kmh = if (span >= windowSec * 0.75 && chord < stillChordM) 0f else (chord / span * 3.6).toFloat()
            // standing still snaps to exactly 0 (an EMA would only halve towards it forever)
            speedKmh = if (kmh == 0f) 0f else speedKmh?.let { it + (kmh - it) * alpha } ?: kmh
        }
        return speedKmh
    }

    companion object {
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
