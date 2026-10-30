package io.drivesymphony.app.signals

import io.drivesymphony.engine.scenario.DriveScenario
import io.drivesymphony.engine.scenario.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demo / fallback source: replays a scripted drive (engine Scenarios) into the store so the app is
 * fully demonstrable in the Pleos emulator even when the Virtual Console or navigation is idle.
 * It only writes fields while active, and the service disables it as soon as real vehicle data arrives.
 */
class ScenarioSource(private var scenario: DriveScenario = Scenarios.story) : SignalSource {
    override val name = "demo:${scenario.id}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var lastWrite = 0L

    fun setScenario(s: DriveScenario) { scenario = s }

    override fun start(store: SignalStore) {
        job?.cancel()
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            while (isActive) {
                val t = ((System.currentTimeMillis() - t0) / 1000.0) % scenario.durationSec
                val s = scenario.signalsAt(t)
                store.steeringAngleDeg = s.steeringAngleDeg
                store.brake = s.brake
                store.turnSignal = s.turnSignal
                store.wiper = s.wiper
                store.gear = s.gear
                store.driveMode = s.driveMode
                store.cruise = s.cruise
                store.speedKmh = s.speedKmh
                store.speedLimitKmh = s.speedLimitKmh
                store.cautionAhead = s.cautionAhead
                store.cautionDistanceM = s.cautionDistanceM
                store.seatBeltFastened = s.seatBeltFastened
                store.roadName = s.roadName
                lastWrite = System.currentTimeMillis()
                delay(40)
            }
        }
    }

    override fun stop() { job?.cancel(); job = null }
    override fun isLive(store: SignalStore): Boolean = job?.isActive == true && System.currentTimeMillis() - lastWrite < 1000
    fun destroy() { stop(); scope.cancel() }
}
