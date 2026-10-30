package io.drivesymphony.engine.scenario

import io.drivesymphony.engine.director.CautionKind
import io.drivesymphony.engine.director.CruiseState
import io.drivesymphony.engine.director.DriveMode
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.TurnSignal
import io.drivesymphony.engine.director.WiperState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Scripted driving used by tests, preview rendering and the in-app demo mode (when no vehicle signals are available).
 * Each scenario is a pure function of time so it is deterministic and can be scrubbed.
 */
class DriveScenario(val id: String, val label: String, val durationSec: Float, val signalsAt: (t: Double) -> DrivingSignals)

object Scenarios {
    private fun base(t: Double, hour: Int = 18, road: String? = "올림픽대로") = DrivingSignals(
        timeSec = t, gear = Gear.DRIVE, hourOfDay = hour, roadName = road, seatBeltFastened = true,
    )

    /** Smooth urban drive: gentle lane keeping, signaled turns, soft braking, a rain segment and a cruise segment. */
    val safe = DriveScenario("safe", "부드러운 주행", 60f) { t ->
        val gentle = (sin(t * 0.6) * 12.0).toFloat()
        val turn1 = if (t in 14.0..19.0) (sin((t - 14.0) / 5.0 * PI) * 160.0).toFloat() else 0f
        val signal1 = if (t in 11.5..19.5) TurnSignal.LEFT else TurnSignal.OFF
        val brakeSoft = (t in 24.0..27.5) || (t in 46.0..48.5)
        base(t).copy(
            steeringAngleDeg = gentle + turn1,
            brake = brakeSoft,
            turnSignal = signal1,
            wiper = if (t in 30.0..42.0) WiperState.ON else WiperState.OFF,
            cruise = if (t > 50.0) CruiseState.ACTIVATED else CruiseState.OFF,
            speedKmh = if (t < 50.0) 46f else 78f,
            speedLimitKmh = if (t < 50.0) 50 else 80,
            driveMode = DriveMode.ECO,
        )
    }

    /** Erratic drive: sawtooth steering, brake pumping, over-speed, a school-zone over-speed, an unsignaled turn. */
    val erratic = DriveScenario("erratic", "거친 주행", 60f) { t ->
        val jitter = if (t in 6.0..12.0 || t in 30.0..36.0) ((sin(t * 9.0) * 85.0)).toFloat() else (sin(t * 0.7) * 10.0).toFloat()
        val unsignaledTurn = if (t in 20.0..24.0) (sin((t - 20.0) / 4.0 * PI) * 150.0).toFloat() else 0f
        val pump = (t in 14.0..19.0) && ((t * 2.5).toInt() % 2 == 0)
        val over = t in 40.0..52.0
        base(t).copy(
            steeringAngleDeg = jitter + unsignaledTurn,
            brake = pump || (t in 54.0..54.25),
            speedKmh = if (over) 72f else 46f,
            speedLimitKmh = if (t in 44.0..50.0) 30 else 50,
            cautionAhead = if (t in 44.0..50.0) CautionKind.SCHOOL_ZONE else CautionKind.NONE,
            cautionDistanceM = if (t in 44.0..50.0) 120 else null,
            driveMode = DriveMode.SPORT,
        )
    }

    /** Demo story for the PT: calm start -> harsh section -> recovery -> highway bloom -> rain -> arrival (PARK). */
    val story = DriveScenario("story", "시연 스토리", 90f) { t ->
        val phase = when {
            t < 20.0 -> 0   // calm city
            t < 32.0 -> 1   // harsh
            t < 50.0 -> 2   // recovery
            t < 70.0 -> 3   // highway cruise, bloom
            t < 82.0 -> 4   // rain
            else -> 5       // arrival
        }
        val steer = when (phase) {
            1 -> (sin(t * 8.0) * 80.0).toFloat()
            3 -> (sin(t * 0.3) * 4.0).toFloat()
            else -> (sin(t * 0.6) * 12.0).toFloat()
        } + if (t in 8.0..12.0) (sin((t - 8.0) / 4.0 * PI) * 150.0).toFloat() else 0f
        base(t, hour = 19, road = "강변북로").copy(
            steeringAngleDeg = steer,
            brake = (phase == 1 && (t * 3).toInt() % 2 == 0) || (t in 83.0..86.0),
            turnSignal = if (t in 6.0..12.5) TurnSignal.RIGHT else TurnSignal.OFF,
            wiper = if (phase == 4) WiperState.ON else WiperState.OFF,
            cruise = if (phase == 3) CruiseState.ACTIVATED else CruiseState.OFF,
            speedKmh = when (phase) { 3 -> 95f; 5 -> 0f; 1 -> 58f; else -> 45f },
            speedLimitKmh = when (phase) { 3 -> 100; else -> 50 },
            gear = if (t > 87.0) Gear.PARK else Gear.DRIVE,
            driveMode = if (phase == 3) DriveMode.NORMAL else DriveMode.ECO,
        )
    }

    /**
     * A/B pair behind the "reacts within a fraction of a second" claim: identical smooth driving, except that
     * [abEvent] adds a brake-stab + pumping burst at 20.0-21.5 s (first release at 20.3 s). Rendered with the same
     * seed the two files are sample-identical until the event, so the reaction time can be measured on the audio.
     */
    private fun ab(id: String, label: String, event: Boolean) = DriveScenario(id, label, 30f) { t ->
        val gentle = (sin(t * 0.6) * 10.0).toFloat()
        val tap = event && t >= 20.0 && t < 21.5 && (((t - 20.0) * 1000).toInt() % 600 < 300)
        base(t).copy(steeringAngleDeg = gentle, brake = tap, speedKmh = 46f, speedLimitKmh = 50)
    }
    val abClean = ab("ab_clean", "A/B 기준 (부드러운 주행)", false)
    val abEvent = ab("ab_event", "A/B 이벤트 (20 s 브레이크 연타)", true)

    /** Scenarios offered in the in-app demo mode. */
    val all = listOf(safe, erratic, story)
    /** Rendered by PreviewMain as evidence files; not shown in the app. */
    val abPair = listOf(abClean, abEvent)
    fun byId(id: String): DriveScenario = all.firstOrNull { it.id == id } ?: story
}
