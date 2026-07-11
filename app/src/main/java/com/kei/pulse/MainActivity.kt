package com.kei.pulse

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kei.pulse.appwatch.ForegroundAppMonitorService
import com.kei.pulse.overlay.PerformanceOverlay
import com.kei.pulse.sleep.SleepProfileMonitorService
import com.kei.pulse.tile.QuickSettingsTileAddResult
import com.kei.pulse.tile.QuickSettingsTilePrompt
import com.kei.pulse.tile.QuickSettingsTileRefresher
import com.kei.pulse.ui.FanCurveEditorBindings
import com.kei.pulse.ui.MainTunerScreen
import com.kei.pulse.ui.PerAppScreen
import com.kei.pulse.ui.SettingsScreen
import com.kei.pulse.ui.TunerViewModel
import com.kei.pulse.ui.theme.LocalThermalHeat
import com.kei.pulse.ui.theme.PulseTheme
import com.kei.pulse.ui.theme.heatForTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
            perAppConfigStorage = container.perAppConfigStorage,
        )
    }
    private val exportProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(::exportProfilesToUri)
    }
    private val importProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProfilesFromUri)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.sleepProfileEnabled) {
                SleepProfileMonitorService.start(this@MainActivity)
            }
        }
    }
    private val perAppNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Enable regardless of the grant — the watcher works without notifications; the user
        // just won't see switch notices if they declined.
        viewModel.setPerAppEnabled(true) {
            ForegroundAppMonitorService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestQuickSettingsTileOnFirstRun()
        maybePromptBatteryExemption()

        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            PulseTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    val activeTier = viewModel.activeTier.collectAsStateWithLifecycle().value
                    val fanMode = viewModel.fanMode.collectAsStateWithLifecycle().value
                    val customFanSupported = viewModel.customFanSupported.collectAsStateWithLifecycle().value
                    val fanCalibrating = viewModel.fanCalibrating.collectAsStateWithLifecycle().value
                    val nativeDisplay = viewModel.nativeDisplay.collectAsStateWithLifecycle().value
                    val resolutionScale = viewModel.resolutionScale.collectAsStateWithLifecycle().value
                    val governor = viewModel.governor.collectAsStateWithLifecycle().value
                    val refreshRate = viewModel.refreshRate.collectAsStateWithLifecycle().value
                    val gpuFloorPercent = viewModel.gpuFloorPercent.collectAsStateWithLifecycle().value
                    val cpuFloorPercent = viewModel.cpuFloorPercent.collectAsStateWithLifecycle().value
                    val powerTargetEnabled = viewModel.powerTargetEnabled.collectAsStateWithLifecycle().value
                    val powerTargetPercent = viewModel.powerTargetPercent.collectAsStateWithLifecycle().value
                    val powerTargetCpuOnly = viewModel.powerTargetCpuOnly.collectAsStateWithLifecycle().value
                    val gpuLocked = viewModel.gpuLocked.collectAsStateWithLifecycle().value
                    val primeCoreBoostLimited = viewModel.primeCoreBoostLimited.collectAsStateWithLifecycle().value
                    val autoTdpEnabled = viewModel.autoTdpDefault.collectAsStateWithLifecycle().value
                    val autoTdpFpsTarget = viewModel.autoTdpFpsTarget.collectAsStateWithLifecycle().value
                    val autoTdpAggressivePark = viewModel.autoTdpAggressivePark.collectAsStateWithLifecycle().value
                    val autoTdpBias = viewModel.autoTdpBias.collectAsStateWithLifecycle().value
                    val estimatedPeakW = viewModel.estimatedPeakW.collectAsStateWithLifecycle().value
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var showPerApps by rememberSaveable { mutableStateOf(false) }
                    val perAppEnabled = viewModel.perAppEnabled.collectAsStateWithLifecycle().value
                    val perAppConfigs = viewModel.perAppConfigs.collectAsStateWithLifecycle().value
                    val perAppSwitchNotices = viewModel.perAppSwitchNotices.collectAsStateWithLifecycle().value
                    val perAppSwitchNoticeDetails =
                        viewModel.perAppSwitchNoticeDetails.collectAsStateWithLifecycle().value

                    // Existing per-app bindings must engage on launch even if the master toggle was never
                    // flipped — the watcher self-stops if nothing needs it. (Per-app comes first.)
                    LaunchedEffect(perAppConfigs.isNotEmpty()) {
                        if (perAppConfigs.isNotEmpty() &&
                            ForegroundAppMonitorService.hasUsageAccess(this@MainActivity)
                        ) {
                            ForegroundAppMonitorService.start(this@MainActivity)
                        }
                    }

                    // System / controller back navigates out of sub-screens instead of exiting.
                    BackHandler(enabled = showPerApps || showSettings) {
                        if (showPerApps) showPerApps = false else showSettings = false
                    }

                    CompositionLocalProvider(LocalThermalHeat provides heatForTier(activeTier)) {
                    if (showPerApps) {
                        PerAppScreen(
                            configs = perAppConfigs,
                            learnedPackages = viewModel.autoTdpLearnedPackages.collectAsStateWithLifecycle().value,
                            profiles = state.displayProfiles,
                            batteryCapacityWh = viewModel.perAppBatteryWh.collectAsStateWithLifecycle().value,
                            fpsOptions = viewModel.autoTdpFpsOptions,
                            defaultFpsTarget = autoTdpFpsTarget,
                            defaultAggressivePark = autoTdpAggressivePark,
                            onSaveConfig = ::onSavePerAppConfig,
                            onRemoveConfig = viewModel::removePerAppConfig,
                            onBack = { showPerApps = false },
                        )
                    } else if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onBack = { showSettings = false },
                            onPulseEnabledChange = ::onPulseMasterToggle,
                            onRgbModeChange = ::onRgbModeSelected,
                            onRgbManualTargetChange = viewModel::setRgbManualTarget,
                            onRgbManualStickChange = ::onRgbManualStickChanged,
                            onColorSourceChange = viewModel::setColorSource,
                            onThemeChange = viewModel::setThemeId,
                            onAccentColorChange = viewModel::setAccentColor,
                            onTileTapBehaviorChange = { behavior ->
                                viewModel.setTileTapBehavior(behavior) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onApplyLastProfileOnBootChange = viewModel::setApplyLastProfileOnBoot,
                            sleepProfileOptions = state.displayProfiles,
                            onSleepProfileEnabledChange = { enabled ->
                                val profileId = settings.sleepProfileId
                                    ?: state.displayProfiles.firstOrNull()?.id
                                viewModel.configureSleepProfile(enabled, profileId) {
                                    if (enabled) {
                                        startSleepProfileMonitor()
                                    } else {
                                        SleepProfileMonitorService.stop(this@MainActivity)
                                    }
                                }
                            },
                            onSleepProfileChange = viewModel::setSleepProfile,
                            onResetProfiles = viewModel::resetProfilesToDefault,
                            onExportProfiles = {
                                exportProfilesLauncher.launch("pulse-profiles.json")
                            },
                            onImportProfiles = {
                                importProfilesLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            onRequestAddQuickSettingsTile = {
                                requestQuickSettingsTile(showResultToast = true)
                            },
                            canRequestAddQuickSettingsTile = QuickSettingsTilePrompt.isSupported,
                            isQuickSettingsTileAdded = settings.isQuickSettingsTileAdded,
                            perAppEnabled = perAppEnabled,
                            perAppConfiguredCount = perAppConfigs.size,
                            onPerAppEnabledChange = ::setPerAppProfilesEnabled,
                            onOpenPerApps = { showPerApps = true },
                            perAppSwitchNotices = perAppSwitchNotices,
                            onPerAppSwitchNoticesChange = viewModel::setPerAppSwitchNotices,
                            perAppSwitchNoticeDetails = perAppSwitchNoticeDetails,
                            onPerAppSwitchNoticeDetailsChange = viewModel::setPerAppSwitchNoticeDetails,
                            overlayEnabled = settings.overlayEnabled,
                            overlayPreset = settings.overlayPreset,
                            overlayElements = settings.overlayElements,
                            overlayOpacity = settings.overlayOpacity,
                            onOverlayEnabledChange = ::setOverlayEnabled,
                            onOverlayPresetChange = viewModel::setOverlayPreset,
                            onOverlayElementToggle = viewModel::setOverlayElement,
                            onOverlayOpacityChange = viewModel::setOverlayOpacity,
                            onQuickAccessChange = ::setQuickAccessEnabled,
                            onQuickAccessShowHandleChange = viewModel::setQuickAccessShowHandle,
                            onSetQuickAccessCombo = viewModel::captureQuickAccessCombo,
                            onClearQuickAccessCombo = viewModel::clearQuickAccessCombo,
                            capturingCombo = viewModel.capturingCombo.collectAsStateWithLifecycle().value,
                        )
                    } else {
                        MainTunerScreen(
                            state = state,
                            sleepProfileId = settings.sleepProfileId.takeIf { settings.sleepProfileEnabled },
                            onApplyProfile = viewModel::applyProfile,
                            onApplyCurrent = { tunerState ->
                                viewModel.applyCurrent(tunerState) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onCreateProfile = viewModel::createUserProfile,
                            onUpdateProfile = viewModel::updateProfile,
                            onDeleteProfile = viewModel::deleteProfile,
                            onMoveProfile = viewModel::moveProfile,
                            onOpenSettings = { showSettings = true },
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            estimatedPeakW = estimatedPeakW,
                            onStatusMessageShown = viewModel::consumeStatusMessage,
                            onErrorMessageShown = viewModel::consumeErrorMessage,
                            activeTier = activeTier,
                            fanMode = fanMode,
                            fanCurveEditor = if (customFanSupported) {
                                FanCurveEditorBindings(
                                    curve = settings.fanCurve,
                                    responseStep = settings.fanResponseStep,
                                    bias = settings.fanBias,
                                    smartEnabled = settings.fanSmartEnabled,
                                    targetTempC = settings.fanTargetTempC,
                                    calibrating = fanCalibrating,
                                    onCurveChange = ::onFanCurveChanged,
                                    onResponseStepChange = ::onFanResponseStepChanged,
                                    onBiasChange = ::onFanBiasChanged,
                                    onSmartToggle = ::onFanSmartToggled,
                                    onTargetTempChange = ::onFanTargetTempChanged,
                                    onAutocalibrate = ::onAutocalibrateFan,
                                    readTelemetry = viewModel::readTelemetry,
                                    readFanDutyPercent = viewModel::readFanDutyPercent,
                                )
                            } else {
                                null
                            },
                            nativeDisplay = nativeDisplay,
                            resolutionScale = resolutionScale,
                            onSelectTier = { tier ->
                                viewModel.applyTier(tier) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onSelectFanMode = ::onFanModeSelected,
                            onSelectResolution = viewModel::setResolutionScale,
                            onRefreshSystemControls = viewModel::refreshSystemControls,
                            onSetPolicyValue = viewModel::setPolicyValue,
                            governor = governor,
                            refreshRate = refreshRate,
                            gpuFloorPercent = gpuFloorPercent,
                            onSelectGovernor = viewModel::setGovernor,
                            onSelectRefreshRate = viewModel::setRefreshRate,
                            onSelectGpuFloor = viewModel::setGpuFloorPercent,
                            cpuFloorPercent = cpuFloorPercent,
                            onSelectCpuFloor = viewModel::setCpuFloorPercent,
                            readTelemetry = viewModel::readTelemetry,
                            powerTargetEnabled = powerTargetEnabled,
                            powerTargetPercent = powerTargetPercent,
                            onPowerTargetEnabledChange = viewModel::setPowerTargetEnabled,
                            onPowerTargetPercentChange = viewModel::setPowerTargetPercent,
                            powerTargetCpuOnly = powerTargetCpuOnly,
                            gpuLocked = gpuLocked,
                            onPowerTargetCpuOnlyChange = viewModel::setPowerTargetCpuOnly,
                            onToggleGpuLock = viewModel::setGpuLocked,
                            primeCoreBoostLimited = primeCoreBoostLimited,
                            onTogglePrimeCoreBoostLimit = viewModel::setPrimeCoreBoostLimited,
                            autoTdpEnabled = autoTdpEnabled,
                            onAutoTdpEnabledChange = ::setAutoTdpDefaultEnabled,
                            autoTdpFpsTarget = autoTdpFpsTarget,
                            autoTdpFpsOptions = viewModel.autoTdpFpsOptions,
                            autoTdpShowWattCaps = viewModel.autoTdpShowWattCaps,
                            onAutoTdpFpsTargetChange = viewModel::setAutoTdpFpsTarget,
                            autoTdpAggressivePark = autoTdpAggressivePark,
                            onAutoTdpAggressiveParkChange = viewModel::setAutoTdpAggressivePark,
                            autoTdpBias = autoTdpBias,
                            onAutoTdpBiasChange = viewModel::setAutoTdpBias,
                        )
                    }
                    }
                }
            }
        }
    }

    // Set when the user flips per-app profiles on without Usage access: we bounce them to the
    // system grant screen and finish enabling automatically when they come back with it granted.
    private var pendingPerAppEnable = false

    // Set when the user flips the overlay on without the "display over other apps" permission:
    // we bounce them to the system grant screen and finish enabling when they return with it.
    private var pendingOverlayEnable = false
    private var pendingQuickAccessEnable = false

    // Set when AutoTDP (global default) is flipped on without Usage access — same bounce/return flow.
    private var pendingAutoTdpEnable = false

    override fun onResume() {
        super.onResume()
        // PULSE's UI is on screen — the OSD must never draw over it (a focused text field makes the foreground
        // probe report the keyboard's package, which used to leak the OSD over our own settings).
        ForegroundAppMonitorService.uiInForeground = true
        if (pendingPerAppEnable) {
            pendingPerAppEnable = false
            if (ForegroundAppMonitorService.hasUsageAccess(this)) {
                Toast.makeText(applicationContext, "Per-app profiles enabled", Toast.LENGTH_SHORT).show()
                enablePerAppProfiles()
            }
        }
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (PerformanceOverlay.hasPermission(this)) {
                if (ForegroundAppMonitorService.hasUsageAccess(this)) {
                    enableOverlay()
                    Toast.makeText(applicationContext, "Overlay enabled", Toast.LENGTH_SHORT).show()
                } else {
                    // Overlay permission is in; the OSD also needs Usage access to know the foreground app.
                    pendingOverlayEnable = true
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(
                        applicationContext,
                        "The overlay needs Usage access to know which game is on screen. Allow it for PULSE, then come back.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        if (pendingQuickAccessEnable) {
            pendingQuickAccessEnable = false
            if (PerformanceOverlay.hasPermission(this)) {
                if (ForegroundAppMonitorService.hasUsageAccess(this)) {
                    enableQuickAccess()
                    Toast.makeText(applicationContext, "Quick Access bar enabled", Toast.LENGTH_SHORT).show()
                } else {
                    pendingQuickAccessEnable = true
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(
                        applicationContext,
                        "The Quick Access bar needs Usage access to know which game is on screen. Allow it for PULSE, then come back.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        if (pendingAutoTdpEnable) {
            pendingAutoTdpEnable = false
            if (ForegroundAppMonitorService.hasUsageAccess(this)) {
                Toast.makeText(applicationContext, "AutoTDP enabled", Toast.LENGTH_SHORT).show()
                enableAutoTdpDefault()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // PULSE left the screen — the OSD may resume over real games again.
        ForegroundAppMonitorService.uiInForeground = false
    }

    private fun setAutoTdpDefaultEnabled(enabled: Boolean) {
        if (!enabled) {
            // The watcher self-stops on its next poll if nothing else (per-app profiles) needs it.
            viewModel.setAutoTdpDefault(false)
            return
        }
        if (!ForegroundAppMonitorService.hasUsageAccess(this)) {
            pendingAutoTdpEnable = true
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                applicationContext,
                "AutoTDP needs Usage access to see which game is running. Allow it for PULSE, then come back.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        enableAutoTdpDefault()
    }

    private fun enableAutoTdpDefault() {
        viewModel.setAutoTdpDefault(true) {
            ForegroundAppMonitorService.start(this)
        }
    }

    private fun setOverlayEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setOverlayEnabled(false)
            return
        }
        if (!PerformanceOverlay.hasPermission(this)) {
            // "Display over other apps" is a special permission granted only from system settings.
            pendingOverlayEnable = true
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            Toast.makeText(
                applicationContext,
                "Allow \"Display over other apps\" for PULSE, then come back",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!ForegroundAppMonitorService.hasUsageAccess(this)) {
            // The OSD watcher needs Usage access to know which app is in front (and to avoid the
            // launcher). Bounce to the grant screen and finish in onResume.
            pendingOverlayEnable = true
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                applicationContext,
                "The overlay needs Usage access to know which game is on screen. Allow it for PULSE, then come back.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        enableOverlay()
    }

    /**
     * Master switch. ON resumes management; OFF makes the service hand everything back to manufacturer stock
     * and stop itself. Starting the service on OFF is intentional — it's how the revert gets a root context
     * (the service runs once, reverts, then stopSelf via the pulseEnabled guard in pollLoop).
     */
    private fun onPulseMasterToggle(enabled: Boolean) {
        viewModel.setPulseEnabled(enabled) {
            ForegroundAppMonitorService.start(this)
            if (enabled) maybePromptBatteryExemption()
        }
    }

    /** RGB joystick LED. Persist the mode and (re)start the watcher so the LED loop engages immediately. */
    private fun onRgbModeSelected(mode: com.kei.pulse.model.RgbMode) {
        viewModel.setRgbMode(mode) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Fan card. Apply + remember the mode, then (re)start the watcher so it re-asserts vs the system Fan tile. */
    private fun onFanModeSelected(mode: Int) {
        viewModel.setFanMode(mode) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Custom fan curve edited. Persist it and (re)start the watcher so the running controller picks it up now. */
    private fun onFanCurveChanged(curve: com.kei.pulse.model.FanCurve) {
        viewModel.setFanCurve(curve) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Custom fan response (slew rate) changed. Persist + (re)start the watcher to engage it immediately. */
    private fun onFanResponseStepChanged(step: Int) {
        viewModel.setFanResponseStep(step) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Cooler/Quieter bias changed. Persist + (re)start the watcher so the offset applies immediately. */
    private fun onFanBiasChanged(bias: Int) {
        viewModel.setFanBias(bias) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Smart (closed-loop) fan toggled. Persist + (re)start the watcher to switch modes immediately. */
    private fun onFanSmartToggled(enabled: Boolean) {
        viewModel.setFanSmartEnabled(enabled) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Smart-mode target temp changed. Persist + (re)start the watcher so the controller retargets now. */
    private fun onFanTargetTempChanged(tempC: Int) {
        viewModel.setFanTargetTemp(tempC) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Autocalibrate the Custom fan: sweep + learn the curve, then (re)start the watcher so it retakes the fan. */
    private fun onAutocalibrateFan() {
        viewModel.autocalibrateFan {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Manual RGB: persist a stick's color + brightness and (re)start the watcher so it applies immediately. */
    private fun onRgbManualStickChanged(stick: com.kei.pulse.model.RgbStick, color: Int, brightness: Float) {
        viewModel.setRgbManualStick(stick, color, brightness) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /** Persist the overlay flag and make sure the watcher service is running to drive it. */
    private fun enableOverlay() {
        viewModel.setOverlayEnabled(true)
        ForegroundAppMonitorService.start(this)
    }

    /**
     * Quick Access bar (experimental) — needs the same two permissions as the OSD (it draws an overlay and
     * reads the foreground app), and must START the watcher when enabled, mirroring [setOverlayEnabled].
     * Without this, flipping the toggle only persisted the flag and the bar never appeared.
     */
    private fun setQuickAccessEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setQuickAccess(false)
            return
        }
        if (!PerformanceOverlay.hasPermission(this)) {
            pendingQuickAccessEnable = true
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            Toast.makeText(
                applicationContext,
                "Allow \"Display over other apps\" for PULSE, then come back",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!ForegroundAppMonitorService.hasUsageAccess(this)) {
            pendingQuickAccessEnable = true
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                applicationContext,
                "The Quick Access bar needs Usage access to know which game is on screen. Allow it for PULSE, then come back.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        enableQuickAccess()
    }

    private fun enableQuickAccess() {
        viewModel.setQuickAccess(true)
        ForegroundAppMonitorService.start(this)
    }

    private fun setPerAppProfilesEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setPerAppEnabled(false) {
                ForegroundAppMonitorService.stop(this)
            }
            return
        }
        if (!ForegroundAppMonitorService.hasUsageAccess(this)) {
            // The special permission can only be granted from system settings; send the user
            // there and finish enabling in onResume once it's granted.
            pendingPerAppEnable = true
            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(
                applicationContext,
                "Per-app profiles need Usage access to detect the foreground app. Allow it for PULSE, then come back.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        enablePerAppProfiles()
    }

    private fun enablePerAppProfiles() {
        // The watcher posts switch notices, so ask for the notification permission too (13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            perAppNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        viewModel.setPerAppEnabled(true) {
            ForegroundAppMonitorService.start(this)
        }
    }

    /**
     * Persist a per-app binding AND make sure the watcher runs so it actually engages — a saved binding
     * must take priority over the global mode (fixes "Custom still wins" when a per-app AutoTDP binding
     * was set but the watcher wasn't running). Prompts for Usage access if it's missing.
     */
    private fun onSavePerAppConfig(config: com.kei.pulse.model.PerAppConfig) {
        viewModel.savePerAppConfig(config) {
            if (ForegroundAppMonitorService.hasUsageAccess(this)) {
                ForegroundAppMonitorService.start(this)
            } else {
                pendingPerAppEnable = true
                startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(
                    applicationContext,
                    "Per-app profiles need Usage access to detect the foreground app — allow it for PULSE.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun startSleepProfileMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SleepProfileMonitorService.start(this)
    }

    private fun maybeRequestQuickSettingsTileOnFirstRun() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.hasPromptedQuickSettingsTile) return@launch

            container.settingsStorage.persistQuickSettingsTilePromptShown()
            if (QuickSettingsTilePrompt.isSupported) {
                requestQuickSettingsTile(showResultToast = false)
            }
        }
    }

    /**
     * One-time prompt: if PULSE is meant to run (master switch on) but isn't exempt from battery
     * optimization, show the system "allow background running" dialog so its persistent watcher (global
     * Fan/RGB, AutoTDP, OSD) isn't throttled in Doze or reclaimed in the background. Asked once (persisted)
     * so it never nags. NB: nothing can survive an explicit force-stop / recents-swipe — this only helps
     * against Doze + background memory reclaim.
     */
    @android.annotation.SuppressLint("BatteryLife") // deliberate: a user-driven persistent tuner watcher
    private fun maybePromptBatteryExemption() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            val power = getSystemService(android.os.PowerManager::class.java)
            val exempt = power?.isIgnoringBatteryOptimizations(packageName) ?: true
            if (!com.kei.pulse.appwatch.BatteryExemptionPrompt.shouldPrompt(
                    masterEnabled = settings.pulseEnabled,
                    isExempt = exempt,
                    alreadyAsked = settings.batteryOptPromptShown,
                )
            ) {
                return@launch
            }
            container.settingsStorage.persistBatteryOptPromptShown()
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
    }

    private fun requestQuickSettingsTile(showResultToast: Boolean) {
        QuickSettingsTilePrompt.request(this) { result ->
            if (result == QuickSettingsTileAddResult.ADDED || result == QuickSettingsTileAddResult.ALREADY_ADDED) {
                lifecycleScope.launch {
                    container.settingsStorage.persistQuickSettingsTileAdded(true)
                }
            }
            if (!showResultToast) return@request
            Toast.makeText(
                applicationContext,
                result.toToastMessage(),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun exportProfilesToUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = viewModel.exportProfilesJson()
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                } ?: error("Unable to open export file")
            }.onSuccess {
                Toast.makeText(applicationContext, "Exported profiles", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to export profiles",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun importProfilesFromUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open import file")
                viewModel.importProfilesJson(json)
            }.onSuccess { importedCount ->
                Toast.makeText(
                    applicationContext,
                    "Imported $importedCount profiles",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to import profiles",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun QuickSettingsTileAddResult.toToastMessage(): String {
        return when (this) {
            QuickSettingsTileAddResult.ADDED -> "Quick Settings tile added"
            QuickSettingsTileAddResult.ALREADY_ADDED -> "Quick Settings tile is already added"
            QuickSettingsTileAddResult.NOT_ADDED -> "Quick Settings tile was not added"
            QuickSettingsTileAddResult.UNAVAILABLE -> "Quick Settings tile prompt is unavailable on this device"
            QuickSettingsTileAddResult.ERROR -> "Failed to request Quick Settings tile"
        }
    }
}
