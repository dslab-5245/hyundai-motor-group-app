package io.drivesymphony.app.signals

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import io.drivesymphony.engine.director.Gear

/**
 * Gear from the AAOS property service (android.car GEAR_SELECTION). Pleos Vehicle SDK 2.3.2 deprecates its own
 * gear listener in favour of this property, so both are subscribed; whichever delivers writes the same store field.
 * Requires android.car.permission.CAR_POWERTRAIN; if the platform refuses, the source just logs and stays silent.
 */
class CarPropertyGearSource(private val context: Context) : SignalSource {
    override val name = "car-property-gear"
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private var store: SignalStore? = null

    override fun start(store: SignalStore) {
        this.store = store
        try {
            val c = Car.createCar(context) ?: return
            car = c
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
            manager = m
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) { (value.value as? Int)?.let { apply(it) } }
                override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "GEAR_SELECTION error prop=$propId zone=$zone") }
            }
            callback = cb
            m.registerCallback(cb, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            runCatching { apply(m.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)) }
            Log.i(TAG, "GEAR_SELECTION subscribed")
        } catch (t: Throwable) {
            Log.w(TAG, "GEAR_SELECTION unavailable: ${t.message}")
        }
    }

    /** android.car.VehicleGear values: UNKNOWN 0, NEUTRAL 1, REVERSE 2, PARK 4, DRIVE 8, manual ranges 16.. */
    private fun apply(v: Int) {
        val st = store ?: return
        st.propertyGearLive = true
        st.gear = when (v) {
            0 -> Gear.UNKNOWN
            1 -> Gear.NEUTRAL
            2 -> Gear.REVERSE
            4 -> Gear.PARK
            else -> Gear.DRIVE
        }
        st.touchVehicle()
    }

    override fun stop() {
        try { callback?.let { manager?.unregisterCallback(it) } } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        callback = null; manager = null; car = null
    }

    /** Never claims liveness on its own; the Pleos sources decide whether vehicle data is flowing. */
    override fun isLive(store: SignalStore): Boolean = false

    companion object { private const val TAG = "CarPropertyGearSource" }
}
