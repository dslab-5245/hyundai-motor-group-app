package io.drivesymphony.app.signals

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState as PleosWiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import io.drivesymphony.engine.director.CruiseState
import io.drivesymphony.engine.director.DriveMode
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.TurnSignal
import io.drivesymphony.engine.director.WiperState

/**
 * Pleos Vehicle SDK 2.3.2 source. Subscribes to the nine read-only signals the music uses.
 * Each registration is isolated: a domain that is unsupported on the current model (capability NONE)
 * or not yet permitted simply logs and the rest keep working (the scorer treats missing signals as neutral).
 *
 * Signatures verified against the SDK's Kotlin metadata (reflection probe, 2026-10-30):
 * all listener callbacks receive nullable values and every listener must implement onFailed(Exception).
 */
class PleosVehicleSource(private val context: Context) : SignalSource {
    override val name = "pleos-vehicle"
    private var vehicle: Vehicle? = null
    private var store: SignalStore? = null

    // One area + listener per rear seat: the SDK reports occupancy per single seat (capabilities return List<SeatArea>),
    // so a combined multi-seat area may be rejected at registration.
    private val rearSeatAreas = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT).map { SeatArea(it) }
    private val rearOccupied = BooleanArray(rearSeatAreas.size)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    private fun warn(what: String, e: Exception) { Log.w(TAG, "$what failed: ${e.message}") }

    private val steering = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { store?.steeringAngleDeg = it; store?.touchVehicle() } }
        override fun onFailed(e: Exception) = warn("steering", e)
    }
    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { status?.let { store?.brake = it; store?.touchVehicle() } }
        override fun onFailed(e: Exception) = warn("brake", e)
    }
    private val turnSignal = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            store?.turnSignal = when (state) { TurnSignalValue.LEFT -> TurnSignal.LEFT; TurnSignalValue.RIGHT -> TurnSignal.RIGHT; else -> TurnSignal.OFF }
            store?.touchVehicle()
        }
        override fun onFailed(e: Exception) = warn("turnSignal", e)
    }
    private val wiper = object : WiperStateListener {
        override fun onWiperStateUpdated(state: PleosWiperState?) {
            store?.wiper = when (state) {
                PleosWiperState.ON -> WiperState.ON
                PleosWiperState.OFF -> WiperState.OFF
                PleosWiperState.SERVICE -> WiperState.SERVICE
                else -> WiperState.OTHER
            }
            store?.touchVehicle()
        }
        override fun onFailed(e: Exception) = warn("wiper", e)
    }
    private val gear = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            store?.gear = when (gear) {
                VehicleGear.GEAR_PARK -> Gear.PARK
                VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                else -> Gear.DRIVE // GEAR_DRIVE and manual-range gears 1..9 all mean "moving forward"
            }
            store?.touchVehicle()
        }
        override fun onFailed(e: Exception) = warn("gear", e)
    }
    private val driveMode = object : DrivingModeStateListener {
        override fun onDrivingModeStateUpdated(state: DrivingModeType?) {
            store?.driveMode = when (state) { DrivingModeType.ECO -> DriveMode.ECO; DrivingModeType.SPORT -> DriveMode.SPORT; else -> DriveMode.NORMAL }
            store?.touchVehicle()
        }
        override fun onFailed(e: Exception) = warn("driveMode", e)
    }
    private val cruise = object : CruiseControlStateListener {
        override fun onCruiseControlStateUpdated(state: CruiseControl?) {
            store?.cruise = when (state) {
                CruiseControl.ACTIVATED -> CruiseState.ACTIVATED
                CruiseControl.ENABLED, CruiseControl.SUSPENDED -> CruiseState.ENABLED
                CruiseControl.USER_OVERRIDE -> CruiseState.OVERRIDE
                else -> CruiseState.OFF
            }
            store?.touchVehicle()
        }
        override fun onFailed(e: Exception) = warn("cruise", e)
    }
    private val rearListeners = List(rearSeatAreas.size) { i ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                occupancy?.let {
                    rearOccupied[i] = it
                    store?.rearSeatOccupied = rearOccupied.any { o -> o }
                    store?.touchVehicle()
                }
            }
            override fun onFailed(e: Exception) = warn("seatOccupancy[$i]", e)
        }
    }
    private val driverBelt = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { store?.seatBeltFastened = state; store?.touchVehicle() }
        override fun onFailed(e: Exception) = warn("seatBelt", e)
    }

    private inline fun guarded(tag: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "$tag unavailable: ${e.message}") }
    }

    override fun start(store: SignalStore) {
        this.store = store
        val v = Vehicle(context.applicationContext)
        v.initialize()
        vehicle = v
        guarded("steering") { v.getSteeringWheel().registerSteeringWheelAngle(steering) }
        guarded("brake") { v.getBrake().registerBrakePedalStatus(brake) }
        guarded("turnSignal") { v.getTurnSignal().registerTurnSignalState(turnSignal) }
        guarded("wiper") { v.getWiper().registerWiperState(frontWindshield, wiper) }
        // Vehicle 2.3.2 deprecates the gear listener in favour of the android.car GEAR_SELECTION property; both are subscribed
        @Suppress("DEPRECATION")
        guarded("gear") { v.getGear().registerGearSelection(gear) }
        guarded("driveMode") { v.getDrivingMode().registerDrivingModeState(driveMode) }
        guarded("cruise") { v.getDrivingMode().registerCruiseControlState(cruise) }
        rearSeatAreas.forEachIndexed { i, area -> guarded("seatOccupancy[$i]") { v.getSeat().registerSeatOccupancy(area, rearListeners[i]) } }
        guarded("seatBelt") { v.getSeat().registerSeatBeltBuckledState(driverSeat, driverBelt) }
        Log.i(TAG, "Vehicle SDK initialized; listeners registered")
    }

    override fun stop() {
        val v = vehicle ?: return
        guarded("steering") { v.getSteeringWheel().unregisterSteeringWheelAngle(steering) }
        guarded("brake") { v.getBrake().unregisterBrakePedalStatus(brake) }
        guarded("turnSignal") { v.getTurnSignal().unregisterTurnSignalState(turnSignal) }
        guarded("wiper") { v.getWiper().unregisterWiperState(frontWindshield, wiper) }
        @Suppress("DEPRECATION")
        guarded("gear") { v.getGear().unregisterGearSelection(gear) }
        guarded("driveMode") { v.getDrivingMode().unregisterDrivingModeState(driveMode) }
        guarded("cruise") { v.getDrivingMode().unregisterCruiseControlState(cruise) }
        rearSeatAreas.forEachIndexed { i, area -> guarded("seatOccupancy[$i]") { v.getSeat().unregisterSeatOccupancy(area, rearListeners[i]) } }
        guarded("seatBelt") { v.getSeat().unregisterSeatBeltBuckledState(driverSeat, driverBelt) }
        guarded("release") { v.release() }
        vehicle = null
    }

    override fun isLive(store: SignalStore): Boolean = System.currentTimeMillis() - store.lastVehicleUpdateMs < 3000

    companion object { private const val TAG = "PleosVehicleSource" }
}
