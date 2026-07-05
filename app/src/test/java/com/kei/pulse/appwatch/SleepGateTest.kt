package com.kei.pulse.appwatch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The TOTAL-sleep contract (1.19.6 battery fix): while the screen is off the watcher does ABSOLUTELY
 * nothing — no fan math, no temp reads, no UsageStats, no cap re-asserts. Deliberately trivial: this test
 * exists so no future edit can sneak per-tick work back into the screen-off state without failing here.
 */
class SleepGateTest {

    @Test
    fun `screen on is a full tick, screen off is a skip`() {
        assertEquals(SleepGate.TickWork.FULL, SleepGate.tickWork(screenOn = true))
        assertEquals(SleepGate.TickWork.SKIP, SleepGate.tickWork(screenOn = false))
    }

    @Test
    fun `transitions fire exactly on the edges`() {
        assertEquals(SleepGate.Transition.NONE, SleepGate.transition(prevOn = true, nowOn = true))
        assertEquals(SleepGate.Transition.NONE, SleepGate.transition(prevOn = false, nowOn = false))
        assertEquals(SleepGate.Transition.WENT_OFF, SleepGate.transition(prevOn = true, nowOn = false))
        assertEquals(SleepGate.Transition.WENT_ON, SleepGate.transition(prevOn = false, nowOn = true))
    }
}
