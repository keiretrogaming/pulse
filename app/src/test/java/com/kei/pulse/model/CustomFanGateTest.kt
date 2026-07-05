package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Truth table for [CustomFanGate] — engage Custom (fan_mode=6) when the chip is warm OR active cooling is
 *  wanted; hand to vendor Smart only when cool + at the floor (death-safe AND quiet). Engage-fast/release-slow. */
class CustomFanGateTest {

    private val floor = 20
    private val engage = CustomFanGate.ENGAGE_TEMP_C // 50

    @Test fun coolAndAtFloorHandsToSmart() {
        val s = CustomFanGate.next(CustomFanState(), tempC = 40, targetPercent = 20, floorPercent = floor)
        assertFalse(s.active)
        assertEquals(1, s.idleTicks)
    }

    @Test fun warmChipEngagesEvenWhenTargetIsFloor() {
        // Mid-game at 60°C with the PI still at the floor (below the 78°C target): PULSE must keep driving its
        // quiet floor, NOT hand to vendor Smart (which may ramp harder there).
        val s = CustomFanGate.next(CustomFanState(active = false, idleTicks = 3), tempC = 60, targetPercent = 20, floorPercent = floor)
        assertTrue(s.active)
        assertEquals(0, s.idleTicks)
    }

    @Test fun aboveFloorTargetEngagesWhenCool() {
        // A manual curve that wants >floor even when cool → drive Custom (respect the curve).
        val s = CustomFanGate.next(CustomFanState(), tempC = 40, targetPercent = 35, floorPercent = floor)
        assertTrue(s.active)
    }

    @Test fun staysActiveThroughBriefCoolDip() {
        var s = CustomFanState(active = true, idleTicks = 0)
        s = CustomFanGate.next(s, tempC = 40, targetPercent = 20, floorPercent = floor)
        assertTrue(s.active)
        assertEquals(1, s.idleTicks)
    }

    @Test fun releasesToSmartAfterSustainedIdle() {
        var s = CustomFanState(active = true, idleTicks = 0)
        repeat(CustomFanGate.RELEASE_TICKS - 1) { s = CustomFanGate.next(s, 40, 20, floor) }
        assertTrue("still active just before the release threshold", s.active)
        s = CustomFanGate.next(s, 40, 20, floor)
        assertFalse("released to Smart after sustained cool+floor", s.active)
    }

    @Test fun warmthResetsTheIdleStreak() {
        var s = CustomFanState(active = true, idleTicks = 0)
        repeat(3) { s = CustomFanGate.next(s, 40, 20, floor) }
        assertEquals(3, s.idleTicks)
        s = CustomFanGate.next(s, engage + 5, 20, floor) // chip warms past the threshold
        assertTrue(s.active)
        assertEquals(0, s.idleTicks)
    }

    @Test fun `exactly at the engage temp counts as warm`() {
        // The threshold is strict (tempC < engageTempC is idle), so temp == ENGAGE_TEMP_C is warm → Custom.
        val s = CustomFanGate.next(CustomFanState(), tempC = engage, targetPercent = 20, floorPercent = floor)
        assertTrue(s.active)
    }
}
