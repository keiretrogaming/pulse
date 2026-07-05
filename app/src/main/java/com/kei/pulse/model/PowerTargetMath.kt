package com.kei.pulse.model

import kotlin.math.abs

/**
 * The Power Target's %→frequency-map computation — the SINGLE source shared by the in-app Custom slider
 * (TunerViewModel.applyPowerTargetValues) and the Quick Access bar's live apply (ForegroundAppMonitorService,
 * where a ViewModel isn't constructable). Pure: policies in, per-policy snapped target frequencies out; both
 * surfaces then feed the result to the same PerformanceRepository.applyValues(persistAsCustom = true).
 */
object PowerTargetMath {

    /**
     * Per-policy target frequencies for [percent] of each policy's selectable max, snapped to the nearest
     * supported step. [cpuOnly] leaves the GPU at its full range (top supported frequency) instead of capping
     * it. Ties snap to the FIRST minimum (the lower frequency) — keep it that way so both surfaces stay
     * bit-identical.
     */
    fun capsForPercent(policies: List<CpuPolicyInfo>, percent: Int, cpuOnly: Boolean): Map<Int, Int> =
        policies.associate { policy ->
            if (cpuOnly && policy.isGpu) {
                // CPU-only: restore GPU to full range (up to max, no PT cap)
                policy.id to (policy.supportedFrequencies.lastOrNull() ?: policy.selectableMaxFreq)
            } else {
                val target = policy.selectableMaxFreq * percent / 100
                val snapped = policy.supportedFrequencies.minByOrNull { abs(it - target) }
                    ?: policy.selectableMaxFreq
                policy.id to snapped
            }
        }
}
