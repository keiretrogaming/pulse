package com.kei.pulse.root

import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The apply-script builder is the single chokepoint that writes CPU `scaling_max/min_freq`. A non-positive
 * frequency (e.g. a malformed `selectableMaxFreq=0` from detection, or a reset/uninstall edge) must NEVER be
 * echoed to a sysfs freq node — the kernel rejects it / the result is undefined. Guard: skip the write for any
 * value ≤ 0; valid values are emitted unchanged.
 */
class PerformanceCommandBuilderTest {

    private fun cpu(
        id: Int = 0,
        supported: List<Int> = listOf(300_000, 1_500_000, 3_000_000),
        minFreq: Int = 300_000,
    ) = CpuPolicyInfo(
        id = id,
        policyPath = "/p$id",
        scalingMaxPath = "/p$id/scaling_max_freq",
        currentMaxFreq = 3_000_000,
        selectableMaxFreq = 3_000_000,
        observedMaxFreq = 3_000_000,
        minFreq = minFreq,
        supportedFrequencies = supported,
    )

    private val builder = PerformanceCommandBuilder()

    @Test fun `emits the scaling_max write for a valid value`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 1_500_000), isReset = false)
        assertTrue(script.contains("echo 1500000 > /p0/scaling_max_freq"))
    }

    @Test fun `never echoes a zero frequency`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 0), isReset = true)
        assertFalse("a 0 freq must never be written", script.contains("echo 0 > /p0/scaling_max_freq"))
    }

    @Test fun `never echoes a negative frequency`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to -1), isReset = false)
        assertFalse(script.contains("echo -1 > /p0/scaling_max_freq"))
    }

    @Test fun `skips a non-positive scaling_min floor`() {
        // floor = supportedFrequencies.min ?: minFreq → 0 here; must not be echoed to scaling_min_freq.
        val policy = cpu(supported = emptyList(), minFreq = 0)
        val script = builder.buildApplyScript(
            listOf(policy), mapOf(0 to 1_500_000), isReset = true, lowerMinPolicyIds = setOf(0),
        )
        assertFalse(script.contains("echo 0 > /p0/scaling_min_freq"))
        // the valid max write still happens
        assertTrue(script.contains("echo 1500000 > /p0/scaling_max_freq"))
    }
}
