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
                        text = "Per-app profiles",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Tap an app to bind a profile and extras",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack) {
                    Text("Done")
                }
            }

            val loaded = apps
            if (loaded == null) {
                Text(
                    text = "Loading apps…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps") },
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

private fun bindingSummary(config: PerAppConfig?, profiles: List<PerformanceProfile>): String {
    if (config == null) return "Not configured"
    val parts = mutableListOf<String>()
    when {
        config.profileBinding == null -> {}
        PerAppConfig.isAuto(config.profileBinding) ->
            parts += "AutoTDP" + (config.fpsTarget?.let { " ${PerAppConfig.fpsTargetLabel(it)}fps" } ?: "") +
                when (config.aggressivePark) { true -> " · Park"; false -> " · No-park"; null -> "" }
        else -> PerAppConfig.tierFromBinding(config.profileBinding)?.let { parts += it.label }
            ?: run { parts += profiles.firstOrNull { it.id == config.profileBinding }?.name ?: "Saved profile" }
    }
    config.fanMode?.let { parts += "Fan ${FanController.labelFor(it)}" }
    // Refresh rate only matters for non-AutoTDP bindings (AutoTDP forces max).
    if (!PerAppConfig.isAuto(config.profileBinding)) config.refreshRateHz?.let { parts += "${it}Hz" }
    return if (parts.isEmpty()) "Not configured" else parts.joinToString(" · ")
}

/** "3h 51m" style runtime from a full battery at the given sustained draw. */
private fun formatRuntime(capacityWh: Float, watts: Float): String {
    val totalMinutes = (capacityWh / watts * 60f).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
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
                    text = bindingSummary(config, profiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (configured) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                // AutoTDP learning status: once a session converges, this app's operating point is saved and
                // warm-started next launch — so it starts already tuned instead of re-discovering from scratch.
                tuned?.let {
                    Text(
                        text = if (it) "✓ tuned" else "learning…",
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
                        text = String.format(java.util.Locale.US, "AVG PW DRAW %.1f W", drawW),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                    )
                    if (batteryCapacityWh > 0f) {
                        // Runtime from the same average draw — the realistic figure.
                        Text(
                            text = "≈ ${formatRuntime(batteryCapacityWh, drawW)}",
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
                DialogGroupLabel("PROFILE")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogChip("None", profileBinding == null) { profileBinding = null }
                    DialogChip("AutoTDP", PerAppConfig.isAuto(profileBinding)) {
                        profileBinding = PerAppConfig.AUTO_BINDING
                    }
                    PowerTier.entries.forEach { tier ->
                        val binding = PerAppConfig.tierBinding(tier)
                        DialogChip(tier.label, profileBinding == binding) { profileBinding = binding }
                    }
                    profiles
                        .filter { it.source != ProfileSource.VIRTUAL || it.id == ProfileStateResolver.STOCK_PROFILE_ID }
                        .forEach { profile ->
                            DialogChip(profile.name, profileBinding == profile.id) {
                                profileBinding = profile.id
                            }
                        }
                }

                // AutoTDP uses the global fan choice; the per-app fan picker is hidden for it until the
                // service layer supports per-app fan overrides during AutoTDP.
                if (!PerAppConfig.isAuto(profileBinding)) {
                    DialogGroupLabel("FAN (ODIN)")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip("Default", fanMode == null) { fanMode = null }
                        FanController.MODES.forEach { mode ->
                            DialogChip(mode.label, fanMode == mode.value) { fanMode = mode.value }
                        }
                    }
                }

                if (PerAppConfig.isAuto(profileBinding)) {
                    // AutoTDP owns the refresh rate (pins the panel to max), so the user picks an FPS
                    // target instead — AutoTDP trims clocks to hold it.
                    DialogGroupLabel("FPS TARGET")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fpsOptions.forEach { target ->
                            DialogChip(PerAppConfig.fpsTargetLabel(target), fpsTarget == target) {
                                fpsTarget = target
                            }
                        }
                    }
                    // Aggressive core parking is part of the AutoTDP algorithm, so it's set per app here.
                    DialogGroupLabel("AGGRESSIVE PARK")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip("On", aggressivePark) { aggressivePark = true }
                        DialogChip("Off", !aggressivePark) { aggressivePark = false }
                    }
                    DialogGroupLabel("EFFICIENCY")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip("Inherit", bias == null) { bias = null }
                        AutoTdpBias.entries.forEach { b ->
                            DialogChip(b.label, bias == b) { bias = b }
                        }
                    }
                } else {
                    DialogGroupLabel("REFRESH RATE")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DialogChip("Default", refreshRate == null) { refreshRate = null }
                        listOf(60, 90, 120).forEach { hz ->
                            DialogChip("$hz Hz", refreshRate == hz) { refreshRate = hz }
                        }
                    }
                }

                Text(
                    text = "Applied when this app comes to the foreground; the previous state is restored when it leaves. \"Default\" leaves that control alone. AutoTDP pins the panel to max refresh and trims the CPU then GPU to hold your FPS target at the lowest power; pair it with the global Custom fan on the main screen. \"Default\" target uses the global default, \"Max\" runs uncapped. Custom applies your saved Custom setup — for frequencies unique to this app, save a profile on the main screen and bind it here.",
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
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = onRemove) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
