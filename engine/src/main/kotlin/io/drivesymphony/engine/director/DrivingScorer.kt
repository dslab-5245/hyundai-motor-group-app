package io.drivesymphony.engine.director

import io.drivesymphony.engine.dsp.clamp01
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

enum class DriveEvent(val label: String, val isPenalty: Boolean) {
    HARSH_STEER("급조향", true),
    BRAKE_PUMP("브레이크 반복", true),
    BRAKE_STAB("급제동", true),
    OVER_SPEED("제한속도 초과", true),
    SCHOOL_ZONE_SPEED("스쿨존 과속", true),
    NO_SIGNAL_TURN("깜빡이 없는 회전", true),
    SEATBELT_OFF("안전벨트 미착용", true),
    SIGNALED_TURN("깜빡이 켜고 회전", false),
    SMOOTH_STREAK("부드러운 주행 유지", false),
}

/**
 * Output of the scorer, consumed by [MusicDirector] and by the UI.
 * @property flow 0..1 smoothed "earned richness". Rises slowly with smooth driving, drops fast on harsh events.
 * @property instant 0..1 un-smoothed target the flow is chasing.
 * @property penaltyPulse 0..1 short envelope (tau ~0.8 s) fired on each penalty for an immediate musical dip.
 * @property streakSec seconds of penalty-free moving time.
 */
data class ScoreState(
    val flow: Float = 0.6f,
    val instant: Float = 0.6f,
    val penaltyPulse: Float = 0f,
    val streakSec: Float = 0f,
    val moving: Boolean = false,
    val lastEvent: DriveEvent? = null,
    val lastEventAgeSec: Float = 999f,
    val overSpeedRatio: Float = 0f,
)

/**
 * Turns raw driving signals into a single musical "flow" score.
 *
 * Design rules (see docs 06_* for the rationale):
 *  - Only patterns are penalized, never a single physical reaction: one hard brake after a long clean streak
 *    is treated as a possible emergency manoeuvre and gets half the penalty (grace rule).
 *  - Nothing is scored while parked / reversing (parking manoeuvres are naturally jerky).
 *  - Falling is fast (tau 0.6 s) so the music answers "순간 순간"; rising is slow (tau 5 s) so richness feels earned.
 *  - Penalties decay on their own; the driver can always recover within a few seconds.
 */
class DrivingScorer(
    private val harshSteerDegPerSec: Float = 220f,
    private val fallTau: Float = 0.6f,
    private val riseTau: Float = 5.0f,
) {
    private var prevAngle = 0f
    private var haveAngle = false
    private var steerRateEma = 0f
    private var harshLatch = 0f

    private var prevBrake = false
    private var brakeOnAt = -1.0
    private val brakeReleases = ArrayDeque<Double>()

    private var signalLastOnAt = -100.0
    private var inTurn = false
    private var turnHold = 0f

    private var penalty = 0f       // decaying accumulated penalty (0..0.9)
    private var pulse = 0f
    private var flow = 0.6f
    private var instant = 0.6f
    private var streak = 0f
    private var lastEvent: DriveEvent? = null
    private var lastEventAt = -1000.0
    private var lastPenaltyAt = -1000.0
    private var smoothStreakAnnouncedAt = -1000.0

    var state: ScoreState = ScoreState()
        private set

    fun reset() {
        prevAngle = 0f; haveAngle = false; steerRateEma = 0f; harshLatch = 0f
        prevBrake = false; brakeOnAt = -1.0; brakeReleases.clear()
        signalLastOnAt = -100.0; inTurn = false; turnHold = 0f
        penalty = 0f; pulse = 0f; flow = 0.6f; instant = 0.6f; streak = 0f
        lastEvent = null; lastEventAt = -1000.0; lastPenaltyAt = -1000.0
        state = ScoreState()
    }

    private fun fire(ev: DriveEvent, amount: Float, now: Double) {
        var a = amount
        if (ev.isPenalty) {
            // Grace rule: a single event after a long clean streak is probably an emergency manoeuvre.
            val sinceLastPenalty = now - lastPenaltyAt
            if (streak >= 30f && sinceLastPenalty > 10.0) a *= 0.5f
            penalty = min(0.9f, penalty + a)
            pulse = 1f
            streak = 0f
            lastPenaltyAt = now
        }
        lastEvent = ev
        lastEventAt = now
    }

    /**
     * @param s latest signals
     * @param dt seconds since previous call (20-50 ms typical)
     */
    fun update(s: DrivingSignals, dt: Float): ScoreState {
        val now = s.timeSec
        val dtc = dt.coerceIn(0.001f, 0.5f)
        val speed = s.speedKmh
        val lowSpeed = speed != null && speed < 15f
        val moving = when (s.gear) {
            Gear.PARK, Gear.REVERSE, Gear.NEUTRAL -> false
            Gear.DRIVE -> true
            Gear.UNKNOWN -> speed == null || speed > 5f
        }

        // --- steering smoothness -------------------------------------------------------------
        val angle = s.steeringAngleDeg
        if (haveAngle) {
            val rate = min(2000f, abs(angle - prevAngle) / dtc)
            steerRateEma += (rate - steerRateEma) * min(1f, dtc / 0.12f)
        }
        prevAngle = angle; haveAngle = true
        var threshold = harshSteerDegPerSec
        if (lowSpeed) threshold *= 2f
        if (s.cruise == CruiseState.ACTIVATED || (speed != null && speed > 70f)) threshold *= 0.75f
        harshLatch = (harshLatch - dtc).coerceAtLeast(0f)
        if (moving && !lowSpeed && steerRateEma > threshold && harshLatch <= 0f) {
            fire(DriveEvent.HARSH_STEER, 0.35f, now)
            harshLatch = 1.5f
        }

        // --- turn-signal discipline ---------------------------------------------------------
        // A "turn" is a steady, smooth steering hold (> 70 deg for >= 0.4 s). Jerky swerves are not turns;
        // they are handled by the harsh-steer rule above and must not double-count as unsignaled turns.
        if (s.turnSignal != TurnSignal.OFF) signalLastOnAt = now
        val steadyTurn = abs(angle) > 70f && steerRateEma < harshSteerDegPerSec
        if (moving && steadyTurn && (speed == null || speed > 12f)) turnHold += dtc else turnHold = 0f
        val turningNow = turnHold >= 0.4f
        if (turningNow && !inTurn) {
            inTurn = true
            if (now - signalLastOnAt < 4.0) {
                fire(DriveEvent.SIGNALED_TURN, 0f, now)
                flow = min(1f, flow + 0.03f) // small immediate reward
            } else {
                fire(DriveEvent.NO_SIGNAL_TURN, 0.15f, now)
            }
        }
        if (abs(angle) < 25f) inTurn = false

        // --- braking patterns ---------------------------------------------------------------
        if (s.brake && !prevBrake) brakeOnAt = now
        if (!s.brake && prevBrake) {
            val held = if (brakeOnAt >= 0) now - brakeOnAt else 1.0
            brakeReleases.addLast(now)
            while (brakeReleases.isNotEmpty() && now - brakeReleases.first() > 8.0) brakeReleases.removeFirst()
            if (moving && !lowSpeed) {
                if (brakeReleases.size >= 3) {
                    fire(DriveEvent.BRAKE_PUMP, 0.3f, now)
                    brakeReleases.clear()
                } else if (held < 0.35) {
                    fire(DriveEvent.BRAKE_STAB, 0.2f, now)
                }
            }
        }
        prevBrake = s.brake

        // --- speed vs. limit (continuous) ---------------------------------------------------
        var overRatio = 0f
        val schoolZone = s.cautionAhead == CautionKind.SCHOOL_ZONE && (s.cautionDistanceM ?: 0) < 250
        if (moving && speed != null && s.speedLimitKmh != null && s.speedLimitKmh > 0) {
            val ratio = speed / s.speedLimitKmh
            overRatio = clamp01((ratio - 1.08f) / 0.25f)
            if (schoolZone && ratio > 1.0f) overRatio = min(1f, overRatio + 0.4f)
        }
        // the navigation app's own camera-zone judgement counts even when we cannot derive speed
        if (moving && s.cameraOverSpeed) overRatio = maxOf(overRatio, 0.5f)
        if (overRatio > 0.05f && now - lastEventAt > 3.0) {
            fire(if (schoolZone) DriveEvent.SCHOOL_ZONE_SPEED else DriveEvent.OVER_SPEED, 0f, now)
            streak = 0f
            lastPenaltyAt = now
        }

        // --- seat belt ----------------------------------------------------------------------
        val beltOff = moving && s.seatBeltFastened == false
        if (beltOff && now - lastEventAt > 10.0) fire(DriveEvent.SEATBELT_OFF, 0f, now)

        // --- integrate -----------------------------------------------------------------------
        penalty *= exp(-dtc / 4f)
        if (penalty < 0.002f) penalty = 0f
        pulse *= exp(-dtc / 0.8f)
        if (pulse < 0.002f) pulse = 0f

        if (moving) {
            if (penalty == 0f && overRatio == 0f && !beltOff) streak += dtc else streak = 0f
            var target = 1f - penalty - overRatio * 0.6f
            if (streak > 20f) target += 0.05f
            if (beltOff) target = min(target, 0.4f)
            // never go fully dark: a floor of 0.1 keeps a thin, soft bed playing (the feedback is "quieter and
            // duller", not punishment by silence)
            instant = target.coerceIn(0.1f, 1f)
            if (streak > 25f && now - smoothStreakAnnouncedAt > 60.0) {
                smoothStreakAnnouncedAt = now
                lastEvent = DriveEvent.SMOOTH_STREAK; lastEventAt = now
            }
        } else {
            instant = 0.65f // calm, parked
        }
        val tau = if (instant < flow) fallTau else riseTau
        flow += (instant - flow) * min(1f, dtc / tau)
        flow = clamp01(flow)

        state = ScoreState(
            flow = flow,
            instant = instant,
            penaltyPulse = pulse,
            streakSec = streak,
            moving = moving,
            lastEvent = lastEvent,
            lastEventAgeSec = (now - lastEventAt).toFloat(),
            overSpeedRatio = overRatio,
        )
        return state
    }
}
