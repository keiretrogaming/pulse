package com.kei.pulse.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kei.pulse.data.FanController
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileSource
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.i18n.LocalPulseStrings
import com.kei.pulse.i18n.localizedName
import com.kei.pulse.i18n.PulseStrings
import com.kei.pulse.ui.theme.HudBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** Settings sub-screen: bind a power tier / saved profile (+ system extras) to installed apps. */
@Composable
fun PerAppScreen(
    configs: List<PerAppConfig>,
    learnedPackages: Set<String> = emptySet(),
    profiles: List<PerformanceProfile>,
    batteryCapacityWh: Float,
    fpsOptions: List<Int>,
    defaultFpsTarget: Int,
    defaultAggressivePark: Boolean,
    onSaveConfig: (PerAppConfig) -> Unit,
    onRemoveConfig: (String) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalPulseStrings.current
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var editingApp by remember { mutableStateOf<InstalledApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .asSequence()
                .map { it.activityInfo }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        icon = runCatching {
                            info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                        }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
    }

    val configsByPackage = configs.associateBy { it.packageName }

    HudBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = strings.perAppScreenTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.perAppSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack) {
                    Text(strings.done)
                }
            }

            val loaded = apps
            if (loaded == null) {
                Text(
                    text = strings.loading,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.searchAppsHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val query = searchQuery.trim()
                val filtered = if (query.isEmpty()) {
                    loaded
                } else {
                    loaded.filter { it.label.contains(query, ignoreCase = true) }
                }
                val sorted = filtered.sortedByDescending { configsByPackage.containsKey(it.packageName) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sorted, key = { it.packageName }) { app ->
                        val rowConfig = configsByPackage[app.packageName]
                        PerAppRow(
                            app = app,
                            config = rowConfig,
                            profiles = profiles,
                            batteryCapacityWh = batteryCapacityWh,
                            // null = not an AutoTDP binding (no badge); true = learned warm-start exists; false = still learning.
                            tuned = if (PerAppConfig.isAuto(rowConfig?.profileBinding)) {
                                app.packageName in learnedPackages
                            } else {
                                null
                            },
                            onClick = { editingApp = app },
                        )
                    }
                }
            }
        }
    }

    editingApp?.let { app ->
        PerAppConfigDialog(
            app = app,
            existing = configsByPackage[app.packageName],
            profiles = profiles,
            fpsOptions = fpsOptions,
            defaultFpsTarget = defaultFpsTarget,
            defaultAggressivePark = defaultAggressivePark,
            onSave = { config ->
                if (config.hasAnyBinding) onSaveConfig(config) else onRemoveConfig(app.packageName)
                editingApp = null
            },
            onRemove = {
                onRemoveConfig(app.packageName)
                editingApp = null
            },
            onDismiss = { editingApp = null },
        )
    }
}

private fun fanModeLabel(mode: Int?, strings: PulseStrings): String = when (mode) {
    FanController.SILENT -> strings.fanModeQuiet
    FanController.SMART -> strings.fanModeSmart
    FanController.SPORT -> strings.fanModeMax
    FanController.CUSTOM -> strings.fanModeCustom
    else -> FanController.labelFor(mode)
}

private fun bindingSummary(config: PerAppConfig?, profiles: List<PerformanceProfile>, strings: PulseStrings): String {
    if (config == null) return strings.notConfigured
    val parts = mutableListOf<String>()
    when {
        config.profileBinding == null -> {}
        PerAppConfig.isAuto(config.profileBinding) -> {
            val fpsPart = config.fpsTarget?.let {
                val label = if (it <= 0) strings.maxStr else it.toString()
                " $label FPS"
            } ?: ""
            parts += "${strings.powerTierAutoTdp}$fpsPart" +
                when (config.aggressivePark) { true -> " · ${strings.autoTdpAggressivePark}"; false -> ""; null -> "" }
        }
        else -> PerAppConfig.tierFromBinding(config.profileBinding)?.let { parts += it.localizedLabel(strings) }
            ?: run {
                val name = if (config.profileBinding == ProfileStateResolver.STOCK_PROFILE_ID) {
                    strings.powerTierStock
                } else {
                    profiles.firstOrNull { it.id == config.profileBinding }?.name ?: strings.savedProfileStr
                }
                parts += name
            }
    }
    config.fanMode?.let { parts += "${strings.fanSectionTitle} ${fanModeLabel(it, strings)}" }
    // Refresh rate only matters for non-AutoTDP bindings (AutoTDP forces max).
    if (!PerAppConfig.isAuto(config.profileBinding)) config.refreshRateHz?.let { parts += "${it}Hz" }
    return if (parts.isEmpty()) strings.notConfigured else parts.joinToString(" · ")
}

/** "3h 51m" style runtime from a full battery at the given sustained draw. */
private fun formatRuntime(capacityWh: Float, watts: Float, strings: PulseStrings): String {
    val totalMinutes = (capacityWh / watts * 60f).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "$h${strings.hourUnit} $m${strings.minuteUnit}" else "$m${strings.minuteUnit}"
}

@Composable
private fun PerAppRow(
    app: InstalledApp,
    config: PerAppConfig?,
    profiles: List<PerformanceProfile>,
    batteryCapacityWh: Float,
    tuned: Boolean?,
    onClick: () -> Unit,
) {
    val strings = LocalPulseStrings.current
    val configured = config != null
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = if (configured) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (configured) accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.icon?.let { icon ->
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(38.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = bindingSummary(config, profiles, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (configured) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // AutoTDP learning status: once a session converges, this app's operating point is saved and
                // warm-started next launch — so it starts already tuned instead of re-discovering from scratch.
                tuned?.let {
                    Text(
                        text = if (it) strings.tunedBadge else strings.learningBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (it) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            val peakW = config?.measuredPeakW ?: 0f
            val avgW = config?.measuredAvgW ?: 0f
            // Headline the realistic AVERAGE draw (falls back to peak until an average accrues) so the number
            // matches the basis of the battery-life estimate shown right below it.
            val drawW = if (avgW > 0f) avgW else peakW
            if (drawW > 0f) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(strings.avgPowerDraw, drawW),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                    if (batteryCapacityWh > 0f) {
                        // Runtime from the same average draw — the realistic figure.
                        Text(
                            text = "≈ ${formatRuntime(batteryCapacityWh, drawW, strings)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerAppConfigDialog(
    app: InstalledApp,
    existing: PerAppConfig?,
    profiles: List<PerformanceProfile>,
    fpsOptions: List<Int>,
    defaultFpsTarget: Int,
    defaultAggressivePark: Boolean,
    onSave: (PerAppConfig) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalPulseStrings.current
    var profileBinding by remember { mutableStateOf(existing?.profileBinding) }
    var fanMode by remember { mutableStateOf(existing?.fanMode) }
    var refreshRate by remember { mutableStateOf(existing?.refreshRateHz) }
    // No "Default" chips: pre-fill from the global setting (snapped to this SoC's options) and always save
    // a concrete value. Old null bindings still inherit via the service until re-saved here.
    var fpsTarget by remember {
        mutableStateOf(PerAppConfig.snapFpsTarget(existing?.fpsTarget ?: defaultFpsTarget, fpsOptions))
    }
    var aggressivePark by remember { mutableStateOf(existing?.aggressivePark ?: defaultAggressivePark) }
    var bias by remember { mutableStateOf(existing?.bias) } // null = inherit the global default

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label, maxLines = 1) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DialogGroupLabel(strings.perAppDialogProfile)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogChip(strings.noneStr, profileBinding == null) { profileBinding = null }
                    DialogChip(strings.powerTierAutoTdp, PerAppConfig.isAuto(profileBinding)) {
                        profileBinding = PerAppConfig.AUTO_BINDING
                    }
                    PowerTier.entries.forEach { tier ->
                        val binding = PerAppConfig.tierBinding(tier)
                        DialogChip(tier.localizedLabel(strings), profileBinding == binding) { profileBinding = binding }
                    }
                    profiles
                        .filter { it.source != ProfileSource.VIRTUAL || it.id == ProfileStateResolver.STOCK_PROFILE_ID }
                        .forEach { profile ->
                            val label = profile.localizedName(strings)
                            DialogChip(label, profileBinding == profile.id) {
                                profileBinding = profile.id
                            }
                        }
                }

                // AutoTDP uses the global fan choice; the per-app fan picker is hidden for it until the
                // service layer supports per-app fan overrides during AutoTDP.
                if (!PerAppConfig.isAuto(profileBinding)) {
                    DialogGroupLabel(strings.perAppDialogFan)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip(strings.defaultStr, fanMode == null) { fanMode = null }
                        FanController.MODES.forEach { mode ->
                            DialogChip(fanModeLabel(mode.value, strings), fanMode == mode.value) { fanMode = mode.value }
                        }
                    }
                }

                if (PerAppConfig.isAuto(profileBinding)) {
                    // AutoTDP owns the refresh rate (pins the panel to max), so the user picks an FPS
                    // target instead — AutoTDP trims clocks to hold it.
                    DialogGroupLabel(strings.perAppDialogFpsTarget)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fpsOptions.forEach { target ->
                            val label = if (target <= 0) strings.maxStr else "$target FPS"
                            DialogChip(label, fpsTarget == target) {
                                fpsTarget = target
                            }
                        }
                    }
                    // Aggressive core parking is part of the AutoTDP algorithm, so it's set per app here.
                    DialogGroupLabel(strings.perAppDialogAggressivePark)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip(strings.on, aggressivePark) { aggressivePark = true }
                        DialogChip(strings.off, !aggressivePark) { aggressivePark = false }
                    }
                    DialogGroupLabel(strings.perAppDialogEfficiency)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip(strings.inheritStr, bias == null) { bias = null }
                        AutoTdpBias.entries.forEach { b ->
                            DialogChip(b.localizedLabel(strings), bias == b) { bias = b }
                        }
                    }
                } else {
                    DialogGroupLabel(strings.perAppDialogRefreshRate)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip(strings.defaultStr, refreshRate == null) { refreshRate = null }
                        listOf(60, 90, 120).forEach { hz ->
                            DialogChip("$hz Hz", refreshRate == hz) { refreshRate = hz }
                        }
                    }
                }

                Text(
                    text = strings.perAppHelpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    PerAppConfig(
                        packageName = app.packageName,
                        appLabel = app.label,
                        profileBinding = profileBinding,
                        fanMode = fanMode,
                        refreshRateHz = refreshRate,
                        fpsTarget = fpsTarget,
                        aggressivePark = aggressivePark,
                        bias = bias,
                    ),
                )
            }) {
                Text(strings.save)
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onRemove) {
                        Text(strings.perAppRemoveButton)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
            }
        },
    )
}

@Composable
private fun DialogGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DialogChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .border(
                1.dp,
                if (selected) accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}
