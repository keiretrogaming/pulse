package com.kei.pulse.overlay

import com.kei.pulse.model.PerAppConfig

/**
 * Per-app targeting for the Quick Access bar. The AutoTDP controls edit the FOREGROUND game's
 * [PerAppConfig] (the service creates one if none exists) so a change actually takes effect for a game with
 * a per-app binding — fixing the bug where a per-app fps target overrode the global default the bar wrote.
 * Fan/RGB/OSD stay global (no per-app equivalent), so those actions are NOT per-app. Pure — the service does
 * the get-or-create + save.
 */
object QuickAccessPerApp {

    /** True for the actions that target the foreground game's per-app profile (the AutoTDP controls + the
     *  tier/Stock mode switches). */
    fun isPerAppAction(action: QuickAccessAction): Boolean = action is QuickAccessAction.ToggleAutoTdp ||
        action is QuickAccessAction.SetTier ||
        action is QuickAccessAction.SetStockMode ||
        action is QuickAccessAction.SetFpsTarget ||
        action is QuickAccessAction.SetBias ||
        action is QuickAccessAction.SetAggressivePark

    /** Apply a per-app AutoTDP [action] to the game's config (creating one for [packageName] if null).
     *  [globalDefault] = the global AutoTDP-on default, needed so the toggle flips the EFFECTIVE state. */
    fun applyPerAppAction(
        existing: PerAppConfig?,
        packageName: String,
        action: QuickAccessAction,
        globalDefault: Boolean = false,
    ): PerAppConfig {
        val base = existing ?: PerAppConfig(packageName = packageName)
        // A value edit binds the app to AutoTDP so the value takes effect ("enable it per app") — BUT only when
        // the app has no binding or is already AutoTDP. It must NOT silently convert (and discard) a user's
        // tier/Custom/profile binding; only the explicit toggle does that.
        val bindAuto = if (base.profileBinding == null || base.profileBinding == PerAppConfig.AUTO_BINDING) {
            PerAppConfig.AUTO_BINDING
        } else {
            base.profileBinding
        }
        return when (action) {
            is QuickAccessAction.ToggleAutoTdp -> {
                // Flip the EFFECTIVE state: turning OFF writes an explicit AUTO_OFF (so it sticks even when the
                // global default is ON — the bug #1 gap), turning ON writes AUTO_BINDING. The explicit toggle
                // is the only action allowed to convert a tier/Custom binding (value edits preserve it via
                // bindAuto above).
                val currentlyOn = effectiveAutoTdpOn(base, globalDefault)
                base.copy(profileBinding = if (currentlyOn) PerAppConfig.AUTO_OFF_BINDING else PerAppConfig.AUTO_BINDING)
            }
            is QuickAccessAction.SetTier ->
                // An explicit mode switch — like the AutoTDP toggle, it's allowed to convert any binding (the
                // user deliberately chose this tier). The AutoTDP fields (fps/bias/park) are preserved untouched
                // so switching back to AutoTDP later restores them — no data loss.
                base.copy(profileBinding = PerAppConfig.tierBinding(action.tier))
            is QuickAccessAction.SetStockMode ->
                // "Stock — don't tune": the explicit hands-off pick. Same conversion rights as SetTier;
                // AUTO_OFF sticks even when the global default is ON, and the AutoTDP fields are preserved.
                base.copy(profileBinding = PerAppConfig.AUTO_OFF_BINDING)
            is QuickAccessAction.SetFpsTarget -> base.copy(fpsTarget = action.fps, profileBinding = bindAuto)
            is QuickAccessAction.SetBias -> base.copy(bias = action.bias, profileBinding = bindAuto)
            is QuickAccessAction.SetAggressivePark -> base.copy(aggressivePark = action.enabled, profileBinding = bindAuto)
            else -> base
        }
    }

    /** Whether AutoTDP is effectively on for the game: an explicit per-app binding wins, else the global. */
    fun effectiveAutoTdpOn(config: PerAppConfig?, globalDefault: Boolean): Boolean =
        when (config?.profileBinding) {
            PerAppConfig.AUTO_BINDING -> true
            PerAppConfig.AUTO_OFF_BINDING -> false // explicit per-app off overrides the global default
            null -> globalDefault                  // inherit the global
            else -> false                          // a tier/Custom binding ⇒ AutoTDP not active for this app
        }

}
