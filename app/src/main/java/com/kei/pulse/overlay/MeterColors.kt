package com.kei.pulse.overlay

import androidx.compose.ui.graphics.Color

// Semantic meter ramp for temps/load — cool → warm → hot, independent of the theme accent. Shared by the
// OSD (OverlayContent) and the Quick Access bar (QaControls/Panel) so the two overlays read as one product.
internal val MeterCool = Color(0xFF4FD89B)
internal val MeterWarm = Color(0xFFFFB000)
internal val MeterHot = Color(0xFFFF5D6C)

/** 0..1 fraction → cool→warm→hot. */
internal fun meterRamp(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return if (f < 0.5f) lerpMeter(MeterCool, MeterWarm, f * 2f) else lerpMeter(MeterWarm, MeterHot, (f - 0.5f) * 2f)
}

/** SoC temp → meter color (40 °C cool … 90 °C hot); null reads cool. */
internal fun meterTempColor(c: Int?): Color = c?.let { meterRamp((it - 40f) / 50f) } ?: MeterCool

/** Load % → meter color; null reads cool. */
internal fun meterLoadColor(p: Int?): Color = p?.let { meterRamp(it / 100f) } ?: MeterCool

private fun lerpMeter(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
