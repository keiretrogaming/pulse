package com.kei.pulse.overlay

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier

/**
 * Pure routing for the Quick Access "This game ⇄ All games" scope (SteamOS-style, the [AppSettings.quickAccessPerGameScope]
 * flag). The scope only affects the PERFORMANCE actions (AutoTDP/tier/fps/bias/park — i.e.
 * [QuickAccessPerApp.isPerAppAction]); fan/RGB/overlay/system are always global. When per-game scope is off
 * those perf actions write the GLOBAL default instead of the foreground game's [PerAppConfig].
 *
 * Device-free so the routing decision is unit-tested; the service does the persist + device apply.
 */
object QuickAccessScope {

    /** True when a scope-sensitive perf [action] should write the GLOBAL default (per-game scope is off). */
    fun routesGlobal(action: QuickAccessAction, perGameScope: Boolean): Boolean =
        QuickAccessPerApp.isPerAppAction(action) && !perGameScope

    /**
     * The new global settings after a global-scope perf [action]. The AutoTDP value actions
     * (ToggleAutoTdp/Fps/Bias/Park) reduce through [QuickAccess.reduce] (which already targets the global
     * AutoTDP defaults); [QuickAccessAction.SetTier] sets the active tier label and turns the AutoTDP default
     * OFF (picking a tier is mutually exclusive with AutoTDP).
     */
    fun globalReduce(settings: AppSettings, action: QuickAccessAction): AppSettings = when (action) {
        is QuickAccessAction.SetTier ->
            settings.copy(activeTierLabel = action.tier.label, autoTdpDefaultEnabled = false)
        is QuickAccessAction.SetStockMode ->
            settings.copy(autoTdpDefaultEnabled = false) // "don't tune by default"; no tier applied
        else -> QuickAccess.reduce(settings, action)
    }

    /**
     * Whether the foreground game "follows the global default" — true only when it has NO per-app binding of
     * its own. A global edit takes effect on such a game live; a game with its own AutoTDP/tier/Custom profile
     * keeps it (the edit-switch model is non-destructive — it never discards a per-app profile).
     */
    fun followsGlobal(config: PerAppConfig?): Boolean = config?.profileBinding == null

    /** The AutoTDP value fields a global "All games" edit can carry. */
    enum class GlobalPerfField { FPS, BIAS, PARK }

    /**
     * Whether a RUNNING AutoTDP session on [config] derives its [field] from the GLOBAL default — i.e. a
     * global edit of that field must be re-pushed to the live session. True for a following-global game
     * (no binding at all) AND for an AUTO_BINDING game whose per-app value for that field is null (the
     * `effective*` fallbacks inherit the global there — gating on [followsGlobal] alone missed that, so a
     * global fps edit didn't reach a bound AutoTDP game until rebind).
     */
    fun receivesGlobalPerfEdit(config: PerAppConfig?, field: GlobalPerfField): Boolean = when {
        config == null -> true
        !PerAppConfig.isAuto(config.profileBinding) && config.profileBinding != null -> false
        else -> when (field) {
            GlobalPerfField.FPS -> config.fpsTarget == null
            GlobalPerfField.BIAS -> config.bias == null
            GlobalPerfField.PARK -> config.aggressivePark == null
        }
    }

    /**
     * What COMMITTING the scope switch must do to the foreground game (user-chosen semantics, 2026-07-03 —
     * this replaced the earlier non-destructive edit-switch): committing **Global** DELETES the game's
     * per-app profile so it truly falls back to the global defaults; committing **Per-Game** CREATES a
     * profile seeded from the current effective global mode (or keeps the existing one untouched). The
     * destructive direction is guarded in the UI by an explicit A-press (never a browse ←/→).
     */
    sealed interface ScopeCommit {
        /** Nothing to change on the game — only the scope flag flips. */
        object FlagOnly : ScopeCommit
        /** Per-Game → Global: remove the game's profile; the service re-resolves tuning as if it never existed. */
        data class DeleteProfile(val packageName: String) : ScopeCommit
        /** Global → Per-Game: save this seeded profile and bind it live. */
        data class CreateSeeded(val config: PerAppConfig) : ScopeCommit
    }

    /** The pure commit plan. [existing] is the STORED config for [packageName] (not just the bound one). */
    fun scopeCommitPlan(
        perGame: Boolean,
        packageName: String?,
        existing: PerAppConfig?,
        settings: AppSettings,
    ): ScopeCommit = when {
        packageName == null -> ScopeCommit.FlagOnly
        !perGame -> if (existing != null) ScopeCommit.DeleteProfile(packageName) else ScopeCommit.FlagOnly
        existing != null -> ScopeCommit.FlagOnly // already has its own profile — Per-Game just edits it
        else -> ScopeCommit.CreateSeeded(seedFromGlobal(packageName, settings))
    }

    /**
     * A new per-app profile mirroring the CURRENT global mode. AutoTDP seeds with null value fields (the
     * `effective*` fallbacks keep inheriting the global live values until the user edits them per-game);
     * stock / an unknown tier label seed the explicit AUTO_OFF binding (which [PerAppConfig.hasAnyBinding]
     * keeps through saveConfig). SNAPSHOT INVARIANT (leans on this mirroring — don't break it): a tier seed
     * implies the global mode was a tier, so the game was NOT in a bound global-AutoTDP session, so the
     * force-rebind enters via firstEntry and takes the pre-game snapshot that the tier apply needs restored
     * on exit. An AutoTDP seed needs no snapshot (stopAutoTdp reopens the clocks itself).
     */
    fun seedFromGlobal(packageName: String, settings: AppSettings): PerAppConfig = PerAppConfig(
        packageName = packageName,
        profileBinding = when {
            settings.autoTdpDefaultEnabled -> PerAppConfig.AUTO_BINDING
            else -> PowerTier.entries.firstOrNull { it.label == settings.activeTierLabel }
                ?.let { PerAppConfig.tierBinding(it) }
                ?: PerAppConfig.AUTO_OFF_BINDING
        },
    )
}
