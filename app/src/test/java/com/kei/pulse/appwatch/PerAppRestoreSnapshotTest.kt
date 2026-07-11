package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppRestoreSnapshotTest {

    @Test
    fun `first app without extras still snapshots controls a later app can change`() {
        val firstApp = PerAppConfig(
            packageName = "game.one",
            profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED),
        )
        var fanRead = false
        var refreshRead = false

        val snapshot = captureInitialRestoreState(
            firstConfig = firstApp,
            values = mapOf(0 to 2_000_000),
            activeTierLabel = "Custom",
            readFanMode = { fanRead = true; 5 },
            readRefreshRate = { refreshRead = true; 120 },
            governor = "schedutil",
        )

        assertTrue("pre-game fan mode must be captured", fanRead)
        assertTrue("pre-game refresh rate must be captured", refreshRead)
        assertEquals(5, snapshot.fanMode)
        assertEquals(120, snapshot.refreshRateHz)
    }
}
