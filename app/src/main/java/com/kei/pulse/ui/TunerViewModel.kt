package com.kei.pulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kei.pulse.data.DisplayController
import com.kei.pulse.data.GovernorController
import com.kei.pulse.data.GovernorOption
import com.kei.pulse.data.AutoTuneController
import com.kei.pulse.data.CpuFloorController
import com.kei.pulse.data.GpuFloorController
import com.kei.pulse.data.RefreshRateController
import com.kei.pulse.data.TelemetryReader
import com.kei.pulse.data.TelemetrySnapshot
import com.kei.pulse.data.FanController
import com.kei.pulse.data.FanCurveController
import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanCalibration
import com.kei.pulse.model.FanCalibrationSample
import kotlinx.coroutines.delay
import com.kei.pulse.data.PerAppConfigStorage
import com.kei.pulse.data.PerformanceRepository
import com.kei.pulse.data.PowerEstimator
import com.kei.pulse.data.SettingsStorage
import com.kei.pulse.model.PerAppConfig
import kotlinx.coroutines.flow.flowOf
import com.kei.pulse.model.AppColorSource
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.SideControlState
import com.kei.pulse.model.TierTransition
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.ProfileSource
import com.kei.pulse.model.TileInteractionBehavior
import com.kei.pulse.model.TunerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs

class TunerViewModel(
    private val repository: PerformanceRepository,
    private val settingsStorage: SettingsStorage,
    private val perAppConfigStorage: PerAppConfigStorage? = null,
) : ViewModel() {

    private val edits = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)

    val state: StateFlow<TunerState> = combine(
        repository.observeState(),
        edits,
        transientMessage,
        transientError,
    ) { repoState, localEdits, message, error ->
        ProfileStateResolver.resolve(
            repoState.copy(
                statusMessage = message,
                errorMessage = error,
            ),
            currentValues = repoState.currentValues + localEdits,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TunerState(),
    )

    val settings: StateFlow<AppSettings> = settingsStorage.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    val perAppEnabled: StateFlow<Boolean> = (perAppConfigStorage?.enabled ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val perAppConfigs: StateFlow<List<PerAppConfig>> =
        (perAppConfigStorage?.configs ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val perAppSwitchNotices: StateFlow<Boolean> = (perAppConfigStorage?.switchNotices ?: flowOf(true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val perAppBatteryWh: StateFlow<Float> = (perAppConfigStorage?.batteryCapacityWh ?: flowOf(0f))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /** Packages AutoTDP has learned a warm-start operating point for — drives the per-app "tuned" badge. */
    val autoTdpLearnedPackages: StateFlow<Set<String>> = settingsStorage.autoTdpLearnedPackages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setPerAppEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            perAppConfigStorage?.persistEnabled(enabled)
            onSaved()
        }
    }

    fun setPerAppSwitchNotices(enabled: Boolean) {
        viewModelScope.launch {
            perAppConfigStorage?.persistSwitchNotices(enabled)
        }
    }

    fun savePerAppConfig(config: PerAppConfig, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            perAppConfigStorage?.saveConfig(config)
            // A saved per-app binding must take effect: per-app always overrides the global mode for that
            // app, so enable the watcher here (otherwise the binding sits inert and the global Custom
            // baseline "wins"). No-op when per-app is already on.
            perAppConfigStorage?.persistEnabled(true)
            onSaved()
        }
    }

    fun removePerAppConfig(packageName: String) {
        viewModelScope.launch {
            perAppConfigStorage?.removeConfig(packageName)
        }
    }

    // Nominal all-out peak (W), self-calibrated from real measured draw at full load. 0 = not yet learned.
    private val _learnedPeakW = MutableStateFlow(0f)

    /**
     * Estimated peak system draw (W) at the current ceilings. Scaled by the self-calibrated nominal,
     * and shaped by AutoTDP's learned per-SoC CPU/GPU power split once it's confident (else the fixed
     * heuristic in [PowerEstimator]).
     */
    val estimatedPeakW: StateFlow<Float?> = combine(state, _learnedPeakW, settingsStorage.powerModel) { s, learned, model ->
        val nominal = if (learned > 1f) learned else PowerEstimator.DEFAULT_PEAK_W
        val relIndex = model?.takeIf { it.hasSplit() }?.relativeIndex(s.policies, s.currentValues)
            ?: PowerEstimator.relativeIndex(s.policies, s.currentValues)
        relIndex?.let { nominal * it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- System controls (fan + resolution) -------------------------------------------
    private val fanController = FanController()
    private val displayController = DisplayController()
    private val governorController = GovernorController()
    private val refreshRateController = RefreshRateController()
    private val gpuFloorController = GpuFloorController()
    private val cpuFloorController = CpuFloorController()
    private val telemetryReader = TelemetryReader()

    private val _governor = MutableStateFlow<String?>(null)
    val governor: StateFlow<String?> = _governor
    private val _refreshRate = MutableStateFlow<Int?>(null)
    val refreshRate: StateFlow<Int?> = _refreshRate
    private val _gpuFloorPercent = MutableStateFlow(0)
    val gpuFloorPercent: StateFlow<Int> = _gpuFloorPercent
    private val _cpuFloorPercent = MutableStateFlow(0)
    val cpuFloorPercent: StateFlow<Int> = _cpuFloorPercent
    fun setCpuFloorPercent(percent: Int) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { cpuFloorController.setFloor(state.value.policies, percent) }
            applySideControls(TierTransition.afterCpuFloor(currentSideControls(), percent))
            transientMessage.value = if (percent <= 0) "CPU floor cleared" else "CPU floor set to ~$percent%"
            transientError.value = if (ok) null else "Couldn't set CPU floor"
            persistTuning()
        }
    }

    private val _powerTargetEnabled = MutableStateFlow(false)
    val powerTargetEnabled: StateFlow<Boolean> = _powerTargetEnabled
    private val _powerTargetPercent = MutableStateFlow(100)
    val powerTargetPercent: StateFlow<Int> = _powerTargetPercent
    private val _powerTargetCpuOnly = MutableStateFlow(false)
    val powerTargetCpuOnly: StateFlow<Boolean> = _powerTargetCpuOnly
    private val _gpuLocked = MutableStateFlow(false)
    val gpuLocked: StateFlow<Boolean> = _gpuLocked

    private val _primeCoreBoostLimited = MutableStateFlow(false)
    val primeCoreBoostLimited: StateFlow<Boolean> = _primeCoreBoostLimited

    // Declared up here (not with the other system-control flows lower down) because the constructor's
    // settings/state collectors read `_activeTier.value` synchronously on first emission (viewModelScope uses
    // Dispatchers.Main.immediate, so a cached settings emission runs during construction). A later declaration
    // left it null at that point → intermittent startup NullPointerException.
    private val _activeTier = MutableStateFlow(PowerTier.CUSTOM)
    val activeTier: StateFlow<PowerTier> = _activeTier

    private val _autoTdpDefault = MutableStateFlow(false)
    val autoTdpDefault: StateFlow<Boolean> = _autoTdpDefault

    private val _autoTdpFpsTarget = MutableStateFlow(60)
    val autoTdpFpsTarget: StateFlow<Int> = _autoTdpFpsTarget

    /** AutoTDP FPS-target options for this device's SoC (Odin: 30/40/60/90/120; 8 Gen 2: 60/90/120). */
    val autoTdpFpsOptions: List<Int> = PerAppConfig.fpsTargetsFor(repository.socModel())

    /** Whether to show per-mode watt caps on the AutoTDP chips — only the Odin enforces them. */
    val autoTdpShowWattCaps: Boolean =
        com.kei.pulse.data.AutoTuneController.appliesOdinPowerTuning(repository.socModel())

    private val _autoTdpAggressivePark = MutableStateFlow(true)
    val autoTdpAggressivePark: StateFlow<Boolean> = _autoTdpAggressivePark

    private val _autoTdpBias = MutableStateFlow(AutoTdpBias.EFFICIENT)
    val autoTdpBias: StateFlow<AutoTdpBias> = _autoTdpBias

    /**
     * Master switch. [onSaved] runs after persist and should (re)start the watcher: ON resumes management;
     * OFF makes the service hand everything back to manufacturer stock and stop itself (the pre-uninstall
     * "system is in control" state). Starting on OFF is intentional — it's how the revert gets a root context.
     */
    fun setPulseEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistPulseEnabled(enabled)
            onSaved()
        }
    }

    /** Toggle AutoTDP as the global default. [onSaved] runs after persist (to start/stop the watcher). */
    fun setAutoTdpDefault(enabled: Boolean, onSaved: () -> Unit = {}) {
        _autoTdpDefault.value = enabled
        viewModelScope.launch {
            settingsStorage.persistAutoTdpDefaultEnabled(enabled)
            // AutoTDP manages clocks per-game; the baseline (home/PULSE) must NOT keep a previous mode's
            // pinned values. Releasing here fixes Custom→AutoTDP leaving scaling_max_freq chmod-444-locked
            // (CPU stuck at the Custom cap, e.g. 3.28 GHz, with the "current values" readout frozen).
            if (enabled) {
                releaseManualCapsToStock()
            } else {
                // Disabling hands the clocks back to the active manual tier. Enabling RELEASED the caps to
                // stock, so disabling must RE-APPLY the tier — otherwise the clocks stay wide open (e.g. a
                // Power Target 69% is lost) and the current-values readout shows full clocks until the user
                // re-nudges the tier (reported bug). Reuses the same restore path as clicking the active tier
                // (which already re-applies the Power Target — see [applyTier]).
                applyTier(_activeTier.value)
            }
            onSaved()
        }
    }

    /** Set the RGB joystick-LED mode. [onSaved] runs after persist (to start the watcher so it engages). */
    fun setRgbMode(mode: com.kei.pulse.model.RgbMode, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistRgbMode(mode)
            onSaved()
        }
    }

    /** Manual RGB: persist one stick's (or both, when target is BOTH) color + brightness. */
    fun setRgbManualStick(
        stick: com.kei.pulse.model.RgbStick,
        color: Int,
        brightness: Float,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            settingsStorage.persistRgbManualStick(stick, color, brightness)
            onSaved()
        }
    }

    /** Manual RGB: which stick the controls edit. */
    fun setRgbManualTarget(target: com.kei.pulse.model.RgbStick) {
        viewModelScope.launch { settingsStorage.persistRgbManualTarget(target) }
    }

    /** Reopen every cluster + GPU to full and restore stock writability (unlock manual `chmod 444` caps). */
    private suspend fun releaseManualCapsToStock() {
        val policies = state.value.policies
        if (policies.isEmpty()) return
        val full = policies.associate { it.id to it.selectableMaxFreq }
        repository.applyValues(
            policies = policies,
            selectedValues = full,
            isReset = true,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
        )
    }

    /** Set the global default AutoTDP FPS target (per-app bindings can override it). */
    fun setAutoTdpFpsTarget(target: Int) {
        _autoTdpFpsTarget.value = target
        viewModelScope.launch { settingsStorage.persistAutoTdpFpsTarget(target) }
    }

    /** Toggle AutoTDP aggressive park (offline the prime cores when they aren't the limiter). */
    fun setAutoTdpAggressivePark(enabled: Boolean) {
        _autoTdpAggressivePark.value = enabled
        viewModelScope.launch { settingsStorage.persistAutoTdpAggressivePark(enabled) }
    }

    /** Set the AutoTDP efficiency↔smoothness lean (global default). Takes effect on the next AutoTDP session. */
    fun setAutoTdpBias(bias: AutoTdpBias) {
        _autoTdpBias.value = bias
        viewModelScope.launch { settingsStorage.persistAutoTdpBias(bias) }
    }

    fun setPrimeCoreBoostLimited(limited: Boolean) {
        viewModelScope.launch {
            val policies = state.value.policies
            // Prime = the CPU policy with the highest max freq (same rule used everywhere; never policy-id).
            val prime = policies.filterNot { it.isGpu }.maxByOrNull { it.selectableMaxFreq }
                ?: run { transientError.value = "No Prime core found"; return@launch }
            val freqs = prime.supportedFrequencies
            val targetKHz = if (limited) {
                freqs.getOrNull(freqs.size - 2) ?: freqs.last()
            } else {
                state.value.currentValues[prime.id] ?: prime.selectableMaxFreq
            }
            // Route through applyFreqsToDevice so the prime's scaling_min is lowered + 444-locked BEFORE its
            // max is written (the proven cap path). A raw max-only write leaves the daemon-pinned prime min in
            // place, so a future sub-min no-turbo target would be rejected (max < min); lowering the min first
            // makes any target hold. Pass the FULL policy list so applyFreqsToDevice identifies the prime, but
            // only the prime id is in the map, so no other cluster is touched.
            withContext(Dispatchers.IO) {
                AutoTuneController.applyFreqsToDevice(policies, mapOf(prime.id to targetKHz))
            }
            applySideControls(TierTransition.afterPrimeBoost(currentSideControls(), limited))
            transientMessage.value = if (limited) "Prime boost limited" else "Prime boost restored"
            persistTuning()
        }
    }

    fun setPowerTargetCpuOnly(enabled: Boolean) {
        _powerTargetCpuOnly.value = enabled
        if (_powerTargetEnabled.value) applyPowerTarget(_powerTargetPercent.value)
        persistTuning()
    }

    fun setGpuLocked(locked: Boolean) {
        val gpu = state.value.policies.firstOrNull { it.isGpu }
        if (gpu == null) {
            transientError.value = "No GPU detected"
            return
        }
        val freqs = gpu.supportedFrequencies
        val n = freqs.size
        viewModelScope.launch {
            if (locked) {
                val pinned = withContext(Dispatchers.IO) { gpuFloorController.lockToCurrentCap(gpu.policyPath) }
                // Lock clears the GPU floor (the interlink rule, now a single tested source of truth).
                applySideControls(TierTransition.afterGpuLock(currentSideControls(), locked = true))
                transientMessage.value = if (pinned != null) {
                    val mhz = freqs.getOrNull(n - 1 - pinned)?.div(1000)
                    if (mhz != null) "GPU locked at $mhz MHz" else "GPU locked (level $pinned)"
                } else {
                    "Couldn't read the GPU clock to lock"
                }
            } else {
                withContext(Dispatchers.IO) {
                    gpuFloorController.setFloorLevel(gpu.policyPath, (n - 1).coerceAtLeast(0))
                }
                applySideControls(TierTransition.afterGpuLock(currentSideControls(), locked = false))
                transientMessage.value = "GPU unlocked"
            }
            transientError.value = null
            persistTuning()
        }
    }

    init {
        viewModelScope.launch {
            val persisted = settingsStorage.settings.first()
            _powerTargetEnabled.value = persisted.powerTargetEnabled
            _powerTargetPercent.value = persisted.powerTargetPercent
            _powerTargetCpuOnly.value = persisted.powerTargetCpuOnly
            _gpuLocked.value = persisted.gpuLocked
            _gpuFloorPercent.value = persisted.gpuFloorPercent
            _cpuFloorPercent.value = persisted.cpuFloorPercent
            // Restore the active tier so the UI doesn't always reset to Custom
            PowerTier.entries.firstOrNull { it.label == persisted.activeTierLabel }
                ?.let { _activeTier.value = it }
            _primeCoreBoostLimited.value = persisted.primeCoreBoostLimited
            _learnedPeakW.value = persisted.learnedPeakW
            _autoTdpDefault.value = persisted.autoTdpDefaultEnabled
            _autoTdpFpsTarget.value = persisted.autoTdpFpsTarget
            _autoTdpAggressivePark.value = persisted.autoTdpAggressivePark
            _autoTdpBias.value = persisted.autoTdpBias
        }
        // The first system read can fire before the CPU policies are loaded, leaving the
        // governor null (no chip selected). Wait for policies, then read live controls.
        viewModelScope.launch {
            state.first { snap -> snap.policies.any { !it.isGpu } }
            refreshSystemControls()
        }
        // Capture full-battery energy once so the per-app page can estimate battery life.
        if (perAppConfigStorage != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { com.kei.pulse.data.BatteryReader.capacityWh() }
                    ?.let { perAppConfigStorage.persistBatteryCapacityWh(it) }
            }
        }
        // When a named profile is applied (not a tier/manual slot), clear the highlighted tier.
        viewModelScope.launch {
            state.collect { snap ->
                val lastApplied = snap.lastAppliedDisplayProfileId
                if (lastApplied != null &&
                    lastApplied != ProfileStateResolver.MANUAL_PROFILE_ID &&
                    _activeTier.value != PowerTier.CUSTOM
                ) {
                    _activeTier.value = PowerTier.CUSTOM
                }
            }
        }
        // Keep the settings-backed UI state in sync with the persisted settings, so a change made ELSEWHERE
        // (the QS tile, and now the Quick Access bar) reflects in the app UI without a restart. Without this,
        // turning AutoTDP off elsewhere left the tiers locked; the bar's global fps edit showed stale in the
        // app (the "set 30 on the bar, app still says 60" bug — the init block seeds these ONCE). All fields
        // read here are declared ABOVE the init block (the Main.immediate first-emission NPE gotcha). The
        // diff-guards also keep a persist echo of the VM's own setter from re-firing anything.
        viewModelScope.launch {
            settingsStorage.settings.collect { s ->
                val tier = PowerTier.entries.firstOrNull { it.label == s.activeTierLabel }
                    ?: PowerTier.CUSTOM
                if (_activeTier.value != tier) {
                    _activeTier.value = tier
                }
                if (_autoTdpDefault.value != s.autoTdpDefaultEnabled) {
                    _autoTdpDefault.value = s.autoTdpDefaultEnabled
                }
                if (_autoTdpFpsTarget.value != s.autoTdpFpsTarget) {
                    _autoTdpFpsTarget.value = s.autoTdpFpsTarget
                }
                if (_autoTdpBias.value != s.autoTdpBias) {
                    _autoTdpBias.value = s.autoTdpBias
                }
                if (_autoTdpAggressivePark.value != s.autoTdpAggressivePark) {
                    _autoTdpAggressivePark.value = s.autoTdpAggressivePark
                }
                // The bar's Custom Power Target writes these too now — mirror them like the rest.
                if (_powerTargetEnabled.value != s.powerTargetEnabled) {
                    _powerTargetEnabled.value = s.powerTargetEnabled
                }
                if (_powerTargetPercent.value != s.powerTargetPercent) {
                    _powerTargetPercent.value = s.powerTargetPercent
                }
            }
        }
    }

    private fun persistTuning() {
        viewModelScope.launch {
            val sideControls = currentSideControls()
            settingsStorage.persistTuningState(
                sideControls = sideControls,
                activeTierLabel = _activeTier.value.label,
            )
            // Snapshot the tuning knobs only while Custom is the active tier. Preset applies set
            // the tier to the preset before calling this, so they can't overwrite the Custom
            // memory — which is exactly what lets cycling back to Custom restore it intact.
            if (_activeTier.value == PowerTier.CUSTOM) {
                settingsStorage.persistCustomTuning(
                    CustomTuning(
                        sideControls = sideControls,
                        // _governor holds the raw kernel name; store the option label so it
                        // round-trips back to an OPTIONS entry on restore.
                        governorLabel = GovernorController.optionForGovernor(_governor.value)?.label,
                    ),
                )
            }
        }
    }

    fun setPowerTargetEnabled(enabled: Boolean) {
        applySideControls(TierTransition.afterPowerTargetEnabled(currentSideControls(), enabled))
        if (enabled) {
            _activeTier.value = PowerTier.CUSTOM
            applyPowerTarget(_powerTargetPercent.value)
        }
        persistTuning()
    }

    fun setPowerTargetPercent(percent: Int) {
        _powerTargetPercent.value = percent
        if (_powerTargetEnabled.value) applyPowerTarget(percent)
        persistTuning()
    }

    private fun applyPowerTarget(percent: Int) {
        viewModelScope.launch { applyPowerTargetValues(percent) }
    }

    /**
     * Compute + apply the Power Target's CPU caps (GPU left at full range when CPU-only) and persist them as the
     * Custom map. Suspends so callers can sequence it — e.g. restoring a Power-Target-governed Custom needs the
     * target re-applied in order, not fired-and-forgotten.
     */
    private suspend fun applyPowerTargetValues(percent: Int) {
        val snapshot = state.value
        if (snapshot.policies.isEmpty()) return
        // Shared with the Quick Access bar's live apply — one math, one behavior (PowerTargetMathTest pins it).
        val values = com.kei.pulse.model.PowerTargetMath.capsForPercent(
            snapshot.policies,
            percent,
            cpuOnly = _powerTargetCpuOnly.value,
        )
        edits.value = emptyMap()
        val result = repository.applyValues(
            policies = snapshot.policies,
            selectedValues = values,
            isReset = percent >= 100,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
            persistAsCustom = true,
        )
        result.onSuccess { outcome ->
            _activeTier.value = PowerTier.CUSTOM
            transientMessage.value = if (outcome.verificationPassed) {
                "Power target set to $percent%"
            } else {
                buildVerificationFailureMessage(snapshot, outcome.actualValues, outcome.commandOutput)
            }
            transientError.value = null
        }.onFailure { throwable ->
            transientError.value = throwable.message ?: "Failed to set power target"
        }
        reapplyGpuLock()
    }

    private val _fanMode = MutableStateFlow<Int?>(null)
    val fanMode: StateFlow<Int?> = _fanMode

    /** True only on the Odin 3 whose firmware exposes the writable custom-fan PWM node — gates the Custom
     *  chip + curve editor so Thor/RP6 never see a dead option. Resolved off the main thread on first refresh. */
    private val _customFanSupported = MutableStateFlow(false)
    val customFanSupported: StateFlow<Boolean> = _customFanSupported

    /** True while the autocalibrate fan sweep is running (drives the editor's progress UI + disables re-runs). */
    private val _fanCalibrating = MutableStateFlow(false)
    val fanCalibrating: StateFlow<Boolean> = _fanCalibrating

    private val _nativeDisplay = MutableStateFlow<DisplayController.DisplaySpec?>(null)
    val nativeDisplay: StateFlow<DisplayController.DisplaySpec?> = _nativeDisplay

    private val _resolutionScale = MutableStateFlow(100)
    val resolutionScale: StateFlow<Int> = _resolutionScale

    fun refreshSystemControls() {
        viewModelScope.launch {
            data class Snap(
                val mode: Int?,
                val native: DisplayController.DisplaySpec?,
                val gov: String?,
                val rr: Int?,
                val customFan: Boolean,
            )
            val snap = withContext(Dispatchers.IO) {
                Snap(
                    mode = fanController.readMode(),
                    native = displayController.readNative(),
                    gov = state.value.policies.firstOrNull { !it.isGpu }
                        ?.let { governorController.readGovernor(it) },
                    rr = refreshRateController.readPeak(),
                    customFan = FanController.customFanAvailable(),
                )
            }
            // Custom never writes a vendor fan_mode, so the readback can't show it — reflect the persisted
            // Custom selection directly (Odin only) so the chip stays selected and the editor survives a
            // relaunch. Everywhere else, trust the live vendor readback.
            _customFanSupported.value = snap.customFan
            _fanMode.value =
                if (snap.customFan && settings.value.managedFanMode == FanController.CUSTOM) {
                    FanController.CUSTOM
                } else {
                    snap.mode
                }
            if (snap.native != null) _nativeDisplay.value = snap.native
            if (snap.gov != null) _governor.value = snap.gov
            if (snap.rr != null) _refreshRate.value = snap.rr
        }
    }

    fun setGovernor(option: GovernorOption) {
        viewModelScope.launch {
            val chosen = withContext(Dispatchers.IO) {
                governorController.setGovernor(state.value.policies, option)
            }
            if (chosen != null) _governor.value = chosen
            // A manual chip pick while in Custom is remembered, so cycling back restores it.
            if (chosen != null && _activeTier.value == PowerTier.CUSTOM) persistTuning()
            transientMessage.value =
                if (chosen != null) "CPU governor: ${option.label}" else "Couldn't set governor"
            transientError.value = null
        }
    }

    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { refreshRateController.setRate(hz) }
            _refreshRate.value = hz
            transientMessage.value = "Refresh rate set to ${hz}Hz"
            transientError.value = null
        }
    }

    fun setGpuFloorPercent(percent: Int) {
        val gpu = state.value.policies.firstOrNull { it.isGpu }
        if (gpu == null) {
            transientError.value = "No GPU detected"
            return
        }
        viewModelScope.launch {
            val floorLevel = gpuFloorLevelFor(gpu, percent)
            withContext(Dispatchers.IO) { gpuFloorController.setFloorLevel(gpu.policyPath, floorLevel) }
            applySideControls(TierTransition.afterGpuFloor(currentSideControls(), percent))
            transientMessage.value = if (percent <= 0) "GPU floor cleared" else "GPU floor set to ~$percent%"
            transientError.value = null
            persistTuning()
        }
    }

    /** GPU power-level index (0 = fastest) for a floor percentage; widest level when cleared. */
    private fun gpuFloorLevelFor(gpu: CpuPolicyInfo, percent: Int): Int {
        val asc = gpu.supportedFrequencies
        val n = asc.size
        if (percent <= 0 || asc.isEmpty()) return (n - 1).coerceAtLeast(0)
        val targetFreq = gpu.selectableMaxFreq * percent / 100
        val pos = asc.indices.minByOrNull { kotlin.math.abs(asc[it] - targetFreq) } ?: 0
        return (n - 1 - pos).coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    /**
     * Re-apply the Custom min-side controls that a preset apply releases. The max-side caps
     * (manual freqs, Power Target, prime boost) are already restored via the saved frequency map;
     * GPU min_pwrlevel gets widened by any apply, so a lock/floor must be re-pinned here.
     */
    private suspend fun reapplyCustomSideControls(sideControls: SideControlState) {
        val policies = state.value.policies
        val gpu = policies.firstOrNull { it.isGpu }
        withContext(Dispatchers.IO) {
            if (gpu != null) {
                when {
                    sideControls.gpuLocked -> gpuFloorController.lockToCurrentCap(gpu.policyPath)
                    sideControls.gpuFloorPercent > 0 ->
                        gpuFloorController.setFloorLevel(
                            gpu.policyPath,
                            gpuFloorLevelFor(gpu, sideControls.gpuFloorPercent),
                        )
                }
            }
            if (sideControls.cpuFloorPercent > 0) {
                cpuFloorController.setFloor(policies, sideControls.cpuFloorPercent)
            }
            // Prime Boost Limit only governs when NO Power Target caps the prime — otherwise the PT's (lower)
            // prime cap must win. Without this gate the no-turbo write (e.g. 4204800) overwrites and 444-locks
            // the prime scaling_max that applyPowerTargetValues just set (e.g. 3072000 @ 69%), so the prime
            // reads back at 4.2 GHz instead of the Power Target value. PT governs the CPU clusters whenever it's
            // on (CPU-only still caps the CPU; only the GPU is freed), so the boost limit is redundant there.
            if (sideControls.primeCoreBoostLimited && !sideControls.powerTargetEnabled) {
                val prime = policies.filterNot { it.isGpu }.maxByOrNull { it.selectableMaxFreq }
                if (prime != null) {
                    val freqs = prime.supportedFrequencies
                    val target = freqs.getOrNull(freqs.size - 2) ?: freqs.last()
                    // Route the no-turbo cap through the proven path: it lowers the prime min + 444-locks it
                    // before writing the max, so the cap holds even if the no-turbo target ever drops below
                    // the daemon's pinned min (a raw max-only write would be rejected, max < min). Full policy
                    // list identifies the prime; only the prime id is written.
                    AutoTuneController.applyFreqsToDevice(policies, mapOf(prime.id to target))
                }
            }
        }
    }

    suspend fun readTelemetry(): TelemetrySnapshot {
        val snap = withContext(Dispatchers.IO) { telemetryReader.read(state.value.policies) }
        updatePeakCalibration(snap)
        return snap
    }

    /**
     * Learn the device's true peak from reality: when the GPU is near full load, the measured
     * draw divided by the current relative-index implies the all-out nominal. EMA toward it so the
     * "Est peak" figure converges to this device instead of staying on the hard-coded constant.
     */
    private fun updatePeakCalibration(snap: TelemetrySnapshot) {
        // Charger current is not system draw — calibrating while plugged in corrupts the model.
        if (!snap.isDischarging) return
        val drawW = snap.batteryDrawW ?: return
        val busy = snap.gpuBusyPercent ?: return
        if (busy < 85 || drawW <= 0f) return
        val rel = PowerEstimator.relativeIndex(state.value.policies, state.value.currentValues) ?: return
        if (rel < 0.2f) return
        val impliedNominal = (drawW / rel).coerceIn(6f, 40f)
        val prior = _learnedPeakW.value.takeIf { it > 1f } ?: PowerEstimator.DEFAULT_PEAK_W
        val updated = prior * 0.85f + impliedNominal * 0.15f
        _learnedPeakW.value = updated
        viewModelScope.launch { settingsStorage.persistLearnedPeakW(updated) }
    }

    fun setFanMode(mode: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { fanController.setMode(mode) }
            // Custom drives the PWM curve and never writes a vendor fan_mode, so reflect the chosen mode
            // directly — reading back the live value would still show Smart/etc. and the chip wouldn't stick.
            _fanMode.value = if (mode == FanController.CUSTOM) {
                mode
            } else {
                withContext(Dispatchers.IO) { fanController.readMode() }
            }
            // Remember it so the watcher re-asserts it against the system Fan tile; onSaved starts that watcher.
            settingsStorage.persistManagedFanMode(mode)
            onSaved()
            transientMessage.value = if (ok) {
                "Fan set to ${FanController.labelFor(mode)}"
            } else {
                "Couldn't change fan mode"
            }
            transientError.value = null
        }
    }

    /** Persist the user's edited Custom fan curve. The running service reads `settings.fanCurve` each tick,
     *  so the change takes effect within a tick; [onSaved] restarts the watcher to engage it immediately. */
    fun setFanCurve(curve: FanCurve, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanCurve(curve)
            onSaved()
        }
    }

    /** Persist the Custom fan slew rate (%/second the fan ramps toward the curve target). */
    fun setFanResponseStep(step: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanResponseStep(step)
            onSaved()
        }
    }

    /** Persist the Cooler/Quieter bias (live % offset on the curve; + cooler/louder, − quieter). */
    fun setFanBias(bias: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanBias(bias)
            onSaved()
        }
    }

    /** Toggle the Smart (closed-loop temp-target) Custom fan controller vs the manual curve. */
    fun setFanSmartEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanSmartEnabled(enabled)
            onSaved()
        }
    }

    /** Persist the Smart-mode target SoC temperature (°C) the controller holds. */
    fun setFanTargetTemp(tempC: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanTargetTemp(tempC)
            onSaved()
        }
    }

    /** Live ACTUAL fan speed as a duty % from the gpio5_pwm2 duty node — the reliable "how fast is the fan"
     *  reading (the RPM tach reads 0 most of the time, even in the vendor's own mode, so we don't trust it). */
    suspend fun readFanDutyPercent(): Int? = withContext(Dispatchers.IO) {
        val duty = FanCurveController.readDutyFromDevice() ?: return@withContext null
        val period = FanCurveController.readPeriodFromDevice() ?: FanCurveController.DEFAULT_PERIOD
        if (period <= 0) null else ((duty.toLong() * 100) / period).toInt().coerceIn(0, 100)
    }

    /**
     * EVGA-style autocalibrate: sweep the fan duty low→100% (settling at each step), read the RPM tach, then
     * derive this unit's real min-spin point and a recommended curve from the samples ([FanCalibration]). The
     * sweep drives the PWM directly, so it flags [FanCurveController.externalControlActive] (the foreground
     * service stands down) for its duration and ALWAYS clears it in a `finally`. Odin-only; persists the
     * recommended curve and restarts the watcher so Custom re-engages and retakes the fan.
     */
    fun autocalibrateFan(onDone: () -> Unit = {}) {
        if (_fanCalibrating.value) return
        viewModelScope.launch {
            val supported = withContext(Dispatchers.IO) {
                FanController.customFanAvailable()
            }
            if (!supported) {
                transientError.value = "Custom fan isn't available on this device"
                return@launch
            }
            _fanCalibrating.value = true
            transientMessage.value = "Calibrating fan — sweeping speeds (~15s)…"
            val cal = withContext(Dispatchers.IO) { runFanSweep() }
            settingsStorage.persistFanCurve(cal.recommendedCurve)
            _fanCalibrating.value = false
            transientMessage.value =
                "Fan calibrated · idle ${cal.minSpinPercent}% · comfort ${cal.comfortPercent}% · ${cal.maxRpm} RPM max"
            transientError.value = null
            onDone() // restart the watcher so the new curve + Custom mode re-engage and retake the fan
        }
    }

    /** Blocking duty sweep + tach read (run on IO). Holds [FanCurveController.externalControlActive] so the
     *  service's re-assert loop doesn't fight the writes; clears it in `finally` even on cancel/throw. */
    private suspend fun runFanSweep(): FanCalibration {
        // Read the device's idle temp first so the curve's quiet anchor sits at this unit's real resting temp.
        val idleTemp = readTelemetry().let { maxOf(it.cpuTempC ?: 0, it.gpuTempC ?: 0) }.takeIf { it > 0 }
        val period = FanCurveController.readPeriodFromDevice() ?: FanCurveController.DEFAULT_PERIOD
        val samples = mutableListOf<FanCalibrationSample>()
        FanCurveController.externalControlActive = true
        try {
            for (pct in FAN_CALIBRATION_STEPS) {
                FanCurveController.writeDutyToDevice(FanCurve.percentToDuty(pct, period))
                delay(FAN_CALIBRATION_SETTLE_MS)
                samples += FanCalibrationSample(pct, FanCurveController.readRpmFromDevice() ?: 0)
            }
        } finally {
            FanCurveController.externalControlActive = false
        }
        return FanCalibration.fromSweep(samples, idleTemp)
    }

    fun setResolutionScale(percent: Int) {
        viewModelScope.launch {
            val native = _nativeDisplay.value
                ?: withContext(Dispatchers.IO) { displayController.readNative() }?.also { _nativeDisplay.value = it }
            if (native == null) {
                transientError.value = "Couldn't read display size"
                return@launch
            }
            withContext(Dispatchers.IO) { displayController.applyScale(native, percent) }
            _resolutionScale.value = percent
            transientMessage.value = if (percent >= 100) {
                "Resolution reset to native"
            } else {
                "Render scale set to $percent%"
            }
            transientError.value = null
        }
    }

    fun applyTier(tier: PowerTier, onApplied: (String) -> Unit = {}) {
        val snapshot = state.value
        if (tier == PowerTier.CUSTOM) {
            // Switching to Custom restores the user's last manual setup: the saved frequency map
            // (max-side caps incl. Power Target / manual / prime boost) plus the tuning knobs that
            // a preset apply clears or releases (Power Target toggle, GPU lock/floor, CPU floor).
            viewModelScope.launch {
                val tuning = settingsStorage.customTuning.first()
                val sideControls = tuning.sideControls
                val result = repository.restoreCustomValues()
                edits.value = emptyMap()
                _activeTier.value = PowerTier.CUSTOM
                // Restore every Custom side-control from the saved tuning via the unit-tested resolver
                // (a saved field with no mapping there is a restoration bug, not a silent drop).
                applySideControls(TierTransition.afterCustomRestore(tuning))
                // A Power-Target-governed Custom must actually RE-APPLY the target, not just restore the saved
                // value map — otherwise the prior preset's CPU freqs keep showing in the current-values readout
                // until the PT slider is nudged (the reported bug). Mirrors the slider path, and re-persists the
                // reduced map so any stale saved values self-heal.
                if (sideControls.powerTargetEnabled) applyPowerTargetValues(sideControls.powerTargetPercent)
                reapplyCustomSideControls(sideControls)
                // Restore the governor the user last set in Custom (if any).
                tuning.governorLabel?.let { label ->
                    GovernorController.OPTIONS.firstOrNull { it.label == label }?.let { option ->
                        val chosen = withContext(Dispatchers.IO) {
                            governorController.setGovernor(state.value.policies, option)
                        }
                        if (chosen != null) _governor.value = chosen
                    }
                }
                persistTuning()
                result.onSuccess {
                    transientMessage.value = "Restored Custom"
                    transientError.value = null
                }
                onApplied(PowerTier.CUSTOM.label)
            }
            return
        }
        if (snapshot.policies.isEmpty()) {
            _activeTier.value = PowerTier.CUSTOM
            persistTuning()
            return
        }
        // Presets drive the CPU. Max/Balanced leave the GPU at full for gaming; only Power
        // Saving eases the GPU back a notch (for media / light play).
        val powerSaving = tier == PowerTier.POWER_SAVING
        val cpuSorted = snapshot.policies.filterNot { it.isGpu }.sortedBy { it.id }
        val primePolicyId = cpuSorted.lastOrNull()?.id
        val values = snapshot.policies.associate { policy ->
            val factor = when {
                policy.isGpu && !powerSaving -> 1.0
                policy.isGpu -> tier.gpuFactor
                else -> tier.cpuFactor
            }
            val target = (policy.selectableMaxFreq * factor).toInt()
            var snapped = policy.supportedFrequencies.minByOrNull { kotlin.math.abs(it - target) }
                ?: policy.selectableMaxFreq
            // Power Saving: limit the Prime cluster below its non-sustainable turbo bin
            if (powerSaving && !policy.isGpu && policy.id == primePolicyId) {
                val freqs = policy.supportedFrequencies
                val noTurbo = freqs.getOrNull(freqs.size - 2) ?: freqs.last()
                snapped = minOf(snapped, noTurbo)
            }
            policy.id to snapped
        }
        edits.value = emptyMap()
        viewModelScope.launch {
            val result = repository.applyValues(
                policies = snapshot.policies,
                selectedValues = values,
                isReset = tier == PowerTier.MAX,
                appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
            )
            result.onSuccess { outcome ->
                edits.value = emptyMap()
                _activeTier.value = tier
                // A preset is governed by the tier, so it CLEARS every Custom side-control (Power Target,
                // GPU lock + floor, CPU floor, prime-boost limit). The cleared UI state is now the single
                // unit-tested source of truth (TierTransition.afterPreset), so a clear can't silently be
                // forgotten here again — that was the prime-boost Known Bug. Device releases are unchanged:
                // the CPU floor is released on the node below (the cap apply writes only scaling_max, never
                // the non-prime scaling_min the floor raised), and the GPU lock is re-evaluated by
                // reapplyGpuLock() at the end of this apply.
                applySideControls(TierTransition.afterPreset(currentSideControls()))
                withContext(Dispatchers.IO) { cpuFloorController.setFloor(snapshot.policies, 0) }
                // Re-assert the preset's smart-default governor (the vendor daemon resets it).
                // Governors respect scaling_max_freq, so the caps just applied still hold.
                tier.governorLabel?.let { label ->
                    GovernorController.OPTIONS.firstOrNull { it.label == label }?.let { option ->
                        val chosen = withContext(Dispatchers.IO) {
                            governorController.setGovernor(snapshot.policies, option)
                        }
                        if (chosen != null) _governor.value = chosen
                    }
                }
                persistTuning()
                transientMessage.value = if (outcome.verificationPassed) {
                    "Applied ${tier.label}"
                } else {
                    buildVerificationFailureMessage(snapshot, outcome.actualValues, outcome.commandOutput)
                }
                transientError.value = null
                onApplied(tier.label)
            }.onFailure { throwable ->
                transientError.value = throwable.message ?: "Failed to apply ${tier.label}"
            }
            reapplyGpuLock()
        }
    }

    /** The seven Custom side-control flags as a pure snapshot (input to [TierTransition.afterPreset]). */
    private fun currentSideControls(): SideControlState = SideControlState(
        powerTargetEnabled = _powerTargetEnabled.value,
        powerTargetPercent = _powerTargetPercent.value,
        powerTargetCpuOnly = _powerTargetCpuOnly.value,
        gpuLocked = _gpuLocked.value,
        gpuFloorPercent = _gpuFloorPercent.value,
        cpuFloorPercent = _cpuFloorPercent.value,
        primeCoreBoostLimited = _primeCoreBoostLimited.value,
    )

    /** Push a resolved side-control state onto the UI flows — the single place these flags are assigned
     *  across tier transitions, so a transition can't clear/restore one but forget another. */
    private fun applySideControls(s: SideControlState) {
        _powerTargetEnabled.value = s.powerTargetEnabled
        _powerTargetPercent.value = s.powerTargetPercent
        _powerTargetCpuOnly.value = s.powerTargetCpuOnly
        _gpuLocked.value = s.gpuLocked
        _gpuFloorPercent.value = s.gpuFloorPercent
        _cpuFloorPercent.value = s.cpuFloorPercent
        _primeCoreBoostLimited.value = s.primeCoreBoostLimited
    }

    fun setPolicyValue(policy: CpuPolicyInfo, rawValue: Int) {
        val snapped = snapToSupported(policy, rawValue)
        val updatedEdits = edits.value + (policy.id to snapped)
        edits.value = updatedEdits
        transientMessage.value = null
        transientError.value = null

        val baseValues = state.value.policies.associate { cpuPolicy ->
            cpuPolicy.id to (state.value.actualValues[cpuPolicy.id] ?: cpuPolicy.currentMaxFreq)
        }
        val pendingValues = baseValues + updatedEdits
        val selectedProfile = state.value.displayProfiles
            .firstOrNull { it.id == state.value.selectedProfileId }

        if (selectedProfile != null &&
            !ProfileStateResolver.matchesProfile(pendingValues, selectedProfile, state.value.policies)
        ) {
            viewModelScope.launch {
                repository.selectProfile(null)
            }
        }
    }

    fun applyProfile(profile: PerformanceProfile) {
        edits.value = edits.value + profile.maxFrequencies
        viewModelScope.launch {
            repository.selectProfile(profile.id.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
        }
    }

    fun clearSelection() {
        viewModelScope.launch {
            repository.selectProfile(null)
        }
    }

    fun consumeStatusMessage() {
        transientMessage.value = null
    }

    fun consumeErrorMessage() {
        transientError.value = null
    }

    fun applyCurrent(state: TunerState, onApplied: (String) -> Unit = {}) {
        transientMessage.value = null
        transientError.value = null

        viewModelScope.launch {
            val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
            val applyResult = repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                // A pure manual apply (no profile matched) is the user's Custom setup — snapshot it.
                persistAsCustom = appliedProfile == null,
            )
            applyResult.onSuccess { outcome ->
                edits.value = emptyMap()
                transientMessage.value = if (outcome.verificationPassed) {
                    buildAppliedMessage(appliedProfile, outcome.commandOutput)
                } else {
                    buildVerificationFailureMessage(state, outcome.actualValues, outcome.commandOutput)
                }
                transientError.value = null
            }.onFailure { throwable ->
                transientError.value = throwable.message ?: "Failed to apply limits"
            }
            if (applyResult.isSuccess) {
                reapplyGpuLock()
                repository.selectProfile(appliedProfile?.id?.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
                onApplied(appliedProfile?.name ?: "Manual")
            }
        }
    }

    /** Apply widens GPU min_pwrlevel (un-pinning a lock); re-pin it if the lock is engaged. */
    private suspend fun reapplyGpuLock() {
        if (!_gpuLocked.value) return
        val gpu = state.value.policies.firstOrNull { it.isGpu } ?: return
        withContext(Dispatchers.IO) { gpuFloorController.lockToCurrentCap(gpu.policyPath) }
    }

    fun createUserProfile(name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = null, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.createUserProfile(trimmedName, state.currentValues)
            transientMessage.value = "Saved profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun updateProfile(profileId: String, name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = profileId, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.updateProfile(profileId, trimmedName, state.currentValues)
            transientMessage.value = "Updated profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            transientMessage.value = "Deleted profile"
            transientError.value = null
        }
    }

    fun moveProfile(profileId: String, offset: Int) {
        viewModelScope.launch {
            repository.moveProfile(profileId, offset)
        }
    }

    fun resetProfilesToDefault() {
        viewModelScope.launch {
            repository.resetProfilesToDefault()
            transientMessage.value = "Restored bundled profiles and removed custom profiles"
            transientError.value = null
        }
    }

    suspend fun exportProfilesJson(): String {
        return repository.exportProfilesJson()
    }

    suspend fun importProfilesJson(rawJson: String): Int {
        val importedCount = repository.importProfilesJson(rawJson)
        transientMessage.value = if (importedCount == 1) {
            "Imported 1 profile"
        } else {
            "Imported $importedCount profiles"
        }
        transientError.value = null
        return importedCount
    }

    fun setTileTapBehavior(behavior: TileInteractionBehavior, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistTileTapBehavior(behavior)
            onSaved()
        }
    }

    fun setApplyLastProfileOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistApplyLastProfileOnBoot(enabled)
        }
    }

    fun setSleepProfileEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileEnabled(enabled)
            onSaved()
        }
    }

    fun configureSleepProfile(enabled: Boolean, profileId: String?, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfile(enabled, profileId)
            onSaved()
        }
    }

    fun setSleepProfile(profileId: String?) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileId(profileId)
        }
    }

    fun setThemeId(themeId: com.kei.pulse.model.PulseThemeId) {
        viewModelScope.launch {
            settingsStorage.persistThemeId(themeId)
        }
    }

    fun setColorSource(colorSource: AppColorSource) {
        viewModelScope.launch {
            settingsStorage.persistColorSource(colorSource)
        }
    }

    fun setAccentColor(accentColor: Int) {
        viewModelScope.launch {
            settingsStorage.persistAccentColor(accentColor)
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStorage.persistOverlayEnabled(enabled) }
    }

    fun setQuickAccess(enabled: Boolean) {
        viewModelScope.launch { settingsStorage.persistQuickAccessEnabled(enabled) }
    }

    fun setQuickAccessShowHandle(show: Boolean) {
        viewModelScope.launch { settingsStorage.persistQuickAccessShowHandle(show) }
    }

    fun clearQuickAccessCombo() {
        viewModelScope.launch { settingsStorage.persistQuickAccessCombo(null) }
    }

    private val _capturingCombo = MutableStateFlow(false)
    /** True while "Set combo" is reading the controller — drives the Settings UI's "press now" state. */
    val capturingCombo: StateFlow<Boolean> = _capturingCombo

    /** Press-to-capture: read the next combo the user holds (via getevent) and save it. */
    fun captureQuickAccessCombo() {
        if (_capturingCombo.value) return
        _capturingCombo.value = true
        viewModelScope.launch {
            try {
                val combo = com.kei.pulse.data.InputComboWatcher().captureNext()
                if (combo.isNotEmpty()) {
                    settingsStorage.persistQuickAccessCombo(com.kei.pulse.data.InputComboParser.encode(combo))
                }
            } finally {
                // Always clear, even on a throw/cancellation — a stuck flag wedges the Settings UI with no recovery.
                _capturingCombo.value = false
            }
        }
    }

    fun setOverlayPreset(preset: com.kei.pulse.model.OverlayPreset) {
        // Settings chip = quick-fill: set the layout AND seed the shown-items set from the preset's bundle.
        // (The in-overlay LAYOUT cycle persists only the preset, so it never wipes a custom item set.)
        viewModelScope.launch {
            settingsStorage.persistOverlayPreset(preset)
            settingsStorage.persistOverlayElements(preset.elements)
        }
    }

    fun setOverlayElement(element: com.kei.pulse.model.OverlayElement, on: Boolean) {
        viewModelScope.launch {
            val current = settingsStorage.settings.first().overlayElements
            settingsStorage.persistOverlayElements(if (on) current + element else current - element)
        }
    }

    fun setOverlayOpacity(opacity: Int) {
        viewModelScope.launch { settingsStorage.persistOverlayOpacity(opacity) }
    }

    fun refreshLiveState() {
        repository.refreshLiveValues()
    }

    private fun snapToSupported(policy: CpuPolicyInfo, rawValue: Int): Int {
        return policy.supportedFrequencies.minByOrNull { supported -> abs(supported - rawValue) }
            ?: rawValue
    }

    private fun hasDuplicateProfileName(
        name: String,
        excludedId: String?,
        state: TunerState,
    ): Boolean {
        return state.displayProfiles
            .filter { profile -> profile.source != ProfileSource.VIRTUAL }
            .any { profile ->
                profile.id != excludedId && profile.name.equals(name, ignoreCase = true)
            }
    }

    private fun buildAppliedMessage(
        appliedProfile: PerformanceProfile?,
        commandOutput: String?,
    ): String {
        val base = if (appliedProfile != null) {
            "Applied profile: ${appliedProfile.name}"
        } else {
            "Applied profile: Manual"
        }
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    private fun buildVerificationFailureMessage(
        state: TunerState,
        actualValues: Map<Int, Int>,
        commandOutput: String?,
    ): String {
        val summary = state.policies.joinToString(", ") { policy ->
            val requested = state.currentValues[policy.id] ?: policy.currentMaxFreq
            val actual = actualValues[policy.id] ?: policy.currentMaxFreq
            "C${policy.id} requested ${formatFrequency(requested)}, " +
                "actual ${formatFrequency(actual, boosted = actual > policy.selectableMaxFreq)}"
        }
        val base = "Apply did not stick: $summary"
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    companion object {
        /** Duty %s the autocalibrate sweep steps through, low→100% (the fan's real start point is found here). */
        private val FAN_CALIBRATION_STEPS = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
        /** Settle time per step so the fan reaches a stable RPM before the tach is sampled. */
        private const val FAN_CALIBRATION_SETTLE_MS = 1_500L

        fun factory(
            repository: PerformanceRepository,
            settingsStorage: SettingsStorage,
            perAppConfigStorage: PerAppConfigStorage? = null,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TunerViewModel(repository, settingsStorage, perAppConfigStorage) as T
                }
            }
        }
    }
}
