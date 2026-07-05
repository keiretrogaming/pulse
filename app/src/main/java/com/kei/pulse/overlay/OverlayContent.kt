package com.kei.pulse.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kei.pulse.data.TelemetrySnapshot
import com.kei.pulse.model.OverlayElement
import com.kei.pulse.model.OverlayPreset
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

// Temp/load coloring comes from the shared MeterColors ramp (also used by the Quick Access bar).

@Composable
fun OverlayContent(
    statsFlow: StateFlow<OverlayStats>,
    configFlow: StateFlow<OverlayConfig>,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onCyclePreset: () -> Unit,
    onToggleLock: () -> Unit,
) {
    val stats by statsFlow.collectAsState()
    val config by configFlow.collectAsState()
    val opacity = (config.opacityPercent / 100f).coerceIn(0.4f, 1f)
    val accent = MaterialTheme.colorScheme.primary

    if (config.preset == OverlayPreset.COMPACT) {
        DockedBar(stats, config, accent, opacity, onCyclePreset, onToggleLock)
    } else {
        FloatingPanel(stats, config, accent, opacity, onDrag, onCyclePreset, onToggleLock)
    }
}

/** Detailed/Full: a wrap-content rounded panel, draggable when unlocked (the window is WRAP_CONTENT). */
@Composable
private fun FloatingPanel(
    stats: OverlayStats,
    config: OverlayConfig,
    accent: Color,
    opacity: Float,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onCyclePreset: () -> Unit,
    onToggleLock: () -> Unit,
) {
    Box(
        modifier = Modifier
            .alpha(opacity)
            .then(
                if (!config.locked) {
                    Modifier.pointerInput(config.locked) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .widthIn(max = 300.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!config.locked) ControlRow(onCyclePreset = onCyclePreset, onToggleLock = onToggleLock)
                when (config.preset) {
                    OverlayPreset.DETAILED -> DetailedPanel(stats, config.elements)
                    OverlayPreset.FULL -> FullPanel(stats, config.elements)
                    OverlayPreset.COMPACT -> Unit // Compact is rendered as the docked bar, not here.
                }
                // The thermal warning is always shown when tripped, regardless of which items are toggled.
                if (isThrottling(stats.telemetry)) ThrottlePill()
            }
        }
    }
}

/**
 * Compact, docked: a full-width status bar pinned to the top of the screen (its window is MATCH_PARENT and
 * not draggable). Metrics are distributed edge-to-edge with [Arrangement.SpaceBetween], so the bar spans the
 * width and never resizes as the numbers change — no jitter, and a wide value (LEFT) can't wrap.
 */
@Composable
private fun DockedBar(
    stats: OverlayStats,
    config: OverlayConfig,
    accent: Color,
    opacity: Float,
    onCyclePreset: () -> Unit,
    onToggleLock: () -> Unit,
) {
    val t = stats.telemetry
    val els = config.elements
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(opacity)
            .background(Color.Black.copy(alpha = 0.66f))
            .drawBehind {
                val y = size.height - 1f
                drawLine(
                    color = accent.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2f,
                )
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (OverlayElement.FPS in els) Inline("FPS", fpsText(stats.fps?.fps), accent)
            if (OverlayElement.GPU_LOAD in els) Inline("GPU", pct(t.gpuLoadPercent), loadColor(t.gpuLoadPercent))
            if (OverlayElement.GPU_TEMP in els) Inline("°G", num(t.gpuTempC), tempColor(t.gpuTempC))
            if (OverlayElement.CPU_LOAD in els) Inline("CPU", pct(t.cpuLoadPercent), loadColor(t.cpuLoadPercent))
            if (OverlayElement.CPU_TEMP in els) Inline("°C", num(t.cpuTempC), tempColor(t.cpuTempC))
            if (OverlayElement.POWER in els) Inline("W", powerValue(stats), MaterialTheme.colorScheme.onSurface)
            if (OverlayElement.BATTERY_LEFT in els) {
                Inline(if (t.isDischarging) "LEFT" else "FULL", leftText(stats.minutesLeft), MaterialTheme.colorScheme.onSurface)
            }
            if (isThrottling(t)) ThrottlePill()
            if (!config.locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "⤢ LAYOUT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.clickable { onCyclePreset() },
                    )
                    Text(
                        "🔒 LOCK",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.clickable { onToggleLock() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlRow(onCyclePreset: () -> Unit, onToggleLock: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "✥ DRAG",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "⤢ LAYOUT",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onCyclePreset() },
        )
        Text(
            "🔒 LOCK",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onToggleLock() },
        )
    }
}

@Composable
private fun ThrottlePill() {
    Text(
        "⚠ THERMAL",
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MeterHot)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// True if ANY of [e] is in the enabled set — used to skip a row entirely when none of its items show.
private fun Set<OverlayElement>.any(vararg e: OverlayElement): Boolean = e.any { it in this }

@Composable
private fun DetailedPanel(stats: OverlayStats, els: Set<OverlayElement>) {
    val t = stats.telemetry
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (els.any(OverlayElement.FPS, OverlayElement.FPS_TREND, OverlayElement.SESSION_TIMER)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (OverlayElement.FPS in els) BigFps(stats.fps?.fps)
                if (OverlayElement.FPS_TREND in els) {
                    Column {
                        Text("AVG ${fpsText(stats.fps?.avgFps)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("LOW ${fpsText(stats.fps?.onePercentLowFps)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (OverlayElement.SESSION_TIMER in els) {
                    Spacer(Modifier.size(4.dp))
                    Text(formatTimer(stats.sessionElapsedMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (els.any(OverlayElement.GPU_LOAD, OverlayElement.GPU_CLOCK, OverlayElement.GPU_TEMP, OverlayElement.POWER, OverlayElement.BATTERY_LEFT)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (OverlayElement.GPU_LOAD in els) Metric("GPU", pct(t.gpuLoadPercent), "%", loadColor(t.gpuLoadPercent))
                if (OverlayElement.GPU_CLOCK in els) Metric("GPU", num(t.gpuMhz), "MHz", MaterialTheme.colorScheme.tertiary)
                if (OverlayElement.GPU_TEMP in els) Metric("GPU", num(t.gpuTempC), "°C", tempColor(t.gpuTempC))
                if (OverlayElement.POWER in els) Metric("PWR", powerValue(stats), "W", MaterialTheme.colorScheme.onSurface)
                if (OverlayElement.BATTERY_LEFT in els) Metric("BAT", leftText(stats.minutesLeft), if (t.isDischarging) "LEFT" else "FULL", MaterialTheme.colorScheme.onSurface)
            }
        }
        if (els.any(OverlayElement.CPU_LOAD, OverlayElement.CPU_CLOCK, OverlayElement.CPU_TEMP, OverlayElement.RAM)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (OverlayElement.CPU_LOAD in els) Metric("CPU", pct(t.cpuLoadPercent), "%", loadColor(t.cpuLoadPercent))
                if (OverlayElement.CPU_CLOCK in els) Metric("CPU", ghz(t.cpuClocksMhz.values.maxOrNull()), "GHz", MaterialTheme.colorScheme.primary)
                if (OverlayElement.CPU_TEMP in els) Metric("CPU", num(t.cpuTempC), "°C", tempColor(t.cpuTempC))
                if (OverlayElement.RAM in els) Metric("RAM", pct(t.ramUsedPercent), "%", MaterialTheme.colorScheme.onSurface)
            }
        }
        if (OverlayElement.AUTOTDP in els) ProfileOrAutoTdp(stats, showClocks = false)
    }
}

@Composable
private fun FullPanel(stats: OverlayStats, els: Set<OverlayElement>) {
    val t = stats.telemetry
    val tertiary = MaterialTheme.colorScheme.tertiary
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (els.any(OverlayElement.SOC_NAME, OverlayElement.SESSION_TIMER)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (OverlayElement.SOC_NAME in els) {
                    Text(
                        stats.socModel ?: "PULSE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (OverlayElement.SESSION_TIMER in els) {
                    Spacer(Modifier.size(2.dp))
                    Text(formatTimer(stats.sessionElapsedMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // FPS hero + trend
        if (els.any(OverlayElement.FPS, OverlayElement.FPS_TREND)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (OverlayElement.FPS in els) BigFps(stats.fps?.fps)
                if (OverlayElement.FPS_TREND in els) {
                    Column {
                        Text("AVG ${fpsText(stats.fps?.avgFps)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("LOW ${fpsText(stats.fps?.onePercentLowFps)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${fmt1(stats.fps?.frameTimeMs)} ms", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    stats.fps?.recentFps?.let { Sparkline(it, primary, Modifier.size(width = 64.dp, height = 22.dp)) }
                }
            }
        }
        // GPU
        if (els.any(OverlayElement.GPU_LOAD, OverlayElement.GPU_CLOCK, OverlayElement.GPU_TEMP, OverlayElement.POWER, OverlayElement.BATTERY_LEFT)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (OverlayElement.GPU_LOAD in els) Metric("GPU", pct(t.gpuLoadPercent), "%", loadColor(t.gpuLoadPercent))
                if (OverlayElement.GPU_CLOCK in els) Metric("GPU", num(t.gpuMhz), "MHz", tertiary)
                if (OverlayElement.GPU_TEMP in els) Metric("GPU", num(t.gpuTempC), "°C", tempColor(t.gpuTempC))
                if (OverlayElement.POWER in els) Metric("PWR", powerValue(stats), "W", MaterialTheme.colorScheme.onSurface)
                if (OverlayElement.BATTERY_LEFT in els) Metric("BAT", leftText(stats.minutesLeft), if (t.isDischarging) "LEFT" else "FULL", MaterialTheme.colorScheme.onSurface)
            }
        }
        // CPU + per-core bars
        if (els.any(OverlayElement.CPU_LOAD, OverlayElement.CPU_CLOCK, OverlayElement.CPU_TEMP, OverlayElement.CORE_BARS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (OverlayElement.CPU_LOAD in els) Metric("CPU", pct(t.cpuLoadPercent), "%", loadColor(t.cpuLoadPercent))
                if (OverlayElement.CPU_CLOCK in els) Metric("CPU", ghz(t.cpuClocksMhz.values.maxOrNull()), "GHz", primary)
                if (OverlayElement.CPU_TEMP in els) Metric("CPU", num(t.cpuTempC), "°C", tempColor(t.cpuTempC))
                if (OverlayElement.CORE_BARS in els && t.cpuCoreLoadsPercent.isNotEmpty()) {
                    CoreBars(t.cpuCoreLoadsPercent, primary, Modifier.size(width = 60.dp, height = 22.dp))
                }
            }
        }
        // RAM
        if (OverlayElement.RAM in els) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Metric(
                    "RAM",
                    if (t.ramUsedMb != null && t.ramTotalMb != null) {
                        "${t.ramUsedMb / 1024}.${(t.ramUsedMb % 1024) * 10 / 1024}/${t.ramTotalMb / 1024}"
                    } else {
                        "—"
                    },
                    "GB",
                    MaterialTheme.colorScheme.onSurface,
                )
                Metric("RAM", pct(t.ramUsedPercent), "%", MaterialTheme.colorScheme.onSurface)
            }
        }
        if (OverlayElement.AUTOTDP in els) ProfileOrAutoTdp(stats, showClocks = true)
    }
}

/**
 * The AutoTDP live readout (per-cluster caps + clocks + learning) when a session is active, else the
 * plain profile/tier banner. AutoTDP replaces the banner because it already names itself.
 */
@Composable
private fun ProfileOrAutoTdp(stats: OverlayStats, showClocks: Boolean) {
    val auto = stats.autoTdp
    when {
        auto != null -> AutoTdpRow(auto, showClocks)
        stats.profileLabel.isNotBlank() -> Text(
            stats.profileLabel.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AutoTdpRow(a: AutoTdpReadout, showClocks: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text("AUTOTDP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primary)
            Text("→ ${if (a.targetFps <= 0) "MAX" else "${a.targetFps}"} fps", fontSize = 9.sp, color = muted)
            if (a.primeParked) Text("PARK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MeterCool)
            Text(
                if (a.learned) "LRN ✓" else "LRN ${a.learningPercent}%",
                fontSize = 9.sp,
                color = muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            Text("CPU", fontSize = 8.sp, color = muted)
            Text(
                a.cpuClusters.joinToString(" ") { "${it.capPercent}%" },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = capColor(a.cpuClusters.minOfOrNull { it.capPercent } ?: 100),
            )
            a.gpuCapPercent?.let {
                Text("GPU", fontSize = 8.sp, color = muted)
                Text("$it%", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = capColor(it))
            }
        }
        if (showClocks) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Text("CLK", fontSize = 8.sp, color = muted)
                Text(a.cpuClusters.joinToString("/") { ghz(it.mhz) }, fontSize = 10.sp, color = primary)
                a.gpuMhz?.let { Text("· ${ghz(it)} G", fontSize = 10.sp, color = tertiary) }
            }
        }
    }
}

@Composable
private fun BigFps(fps: Float?) {
    Text(
        text = fpsText(fps),
        style = TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            shadow = Shadow(color = Color.Black, blurRadius = 6f),
        ),
    )
}

@Composable
private fun Inline(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(unit, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CoreBars(values: List<Int>, color: Color, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Canvas(modifier) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 2f
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            // faint full-height track + filled portion
            drawRect(color = muted, topLeft = Offset(x, 0f), size = Size(barW, size.height))
            val h = size.height * (v / 100f).coerceIn(0f, 1f)
            drawRect(color = color, topLeft = Offset(x, size.height - h), size = Size(barW, h))
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier) {
    if (values.size < 2) return
    val min = values.minOrNull() ?: return
    val max = values.maxOrNull() ?: return
    // Use a minimum span so small frame-to-frame jitter doesn't get amplified into a "seismograph".
    val range = (max - min).coerceAtLeast(15f)
    Canvas(modifier) {
        val stepX = size.width / (values.size - 1)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val p = Offset(i * stepX, size.height - ((v - min) / range) * size.height)
            prev?.let { drawLine(color, it, p, strokeWidth = 2f) }
            prev = p
        }
    }
}

// ── formatting / color helpers ──────────────────────────────────────────────

private fun num(v: Int?): String = v?.toString() ?: "—"
private fun pct(v: Int?): String = v?.toString() ?: "—"
private fun fmt1(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"
private fun ghz(mhz: Int?): String = mhz?.let { String.format(java.util.Locale.US, "%.2f", it / 1000f) } ?: "—"
private fun fpsText(fps: Float?): String = fps?.takeIf { it > 0f }?.roundToInt()?.toString() ?: "—"

/** Battery time-left: "2h14m" / "47m", or "—" when not estimable (charging, idle draw, unknown capacity). */
private fun leftText(minutes: Int?): String {
    val m = minutes ?: return "—"
    val h = m / 60
    val mm = m % 60
    return if (h > 0) "${h}h${mm}m" else "${mm}m"
}

/** Power readout: "3.4" (system draw on battery) or "⚡18.5" (charge rate while plugged in); "—" when unknown. */
private fun powerValue(stats: OverlayStats): String {
    val v = stats.powerDrawW?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: return "—"
    return if (stats.powerIsCharging) "⚡$v" else v
}

// Mirrors AutoTuneController.THERMAL_C (the hard thermal trip): the pill must light when the controller
// starts thermal-limping, not 5 °C later — keep these two in sync.
private const val THROTTLE_TEMP_C = 85
private fun isThrottling(t: TelemetrySnapshot): Boolean =
    (t.cpuTempC ?: 0) >= THROTTLE_TEMP_C || (t.gpuTempC ?: 0) >= THROTTLE_TEMP_C

private fun tempColor(c: Int?): Color = meterTempColor(c)
private fun loadColor(p: Int?): Color = meterLoadColor(p)

// AutoTDP caps: a trimmed domain (below 100%) is the savings — show it green; full clocks stay neutral.
@Composable
private fun capColor(percent: Int): Color =
    if (percent >= 100) MaterialTheme.colorScheme.onSurface else MeterCool

private fun formatTimer(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
}
