package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveAutoTdpValuesTest {

    private val global = AppSettings(
        autoTdpFpsTarget = 60,
        autoTdpAggressivePark = false,
        autoTdpBias = AutoTdpBias.EFFICIENT,
    )

    @Test
    fun `all per-app values override their global counterparts`() {
        val values = resolveEffectiveAutoTdpValues(
            config = PerAppConfig(
                packageName = "game",
                fpsTarget = 120,
                aggressivePark = true,
                bias = AutoTdpBias.SMOOTH,
            ),
            global = global,
        )

        assertEquals(120, values.fpsTarget)
        assertEquals(true, values.aggressivePark)
        assertEquals(AutoTdpBias.SMOOTH, values.bias)
    }

    @Test
    fun `null per-app fields inherit their global counterparts`() {
        val values = resolveEffectiveAutoTdpValues(
            config = PerAppConfig(packageName = "game"),
            global = global,
        )

        assertEquals(60, values.fpsTarget)
        assertEquals(false, values.aggressivePark)
        assertEquals(AutoTdpBias.EFFICIENT, values.bias)
    }

    @Test
    fun `global scope resolves the same values by omitting the per-app config`() {
        assertEquals(
            EffectiveAutoTdpValues(60, false, AutoTdpBias.EFFICIENT),
            resolveEffectiveAutoTdpValues(config = null, global = global),
        )
    }
}
