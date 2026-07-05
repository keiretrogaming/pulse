package com.kei.pulse.root

import com.kei.pulse.model.CpuPolicyInfo

class PerformanceCommandBuilder {

    /**
     * @param lowerMinPolicyIds CPU clusters whose `scaling_min_freq` should ALSO be written down to their
     *   floor (before the max). This is **per-cluster on purpose**. The vendor perf HAL pins the *prime*
     *   cluster's min high during gaming (~3 GHz), so without lowering its min the kernel rejects any lower
     *   `scaling_max` (max < min) and the prime never drops. BUT writing a cluster's `scaling_min` wakes the
     *   HAL, which re-asserts that cluster's OPP — harmless for the prime (we want it pinned low) but it
     *   stomps the *perf* cluster's `scaling_max` back up. So AutoTDP passes ONLY the prime here: prime min
     *   drops (prime can be capped) while the perf cluster's min is left alone (its cap keeps biting). On
     *   reset, pass every CPU id (writable `644`) to hand min control back to the HAL and clear stale locks.
     */
    fun buildApplyScript(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        lowerMinPolicyIds: Set<Int> = emptySet(),
    ): String {
        val lines = mutableListOf<String>()
        val targetMode = if (isReset) "644" else "444"

        policies.forEach { policy ->
            val value = selectedValues[policy.id] ?: return@forEach
            if (policy.isGpu) {
                appendGpuLevel(lines, policy, value)
            } else {
                // Never echo a non-positive frequency to a CPU freq node — the kernel rejects it / the result
                // is undefined. A 0/negative value here means malformed detection (or a reset/uninstall edge);
                // skip the cluster entirely rather than write garbage.
                if (value <= 0) return@forEach
                if (policy.id in lowerMinPolicyIds) {
                    val floor = policy.supportedFrequencies.minOrNull() ?: policy.minFreq
                    if (floor > 0) write(lines, "${policy.policyPath}/scaling_min_freq", floor.toLong(), targetMode)
                }
                write(lines, policy.scalingMaxPath, value.toLong(), targetMode)
            }
        }

        return buildString {
            appendLine("#!/system/bin/sh")
            lines.forEach(::appendLine)
        }
    }

    /**
     * Adreno is capped by power-level INDEX (fastest = 0). The kernel clamps `max_pwrlevel`
     * so it can never be a higher index (slower level) than `min_pwrlevel`. If the device's
     * default `min_pwrlevel` sits above our target the cap silently snaps back — which is
     * why only level 0 (uncapped) "stuck" before. So we widen `min_pwrlevel` to the slowest
     * level first (full downscale headroom), then set the ceiling.
     */
    private fun appendGpuLevel(
        lines: MutableList<String>,
        policy: CpuPolicyInfo,
        valueKHz: Int,
    ) {
        val maxLevel = gpuPowerLevelFor(policy, valueKHz)
        val slowestLevel = (policy.supportedFrequencies.size - 1).coerceAtLeast(maxLevel)
        val minPath = "${policy.policyPath}/min_pwrlevel"
        // Always lock the GPU bounds read-only (444). On the Max tier (isReset) the CPU nodes
        // are intentionally left writable for stock behaviour, but doing the same for the GPU
        // lets the vendor performance daemon stomp min_pwrlevel back up and floor the GPU
        // mid-range (~900MHz). Locking min=slowest / max=ceiling keeps it free to idle down
        // and scale up to its cap.
        write(lines, minPath, slowestLevel.toLong(), "444")
        write(lines, policy.scalingMaxPath, maxLevel.toLong(), "444")
    }

    private fun write(lines: MutableList<String>, path: String, value: Long, mode: String) {
        lines += "chmod 666 $path"
        lines += "echo $value > $path"
        lines += "chmod $mode $path"
    }

    /** ascending kHz table -> power level (fastest = 0). */
    private fun gpuPowerLevelFor(policy: CpuPolicyInfo, valueKHz: Int): Int {
        val asc = policy.supportedFrequencies
        if (asc.isEmpty()) return 0
        val pos = asc.indexOf(valueKHz).let { exact ->
            if (exact >= 0) exact else asc.indices.minByOrNull { kotlin.math.abs(asc[it] - valueKHz) } ?: asc.lastIndex
        }
        return (asc.size - 1 - pos).coerceIn(0, asc.lastIndex)
    }
}
