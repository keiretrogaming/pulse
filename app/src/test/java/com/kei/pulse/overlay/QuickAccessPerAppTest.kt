package com.kei.pulse.overlay

import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.RgbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Quick Access bar's per-app targeting: the AutoTDP controls edit the FOREGROUND game's
 * per-app profile (creating one if needed) instead of the global default — so changing the frame target in
 * the bar actually takes effect for a game that has a per-app binding (bug: per-app fps overrode the global
 * the bar wrote). Fan/RGB/OSD remain global; those actions are NOT per-app.
 */
class QuickAccessPerAppTest {

    private val pkg = "com.game.example"

    @Test
    fun `set fps target creates an autotdp config with that target`() {
        val c = QuickAccessPerApp.applyPerAppAction(null, pkg, QuickAccessAction.SetFpsTarget(120))
        assertEquals(pkg, c.packageName)
        assertEquals(PerAppConfig.AUTO_BINDING, c.profileBinding)
        assertEquals(120, c.fpsTarget)
    }

    @Test
    fun `set fps target updates an existing config and keeps it on autotdp`() {
        val existing = PerAppConfig(packageName = pkg, profileBinding = PerAppConfig.AUTO_BINDING, fpsTarget = 60)
        val c = QuickAccessPerApp.applyPerAppAction(existing, pkg, QuickAccessAction.SetFpsTarget(120))
        assertEquals(120, c.fpsTarget)
        assertEquals(PerAppConfig.AUTO_BINDING, c.profileBinding)
    }

    @Test
    fun `set bias and park target the config`() {
        assertEquals(
            AutoTdpBias.SMOOTH,
            QuickAccessPerApp.applyPerAppAction(null, pkg, QuickAccessAction.SetBias(AutoTdpBias.SMOOTH)).bias,
        )
        assertEquals(
            true,
            QuickAccessPerApp.applyPerAppAction(null, pkg, QuickAccessAction.SetAggressivePark(true)).aggressivePark,
        )
    }

    @Test
    fun `set tier binds the foreground app to that tier`() {
        val c = QuickAccessPerApp.applyPerAppAction(null, pkg, QuickAccessAction.SetTier(com.kei.pulse.model.PowerTier.MAX))
        assertEquals(pkg, c.packageName)
        assertEquals(PerAppConfig.tierBinding(com.kei.pulse.model.PowerTier.MAX), c.profileBinding)
    }

    @Test
    fun `set tier is a mode switch that converts an autotdp binding but preserves the fps target`() {
        val auto = PerAppConfig(packageName = pkg, profileBinding = PerAppConfig.AUTO_BINDING, fpsTarget = 90)
        val c = QuickAccessPerApp.applyPerAppAction(auto, pkg, QuickAccessAction.SetTier(com.kei.pulse.model.PowerTier.BALANCED))
        assertEquals(PerAppConfig.tierBinding(com.kei.pulse.model.PowerTier.BALANCED), c.profileBinding)
        assertEquals("AutoTDP fields are kept for a later switch-back (no data loss)", 90, c.fpsTarget)
        assertFalse("a tier binding means AutoTDP is no longer effective", QuickAccessPerApp.effectiveAutoTdpOn(c, globalDefault = true))
    }

    @Test
    fun `a value edit never converts a non-autotdp binding (no silent profile loss)`() {
        val tierBound = PerAppConfig(packageName = pkg, profileBinding = "tier:MAX")
        val c = QuickAccessPerApp.applyPerAppAction(tierBound, pkg, QuickAccessAction.SetFpsTarget(120))
        assertEquals("tier:MAX", c.profileBinding) // binding preserved
        assertEquals(120, c.fpsTarget)
    }

    @Test
    fun `toggle autotdp flips between explicit on and off`() {
        // Global default OFF: an inheriting app (null) is effectively off ⇒ toggle turns it ON.
        val inheriting = PerAppConfig(packageName = pkg, profileBinding = null)
        assertEquals(
            PerAppConfig.AUTO_BINDING,
            QuickAccessPerApp.applyPerAppAction(inheriting, pkg, QuickAccessAction.ToggleAutoTdp).profileBinding,
        )
        // An explicitly-on app toggles to explicit OFF (not null — so it sticks even under a global ON).
        val on = PerAppConfig(packageName = pkg, profileBinding = PerAppConfig.AUTO_BINDING)
        assertEquals(
            PerAppConfig.AUTO_OFF_BINDING,
            QuickAccessPerApp.applyPerAppAction(on, pkg, QuickAccessAction.ToggleAutoTdp).profileBinding,
        )
        // And explicit OFF toggles back ON.
        val offExplicit = PerAppConfig(packageName = pkg, profileBinding = PerAppConfig.AUTO_OFF_BINDING)
        assertEquals(
            PerAppConfig.AUTO_BINDING,
            QuickAccessPerApp.applyPerAppAction(offExplicit, pkg, QuickAccessAction.ToggleAutoTdp).profileBinding,
        )
    }

    @Test
    fun `isPerAppAction is true for autotdp actions and false for global ones`() {
        assertTrue(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetFpsTarget(60)))
        assertTrue(QuickAccessPerApp.isPerAppAction(QuickAccessAction.ToggleAutoTdp))
        assertTrue(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetBias(AutoTdpBias.EFFICIENT)))
        assertTrue(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetTier(com.kei.pulse.model.PowerTier.MAX)))
        assertFalse(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetRgbMode(RgbMode.OFF)))
        assertFalse(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetFanMode(4)))
        assertFalse(QuickAccessPerApp.isPerAppAction(QuickAccessAction.SetOverlayEnabled(true)))
    }

    @Test
    fun `effective autotdp-on reflects the per-app binding, else the global default`() {
        assertTrue(QuickAccessPerApp.effectiveAutoTdpOn(PerAppConfig(packageName = pkg, profileBinding = PerAppConfig.AUTO_BINDING), globalDefault = false))
        assertTrue(QuickAccessPerApp.effectiveAutoTdpOn(null, globalDefault = true))
        assertFalse(QuickAccessPerApp.effectiveAutoTdpOn(null, globalDefault = false))
        // A non-AutoTDP per-app binding overrides the global default → AutoTDP is NOT on for this app.
        assertFalse(QuickAccessPerApp.effectiveAutoTdpOn(PerAppConfig(packageName = pkg, profileBinding = "tier:MAX"), globalDefault = true))
    }

    @Test
    fun `toggle off while global default on sets an explicit off that overrides the global`() {
        // The QA bug #1: a game inheriting the global default (binding null) is effectively ON when the
        // global default is ON. Toggling AutoTDP OFF must produce an EXPLICIT off (AUTO_OFF_BINDING), not null
        // (which would just re-inherit the global ON), so the game actually runs no AutoTDP.
        val inheriting = PerAppConfig(packageName = pkg, profileBinding = null)
        val toggled = QuickAccessPerApp.applyPerAppAction(
            inheriting, pkg, QuickAccessAction.ToggleAutoTdp, globalDefault = true,
        )
        assertEquals(PerAppConfig.AUTO_OFF_BINDING, toggled.profileBinding)
        assertFalse(
            "explicit per-app off must override the global default",
            QuickAccessPerApp.effectiveAutoTdpOn(toggled, globalDefault = true),
        )
    }

}
