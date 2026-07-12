package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.SideControlState
import com.kei.pulse.model.toSideControlState
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickAccessPowerTargetPersistenceTest {
    @Test
    fun `power target edit preserves saved Custom controls cleared by a live preset`() {
        val savedCustom = CustomTuning(
            sideControls = SideControlState(
                powerTargetEnabled = false,
                powerTargetPercent = 100,
                powerTargetCpuOnly = true,
                gpuLocked = true,
                gpuFloorPercent = 41,
                cpuFloorPercent = 29,
                primeCoreBoostLimited = true,
            ),
            governorLabel = "Performance",
        )
        val livePreset = AppSettings(
            powerTargetEnabled = false,
            powerTargetPercent = 100,
            powerTargetCpuOnly = false,
            gpuLocked = false,
            gpuFloorPercent = 0,
            cpuFloorPercent = 0,
            primeCoreBoostLimited = false,
            activeTierLabel = "Balanced",
        )

        val result = resolveQuickAccessPowerTargetPersistence(
            settings = livePreset,
            customTuning = savedCustom,
            percent = 73,
            enabled = true,
        )

        assertEquals(
            livePreset.toSideControlState().copy(powerTargetEnabled = true, powerTargetPercent = 73),
            result.liveSideControls,
        )
        assertEquals(
            savedCustom.sideControls.copy(powerTargetEnabled = true, powerTargetPercent = 73),
            result.customTuning.sideControls,
        )
        assertEquals("Performance", result.customTuning.governorLabel)
    }
}
