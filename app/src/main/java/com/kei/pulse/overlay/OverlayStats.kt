package com.kei.pulse.overlay

import com.kei.pulse.data.FpsReader
import com.kei.pulse.data.TelemetrySnapshot

/** Everything the in-game overlay renders — pushed by the watcher's feed each poll. */
data class OverlayStats(
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val fps: FpsReader.FpsSample? = null,
    val sessionElapsedMs: Long = 0L,
    /** Friendly SoC name (e.g. "Dragonwing Q8"). */
    val socModel: String? = null,
    /** The bound app's profile/tier label for the banner (e.g. "Balanced", "Custom", a saved name). */
    val profileLabel: String = "",
    /** Power to display (W): EMA-smoothed measured draw on battery, or the charge rate while plugged in. */
    val powerDrawW: Float? = null,
    /** True while charging: [powerDrawW] is the charge rate into the battery, not system draw (overlay shows ⚡). */
    val powerIsCharging: Boolean = false,
    /** Battery minutes to show: time-remaining on battery, or time-to-full while charging; null when unknown. */
    val minutesLeft: Int? = null,
    /** Live AutoTDP state when a session is active (null otherwise). */
    val autoTdp: AutoTdpReadout? = null,
    /** Current screen brightness 0..100 (for the Quick Access System slider); null if unread. */
    val brightnessPercent: Int? = null,
    /** Current media volume 0..100 (for the Quick Access System slider); null if unread. */
    val volumePercent: Int? = null,
    /** The Adreno's supported frequencies (kHz, ascending) for the Quick Access GPU-cap stepper; null if unread. */
    val gpuLevels: List<Int>? = null,
    /** The Adreno's LIVE current max (kHz) read back from the device — the stepper's source of truth. */
    val gpuCapKhz: Int? = null,
)

/** Below this smoothed draw (W), a battery time-left estimate is meaningless (paused game ≈ 0 W → "200h"). */
const val MIN_LEFT_WATTS = 0.5f

/** Display step (min) for the battery time-left readout — quantized so it doesn't flicker by a minute. */
const val MINUTES_DISPLAY_STEP = 5

/** EMA weight for the battery-minutes smoothing at the ~1 s overlay cadence (≈20 s time constant). */
const val MINUTES_SMOOTH_ALPHA = 0.05f

/**
 * Output-domain smoothing for the battery time-left readout. time-left = capacity / draw, and dividing by a
 * small noisy draw amplifies the jitter into big jumps in the displayed minutes — so we EMA the **minutes**
 * (a slow ~20-30 s constant; this is a glanceable, slow-moving quantity) rather than slowing the draw EMA
 * (which would also lag the live wattage readout). Returns the updated EMA to carry across ticks; a null
 * [rawMinutes] (charging/paused/untrusted) drops the state so it re-seeds cleanly on the next real reading.
 */
fun smoothMinutes(prevEma: Float?, rawMinutes: Int?, alpha: Float): Float? {
    if (rawMinutes == null) return null
    return if (prevEma == null) rawMinutes.toFloat() else prevEma * (1f - alpha) + rawMinutes * alpha
}

/** Quantize the smoothed minutes to [stepMin] for a stable on-screen value (null stays null). */
fun displayMinutes(ema: Float?, stepMin: Int = MINUTES_DISPLAY_STEP): Int? =
    ema?.let { (Math.round(it / stepMin.toFloat()) * stepMin).coerceAtLeast(0) }

/**
 * Estimated battery minutes remaining at the current SMOOTHED draw, or null when it can't be trusted:
 * charging (draw is charger current, not consumption), unknown capacity/percent, or a draw below
 * [MIN_LEFT_WATTS] (a paused game draws ~0, which divides out to an absurd multi-day figure). Pure so it
 * can be unit-tested; the watcher feeds it the same EMA the power readout uses.
 */
fun batteryMinutesLeft(
    capacityWh: Float?,
    batteryPercent: Int?,
    smoothedWatts: Float?,
    discharging: Boolean,
): Int? {
    if (!discharging) return null
    val cap = capacityWh ?: return null
    val pct = batteryPercent ?: return null
    val w = smoothedWatts ?: return null
    if (w < MIN_LEFT_WATTS || cap <= 0f || pct <= 0) return null
    val remainingWh = cap * (pct.coerceIn(0, 100) / 100f)
    return (remainingWh / w * 60f).toInt().coerceAtLeast(0)
}

/**
 * Estimated minutes to a full charge at the current SMOOTHED charge rate, or null when not trustworthy:
 * unknown capacity/percent, already full (≥100%), or a charge rate below [MIN_LEFT_WATTS] (the trickle near
 * 100% would divide out to hours). The charge rate is `|current_now| × voltage_now` while plugged in.
 */
fun batteryMinutesToFull(
    capacityWh: Float?,
    batteryPercent: Int?,
    chargeWatts: Float?,
): Int? {
    val cap = capacityWh ?: return null
    val pct = batteryPercent ?: return null
    val w = chargeWatts ?: return null
    if (w < MIN_LEFT_WATTS || cap <= 0f || pct >= 100 || pct < 0) return null
    val remainingToFullWh = cap * ((100 - pct.coerceIn(0, 100)) / 100f)
    return (remainingToFullWh / w * 60f).toInt().coerceAtLeast(0)
}

/**
 * What AutoTDP is doing right now, for the overlay: each CPU cluster's cap + live clock (prime-first),
 * the GPU's cap + clock, and how confident the per-SoC power-split learning is.
 */
data class AutoTdpReadout(
    val cpuClusters: List<ClusterReadout>,
    val gpuCapPercent: Int? = null,
    val gpuMhz: Int? = null,
    /** Frame-rate target being held (fps); `0` = Max (uncapped). */
    val targetFps: Int = 0,
    /** True while the prime cores are parked (offlined) by aggressive park. */
    val primeParked: Boolean = false,
    /** 0..100 confidence in the learned CPU/GPU split. */
    val learningPercent: Int = 0,
    /** True once the split is trusted (overlay shows "LRN ✓" instead of a percent). */
    val learned: Boolean = false,
)

/** One CPU cluster's AutoTDP cap (percent of its max) and current clock (MHz, null if unknown). */
data class ClusterReadout(val capPercent: Int, val mhz: Int? = null)

/** Runtime overlay window config: layout preset, which items to show, opacity, and drag lock. */
data class OverlayConfig(
    val preset: com.kei.pulse.model.OverlayPreset = com.kei.pulse.model.OverlayPreset.COMPACT,
    val opacityPercent: Int = 90,
    val locked: Boolean = true,
    val elements: Set<com.kei.pulse.model.OverlayElement> = com.kei.pulse.model.OverlayPreset.COMPACT.elements,
)
