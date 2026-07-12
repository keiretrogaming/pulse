package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SideControlStateMappingTest {
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
    fun `AppSettings exports every side-control field`() {
        val settings = AppSettings(
            powerTargetEnabled = true,
            powerTargetPercent = 73,
            powerTargetCpuOnly = true,
            gpuLocked = true,
            gpuFloorPercent = 41,
            cpuFloorPercent = 29,
            primeCoreBoostLimited = true,
        )

        assertEquals(engaged, settings.toSideControlState())
    }

    @Test
    fun `AppSettings replaces every side-control field and preserves unrelated settings`() {
        val before = AppSettings(accentColor = 0x12345678, activeTierLabel = "Balanced")
        val after = before.withSideControls(engaged)

        assertEquals(engaged, after.toSideControlState())
        assertEquals(before.accentColor, after.accentColor)
        assertEquals(before.activeTierLabel, after.activeTierLabel)
    }

    @Test
    fun `neutral SideControlState matches the cleared alias`() {
        assertEquals(SideControlState.CLEARED, SideControlState())
    }
}
