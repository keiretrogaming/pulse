package com.kei.pulse

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kei.pulse.appwatch.ForegroundAppMonitorService
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.TunerState
import com.kei.pulse.overlay.PerformanceOverlay
import com.kei.pulse.tile.QuickSettingsTileRefresher
import com.kei.pulse.ui.CompactTunerScreen
import com.kei.pulse.ui.TunerViewModel
import com.kei.pulse.ui.theme.PulseTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TileControlActivity : ComponentActivity() {

    companion object {
        private const val ACTION_OPEN_DIALOG = "com.kei.pulse.action.OPEN_TILE_DIALOG"
        private const val ACTION_TOGGLE_OVERLAY = "com.kei.pulse.action.TOGGLE_OVERLAY_FROM_TILE"
        private const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        fun createDialogIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_OPEN_DIALOG
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }

        /**
         * The Quick Settings tile can't start the watcher service itself — a [android.service.quicksettings.TileService]
         * is a background context, so [ForegroundAppMonitorService] fails its foreground-start on Android 14+. This
         * activity runs the toggle from a foreground context instead, then finishes without showing UI.
         */
        fun createToggleOverlayIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_TOGGLE_OVERLAY
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }
    }

    private val container by lazy { AppContainer(this) }
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        when (intent?.action) {
            ACTION_QS_TILE_PREFERENCES -> openFullApp()
            ACTION_TOGGLE_OVERLAY -> {
                // This launch is a headless trampoline from the tile, not the editor dialog: drop the
                // dialog theme's scrim so nothing flashes on screen before finish().
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                toggleOverlayAndFinish()
            }
            else -> setEditorContent()
        }
    }

    private fun toggleOverlayAndFinish() {
        lifecycleScope.launch {
            val store = container.settingsStorage
            val enable = !store.settings.first().overlayEnabled
            if (enable && !PerformanceOverlay.hasPermission(this@TileControlActivity)) {
                bounceForOverlaySetup(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    "Allow \"Display over other apps\" for PULSE, then tap the tile again.",
                )
                return@launch
            }
            if (enable && !ForegroundAppMonitorService.hasUsageAccess(this@TileControlActivity)) {
                bounceForOverlaySetup(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                    "The overlay needs Usage access to see which game is running. Allow it for PULSE, then tap the tile again.",
                )
                return@launch
            }
            store.persistOverlayEnabled(enable)
            if (enable) ForegroundAppMonitorService.start(this@TileControlActivity)
            QuickSettingsTileRefresher.requestUpdate(applicationContext)
            Toast.makeText(
                applicationContext,
                if (enable) "In-game overlay on" else "In-game overlay off",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private fun bounceForOverlaySetup(settingsIntent: Intent, message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        startActivity(settingsIntent)
        finish()
    }

    private fun setEditorContent() {
        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            PulseTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    CompactTunerScreen(
                        state = state,
                        onPolicyValueChange = viewModel::setPolicyValue,
                        onApplyProfile = viewModel::applyProfile,
                        onClearSelection = viewModel::clearSelection,
                        onApplyCurrent = { tunerState ->
                            applyCurrentFromDialog(tunerState)
                        },
                        onDismissRequest = ::dismissTileDialog,
                        onRefreshLiveValues = viewModel::refreshLiveState,
                        onOpenFullApp = {
                            openFullApp()
                        },
                    )
                }
            }
        }
    }

    private fun applyCurrentFromDialog(state: TunerState) {
        lifecycleScope.launch {
            val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
            val result = container.repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
            )
            result.onSuccess {
                container.repository.selectProfile(
                    appliedProfile?.id?.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID },
                )
                Toast.makeText(
                    applicationContext,
                    "Applied ${appliedProfile?.name ?: "Manual"}",
                    Toast.LENGTH_SHORT,
                ).show()
                dismissTileDialog()
                QuickSettingsTileRefresher.requestUpdate(applicationContext)
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to apply limits",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun dismissTileDialog() {
        finishAndRemoveTask()
    }
}
