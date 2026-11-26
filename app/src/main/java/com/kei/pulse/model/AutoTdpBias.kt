package com.kei.pulse.model

import com.kei.pulse.i18n.PulseStrings
import kotlinx.serialization.Serializable

/**
 * AutoTDP's efficiency↔smoothness lean — the single user-facing lever behind efficiency, wattage, and fan
 * noise. It scales how aggressively the controller harvests vs. holds clocks:
 *  - [EFFICIENT] (default): harvest hard when play is genuinely smooth; only protect frames on a *sustained*
 *    real stutter, not emulator jitter. Lowest watts + quietest.
 *  - [SMOOTH]: the conservative, stutter-averse behavior (keeps clocks higher).
 *  - [BALANCED]: between the two.
 *
 * The scaling lives in [com.kei.pulse.data.AutoTuneController] (the margin gate + harvest deadband); this
 * enum only carries the choice. Stored as the global default ([AppSettings.autoTdpBias]) with an optional
 * per-app override ([PerAppConfig.bias]); [resolve] applies the per-app-first precedence.
 */
@Serializable
enum class AutoTdpBias(val label: String) {
    EFFICIENT("Efficient"),
    BALANCED("Balanced"),
    SMOOTH("Smooth"),
    ;

    fun localizedLabel(strings: PulseStrings): String = when (this) {
        EFFICIENT -> strings.autoTdpBiasEfficient
        BALANCED -> strings.autoTdpBiasBalanced
        SMOOTH -> strings.autoTdpBiasSmooth
    }

    fun localizedDesc(strings: PulseStrings): String = when (this) {
        EFFICIENT -> strings.autoTdpBiasDescEfficient
        BALANCED -> strings.autoTdpBiasDescBalanced
        SMOOTH -> strings.autoTdpBiasDescSmooth
    }

    companion object {
        /** Per-app bias wins; a null per-app inherits the [global] default. */
        fun resolve(perApp: AutoTdpBias?, global: AutoTdpBias): AutoTdpBias = perApp ?: global
    }
}
