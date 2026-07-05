package com.kei.pulse.overlay

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.RgbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the Quick Access "This game ⇄ All games" scope router. The device apply is
 * hardware-verified; the routing decision + the global reducer + the follows-global predicate are tested here.
 */
class QuickAccessScopeTest {

    private val base = AppSettings()

    private val perfActions = listOf(
        QuickAccessAction.ToggleAutoTdp,
        QuickAccessAction.SetTier(PowerTier.BALANCED),
        QuickAccessAction.SetStockMode,
        QuickAccessAction.SetFpsTarget(60),
        QuickAccessAction.SetBias(AutoTdpBias.SMOOTH),
        QuickAccessAction.SetAggressivePark(true),
    )

    @Test
    fun `perf actions route global only when per-game scope is off`() {
        perfActions.forEach {
            assertFalse(it.toString(), QuickAccessScope.routesGlobal(it, perGameScope = true))
            assertTrue(it.toString(), QuickAccessScope.routesGlobal(it, perGameScope = false))
        }
    }

    @Test
    fun `non-perf actions never route global regardless of scope`() {
        val nonPerf = listOf(
            QuickAccessAction.SetFanMode(1),
            QuickAccessAction.SetRgbMode(RgbMode.BATTERY),
            QuickAccessAction.SetOverlayEnabled(true),
            QuickAccessAction.SetBrightness(50),
            QuickAccessAction.SetVolume(50),
            QuickAccessAction.SetScope(true),
        )
        nonPerf.forEach {
            assertFalse(it.toString(), QuickAccessScope.routesGlobal(it, perGameScope = false))
            assertFalse(it.toString(), QuickAccessScope.routesGlobal(it, perGameScope = true))
        }
    }

    @Test
    fun `global tier sets the active label and turns autotdp off`() {
        val r = QuickAccessScope.globalReduce(
            base.copy(autoTdpDefaultEnabled = true),
            QuickAccessAction.SetTier(PowerTier.BALANCED),
        )
        assertEquals(PowerTier.BALANCED.label, r.activeTierLabel)
        assertFalse(r.autoTdpDefaultEnabled)
    }

    @Test
    fun `global autotdp value edits reduce through the global defaults`() {
        assertTrue(
            QuickAccessScope.globalReduce(base.copy(autoTdpDefaultEnabled = false), QuickAccessAction.ToggleAutoTdp)
                .autoTdpDefaultEnabled,
        )
        assertEquals(90, QuickAccessScope.globalReduce(base, QuickAccessAction.SetFpsTarget(90)).autoTdpFpsTarget)
        assertEquals(
            AutoTdpBias.SMOOTH,
            QuickAccessScope.globalReduce(base, QuickAccessAction.SetBias(AutoTdpBias.SMOOTH)).autoTdpBias,
        )
        assertFalse(
            QuickAccessScope.globalReduce(base.copy(autoTdpAggressivePark = true), QuickAccessAction.SetAggressivePark(false))
                .autoTdpAggressivePark,
        )
    }

    @Test
    fun `follows global only when there is no per-app binding`() {
        assertTrue(QuickAccessScope.followsGlobal(null))
        assertTrue(QuickAccessScope.followsGlobal(PerAppConfig(packageName = "x", profileBinding = null)))
        assertFalse(QuickAccessScope.followsGlobal(PerAppConfig(packageName = "x", profileBinding = PerAppConfig.AUTO_BINDING)))
        assertFalse(QuickAccessScope.followsGlobal(PerAppConfig(packageName = "x", profileBinding = PerAppConfig.AUTO_OFF_BINDING)))
        assertFalse(
            QuickAccessScope.followsGlobal(
                PerAppConfig(packageName = "x", profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED)),
            ),
        )
    }

    @Test
    fun `global stock mode turns the autotdp default off without touching the tier label`() {
        val before = base.copy(autoTdpDefaultEnabled = true, activeTierLabel = "Balanced")
        val r = QuickAccessScope.globalReduce(before, QuickAccessAction.SetStockMode)
        assertFalse(r.autoTdpDefaultEnabled)
        assertEquals("Balanced", r.activeTierLabel)
    }

    @Test
    fun `per-app stock mode writes the explicit auto-off binding preserving autotdp fields`() {
        val existing = PerAppConfig(
            packageName = "x", profileBinding = PerAppConfig.tierBinding(PowerTier.MAX),
            fpsTarget = 90, bias = AutoTdpBias.SMOOTH, aggressivePark = false,
        )
        val r = QuickAccessPerApp.applyPerAppAction(existing, "x", QuickAccessAction.SetStockMode)
        assertEquals(PerAppConfig.AUTO_OFF_BINDING, r.profileBinding)
        assertEquals(90, r.fpsTarget)
        assertEquals(AutoTdpBias.SMOOTH, r.bias)
        assertEquals(false, r.aggressivePark)
    }

    @Test
    fun `receives global perf edit - following-global and inheriting auto-bound games only`() {
        val f = QuickAccessScope.GlobalPerfField.FPS
        // No binding at all: follows the global entirely.
        assertTrue(QuickAccessScope.receivesGlobalPerfEdit(null, f))
        // AUTO_BINDING with a null per-app fps: the session inherits the global fps → must be re-pushed.
        val inheriting = PerAppConfig(packageName = "x", profileBinding = PerAppConfig.AUTO_BINDING)
        assertTrue(QuickAccessScope.receivesGlobalPerfEdit(inheriting, f))
        // AUTO_BINDING with its own fps: the per-app value wins → a global fps edit must NOT touch it…
        val ownFps = inheriting.copy(fpsTarget = 90)
        assertFalse(QuickAccessScope.receivesGlobalPerfEdit(ownFps, f))
        // …but the SAME game still inherits the fields it left null.
        assertTrue(QuickAccessScope.receivesGlobalPerfEdit(ownFps, QuickAccessScope.GlobalPerfField.BIAS))
        assertTrue(QuickAccessScope.receivesGlobalPerfEdit(ownFps, QuickAccessScope.GlobalPerfField.PARK))
        // Tier/Custom/AUTO_OFF bindings never take global AutoTDP edits.
        val tierBound = PerAppConfig(packageName = "x", profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED))
        assertFalse(QuickAccessScope.receivesGlobalPerfEdit(tierBound, f))
        val stock = PerAppConfig(packageName = "x", profileBinding = PerAppConfig.AUTO_OFF_BINDING)
        assertFalse(QuickAccessScope.receivesGlobalPerfEdit(stock, f))
    }

    // ---- Scope COMMIT plan (the A-press semantics, user-chosen 2026-07-03): committing Global DELETES the
    // game's profile so it truly follows the global defaults; committing Per-Game CREATES one seeded from the
    // current effective global mode. ----

    @Test
    fun `scope commit with no foreground game is flag-only in both directions`() {
        assertEquals(
            QuickAccessScope.ScopeCommit.FlagOnly,
            QuickAccessScope.scopeCommitPlan(perGame = true, packageName = null, existing = null, settings = base),
        )
        assertEquals(
            QuickAccessScope.ScopeCommit.FlagOnly,
            QuickAccessScope.scopeCommitPlan(perGame = false, packageName = null, existing = null, settings = base),
        )
    }

    @Test
    fun `committing global deletes the game's profile when one exists, else flag-only`() {
        val profile = PerAppConfig(packageName = "g", profileBinding = PerAppConfig.AUTO_BINDING)
        assertEquals(
            QuickAccessScope.ScopeCommit.DeleteProfile("g"),
            QuickAccessScope.scopeCommitPlan(perGame = false, packageName = "g", existing = profile, settings = base),
        )
        assertEquals(
            QuickAccessScope.ScopeCommit.FlagOnly,
            QuickAccessScope.scopeCommitPlan(perGame = false, packageName = "g", existing = null, settings = base),
        )
    }

    @Test
    fun `committing per-game keeps an existing profile untouched`() {
        // The game already has its own profile — Per-Game just points future edits at it (no data loss).
        val profile = PerAppConfig(packageName = "g", profileBinding = PerAppConfig.tierBinding(PowerTier.MAX), fpsTarget = 90)
        assertEquals(
            QuickAccessScope.ScopeCommit.FlagOnly,
            QuickAccessScope.scopeCommitPlan(perGame = true, packageName = "g", existing = profile, settings = base),
        )
    }

    @Test
    fun `committing per-game seeds a profile mirroring the current global mode`() {
        // Global AutoTDP on → AUTO_BINDING with null value fields (they keep inheriting the global live values).
        val autoPlan = QuickAccessScope.scopeCommitPlan(
            perGame = true, packageName = "g", existing = null,
            settings = base.copy(autoTdpDefaultEnabled = true, activeTierLabel = PowerTier.BALANCED.label),
        )
        val auto = (autoPlan as QuickAccessScope.ScopeCommit.CreateSeeded).config
        assertEquals("g", auto.packageName)
        assertEquals(PerAppConfig.AUTO_BINDING, auto.profileBinding)
        assertEquals(null, auto.fpsTarget)
        assertEquals(null, auto.bias)
        assertEquals(null, auto.aggressivePark)

        // Global tier active (AutoTDP off) → the matching tier binding.
        val tierPlan = QuickAccessScope.scopeCommitPlan(
            perGame = true, packageName = "g", existing = null,
            settings = base.copy(autoTdpDefaultEnabled = false, activeTierLabel = PowerTier.BALANCED.label),
        )
        assertEquals(
            PerAppConfig.tierBinding(PowerTier.BALANCED),
            (tierPlan as QuickAccessScope.ScopeCommit.CreateSeeded).config.profileBinding,
        )

        // Global stock (no AutoTDP, label matches no tier) → the explicit AUTO_OFF binding.
        val stockPlan = QuickAccessScope.scopeCommitPlan(
            perGame = true, packageName = "g", existing = null,
            settings = base.copy(autoTdpDefaultEnabled = false, activeTierLabel = ""),
        )
        assertEquals(
            PerAppConfig.AUTO_OFF_BINDING,
            (stockPlan as QuickAccessScope.ScopeCommit.CreateSeeded).config.profileBinding,
        )

        // An unrecognized tier label degrades to stock, never to a broken binding.
        val junkPlan = QuickAccessScope.scopeCommitPlan(
            perGame = true, packageName = "g", existing = null,
            settings = base.copy(autoTdpDefaultEnabled = false, activeTierLabel = "NotATier"),
        )
        assertEquals(
            PerAppConfig.AUTO_OFF_BINDING,
            (junkPlan as QuickAccessScope.ScopeCommit.CreateSeeded).config.profileBinding,
        )
    }

    @Test
    fun `every seeded profile has a binding so saveConfig keeps it`() {
        // PerAppConfigStorage.saveConfig drops a config with no binding (soft delete) — a seeded profile with
        // hasAnyBinding=false would silently vanish, making the Per-Game commit a no-op.
        listOf(
            base.copy(autoTdpDefaultEnabled = true),
            base.copy(autoTdpDefaultEnabled = false, activeTierLabel = PowerTier.MAX.label),
            base.copy(autoTdpDefaultEnabled = false, activeTierLabel = ""),
        ).forEach { settings ->
            val plan = QuickAccessScope.scopeCommitPlan(perGame = true, packageName = "g", existing = null, settings = settings)
            assertTrue(settings.toString(), (plan as QuickAccessScope.ScopeCommit.CreateSeeded).config.hasAnyBinding)
        }
    }

    @Test
    fun `set scope updates the per-game flag`() {
        assertFalse(
            QuickAccess.reduce(base.copy(quickAccessPerGameScope = true), QuickAccessAction.SetScope(false))
                .quickAccessPerGameScope,
        )
        assertTrue(
            QuickAccess.reduce(base.copy(quickAccessPerGameScope = false), QuickAccessAction.SetScope(true))
                .quickAccessPerGameScope,
        )
    }
}
