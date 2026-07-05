package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The stock fan controller caches the active mode and won't re-apply the same one, so [FanController.setMode]
 * bounces through a different real mode first to force a reload. Reaching SMART must bounce through SILENT
 * (low fan), NOT SPORT (high fan) — otherwise handing the fan back to Smart (e.g. AutoTDP restoring it on
 * game-exit) revs the fan for a moment. This locks that down.
 */
class FanControllerTest {

    @Test
    fun reachingSmartBouncesThroughSilentNotSport() {
        assertEquals(FanController.SILENT, FanController.bounceModeFor(FanController.SMART))
        assertNotEquals(
            "reaching Smart must never route through the high Sport fan (that's the rev)",
            FanController.SPORT,
            FanController.bounceModeFor(FanController.SMART),
        )
    }

    @Test
    fun reachingSportBouncesThroughSmart() {
        assertEquals(FanController.SMART, FanController.bounceModeFor(FanController.SPORT))
    }

    @Test
    fun reachingSilentBouncesThroughSmart() {
        assertEquals(FanController.SMART, FanController.bounceModeFor(FanController.SILENT))
    }

    @Test
    fun bounceModeAlwaysDiffersFromTarget() {
        for (target in listOf(FanController.SILENT, FanController.SMART, FanController.SPORT)) {
            assertNotEquals(target, FanController.bounceModeFor(target))
        }
    }
}
