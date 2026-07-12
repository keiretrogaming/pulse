package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PerAppRestoreState
import com.kei.pulse.model.PowerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundBindingTransitionTest {

    private val original = PerAppRestoreState(fanMode = 4, refreshRateHz = 120)

    private fun config(pkg: String, fan: Int? = null, refresh: Int? = null) = PerAppConfig(
        packageName = pkg,
        profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED),
        fanMode = fan,
        refreshRateHz = refresh,
    )

    @Test
    fun `first bind starts session without flushing or restoring`() {
        val plan = planForegroundBindingTransition(null, "game.one", config("game.one"), original, true, false)

        assertEquals(ForegroundBindingTransition.Kind.REBIND, plan.kind)
        assertFalse(plan.flushOutgoingDraw)
        assertNull(plan.restoreFanMode)
        assertNull(plan.restoreRefreshRateHz)
        assertTrue(plan.restartSessionClock)
        assertTrue(plan.resetOverlayAverages)
    }

    @Test
    fun `direct switch flushes outgoing state and restores controls the next app does not own`() {
        val plan = planForegroundBindingTransition(
            "game.one", "game.two", config("game.two"), original, engages = true, force = false,
        )

        assertEquals(ForegroundBindingTransition.Kind.REBIND, plan.kind)
        assertTrue(plan.flushOutgoingDraw)
        assertEquals(4, plan.restoreFanMode)
        assertEquals(120, plan.restoreRefreshRateHz)
        assertTrue(plan.restartSessionClock)
        assertTrue(plan.resetOverlayAverages)
    }

    @Test
    fun `incoming explicit controls are not overwritten by the baseline`() {
        val plan = planForegroundBindingTransition(
            "game.one", "game.two", config("game.two", fan = 5), original, true, false,
        )

        assertNull(plan.restoreFanMode)
        assertEquals(120, plan.restoreRefreshRateHz)
    }

    @Test
    fun `forced same-app rebind flushes state without restarting timers or averages`() {
        val plan = planForegroundBindingTransition(
            "game.one", "game.one", config("game.one"), original, engages = true, force = true,
        )

        assertEquals(ForegroundBindingTransition.Kind.REBIND, plan.kind)
        assertTrue(plan.flushOutgoingDraw)
        assertFalse(plan.restartSessionClock)
        assertFalse(plan.resetOverlayAverages)
    }

    @Test
    fun `global autotdp owns fan and refresh so no baseline controls are restored`() {
        val plan = planForegroundBindingTransition(
            "game.one", "game.two", nextConfig = null, originalState = original, engages = true, force = false,
        )

        assertNull(plan.restoreFanMode)
        assertNull(plan.restoreRefreshRateHz)
    }

    @Test
    fun `leaving the bound chain flushes once and releases`() {
        val plan = planForegroundBindingTransition(
            "game.one", "launcher", nextConfig = null, originalState = original, engages = false, force = false,
        )

        assertEquals(ForegroundBindingTransition.Kind.RELEASE, plan.kind)
        assertTrue(plan.flushOutgoingDraw)
    }
}
