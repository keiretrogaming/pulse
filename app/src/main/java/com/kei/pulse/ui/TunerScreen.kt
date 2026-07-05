package com.kei.pulse.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kei.pulse.data.DisplayController
import com.kei.pulse.data.GovernorOption
import com.kei.pulse.data.TelemetrySnapshot
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PowerTier
import com.kei.pulse.ui.theme.HudBackground
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.ProfileSource
import com.kei.pulse.model.TunerState
import kotlinx.coroutines.delay

private const val NEW_PROFILE_DIALOG_ID = "__new_profile__"

@Composable
fun MainTunerScreen(
    state: TunerState,
    sleepProfileId: String?,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onApplyCurrent: (TunerState) -> Unit,
    onCreateProfile: (String, TunerState) -> Unit,
    onUpdateProfile: (String, String, TunerState) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshLiveValues: () -> Unit,
    onStatusMessageShown: () -> Unit,
    onErrorMessageShown: () -> Unit,
    activeTier: PowerTier,
    fanMode: Int?,
    fanCurveEditor: FanCurveEditorBindings?,
    nativeDisplay: DisplayController.DisplaySpec?,
    resolutionScale: Int,
    onSelectTier: (PowerTier) -> Unit,
    onSelectFanMode: (Int) -> Unit,
    onSelectResolution: (Int) -> Unit,
    onRefreshSystemControls: () -> Unit,
    onSetPolicyValue: (CpuPolicyInfo, Int) -> Unit,
    governor: String?,
    refreshRate: Int?,
    gpuFloorPercent: Int,
    onSelectGovernor: (GovernorOption) -> Unit,
    onSelectRefreshRate: (Int) -> Unit,
    onSelectGpuFloor: (Int) -> Unit,
    cpuFloorPercent: Int,
    onSelectCpuFloor: (Int) -> Unit,
    readTelemetry: suspend () -> TelemetrySnapshot,
    estimatedPeakW: Float?,
    powerTargetEnabled: Boolean,
    powerTargetPercent: Int,
    powerTargetCpuOnly: Boolean,
    gpuLocked: Boolean,
    onPowerTargetEnabledChange: (Boolean) -> Unit,
    onPowerTargetPercentChange: (Int) -> Unit,
    onPowerTargetCpuOnlyChange: (Boolean) -> Unit,
    onToggleGpuLock: (Boolean) -> Unit,
    primeCoreBoostLimited: Boolean,
    onTogglePrimeCoreBoostLimit: (Boolean) -> Unit,
    autoTdpEnabled: Boolean,
    onAutoTdpEnabledChange: (Boolean) -> Unit,
    autoTdpFpsTarget: Int,
    autoTdpFpsOptions: List<Int>,
    autoTdpShowWattCaps: Boolean,
    onAutoTdpFpsTargetChange: (Int) -> Unit,
    autoTdpAggressivePark: Boolean,
    onAutoTdpAggressiveParkChange: (Boolean) -> Unit,
    autoTdpBias: AutoTdpBias,
    onAutoTdpBiasChange: (AutoTdpBias) -> Unit,
) {
    var dialogProfileId by remember { mutableStateOf<String?>(null) }

    ScreenNotifications(
        state = state,
        onStatusMessageShown = onStatusMessageShown,
        onErrorMessageShown = onErrorMessageShown,
    )

    LaunchedEffect(Unit) {
        onRefreshSystemControls()
        onRefreshLiveValues()
        while (true) {
            delay(1_000)
            onRefreshLiveValues()
        }
    }

    ScreenContainer(compactMode = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Header(
                state = state,
                compactMode = false,
                onOpenSettings = onOpenSettings,
            )

            if (state.isLoading) {
                LoadingClustersCard()
            } else if (!state.isPServerAvailable) {
                Text(
                    text = "Your device is not compatible with this app",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                TelemetryHud(
                    readTelemetry = readTelemetry,
                    estimatedPeakW = estimatedPeakW,
                )

                // Names the whole page so it's clear these are the everywhere-defaults, distinct from the
                // per-app overrides (Settings → Per-app profiles).
                Column(
                    modifier = Modifier.padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Global Profile",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Applies to every app unless a per-app profile overrides it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                PulseSectionLabel("GLOBAL PERFORMANCE TIER")
                AutoTdpModule(
                    enabled = autoTdpEnabled,
                    onEnabledChange = onAutoTdpEnabledChange,
                    fpsTarget = autoTdpFpsTarget,
                    fpsOptions = autoTdpFpsOptions,
                    onFpsTargetChange = onAutoTdpFpsTargetChange,
                    aggressivePark = autoTdpAggressivePark,
                    onAggressiveParkChange = onAutoTdpAggressiveParkChange,
                    bias = autoTdpBias,
                    onBiasChange = onAutoTdpBiasChange,
                    showWattCaps = autoTdpShowWattCaps,
                )
                TierSelector(active = activeTier, onSelect = onSelectTier, enabled = !autoTdpEnabled)

                if (activeTier == PowerTier.CUSTOM && !autoTdpEnabled) {
                    PowerTargetModule(
                        enabled = powerTargetEnabled,
                        percent = powerTargetPercent,
                        cpuOnly = powerTargetCpuOnly,
                        onEnabledChange = onPowerTargetEnabledChange,
                        onPercentChange = onPowerTargetPercentChange,
                        onCpuOnlyChange = onPowerTargetCpuOnlyChange,
                    )
                }

                CurrentFrequenciesCard(state = state)

                // AutoTDP manages the governor + clocks itself, but the fan stays user-configurable: a
                // Custom fan set here keeps running (cascaded) during AutoTDP, otherwise Smart is used.
                // Only the governor hides while AutoTDP is on.
                FanModule(currentMode = fanMode, onSelect = onSelectFanMode, editor = fanCurveEditor)
                if (!autoTdpEnabled) {
                    GovernorModule(current = governor, onSelect = onSelectGovernor)
                }

                RefreshRateModule(current = refreshRate, onSelect = onSelectRefreshRate)

                if (!autoTdpEnabled) {
                    ResolutionModule(
                        native = nativeDisplay,
                        currentScale = resolutionScale,
                        onSelect = onSelectResolution,
                    )
                }

                if (activeTier == PowerTier.CUSTOM && !autoTdpEnabled) {
                    PulseSectionLabel(
                        when {
                            powerTargetEnabled && !powerTargetCpuOnly -> "MANUAL CONTROL · LOCKED BY POWER TARGET"
                            powerTargetEnabled && powerTargetCpuOnly -> "MANUAL CONTROL · CPU LOCKED · GPU FREE"
                            else -> "MANUAL CONTROL · CPU + GPU"
                        },
                    )
                    state.policies.forEach { policy ->
                        val cardLocked = powerTargetEnabled && (!policy.isGpu || !powerTargetCpuOnly)
                        val (clusterName, clusterCaption) = clusterRole(policy, state.policies)
                        PolicyCard(
                            policy = policy,
                            clusterName = clusterName,
                            clusterCaption = clusterCaption,
                            selectedValue = state.currentValues[policy.id] ?: policy.currentMaxFreq,
                            onValueChanged = { onSetPolicyValue(policy, it) },
                            actualValue = state.actualValues[policy.id] ?: policy.currentMaxFreq,
                            enabled = !cardLocked,
                        )
                        if (clusterName == "Prime" && !policy.isGpu) {
                            PrimeBoostLimitRow(
                                limited = primeCoreBoostLimited,
                                onToggle = onTogglePrimeCoreBoostLimit,
                            )
                        }
                    }
                    Button(
                        onClick = { onApplyCurrent(state) },
                        enabled = !(powerTargetEnabled && !powerTargetCpuOnly),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Apply manual limits")
                    }

                    GpuFloorModule(
                        currentPercent = gpuFloorPercent,
                        locked = gpuLocked,
                        onSelect = onSelectGpuFloor,
                        onToggleLock = onToggleGpuLock,
                    )

                    CpuFloorModule(currentPercent = cpuFloorPercent, onSelect = onSelectCpuFloor)

                    PulseSectionLabel("SAVED SETUPS")
                    ProfileListSection(
                        state = state,
                        sleepProfileId = sleepProfileId,
                        onApplyProfile = onApplyProfile,
                        onOpenCreateProfile = { dialogProfileId = NEW_PROFILE_DIALOG_ID },
                        onEditProfile = { dialogProfileId = it },
                        onMoveProfile = onMoveProfile,
                        onApplySelectedProfile = { onApplyCurrent(state) },
                    )
                }
            }
        }
    }

    dialogProfileId?.let { profileId ->
        val manualProfile = remember(state.actualValues, state.policies) {
            if (state.policies.isEmpty()) {
                null
            } else {
                PerformanceProfile(
                    id = ProfileStateResolver.MANUAL_PROFILE_ID,
                    name = "Manual",
                    maxFrequencies = state.policies.associate { policy ->
                        policy.id to (state.actualValues[policy.id] ?: policy.currentMaxFreq)
                    },
                    source = ProfileSource.VIRTUAL,
                    isEditable = true,
                    isDeletable = false,
                )
            }
        }
        val profile = when (profileId) {
            ProfileStateResolver.MANUAL_PROFILE_ID -> manualProfile
            else -> state.displayProfiles.firstOrNull { it.id == profileId }
        }
        ProfileEditorDialog(
            baseState = state,
            profile = profile,
            creatingNewProfile = profileId == NEW_PROFILE_DIALOG_ID,
            manualMode = profileId == ProfileStateResolver.MANUAL_PROFILE_ID,
            onDismiss = { dialogProfileId = null },
            onSave = { name, values ->
                val editedState = state.copy(currentValues = values)
                when {
                    profileId == NEW_PROFILE_DIALOG_ID -> onCreateProfile(name, editedState)
                    profileId == ProfileStateResolver.MANUAL_PROFILE_ID -> onApplyCurrent(editedState)
                    profile != null -> onUpdateProfile(profile.id, name, editedState)
                }
                dialogProfileId = null
            },
            onDelete = {
                profile?.let { onDeleteProfile(it.id) }
                dialogProfileId = null
            },
        )
    }
}

@Composable
fun CompactTunerScreen(
    state: TunerState,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onClearSelection: () -> Unit,
    onApplyCurrent: (TunerState) -> Unit,
    onDismissRequest: (() -> Unit)?,
    onRefreshLiveValues: () -> Unit,
    onOpenFullApp: (() -> Unit)? = null,
) {
    ScreenNotifications(
        state = state,
        onStatusMessageShown = {},
        onErrorMessageShown = {},
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            onRefreshLiveValues()
        }
    }

    ScreenContainer(compactMode = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                state = state,
                compactMode = true,
                onOpenSettings = null,
            )
            if (state.isLoading) {
                LoadingClustersCard()
            } else {
                ProfileChipSelector(
                    state = state,
                    onApplyProfile = onApplyProfile,
                    onClearSelection = onClearSelection,
                    onOpenFullApp = onOpenFullApp,
                )
                PolicyEditorSection(
                    state = state,
                    onPolicyValueChange = onPolicyValueChange,
                    compactMode = true,
                )
            }

            if (onDismissRequest != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onApplyCurrent(state)
                        },
                        enabled = state.policies.isNotEmpty() && state.isPServerAvailable,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingClustersCard() {
    SectionCard(
        title = null,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
            )
            Text(
                text = "Scanning CPU clusters...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ScreenNotifications(
    state: TunerState,
    onStatusMessageShown: () -> Unit,
    onErrorMessageShown: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onStatusMessageShown()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onErrorMessageShown()
        }
    }
}

@Composable
private fun ScreenContainer(
    compactMode: Boolean,
    content: @Composable () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    if (compactMode) {
        // Quick Settings tile dialog: dim scrim + bottom sheet card.
        Box(modifier = Modifier.fillMaxSize().background(colorScheme.scrim.copy(alpha = 0.45f))) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                shape = RoundedCornerShape(30.dp, 30.dp, 24.dp, 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceColorAtElevation(4.dp),
                ),
            ) {
                content()
            }
        }
    } else {
        // Full app: signature PULSE telemetry atmosphere behind transparent content.
        HudBackground(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun Header(
    state: TunerState,
    compactMode: Boolean,
    onOpenSettings: (() -> Unit)?,
) {
    if (compactMode && state.statusMessage == null && state.errorMessage == null) return

    Column(verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 8.dp)) {
        if (!compactMode) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "PUL",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "SE",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "CLUSTER · GPU FREQUENCY CONTROL",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PServerStatusChip(isLinked = state.isPServerAvailable && state.policies.isNotEmpty())
                }
                onOpenSettings?.let { openSettings ->
                    IconButton(onClick = openSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.statusMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PServerStatusChip(isLinked: Boolean) {
    val color = if (isLinked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
            Text(
                text = if (isLinked) "PSERVER · LINKED · NO-ROOT" else "PSERVER UNAVAILABLE",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/** Read-only live readout of each cluster's active clock. The sliders below are what change them. */
@Composable
private fun CurrentFrequenciesCard(state: TunerState) {
    SectionCard(
        title = null,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        if (state.policies.isEmpty()) {
            Text("No CPU clusters found.")
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Current values",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ValuePreviewChips(
                    values = state.policies.associate { policy ->
                        policy.id to (state.actualValues[policy.id] ?: policy.currentMaxFreq)
                    },
                    policies = state.policies,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ProfileListSection(
    state: TunerState,
    sleepProfileId: String?,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onOpenCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    onApplySelectedProfile: () -> Unit,
) {
    SectionCard(
        title = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Saved setups",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onOpenCreateProfile) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "New",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.displayProfiles.forEach { profile ->
                val movableIndex = state.displayProfiles.indexOfFirst { it.id == profile.id }
                val canMove = movableIndex >= 0
                ProfileListRow(
                    profile = profile,
                    isApplied = profile.id == state.activeDisplayProfileId,
                    isSelected = profile.id == state.selectedDisplayProfileId,
                    isSleepProfile = profile.id == sleepProfileId,
                    canMoveUp = canMove && movableIndex > 0,
                    canMoveDown = canMove && movableIndex < state.displayProfiles.lastIndex,
                    showReorder = false,
                    showEdit = profile.isEditable,
                    valuePreview = profile.maxFrequencies,
                    onClick = { onApplyProfile(profile) },
                    onEdit = {
                        if (profile.isEditable) {
                            onEditProfile(profile.id)
                        }
                    },
                    onMoveProfile = { offset -> onMoveProfile(profile.id, offset) },
                )
            }
        }

        val canApplySelectedProfile = state.selectedDisplayProfileId != null &&
            state.policies.isNotEmpty() &&
            state.isPServerAvailable
        Spacer(Modifier.size(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onApplySelectedProfile,
                enabled = canApplySelectedProfile,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = state.selectedDisplayProfileName?.let { "Apply $it" }
                        ?: "Select a profile to apply",
                )
            }
        }
    }
}

@Composable
private fun ProfileListRow(
    profile: PerformanceProfile,
    isApplied: Boolean,
    isSelected: Boolean,
    isSleepProfile: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showReorder: Boolean,
    showEdit: Boolean,
    valuePreview: Map<Int, Int>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMoveProfile: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    val containerColor = when {
        isApplied && isSelected -> colorScheme.primaryContainer
        isApplied -> colorScheme.primaryContainer
        else -> colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isApplied && isSelected -> colorScheme.onPrimaryContainer
        isApplied -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurface
    }
    val borderColor = when {
        isApplied -> colorScheme.primary
        isSelected -> colorScheme.primary
        else -> Color.Transparent
    }
    val chipContainerColor = when {
        isApplied -> colorScheme.secondaryContainer.copy(alpha = 0.92f)
        else -> colorScheme.primaryContainer.copy(alpha = 0.92f)
    }
    val chipContentColor = when {
        isApplied -> colorScheme.onSecondaryContainer
        else -> colorScheme.onPrimaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor, rowShape)
            .border(BorderStroke(2.dp, borderColor), rowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showReorder) {
            ReorderControl(
                enabled = true,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveProfile = onMoveProfile,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (isSleepProfile) {
                    Icon(
                        imageVector = Icons.Rounded.DarkMode,
                        contentDescription = "Sleep profile",
                        modifier = Modifier.size(16.dp),
                        tint = contentColor.copy(alpha = 0.78f),
                    )
                }
            }
            if (valuePreview.isNotEmpty()) {
                ValuePreviewChips(
                    values = valuePreview,
                    chipContainerColor = chipContainerColor,
                    chipContentColor = chipContentColor,
                )
            }
        }
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Edit ${profile.name}",
                    tint = contentColor,
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ReorderControl(
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveProfile: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(
            onClick = { onMoveProfile(-1) },
            enabled = enabled && canMoveUp,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Rounded.ExpandLess,
                contentDescription = "Move up",
                tint = if (canMoveUp) colorScheme.primary else colorScheme.outline,
            )
        }
        IconButton(
            onClick = { onMoveProfile(1) },
            enabled = enabled && canMoveDown,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = "Move down",
                tint = if (canMoveDown) colorScheme.primary else colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ValuePreviewChips(
    values: Map<Int, Int>,
    modifier: Modifier = Modifier,
    chipContainerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
    chipContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    policies: List<CpuPolicyInfo> = emptyList(),
) {
    val policiesById = policies.associateBy { it.id }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.toSortedMap().forEach { (policyId, value) ->
            Surface(
                color = chipContainerColor,
                shape = RoundedCornerShape(999.dp),
            ) {
                val policy = policiesById[policyId]
                Text(
                    text = "${if (policyId == com.kei.pulse.model.CpuPolicyInfo.GPU_POLICY_ID) "GPU" else "C$policyId"}: ${formatFrequency(value, boosted = policy?.isBoosted(value) == true)}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = chipContentColor,
                )
            }
        }
    }
}

@Composable
private fun ProfileChipSelector(
    state: TunerState,
    onApplyProfile: (PerformanceProfile) -> Unit,
    onClearSelection: () -> Unit,
    onOpenFullApp: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.displayProfiles.forEach { profile ->
                ProfileSelectorChip(
                    label = profile.name,
                    isApplied = profile.id == state.activeDisplayProfileId,
                    isSelected = profile.id == state.selectedDisplayProfileId,
                    onClick = { onApplyProfile(profile) },
                )
            }
            if (state.isManualSelection) {
                ProfileSelectorChip(
                    label = "Manual",
                    isApplied = false,
                    isSelected = true,
                    onClick = onClearSelection,
                )
            }
        }
        onOpenFullApp?.let { openFullApp ->
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) {
                IconButton(
                    onClick = openFullApp,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Open full app",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSelectorChip(
    label: String,
    isApplied: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        colors = when {
            isApplied -> AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            else -> AssistChipDefaults.assistChipColors()
        },
        border = when {
            isApplied -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
        label = { Text(label) },
    )
}

@Composable
private fun PolicyEditorSection(
    state: TunerState,
    onPolicyValueChange: (CpuPolicyInfo, Int) -> Unit,
    compactMode: Boolean,
) {
    if (state.policies.isEmpty()) {
        EmptyState(state)
        return
    }

    state.policies.forEach { policy ->
        PolicyCard(
            policy = policy,
            selectedValue = state.currentValues[policy.id] ?: policy.currentMaxFreq,
            actualValue = state.actualValues[policy.id] ?: policy.currentMaxFreq,
            onValueChanged = { onPolicyValueChange(policy, it) },
            compactMode = compactMode,
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    baseState: TunerState,
    profile: PerformanceProfile?,
    creatingNewProfile: Boolean,
    manualMode: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Map<Int, Int>) -> Unit,
    onDelete: () -> Unit,
) {
    val initialValues = remember(profile?.id, creatingNewProfile, manualMode, baseState.actualValues) {
        baseState.policies.associate { policy ->
            val initialValue = when {
                creatingNewProfile || manualMode -> baseState.actualValues[policy.id]
                else -> profile?.maxFrequencies?.get(policy.id)
            } ?: policy.currentMaxFreq
            policy.id to policy.clampToWritableMax(initialValue)
        }
    }
    var profileName by remember(profile?.id, creatingNewProfile) { mutableStateOf(profile?.name.orEmpty()) }
    var editedValues by remember(profile?.id, initialValues) { mutableStateOf(initialValues) }
    var showDeleteConfirmation by remember(profile?.id) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .widthIn(max = 900.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!manualMode) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Profile name") },
                    )
                }
                baseState.policies.forEach { policy ->
                    PolicyCard(
                        policy = policy,
                        selectedValue = editedValues[policy.id] ?: policy.currentMaxFreq,
                        actualValue = baseState.actualValues[policy.id] ?: policy.currentMaxFreq,
                        onValueChanged = { editedValue ->
                            editedValues = editedValues + (policy.id to editedValue)
                        },
                        compactMode = true,
                    )
                }
                if (manualMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { onSave(profile?.name.orEmpty(), editedValues) },
                            enabled = baseState.policies.isNotEmpty(),
                        ) {
                            Text("Apply custom values")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (profile?.isDeletable == true) {
                            IconButton(
                                onClick = { showDeleteConfirmation = true },
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = "Delete profile",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            Spacer(Modifier.size(48.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = { onSave(profileName, editedValues) },
                                enabled = profileName.isNotBlank() && baseState.policies.isNotEmpty(),
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete profile?") },
            text = { Text("This profile will be removed until you reset profiles to default.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyState(state: TunerState) {
    SectionCard(title = if (state.isLoading) "Scanning CPU Clusters" else "No CPU Clusters Found") {
        Text(
            text = if (state.isLoading) {
                "Scanning CPU clusters..."
            } else {
                "No compatible CPU frequency clusters were found."
            },
        )
    }
}

@Composable
private fun PrimeBoostLimitRow(limited: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = "Limit Prime core boost",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Caps the top frequency bin — trades peak spikes for cooler, sustained performance.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = limited, onCheckedChange = onToggle)
    }
}

/** Friendly role + one-line caption for a CPU cluster (or the GPU), by order and core count. */
private fun clusterRole(policy: CpuPolicyInfo, policies: List<CpuPolicyInfo>): Pair<String, String?> {
    if (policy.isGpu) return "GPU" to null
    val cpu = policies.filterNot { it.isGpu }.sortedBy { it.id }
    val n = cpu.size
    val idx = cpu.indexOfFirst { it.id == policy.id }
    if (n <= 1 || idx < 0) return "CPU" to "Main processor cores"
    val name = when (idx) {
        0 -> "Efficiency"
        n - 1 -> "Prime"
        else -> "Performance"
    }
    val caption = when (name) {
        "Efficiency" -> "Light-load cores for battery life"
        "Prime" -> if (policy.cpuIds.size <= 1) "Single big core for peak speed" else "Big cores for peak speed"
        else -> "Mid cores for sustained load"
    }
    return name to caption
}

@Composable
private fun PolicyCard(
    policy: CpuPolicyInfo,
    selectedValue: Int,
    onValueChanged: (Int) -> Unit,
    compactMode: Boolean = false,
    actualValue: Int = selectedValue,
    enabled: Boolean = true,
    clusterName: String = if (policy.isGpu) "GPU" else "Cluster ${policy.id}",
    clusterCaption: String? = null,
) {
    val supported = policy.supportedFrequencies
    val displaySelectedValue = policy.clampToWritableMax(selectedValue)
    val currentIndex = supported.indexOf(displaySelectedValue).takeIf { it >= 0 } ?: supported.lastIndex
    val actualSatisfiesSelected = ProfileStateResolver.isPolicyValueSatisfied(
        policy = policy,
        requestedValue = selectedValue,
        actualValue = actualValue,
    )

    SectionCard(title = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = clusterName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (clusterCaption != null) {
                        Text(
                            text = clusterCaption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    policy.cpuIds.forEach { cpuId ->
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = "CPU $cpuId",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Surface(
                color = if (actualSatisfiesSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = "Current ${formatFrequency(actualValue, boosted = policy.isBoosted(actualValue))}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (actualSatisfiesSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    textAlign = TextAlign.End,
                )
            }
        }
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides if (compactMode) Dp.Unspecified else 48.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { raw ->
                        val index = raw.toInt().coerceIn(0, supported.lastIndex)
                        onValueChanged(supported[index])
                    },
                    valueRange = 0f..supported.lastIndex.toFloat(),
                    steps = (supported.size - 2).coerceAtLeast(0),
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        thumbColor = if (policy.isGpu) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        activeTrackColor = if (policy.isGpu) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        activeTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatFrequency(selectedValue, boosted = policy.isBoosted(selectedValue)),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (policy.isGpu) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String?,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

private fun CpuPolicyInfo.clampToWritableMax(valueKhz: Int): Int {
    return valueKhz.coerceAtMost(selectableMaxFreq)
}

private fun CpuPolicyInfo.isBoosted(valueKhz: Int): Boolean {
    return valueKhz > selectableMaxFreq
}

internal fun formatFrequency(valueKhz: Int, boosted: Boolean = false): String {
    val base = when {
        valueKhz >= 1_000_000 -> String.format(java.util.Locale.US, "%.2f GHz", valueKhz / 1_000_000f)
        valueKhz >= 1_000 -> String.format(java.util.Locale.US, "%.0f MHz", valueKhz / 1_000f)
        else -> "$valueKhz kHz"
    }
    return if (boosted) "$base+" else base
}
