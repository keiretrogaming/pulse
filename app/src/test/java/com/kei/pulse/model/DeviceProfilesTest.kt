package com.kei.pulse.model

import com.kei.pulse.data.AutoTuneController
import com.kei.pulse.data.FanController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every per-device invariant row AND asserts the legacy consumers agree with the table — so a device
 * fact can never again live (and silently diverge) in two places. The bug class: an assumption true on the
 * Odin, false on the Thor/RP6, baked into imperative code.
 */
class DeviceProfilesTest {

    @Test
    fun `soc resolution maps the three known devices and falls back conservatively`() {
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.forSoc("CQ8725S"))
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.forSoc(" cq8725s ")) // trims + case-insensitive
        assertEquals(DeviceProfiles.SD8GEN2, DeviceProfiles.forSoc("QCS8550"))
        assertEquals(DeviceProfiles.UNKNOWN, DeviceProfiles.forSoc(null))
        assertEquals(DeviceProfiles.UNKNOWN, DeviceProfiles.forSoc("SM8650"))
    }

    @Test
    fun `odin row - floored prime, game-mode cap, no 90hz target, watt tuning on`() {
        val p = DeviceProfiles.ODIN3
        assertTrue(p.primeIsVendorFloored)
        assertTrue(p.honorsGameModeFpsCap)
        assertEquals(listOf(30, 60, 120), p.fpsTargetOptions)
        assertFalse("the Odin panel has no 90 Hz mode — Android floors a 90 cap to 60", 90 in p.fpsTargetOptions)
        assertTrue(p.appliesOdinPowerTuning)
        assertEquals(FanController.SMART, p.fanReleaseMode)
    }

    @Test
    fun `sd 8 gen 2 row - scaling prime, refresh path with 90hz, watt tuning off`() {
        val p = DeviceProfiles.SD8GEN2
        assertFalse("the 8 Gen 2 prime genuinely scales — the Odin watt cap/settle must not apply", p.primeIsVendorFloored)
        assertFalse(p.honorsGameModeFpsCap)
        assertEquals(listOf(60, 90, 120), p.fpsTargetOptions)
        assertFalse(p.appliesOdinPowerTuning)
        assertEquals(FanController.SMART, p.fanReleaseMode)
    }

    @Test
    fun `unknown device gets conservative defaults`() {
        val p = DeviceProfiles.UNKNOWN
        assertFalse(p.primeIsVendorFloored)
        assertFalse(p.honorsGameModeFpsCap)
        assertFalse(p.appliesOdinPowerTuning)
        assertEquals(FanController.SMART, p.fanReleaseMode)
    }

    // ── Consumers must agree with the table (no second copy of a device fact) ───────────────────────

    @Test
    fun `perappconfig game-mode-cap and fps targets delegate to the table`() {
        for (soc in listOf("CQ8725S", "QCS8550", null, "SM8650")) {
            assertEquals(soc ?: "null", DeviceProfiles.forSoc(soc).honorsGameModeFpsCap, PerAppConfig.isGameModeCapSoc(soc))
            assertEquals(soc ?: "null", DeviceProfiles.forSoc(soc).fpsTargetOptions, PerAppConfig.fpsTargetsFor(soc))
        }
    }

    @Test
    fun `autotune odin power tuning delegates to the table`() {
        for (soc in listOf("CQ8725S", "QCS8550", null, "SM8650")) {
            assertEquals(
                soc ?: "null",
                DeviceProfiles.forSoc(soc).appliesOdinPowerTuning,
                AutoTuneController.appliesOdinPowerTuning(soc),
            )
        }
    }
}
