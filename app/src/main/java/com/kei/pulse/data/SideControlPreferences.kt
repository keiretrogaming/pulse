package com.kei.pulse.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.kei.pulse.model.SideControlState

internal data class SideControlPreferenceKeys(
    val powerTargetEnabled: Preferences.Key<Boolean>,
    val powerTargetPercent: Preferences.Key<Int>,
    val powerTargetCpuOnly: Preferences.Key<Boolean>,
    val gpuLocked: Preferences.Key<Boolean>,
    val gpuFloorPercent: Preferences.Key<Int>,
    val cpuFloorPercent: Preferences.Key<Int>,
    val primeCoreBoostLimited: Preferences.Key<Boolean>,
)

internal val LIVE_SIDE_CONTROL_KEYS = SideControlPreferenceKeys(
    powerTargetEnabled = booleanPreferencesKey("power_target_enabled"),
    powerTargetPercent = intPreferencesKey("power_target_percent"),
    powerTargetCpuOnly = booleanPreferencesKey("power_target_cpu_only"),
    gpuLocked = booleanPreferencesKey("gpu_locked"),
    gpuFloorPercent = intPreferencesKey("gpu_floor_percent"),
    cpuFloorPercent = intPreferencesKey("cpu_floor_percent"),
    primeCoreBoostLimited = booleanPreferencesKey("prime_core_boost_limited"),
)

internal val CUSTOM_SIDE_CONTROL_KEYS = SideControlPreferenceKeys(
    powerTargetEnabled = booleanPreferencesKey("custom_pt_enabled"),
    powerTargetPercent = intPreferencesKey("custom_pt_percent"),
    powerTargetCpuOnly = booleanPreferencesKey("custom_pt_cpu_only"),
    gpuLocked = booleanPreferencesKey("custom_gpu_locked"),
    gpuFloorPercent = intPreferencesKey("custom_gpu_floor_percent"),
    cpuFloorPercent = intPreferencesKey("custom_cpu_floor_percent"),
    primeCoreBoostLimited = booleanPreferencesKey("custom_prime_core_boost"),
)

internal fun Preferences.readSideControls(keys: SideControlPreferenceKeys): SideControlState =
    SideControlState(
        powerTargetEnabled = this[keys.powerTargetEnabled] ?: false,
        powerTargetPercent = this[keys.powerTargetPercent] ?: 100,
        powerTargetCpuOnly = this[keys.powerTargetCpuOnly] ?: false,
        gpuLocked = this[keys.gpuLocked] ?: false,
        gpuFloorPercent = this[keys.gpuFloorPercent] ?: 0,
        cpuFloorPercent = this[keys.cpuFloorPercent] ?: 0,
        primeCoreBoostLimited = this[keys.primeCoreBoostLimited] ?: false,
    )

internal fun MutablePreferences.putSideControls(
    keys: SideControlPreferenceKeys,
    sideControls: SideControlState,
) {
    this[keys.powerTargetEnabled] = sideControls.powerTargetEnabled
    this[keys.powerTargetPercent] = sideControls.powerTargetPercent
    this[keys.powerTargetCpuOnly] = sideControls.powerTargetCpuOnly
    this[keys.gpuLocked] = sideControls.gpuLocked
    this[keys.gpuFloorPercent] = sideControls.gpuFloorPercent
    this[keys.cpuFloorPercent] = sideControls.cpuFloorPercent
    this[keys.primeCoreBoostLimited] = sideControls.primeCoreBoostLimited
}
