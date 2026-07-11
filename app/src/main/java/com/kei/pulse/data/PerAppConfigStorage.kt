package com.kei.pulse.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PerAppRestoreState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.perAppDataStore by preferencesDataStore(name = "pulse_per_app")

class PerAppConfigStorage(private val context: Context) {

    private val enabledKey = booleanPreferencesKey("per_app_enabled")
    private val configsKey = stringPreferencesKey("per_app_configs")
    private val restoreKey = stringPreferencesKey("per_app_restore")
    private val switchNoticesKey = booleanPreferencesKey("per_app_switch_notices")
    private val switchNoticeDetailsKey = booleanPreferencesKey("per_app_switch_notice_details")
    private val batteryWhKey = floatPreferencesKey("battery_capacity_wh")
    private val json = Json { ignoreUnknownKeys = true }

    /** Full-battery energy (Wh) captured from sysfs; 0 = not yet read. */
    val batteryCapacityWh: Flow<Float> = context.perAppDataStore.data.map { preferences ->
        preferences[batteryWhKey] ?: 0f
    }

    suspend fun persistBatteryCapacityWh(wh: Float) {
        context.perAppDataStore.edit { preferences ->
            preferences[batteryWhKey] = wh
        }
    }

    /** Records measured draw (peak + smoothed average) for a configured app. */
    suspend fun updateMeasuredDraw(packageName: String, peakW: Float, avgW: Float) {
        context.perAppDataStore.edit { preferences ->
            val current = preferences[configsKey]
                ?.let { raw -> runCatching { json.decodeFromString<List<PerAppConfig>>(raw) }.getOrNull() }
                .orEmpty()
            preferences[configsKey] = json.encodeToString(
                current.map {
                    if (it.packageName == packageName) {
                        it.copy(measuredPeakW = peakW, measuredAvgW = avgW)
                    } else {
                        it
                    }
                },
            )
        }
    }

    val enabled: Flow<Boolean> = context.perAppDataStore.data.map { preferences ->
        preferences[enabledKey] ?: false
    }

    /** Whether the watcher shows a toast + notification update on every profile switch. */
    val switchNotices: Flow<Boolean> = context.perAppDataStore.data.map { preferences ->
        preferences[switchNoticesKey] ?: true
    }

    suspend fun persistSwitchNotices(enabled: Boolean) {
        context.perAppDataStore.edit { preferences ->
            preferences[switchNoticesKey] = enabled
        }
    }

    /** Whether AutoTDP switch notices include the per-app values that override global defaults. */
    val switchNoticeDetails: Flow<Boolean> = context.perAppDataStore.data.map { preferences ->
        preferences[switchNoticeDetailsKey] ?: true
    }

    suspend fun persistSwitchNoticeDetails(enabled: Boolean) {
        context.perAppDataStore.edit { preferences ->
            preferences[switchNoticeDetailsKey] = enabled
        }
    }

    val configs: Flow<List<PerAppConfig>> = context.perAppDataStore.data.map { preferences ->
        preferences[configsKey]
            ?.let { raw -> runCatching { json.decodeFromString<List<PerAppConfig>>(raw) }.getOrNull() }
            .orEmpty()
    }

    val restoreState: Flow<PerAppRestoreState?> = context.perAppDataStore.data.map { preferences ->
        preferences[restoreKey]
            ?.let { raw -> runCatching { json.decodeFromString<PerAppRestoreState>(raw) }.getOrNull() }
    }

    suspend fun persistEnabled(enabled: Boolean) {
        context.perAppDataStore.edit { preferences ->
            preferences[enabledKey] = enabled
        }
    }

    /** Upserts by package name; a config with no bindings is treated as a removal. */
    suspend fun saveConfig(config: PerAppConfig) {
        context.perAppDataStore.edit { preferences ->
            val current = preferences[configsKey]
                ?.let { raw -> runCatching { json.decodeFromString<List<PerAppConfig>>(raw) }.getOrNull() }
                .orEmpty()
                .filterNot { it.packageName == config.packageName }
            val updated = if (config.hasAnyBinding) current + config else current
            preferences[configsKey] = json.encodeToString(updated)
        }
    }

    suspend fun removeConfig(packageName: String) {
        context.perAppDataStore.edit { preferences ->
            val current = preferences[configsKey]
                ?.let { raw -> runCatching { json.decodeFromString<List<PerAppConfig>>(raw) }.getOrNull() }
                .orEmpty()
            preferences[configsKey] = json.encodeToString(current.filterNot { it.packageName == packageName })
        }
    }

    suspend fun persistRestoreState(state: PerAppRestoreState?) {
        context.perAppDataStore.edit { preferences ->
            if (state == null) {
                preferences.remove(restoreKey)
            } else {
                preferences[restoreKey] = json.encodeToString(state)
            }
        }
    }
}
