package io.drivesymphony.engine

import io.drivesymphony.engine.director.CruiseState
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.TurnSignal
import io.drivesymphony.engine.director.WiperState
import io.drivesymphony.engine.theory.Genre
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** Context layers the proposal promises: cruise -> drone, rain -> title, signaled turn -> an extra layer. */
class EngineLayersTest {
    private val sr = 48000
    private val block = 1920 // 40 ms

    private fun base(t: Double) = DrivingSignals(
        t, steeringAngleDeg = (sin(t * 0.6) * 10).toFloat(), gear = Gear.DRIVE, speedKmh = 70f, speedLimitKmh = 80,
        seatBeltFastened = true, hourOfDay = 18, roadName = "올림픽대로",
    )

    private fun run(seconds: Float, probe: ((Double, SymphonyEngine) -> Unit)? = null, signals: (Double) -> DrivingSignals): SymphonyEngine {
        val e = SymphonyEngine(sr, Genre.LOFI, 5, 18, "올림픽대로")
        val buf = FloatArray(block * 2)
        val ticks = (seconds / 0.04f).toInt()
        for (i in 0 until ticks) {
            val t = i * 0.04
            e.update(signals(t), 0.04f); e.render(buf, block); probe?.invoke(t, e)
        }
        return e
    }

    @Test
    fun cruiseControlAddsADroneLayer() {
        val withCruise = run(16f) { t -> base(t).copy(cruise = if (t > 2) CruiseState.ACTIVATED else CruiseState.OFF) }
        assertTrue(withCruise.params.drone > 0.8f, "drone param should follow cruise control (got ${withCruise.params.drone})")
        assertTrue("drone" in withCruise.state.layers, "cruise should add the drone layer: ${withCruise.state.layers}")
        val without = run(16f) { t -> base(t) }
        assertTrue("drone" !in without.state.layers, "no drone without cruise: ${without.state.layers}")
    }

    @Test
    fun rainForMostOfTheTripMakesTheTitleRainy() {
        val rainy = run(40f) { t -> base(t).copy(wiper = WiperState.ON) }
        assertTrue(rainy.state.title.startsWith("비 오는"), "title should start with 비 오는: ${rainy.state.title}")
        assertTrue(rainy.summary().title.startsWith("비 오는"), "summary title: ${rainy.summary().title}")
        val dry = run(40f) { t -> base(t) }
        assertTrue(!dry.state.title.startsWith("비 오는"), "dry title: ${dry.state.title}")
    }

    @Test
    fun signaledTurnOpensAnExtraLayerForTwoBars() {
        val plain = ArrayList<Float>(); val turned = ArrayList<Float>()
        run(14f, probe = { _, e -> plain += e.state.density }) { t -> base(t) }
        run(14f, probe = { _, e -> turned += e.state.density }) { t ->
            base(t).copy(
                turnSignal = if (t in 1.5..7.0) TurnSignal.LEFT else TurnSignal.OFF,
                steeringAngleDeg = if (t in 3.0..6.5) (sin((t - 3.0) / 3.5 * PI) * 150).toFloat() else (sin(t * 0.6) * 10).toFloat(),
            )
        }
        var maxDiff = 0f; var at = 0.0
        for (i in plain.indices) { val d = turned[i] - plain[i]; if (d > maxDiff) { maxDiff = d; at = i * 0.04 } }
        println("signaled turn: extra density ${"%.2f".format(maxDiff)} at ${"%.1f".format(at)} s")
        assertTrue(maxDiff > 0.4f, "a signaled turn should open an extra layer for two bars (max extra density $maxDiff)")
    }
}
