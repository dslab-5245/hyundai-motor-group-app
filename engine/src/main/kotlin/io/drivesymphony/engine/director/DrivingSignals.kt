package io.drivesymphony.engine.director

/**
 * Platform-independent snapshot of what the car is doing right now.
 * The Android layer fills this from the Pleos Vehicle SDK + NaviHelper SDK;
 * tests and the demo scenario fill it from scripts.
 *
 * Only signals that the Pleos Vehicle SDK 2.3.2 / NaviHelper 3.0.5 actually expose are modeled.
 * Notably there is NO vehicle speed signal in the Vehicle SDK; [speedKmh] is derived from
 * successive NaviHelper positions and may be null.
 */
enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }
enum class TurnSignal { OFF, LEFT, RIGHT }
enum class WiperState { OFF, ON, SERVICE, OTHER }
enum class DriveMode { NORMAL, ECO, SPORT }
enum class CruiseState { OFF, ENABLED, ACTIVATED, OVERRIDE }
enum class CautionKind { NONE, SCHOOL_ZONE, SPEED_BUMP, SLIPPERY, DOWNHILL, ROAD_DAMAGE, CAMERA, OTHER }

data class DrivingSignals(
    val timeSec: Double,
    val steeringAngleDeg: Float = 0f,
    val brake: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val wiper: WiperState = WiperState.OFF,
    val gear: Gear = Gear.UNKNOWN,
    val driveMode: DriveMode = DriveMode.NORMAL,
    val cruise: CruiseState = CruiseState.OFF,
    /** km/h derived from navigation positions; null when the navigation app is not running. */
    val speedKmh: Float? = null,
    /** km/h from NaviHelper onCurrentRoadSpeedLimit; null when unknown. */
    val speedLimitKmh: Int? = null,
    val distanceToTurnM: Int? = null,
    val sharpTurnAhead: Boolean = false,
    val cautionAhead: CautionKind = CautionKind.NONE,
    val cautionDistanceM: Int? = null,
    /** NaviHelper CameraAlert.isOverSpeed: the navigation app's own over-speed judgement near a camera. */
    val cameraOverSpeed: Boolean = false,
    val seatBeltFastened: Boolean? = null,
    val rearSeatOccupied: Boolean = false,
    val hourOfDay: Int = 12,
    val roadName: String? = null,
)
