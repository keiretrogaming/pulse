package com.kei.pulse.appwatch

import com.kei.pulse.data.FanController
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundBindingPolicyTest {

    @Test
    fun `explicit AutoTDP wins without relying on the global default`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_BINDING)

        assertEquals(
            ForegroundBindingKind.EXPLICIT_AUTOTDP,
            resolveForegroundBinding(config, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = false),
        )
    }

    @Test
    fun `explicit AutoTDP off blocks the global default`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_OFF_BINDING)

        assertEquals(
            ForegroundBindingKind.NONE,
            resolveForegroundBinding(config, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
    }

    @Test
    fun `explicit off with hardware extras still binds without enabling AutoTDP`() {
        val fanOnly = PerAppConfig(
            "game",
            profileBinding = PerAppConfig.AUTO_OFF_BINDING,
            fanMode = FanController.SPORT,
        )
        val refreshOnly = PerAppConfig(
            "game",
            profileBinding = PerAppConfig.AUTO_OFF_BINDING,
            refreshRateHz = 60,
        )

        assertEquals(
            ForegroundBindingKind.EXPLICIT_CONFIG,
            resolveForegroundBinding(fanOnly, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            ForegroundBindingKind.EXPLICIT_CONFIG,
            resolveForegroundBinding(refreshOnly, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
    }

    @Test
    fun `ordinary per-app profiles are explicit bindings`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED))

        assertEquals(
            ForegroundBindingKind.EXPLICIT_CONFIG,
            resolveForegroundBinding(config, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = false),
        )
    }

    @Test
    fun `global AutoTDP requires both the toggle and an eligible target`() {
        assertEquals(
            ForegroundBindingKind.GLOBAL_AUTOTDP,
            resolveForegroundBinding(null, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            ForegroundBindingKind.NONE,
            resolveForegroundBinding(null, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            ForegroundBindingKind.NONE,
            resolveForegroundBinding(null, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = false),
        )
    }
}
