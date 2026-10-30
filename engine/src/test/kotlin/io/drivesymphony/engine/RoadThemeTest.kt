package io.drivesymphony.engine

import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.theory.Genre
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same road must produce the same composition (key + chord sequence) regardless of the engine's start seed. */
class RoadThemeTest {
    private fun labelsAfterNewTrip(seed: Int, road: String?): Pair<String, List<String>> {
        val engine = SymphonyEngine(48000, Genre.LOFI, seed = seed, hourOfDay = 9)
        engine.newTrip(hourOfDay = 9, roadName = road)
        val buf = FloatArray(1920 * 2)
        val labels = ArrayList<String>()
        var lastBar = -1
        for (i in 0 until 400) { // 16 s: cadence bar + first bars of the new composition
            engine.update(DrivingSignals(i * 0.04, gear = Gear.DRIVE, hourOfDay = 9), 0.04f)
            engine.render(buf, 1920)
            val st = engine.state
            if (st.bar != lastBar) { lastBar = st.bar; labels.add(st.chordLabel) }
        }
        return engine.state.keyLabel to labels
    }

    @Test
    fun sameRoadSameTheme() {
        val a = labelsAfterNewTrip(seed = 1, road = "강변북로")
        val b = labelsAfterNewTrip(seed = 999, road = "강변북로")
        assertEquals(a.first, b.first, "key should match for the same road")
        assertEquals(a.second.drop(1), b.second.drop(1), "chord sequence should match for the same road (after the cadence bar)")
        assertTrue(a.second.size >= 4)
    }

    @Test
    fun unknownRoadStillPlays() {
        val a = labelsAfterNewTrip(seed = 5, road = null)
        assertTrue(a.first.isNotBlank() && a.second.size >= 4)
    }
}
