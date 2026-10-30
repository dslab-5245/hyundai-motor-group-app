package io.drivesymphony.app.signals

import io.drivesymphony.engine.director.CautionKind
import io.drivesymphony.engine.director.CruiseState
import io.drivesymphony.engine.director.DriveMode
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.TurnSignal
import io.drivesymphony.engine.director.WiperState
import java.util.Calendar

/**
 * Mutable, thread-safe-enough (volatile fields) holder that SDK callbacks write into and the
 * control loop snapshots at 25 Hz. Each field remembers when it was last updated so the UI can
 * show which signals are live.
 */
class SignalStore {
    @Volatile var steeringAngleDeg = 0f
    @Volatile var brake = false
    @Volatile var turnSignal = TurnSignal.OFF
    @Volatile var wiper = WiperState.OFF
    @Volatile var gear = Gear.UNKNOWN
    @Volatile var driveMode = DriveMode.NORMAL
    @Volatile var cruise = CruiseState.OFF
    @Volatile var speedKmh: Float? = null
    @Volatile var speedLimitKmh: Int? = null
    @Volatile var distanceToTurnM: Int? = null
    @Volatile var sharpTurnAhead = false
    @Volatile var cautionAhead = CautionKind.NONE
    @Volatile var cautionDistanceM: Int? = null
    @Volatile var cameraOverSpeed = false
    @Volatile var seatBeltFastened: Boolean? = null
    @Volatile var rearSeatOccupied = false
    @Volatile var roadName: String? = null

    @Volatile var lastVehicleUpdateMs = 0L
    @Volatile var lastNaviUpdateMs = 0L

    fun touchVehicle() { lastVehicleUpdateMs = System.currentTimeMillis() }
    fun touchNavi() { lastNaviUpdateMs = System.currentTimeMillis() }

    fun snapshot(timeSec: Double): DrivingSignals = DrivingSignals(
        timeSec = timeSec,
        steeringAngleDeg = steeringAngleDeg,
        brake = brake,
        turnSignal = turnSignal,
        wiper = wiper,
        gear = gear,
        driveMode = driveMode,
        cruise = cruise,
        speedKmh = speedKmh,
        speedLimitKmh = speedLimitKmh,
        distanceToTurnM = distanceToTurnM,
        sharpTurnAhead = sharpTurnAhead,
        cautionAhead = cautionAhead,
        cautionDistanceM = cautionDistanceM,
        cameraOverSpeed = cameraOverSpeed,
        seatBeltFastened = seatBeltFastened,
        rearSeatOccupied = rearSeatOccupied,
        hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        roadName = roadName,
    )
}

/** A source feeds a [SignalStore]. Sources are started/stopped by the service. */
interface SignalSource {
    val name: String
    fun start(store: SignalStore)
    fun stop()
    /** True when this source has delivered data recently. */
    fun isLive(store: SignalStore): Boolean
}
