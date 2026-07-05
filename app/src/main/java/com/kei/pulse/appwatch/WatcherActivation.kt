package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.RgbMode

/**
 * Decides whether the foreground-watcher service should run.
 *
 * The watcher drives two kinds of feature:
 *  - **foreground-dependent** — per-app profiles, AutoTDP, and the OSD overlay all read the foreground
 *    app via UsageStats, so they need the "Usage access" special permission to do anything;
 *  - **foreground-free** — the global Fan mode and joystick RGB just re-assert global hardware state
 *    every tick and never look at the foreground, so they need no permission at all.
 *
 * Gating the *whole* service on Usage Access (the old behaviour) silently killed the global Fan/RGB on a
 * fresh install — and across a reboot — even though they don't need it. Centralising the decision here
 * keeps the service's keep-alive ([ForegroundAppMonitorService]) and the boot restart
 * ([com.kei.pulse.boot.BootCompletedReceiver]) in lock-step so they can never drift apart again.
 */
object WatcherActivation {

    /**
     * Whether the watcher should run, given which features are active and whether Usage Access is granted.
     *
     * Foreground-free features (global Fan / RGB) run with no permission. Foreground-dependent features
     * (per-app, AutoTDP, OSD) can't do anything without Usage Access, so on their own they do NOT keep the
     * service alive without it — there's nothing for it to do but spin.
     */
    fun shouldRun(
        perAppEnabled: Boolean,
        hasPerAppConfigs: Boolean,
        settings: AppSettings,
        hasUsageAccess: Boolean,
    ): Boolean {
        val needsForeground = perAppEnabled || hasPerAppConfigs ||
            settings.autoTdpDefaultEnabled || settings.overlayEnabled || settings.quickAccessEnabled
        val foregroundFree = settings.rgbMode != RgbMode.OFF || settings.managedFanMode != null
        return foregroundFree || (needsForeground && hasUsageAccess)
    }
}
