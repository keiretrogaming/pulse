package com.kei.pulse.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kei.pulse.model.AppColorSource
import com.kei.pulse.model.OverlayElement
import com.kei.pulse.model.OverlayPreset
import com.kei.pulse.model.PulseThemeId
import com.kei.pulse.model.RgbMode
import com.kei.pulse.model.RgbStick
import com.kei.pulse.ui.theme.HudBackground
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.TileInteractionBehavior
import kotlin.math.roundToInt

private val accentColorOptions = listOf(
    0xFF3F51B5.toInt(),
    0xFF006E1C.toInt(),
    0xFFB3261E.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00639A.toInt(),
    0xFF9A4600.toInt(),
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onPulseEnabledChange: (Boolean) -> Unit = {},
    onRgbModeChange: (RgbMode) -> Unit = {},
    onRgbManualTargetChange: (RgbStick) -> Unit = {},
    onRgbManualStickChange: (RgbStick, Int, Float) -> Unit = { _, _, _ -> },
    onColorSourceChange: (AppColorSource) -> Unit,
    onThemeChange: (PulseThemeId) -> Unit,
    onAccentColorChange: (Int) -> Unit,
    onTileTapBehaviorChange: (TileInteractionBehavior) -> Unit,
    onApplyLastProfileOnBootChange: (Boolean) -> Unit,
    sleepProfileOptions: List<PerformanceProfile>,
    onSleepProfileEnabledChange: (Boolean) -> Unit,
    onSleepProfileChange: (String?) -> Unit,
    onResetProfiles: () -> Unit,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onRequestAddQuickSettingsTile: () -> Unit,
    canRequestAddQuickSettingsTile: Boolean,
    isQuickSettingsTileAdded: Boolean,
    perAppEnabled: Boolean = false,
    perAppConfiguredCount: Int = 0,
    onPerAppEnabledChange: (Boolean) -> Unit = {},
    onOpenPerApps: () -> Unit = {},
    perAppSwitchNotices: Boolean = true,
    onPerAppSwitchNoticesChange: (Boolean) -> Unit = {},
    overlayEnabled: Boolean = false,
    overlayPreset: OverlayPreset = OverlayPreset.COMPACT,
    overlayElements: Set<OverlayElement> = OverlayPreset.COMPACT.elements,
    overlayOpacity: Int = 90,
    onOverlayEnabledChange: (Boolean) -> Unit = {},
    onOverlayPresetChange: (OverlayPreset) -> Unit = {},
    onOverlayElementToggle: (OverlayElement, Boolean) -> Unit = { _, _ -> },
    onOverlayOpacityChange: (Int) -> Unit = {},
    onQuickAccessChange: (Boolean) -> Unit = {},
    onQuickAccessShowHandleChange: (Boolean) -> Unit = {},
    onSetQuickAccessCombo: () -> Unit = {},
    onClearQuickAccessCombo: () -> Unit = {},
    capturingCombo: Boolean = false,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    HudBackground(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "P.U.L.S.E.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Performance Utility for Load and System Efficiency",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBack) {
                Text("Done")
            }
        }

        SettingsSection(title = "PULSE") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (settings.pulseEnabled) "PULSE is active" else "System in control",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Master switch. Turn OFF to hand every control back to manufacturer stock — " +
                            "uncapped clocks, Smart fan, restored governor/refresh — and fully stop PULSE. " +
                            "Do this before uninstalling for a clean device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.pulseEnabled,
                    onCheckedChange = onPulseEnabledChange,
                )
            }
        }

        SettingsSection(title = "Appearance") {
            ThemeSelector(selected = settings.themeId, onSelect = onThemeChange)
        }

        SettingsSection(title = "Quick Settings Tile") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Add PULSE to Quick Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onRequestAddQuickSettingsTile,
                    enabled = canRequestAddQuickSettingsTile && !isQuickSettingsTileAdded,
                ) {
                    Text(
                        when {
                            isQuickSettingsTileAdded -> "Tile already added"
                            canRequestAddQuickSettingsTile -> "Add tile"
                            else -> "Unavailable"
                        },
                    )
                }
            }
            SettingsControlGroup(label = "Single tap") {
                TileBehaviorSelector(
                    selected = settings.tileTapBehavior,
                    onChange = onTileTapBehaviorChange,
                )
            }
        }

        SettingsSection(title = "Startup") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Apply last profile on device boot",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When enabled, the app will attempt to restore the last applied profile after boot.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.applyLastProfileOnBoot,
                    onCheckedChange = onApplyLastProfileOnBootChange,
                )
            }
        }

        SettingsSection(title = "Sleep") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Apply sleep profile",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When enabled, PULSE keeps a low-priority notification so it can apply this profile when the screen turns off and restore the previous limits when the device wakes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.sleepProfileEnabled,
                    onCheckedChange = onSleepProfileEnabledChange,
                    enabled = sleepProfileOptions.isNotEmpty(),
                )
            }
            if (sleepProfileOptions.isEmpty()) {
                Text(
                    text = "No profiles are available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                SettingsControlGroup(label = "Profile while asleep") {
                    SleepProfileSelector(
                        profiles = sleepProfileOptions,
                        selectedProfileId = settings.sleepProfileId,
                        enabled = settings.sleepProfileEnabled,
                        onChange = onSleepProfileChange,
                    )
                }
            }
        }

        SettingsSection(title = "Per-app profiles") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Switch profiles per app",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Applies an app's bound profile and extras when it launches, and restores the previous state when it exits. Needs the Usage access permission.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = perAppEnabled,
                    onCheckedChange = onPerAppEnabledChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (perAppConfiguredCount == 1) {
                        "1 app configured"
                    } else {
                        "$perAppConfiguredCount apps configured"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenPerApps) {
                    Text("Configure apps")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Switch notifications",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Show a toast and status notification when a per-app profile applies or restores.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = perAppSwitchNotices,
                    onCheckedChange = onPerAppSwitchNoticesChange,
                )
            }
        }

        SettingsSection(title = "On-screen overlay") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Show in-game overlay",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Floats live FPS, clocks, temps, power and battery time-left over apps you've configured in Per-app profiles. Needs the \"Display over other apps\" permission. While unlocked, drag to reposition and tap LAYOUT to switch density; the notification's \"Move overlay\" action toggles the lock during play.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = overlayEnabled,
                    onCheckedChange = onOverlayEnabledChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Quick Access bar",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Adjust performance, fan, and lighting without leaving your game. Open the panel " +
                            "with the edge handle or your controller shortcut — changes apply instantly.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.quickAccessEnabled,
                    onCheckedChange = onQuickAccessChange,
                )
            }
            if (settings.quickAccessEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Edge handle",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Show a small tab on the screen edge that opens the panel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.quickAccessShowHandle,
                        onCheckedChange = onQuickAccessShowHandleChange,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Controller shortcut: " +
                            com.kei.pulse.data.InputComboParser.displayName(
                                com.kei.pulse.data.InputComboParser.decode(settings.quickAccessCombo),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (capturingCombo) {
                            Text(
                                text = "Hold the buttons on your controller…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        } else {
                            TextButton(onClick = onSetQuickAccessCombo) { Text("Set shortcut") }
                            TextButton(onClick = onClearQuickAccessCombo) { Text("Clear") }
                        }
                    }
                }
            }
            SettingsControlGroup(label = "Layout · density + quick-fill") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverlayPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = overlayPreset == preset,
                            onClick = { onOverlayPresetChange(preset) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }
            SettingsControlGroup(label = "Shown items") {
                Text(
                    text = "Tap to add or remove. Grouped by what it measures; a few items only render in the " +
                        "Detailed/Full layouts (core bars, chip name, FPS trend).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OverlayItemGroups.forEach { group ->
                    OverlayItemGroup(
                        title = group.title,
                        items = group.items,
                        selected = overlayElements,
                        onToggle = onOverlayElementToggle,
                    )
                }
            }
            SettingsControlGroup(label = "Opacity · $overlayOpacity%") {
                Slider(
                    value = overlayOpacity.toFloat(),
                    onValueChange = { onOverlayOpacityChange(it.roundToInt()) },
                    valueRange = 40f..100f,
                )
            }
        }

        SettingsSection(title = "Joystick RGB") {
            Text(
                text = "Color the controller's joystick LEDs. Battery and Heat glow with device status; " +
                    "Manual sets your own color per stick. Turn the lights on in your system settings to see them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RgbMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.rgbMode == mode,
                        onClick = { onRgbModeChange(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (settings.rgbMode == RgbMode.MANUAL) {
                ManualRgbControls(
                    settings = settings,
                    onTargetChange = onRgbManualTargetChange,
                    onStickChange = onRgbManualStickChange,
                )
            }
        }

        SettingsSection(title = "Profiles") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Share profiles",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Export profiles to JSON or import a shared profile file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onImportProfiles) {
                        Text("Import")
                    }
                    TextButton(onClick = onExportProfiles) {
                        Text("Export")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Reset profiles to default",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Bundled profiles are restored and custom profiles are removed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { showResetConfirmation = true }) {
                    Text("Reset")
                }
            }
        }
        SettingsSection(title = "About") {
            Text(
                text = "P.U.L.S.E.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Performance Utility for Load and System Efficiency",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "No-root CPU + GPU control for AYN Odin 3, AYN Thor and Retroid Pocket 6.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset profiles?") },
            text = {
                Text("This removes custom profiles and restores the bundled defaults.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        onResetProfiles()
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepProfileSelector(
    profiles: List<PerformanceProfile>,
    selectedProfileId: String?,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { profile -> profile.id == selectedProfileId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedProfile?.name ?: "Select profile",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onChange(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: AppColorSource,
    onChange: (AppColorSource) -> Unit,
    selectedAccentColor: Int,
    onAccentColorChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeModeOption(
            title = "System colors",
            selected = selected == AppColorSource.SYSTEM,
            onClick = { onChange(AppColorSource.SYSTEM) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected == AppColorSource.CUSTOM_ACCENT,
                onClick = { onChange(AppColorSource.CUSTOM_ACCENT) },
            )
            Text(
                text = "Custom",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
            Row(
                modifier = Modifier.padding(start = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                accentColorOptions.forEach { accentColor ->
                    AccentSwatch(
                        color = Color(accentColor),
                        selected = selectedAccentColor == accentColor,
                        onClick = {
                            onChange(AppColorSource.CUSTOM_ACCENT)
                            onAccentColorChange(accentColor)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TileBehaviorSelector(
    selected: TileInteractionBehavior,
    onChange: (TileInteractionBehavior) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TileBehaviorOption(
            title = "Quick settings dialog",
            selected = selected == TileInteractionBehavior.SHOW_DIALOG,
            onClick = { onChange(TileInteractionBehavior.SHOW_DIALOG) },
            modifier = Modifier.weight(1f),
        )
        TileBehaviorOption(
            title = "Cycle profiles",
            selected = selected == TileInteractionBehavior.CYCLE_PROFILES,
            onClick = { onChange(TileInteractionBehavior.CYCLE_PROFILES) },
            modifier = Modifier.weight(1f),
        )
        TileBehaviorOption(
            title = "Open app",
            selected = selected == TileInteractionBehavior.OPEN_APP,
            onClick = { onChange(TileInteractionBehavior.OPEN_APP) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TileBehaviorOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ManualRgbControls(
    settings: AppSettings,
    onTargetChange: (RgbStick) -> Unit,
    onStickChange: (RgbStick, Int, Float) -> Unit,
) {
    val target = settings.rgbManualTarget
    // The edited stick's stored color (at full value) + brightness. "Both" edits from the Left values.
    val storedColor = if (target == RgbStick.RIGHT) settings.rgbManualRightColor else settings.rgbManualLeftColor
    val storedBright = if (target == RgbStick.RIGHT) settings.rgbManualRightBrightness else settings.rgbManualLeftBrightness

    // Local pick state, re-seeded whenever the edited stick changes so each loads its own stored values.
    val seedHue = remember(target) { FloatArray(3).also { android.graphics.Color.colorToHSV(storedColor, it) }[0] }
    var hue by remember(target) { mutableStateOf(seedHue) }
    var bright by remember(target) { mutableStateOf(storedBright) }

    fun commit() = onStickChange(target, android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), bright)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left — stick target + L/R swatches
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Stick",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RgbStick.entries.forEach { stick ->
                    FilterChip(
                        selected = target == stick,
                        onClick = { onTargetChange(stick) },
                        label = { Text(stick.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ManualStickSwatch("L", settings.rgbManualLeftColor, settings.rgbManualLeftBrightness)
                ManualStickSwatch("R", settings.rgbManualRightColor, settings.rgbManualRightBrightness)
            }
        }
        // Right — the wordmark picker, fixed width, flush to the right edge
        Column(
            modifier = Modifier.width(380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PulseColorPicker(
                modifier = Modifier.fillMaxWidth(),
                hue = hue,
                brightness = bright,
                onHue = { hue = it },
                onBrightness = { bright = it },
                onCommit = { commit() },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(
                    text = "color",
                    modifier = Modifier.weight(17f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "brightness",
                    modifier = Modifier.weight(12f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ManualStickSwatch(label: String, color: Int, brightness: Float) {
    val b = brightness.coerceIn(0f, 1f)
    val swatch = Color(
        red = (((color shr 16) and 0xFF) * b) / 255f,
        green = (((color shr 8) and 0xFF) * b) / 255f,
        blue = ((color and 0xFF) * b) / 255f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(swatch, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val PULSE_GLYPHS = listOf(
    listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"), // P
    listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"), // U
    listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"), // L
    listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"), // S
    listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"), // E
)

/**
 * The PULSE wordmark, made into the picker. P-U-L are a hue spectrum — drag across to choose colour. S-E are a
 * brightness ramp in the chosen hue — drag up/down to choose brightness. Equalizer bars above and below pulse.
 * Left ~3/5 (x < split) edits colour by x; right ~2/5 edits brightness by y. Commits on release.
 */
@Composable
private fun PulseColorPicker(
    hue: Float,
    brightness: Float,
    onHue: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // pointerInput(Unit) runs once and captures these — keep them fresh so switching sticks isn't ignored.
    val latestOnHue = rememberUpdatedState(onHue)
    val latestOnBrightness = rememberUpdatedState(onBrightness)
    val latestOnCommit = rememberUpdatedState(onCommit)
    val barAlpha by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1300, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "barAlpha",
    )
    Canvas(
        modifier = modifier
            .aspectRatio(2.4f)
            .pointerInput(Unit) {
                // One low-level gesture that CONSUMES from touch-down, so the parent vertical scroll can't steal
                // the drag (the old tap+drag detectors fought the scroll and froze). Handles tap and drag alike.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pickFromPosition(down.position, size.width.toFloat(), size.height.toFloat(), latestOnHue.value, latestOnBrightness.value)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        pickFromPosition(change.position, size.width.toFloat(), size.height.toFloat(), latestOnHue.value, latestOnBrightness.value)
                    }
                    latestOnCommit.value()
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val px = w * 0.04f
        val b = (w - px * 2f) / 29f
        val topOff = (h - 12.6f * b) / 2f
        val barH = 1.6f * b
        val letterY0 = topOff + 2.4f * b
        val letterH = 7f * b
        val barBotY = topOff + 10.2f * b
        val segGap = b * 0.3f

        fun bars(y: Float) {
            val cz = listOf(Color(0xFFE23B86), Color(0xFF2BD07A), Color(0xFF5B7CFF), Color(0xFFB56CFF))
            val czSeg = (17f * b - 3 * segGap) / 4f
            for (k in 0..3) drawRect(cz[k].copy(alpha = barAlpha), Offset(px + k * (czSeg + segGap), y), Size(czSeg, barH))
            val bzStart = px + 18f * b
            val bz = listOf(Color(0xFFCFD6E6), Color(0xFF9AA3B8), Color(0xFF5B6478))
            val bzSeg = (11f * b - 2 * segGap) / 3f
            for (k in 0..2) drawRect(bz[k].copy(alpha = barAlpha), Offset(bzStart + k * (bzSeg + segGap), y), Size(bzSeg, barH))
        }
        bars(topOff)
        bars(barBotY)

        val blk = b * 0.84f
        for (i in 0..4) {
            val glyph = PULSE_GLYPHS[i]
            val lx = px + i * 6f * b
            for (r in 0..6) for (c in 0..4) {
                if (glyph[r][c] != '1') continue
                val fill = if (i < 3) {
                    Color(android.graphics.Color.HSVToColor(floatArrayOf((i * 5 + c) / 14f * 360f, 0.85f, 1f)))
                } else {
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, (1f - r / 6f).coerceIn(0.08f, 1f))))
                }
                drawRect(fill, Offset(lx + c * b, letterY0 + r * b), Size(blk, blk))
            }
        }

        val cw = 3.dp.toPx()
        // Hue cursor — vertical line over the P-U-L spectrum.
        val hx = px + (hue / 360f).coerceIn(0f, 1f) * 17f * b
        drawRect(Color.Black.copy(alpha = 0.5f), Offset(hx - cw, letterY0 - b * 0.4f), Size(cw * 2.2f, letterH + b * 0.8f))
        drawRect(Color.White, Offset(hx - cw / 2f, letterY0 - b * 0.4f), Size(cw, letterH + b * 0.8f))
        // Brightness cursor — horizontal line over the S-E ramp.
        val seL = px + 18f * b
        val seR = px + 29f * b
        val by = letterY0 + (1f - brightness.coerceIn(0f, 1f)) * letterH
        drawRect(Color.Black.copy(alpha = 0.5f), Offset(seL, by - cw), Size(seR - seL, cw * 2.2f))
        drawRect(Color.White, Offset(seL, by - cw / 2f), Size(seR - seL, cw))
    }
}

/** Map a touch position to hue (left zone, by x) or brightness (right zone, by y), matching the draw geometry. */
private fun pickFromPosition(pos: Offset, w: Float, h: Float, onHue: (Float) -> Unit, onBrightness: (Float) -> Unit) {
    val px = w * 0.04f
    val b = (w - px * 2f) / 29f
    val splitX = px + 17.5f * b
    val letterY0 = (h - 12.6f * b) / 2f + 2.4f * b
    val letterH = 7f * b
    if (pos.x < splitX) {
        onHue(((pos.x - px) / (17f * b)).coerceIn(0f, 1f) * 360f)
    } else {
        onBrightness((1f - (pos.y - letterY0) / letterH).coerceIn(0f, 1f))
    }
}

@Composable
private fun SettingsControlGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

/** A hardware-domain grouping of overlay items, so the picker reads like an Afterburner/Adrenalin OSD config. */
private class OverlayItemGroupDef(val title: String, val items: List<Pair<OverlayElement, String>>)

// Short, in-context labels (the group header carries the "GPU"/"CPU" prefix) covering all 15 OverlayElements.
private val OverlayItemGroups = listOf(
    OverlayItemGroupDef(
        "Frame rate",
        listOf(OverlayElement.FPS to "FPS", OverlayElement.FPS_TREND to "Avg · Low · Trend"),
    ),
    OverlayItemGroupDef(
        "GPU",
        listOf(
            OverlayElement.GPU_LOAD to "Load",
            OverlayElement.GPU_CLOCK to "Clock",
            OverlayElement.GPU_TEMP to "Temp",
        ),
    ),
    OverlayItemGroupDef(
        "CPU",
        listOf(
            OverlayElement.CPU_LOAD to "Load",
            OverlayElement.CPU_CLOCK to "Clock",
            OverlayElement.CPU_TEMP to "Temp",
            OverlayElement.CORE_BARS to "Core bars",
        ),
    ),
    OverlayItemGroupDef(
        "System",
        listOf(
            OverlayElement.RAM to "RAM",
            OverlayElement.POWER to "Power",
            OverlayElement.BATTERY_LEFT to "Time left",
        ),
    ),
    OverlayItemGroupDef(
        "Status",
        listOf(
            OverlayElement.AUTOTDP to "AutoTDP",
            OverlayElement.SESSION_TIMER to "Timer",
            OverlayElement.SOC_NAME to "Chip name",
        ),
    ),
)

/** One domain row: a muted caption + a wrapping cluster of toggle chips (checked = shown on the overlay). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayItemGroup(
    title: String,
    items: List<Pair<OverlayElement, String>>,
    selected: Set<OverlayElement>,
    onToggle: (OverlayElement, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { (element, label) ->
                val isOn = element in selected
                FilterChip(
                    selected = isOn,
                    onClick = { onToggle(element, !isOn) },
                    label = { Text(label) },
                    leadingIcon = if (isOn) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun ThemeSelector(
    selected: PulseThemeId,
    onSelect: (PulseThemeId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PulseThemeId.entries.forEach { theme ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(theme) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == theme, onClick = { onSelect(theme) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = theme.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = theme.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
