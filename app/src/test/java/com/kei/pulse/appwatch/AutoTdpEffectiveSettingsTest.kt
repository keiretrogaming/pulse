package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTdpEffectiveSettingsTest {

    private val global = AppSettings(
        autoTdpFpsTarget = 60,
        autoTdpAggressivePark = true,
        autoTdpBias = AutoTdpBias.EFFICIENT,
    )

    @Test
    fun `null per-app values inherit every global AutoTDP setting`() {
        val resolved = resolveAutoTdpSettings(
            config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_BINDING),
            global = global,
            soc = "CQ8725S",
            maxRefreshRate = 120,
        )

        assertEquals(60, resolved.targetFps)
        assertEquals(true, resolved.aggressivePark)
        assertEquals(AutoTdpBias.EFFICIENT, resolved.bias)
        assertEquals(AutoTdpEffectiveSettings.FrameRatePath.GAME_MODE_CAP, resolved.frameRatePath)
    }

    @Test
    fun `per-app values override all global AutoTDP settings together`() {
        val resolved = resolveAutoTdpSettings(
            config = PerAppConfig(
                packageName = "game",
                profileBinding = PerAppConfig.AUTO_BINDING,
                fpsTarget = 30,
                aggressivePark = false,
                bias = AutoTdpBias.SMOOTH,
            ),
            global = global,
            soc = "CQ8725S",
            maxRefreshRate = 120,
        )

        assertEquals(30, resolved.targetFps)
        assertEquals(false, resolved.aggressivePark)
        assertEquals(AutoTdpBias.SMOOTH, resolved.bias)
    }

    @Test
    fun `legacy target is snapped before choosing the enforcement path`() {
        val resolved = resolveAutoTdpSettings(
            config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_BINDING, fpsTarget = 90),
            global = global,
            soc = "CQ8725S",
            maxRefreshRate = 120,
        )

        assertEquals(60, resolved.targetFps)
        assertEquals(AutoTdpEffectiveSettings.FrameRatePath.GAME_MODE_CAP, resolved.frameRatePath)
    }

    @Test
    fun `refresh-only devices never select the game-mode cap`() {
        val resolved = resolveAutoTdpSettings(
            config = null,
            global = global.copy(autoTdpFpsTarget = 90),
            soc = "QCS8550",
            maxRefreshRate = 120,
        )

        assertEquals(90, resolved.targetFps)
        assertEquals(AutoTdpEffectiveSettings.FrameRatePath.REFRESH_RATE, resolved.frameRatePath)
    }
}
