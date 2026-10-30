package io.drivesymphony.app.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wraps the AAOS CarUxRestrictionsManager. While restrictions are active the app shows only the
 * glanceable Flow screen (one ring, three words). If the car library is unavailable (e.g. a plain
 * emulator) we fall back to "unrestricted" and the service additionally gates on gear == PARK.
 */
class DrivingStateObserver(private val context: Context) {
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var listener: CarUxRestrictionsManager.OnUxRestrictionsChangedListener? = null

    fun start() {
        try {
            val c = Car.createCar(context) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            // created here (not as a field initialiser) so a device without android.car fails inside this try/catch
            val l = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r: CarUxRestrictions ->
                _restricted.value = r.isRequiresDistractionOptimization
            }
            listener = l
            m.registerListener(l)
            m.currentCarUxRestrictions?.let { _restricted.value = it.isRequiresDistractionOptimization }
            Log.i(TAG, "CarUxRestrictions connected, restricted=${_restricted.value}")
        } catch (t: Throwable) {
            Log.w(TAG, "android.car unavailable, falling back to gear-based gating: ${t.message}")
        }
    }

    fun stop() {
        try { manager?.unregisterListener() } catch (_: Throwable) {}
        try { car?.disconnect() } catch (_: Throwable) {}
        manager = null; car = null
    }

    companion object { private const val TAG = "DrivingStateObserver" }
}
