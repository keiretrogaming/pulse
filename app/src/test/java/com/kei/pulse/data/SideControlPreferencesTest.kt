package com.kei.pulse.data

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.kei.pulse.model.SideControlState
import org.junit.Assert.assertEquals
import org.junit.Test

class SideControlPreferencesTest {
    private val engaged = SideControlState(
        powerTargetEnabled = true,
        powerTargetPercent = 73,
        powerTargetCpuOnly = true,
        gpuLocked = true,
        gpuFloorPercent = 41,
        cpuFloorPercent = 29,
        primeCoreBoostLimited = true,
    )

    @Test
    fun `live and Custom key sets round-trip every side-control field independently`() {
        val preferences = mutablePreferencesOf()
        val savedCustom = engaged.copy(powerTargetPercent = 67, gpuFloorPercent = 35)

        preferences.putSideControls(LIVE_SIDE_CONTROL_KEYS, engaged)
        preferences.putSideControls(CUSTOM_SIDE_CONTROL_KEYS, savedCustom)

        assertEquals(engaged, preferences.readSideControls(LIVE_SIDE_CONTROL_KEYS))
        assertEquals(savedCustom, preferences.readSideControls(CUSTOM_SIDE_CONTROL_KEYS))
    }

    @Test
    fun `missing live and Custom keys restore neutral defaults`() {
        val preferences = emptyPreferences()

        assertEquals(SideControlState.CLEARED, preferences.readSideControls(LIVE_SIDE_CONTROL_KEYS))
        assertEquals(SideControlState.CLEARED, preferences.readSideControls(CUSTOM_SIDE_CONTROL_KEYS))
    }
}
