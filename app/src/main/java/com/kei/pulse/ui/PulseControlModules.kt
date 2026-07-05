package com.kei.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kei.pulse.data.DisplayController
import com.kei.pulse.data.FanController
import com.kei.pulse.data.GovernorController
import com.kei.pulse.data.GovernorOption
import com.kei.pulse.data.RefreshRateController
import com.kei.pulse.data.TelemetrySnapshot
import com.kei.pulse.data.AutoTuneController
import kotlinx.coroutines.delay
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanCurveEditing
import com.kei.pulse.model.FanGraphGeometry
import com.kei.pulse.model.FanTempController
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun PulseSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
    )
}

/** The four performance tiers as the primary control: a 2x2 grid of selectable cards. */
@Composable
fun TierSelector(
    active: PowerTier,
    onSelect: (PowerTier) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tiers = PowerTier.entries
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tiers.chunked(2).forEach { rowTiers ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiers.forEach { tier ->
                    TierCard(
                        tier = tier,
                        selected = tier == active,
                        onClick = { if (enabled) onSelect(tier) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TierCard(
    tier: PowerTier,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (tier == PowerTier.POWER_SAVING) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val container = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = if (selected) accent else MaterialTheme.colorScheme.outline
    Surface(
        color = container,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = tier.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tier.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Bindings for the Custom fan-curve editor. Non-null only on the Odin 3 (the device with the writable PWM
 * node); when present, [FanModule] shows the Custom chip + reveals [FanCurveEditor] while Custom is selected.
 * [curve]/[responseStep] mirror the persisted settings (one source of truth — the same curve the running
 * controller drives); the callbacks persist edits; [readTelemetry]/[readFanDutyPercent] feed the live marker.
 */
data class FanCurveEditorBindings(
    val curve: FanCurve,            // the BASE curve the user edits/stores
    val responseStep: Int,          // slew rate (%/second) the fan ramps toward the target
    val bias: Int,                  // Cooler/Quieter live offset (±, % shifted on top of the base)
    val smartEnabled: Boolean,      // closed-loop temp-target mode (vs the manual curve)
    val targetTempC: Int,           // Smart-mode target SoC temperature
    val calibrating: Boolean,
    val onCurveChange: (FanCurve) -> Unit,
    val onResponseStepChange: (Int) -> Unit,
    val onBiasChange: (Int) -> Unit,
    val onSmartToggle: (Boolean) -> Unit,
    val onTargetTempChange: (Int) -> Unit,
    val onAutocalibrate: () -> Unit,
    val readTelemetry: suspend () -> TelemetrySnapshot,
    val readFanDutyPercent: suspend () -> Int?, // ACTUAL live fan % (reliable; the RPM tach is not)
)

/** Fan mode selector (Odin family). Smart is the safe stock default. Custom (Odin 3 only) reveals the
 *  temperature→% curve editor below the chips. */
@Composable
fun FanModule(
    currentMode: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    editor: FanCurveEditorBindings? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PulseSectionLabel("FAN · ${FanController.labelFor(currentMode).uppercase()}")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FanController.MODES.forEach { mode ->
                // The Custom chip only exists where PULSE can actually drive the PWM (Odin 3). On Thor/RP6
                // `editor` is null, so Custom is hidden and those devices never reach the fallback path.
                if (mode.value == FanController.CUSTOM && editor == null) return@forEach
                PulseChip(
                    label = mode.label,
                    selected = currentMode == mode.value,
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = { onSelect(mode.value) },
                )
            }
        }
        if (currentMode == FanController.CUSTOM && editor != null) {
            Spacer(Modifier.height(10.dp))
            FanCurveEditor(bindings = editor)
        }
    }
}

/**
 * EVGA-style temperature→fan% curve editor (Odin 3 Custom mode). Drag the knee handles to shape the curve;
 * a live marker rides the curve at the current SoC temp so you watch it climb as the device heats, with the
 * live RPM shown alongside. "Autocalibrate" sweeps the fan to learn its real speed range and rebuilds the
 * curve. The graph drawing + drag gestures are verified on-device (PULSE can't be screenshotted); all the
 * coordinate/edit math is unit-tested ([FanGraphGeometry], [FanCurveEditing]).
 *
 * Gesture notes (CLAUDE.md gotchas): the curve points live in ONE stable [mutableStateOf] (not re-created
 * per external curve change — that would strand the gesture's captured reference); external changes sync in
 * via a [LaunchedEffect]. The drag CONSUMES from touch-down only when a knee is grabbed, so it wins over the
 * page's verticalScroll without freezing taps elsewhere. [onCurveChange] is read through [rememberUpdatedState].
 */
@Composable
fun FanCurveEditor(bindings: FanCurveEditorBindings, modifier: Modifier = Modifier) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val kneeFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val markerColor = Color(0xFFFF5D6C)

    // ONE stable points state (the BASE curve) + bias; external changes (e.g. Autocalibrate) sync in via
    // LaunchedEffect. The graph draws the EFFECTIVE curve = base shifted by the Cooler/Quieter bias.
    val pointsState = remember { mutableStateOf(bindings.curve.points.sortedBy { it.tempC }) }
    LaunchedEffect(bindings.curve) { pointsState.value = bindings.curve.points.sortedBy { it.tempC } }
    val biasState = remember { mutableStateOf(bindings.bias) }
    LaunchedEffect(bindings.bias) { biasState.value = bindings.bias }
    val onCurveChange by rememberUpdatedState(bindings.onCurveChange)
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = onSurfaceVariant, fontSize = 9.sp)
    fun effective() = FanCurve(pointsState.value).shiftedBy(biasState.value)

    // Live marker telemetry: current SoC temp (max of CPU/GPU) + the ACTUAL fan % (read from the duty node;
    // the RPM tach is unreliable, so we report the real fan duty % — same number the vendor app shows).
    var liveTemp by remember { mutableStateOf<Int?>(null) }
    var liveDutyPercent by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = bindings.readTelemetry()
            liveTemp = maxOf(t.cpuTempC ?: 0, t.gpuTempC ?: 0).takeIf { it > 0 }
            bindings.readFanDutyPercent()?.let { liveDutyPercent = it }
            delay(1_500)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // Header + live readout: "47°C · fan 49%" — temp + the ACTUAL fan duty % off the device.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "CUSTOM FAN CURVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant,
                )
                Text(
                    text = buildString {
                        append(liveTemp?.let { "$it°C" } ?: "—")
                        append(" · fan ")
                        append(liveDutyPercent?.let { "$it%" } ?: "—")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))

            // Smart (closed-loop) vs manual curve. Smart holds a target temp with minimum fan — no curve to tune.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HOLD TARGET TEMP", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
                    Text(
                        if (bindings.smartEnabled) "Closed-loop — the fan self-adjusts to hold the target, quietly"
                        else "Manual — you shape the temperature → fan curve",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = bindings.smartEnabled, onCheckedChange = bindings.onSmartToggle)
            }
            Spacer(Modifier.height(8.dp))

            if (bindings.smartEnabled) {
                // Target SoC temperature: the PI controller holds this with the least fan it can.
                var target by remember { mutableStateOf(bindings.targetTempC.toFloat()) }
                LaunchedEffect(bindings.targetTempC) { target = bindings.targetTempC.toFloat() }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TARGET TEMP", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
                    Text(
                        "hold ${target.roundToInt()}°C",
                        style = MaterialTheme.typography.labelMedium,
                        color = tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    onValueChangeFinished = { bindings.onTargetTempChange(target.roundToInt()) },
                    valueRange = FanTempController.TARGET_MIN_C.toFloat()..FanTempController.TARGET_MAX_C.toFloat(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("cooler · louder", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                    Text("quieter · warmer", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                }
                Text(
                    text = "The fan holds your chip at this temperature using the least speed it can — silent when " +
                        "cool, ramping only as much as needed, adapting to each game and the room. No curve to tune.",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {

            val d = LocalDensity.current
            val padL = with(d) { 22.dp.toPx() } // room for the % tick labels
            val padR = with(d) { 8.dp.toPx() }
            val padT = with(d) { 10.dp.toPx() }
            val padB = with(d) { 16.dp.toPx() } // room for the °C tick labels
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Cooler ⟷ Quieter live bias on the left: up = cooler (curve lifts), down = quieter (curve drops).
                Column(
                    modifier = Modifier.height(190.dp).width(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("COOL", style = MaterialTheme.typography.labelSmall, color = tertiary)
                    VerticalSlider(
                        value = biasState.value.toFloat(),
                        onValueChange = { biasState.value = it.roundToInt() },
                        onValueChangeFinished = { bindings.onBiasChange(biasState.value) },
                        valueRange = -FanCurve.MAX_BIAS.toFloat()..FanCurve.MAX_BIAS.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text("QUIET", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                }

                Box(modifier = Modifier.weight(1f).height(190.dp)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val left = padL; val top = padT
                                    val w = size.width - padL - padR; val h = size.height - padT - padB
                                    val bias = biasState.value
                                    // Hit-test against the EFFECTIVE knee positions (where the dots are drawn).
                                    val effPts = FanCurve(pointsState.value).shiftedBy(bias).points
                                    var idx = -1; var best = Float.MAX_VALUE
                                    effPts.forEachIndexed { i, p ->
                                        val (nx, ny) = FanGraphGeometry.toNorm(p.tempC, p.percent)
                                        val dist = hypot(down.position.x - (left + nx * w), down.position.y - (top + ny * h))
                                        if (dist < best) { best = dist; idx = i }
                                    }
                                    val downNp = FanGraphGeometry.fromNorm(
                                        (down.position.x - left) / w, (down.position.y - top) / h,
                                    )
                                    if (best > 60f || idx < 0) {
                                        // Empty spot: a TAP adds a knee here; a SWIPE should scroll the page. Watch
                                        // WITHOUT consuming and only add on a tap (no real movement) so scrolling works.
                                        var moved = false
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val c = e.changes.firstOrNull() ?: break
                                            if (!c.pressed) break
                                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                                moved = true; break
                                            }
                                        }
                                        if (moved) return@awaitEachGesture // it was a scroll — leave it to the page
                                        val added = FanCurveEditing.addPoint(pointsState.value, downNp.tempC, downNp.percent - bias)
                                        if (added.size != pointsState.value.size) {
                                            pointsState.value = added
                                            onCurveChange(FanCurve(pointsState.value))
                                        }
                                        return@awaitEachGesture
                                    }
                                    // Grabbed a knee → claim the pointer and drag it (drag off the bottom to delete).
                                    down.consume()
                                    var dragNy = (down.position.y - top) / h
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val nx = (change.position.x - left) / w
                                        dragNy = (change.position.y - top) / h
                                        val np = FanGraphGeometry.fromNorm(nx, dragNy)
                                        pointsState.value = FanCurveEditing.movePointBiased(
                                            pointsState.value, idx, np.tempC, np.percent, bias,
                                        )
                                    }
                                    // Dragged off the bottom of the graph → DELETE the knee (keeps ≥ 2).
                                    if (dragNy > 1.12f) pointsState.value = FanCurveEditing.removePoint(pointsState.value, idx)
                                    onCurveChange(FanCurve(pointsState.value)) // persist BASE once, at gesture end
                                }
                            },
                    ) {
                        val left = padL; val top = padT
                        val w = this.size.width - padL - padR; val h = this.size.height - padT - padB
                        fun pt(tempC: Int, percent: Int): Offset {
                            val (nx, ny) = FanGraphGeometry.toNorm(tempC, percent)
                            return Offset(left + nx * w, top + ny * h)
                        }

                        val grid = outline.copy(alpha = 0.35f)
                        for (t in FanGraphGeometry.TEMP_MIN_C..FanGraphGeometry.TEMP_MAX_C step 20) {
                            val x = pt(t, 0).x
                            drawLine(grid, Offset(x, top), Offset(x, top + h), 1f)
                            val tl = textMeasurer.measure("$t", tickStyle)
                            drawText(tl, topLeft = Offset(x - tl.size.width / 2f, top + h + 2f))
                        }
                        for (p in 0..100 step 20) {
                            val y = pt(FanGraphGeometry.TEMP_MIN_C, p).y
                            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
                            val tl = textMeasurer.measure("$p", tickStyle)
                            drawText(tl, topLeft = Offset(left - tl.size.width - 4f, y - tl.size.height / 2f))
                        }

                        // SMOOTH curve: sample the effective spline at FLOAT precision across the temp axis so the
                        // drawn line exactly matches the fan's response with no integer stair-stepping. Knee dots
                        // mark the draggable control points.
                        val spanC = (FanGraphGeometry.TEMP_MAX_C - FanGraphGeometry.TEMP_MIN_C).toFloat()
                        fun ptF(tempC: Float, percentF: Float): Offset {
                            val nx = ((tempC - FanGraphGeometry.TEMP_MIN_C) / spanC).coerceIn(0f, 1f)
                            val ny = (1f - percentF / 100f).coerceIn(0f, 1f)
                            return Offset(left + nx * w, top + ny * h)
                        }
                        val effCurve = effective()
                        val pts = effCurve.points.sortedBy { it.tempC }
                        if (pts.isNotEmpty()) {
                            val path = Path()
                            val first = FanGraphGeometry.TEMP_MIN_C.toFloat()
                            val start = ptF(first, effCurve.percentForExact(first))
                            path.moveTo(start.x, start.y)
                            var t = first + 1f
                            while (t <= FanGraphGeometry.TEMP_MAX_C) {
                                val o = ptF(t, effCurve.percentForExact(t))
                                path.lineTo(o.x, o.y)
                                t += 1f
                            }
                            drawPath(path, tertiary, style = Stroke(width = 4f))
                            pts.forEach { p ->
                                val o = pt(p.tempC, p.percent)
                                drawCircle(kneeFill, 9f, o)
                                drawCircle(tertiary, 9f, o, style = Stroke(width = 3f))
                            }
                        }

                        // Live marker: a dot riding the effective curve at the current temp + the live fan speed.
                        liveTemp?.let { tC ->
                            val ePct = effCurve.effectivePercent(tC)
                            val o = pt(tC.coerceIn(FanGraphGeometry.TEMP_MIN_C, FanGraphGeometry.TEMP_MAX_C), ePct)
                            drawLine(
                                markerColor.copy(alpha = 0.5f),
                                Offset(o.x, top + h), o, 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                            )
                            drawCircle(markerColor, 7f, o)
                            drawCircle(markerColor.copy(alpha = 0.25f), 13f, o)
                            liveDutyPercent?.let { pctNow ->
                                val tl = textMeasurer.measure("$pctNow%", TextStyle(color = markerColor, fontSize = 9.sp))
                                val lx = (o.x - tl.size.width / 2f).coerceIn(left, left + w - tl.size.width)
                                drawText(tl, topLeft = Offset(lx, (o.y - tl.size.height - 6f).coerceAtLeast(top)))
                            }
                        }
                    }

                }
            }

            // Axis legend (outside the plot so nothing overlaps the curve): fan % is the vertical axis, °C the
            // horizontal. The numbered ticks on each axis fill in the scale.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Air, contentDescription = null, tint = tertiary, modifier = Modifier.size(13.dp))
                    Text(" Fan %", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Temp °C ", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                    Icon(Icons.Outlined.Thermostat, contentDescription = null, tint = onSurfaceVariant, modifier = Modifier.size(13.dp))
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = outline.copy(alpha = 0.6f),
            )

            // Response = how fast the fan ramps toward the curve (slew rate). Lower = smoother/quieter, higher
            // = snappier. The fan always moves in tiny continuous steps, so it never audibly "steps".
            var step by remember { mutableStateOf(bindings.responseStep.toFloat()) }
            LaunchedEffect(bindings.responseStep) { step = bindings.responseStep.toFloat() }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RESPONSE", style = MaterialTheme.typography.labelMedium, color = onSurfaceVariant)
                Text(
                    "smoother · ${step.roundToInt()}%/s · snappier",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant,
                )
            }
            Slider(
                value = step,
                onValueChange = { step = it },
                onValueChangeFinished = { bindings.onResponseStepChange(step.roundToInt()) },
                valueRange = FanCurve.MIN_SLEW.toFloat()..FanCurve.MAX_SLEW.toFloat(),
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = bindings.onAutocalibrate,
                enabled = !bindings.calibrating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (bindings.calibrating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Calibrating…")
                } else {
                    Text("Auto-Calibrate")
                }
            }
            Text(
                text = "Drag a point to shape the curve, tap an empty spot to add one, drag a point off the bottom to remove it. " +
                    "Auto-Calibrate learns this fan's real speed range and efficient point, anchored to your idle temp.",
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            } // end manual-curve mode
        }
    }
}

/**
 * A vertical [Slider] — value increases upward — for the Cooler/Quieter bias on the left of the curve graph.
 * Rotates a standard Slider 270° and swaps its measured dimensions (the canonical Compose recipe) so it fills
 * the height it's given. Verified on-device (can't screenshot PULSE).
 */
@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier
                .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        Constraints(
                            minWidth = constraints.minHeight,
                            maxWidth = constraints.maxHeight,
                            minHeight = constraints.minWidth,
                            maxHeight = constraints.maxWidth,
                        ),
                    )
                    layout(placeable.height, placeable.width) {
                        placeable.place(-placeable.width, 0)
                    }
                },
        )
    }
}

/** Render-scale selector. Lowering resolution frees up GPU headroom; fully reversible. */
@Composable
fun ResolutionModule(
    native: DisplayController.DisplaySpec?,
    currentScale: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val nativeLabel = native?.let { " · ${it.width}×${it.height}" } ?: ""
        PulseSectionLabel("RENDER SCALE$nativeLabel")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DisplayController.SCALES.forEach { pct ->
                val sub = native?.let {
                    if (pct >= 100) "native" else "${it.width * pct / 100}×${it.height * pct / 100}"
                }
                PulseChip(
                    label = if (pct >= 100) "Native" else "$pct%",
                    sub = sub,
                    selected = currentScale == pct,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onSelect(pct) },
                )
            }
        }
    }
}

@Composable
private fun PulseChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    sub: String? = null,
) {
    val container = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = if (selected) accent else MaterialTheme.colorScheme.outline
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Live telemetry HUD: polls clocks / load / battery / temps / draw every 1.5s. */
@Composable
fun TelemetryHud(
    readTelemetry: suspend () -> TelemetrySnapshot,
    estimatedPeakW: Float? = null,
    modifier: Modifier = Modifier,
) {
    var snap by remember { mutableStateOf(TelemetrySnapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = readTelemetry()
            delay(1_500)
        }
    }
    val cpuMhz = snap.cpuClocksMhz.values.maxOrNull()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryMetric("CPU", cpuMhz?.toString(), "MHz", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                TelemetryMetric("GPU", snap.gpuMhz?.toString(), "MHz", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                TelemetryMetric("CPU LOAD", snap.cpuLoadPercent?.toString(), "%", loadColor(snap.cpuLoadPercent, muted), Modifier.weight(1f))
                TelemetryMetric("BATT", snap.batteryPercent?.toString(), "%", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            )
            val drawValue = snap.batteryDrawW?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: snap.batteryDrawMa?.toString()
            val drawUnit = if (snap.batteryDrawW != null) "W" else "mA"
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TelemetryMetric("CPU TEMP", snap.cpuTempC?.toString(), "°C", tempColor(snap.cpuTempC, muted), Modifier.weight(1f))
                TelemetryMetric("GPU TEMP", snap.gpuTempC?.toString(), "°C", tempColor(snap.gpuTempC, muted), Modifier.weight(1f))
                TelemetryMetric("DRAW", drawValue, drawUnit, drawColor(snap.batteryDrawW, snap.batteryDrawMa, estimatedPeakW, snap.isDischarging, muted), Modifier.weight(1f))
                TelemetryMetric("EST PK", estimatedPeakW?.let { String.format(java.util.Locale.US, "%.0f", it) }, "W", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
        }
    }
}

// Semantic meter ramp: green/cool (low) → amber (mid) → red (high). Used for temps, GPU load,
// and power draw so the colour describes what the reading is doing, not the theme. CPU/GPU
// clocks keep their theme identity colours; this is the "Direction A" semantic-meter scheme.
private val MeterCool = Color(0xFF4FD89B)
private val MeterWarm = Color(0xFFFFB000)
private val MeterHot = Color(0xFFFF5D6C)

private fun meterRamp(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return if (f < 0.5f) lerp(MeterCool, MeterWarm, f * 2f) else lerp(MeterWarm, MeterHot, (f - 0.5f) * 2f)
}

private fun tempColor(c: Int?, fallback: Color): Color =
    c?.let { meterRamp((it - 40f) / 50f) } ?: fallback // 40°C cool → 90°C hot

private fun loadColor(percent: Int?, fallback: Color): Color =
    percent?.let { meterRamp(it / 100f) } ?: fallback

private fun drawColor(watts: Float?, milliamps: Int?, ceilingW: Float?, isDischarging: Boolean, fallback: Color): Color = when {
    !isDischarging -> fallback // plugged in: the reading is charger current, not system draw
    watts != null -> meterRamp(watts / (ceilingW?.takeIf { it > 1f } ?: 18f))
    milliamps != null -> meterRamp(milliamps / 4000f)
    else -> fallback
}

@Composable
private fun TelemetryMetric(
    label: String,
    value: String?,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.titleLarge,
            color = if (value != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** CPU governor selector (#4). */
@Composable
fun GovernorModule(current: String?, onSelect: (GovernorOption) -> Unit, modifier: Modifier = Modifier) {
    val activeOption = GovernorController.optionForGovernor(current)
    Column(modifier = modifier.fillMaxWidth()) {
        PulseSectionLabel("CPU GOVERNOR${current?.let { " · ${it.uppercase()}" } ?: ""}")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GovernorController.OPTIONS.forEach { opt ->
                PulseChip(
                    label = opt.label,
                    selected = activeOption == opt,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onSelect(opt) },
                )
            }
        }
    }
}

/** Display refresh-rate selector (#8). */
@Composable
fun RefreshRateModule(current: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        PulseSectionLabel("REFRESH RATE${current?.let { " · ${it}HZ" } ?: ""}")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RefreshRateController.RATES.forEach { hz ->
                PulseChip(
                    label = "$hz Hz",
                    selected = current == hz,
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = { onSelect(hz) },
                )
            }
        }
    }
}

/** GPU minimum-clock floor selector (#5). 0 = no floor. */
@Composable
fun GpuFloorModule(
    currentPercent: Int,
    locked: Boolean,
    onSelect: (Int) -> Unit,
    onToggleLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(0, 25, 50, 75)
    Column(modifier = modifier.fillMaxWidth()) {
        PulseSectionLabel("GPU FLOOR")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Lock to selected clock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = locked, onCheckedChange = onToggleLock)
        }
        if (locked) {
            Text(
                text = "Pinned to the GPU's current clock — set and apply your GPU frequency first, then lock. The floor is disabled while locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { pct ->
                    PulseChip(
                        label = if (pct == 0) "Off" else "$pct%",
                        selected = currentPercent == pct,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onSelect(pct) },
                    )
                }
            }
            Text(
                text = "Keeps the GPU from dropping below this share of its max — steadier frame pacing in demanding games.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * Power Target (the "TDP-style" master control). Not real watts — Snapdragon has no
 * programmable wattage cap — but one slider scaling every CPU + GPU ceiling together.
 * While enabled it owns those limits, so the individual sliders are locked.
 */
@Composable
fun PowerTargetModule(
    enabled: Boolean,
    percent: Int,
    cpuOnly: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPercentChange: (Int) -> Unit,
    onCpuOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember(percent) { mutableStateOf(percent.toFloat()) }
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "POWER TARGET",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (enabled) "${live.toInt()}%" else "Off",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Slider(
                    value = live,
                    onValueChange = { live = it },
                    onValueChangeFinished = { onPercentChange(live.toInt()) },
                    valueRange = 40f..100f,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = if (cpuOnly) {
                        "Scales the CPU clusters only — the GPU stays on its own slider and floor."
                    } else {
                        "Scales every CPU cluster and the GPU together. The individual sliders below are locked while this is on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CPU only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(checked = cpuOnly, onCheckedChange = onCpuOnlyChange)
                }
            }
        }
    }
}

/** Format a watt ceiling for the UI: 11.0→"11", 12.5→"12.5". */
private fun wattLabel(w: Float): String = "%.1f".format(w).removeSuffix(".0")

/**
 * AutoTDP master toggle. When on, PULSE dynamically trims the CPU first, then GPU, to hold each
 * foreground game's refresh-rate FPS (uses your Custom fan if set, otherwise Smart; refresh rate
 * untouched). It becomes the default for any game without its own per-app binding, so the manual
 * tier/clock controls are locked — but the fan stays adjustable.
 */
@Composable
fun AutoTdpModule(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    fpsTarget: Int,
    fpsOptions: List<Int>,
    onFpsTargetChange: (Int) -> Unit,
    aggressivePark: Boolean,
    onAggressiveParkChange: (Boolean) -> Unit,
    bias: AutoTdpBias,
    onBiasChange: (AutoTdpBias) -> Unit,
    showWattCaps: Boolean = true, // only the Odin enforces per-mode watt caps; hidden on the SD 8 Gen 2
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AUTOTDP",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (enabled) "Auto-Tune games" else "Off",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                text = if (enabled) {
                    "Automatically tunes the CPU and GPU clocks on the fly, aiming to hold each app's " +
                        "frame rate at the lowest power. It uses your Custom fan if you've set one (it keeps " +
                        "running, cascaded), otherwise the Smart fan. The tier and manual clock controls below " +
                        "are locked while AutoTDP is on, but the fan stays adjustable. Per-app bindings still " +
                        "take priority, and a hand-tuned manual profile may still perform better in some games."
                } else {
                    "Automatically tunes the CPU and GPU clocks on the fly to hold your FPS target at the " +
                        "lowest power — games, emulators and even media — using your Custom fan if set, " +
                        "otherwise Smart, with the panel pinned to max refresh. Runs on any app except PULSE " +
                        "and the home screen; per-app bindings take priority."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (enabled) {
                Text(
                    text = "DEFAULT FPS TARGET",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fpsOptions.forEach { target ->
                        PulseChip(
                            label = PerAppConfig.fpsTargetLabel(target),
                            selected = fpsTarget == target,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onFpsTargetChange(target) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Aggressive park",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Offlines the prime cores on heavy emulators when they aren't the " +
                                "limiter (where a clock cap can't lower them), and brings them back the " +
                                "instant the frame rate dips. Bigger savings; slightly more aggressive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = aggressivePark, onCheckedChange = onAggressiveParkChange)
                }
                Text(
                    text = "EFFICIENCY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutoTdpBias.entries.forEach { b ->
                        PulseChip(
                            label = if (showWattCaps) "${b.label} · ${wattLabel(AutoTuneController.powerCeilingW(b))}W" else b.label,
                            selected = bias == b,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onBiasChange(b) },
                        )
                    }
                }
                Text(
                    text = (if (showWattCaps)
                        "Caps sustained power to ~${wattLabel(AutoTuneController.powerCeilingW(bias))} W — the " +
                            "chassis envelope; over it, heat outruns the fan. " else "") + when (bias) {
                        AutoTdpBias.EFFICIENT ->
                            "Harvests clocks hard while play is smooth; only steps in on a sustained stutter. " +
                                "Lowest power and quietest — may allow rare micro-hitches in the heaviest moments."
                        AutoTdpBias.BALANCED ->
                            "Middle ground — harvests in steady play but backs off a little earlier on roughness."
                        AutoTdpBias.SMOOTH ->
                            "Protects frames first, keeping clocks higher to avoid hitches. Highest power/heat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** CPU minimum-clock floor selector. 0 = no floor. Mirrors the GPU floor. */
@Composable
fun CpuFloorModule(currentPercent: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(0, 25, 50, 75)
    Column(modifier = modifier.fillMaxWidth()) {
        PulseSectionLabel("CPU FLOOR")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { pct ->
                PulseChip(
                    label = if (pct == 0) "Off" else "$pct%",
                    selected = currentPercent == pct,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onSelect(pct) },
                )
            }
        }
        Text(
            text = "Holds the CPU clusters above this share of their max (scaling_min_freq) — snappier response, more idle draw. Clamped below your cap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}
