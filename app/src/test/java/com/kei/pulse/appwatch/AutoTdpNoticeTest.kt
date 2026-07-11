package com.kei.pulse.appwatch

import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTdpNoticeTest {
    private val global = AppSettings(
        autoTdpFpsTarget = 60,
        autoTdpAggressivePark = true,
        autoTdpBias = AutoTdpBias.EFFICIENT,
    )

    @Test
    fun `includes every AutoTDP value that differs from global`() {
        val config = PerAppConfig(
            packageName = "game",
            profileBinding = PerAppConfig.AUTO_BINDING,
            fpsTarget = 30,
            aggressivePark = false,
            bias = AutoTdpBias.SMOOTH,
        )

        val notice = AutoTdpNotice.text("Game", config, global)

        assertEquals("Game: AutoTDP", notice.compact)
        assertEquals("Game: AutoTDP  ◆ FPS target › 30  ◆ Aggressive park › Off  ◆ Efficiency › Smooth", notice.expanded)
    }

    @Test
    fun `omits inherited and global-matching AutoTDP values`() {
        val inherited = PerAppConfig(
            packageName = "game",
            profileBinding = PerAppConfig.AUTO_BINDING,
        )
        val matching = inherited.copy(
            fpsTarget = 60,
            aggressivePark = true,
            bias = AutoTdpBias.EFFICIENT,
        )

        assertEquals(AutoTdpNoticeText("Game: AutoTDP", "Game: AutoTDP"), AutoTdpNotice.text("Game", inherited, global))
        assertEquals(AutoTdpNoticeText("Game: AutoTDP", "Game: AutoTDP"), AutoTdpNotice.text("Game", matching, global))
    }

    @Test
    fun `labels the legacy uncapped FPS target as Max`() {
        val config = PerAppConfig(
            packageName = "game",
            profileBinding = PerAppConfig.AUTO_BINDING,
            fpsTarget = 0,
        )

        assertEquals(
            AutoTdpNoticeText("Game: AutoTDP", "Game: AutoTDP  ◆ FPS target › Max"),
            AutoTdpNotice.text("Game", config, global),
        )
    }

    @Test
    fun `can suppress override details while keeping the basic notice`() {
        val config = PerAppConfig(
            packageName = "game",
            profileBinding = PerAppConfig.AUTO_BINDING,
            fpsTarget = 30,
            aggressivePark = false,
            bias = AutoTdpBias.SMOOTH,
        )

        assertEquals(
            AutoTdpNoticeText("Game: AutoTDP", "Game: AutoTDP"),
            AutoTdpNotice.text("Game", config, global, includeOverrides = false),
        )
    }

    @Test
    fun `long app labels keep every setting group indivisible`() {
        val config = PerAppConfig(
            packageName = "game",
            profileBinding = PerAppConfig.AUTO_BINDING,
            fpsTarget = 30,
            aggressivePark = false,
            bias = AutoTdpBias.SMOOTH,
        )

        val notice = AutoTdpNotice.text(
            "A Very Long Game Name That Uses Most Of The Available Notification Width",
            config,
            global,
        )
        val settingGroups = notice.expanded.split("  ◆ ").drop(1)

        assertEquals(3, settingGroups.size)
        assertEquals(listOf("FPS target › 30", "Aggressive park › Off", "Efficiency › Smooth"), settingGroups)
        settingGroups.forEach { group -> assertEquals(-1, group.indexOf(' ')) }
    }
}
