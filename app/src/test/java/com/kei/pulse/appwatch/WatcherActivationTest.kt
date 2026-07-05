package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.RgbMode
import com.kei.pulse.data.FanController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watcher-activation decision. Foreground-free features (global Fan / RGB) must run with no
 * permission; foreground-dependent features (per-app, AutoTDP, OSD) need Usage Access. This is the
 * exact logic that, when over-broad, silently killed the global Fan/RGB on a fresh install and across
 * a reboot — so the truth table is locked down here.
 */
class WatcherActivationTest {

    private val nothing = AppSettings(
        rgbMode = RgbMode.OFF,
        managedFanMode = null,
        autoTdpDefaultEnabled = false,
        overlayEnabled = false,
    )

    private fun shouldRun(
        settings: AppSettings,
        hasUsageAccess: Boolean,
        perAppEnabled: Boolean = false,
        hasPerAppConfigs: Boolean = false,
    ) = WatcherActivation.shouldRun(
        perAppEnabled = perAppEnabled,
        hasPerAppConfigs = hasPerAppConfigs,
        settings = settings,
        hasUsageAccess = hasUsageAccess,
    )

    @Test
    fun freshInstallWithNothingEnabledDoesNotRun() {
        assertFalse(shouldRun(nothing, hasUsageAccess = false))
    }

    @Test
    fun nothingEnabledDoesNotRunEvenWithUsageAccess() {
        assertFalse(shouldRun(nothing, hasUsageAccess = true))
    }

    @Test
    fun globalFanRunsWithoutUsageAccess() {
        val fanOnly = nothing.copy(managedFanMode = FanController.CUSTOM)
        assertTrue(shouldRun(fanOnly, hasUsageAccess = false))
    }

    @Test
    fun globalRgbRunsWithoutUsageAccess() {
        val rgbOnly = nothing.copy(rgbMode = RgbMode.BATTERY)
        assertTrue(shouldRun(rgbOnly, hasUsageAccess = false))
    }

    @Test
    fun autoTdpAloneDoesNotRunWithoutUsageAccess() {
        val autoOnly = nothing.copy(autoTdpDefaultEnabled = true)
        assertFalse(shouldRun(autoOnly, hasUsageAccess = false))
    }

    @Test
    fun autoTdpRunsWithUsageAccess() {
        val autoOnly = nothing.copy(autoTdpDefaultEnabled = true)
        assertTrue(shouldRun(autoOnly, hasUsageAccess = true))
    }

    @Test
    fun overlayAloneDoesNotRunWithoutUsageAccess() {
        val overlayOnly = nothing.copy(overlayEnabled = true)
        assertFalse(shouldRun(overlayOnly, hasUsageAccess = false))
    }

    @Test
    fun overlayRunsWithUsageAccess() {
        val overlayOnly = nothing.copy(overlayEnabled = true)
        assertTrue(shouldRun(overlayOnly, hasUsageAccess = true))
    }

    @Test
    fun quickAccessAloneDoesNotRunWithoutUsageAccess() {
        val qaOnly = nothing.copy(quickAccessEnabled = true)
        assertFalse(shouldRun(qaOnly, hasUsageAccess = false))
    }

    @Test
    fun quickAccessRunsWithUsageAccess() {
        val qaOnly = nothing.copy(quickAccessEnabled = true)
        assertTrue(shouldRun(qaOnly, hasUsageAccess = true))
    }

    @Test
    fun perAppEnabledAloneDoesNotRunWithoutUsageAccess() {
        assertFalse(shouldRun(nothing, hasUsageAccess = false, perAppEnabled = true))
    }

    @Test
    fun perAppConfigsRunWithUsageAccess() {
        assertTrue(shouldRun(nothing, hasUsageAccess = true, hasPerAppConfigs = true))
    }

    @Test
    fun globalFanKeepsServiceAliveWhileAutoTdpWaitsForPermission() {
        // Fan (foreground-free) + AutoTDP (foreground-dependent), no Usage Access: the service must stay
        // up to drive the fan; AutoTDP simply stays dormant until permission is granted.
        val fanAndAuto = nothing.copy(managedFanMode = FanController.CUSTOM, autoTdpDefaultEnabled = true)
        assertTrue(shouldRun(fanAndAuto, hasUsageAccess = false))
    }
}
