package com.kei.pulse.overlay

import com.kei.pulse.data.FanController
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Quick Access Bar core: the control reducer (which setting each panel control
 * writes), the handle-visibility gate, and the right-docked panel geometry. The overlay window + Compose
 * UI are hardware-verified; these device-free decisions are tested here.
 */
class QuickAccessStateTest {

    private val base = AppSettings()

    @Test
    fun `audit labels carry the action's value`() {
        // The PulseQA audit line must identify WHAT was applied, not just that something was ("SetBias SMOOTH
        // → app.gamenative" is the whole point). Exhaustiveness is compile-checked by the when; this pins the
        // value-carrying format for the incident-class actions.
        assertEquals("SetBias SMOOTH", QuickAccess.auditLabel(QuickAccessAction.SetBias(AutoTdpBias.SMOOTH)))
        assertEquals("SetFpsTarget 60", QuickAccess.auditLabel(QuickAccessAction.SetFpsTarget(60)))
        assertEquals("SetFanMode 6", QuickAccess.auditLabel(QuickAccessAction.SetFanMode(FanController.CUSTOM)))
        assertEquals("SetScope GLOBAL", QuickAccess.auditLabel(QuickAccessAction.SetScope(false)))
        assertEquals("SetScope PER-GAME", QuickAccess.auditLabel(QuickAccessAction.SetScope(true)))
        assertEquals("SetPowerTarget 55%", QuickAccess.auditLabel(QuickAccessAction.SetPowerTarget(55)))
    }

    @Test
    fun `toggle autotdp flips the default-enabled flag`() {
        assertTrue(
            QuickAccess.reduce(base.copy(autoTdpDefaultEnabled = false), QuickAccessAction.ToggleAutoTdp)
                .autoTdpDefaultEnabled,
        )
        assertFalse(
            QuickAccess.reduce(base.copy(autoTdpDefaultEnabled = true), QuickAccessAction.ToggleAutoTdp)
                .autoTdpDefaultEnabled,
        )
    }

    @Test
    fun `set fps target updates the target`() {
        assertEquals(120, QuickAccess.reduce(base, QuickAccessAction.SetFpsTarget(120)).autoTdpFpsTarget)
    }

    @Test
    fun `set bias updates the bias`() {
        assertEquals(
            AutoTdpBias.SMOOTH,
            QuickAccess.reduce(base, QuickAccessAction.SetBias(AutoTdpBias.SMOOTH)).autoTdpBias,
        )
    }

    @Test
    fun `set power target enables the cap and stores the percent`() {
        val r = QuickAccess.reduce(base, QuickAccessAction.SetPowerTarget(85))
        assertTrue(r.powerTargetEnabled)
        assertEquals(85, r.powerTargetPercent)
    }

    @Test
    fun `power target of 100 percent means no cap`() {
        val r = QuickAccess.reduce(base, QuickAccessAction.SetPowerTarget(100))
        assertFalse(r.powerTargetEnabled)
        assertEquals(100, r.powerTargetPercent)
    }

    @Test
    fun `power target is clamped to the minimum`() {
        assertEquals(
            QuickAccess.POWER_TARGET_MIN,
            QuickAccess.reduce(base, QuickAccessAction.SetPowerTarget(2)).powerTargetPercent,
        )
    }

    @Test
    fun `set fan mode updates the managed fan mode`() {
        assertEquals(
            FanController.CUSTOM,
            QuickAccess.reduce(base, QuickAccessAction.SetFanMode(FanController.CUSTOM)).managedFanMode,
        )
    }

    @Test
    fun `set aggressive park updates the flag`() {
        assertFalse(QuickAccess.reduce(base.copy(autoTdpAggressivePark = true), QuickAccessAction.SetAggressivePark(false)).autoTdpAggressivePark)
        assertTrue(QuickAccess.reduce(base.copy(autoTdpAggressivePark = false), QuickAccessAction.SetAggressivePark(true)).autoTdpAggressivePark)
    }

    @Test
    fun `set fan target temp updates and clamps to the controller range`() {
        assertEquals(60, QuickAccess.reduce(base, QuickAccessAction.SetFanTargetTemp(60)).fanTargetTempC)
        // Below the controller's selectable minimum clamps up.
        assertEquals(
            com.kei.pulse.model.FanTempController.TARGET_MIN_C,
            QuickAccess.reduce(base, QuickAccessAction.SetFanTargetTemp(0)).fanTargetTempC,
        )
    }

    @Test
    fun `set rgb mode updates the mode`() {
        assertEquals(
            com.kei.pulse.model.RgbMode.BATTERY,
            QuickAccess.reduce(base, QuickAccessAction.SetRgbMode(com.kei.pulse.model.RgbMode.BATTERY)).rgbMode,
        )
    }

    @Test
    fun `set overlay enabled updates the flag`() {
        assertTrue(QuickAccess.reduce(base, QuickAccessAction.SetOverlayEnabled(true)).overlayEnabled)
    }

    @Test
    fun `set overlay preset updates the preset and seeds its elements`() {
        val r = QuickAccess.reduce(base, QuickAccessAction.SetOverlayPreset(com.kei.pulse.model.OverlayPreset.FULL))
        assertEquals(com.kei.pulse.model.OverlayPreset.FULL, r.overlayPreset)
        assertEquals(com.kei.pulse.model.OverlayPreset.FULL.elements, r.overlayElements)
    }

    @Test
    fun `set fan smart updates hold-target-temp`() {
        assertTrue(QuickAccess.reduce(base.copy(fanSmartEnabled = false), QuickAccessAction.SetFanSmart(true)).fanSmartEnabled)
        assertFalse(QuickAccess.reduce(base.copy(fanSmartEnabled = true), QuickAccessAction.SetFanSmart(false)).fanSmartEnabled)
    }

    @Test
    fun `set rgb color applies to both sticks`() {
        val r = QuickAccess.reduce(base, QuickAccessAction.SetRgbColor(0xFFFF0000.toInt()))
        assertEquals(0xFFFF0000.toInt(), r.rgbManualLeftColor)
        assertEquals(0xFFFF0000.toInt(), r.rgbManualRightColor)
    }

    @Test
    fun `handle shows only when enabled with permission over a non-neutral foreground`() {
        assertTrue(QuickAccess.shouldShowHandle(enabled = true, hasOverlayPermission = true, foregroundNeutral = false))
        assertFalse(QuickAccess.shouldShowHandle(enabled = false, hasOverlayPermission = true, foregroundNeutral = false))
        assertFalse(QuickAccess.shouldShowHandle(enabled = true, hasOverlayPermission = false, foregroundNeutral = false))
        assertFalse(QuickAccess.shouldShowHandle(enabled = true, hasOverlayPermission = true, foregroundNeutral = true))
    }

    @Test
    fun `panel width is a fraction of the screen`() {
        assertEquals(710, QuickAccess.widthPx(screenWidthPx = 1920, fraction = 0.37f, minPx = 280, maxPx = 720))
    }

    @Test
    fun `panel width clamps to the max on huge screens`() {
        assertEquals(720, QuickAccess.widthPx(3840, 0.37f, 280, 720))
    }

    @Test
    fun `panel width clamps to the min on tiny screens`() {
        assertEquals(280, QuickAccess.widthPx(600, 0.37f, 280, 720))
    }
}
