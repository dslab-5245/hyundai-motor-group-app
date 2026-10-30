package io.drivesymphony.engine.director

import io.drivesymphony.engine.dsp.clamp
import io.drivesymphony.engine.dsp.clamp01
import io.drivesymphony.engine.dsp.logMap
import io.drivesymphony.engine.theory.Genre
import kotlin.math.min

/**
 * Continuous musical controls. All values are already smoothed by [MusicDirector]; the engine reads them per block.
 */
data class MusicParams(
    val density: Float = 1.5f,     // 0..3 layer density (pattern complexity + layer gains)
    val cutoffHz: Float = 8000f,   // master lowpass
    val wobble: Float = 0.1f,      // 0..1 tape wobble depth
    val crackle: Float = 0.3f,     // 0..1 vinyl/texture level
    val crush: Float = 0.1f,       // 0..1 sample-rate reduction
    val reverbMix: Float = 0.25f,  // 0..0.7
    val width: Float = 0.8f,       // 0..1 stereo width
    val gainDb: Float = 0f,        // master trim, <= 0
    val richness: Float = 0.5f,    // 0..1 chord extensions / voicing spread
    val bloom: Float = 0f,         // 0..1 melody voice presence
    val pan: Float = 0f,           // -1..1 musical pan from steering
    val rain: Float = 0f,          // 0..1 wiper texture
    val blinker: Boolean = false,
    val drive: Float = 0.5f,       // 0..1 energy from drive mode
    val nightTint: Float = 0f,     // 0..1
    val flow: Float = 0.6f,        // echoed for UI / tests
    val pulse: Float = 0f,         // echoed
)

/**
 * Maps the scorer output + raw signals to musical parameters, with asymmetric smoothing:
 * controls fall quickly (so a harsh event is heard within ~150 ms) and rise slowly (richness feels earned).
 */
class MusicDirector(var genre: Genre) {
    private var cur = MusicParams()
    private var initialized = false

    /** 0.6 = gentle, 1.0 = standard, 1.4 = strong: scales how much erratic driving darkens the music. */
    var intensity = 1f

    val params: MusicParams get() = cur

    private fun ease(current: Float, target: Float, dt: Float, fallTau: Float, riseTau: Float): Float {
        val tau = if (target < current) fallTau else riseTau
        return current + (target - current) * min(1f, dt / tau)
    }

    fun update(score: ScoreState, s: DrivingSignals, dt: Float): MusicParams {
        val pulse = clamp01(score.penaltyPulse * intensity)
        // "dark" is how far from ideal we are, scaled by the feedback intensity; eff = 1 - dark
        val dark = clamp01((1f - clamp01(score.flow - pulse * 0.35f)) * intensity)
        val eff = 1f - dark
        val g = genre

        val densityT = when (g) {
            Genre.AMBIENT -> eff * 2.2f
            else -> 0.35f + eff * 2.65f
        }
        var cutoffT = logMap(eff, 900f, 14000f) * (1f - pulse * 0.6f)
        if (g == Genre.AMBIENT) cutoffT = logMap(eff, 1200f, 12000f) * (1f - pulse * 0.5f)
        val wobbleT = (1f - eff) * (if (g == Genre.LOFI) 0.6f else 0.4f) + pulse * 0.45f
        val crackleT = when (g) {
            Genre.LOFI -> 0.25f + (1f - eff) * 0.5f
            Genre.JAZZ -> 0.08f + (1f - eff) * 0.4f
            Genre.AMBIENT -> 0.12f + (1f - eff) * 0.3f
        }
        val crushT = when (g) {
            Genre.LOFI -> (1f - eff) * 0.4f + pulse * 0.3f
            Genre.JAZZ -> (1f - eff) * 0.25f + pulse * 0.25f
            Genre.AMBIENT -> (1f - eff) * 0.15f + pulse * 0.2f
        }
        val reverbT = when (g) {
            Genre.AMBIENT -> 0.35f + eff * 0.3f
            else -> 0.15f + eff * 0.25f
        }
        val widthT = 0.45f + eff * 0.55f
        val gainT = -5f * (1f - eff) - pulse * 3f
        val richnessT = eff
        val bloomT = clamp01((eff - 0.78f) / 0.2f) * (if (score.streakSec > 15f) 1f else 0.55f)
        val panT = clamp(s.steeringAngleDeg / 180f, -1f, 1f) * 0.35f
        val rainT = if (s.wiper == WiperState.ON) 1f else 0f
        val driveT = when (s.driveMode) { DriveMode.ECO -> 0.3f; DriveMode.NORMAL -> 0.5f; DriveMode.SPORT -> 0.8f }
        val nightT = if (s.hourOfDay < 6 || s.hourOfDay >= 19) 1f else 0f

        if (!initialized) {
            cur = MusicParams(densityT, cutoffT, wobbleT, crackleT, crushT, reverbT, widthT, gainT, richnessT, bloomT, panT, rainT,
                s.turnSignal != TurnSignal.OFF, driveT, nightT, score.flow, pulse)
            initialized = true
            return cur
        }
        cur = MusicParams(
            density = ease(cur.density, densityT, dt, 0.3f, 2.5f),
            cutoffHz = ease(cur.cutoffHz, cutoffT, dt, 0.12f, 1.8f),
            wobble = ease(cur.wobble, wobbleT, dt, 0.15f, 1.5f),
            crackle = ease(cur.crackle, crackleT, dt, 0.4f, 1.5f),
            crush = ease(cur.crush, crushT, dt, 0.15f, 1.5f),
            reverbMix = ease(cur.reverbMix, reverbT, dt, 0.6f, 2.5f),
            width = ease(cur.width, widthT, dt, 0.3f, 2.0f),
            gainDb = ease(cur.gainDb, gainT, dt, 0.1f, 1.5f),
            richness = ease(cur.richness, richnessT, dt, 0.5f, 3.0f),
            bloom = ease(cur.bloom, bloomT, dt, 0.4f, 2.0f),
            pan = ease(cur.pan, panT, dt, 0.5f, 0.5f),
            rain = ease(cur.rain, rainT, dt, 1.0f, 1.0f),
            blinker = s.turnSignal != TurnSignal.OFF,
            drive = ease(cur.drive, driveT, dt, 2f, 2f),
            nightTint = nightT,
            flow = score.flow,
            pulse = pulse,
        )
        return cur
    }
}
