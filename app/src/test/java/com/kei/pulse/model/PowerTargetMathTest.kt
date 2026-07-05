package com.kei.pulse.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Power Target's %→frequency-map computation, extracted from TunerViewModel so the Quick Access bar's
 * live apply (in the watcher service, where a ViewModel can't be constructed) uses the IDENTICAL math as the
 * in-app slider — a divergence here would be a UI-vs-device split between the two surfaces.
 */
class PowerTargetMathTest {

    private fun cpu(id: Int, max: Int, supported: List<Int>) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy$id",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        currentMaxFreq = max,
        selectableMaxFreq = max,
        observedMaxFreq = max,
        minFreq = supported.first(),
        supportedFrequencies = supported,
    )

    private fun gpu(max: Int, supported: List<Int>) = cpu(CpuPolicyInfo.GPU_POLICY_ID, max, supported)

    @Test
    fun `snaps each policy to the supported frequency nearest the percent target`() {
        val p = cpu(0, 2000000, listOf(600000, 1000000, 1400000, 2000000))
        // 55% of 2.0 GHz = 1.1 GHz → nearest supported is 1.0 GHz.
        assertEquals(mapOf(0 to 1000000), PowerTargetMath.capsForPercent(listOf(p), 55, cpuOnly = false))
    }

    @Test
    fun `one hundred percent selects the top frequency`() {
        val p = cpu(0, 2000000, listOf(600000, 1000000, 2000000))
        assertEquals(mapOf(0 to 2000000), PowerTargetMath.capsForPercent(listOf(p), 100, cpuOnly = false))
    }

    @Test
    fun `cpu-only restores the gpu to its full range while capping cpu`() {
        val c = cpu(0, 2000000, listOf(600000, 1000000, 2000000))
        val g = gpu(900000, listOf(300000, 600000, 900000))
        val values = PowerTargetMath.capsForPercent(listOf(c, g), 50, cpuOnly = true)
        assertEquals(1000000, values[0])
        assertEquals(900000, values[CpuPolicyInfo.GPU_POLICY_ID]) // full range, no PT cap
    }

    @Test
    fun `gpu is capped like a cluster when not cpu-only`() {
        val g = gpu(900000, listOf(300000, 600000, 900000))
        // 50% of 900 MHz = 450 → nearest supported 300 or 600: 450-300=150, 600-450=150 → minByOrNull keeps
        // the first minimum (300000) — pinned so both surfaces stay bit-identical.
        assertEquals(mapOf(CpuPolicyInfo.GPU_POLICY_ID to 300000), PowerTargetMath.capsForPercent(listOf(g), 50, cpuOnly = false))
    }

    @Test
    fun `empty supported list falls back to the selectable max`() {
        val p = cpu(0, 2000000, listOf(600000)).copy(supportedFrequencies = emptyList())
        assertEquals(mapOf(0 to 2000000), PowerTargetMath.capsForPercent(listOf(p), 40, cpuOnly = false))
    }
}
