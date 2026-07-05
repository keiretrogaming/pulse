package com.kei.pulse.overlay

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Deck-Glass control vocabulary for the Quick Access bar — custom-drawn (NOT Material `FilterChip`/`Switch`) so
 * the bar reads as a console overlay matching the OSD ([OverlayContent]), and so controller FOCUS (the row) is
 * visually distinct from the SELECTED value (the filled pill / dot / slider fill).
 *
 * The panel is forced onto a translucent-dark surface regardless of the app's light/dark theme, so neutral
 * text/outline colors come from [QaColors] (always light-on-dark, contrast-safe) while the ACCENT is the user's
 * theme `primary` (visible on dark either way).
 */
internal object QaColors {
    val Surface = Color.Black.copy(alpha = 0.82f)
    val RailFill = Color.White.copy(alpha = 0.05f)
    val FocusFill = Color.White.copy(alpha = 0.08f)
    val Text = Color(0xFFF3F4F6)
    val Muted = Color(0xFFB4B9C2)
    val Outline = Color.White.copy(alpha = 0.22f)
    val TrackBg = Color.White.copy(alpha = 0.12f)
}

/** True when the OS animator scale is 0 (Developer Options "Animations off" / reduced motion) — read once. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) == 0f
    }
}

/**
 * Wraps a row so that when it's the controller cursor ([focused]) it draws an accent LEFT-EDGE bar + a subtle
 * fill + a slight scale, and pulls itself into view (so the cursor never walks off-screen). The selected VALUE
 * lives inside [content] — separate from this row-focus treatment.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QaFocusRow(focused: Boolean, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val reduce = rememberReduceMotion()
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    val scale by animateFloatAsState(
        targetValue = if (focused && !reduce) 1.02f else 1f,
        animationSpec = tween(if (reduce) 0 else 150),
        label = "qaFocusScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) QaColors.FocusFill else Color.Transparent)
            .drawBehind {
                if (focused) {
                    val w = 3.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(w),
                    )
                }
            }
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
    ) { content() }
}

/** One mode in the vertical Mode list: a radio dot + label + tagline. Selected = filled accent dot + brighter label. */
@Composable
internal fun QaModeRow(label: String, tagline: String, selected: Boolean, onSelect: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
    ) {
        Box(
            modifier = Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) accent else QaColors.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                label,
                color = if (selected) QaColors.Text else QaColors.Text.copy(alpha = 0.82f),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (tagline.isNotBlank()) Text(tagline, color = QaColors.Muted, fontSize = 10.sp)
        }
    }
}

/** A labeled segmented selector: single-line pills (wraps if narrow). Selected pill = filled accent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QaSegmentedRow(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), color = QaColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, text ->
                val sel = i == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) accent else Color.Transparent)
                        .border(1.dp, if (sel) accent else QaColors.Outline, RoundedCornerShape(8.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text,
                        color = if (sel) MaterialTheme.colorScheme.onPrimary else QaColors.Text,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** A label + accent pill toggle. The whole row taps; the controller path activates via [onToggle]. */
@Composable
internal fun QaToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Text(label, color = QaColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        QaTogglePill(checked)
    }
}

@Composable
private fun QaTogglePill(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) accent else QaColors.TrackBg)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) MaterialTheme.colorScheme.onPrimary else QaColors.Muted))
    }
}

/** A label + −/value/+ stepper. Controller steps via ←/→; the buttons handle touch. */
@Composable
internal fun QaStepperRow(label: String, value: String, onStep: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = QaColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        QaStepButton("−") { onStep(-1) }
        Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        QaStepButton("+") { onStep(1) }
    }
}

@Composable
private fun QaStepButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, QaColors.Outline, RoundedCornerShape(7.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = QaColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// Live telemetry values use the shared MeterColors ramp (the same cool→warm→hot the OSD uses).
