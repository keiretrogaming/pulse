package com.kei.pulse.model

import com.kei.pulse.data.FanController
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTdpBindingPolicyTest {

    @Test
    fun `explicit AutoTDP wins without relying on the global default`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_BINDING)

        assertEquals(
            AutoTdpBindingKind.EXPLICIT_AUTOTDP,
            resolveAutoTdpBinding(config, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = false),
        )
    }

    @Test
    fun `explicit AutoTDP off blocks the global default`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_OFF_BINDING)

        assertEquals(
            AutoTdpBindingKind.NONE,
            resolveAutoTdpBinding(config, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
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
            AutoTdpBindingKind.EXPLICIT_CONFIG,
            resolveAutoTdpBinding(fanOnly, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            AutoTdpBindingKind.EXPLICIT_CONFIG,
            resolveAutoTdpBinding(refreshOnly, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
    }

    @Test
    fun `ordinary per-app profiles are explicit bindings`() {
        val config = PerAppConfig("game", profileBinding = PerAppConfig.tierBinding(PowerTier.BALANCED))

        assertEquals(
            AutoTdpBindingKind.EXPLICIT_CONFIG,
            resolveAutoTdpBinding(config, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = false),
        )
    }

    @Test
    fun `fan-only config inherits the global AutoTDP performance binding`() {
        val config = PerAppConfig("game", fanMode = FanController.SPORT)

        assertEquals(
            AutoTdpBindingKind.GLOBAL_AUTOTDP,
            resolveAutoTdpBinding(config, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            AutoTdpBindingKind.EXPLICIT_CONFIG,
            resolveAutoTdpBinding(config, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = true),
        )
    }

    @Test
    fun `global AutoTDP requires both the toggle and an eligible target`() {
        assertEquals(
            AutoTdpBindingKind.GLOBAL_AUTOTDP,
            resolveAutoTdpBinding(null, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            AutoTdpBindingKind.NONE,
            resolveAutoTdpBinding(null, globalAutoTdpEnabled = false, eligibleForGlobalAutoTdp = true),
        )
        assertEquals(
            AutoTdpBindingKind.NONE,
            resolveAutoTdpBinding(null, globalAutoTdpEnabled = true, eligibleForGlobalAutoTdp = false),
        )
    }
}
