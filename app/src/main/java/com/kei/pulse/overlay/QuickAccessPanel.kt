package com.kei.pulse.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kei.pulse.data.FanController
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.OverlayPreset
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.RgbMode
import com.kei.pulse.model.resolveEffectiveAutoTdpValues
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

private val FPS_OPTIONS = listOf(30, 60, 120)
private const val FLASH_MS = 1800L // how long an applied-feedback confirmation stays in the footer

/**
 * One controller-focusable control in a tab. [render] draws it (highlighted when it's the cursor); [onActivate]
 * /[onLeft]/[onRight] are what the A button / D-pad left-right do to it. Touch still works via the inner control's
 * own onClick — these just add the controller path on top.
 */
private class NavItem(
    val render: @Composable (focused: Boolean) -> Unit,
    val onActivate: () -> Unit = {},
    val onLeft: () -> Unit = {},
    val onRight: () -> Unit = {},
)

/** Root of the Quick Access overlay content — a collapsed handle, or the expanded right-docked panel. */
@Composable
fun QuickAccessContent(
    statsFlow: StateFlow<OverlayStats>,
    settingsFlow: StateFlow<AppSettings>,
    perAppFlow: StateFlow<PerAppConfig?>,
    expandedFlow: StateFlow<Boolean>,
    showHandleFlow: StateFlow<Boolean>,
    navIntents: SharedFlow<QuickAccessNavIntent>,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onAction: (QuickAccessAction) -> Unit,
) {
    val expanded by expandedFlow.collectAsState()
    if (!expanded) {
        val showHandle by showHandleFlow.collectAsState()
        if (showHandle) QuickAccessHandle(onExpand) else Box(Modifier.size(1.dp)) // combo-only: invisible
        return
    }
    val stats by statsFlow.collectAsState()
    val settings by settingsFlow.collectAsState()
    val perApp by perAppFlow.collectAsState()
    QuickAccessPanel(stats, settings, perApp, navIntents, onClose, onAction)
}

@Composable
private fun QuickAccessHandle(onExpand: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 22.dp, height = 66.dp)
            .background(cs.primary.copy(alpha = 0.92f), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            .clickable { onExpand() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ChevronLeft, contentDescription = "Open Quick Access bar", tint = cs.onPrimary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun QuickAccessPanel(
    stats: OverlayStats,
    settings: AppSettings,
    perApp: PerAppConfig?,
    navIntents: SharedFlow<QuickAccessNavIntent>,
    onClose: () -> Unit,
    onAction: (QuickAccessAction) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tabs = QuickAccessTab.entries
    var tabIndex by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableIntStateOf(0) }
    // Optimistic local values for the System sliders (brightness/volume) so ←/→/tap step instantly instead of
    // waiting a poll for the telemetry readback; re-synced from telemetry while not being touched. Keyed by id.
    val sliderLocal = remember { mutableStateMapOf<String, Int>() }
    // Applied-feedback flash: a short confirmation line in the footer after a commit ("Profile removed — following
    // Global"). Auto-clears; flashKey retriggers the timer when the same text flashes twice in a row.
    var flash by remember { mutableStateOf<String?>(null) }
    var flashKey by remember { mutableIntStateOf(0) }
    val showFlash: (String) -> Unit = { flash = it; flashKey++ }
    LaunchedEffect(flashKey) { if (flash != null) { kotlinx.coroutines.delay(FLASH_MS); flash = null } }
    // The scope control's UNCOMMITTED ←/→ selection (browsing must never apply — committing Global deletes the
    // game's profile, so it takes an explicit A press / tap). Cleared when the committed value catches up or on
    // tab change.
    var pendingScope by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.quickAccessPerGameScope) { pendingScope = null }
    LaunchedEffect(tabIndex) { pendingScope = null }
    val tab = tabs[tabIndex]
    // Every applied control flashes its confirmation in the footer (browsing==editing made a silent apply a
    // real incident; the flash makes each one visible). Sliders + scope flash their own way (null label).
    val dispatch: (QuickAccessAction) -> Unit = { a ->
        onAction(a)
        flashLabel(a)?.let(showFlash)
    }
    // The live control list for the active tab. Rebuilt each recomposition off the current settings/perApp, so
    // selections + visibility (e.g. AutoTDP sub-controls) are always current.
    val items: List<NavItem> = when (tab) {
        QuickAccessTab.PERFORMANCE ->
            performanceItems(stats, settings, perApp, sliderLocal, pendingScope, { pendingScope = it }, showFlash, dispatch)
        QuickAccessTab.FAN -> fanItems(settings, dispatch)
        QuickAccessTab.RGB -> lightingItems(settings, dispatch)
        QuickAccessTab.OVERLAY -> overlayItems(settings, dispatch)
        QuickAccessTab.SYSTEM -> systemItems(stats, sliderLocal, dispatch)
    }
    // The intent collector is long-lived; rememberUpdatedState so it always sees the CURRENT list/tab (the
    // pointerInput/collector-captures-once gotcha).
    val curItems by rememberUpdatedState(items)
    val curTabIndex by rememberUpdatedState(tabIndex)
    LaunchedEffect(items.size) { cursor = QuickAccessNav.clampItem(cursor, items.size) }
    // Reconcile the optimistic System-slider values: once telemetry reports the value the device actually
    // reached, drop the local override so the slider re-syncs to the live system value (e.g. an external
    // brightness/volume change is then reflected) instead of sticking to a stale optimistic value forever.
    LaunchedEffect(stats.brightnessPercent, stats.volumePercent, stats.gpuCapKhz) {
        if (sliderLocal["bri"] == stats.brightnessPercent) sliderLocal.remove("bri")
        if (sliderLocal["vol"] == stats.volumePercent) sliderLocal.remove("vol")
        if (sliderLocal["gpucap"] == stats.gpuCapKhz) sliderLocal.remove("gpucap")
    }
    LaunchedEffect(Unit) {
        navIntents.collect { intent ->
            when (intent) {
                QuickAccessNavIntent.TAB_PREV -> { tabIndex = QuickAccessNav.moveTab(curTabIndex, -1, tabs.size); cursor = 0 }
                QuickAccessNavIntent.TAB_NEXT -> { tabIndex = QuickAccessNav.moveTab(curTabIndex, 1, tabs.size); cursor = 0 }
                QuickAccessNavIntent.UP -> cursor = QuickAccessNav.moveItem(cursor, -1, curItems.size)
                QuickAccessNavIntent.DOWN -> cursor = QuickAccessNav.moveItem(cursor, 1, curItems.size)
                QuickAccessNavIntent.LEFT -> curItems.getOrNull(cursor)?.onLeft?.invoke()
                QuickAccessNavIntent.RIGHT -> curItems.getOrNull(cursor)?.onRight?.invoke()
                QuickAccessNavIntent.ACTIVATE -> curItems.getOrNull(cursor)?.onActivate?.invoke()
            }
        }
    }

    // Entrance: a subtle slide-in + fade from the right edge (the panel is docked right). Reduced-motion → instant.
    val reduce = rememberReduceMotion()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enter by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(if (reduce) 0 else 200, easing = FastOutSlowInEasing),
        label = "qaEnter",
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                translationX = (1f - enter) * 36.dp.toPx()
            }
            .background(QaColors.Surface)
            .drawBehind {
                val w = 2.dp.toPx() // accent edge facing the game
                drawRect(color = accent.copy(alpha = 0.55f), topLeft = Offset(0f, 0f), size = Size(w, size.height))
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier.fillMaxHeight().width(46.dp).background(QaColors.RailFill).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RailIcon(Icons.Rounded.Bolt, tab == QuickAccessTab.PERFORMANCE) { tabIndex = 0; cursor = 0 }
                RailIcon(Icons.Rounded.Air, tab == QuickAccessTab.FAN) { tabIndex = 1; cursor = 0 }
                RailIcon(Icons.Rounded.Lightbulb, tab == QuickAccessTab.RGB) { tabIndex = 2; cursor = 0 }
                RailIcon(Icons.Rounded.Dashboard, tab == QuickAccessTab.OVERLAY) { tabIndex = 3; cursor = 0 }
                RailIcon(Icons.Rounded.Tune, tab == QuickAccessTab.SYSTEM) { tabIndex = 4; cursor = 0 }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PULSE", color = accent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    stats.telemetry.batteryPercent?.let { Text("$it%", color = QaColors.Muted, fontSize = 12.sp) }
                }
                Text(tabTitle(tab), color = QaColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                TelemetryStrip(stats)
                Box(Modifier.fillMaxWidth().height(1.dp).background(QaColors.Outline))
                items.forEachIndexed { i, item -> item.render(i == cursor) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(QaColors.RailFill).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The footer doubles as the applied-feedback line: a commit flashes its confirmation here briefly.
            Text(
                flash ?: "↑↓ move · ←→ adjust · A select · B close",
                color = if (flash != null) accent else QaColors.Muted,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
                fontWeight = if (flash != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

// ---- Per-tab control lists (pure builders; each NavItem carries its render + controller actions) ----

private fun performanceItems(
    stats: OverlayStats,
    settings: AppSettings,
    perApp: PerAppConfig?,
    sliderLocal: MutableMap<String, Int>,
    pendingScope: Int?,
    onPendingScope: (Int?) -> Unit,
    onFlash: (String) -> Unit,
    onAction: (QuickAccessAction) -> Unit,
): List<NavItem> {
    // "PROFILE: Per-Game | Global" — where the perf edits land. Per-Game reflects/edits the foreground game's
    // per-app profile; Global reflects/edits the global default. COMMITTING the switch has real semantics
    // (QuickAccessScope.scopeCommitPlan): Global deletes the game's profile, Per-Game creates one — so the
    // control is A-commit (←/→ only moves a pending highlight; browsing can never delete a profile).
    val perGame = settings.quickAccessPerGameScope
    val autoOn = if (perGame) QuickAccessPerApp.effectiveAutoTdpOn(perApp, settings.autoTdpDefaultEnabled)
    else settings.autoTdpDefaultEnabled
    val tier = if (perGame) PerAppConfig.tierFromBinding(perApp?.profileBinding)
    else PowerTier.entries.firstOrNull { it.label == settings.activeTierLabel }
    // Mode is a VERTICAL radio list (D-pad ↑/↓ moves between modes, A selects) — index 0 = AutoTDP, 1.. = the
    // power tiers, exactly one filled. A wrapped horizontal chip row used to trap the cursor (you could only
    // reach AutoTDP/AAA-Max with ←/→ and ↓ skipped the whole control); a radio list makes every mode reachable
    // and matches the Deck. `selectedMode == -1` (explicit AUTO_OFF) simply shows no dot filled.
    val selectedMode = when {
        autoOn -> 0
        tier != null -> 1 + PowerTier.entries.indexOf(tier)
        else -> -1
    }
    val items = mutableListOf<NavItem>()
    items += scopeNavItem(
        committedIndex = if (perGame) 0 else 1,
        pending = pendingScope,
        hasProfile = perApp != null,
        onPending = onPendingScope,
        onCommit = { i ->
            val toPerGame = i == 0
            if (toPerGame != perGame) {
                onAction(QuickAccessAction.SetScope(toPerGame))
                onFlash(
                    when {
                        toPerGame && perApp == null -> "Per-game profile created"
                        toPerGame -> "Editing this game's profile"
                        perApp != null -> "Profile removed — following Global"
                        else -> "Following Global"
                    },
                )
            }
            onPendingScope(null)
        },
    )
    items += modeRadioItem("AutoTDP", "Auto-hold target FPS at min power", selectedMode == 0) {
        if (!autoOn) onAction(QuickAccessAction.ToggleAutoTdp) // selecting AutoTDP turns it on (never off)
    }
    PowerTier.entries.forEachIndexed { i, t ->
        items += modeRadioItem(t.label, t.tagline, selectedMode == i + 1) { onAction(QuickAccessAction.SetTier(t)) }
    }
    // "Stock — don't tune": the explicit hands-off mode. Per-game = the AUTO_OFF binding (sticks even when the
    // global default is on — e.g. don't tune a benchmark); All-games = the global default off. Gives the old
    // "nothing selected" state a real identity.
    val stockSelected = if (perGame) PerAppConfig.isAutoOff(perApp?.profileBinding) else !autoOn && tier == null
    items += modeRadioItem(
        "Stock",
        if (perGame) "Don't tune this game — PULSE hands off" else "No tuning by default",
        stockSelected,
    ) { if (!stockSelected) onAction(QuickAccessAction.SetStockMode) }
    // Gate on the SELECTED MODE being Custom (AutoTDP outranks the tier, same precedence as the radio list) —
    // gating on `tier` alone left these stuck open after picking AutoTDP in Global scope, because the global
    // activeTierLabel stays "Custom" (it's never null) even while AutoTDP is the active mode.
    if (!autoOn && tier == PowerTier.CUSTOM) {
        // Custom's in-game levers (fixes "Custom shows nothing to tune"). Both edit the GLOBAL Custom tuning —
        // they define the Custom tier itself; a per-game Custom binding references the same values.
        // Power Target: 100% = uncapped. Value comes from settings (the reactive feed reflects it within ms).
        val pt = if (settings.powerTargetEnabled) settings.powerTargetPercent else 100
        items += sliderNavItem("Power target", pt) { v ->
            onAction(QuickAccessAction.SetPowerTarget(v.coerceIn(QuickAccess.POWER_TARGET_MIN, QuickAccess.POWER_TARGET_MAX)))
        }
        // GPU cap: steps through the Adreno's supported levels. The shown value is the LIVE device readback
        // (stats.gpuCapKhz) with an optimistic local override so rapid steps chain from the intended level.
        val levels = stats.gpuLevels?.sorted()
        if (!levels.isNullOrEmpty()) {
            val cap = sliderLocal["gpucap"] ?: stats.gpuCapKhz ?: levels.last()
            val idx = levels.indices.minByOrNull { kotlin.math.abs(levels[it] - cap) } ?: levels.lastIndex
            items += stepperNavItem("GPU cap", "${levels[idx] / 1000} MHz") { d ->
                val next = levels[(idx + d).coerceIn(0, levels.lastIndex)]
                if (next != levels[idx]) {
                    sliderLocal["gpucap"] = next
                    onAction(QuickAccessAction.SetGpuCap(next))
                }
            }
        }
    }
    if (autoOn) {
        val effective = resolveEffectiveAutoTdpValues(
            config = if (perGame) {
                perApp
            } else {
                null
            },
            global = settings,
        )
        items += chipNavItem(
            "Frame target",
            FPS_OPTIONS.map { it.toString() },
            FPS_OPTIONS.indexOf(effective.fpsTarget),
        ) { i ->
            onAction(QuickAccessAction.SetFpsTarget(FPS_OPTIONS[i]))
        }
        items += chipNavItem(
            "Bias",
            AutoTdpBias.entries.map { biasLabel(it) },
            AutoTdpBias.entries.indexOf(effective.bias),
        ) { i ->
            onAction(QuickAccessAction.SetBias(AutoTdpBias.entries[i]))
        }
        items += toggleNavItem("Aggressive park", effective.aggressivePark) {
            onAction(QuickAccessAction.SetAggressivePark(!effective.aggressivePark))
        }
    }
    return items
}

private fun fanItems(settings: AppSettings, onAction: (QuickAccessAction) -> Unit): List<NavItem> {
    val items = mutableListOf<NavItem>()
    val modes = FanController.MODES
    items += chipNavItem("Fan mode", modes.map { it.label }, modes.indexOfFirst { it.value == settings.managedFanMode }) { i ->
        onAction(QuickAccessAction.SetFanMode(modes[i].value))
    }
    if (settings.managedFanMode == FanController.CUSTOM) {
        items += toggleNavItem("Hold target temp", settings.fanSmartEnabled) { onAction(QuickAccessAction.SetFanSmart(!settings.fanSmartEnabled)) }
        if (settings.fanSmartEnabled) {
            items += stepperNavItem("Target temp", "${settings.fanTargetTempC}°C") { d ->
                onAction(QuickAccessAction.SetFanTargetTemp(settings.fanTargetTempC + d))
            }
        } else {
            // Curve mode: the Cooler⟷Quieter live offset (steps of 5, ±FanCurve.MAX_BIAS; + = cooler/louder).
            // Curve-knee editing stays in the app — drag doesn't translate to a D-pad.
            items += stepperNavItem("Cooler ⟷ Quieter", fanBiasLabel(settings.fanBias)) { d ->
                val next = (settings.fanBias + d * 5)
                    .coerceIn(-com.kei.pulse.model.FanCurve.MAX_BIAS, com.kei.pulse.model.FanCurve.MAX_BIAS)
                if (next != settings.fanBias) onAction(QuickAccessAction.SetFanBias(next))
            }
        }
    }
    return items
}

/** "+10 cooler" / "0" / "−10 quieter" — the bias stepper's value readout. */
private fun fanBiasLabel(bias: Int): String = when {
    bias > 0 -> "+$bias cooler"
    bias < 0 -> "$bias quieter"
    else -> "0"
}

private fun lightingItems(settings: AppSettings, onAction: (QuickAccessAction) -> Unit): List<NavItem> {
    val items = mutableListOf<NavItem>()
    items += chipNavItem("RGB mode", RgbMode.entries.map { it.label }, RgbMode.entries.indexOf(settings.rgbMode)) { i ->
        onAction(QuickAccessAction.SetRgbMode(RgbMode.entries[i]))
    }
    if (settings.rgbMode == RgbMode.MANUAL) {
        items += swatchNavItem(RGB_SWATCHES, settings.rgbManualLeftColor) { c -> onAction(QuickAccessAction.SetRgbColor(c)) }
    }
    return items
}

private fun overlayItems(settings: AppSettings, onAction: (QuickAccessAction) -> Unit): List<NavItem> = buildList {
    add(toggleNavItem("Show overlay", settings.overlayEnabled) { onAction(QuickAccessAction.SetOverlayEnabled(!settings.overlayEnabled)) })
    add(chipNavItem("Density", OverlayPreset.entries.map { it.label }, OverlayPreset.entries.indexOf(settings.overlayPreset)) { i ->
        onAction(QuickAccessAction.SetOverlayPreset(OverlayPreset.entries[i]))
    })
}

private fun systemItems(
    stats: OverlayStats,
    local: MutableMap<String, Int>,
    onAction: (QuickAccessAction) -> Unit,
): List<NavItem> = buildList {
    // Optimistic value wins once touched (so rapid ←/→ steps from the right base); else the live telemetry value.
    val bri = local["bri"] ?: stats.brightnessPercent ?: 50
    add(sliderNavItem("Brightness", bri) { v -> local["bri"] = v; onAction(QuickAccessAction.SetBrightness(v)) })
    val vol = local["vol"] ?: stats.volumePercent ?: 50
    add(sliderNavItem("Volume", vol) { v -> local["vol"] = v; onAction(QuickAccessAction.SetVolume(v)) })
}

// ---- NavItem builders: a segmented selector adjusts with D-pad ←/→; modes/toggles activate with A. ----

private fun modeRadioItem(label: String, tagline: String, selected: Boolean, onSelect: () -> Unit) = NavItem(
    render = { focused -> QaFocusRow(focused) { QaModeRow(label, tagline, selected, onSelect) } },
    onActivate = onSelect,
)

/**
 * The "PROFILE: Per-Game | Global" control. Unlike [chipNavItem], ←/→ only moves a PENDING highlight and the
 * switch happens on A (or a direct tap — touch is deliberate, no browse hazard): committing Global DELETES the
 * game's per-app profile, and a destructive edit must never fire from D-pad browsing (the bias=SMOOTH incident
 * class). While pending differs from committed, a hint line spells out exactly what A will do.
 */
private fun scopeNavItem(
    committedIndex: Int,
    pending: Int?,
    hasProfile: Boolean,
    onPending: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
): NavItem {
    val options = listOf("Per-Game", "Global")
    val shown = pending ?: committedIndex
    val dirty = pending != null && pending != committedIndex
    fun moveTo(target: Int) = onPending(if (target == committedIndex) null else target)
    return NavItem(
        render = { focused ->
            QaFocusRow(focused) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    QaSegmentedRow("Profile", options, shown) { i -> onCommit(i) }
                    if (dirty) {
                        Text(
                            if (shown == 1 && hasProfile) {
                                "Ⓐ apply — removes this game's profile"
                            } else if (shown == 0) {
                                "Ⓐ apply — gives this game its own profile"
                            } else {
                                "Ⓐ apply"
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        },
        onActivate = { if (dirty) onCommit(shown) },
        onLeft = { moveTo((shown - 1).coerceAtLeast(0)) },
        onRight = { moveTo((shown + 1).coerceAtMost(options.size - 1)) },
    )
}

private fun chipNavItem(label: String, options: List<String>, selectedIndex: Int, onPick: (Int) -> Unit) = NavItem(
    render = { focused -> QaFocusRow(focused) { QaSegmentedRow(label, options, selectedIndex, onPick) } },
    // A is reserved for modes/toggles/buttons; a segmented selector is adjusted with D-pad LEFT/RIGHT. Touch
    // still taps a chip directly.
    onActivate = {},
    // No-op at the edges (don't re-dispatch the same value, which would re-fire a DataStore write + device
    // apply on every press at the boundary); an unselected row (-1) lands on the first option once.
    onLeft = { if (selectedIndex > 0) onPick(selectedIndex - 1) else if (selectedIndex < 0 && options.isNotEmpty()) onPick(0) },
    onRight = { if (selectedIndex in 0 until options.size - 1) onPick(selectedIndex + 1) else if (selectedIndex < 0 && options.isNotEmpty()) onPick(0) },
)

private fun toggleNavItem(label: String, checked: Boolean, onToggle: () -> Unit) = NavItem(
    render = { focused -> QaFocusRow(focused) { QaToggleRow(label, checked) { onToggle() } } },
    onActivate = onToggle,
    onLeft = onToggle,
    onRight = onToggle,
)

private fun stepperNavItem(label: String, value: String, onStep: (Int) -> Unit) = NavItem(
    render = { focused -> QaFocusRow(focused) { QaStepperRow(label, value) { onStep(it) } } },
    onLeft = { onStep(-1) },
    onRight = { onStep(1) },
)

private fun swatchNavItem(colors: List<Int>, selected: Int, onPick: (Int) -> Unit): NavItem {
    val idx = colors.indexOfFirst { (it or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt()) }.coerceAtLeast(0)
    return NavItem(
        render = { focused -> QaFocusRow(focused) { ColorSwatchRow(colors, selected, onPick) } },
        onLeft = { onPick(colors[(idx - 1).coerceAtLeast(0)]) },
        onRight = { onPick(colors[(idx + 1).coerceAtMost(colors.size - 1)]) },
    )
}

private fun sliderNavItem(label: String, percent: Int, step: Int = 5, onSet: (Int) -> Unit) = NavItem(
    render = { focused -> QaFocusRow(focused) { QaSlider(label, percent, onSet) } },
    onLeft = { onSet((percent - step).coerceIn(0, 100)) },
    onRight = { onSet((percent + step).coerceIn(0, 100)) },
)

// ---- Slider (controller ←/→ in 5% steps + tap-to-set; the % readout updates optimistically) ----

@Composable
private fun QaSlider(label: String, percent: Int, onSet: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val onSetState = rememberUpdatedState(onSet) // pointerInput captures once — read the latest via state
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = QaColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("$percent%", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(QaColors.TrackBg)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onSetState.value((offset.x / size.width * 100f).roundToInt().coerceIn(0, 100))
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((percent.coerceIn(0, 100)) / 100f)
                    .background(accent, RoundedCornerShape(6.dp)),
            )
        }
    }
}

private val RGB_SWATCHES = listOf(
    0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, 0xFF5856D6, 0xFFFF2D55, 0xFFFFFFFF,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchRow(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("COLOR", color = QaColors.Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { c ->
                val isSel = (c or 0xFF000000.toInt()) == (selected or 0xFF000000.toInt())
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(c))
                        .border(if (isSel) 2.dp else 1.dp, if (isSel) QaColors.Text else QaColors.Outline, RoundedCornerShape(6.dp))
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun RailIcon(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) accent else Color.Transparent).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else QaColors.Muted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun TelemetryStrip(stats: OverlayStats) {
    val accent = MaterialTheme.colorScheme.primary
    val t = stats.telemetry
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${stats.fps?.fps?.toInt() ?: "—"} FPS", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("CPU ${t.cpuTempC ?: "—"}°", color = meterTempColor(t.cpuTempC), fontSize = 12.sp)
        Text("GPU ${t.gpuTempC ?: "—"}°", color = meterTempColor(t.gpuTempC), fontSize = 12.sp)
        stats.powerDrawW?.let { Text("%.1f W".format(it), color = QaColors.Muted, fontSize = 12.sp) }
    }
}

private fun tabTitle(tab: QuickAccessTab): String = when (tab) {
    QuickAccessTab.PERFORMANCE -> "Performance"
    QuickAccessTab.FAN -> "Fan"
    QuickAccessTab.RGB -> "Lighting"
    QuickAccessTab.OVERLAY -> "Overlay"
    QuickAccessTab.SYSTEM -> "System"
}

private fun biasLabel(b: AutoTdpBias): String = when (b) {
    AutoTdpBias.EFFICIENT -> "Efficient"
    AutoTdpBias.BALANCED -> "Balanced"
    AutoTdpBias.SMOOTH -> "Smooth"
}

/**
 * The footer confirmation for an applied action ("Bias Smooth", "Fan Sport"). Null = the control provides its
 * own feedback (sliders show their value live; the scope commit flashes a richer message of its own).
 */
private fun flashLabel(a: QuickAccessAction): String? = when (a) {
    QuickAccessAction.ToggleAutoTdp -> "Mode: AutoTDP"
    is QuickAccessAction.SetTier -> "Mode: ${a.tier.label}"
    QuickAccessAction.SetStockMode -> "Mode: Stock"
    is QuickAccessAction.SetFpsTarget -> "Frame target ${a.fps}"
    is QuickAccessAction.SetBias -> "Bias ${biasLabel(a.bias)}"
    is QuickAccessAction.SetAggressivePark -> "Aggressive park ${if (a.enabled) "on" else "off"}"
    is QuickAccessAction.SetFanMode -> "Fan ${FanController.MODES.firstOrNull { it.value == a.mode }?.label ?: a.mode}"
    is QuickAccessAction.SetFanSmart -> "Hold target temp ${if (a.enabled) "on" else "off"}"
    is QuickAccessAction.SetFanTargetTemp -> "Target temp ${a.tempC}°C"
    is QuickAccessAction.SetFanBias -> "Fan bias ${fanBiasLabel(a.bias)}"
    is QuickAccessAction.SetPowerTarget -> "Power target ${a.percent}%"
    is QuickAccessAction.SetGpuCap -> "GPU cap ${a.freqKhz / 1000} MHz"
    is QuickAccessAction.SetRgbMode -> "Lighting ${a.mode.label}"
    is QuickAccessAction.SetRgbColor -> "Color applied"
    is QuickAccessAction.SetOverlayEnabled -> "Overlay ${if (a.enabled) "on" else "off"}"
    is QuickAccessAction.SetOverlayPreset -> "Overlay ${a.preset.label}"
    is QuickAccessAction.SetBrightness, is QuickAccessAction.SetVolume -> null
    is QuickAccessAction.SetScope -> null
}
