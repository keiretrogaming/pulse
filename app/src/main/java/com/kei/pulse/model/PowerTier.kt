package com.kei.pulse.model

import com.kei.pulse.i18n.PulseStrings

/**
 * The four PULSE performance tiers. On Snapdragon there is no programmable wattage cap
 * (unlike x86 AYANEO/AYASPACE), so each tier is a *power envelope* implemented by capping
 * the CPU clusters and the GPU to a fraction of their real top frequency. CUSTOM is manual.
 *
 * Factors are applied to each policy's true max frequency and snapped to a real OPP, so
 * the tiers stay valid on every device regardless of its actual frequency table.
 *
 * [governorLabel] is the smart-default CPU governor each preset re-asserts on apply (resolved
 * to a real kernel governor via GovernorController.OPTIONS at apply time; null = leave alone /
 * remembered separately for Custom). Labels match GovernorController.OPTIONS exactly.
 */
enum class PowerTier(
    val label: String,
    val tagline: String,
    val cpuFactor: Double,
    val gpuFactor: Double,
    val governorLabel: String?,
) {
    MAX("AAA / Max", "Full SoC ceiling", 1.0, 1.0, "Performance"),
    BALANCED("Balanced", "Cooler, long sessions", 0.78, 0.70, "Balanced"),
    POWER_SAVING("Power Saving", "Max battery life", 0.55, 0.45, "Power Save"),
    CUSTOM("Custom", "Your own limits", 1.0, 1.0, null);

    fun localizedLabel(strings: PulseStrings): String = when (this) {
        MAX -> strings.tierMaxLabel
        BALANCED -> strings.tierBalancedLabel
        POWER_SAVING -> strings.tierPowerSavingLabel
        CUSTOM -> strings.tierCustomLabel
    }

    fun localizedTagline(strings: PulseStrings): String = when (this) {
        MAX -> strings.tierMaxTagline
        BALANCED -> strings.tierBalancedTagline
        POWER_SAVING -> strings.tierPowerSavingTagline
        CUSTOM -> strings.tierCustomTagline
    }
}
