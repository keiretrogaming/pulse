package com.kei.pulse.model

import kotlinx.serialization.Serializable

/**
 * A per-app binding: when [packageName] comes to the foreground, PULSE applies the bound
 * profile/tier plus any system extras, then restores the pre-launch state when it leaves.
 * Null fields mean "leave that control alone".
 */
@Serializable
data class PerAppConfig(
    val packageName: String,
    val appLabel: String = "",
    /**
     * What to apply: a power tier encoded as "tier:<NAME>" (e.g. "tier:MAX"), or a display
     * profile id (saved profile / [ProfileStateResolver.STOCK_PROFILE_ID]). Null = none.
     */
    val profileBinding: String? = null,
    val fanMode: Int? = null,
    val refreshRateHz: Int? = null,
    /**
     * AutoTDP frame-rate target (fps) for this app: clocks are trimmed to hold this rate. `0` = Max
     * (uncapped — only thermal trims); `null` = inherit the global default. Only used when
     * [profileBinding] is AutoTDP.
     */
    val fpsTarget: Int? = null,
    /**
     * AutoTDP aggressive core parking for this app: offline the prime cluster when it's idle (the only way
     * to cut prime power, since it's vendor-floored mid-game). `null` = inherit the global default. Only
     * used when [profileBinding] is AutoTDP — parking is part of the AutoTDP algorithm.
     */
    val aggressivePark: Boolean? = null,
    /**
     * AutoTDP efficiency↔smoothness lean for this app; `null` = inherit the global default. Only used when
     * [profileBinding] is AutoTDP.
     */
    val bias: AutoTdpBias? = null,
    /** Highest real draw (W) measured while this app ran on battery with these settings. */
    val measuredPeakW: Float = 0f,
    /**
     * Smoothed average draw (W) across battery play sessions with these settings — the basis
     * for the battery-life estimate. An EMA that keeps refining the longer the app is played.
     */
    val measuredAvgW: Float = 0f,
) {
    val hasAnyBinding: Boolean
        get() = profileBinding != null || fanMode != null || refreshRateHz != null

    /**
     * Fold one live battery-draw sample (W) into the measured peak/avg, returning the updated config.
     * Only [active] samples count: idle/menu/paused draw (≈0.5 W) would otherwise drag the average down and
     * inflate the battery-life estimate (a 7 W game once read as ≈13 h because a long pause averaged in). The
     * peak rises only gradually toward a sample ([PEAK_RISE_W]/sample), so a single noisy `current_now` spike
     * can't bake in a false maximum — it takes sustained high draw to move it.
     */
    fun foldMeasuredDraw(watts: Float, active: Boolean): PerAppConfig {
        if (!active || watts <= 0f) return this
        val peak = if (watts > measuredPeakW) (measuredPeakW + PEAK_RISE_W).coerceAtMost(watts) else measuredPeakW
        val avg = if (measuredAvgW > 0f) measuredAvgW * (1f - AVG_ALPHA) + watts * AVG_ALPHA else watts
        return copy(measuredPeakW = peak, measuredAvgW = avg)
    }

    companion object {
        const val TIER_PREFIX = "tier:"

        /** Per-app draw readout (see [foldMeasuredDraw]). */
        const val PEAK_RISE_W = 1f // peak climbs ≤ this per active sample, so a lone current_now spike can't bake in
        const val AVG_ALPHA = 0.05f // ~30 s EMA toward the realistic sustained-play draw

        /** Binding sentinel for AutoTDP (dynamic CPU→GPU clock trimming), stored in [profileBinding]. */
        const val AUTO_BINDING = "auto:tdp"

        /**
         * Binding sentinel for "this app explicitly runs NO AutoTDP" — distinct from `null` (inherit the
         * global default) and from a tier/Custom binding. Lets a per-app toggle turn AutoTDP OFF for one game
         * even when the global default is ON (the gap that [QuickAccessPerApp] bug #1 left).
         */
        const val AUTO_OFF_BINDING = "auto:off"

        /**
         * AutoTDP FPS-target chips for a **Game-Mode-cap** device (Odin 3): 30/60/120 are hard-capped at
         * 120 Hz (they divide 120 cleanly). **90 and 40 are intentionally omitted** — the Odin panel is
         * 60/120 only (no 90 Hz or 40 Hz mode), and Android floors a 90 fps-cap to 60 and a 40 to 30 (the
         * nearest clean divisor of 120), confirmed on-device via `frameRateOverride`. So a "90" would silently
         * be 60 — misleading — and there's no panel mode to fall back to. Offer only the rates this panel can
         * actually pace.
         */
        val FPS_TARGET_OPTIONS = listOf(30, 60, 120)

        /**
         * AutoTDP FPS-target chips for **refresh-rate-only** devices (8 Gen 2 — Thor / RP6) where the Game
         * Mode fps cap isn't honored: the target IS the panel refresh rate, so only real panel rates are offered.
         */
        val FPS_TARGET_OPTIONS_REFRESH = listOf(60, 90, 120)

        const val DEFAULT_FPS_TARGET = 60

        /** True for the SoC whose firmware honors the Game Mode fps cap (Odin 3); others use the refresh path.
         *  Delegates to the [DeviceProfiles] invariants table (single source for per-device facts). */
        fun isGameModeCapSoc(soc: String?): Boolean = DeviceProfiles.forSoc(soc).honorsGameModeFpsCap

        /** The AutoTDP FPS-target options available for this device's [soc] (from [DeviceProfiles]). */
        fun fpsTargetsFor(soc: String?): List<Int> = DeviceProfiles.forSoc(soc).fpsTargetOptions

        /**
         * Whether to enforce [target] via the **Game Mode fps cap** (panel held at [maxRefresh]) instead of
         * the refresh-rate path. The cap only frame-paces cleanly when the target **evenly divides** the
         * panel's max refresh — on a 120 Hz panel 30/60/120 cap fine, but Android FLOORS a non-divisor to the
         * nearest divisor (90 → 60, confirmed on the Odin via `frameRateOverride=60`), and the Odin panel has
         * no other mode to fall back to. So only divisor targets are offered/used on the Odin. Refresh-rate-
         * only SoCs (Thor/RP6 — firmware doesn't honor the cap) always take the refresh path.
         */
        fun useGameModeCap(soc: String?, target: Int, maxRefresh: Int): Boolean =
            isGameModeCapSoc(soc) && target > 0 && maxRefresh % target == 0

        /** Snap [target] to a valid entry in [options]: legacy Max(0) → the highest rate; else the nearest. */
        fun snapFpsTarget(target: Int, options: List<Int>): Int = when {
            options.isEmpty() -> DEFAULT_FPS_TARGET
            target <= 0 -> options.max() // legacy Max(0) → the highest concrete rate
            else -> options.minByOrNull { kotlin.math.abs(it - target) }!!
        }

        /** Display label for an FPS target (legacy `0` = "Max"; current options are all concrete rates). */
        fun fpsTargetLabel(target: Int): String = if (target <= 0) "Max" else target.toString()

        fun isAuto(binding: String?): Boolean = binding == AUTO_BINDING

        fun isAutoOff(binding: String?): Boolean = binding == AUTO_OFF_BINDING

        fun tierBinding(tier: PowerTier): String = "$TIER_PREFIX${tier.name}"

        fun tierFromBinding(binding: String?): PowerTier? =
            binding?.removePrefix(TIER_PREFIX)
                ?.takeIf { binding.startsWith(TIER_PREFIX) }
                ?.let { name -> PowerTier.entries.firstOrNull { it.name == name } }
    }
}

/**
 * Snapshot of the state to restore when the bound app leaves the foreground. Persisted so a
 * service restart mid-game can still revert cleanly.
 */
@Serializable
data class PerAppRestoreState(
    val values: Map<Int, Int> = emptyMap(),
    val appliedDisplayProfileId: String? = null,
    val activeTierLabel: String? = null,
    val fanMode: Int? = null,
    val refreshRateHz: Int? = null,
    /** CPU governor to restore (captured before AutoTDP forced Balanced); null = leave alone. */
    val governor: String? = null,
)
