package com.kei.pulse.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kei.pulse.AppContainer
import com.kei.pulse.appwatch.ForegroundAppMonitorService
import com.kei.pulse.appwatch.WatcherActivation
import com.kei.pulse.sleep.SleepProfileMonitorService
import com.kei.pulse.tile.QuickSettingsTileRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> restartManagedState(context, applyBootValues = true)
            // A package UPDATE kills the process WITHOUT onDestroy — the watcher (and a Custom fan left in
            // manual passthrough, fan_mode=6) stayed down/STRANDED until the app was manually reopened
            // (observed live on the Thor: install -r mid-Custom-fan → duty pinned, nothing driving it). Run
            // the same restart rule as boot; only a real boot re-applies the persisted boot values.
            Intent.ACTION_MY_PACKAGE_REPLACED -> restartManagedState(context, applyBootValues = false)
        }
    }

    private fun restartManagedState(context: Context, applyBootValues: Boolean) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(context)
                val settings = container.settingsStorage.settings.first()
                QuickSettingsTileRefresher.requestUpdate(context)
                if (settings.sleepProfileEnabled) {
                    SleepProfileMonitorService.start(context)
                }
                // Restart the watcher using the SAME rule as the pollLoop keep-alive ([WatcherActivation]):
                // global Fan/RGB re-engage with no permission; per-app/AutoTDP/OSD need Usage Access. (The old
                // code required Usage Access for ALL of them, so a global Fan/RGB-only setup silently died
                // across a reboot until the app was reopened.)
                val shouldStart = WatcherActivation.shouldRun(
                    perAppEnabled = container.perAppConfigStorage.enabled.first(),
                    hasPerAppConfigs = container.perAppConfigStorage.configs.first().isNotEmpty(),
                    settings = settings,
                    hasUsageAccess = ForegroundAppMonitorService.hasUsageAccess(context),
                )
                if (shouldStart) {
                    ForegroundAppMonitorService.start(context)
                }
                if (!applyBootValues || !settings.applyLastProfileOnBoot) {
                    return@launch
                }
                container.repository.applyPersistedLastValuesOnBoot()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
