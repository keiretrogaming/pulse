package com.kei.pulse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kei.pulse.model.AppColorSource
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanTempController
import com.kei.pulse.model.OverlayElement
import com.kei.pulse.model.OverlayPreset
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.RgbMode
import com.kei.pulse.model.RgbStick
import com.kei.pulse.model.PulseThemeId
import com.kei.pulse.model.TileInteractionBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "android_tuner_settings")

class SettingsStorage(private val context: Context) {

    private val tileTapBehaviorKey = stringPreferencesKey("tile_tap_behavior")
    private val pulseEnabledKey = booleanPreferencesKey("pulse_enabled")
    private val applyLastProfileOnBootKey = booleanPreferencesKey("apply_last_profile_on_boot")
    private val sleepProfileEnabledKey = booleanPreferencesKey("sleep_profile_enabled")
    private val sleepProfileIdKey = stringPreferencesKey("sleep_profile_id")
    private val quickSettingsTilePromptShownKey = booleanPreferencesKey("quick_settings_tile_prompt_shown")
    private val quickSettingsTileAddedKey = booleanPreferencesKey("quick_settings_tile_added")
    private val batteryOptPromptShownKey = booleanPreferencesKey("battery_opt_prompt_shown")
    private val themeIdKey = stringPreferencesKey("theme_id")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val accentColorKey = intPreferencesKey("accent_color")
    private val powerTargetEnabledKey = booleanPreferencesKey("power_target_enabled")
    private val powerTargetPercentKey = intPreferencesKey("power_target_percent")
    private val powerTargetCpuOnlyKey = booleanPreferencesKey("power_target_cpu_only")
    private val gpuLockedKey = booleanPreferencesKey("gpu_locked")
    private val gpuFloorPercentKey = intPreferencesKey("gpu_floor_percent")
    private val cpuFloorPercentKey = intPreferencesKey("cpu_floor_percent")
    private val managedFanModeKey = intPreferencesKey("managed_fan_mode")
    private val activeTierKey = stringPreferencesKey("active_tier_label")
    private val primeCoreBoostKey = booleanPreferencesKey("prime_core_boost_limited")
    private val learnedPeakKey = floatPreferencesKey("learned_peak_w")
    private val autoTdpDefaultKey = booleanPreferencesKey("auto_tdp_default_enabled")
    private val autoTdpFpsTargetKey = intPreferencesKey("auto_tdp_fps_target")
    private val autoTdpAggressiveParkKey = booleanPreferencesKey("auto_tdp_aggressive_park")
    private val autoTdpBiasKey = stringPreferencesKey("auto_tdp_bias")
    // AutoTDP's learned per-SoC power model (CPU/GPU split), stored as JSON.
    private val powerModelKey = stringPreferencesKey("power_model_json")
    private val powerModelJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // AutoTDP per-app warm-start memory: package → (policy id → cap %). Policy ids are stringified
    // because JSON object keys must be strings.
    private val autoTdpCapsKey = stringPreferencesKey("auto_tdp_caps_json")

    // In-game overlay (OSD) preferences.
    private val overlayEnabledKey = booleanPreferencesKey("overlay_enabled")
    private val quickAccessEnabledKey = booleanPreferencesKey("quick_access_enabled")
    private val quickAccessShowHandleKey = booleanPreferencesKey("quick_access_show_handle")
    private val quickAccessComboKey = stringPreferencesKey("quick_access_combo")
    private val quickAccessPerGameScopeKey = booleanPreferencesKey("quick_access_per_game_scope")
    private val overlayPresetKey = stringPreferencesKey("overlay_preset")
    private val overlayElementsKey = stringPreferencesKey("overlay_elements")
    private val overlayOpacityKey = intPreferencesKey("overlay_opacity")
    private val overlayPosXKey = intPreferencesKey("overlay_pos_x")
    private val overlayPosYKey = intPreferencesKey("overlay_pos_y")
    private val fanCurveKey = stringPreferencesKey("fan_curve")
    private val fanResponseKey = intPreferencesKey("fan_response_step")
    private val fanBiasKey = intPreferencesKey("fan_bias")
    private val fanSmartKey = booleanPreferencesKey("fan_smart_enabled")
    private val fanTargetTempKey = intPreferencesKey("fan_target_temp")
    private val rgbModeKey = stringPreferencesKey("rgb_mode")
    private val rgbManualTargetKey = stringPreferencesKey("rgb_manual_target")
    private val rgbManualLeftColorKey = intPreferencesKey("rgb_manual_left_color")
    private val rgbManualLeftBrightnessKey = floatPreferencesKey("rgb_manual_left_brightness")
    private val rgbManualRightColorKey = intPreferencesKey("rgb_manual_right_color")
    private val rgbManualRightBrightnessKey = floatPreferencesKey("rgb_manual_right_brightness")

    // Custom-mode snapshot: preserved across preset applies so cycling back to Custom restores it.
    private val customPtEnabledKey = booleanPreferencesKey("custom_pt_enabled")
    private val customPtPercentKey = intPreferencesKey("custom_pt_percent")
    private val customPtCpuOnlyKey = booleanPreferencesKey("custom_pt_cpu_only")
    private val customGpuLockedKey = booleanPreferencesKey("custom_gpu_locked")
    private val customGpuFloorKey = intPreferencesKey("custom_gpu_floor_percent")
    private val customCpuFloorKey = intPreferencesKey("custom_cpu_floor_percent")
    private val customPrimeBoostKey = booleanPreferencesKey("custom_prime_core_boost")
    private val customGovernorLabelKey = stringPreferencesKey("custom_governor_label")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            themeId = preferences[themeIdKey]?.let(::parseThemeId) ?: PulseThemeId.SIGNAL,
            colorSource = preferences[colorSourceKey]
                ?.let(::parseColorSource)
                ?: AppColorSource.SYSTEM,
            accentColor = preferences[accentColorKey] ?: 0xFF3F51B5.toInt(),
            tileTapBehavior = preferences[tileTapBehaviorKey]
                ?.let(::parseBehavior)
                ?: TileInteractionBehavior.SHOW_DIALOG,
            pulseEnabled = preferences[pulseEnabledKey] ?: true,
            applyLastProfileOnBoot = preferences[applyLastProfileOnBootKey] ?: false,
            sleepProfileEnabled = preferences[sleepProfileEnabledKey] ?: false,
            sleepProfileId = preferences[sleepProfileIdKey],
            hasPromptedQuickSettingsTile = preferences[quickSettingsTilePromptShownKey] ?: false,
            isQuickSettingsTileAdded = preferences[quickSettingsTileAddedKey] ?: false,
            batteryOptPromptShown = preferences[batteryOptPromptShownKey] ?: false,
            powerTargetEnabled = preferences[powerTargetEnabledKey] ?: false,
            powerTargetPercent = preferences[powerTargetPercentKey] ?: 100,
            powerTargetCpuOnly = preferences[powerTargetCpuOnlyKey] ?: false,
            gpuLocked = preferences[gpuLockedKey] ?: false,
            gpuFloorPercent = preferences[gpuFloorPercentKey] ?: 0,
            cpuFloorPercent = preferences[cpuFloorPercentKey] ?: 0,
            managedFanMode = preferences[managedFanModeKey],
            activeTierLabel = preferences[activeTierKey] ?: "Custom",
            primeCoreBoostLimited = preferences[primeCoreBoostKey] ?: false,
            learnedPeakW = preferences[learnedPeakKey] ?: 0f,
            autoTdpDefaultEnabled = preferences[autoTdpDefaultKey] ?: false,
            autoTdpFpsTarget = preferences[autoTdpFpsTargetKey] ?: 60,
            autoTdpAggressivePark = preferences[autoTdpAggressiveParkKey] ?: true,
            autoTdpBias = preferences[autoTdpBiasKey]?.let(::parseAutoTdpBias) ?: AutoTdpBias.EFFICIENT,
            overlayEnabled = preferences[overlayEnabledKey] ?: false,
            quickAccessEnabled = preferences[quickAccessEnabledKey] ?: false,
            quickAccessShowHandle = preferences[quickAccessShowHandleKey] ?: true,
            quickAccessCombo = preferences[quickAccessComboKey],
            quickAccessPerGameScope = preferences[quickAccessPerGameScopeKey] ?: true,
            overlayPreset = preferences[overlayPresetKey]?.let(::parseOverlayPreset) ?: OverlayPreset.COMPACT,
            // Existing users (no element key yet) inherit their saved preset's bundle — no migration logic needed.
            overlayElements = preferences[overlayElementsKey]?.let(::parseOverlayElements)
                ?: (preferences[overlayPresetKey]?.let(::parseOverlayPreset) ?: OverlayPreset.COMPACT).elements,
            overlayOpacity = preferences[overlayOpacityKey] ?: 90,
            overlayPosX = preferences[overlayPosXKey] ?: 24,
            overlayPosY = preferences[overlayPosYKey] ?: 48,
            rgbMode = preferences[rgbModeKey]?.let(::parseRgbMode) ?: RgbMode.OFF,
            rgbManualTarget = preferences[rgbManualTargetKey]?.let(::parseRgbStick) ?: RgbStick.BOTH,
            rgbManualLeftColor = preferences[rgbManualLeftColorKey] ?: 0xFF3F6BFF.toInt(),
            rgbManualLeftBrightness = preferences[rgbManualLeftBrightnessKey] ?: 1f,
            rgbManualRightColor = preferences[rgbManualRightColorKey] ?: 0xFF3F6BFF.toInt(),
            rgbManualRightBrightness = preferences[rgbManualRightBrightnessKey] ?: 1f,
            fanCurve = FanCurve.parse(preferences[fanCurveKey]),
            fanResponseStep = preferences[fanResponseKey] ?: FanCurve.DEFAULT_SLEW,
            fanBias = preferences[fanBiasKey] ?: 0,
            fanSmartEnabled = preferences[fanSmartKey] ?: true,
            fanTargetTempC = preferences[fanTargetTempKey] ?: FanTempController.DEFAULT_TARGET_C,
        )
    }

    suspend fun persistRgbMode(mode: RgbMode) {
        context.settingsDataStore.edit { preferences -> preferences[rgbModeKey] = mode.name }
    }

    suspend fun persistRgbManualTarget(target: RgbStick) {
        context.settingsDataStore.edit { preferences -> preferences[rgbManualTargetKey] = target.name }
    }

    /** Persist one stick's color + brightness (or both when [stick] is BOTH). Color is ARGB at full value. */
    suspend fun persistRgbManualStick(stick: RgbStick, color: Int, brightness: Float) {
        context.settingsDataStore.edit { preferences ->
            if (stick != RgbStick.RIGHT) {
                preferences[rgbManualLeftColorKey] = color
                preferences[rgbManualLeftBrightnessKey] = brightness
            }
            if (stick != RgbStick.LEFT) {
                preferences[rgbManualRightColorKey] = color
                preferences[rgbManualRightBrightnessKey] = brightness
            }
        }
    }

    private fun parseRgbMode(raw: String): RgbMode =
        runCatching { RgbMode.valueOf(raw) }.getOrDefault(RgbMode.OFF)

    private fun parseRgbStick(raw: String): RgbStick =
        runCatching { RgbStick.valueOf(raw) }.getOrDefault(RgbStick.BOTH)

    suspend fun persistPulseEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[pulseEnabledKey] = enabled }
    }

    suspend fun persistOverlayEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[overlayEnabledKey] = enabled }
    }

    suspend fun persistQuickAccessEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[quickAccessEnabledKey] = enabled }
    }

    suspend fun persistQuickAccessShowHandle(show: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[quickAccessShowHandleKey] = show }
    }

    suspend fun persistQuickAccessCombo(combo: String?) {
        context.settingsDataStore.edit { preferences ->
            if (combo.isNullOrBlank()) preferences.remove(quickAccessComboKey) else preferences[quickAccessComboKey] = combo
        }
    }

    suspend fun persistQuickAccessPerGameScope(perGame: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[quickAccessPerGameScopeKey] = perGame }
    }

    suspend fun persistOverlayPreset(preset: OverlayPreset) {
        context.settingsDataStore.edit { preferences -> preferences[overlayPresetKey] = preset.name }
    }

    /** Persist the chosen overlay items as a comma-joined name list (empty string = nothing shown). */
    suspend fun persistOverlayElements(elements: Set<OverlayElement>) {
        context.settingsDataStore.edit { preferences ->
            preferences[overlayElementsKey] = elements.joinToString(",") { it.name }
        }
    }

    /** Persist the Custom fan curve as a "temp:percent,…" string (see [FanCurve.serialize]). */
    suspend fun persistFanCurve(curve: FanCurve) {
        context.settingsDataStore.edit { preferences -> preferences[fanCurveKey] = curve.serialize() }
    }

    /** Persist the fan slew rate (%/second the fan ramps toward the curve); clamped to the editor range. */
    suspend fun persistFanResponseStep(step: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[fanResponseKey] = step.coerceIn(FanCurve.MIN_SLEW, FanCurve.MAX_SLEW)
        }
    }

    /** Persist the Cooler/Quieter bias (% offset, ±[FanCurve.MAX_BIAS]); + = cooler/louder, − = quieter. */
    suspend fun persistFanBias(bias: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[fanBiasKey] = bias.coerceIn(-FanCurve.MAX_BIAS, FanCurve.MAX_BIAS)
        }
    }

    /** Persist whether the Smart (closed-loop temp-target) Custom fan controller is enabled vs the manual curve. */
    suspend fun persistFanSmartEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[fanSmartKey] = enabled }
    }

    /** Persist the Smart-mode target SoC temperature (°C), clamped to the controller's selectable band. */
    suspend fun persistFanTargetTemp(tempC: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[fanTargetTempKey] = tempC.coerceIn(FanTempController.TARGET_MIN_C, FanTempController.TARGET_MAX_C)
        }
    }

    suspend fun persistOverlayOpacity(opacity: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[overlayOpacityKey] = opacity.coerceIn(40, 100)
        }
    }

    suspend fun persistOverlayPosition(x: Int, y: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[overlayPosXKey] = x
            preferences[overlayPosYKey] = y
        }
    }

    suspend fun persistLearnedPeakW(watts: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[learnedPeakKey] = watts
        }
    }

    suspend fun persistAutoTdpDefaultEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[autoTdpDefaultKey] = enabled
        }
    }

    suspend fun persistAutoTdpFpsTarget(target: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[autoTdpFpsTargetKey] = target
        }
    }

    suspend fun persistAutoTdpAggressivePark(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[autoTdpAggressiveParkKey] = enabled
        }
    }

    suspend fun persistAutoTdpBias(bias: AutoTdpBias) {
        context.settingsDataStore.edit { preferences -> preferences[autoTdpBiasKey] = bias.name }
    }

    private fun parseAutoTdpBias(raw: String): AutoTdpBias =
        runCatching { AutoTdpBias.valueOf(raw) }.getOrDefault(AutoTdpBias.EFFICIENT)

    /** The learned power model for [soc]; a fresh model when none is stored or the SoC changed. */
    suspend fun loadPowerModel(soc: String): PowerModel {
        val raw = context.settingsDataStore.data.first()[powerModelKey] ?: return PowerModel(soc = soc)
        val model = runCatching { powerModelJson.decodeFromString<PowerModel>(raw) }.getOrNull()
        return if (model != null && model.soc == soc) model else PowerModel(soc = soc)
    }

    suspend fun persistPowerModel(model: PowerModel) {
        withContext(NonCancellable) {
            context.settingsDataStore.edit { preferences ->
                preferences[powerModelKey] = powerModelJson.encodeToString(model)
            }
        }
    }

    /** A previous session's converged AutoTDP caps (policy id → percent) for [pkg]; null if none. */
    suspend fun loadAutoTdpCaps(pkg: String): Map<Int, Int>? {
        val raw = context.settingsDataStore.data.first()[autoTdpCapsKey] ?: return null
        val all = runCatching {
            powerModelJson.decodeFromString<Map<String, Map<String, Int>>>(raw)
        }.getOrNull() ?: return null
        return all[pkg]
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
            ?.toMap()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Packages with a persisted AutoTDP operating point (warm-start available) — drives the per-app "tuned" badge. */
    val autoTdpLearnedPackages: Flow<Set<String>> =
        context.settingsDataStore.data.map { prefs ->
            prefs[autoTdpCapsKey]?.let { raw ->
                runCatching {
                    powerModelJson.decodeFromString<Map<String, Map<String, Int>>>(raw).keys
                }.getOrNull()
            }.orEmpty()
        }

    /** Remember [caps] (policy id → percent) as [pkg]'s AutoTDP warm-start point for next launch. */
    suspend fun persistAutoTdpCaps(pkg: String, caps: Map<Int, Int>) {
        withContext(NonCancellable) {
            context.settingsDataStore.edit { preferences ->
                val current = preferences[autoTdpCapsKey]?.let { raw ->
                    runCatching {
                        powerModelJson.decodeFromString<Map<String, Map<String, Int>>>(raw)
                    }.getOrNull()
                }.orEmpty().toMutableMap()
                current[pkg] = caps.mapKeys { it.key.toString() }
                preferences[autoTdpCapsKey] = powerModelJson.encodeToString(current)
            }
        }
    }

    /** Reactive learned model for the EST PK readout (null when none stored yet). */
    val powerModel: Flow<PowerModel?> = context.settingsDataStore.data.map { preferences ->
        preferences[powerModelKey]?.let { raw ->
            runCatching { powerModelJson.decodeFromString<PowerModel>(raw) }.getOrNull()
        }
    }

    val customTuning: Flow<CustomTuning> = context.settingsDataStore.data.map { preferences ->
        CustomTuning(
            powerTargetEnabled = preferences[customPtEnabledKey] ?: false,
            powerTargetPercent = preferences[customPtPercentKey] ?: 100,
            powerTargetCpuOnly = preferences[customPtCpuOnlyKey] ?: false,
            gpuLocked = preferences[customGpuLockedKey] ?: false,
            gpuFloorPercent = preferences[customGpuFloorKey] ?: 0,
            cpuFloorPercent = preferences[customCpuFloorKey] ?: 0,
            primeCoreBoostLimited = preferences[customPrimeBoostKey] ?: false,
            governorLabel = preferences[customGovernorLabelKey],
        )
    }

    suspend fun persistCustomTuning(tuning: CustomTuning) {
        withContext(NonCancellable) { context.settingsDataStore.edit { preferences ->
            preferences[customPtEnabledKey] = tuning.powerTargetEnabled
            preferences[customPtPercentKey] = tuning.powerTargetPercent
            preferences[customPtCpuOnlyKey] = tuning.powerTargetCpuOnly
            preferences[customGpuLockedKey] = tuning.gpuLocked
            preferences[customGpuFloorKey] = tuning.gpuFloorPercent
            preferences[customCpuFloorKey] = tuning.cpuFloorPercent
            preferences[customPrimeBoostKey] = tuning.primeCoreBoostLimited
            // Preferences DataStore can't hold null — remove the key when no governor is remembered.
            tuning.governorLabel
                ?.let { preferences[customGovernorLabelKey] = it }
                ?: preferences.remove(customGovernorLabelKey)
        } }
    }

    suspend fun persistActiveTierLabel(label: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[activeTierKey] = label
        }
    }

    /** The fan mode PULSE holds globally (vs the system Fan tile). null clears it (PULSE releases the fan). */
    suspend fun persistManagedFanMode(mode: Int?) {
        context.settingsDataStore.edit { preferences ->
            if (mode == null) preferences.remove(managedFanModeKey) else preferences[managedFanModeKey] = mode
        }
    }

    suspend fun persistTileTapBehavior(behavior: TileInteractionBehavior) {
        context.settingsDataStore.edit { preferences ->
            preferences[tileTapBehaviorKey] = behavior.name
        }
    }

    suspend fun persistApplyLastProfileOnBoot(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[applyLastProfileOnBootKey] = enabled
        }
    }

    suspend fun persistSleepProfileEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[sleepProfileEnabledKey] = enabled
        }
    }

    suspend fun persistSleepProfileId(profileId: String?) {
        context.settingsDataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(sleepProfileIdKey)
            } else {
                preferences[sleepProfileIdKey] = profileId
            }
        }
    }

    suspend fun persistSleepProfile(enabled: Boolean, profileId: String?) {
        context.settingsDataStore.edit { preferences ->
            preferences[sleepProfileEnabledKey] = enabled
            if (profileId == null) {
                preferences.remove(sleepProfileIdKey)
            } else {
                preferences[sleepProfileIdKey] = profileId
            }
        }
    }

    suspend fun persistQuickSettingsTilePromptShown() {
        context.settingsDataStore.edit { preferences ->
            preferences[quickSettingsTilePromptShownKey] = true
        }
    }

    suspend fun persistBatteryOptPromptShown() {
        context.settingsDataStore.edit { preferences ->
            preferences[batteryOptPromptShownKey] = true
        }
    }

    suspend fun persistQuickSettingsTileAdded(isAdded: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[quickSettingsTileAddedKey] = isAdded
        }
    }

    suspend fun persistThemeId(themeId: PulseThemeId) {
        context.settingsDataStore.edit { preferences ->
            preferences[themeIdKey] = themeId.name
        }
    }

    suspend fun persistColorSource(colorSource: AppColorSource) {
        context.settingsDataStore.edit { preferences ->
            preferences[colorSourceKey] = colorSource.name
        }
    }

    suspend fun persistAccentColor(accentColor: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[accentColorKey] = accentColor
        }
    }

    suspend fun persistTuningState(
        powerTargetEnabled: Boolean,
        powerTargetPercent: Int,
        powerTargetCpuOnly: Boolean,
        gpuLocked: Boolean,
        gpuFloorPercent: Int,
        cpuFloorPercent: Int,
        activeTierLabel: String,
        primeCoreBoostLimited: Boolean,
    ) {
        withContext(NonCancellable) { context.settingsDataStore.edit { preferences ->
            preferences[powerTargetEnabledKey] = powerTargetEnabled
            preferences[powerTargetPercentKey] = powerTargetPercent
            preferences[powerTargetCpuOnlyKey] = powerTargetCpuOnly
            preferences[gpuLockedKey] = gpuLocked
            preferences[gpuFloorPercentKey] = gpuFloorPercent
            preferences[cpuFloorPercentKey] = cpuFloorPercent
            preferences[activeTierKey] = activeTierLabel
            preferences[primeCoreBoostKey] = primeCoreBoostLimited
        } }
    }

    private fun parseBehavior(raw: String): TileInteractionBehavior {
        return runCatching { TileInteractionBehavior.valueOf(raw) }
            .getOrDefault(TileInteractionBehavior.SHOW_DIALOG)
    }

    private fun parseThemeId(raw: String): PulseThemeId =
        when (raw) {
            // "The Grid" was replaced by Ad Astra — keep anyone who had it selected on the successor.
            "GRID" -> PulseThemeId.ADASTRA
            else -> runCatching { PulseThemeId.valueOf(raw) }.getOrDefault(PulseThemeId.SIGNAL)
        }

    private fun parseColorSource(raw: String): AppColorSource {
        return runCatching { AppColorSource.valueOf(raw) }
            .getOrDefault(AppColorSource.SYSTEM)
    }

    private fun parseOverlayPreset(raw: String): OverlayPreset =
        runCatching { OverlayPreset.valueOf(raw) }.getOrDefault(OverlayPreset.COMPACT)

    /** Comma-joined names → element set, skipping any name a newer build renamed/removed (never throws). */
    private fun parseOverlayElements(raw: String): Set<OverlayElement> =
        raw.split(",")
            .mapNotNull { name -> runCatching { OverlayElement.valueOf(name.trim()) }.getOrNull() }
            .toSet()
}
