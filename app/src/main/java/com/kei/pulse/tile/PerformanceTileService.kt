package com.kei.pulse.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.kei.pulse.AppContainer
import com.kei.pulse.R
import com.kei.pulse.TileControlActivity
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.TileInteractionBehavior
import com.kei.pulse.model.TunerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class PerformanceTileService : TileService() {

    // All tile work runs off the main thread: PServer reads/applies block, and runBlocking on the
    // main thread is exactly what froze the UI under load. qsTile mutations switch back to Main.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "PerformanceTile"
        private var activeService = WeakReference<PerformanceTileService>(null)

        fun refreshActiveTile(): Boolean {
            val service = activeService.get() ?: return false
            service.refreshTileState()
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
    }

    override fun onDestroy() {
        if (activeService.get() === this) {
            activeService.clear()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        persistTileAddedState(isAdded = true)
        refreshTileState()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        persistTileAddedState(isAdded = false)
    }

    override fun onStartListening() {
        super.onStartListening()
        persistTileAddedState(isAdded = true)
        refreshTileState()
    }

    private fun refreshTileState() {
        serviceScope.launch {
            runCatching {
                val container = AppContainer(applicationContext)
                val state = container.repository.observeState().first()
                val settings = container.settingsStorage.settings.first()
                val presentation = buildTilePresentation(state, settings)
                val visualState = buildTileVisualState(state)
                withContext(Dispatchers.Main) {
                    qsTile?.apply {
                        label = presentation.label
                        subtitle = presentation.subtitle
                        this.state = visualState
                        updateTile()
                    }
                }
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to refresh tile state", throwable)
                withContext(Dispatchers.Main) {
                    qsTile?.apply {
                        label = getString(R.string.tile_title)
                        subtitle = getString(R.string.tile_state_unavailable)
                        state = Tile.STATE_INACTIVE
                        updateTile()
                    }
                }
            }
        }
    }

    private data class TilePresentation(
        val label: String,
        val subtitle: String,
    )

    private fun buildTilePresentation(state: TunerState, settings: AppSettings): TilePresentation {
        if (!state.isPServerAvailable) {
            return TilePresentation(
                label = getString(R.string.tile_title),
                subtitle = getString(R.string.tile_state_unavailable),
            )
        }
        // Tiers apply as MANUAL_PROFILE_ID, so "Manual" really means a tier is active — show it.
        val manual = getString(R.string.tile_state_manual)
        val profileName = effectiveTileProfileName(state)
        val displayName = if (profileName == null || profileName == manual) {
            settings.activeTierLabel.ifBlank { manual }
        } else {
            profileName
        }
        return if (settings.tileTapBehavior == TileInteractionBehavior.CYCLE_PROFILES) {
            // Cycle mode taps the tile to change profile, so the active name is the headline.
            TilePresentation(label = displayName, subtitle = getString(R.string.tile_title))
        } else {
            TilePresentation(label = getString(R.string.tile_title), subtitle = displayName)
        }
    }

    private fun buildTileVisualState(state: TunerState): Int {
        if (!state.isPServerAvailable) return Tile.STATE_INACTIVE
        val activeProfileId = effectiveTileProfileId(state)
        val activeName = effectiveTileProfileName(state)
        val stockIsActive = activeProfileId == ProfileStateResolver.STOCK_PROFILE_ID || activeName == "Stock"
        return if (stockIsActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }

    private fun effectiveTileProfileId(state: TunerState): String? {
        return state.lastAppliedDisplayProfileId
            ?.takeIf { id ->
                id == ProfileStateResolver.MANUAL_PROFILE_ID ||
                    id == ProfileStateResolver.STOCK_PROFILE_ID ||
                    state.displayProfiles.any { profile -> profile.id == id }
            }
            ?: state.activeDisplayProfileId
    }

    private fun effectiveTileProfileName(state: TunerState): String? {
        val effectiveId = effectiveTileProfileId(state)
        if (effectiveId == ProfileStateResolver.MANUAL_PROFILE_ID) {
            return getString(R.string.tile_state_manual)
        }
        return state.displayProfiles.firstOrNull { it.id == effectiveId }?.name
            ?: state.activeDisplayProfileName
    }

    private fun persistTileAddedState(isAdded: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            AppContainer(applicationContext).settingsStorage.persistQuickSettingsTileAdded(isAdded)
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun(::handleTap)
        } else {
            handleTap()
        }
    }

    private fun handleTap() {
        serviceScope.launch {
            runCatching {
                val container = AppContainer(applicationContext)
                val settings = container.settingsStorage.settings.first()
                when (settings.tileTapBehavior) {
                    TileInteractionBehavior.SHOW_DIALOG ->
                        withContext(Dispatchers.Main) { launchDialogAndCollapse() }
                    TileInteractionBehavior.OPEN_APP ->
                        withContext(Dispatchers.Main) { launchAppAndCollapse() }
                    TileInteractionBehavior.CYCLE_PROFILES -> cycleTierAndUpdate(container)
                }
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to handle tile tap", throwable)
                showToast(throwable.message ?: "Failed to handle tile tap")
            }
        }
    }

    private suspend fun cycleTierAndUpdate(container: AppContainer) {
        val tierCycle = listOf(PowerTier.MAX, PowerTier.BALANCED, PowerTier.POWER_SAVING, PowerTier.CUSTOM)
        val currentLabel = container.settingsStorage.settings.first().activeTierLabel
        val currentIndex = tierCycle.indexOfFirst { it.label == currentLabel }
        val nextTier = if (currentIndex == -1) tierCycle.first()
                       else tierCycle[(currentIndex + 1) % tierCycle.size]
        if (nextTier == PowerTier.CUSTOM) {
            // Restore the user's saved Custom setup if they have one; otherwise
            // just switch the label into Custom (manual) mode.
            container.settingsStorage.persistActiveTierLabel(PowerTier.CUSTOM.label)
            val restored = container.repository.restoreCustomValues()
            updateTileToActive(container)
            showToast(if (restored.isSuccess) "Applied Custom" else "Custom — adjust in app")
        } else {
            container.repository.applyTier(nextTier)
                .onSuccess {
                    container.settingsStorage.persistActiveTierLabel(nextTier.label)
                    updateTileToActive(container)
                    showToast("Applied ${nextTier.label}")
                }
                .onFailure { throwable ->
                    showToast(throwable.message ?: "Failed to cycle tier")
                }
        }
    }

    private suspend fun updateTileToActive(container: AppContainer) {
        val refreshedState = container.repository.observeState().first()
        val refreshedSettings = container.settingsStorage.settings.first()
        val presentation = buildTilePresentation(refreshedState, refreshedSettings)
        withContext(Dispatchers.Main) {
            qsTile?.apply {
                label = presentation.label
                subtitle = presentation.subtitle
                state = Tile.STATE_ACTIVE
                updateTile()
            }
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchDialogAndCollapse() {
        val intent = TileControlActivity.createDialogIntent(applicationContext)
        launchIntentAndCollapse(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchAppAndCollapse() {
        val intent = Intent(applicationContext, com.kei.pulse.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launchIntentAndCollapse(intent)
    }

    @Suppress("DEPRECATION")
    // Verified false positive: line below is version-gated to API < 34 (Thor/RP6 = Android 13) where the
    // Intent overload is the ONLY one available; API 34+ (Odin) uses the PendingIntent overload above.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchIntentAndCollapse(intent: Intent) {
        // API 34+ (Odin 3 = Android 15): the Intent overload throws
        // UnsupportedOperationException — a PendingIntent is mandatory.
        // API < 34 (Thor / RP6 = Android 13): the Intent overload is the only one available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
