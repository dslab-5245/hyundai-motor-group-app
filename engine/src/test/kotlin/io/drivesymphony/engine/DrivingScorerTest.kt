package io.drivesymphony.engine

import io.drivesymphony.engine.director.DriveEvent
import io.drivesymphony.engine.director.DrivingScorer
import io.drivesymphony.engine.director.DrivingSignals
import io.drivesymphony.engine.director.Gear
import io.drivesymphony.engine.director.TurnSignal
import io.drivesymphony.engine.scenario.Scenarios
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrivingScorerTest {
    private val dt = 0.04f

    private fun run(scorer: DrivingScorer, from: Double, to: Double, f: (Double) -> DrivingSignals) {
        var t = from
        while (t < to) { scorer.update(f(t), dt); t += dt }
    }

    @Test
    fun smoothDrivingRaisesFlowAboveThreshold() {
        val s = DrivingScorer()
        run(s, 0.0, 40.0) { t -> DrivingSignals(t, steeringAngleDeg = (sin(t) * 10).toFloat(), gear = Gear.DRIVE, speedKmh = 45f, speedLimitKmh = 50, seatBeltFastened = true) }
        assertTrue(s.state.flow > 0.9f, "flow should approach 1 with smooth driving, was ${s.state.flow}")
        assertTrue(s.state.streakSec > 30f)
    }

    @Test
    fun harshSteeringDropsFlowFastAndRecovers() {
        val s = DrivingScorer()
        run(s, 0.0, 20.0) { t -> DrivingSignals(t, steeringAngleDeg = (sin(t) * 10).toFloat(), gear = Gear.DRIVE, speedKmh = 45f, seatBeltFastened = true) }
        val before = s.state.flow
        // sawtooth steering ~ 85 deg amplitude at 9 rad/s -> rates > 500 deg/s
        run(s, 20.0, 23.0) { t -> DrivingSignals(t, steeringAngleDeg = (sin(t * 9.0) * 85).toFloat(), gear = Gear.DRIVE, speedKmh = 45f, seatBeltFastened = true) }
        val during = s.state
        assertTrue(during.flow < before - 0.25f, "flow should drop (before=$before, during=${during.flow})")
        assertEquals(DriveEvent.HARSH_STEER, during.lastEvent)
        run(s, 23.0, 45.0) { t -> DrivingSignals(t, steeringAngleDeg = (sin(t) * 8).toFloat(), gear = Gear.DRIVE, speedKmh = 45f, seatBeltFastened = true) }
        assertTrue(s.state.flow > 0.85f, "flow should recover, was ${s.state.flow}")
    }

    @Test
    fun brakePumpingIsPenalizedButSingleSoftBrakeIsNot() {
        val s = DrivingScorer()
        run(s, 0.0, 10.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 40f, seatBeltFastened = true) }
        run(s, 10.0, 13.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 40f, brake = t < 12.0, seatBeltFastened = true) } // one 2 s brake
        assertTrue(s.state.lastEvent == null || !s.state.lastEvent!!.isPenalty, "a single soft brake must not be a penalty")
        run(s, 13.0, 18.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 40f, brake = ((t * 2.5).toInt() % 2 == 0), seatBeltFastened = true) }
        assertTrue(s.state.lastEvent == DriveEvent.BRAKE_PUMP || s.state.lastEvent == DriveEvent.BRAKE_STAB)
        assertTrue(s.state.flow < 0.8f)
    }

    @Test
    fun graceRuleHalvesPenaltyAfterLongCleanStreak() {
        // scorer A: event after 40 s clean streak; scorer B: event after 5 s
        val a = DrivingScorer(); val b = DrivingScorer()
        run(a, 0.0, 40.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 45f, seatBeltFastened = true) }
        run(b, 0.0, 5.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 45f, seatBeltFastened = true) }
        val stab = { t: Double, t0: Double -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 45f, brake = t - t0 < 0.2, seatBeltFastened = true) }
        // run just past the brake release so the un-smoothed target reflects the fresh penalty
        run(a, 40.0, 40.3) { t -> stab(t, 40.0) }
        run(b, 5.0, 5.3) { t -> stab(t, 5.0) }
        assertEquals(DriveEvent.BRAKE_STAB, a.state.lastEvent)
        assertEquals(DriveEvent.BRAKE_STAB, b.state.lastEvent)
        assertTrue(a.state.instant > b.state.instant + 0.05f,
            "grace: penalty after long streak (instant=${a.state.instant}) should be milder than after short streak (instant=${b.state.instant})")
    }

    @Test
    fun overSpeedLowersInstantTarget() {
        val s = DrivingScorer()
        run(s, 0.0, 10.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 45f, speedLimitKmh = 50, seatBeltFastened = true) }
        run(s, 10.0, 14.0) { t -> DrivingSignals(t, gear = Gear.DRIVE, speedKmh = 70f, speedLimitKmh = 50, seatBeltFastened = true) }
        assertTrue(s.state.overSpeedRatio > 0.5f)
        assertTrue(s.state.instant < 0.7f)
        assertEquals(DriveEvent.OVER_SPEED, s.state.lastEvent)
    }

    @Test
    fun parkedIsNeverPenalized() {
        val s = DrivingScorer()
        run(s, 0.0, 10.0) { t -> DrivingSignals(t, steeringAngleDeg = (sin(t * 9.0) * 300).toFloat(), gear = Gear.PARK, brake = (t * 4).toInt() % 2 == 0) }
        assertTrue(s.state.lastEvent == null)
        assertTrue(!s.state.moving)
    }

    @Test
    fun signaledTurnIsRewardedUnsignaledIsNot() {
        val good = DrivingScorer(); val bad = DrivingScorer()
        val turn = { t: Double -> if (t in 10.0..14.0) (sin((t - 10.0) / 4.0 * Math.PI) * 150).toFloat() else 0f }
        run(good, 0.0, 16.0) { t -> DrivingSignals(t, steeringAngleDeg = turn(t), turnSignal = if (t in 8.0..14.0) TurnSignal.LEFT else TurnSignal.OFF, gear = Gear.DRIVE, speedKmh = 30f, seatBeltFastened = true) }
        run(bad, 0.0, 16.0) { t -> DrivingSignals(t, steeringAngleDeg = turn(t), gear = Gear.DRIVE, speedKmh = 30f, seatBeltFastened = true) }
        assertEquals(DriveEvent.SIGNALED_TURN, good.state.lastEvent)
        assertEquals(DriveEvent.NO_SIGNAL_TURN, bad.state.lastEvent)
        assertTrue(good.state.flow > bad.state.flow)
    }

    @Test
    fun scenariosProduceExpectedOrdering() {
        val safe = DrivingScorer(); val erratic = DrivingScorer()
        var sumSafe = 0.0; var sumErr = 0.0; var n = 0
        var t = 0.0
        while (t < 60.0) {
            sumSafe += safe.update(Scenarios.safe.signalsAt(t), dt).flow
            sumErr += erratic.update(Scenarios.erratic.signalsAt(t), dt).flow
            n++; t += dt
        }
        assertTrue(sumSafe / n > sumErr / n + 0.15, "safe mean flow ${sumSafe / n} should exceed erratic ${sumErr / n}")
    }
}
