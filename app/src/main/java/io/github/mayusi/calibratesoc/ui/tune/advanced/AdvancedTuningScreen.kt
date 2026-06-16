package io.github.mayusi.calibratesoc.ui.tune.advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.capability.AdrenoExtrasProbe
import io.github.mayusi.calibratesoc.data.capability.BlockDeviceProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CoolingDeviceProbe
import io.github.mayusi.calibratesoc.data.capability.DevfreqDeviceProbe
import io.github.mayusi.calibratesoc.data.capability.GpuProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.SchedBoostInterface
import io.github.mayusi.calibratesoc.data.capability.ThermalZoneExtras
import io.github.mayusi.calibratesoc.data.capability.VmSysctlsProbe
import io.github.mayusi.calibratesoc.data.thermal.FanCurveModel
import io.github.mayusi.calibratesoc.data.thermal.PredictiveThrottleGuard
import io.github.mayusi.calibratesoc.data.thermal.ThrottleForecast
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.Risk
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.ValueKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.VoltageControl
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.MetricTile
import io.github.mayusi.calibratesoc.ui.components.PanelAccentEdge
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatBar
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Advanced Tuning screen — Direction C Arsenal rebuild.
 *
 * EASY at the top: one clear primary action (Apply / Generate Script CTA).
 * DEEP below: per-cluster CPU cards, GPU, thermal guard, fan curve, scheduler, VM, I/O.
 *
 * Arsenal look throughout: ArsenalPanel, MetricTile, StatBar, StatusPill, ArsenalButton.
 * No plain Material rounded cards.
 */
@Composable
fun AdvancedTuningScreen(
    onBack: () -> Unit,
    viewModel: AdvancedTuningViewModel = hiltViewModel(),
) {
    val report by viewModel.capability.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val customHistory by viewModel.customRuleHistory.collectAsStateWithLifecycle()
    val pendingAdvanced by viewModel.pendingAdvanced.collectAsStateWithLifecycle()
    val lastDeploy by viewModel.lastDeploy.collectAsStateWithLifecycle()
    val thermalGuardEnabled by viewModel.thermalGuardEnabled.collectAsStateWithLifecycle()
    val throttleForecast by viewModel.throttleForecast.collectAsStateWithLifecycle()
    val fanCurveModel by viewModel.fanCurveModel.collectAsStateWithLifecycle()

    val r = report
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Probing device…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF999999))
        }
        return
    }

    val globalScriptBuilderMode = r.privilege != PrivilegeTier.ROOT
    var dangerousExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        // ── Page header ─────────────────────────────────────────────────────────
        item {
            ArsenalAdvancedHeader(
                report = r,
                onBack = onBack,
                scriptBuilderMode = globalScriptBuilderMode,
                stagedCount = pendingAdvanced.size,
                onGenerateScript = { viewModel.generateAdvancedScript() },
                onClearStaged = { viewModel.clearPendingAdvanced() },
            )
        }

        // ── Script deploy result ────────────────────────────────────────────────
        lastDeploy?.let { deployed ->
            item {
                ArsenalScriptDeployedCard(deployed = deployed, onDismiss = viewModel::clearLastDeploy)
            }
        }

        // ── Last write result feedback (live-write mode) ───────────────────────
        lastResult?.let { result ->
            item {
                ArsenalWriteResultCard(result = result, onDismiss = viewModel::clearLastResult)
            }
        }

        // ══ EASY ZONE — Primary actions at the top ══════════════════════════════

        // ── 1. Predictive Thermal Guard ─────────────────────────────────────────
        item {
            ThermalGuardPanel(
                enabled = thermalGuardEnabled,
                forecast = throttleForecast,
                onToggle = { viewModel.setThermalGuardEnabled(it) },
            )
        }

        // ── 2. Fan curve (only if device has a controllable fan) ────────────────
        fanCurveModel?.let { fan ->
            if (fan.isActive) {
                item {
                    FanCurvePanel(fanCurveModel = fan)
                }
            }
        }

        // ══ DEEP ZONE — Power-user controls below ═══════════════════════════════

        // ── 3. CPU per-cluster cards (one per policy) ───────────────────────────
        item {
            ArsenalCpuClustersSection(
                report = r,
                pending = pendingAdvanced,
                onWrite = { id, value, reason ->
                    if (viewModel.isScriptBuilderMode(id, r)) viewModel.stageAdvancedKnob(id, value)
                    else viewModel.write(id, value, reason)
                },
                onStage = { id, value -> viewModel.stageAdvancedKnob(id, value) },
                onUnstage = { id -> viewModel.unstageAdvancedKnob(id) },
                scriptBuilderMode = globalScriptBuilderMode,
            )
        }

        // ── 4. CPU Governor Tunables ────────────────────────────────────────────
        if (r.cpuGovernorTunables.isNotEmpty()) {
            item {
                ArsenalExpandableSection("CPU GOVERNOR TUNING", Icons.Outlined.Settings, AccentBar.Blue) {
                    Text(
                        "Per-policy governor tunables — values from last probe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                    r.cpuGovernorTunables.forEachIndexed { i, probe ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
                        Text(
                            "policy${probe.policyId} — ${probe.governor}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentBar.Blue,
                            letterSpacing = 0.06.sp,
                            modifier = Modifier.padding(top = Spacing.group),
                        )
                        probe.tunables.entries.sortedBy { it.key }.forEach { (name, currentValue) ->
                            val id = KernelTunables.cpuGovernorTunable(probe.policyId, probe.governor, name)
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                            TunableControl(id = id, currentValue = currentValue, report = r, onWrite = { tid, v, rsn ->
                                if (viewModel.isScriptBuilderMode(tid, r)) viewModel.stageAdvancedKnob(tid, v)
                                else viewModel.write(tid, v, rsn)
                            }, pending = pendingAdvanced, scriptBuilderMode = globalScriptBuilderMode)
                        }
                    }
                }
            }
        }

        // ── 5. GPU Advanced ─────────────────────────────────────────────────────
        r.gpu?.let { gpu ->
            item {
                ArsenalGpuAdvancedSection(
                    report = r,
                    gpu = gpu,
                    adrenoExtras = r.adrenoExtras,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) viewModel.stageAdvancedKnob(id, value)
                        else viewModel.write(id, value, reason)
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 6. Scheduler Boost ──────────────────────────────────────────────────
        if (r.schedBoostInterface != SchedBoostInterface.NONE && r.schedBoostValues.isNotEmpty()) {
            item {
                ArsenalExpandableSection("SCHEDULER BOOST", Icons.Outlined.Tune, AccentBar.Blue) {
                    SchedBoostContent(report = r, scriptBuilderMode = globalScriptBuilderMode, onWrite = { id, value, reason -> viewModel.write(id, value, reason) })
                }
            }
        }

        // ── 7. Input Boost ──────────────────────────────────────────────────────
        if (r.inputBoostPresent) {
            item {
                ArsenalExpandableSection("INPUT BOOST", Icons.Outlined.TouchApp, AccentBar.Blue) {
                    InputBoostContent(report = r, pending = pendingAdvanced, onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) viewModel.stageAdvancedKnob(id, value)
                        else viewModel.write(id, value, reason)
                    }, scriptBuilderMode = globalScriptBuilderMode)
                }
            }
        }

        // ── 8. Memory / Bus ─────────────────────────────────────────────────────
        if (r.devfreqDevices.isNotEmpty()) {
            item {
                ArsenalExpandableSection("MEMORY / BUS (devfreq)", Icons.Outlined.Memory, AccentBar.Blue) {
                    MemoryBusContent(report = r, devices = r.devfreqDevices, pending = pendingAdvanced, onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) viewModel.stageAdvancedKnob(id, value)
                        else viewModel.write(id, value, reason)
                    }, scriptBuilderMode = globalScriptBuilderMode)
                }
            }
        }

        // ── 9. I/O Scheduler ────────────────────────────────────────────────────
        if (r.blockDevices.isNotEmpty()) {
            item {
                ArsenalExpandableSection("I/O SCHEDULER", Icons.Outlined.Storage, AccentBar.Neutral) {
                    IoContent(report = r, devices = r.blockDevices, pending = pendingAdvanced, onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) viewModel.stageAdvancedKnob(id, value)
                        else viewModel.write(id, value, reason)
                    }, scriptBuilderMode = globalScriptBuilderMode)
                }
            }
        }

        // ── 10. VM / Kernel Sysctls ─────────────────────────────────────────────
        r.vmSysctls?.let { vm ->
            item {
                ArsenalExpandableSection("VM / KERNEL SYSCTLS", Icons.Outlined.Settings, AccentBar.Neutral) {
                    VmKernelContent(report = r, vm = vm, pending = pendingAdvanced, onWrite = { id, value, reason ->
                        if (r.privilege == PrivilegeTier.ROOT) viewModel.write(id, value, reason)
                        else viewModel.stageAdvancedKnob(id, value)
                    }, scriptBuilderMode = globalScriptBuilderMode)
                }
            }
        }

        // ── 11. Custom Sysfs Rule ───────────────────────────────────────────────
        item {
            ArsenalExpandableSection("CUSTOM SYSFS RULE", Icons.Outlined.Code, AccentBar.Amber) {
                CustomSysfsContent(report = r, history = customHistory, pending = pendingAdvanced,
                    scriptBuilderMode = globalScriptBuilderMode, onWrite = { path, value -> viewModel.writeCustomRule(path, value) })
            }
        }

        // ── 12. Voltage / Undervolt honesty card ────────────────────────────────
        item { ArsenalVoltageHonestyPanel() }

        // ── 13. Dangerous thermal section ───────────────────────────────────────
        if (r.thermalExtras.isNotEmpty() || r.coolingDevices.isNotEmpty()) {
            item {
                ArsenalDangerousExpander(
                    expanded = dangerousExpanded,
                    onToggle = { dangerousExpanded = !dangerousExpanded },
                )
            }
            if (dangerousExpanded) {
                item {
                    ArsenalThermalDangerousSection(
                        report = r,
                        thermalExtras = r.thermalExtras,
                        coolingDevices = r.coolingDevices,
                        onWrite = { id, value, reason -> viewModel.write(id, value, reason) },
                    )
                }
            }
        }

        // ── Bottom Generate Script CTA ──────────────────────────────────────────
        if (globalScriptBuilderMode && pendingAdvanced.isNotEmpty()) {
            item {
                ArsenalGenerateScriptCta(
                    stagedCount = pendingAdvanced.size,
                    onGenerate = { viewModel.generateAdvancedScript() },
                    onClear = { viewModel.clearPendingAdvanced() },
                )
            }
        }

        // ── Footer ──────────────────────────────────────────────────────────────
        item { ArsenalRevertFooter(scriptBuilderMode = globalScriptBuilderMode) }
    }
}

// =============================================================================
// Header
// =============================================================================

@Composable
private fun ArsenalAdvancedHeader(
    report: CapabilityReport,
    onBack: () -> Unit,
    scriptBuilderMode: Boolean,
    stagedCount: Int,
    onGenerateScript: () -> Unit,
    onClearStaged: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.Settings, contentDescription = "Back", tint = Color(0xFF999999), modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ADVANCED TUNING",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.05.sp,
                    color = Color.White,
                )
                Text(
                    "Per-cluster, GPU, thermal, I/O",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF999999),
                    letterSpacing = 0.04.sp,
                )
            }
        }

        // Mode disclosure
        val modeAccent = when {
            report.privilege == PrivilegeTier.ROOT -> AccentBar.Emerald
            report.sysfsDirectlyWritable -> AccentBar.Blue
            else -> AccentBar.Amber
        }
        val modeLabel = when {
            report.privilege == PrivilegeTier.ROOT -> "ROOT — writes go live immediately"
            report.sysfsDirectlyWritable -> "UNLOCK ACTIVE — CPU/GPU/I/O write live"
            else -> "SCRIPT BUILDER — stage knobs, then Generate Script"
        }
        val modeBody = when {
            report.privilege == PrivilegeTier.ROOT ->
                "Every change is snapshotted before writing and reverts automatically on next reboot."
            report.sysfsDirectlyWritable ->
                "The unlock script ran. CPU, GPU, DDR, I/O, input-boost write directly. Scheduler boost (cgroups) and VM sysctls are script-only."
            else ->
                "Nothing writes until you run the script via Settings → Run script as Root. Controls are enabled — select values, then Generate Script."
        }

        ArsenalPanel(accent = modeAccent) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group), verticalAlignment = Alignment.Top) {
                StatusPill(text = modeLabel, accent = modeAccent)
            }
            Spacer(Modifier.height(Spacing.dense))
            Text(modeBody, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBBBBBB))
        }

        // Generate Script CTA at top when knobs are staged
        if (scriptBuilderMode && stagedCount > 0) {
            ArsenalGenerateScriptCta(stagedCount = stagedCount, onGenerate = onGenerateScript, onClear = onClearStaged)
        }
    }
}

// =============================================================================
// Script deploy result
// =============================================================================

@Composable
private fun ArsenalScriptDeployedCard(deployed: AynScriptDeployer.Deployed, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ArsenalPanel(accent = AccentBar.Amber, title = "Script generated") {
        Text(
            "Saved to: ${deployed.path}\n\n${
                if (deployed.visibleToOdinPicker) "Open your device Settings → Run script as Root → pick the .sh from the CalibrateSoC folder."
                else "Script in app-private folder — copy to /sdcard/CalibrateSoC/ manually or grant storage permission."
            }",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC),
        )
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            if (deployed.visibleToOdinPicker) {
                ArsenalButton(label = "Open Settings", onClick = {
                    io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openVendorSettings(context)
                }, accent = AccentBar.Amber)
            }
            ArsenalButton(label = "Dismiss", onClick = onDismiss, accent = AccentBar.Neutral, style = ArsenalButtonStyle.Secondary)
        }
    }
}

// =============================================================================
// Write result feedback
// =============================================================================

@Composable
private fun ArsenalWriteResultCard(result: WriteResult, onDismiss: () -> Unit) {
    val (accent, title, message) = when (result) {
        is WriteResult.Success -> Triple(AccentBar.Emerald, "WRITE ACCEPTED",
            "${result.id.target.substringAfterLast("/")} = ${result.newValue}${result.previousValue?.let { " (was: $it)" } ?: ""}")
        is WriteResult.CapabilityDenied -> Triple(AccentBar.Amber, "WRITE BLOCKED", result.reason)
        is WriteResult.Rejected -> Triple(AccentBar.Red, "KERNEL REJECTED WRITE",
            result.message + (result.errno?.let { " (errno $it)" } ?: ""))
        is WriteResult.Failed -> Triple(AccentBar.Red, "WRITE FAILED", result.error.message ?: result.error.javaClass.simpleName)
    }
    ArsenalPanel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            StatusPill(text = title, accent = accent)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
            ArsenalButton(label = "Dismiss", onClick = onDismiss, accent = AccentBar.Neutral, style = ArsenalButtonStyle.Secondary)
        }
    }
}

// =============================================================================
// Generate Script CTA
// =============================================================================

@Composable
private fun ArsenalGenerateScriptCta(stagedCount: Int, onGenerate: () -> Unit, onClear: () -> Unit) {
    ArsenalPanel(accent = AccentBar.Amber, accentEdge = PanelAccentEdge.Bottom, title = "$stagedCount knob${if (stagedCount != 1) "s" else ""} staged") {
        Text(
            "Generate a script with all staged knobs, then run it via Settings → Run script as Root.",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            ArsenalButton(
                label = "Generate Script ($stagedCount)",
                onClick = onGenerate,
                accent = AccentBar.Amber,
                modifier = Modifier.weight(1f),
            )
            ArsenalButton(label = "Clear", onClick = onClear, accent = AccentBar.Neutral, style = ArsenalButtonStyle.Secondary)
        }
    }
}

// =============================================================================
// Thermal Guard panel — EASY zone, primary power-user action
// =============================================================================

@Composable
private fun ThermalGuardPanel(
    enabled: Boolean,
    forecast: ThrottleForecast?,
    onToggle: (Boolean) -> Unit,
) {
    val accent = if (enabled && forecast?.actionRequired == true) AccentBar.Red
                 else if (enabled) AccentBar.Emerald
                 else AccentBar.Neutral

    ArsenalPanel(accent = accent, title = "Predictive Thermal Guard") {
        Text(
            "Monitors CPU temperature trend using linear regression. Applies a pre-emptive frequency cap " +
                "when a kernel throttle cliff is predicted within ${PredictiveThrottleGuard.DEFAULT_HORIZON_SECONDS}s — " +
                "keeping FPS smooth instead of a sudden throttle drop.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "THERMAL GUARD",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.07.sp,
            )
            Box(
                modifier = Modifier
                    .background(
                        if (enabled) accent.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, if (enabled) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .clickable { onToggle(!enabled) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (enabled) "ON" else "OFF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) accent else Color(0xFF888888),
                    letterSpacing = 0.06.sp,
                )
            }
        }

        if (enabled && forecast != null) {
            Spacer(Modifier.height(Spacing.dense))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(Spacing.dense))
            if (forecast.actionRequired) {
                StatusPill(text = "THROTTLE IMMINENT", accent = AccentBar.Red)
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    forecast.reason,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = AccentBar.Red,
                )
                forecast.recommendedCapKhz?.let { cap ->
                    Spacer(Modifier.height(Spacing.dense))
                    MetricTile(
                        label = "RECOMMENDED PRE-EMPTIVE CAP",
                        value = "${cap / 1000}",
                        unit = "MHz",
                        accent = AccentBar.Red,
                        valueColor = AccentBar.Red,
                    )
                }
            } else {
                StatusPill(text = "THERMAL OK", accent = AccentBar.Emerald)
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    forecast.reason,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF888888),
                )
            }
        } else if (enabled) {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Collecting telemetry window… need ${PredictiveThrottleGuard.MIN_WINDOW_POINTS} samples.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF888888),
            )
        }
    }
}

// =============================================================================
// Fan curve panel (only shown when device has a controllable fan)
// =============================================================================

@Composable
private fun FanCurvePanel(fanCurveModel: FanCurveModel) {
    ArsenalPanel(accent = AccentBar.Blue, title = "Fan curve") {
        val probe = fanCurveModel.fanProbe!!
        Text(
            "Device has a controllable fan (${probe.source.name}). Default curve shown — curve editor coming in a future update. Current RPM: ${probe.currentRpm?.let { "$it RPM" } ?: "unknown"}.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.dense))
        fanCurveModel.points.forEachIndexed { i, pt ->
            StatBar(
                label = "${pt.tempC.toInt()}°C",
                value = "${pt.dutyPct.toInt()}%",
                fraction = pt.dutyPct / 100f,
                accent = when {
                    pt.dutyPct >= 90f -> AccentBar.Red
                    pt.dutyPct >= 65f -> AccentBar.Amber
                    pt.dutyPct >= 30f -> AccentBar.Blue
                    else -> AccentBar.Neutral
                },
            )
            if (i < fanCurveModel.points.size - 1) Spacer(Modifier.height(Spacing.dense))
        }
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Fan control path: ${probe.controlPath}. Source: ${probe.source.name}. Curve editor will be exposed once the write path is verified on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )
    }
}

// =============================================================================
// Per-cluster CPU cards
// =============================================================================

@Composable
private fun ArsenalCpuClustersSection(
    report: CapabilityReport,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    onStage: (TunableId, String) -> String?,
    onUnstage: (TunableId) -> Unit,
    scriptBuilderMode: Boolean,
) {
    val allPolicyMaxKhz = report.cpuPolicies.map { it.currentMaxKhz }.distinct().sorted()

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        SectionHeader(title = "CPU Clusters", accent = AccentBar.Blue)
        Spacer(Modifier.height(Spacing.dense))

        report.cpuPolicies.forEach { policy ->
            val tierLabel = clusterTierLabel(policy.currentMaxKhz, allPolicyMaxKhz).uppercase()
            val clusterAccent = when (tierLabel) {
                "EFFICIENCY" -> AccentBar.Emerald
                "BIG" -> AccentBar.Blue
                "PRIME" -> AccentBar.Red
                else -> AccentBar.Blue
            }

            ArsenalPanel(accent = clusterAccent) {
                // Cluster header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    StatusPill(text = tierLabel, accent = clusterAccent)
                    Text(
                        "policy${policy.policyId}  •  cores ${policy.onlineCores.min()}–${policy.onlineCores.max()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888),
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(Modifier.height(Spacing.group))

                // Live frequency StatBars
                val freqFraction = if (policy.currentMaxKhz > 0 && policy.availableFreqsKhz.isNotEmpty()) {
                    val max = policy.availableFreqsKhz.max().toFloat()
                    policy.currentMaxKhz.toFloat() / max
                } else 0f

                StatBar(
                    label = "Max freq",
                    value = "${policy.currentMaxKhz / 1000} MHz",
                    fraction = freqFraction,
                    accent = clusterAccent,
                )
                Spacer(Modifier.height(Spacing.dense))
                val minFraction = if (policy.currentMinKhz > 0 && policy.availableFreqsKhz.isNotEmpty()) {
                    val max = policy.availableFreqsKhz.max().toFloat()
                    policy.currentMinKhz.toFloat() / max
                } else 0f
                StatBar(
                    label = "Min freq",
                    value = "${policy.currentMinKhz / 1000} MHz",
                    fraction = minFraction,
                    accent = clusterAccent.copy(alpha = 0.6f),
                )

                Spacer(Modifier.height(Spacing.group))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(Spacing.group))

                // Governor picker
                if (policy.availableGovernors.size > 1) {
                    val scalingGovId = Tunables.cpuGovernor(policy.policyId)
                    Text("GOVERNOR", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(Spacing.dense))
                    GovernorDropdown(
                        current = policy.currentGovernor,
                        options = policy.availableGovernors,
                        enabled = scriptBuilderMode || Tunables.whyWriteDenied(scalingGovId, report) == null,
                        onSelect = { gov ->
                            onWrite(scalingGovId, gov, "CPU governor policy${policy.policyId}")
                        },
                    )
                    Spacer(Modifier.height(Spacing.group))
                }

                // Max freq slider
                if (policy.availableFreqsKhz.size > 1) {
                    val maxId = Tunables.cpuMaxFreq(policy.policyId)
                    Text("MAX FREQ (scaling_max_freq)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(Spacing.dense))
                    FreqKhzControl(
                        currentKhz = policy.currentMaxKhz,
                        minKhz = policy.availableFreqsKhz.min(),
                        maxKhz = policy.availableFreqsKhz.max(),
                        enabled = scriptBuilderMode || Tunables.whyWriteDenied(maxId, report) == null,
                        onCommit = { onWrite(maxId, it.toString(), "CPU max freq policy${policy.policyId}") },
                    )
                    Spacer(Modifier.height(Spacing.group))
                }

                // Min freq slider
                if (policy.availableFreqsKhz.size > 1) {
                    val minId = Tunables.cpuMinFreq(policy.policyId)
                    Text("MIN FREQ (scaling_min_freq)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
                    Spacer(Modifier.height(Spacing.dense))
                    FreqKhzControl(
                        currentKhz = policy.currentMinKhz,
                        minKhz = policy.availableFreqsKhz.min(),
                        maxKhz = policy.availableFreqsKhz.max(),
                        enabled = scriptBuilderMode || Tunables.whyWriteDenied(minId, report) == null,
                        onCommit = { onWrite(minId, it.toString(), "CPU min freq policy${policy.policyId}") },
                    )
                }

                // CPU0 protection note
                if (0 in policy.onlineCores) {
                    Spacer(Modifier.height(Spacing.dense))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Spacer(Modifier.height(Spacing.dense))
                    Text(
                        "cpu0 is the boot processor — cannot be offlined. Online/offline of other cores available in CPU Cores section.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBar.Amber,
                    )
                }
            }
        }

        // CPU Cores online/offline
        ArsenalExpandableSection("CPU CORE ONLINE/OFFLINE", Icons.Outlined.Speed, AccentBar.Blue, initiallyExpanded = false) {
            Text("CPU0 cannot be offlined — it is the boot processor.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999))
            val maxCore = report.cpuPolicies.flatMap { it.onlineCores }.maxOrNull() ?: 0
            for (coreIdx in 0..maxCore) {
                val isCpu0 = coreIdx == 0
                val id = KernelTunables.cpuOnline(coreIdx)
                val meta = TunableMetadata.forId(id)
                val denyReason = Tunables.whyWriteDenied(id, report)
                val isStaged = id.target in pending
                val cluster = report.cpuPolicies.firstOrNull { coreIdx in it.onlineCores }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                            Text("CPU$coreIdx", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                            cluster?.let { Text("policy${it.policyId}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888)) }
                            ArsenalRiskBadge(meta.risk)
                            if (isStaged) StatusPill(text = "staged", accent = AccentBar.Blue)
                        }
                        if (isCpu0) Text("Boot processor — cannot be offlined", style = MaterialTheme.typography.labelSmall, color = AccentBar.Red)
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                if (!isCpu0) AccentBar.Emerald.copy(alpha = 0.15f) else Color(0xFF0C0C10),
                                RoundedCornerShape(4.dp),
                            )
                            .border(1.dp, if (!isCpu0) AccentBar.Emerald.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                            .let { mod ->
                                if (!isCpu0) mod.clickable {
                                    onWrite(id, "0", "CPU$coreIdx offline")
                                } else mod
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "ONLINE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (!isCpu0) AccentBar.Emerald else Color(0xFF555555),
                            letterSpacing = 0.06.sp,
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// GPU Advanced section
// =============================================================================

@Composable
private fun ArsenalGpuAdvancedSection(
    report: CapabilityReport,
    gpu: GpuProbe,
    adrenoExtras: AdrenoExtrasProbe?,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ArsenalExpandableSection("GPU ADVANCED", Icons.Outlined.Bolt, AccentBar.Purple) {
        Text(
            "Adreno power levels (0 = fastest, higher = slower/lower voltage).",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )

        if (adrenoExtras != null) {
            val numLevels = adrenoExtras.pwrLevelFreqHz.size.coerceAtLeast(
                maxOf(
                    (adrenoExtras.currentMinPwrLevel ?: 0),
                    (adrenoExtras.currentMaxPwrLevel ?: 0),
                    (adrenoExtras.currentDefaultPwrLevel ?: 0),
                ) + 1,
            ).coerceAtLeast(1)

            fun levelLabel(idx: Int?): String {
                if (idx == null) return "—"
                val hz = adrenoExtras.pwrLevelFreqHz[idx]
                return if (hz != null) "$idx (${"%.0f".format(hz / 1_000_000f)} MHz)" else "$idx"
            }

            Spacer(Modifier.height(Spacing.group))

            // Power level MetricTiles
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                MetricTile(
                    label = "MIN PWR LEVEL",
                    value = levelLabel(adrenoExtras.currentMinPwrLevel),
                    unit = null,
                    accent = AccentBar.Purple,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "MAX PWR LEVEL",
                    value = levelLabel(adrenoExtras.currentMaxPwrLevel),
                    unit = null,
                    accent = AccentBar.Purple,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = "DEFAULT",
                    value = levelLabel(adrenoExtras.currentDefaultPwrLevel),
                    unit = null,
                    accent = AccentBar.Neutral,
                    modifier = Modifier.weight(1f),
                )
            }

            if (numLevels > 1) {
                Spacer(Modifier.height(Spacing.group))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Spacer(Modifier.height(Spacing.group))

                // Min power level slider
                val minId = KernelTunables.adrenoMinPowerLevel(gpu.rootPath)
                Text("MIN POWER LEVEL (min_pwrlevel)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
                Spacer(Modifier.height(Spacing.dense))
                IntRangeControl(
                    current = adrenoExtras.currentMinPwrLevel ?: 0,
                    min = 0, max = numLevels - 1, step = 1, unit = "(lower=faster)",
                    enabled = scriptBuilderMode || Tunables.whyWriteDenied(minId, report) == null,
                    onCommit = { onWrite(minId, it.toString(), "GPU min power level") },
                )

                Spacer(Modifier.height(Spacing.group))

                // Max power level slider
                val maxId = KernelTunables.adrenoMaxPowerLevel(gpu.rootPath)
                Text("MAX POWER LEVEL (max_pwrlevel)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
                Spacer(Modifier.height(Spacing.dense))
                IntRangeControl(
                    current = adrenoExtras.currentMaxPwrLevel ?: 0,
                    min = 0, max = numLevels - 1, step = 1, unit = "(lower=faster)",
                    enabled = scriptBuilderMode || Tunables.whyWriteDenied(maxId, report) == null,
                    onCommit = { onWrite(maxId, it.toString(), "GPU max power level") },
                )
            }

            // GPU throttling toggle
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
            val throttleId = KernelTunables.gpuThrottling(gpu.rootPath)
            TunableControl(id = throttleId, currentValue = if (adrenoExtras.throttlingEnabled == true) "1" else "0",
                report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)

            // Force clocks on
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
            val forceClkId = KernelTunables.gpuForceClkOn(gpu.rootPath)
            TunableControl(id = forceClkId, currentValue = if (adrenoExtras.forceClkOn == true) "1" else "0",
                report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)

            // Idle timer
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
            val idleTimerId = KernelTunables.gpuIdleTimer(gpu.rootPath)
            TunableControl(id = idleTimerId, currentValue = adrenoExtras.idleTimerMs?.toString() ?: "0",
                report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
        }

        // GPU governor
        if (gpu.availableGovernors.size > 1) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
            val govId = Tunables.gpuGovernor(gpu.rootPath)
            Text("GPU GOVERNOR", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
            Spacer(Modifier.height(Spacing.dense))
            GovernorDropdown(
                current = gpu.currentGovernor,
                options = gpu.availableGovernors,
                enabled = scriptBuilderMode || Tunables.whyWriteDenied(govId, report) == null,
                onSelect = { onWrite(govId, it, "GPU governor") },
            )
        }

        // GPU devfreq governor tunables
        if (report.gpuGovernorTunables.isNotEmpty()) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
            Text("GPU GOVERNOR TUNABLES", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888), letterSpacing = 0.06.sp)
            report.gpuGovernorTunables.forEach { probe ->
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                val id = KernelTunables.gpuDevfreqGovernorTunable(gpu.rootPath, probe.governor, probe.name)
                TunableControl(id = id, currentValue = probe.currentValue, report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
            }
        }
    }
}

// =============================================================================
// Section content helpers (unchanged logic, Arsenal styled container)
// =============================================================================

@Composable
private fun SchedBoostContent(report: CapabilityReport, scriptBuilderMode: Boolean, onWrite: (TunableId, String, String) -> String?) {
    val iface = report.schedBoostInterface
    if (scriptBuilderMode) {
        AlertCard(type = AlertType.WARNING, title = "Root only — not scriptable",
            message = "Scheduler boost controls cgroup hierarchies. SELinux blocks writes from app UID on stock. Shown read-only.")
    }
    report.schedBoostValues.filter { it.slice in listOf("top-app", "foreground") }.forEach { probe ->
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
        Text("Slice: ${probe.slice}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AccentBar.Blue, letterSpacing = 0.06.sp)
        if (scriptBuilderMode) {
            when (iface) {
                SchedBoostInterface.STUNE -> KvRow(label = "boost", value = "${probe.boostOrUclampMin ?: 0}", explainer = "prefer_idle: ${probe.preferIdleOrUclampMax ?: 0}")
                SchedBoostInterface.UCLAMP -> KvRow(label = "uclamp.min", value = "${probe.boostOrUclampMin ?: 0}", explainer = "uclamp.max: ${probe.preferIdleOrUclampMax ?: 1024}")
                SchedBoostInterface.NONE -> Unit
            }
        } else {
            when (iface) {
                SchedBoostInterface.STUNE -> {
                    TunableControl(id = KernelTunables.schedtuneBoost(probe.slice), currentValue = probe.boostOrUclampMin?.toString() ?: "0", report = report, onWrite = onWrite)
                    TunableControl(id = KernelTunables.schedtunePreferIdle(probe.slice), currentValue = probe.preferIdleOrUclampMax?.toString() ?: "0", report = report, onWrite = onWrite)
                }
                SchedBoostInterface.UCLAMP -> {
                    TunableControl(id = KernelTunables.uclampMin(probe.slice), currentValue = probe.boostOrUclampMin?.toString() ?: "0", report = report, onWrite = onWrite)
                    TunableControl(id = KernelTunables.uclampMax(probe.slice), currentValue = probe.preferIdleOrUclampMax?.toString() ?: "1024", report = report, onWrite = onWrite)
                }
                SchedBoostInterface.NONE -> Unit
            }
        }
    }
}

@Composable
private fun InputBoostContent(report: CapabilityReport, pending: Map<String, String>, onWrite: (TunableId, String, String) -> String?, scriptBuilderMode: Boolean) {
    val boost = report.inputBoost
    TunableControl(id = KernelTunables.inputBoostFreq(), currentValue = boost?.inputBoostFreqRaw ?: "0", report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
    TunableControl(id = KernelTunables.inputBoostMs(), currentValue = boost?.inputBoostMs?.toString() ?: "0", report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
}

@Composable
private fun MemoryBusContent(report: CapabilityReport, devices: List<DevfreqDeviceProbe>, pending: Map<String, String>, onWrite: (TunableId, String, String) -> String?, scriptBuilderMode: Boolean) {
    devices.forEachIndexed { i, dev ->
        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
        Text(dev.deviceName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AccentBar.Blue)
        Spacer(Modifier.height(2.dp))
        Text("Current: ${"%.0f".format(dev.curFreqHz / 1_000_000f)} MHz", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        if (dev.availableGovernors.size > 1) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(id = KernelTunables.devfreqGovernor(dev.deviceName), currentValue = dev.currentGovernor, report = report, onWrite = onWrite, enumOptions = dev.availableGovernors, pending = pending, scriptBuilderMode = scriptBuilderMode)
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(id = KernelTunables.devfreqMinFreq(dev.deviceName), currentValue = dev.minFreqHz.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(id = KernelTunables.devfreqMaxFreq(dev.deviceName), currentValue = dev.maxFreqHz.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
    }
}

@Composable
private fun IoContent(report: CapabilityReport, devices: List<BlockDeviceProbe>, pending: Map<String, String>, onWrite: (TunableId, String, String) -> String?, scriptBuilderMode: Boolean) {
    devices.forEachIndexed { i, dev ->
        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
        Text("/dev/block/${dev.deviceName}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AccentBar.Neutral)
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(id = KernelTunables.ioScheduler(dev.deviceName), currentValue = dev.currentScheduler, report = report, onWrite = onWrite, enumOptions = dev.availableSchedulers.ifEmpty { null }, pending = pending, scriptBuilderMode = scriptBuilderMode)
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(id = KernelTunables.ioReadAheadKb(dev.deviceName), currentValue = dev.readAheadKb.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(id = KernelTunables.ioNrRequests(dev.deviceName), currentValue = dev.nrRequests.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode)
    }
}

@Composable
private fun VmKernelContent(report: CapabilityReport, vm: VmSysctlsProbe, pending: Map<String, String>, onWrite: (TunableId, String, String) -> String?, scriptBuilderMode: Boolean) {
    if (scriptBuilderMode) Text("Script-builder: these procfs knobs go into the generated script.", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
    vm.swappiness?.let { TunableControl(id = KernelTunables.vmSwappiness(), currentValue = it.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode) }
    vm.vfsCachePressure?.let { HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense)); TunableControl(id = KernelTunables.vmVfsCachePressure(), currentValue = it.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode) }
    vm.dirtyRatio?.let { HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense)); TunableControl(id = KernelTunables.vmDirtyRatio(), currentValue = it.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode) }
    vm.dirtyBackgroundRatio?.let { HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense)); TunableControl(id = KernelTunables.vmDirtyBackgroundRatio(), currentValue = it.toString(), report = report, onWrite = onWrite, pending = pending, scriptBuilderMode = scriptBuilderMode) }
}

@Composable
private fun CustomSysfsContent(report: CapabilityReport, history: List<AdvancedTuningViewModel.CustomSysfsRule>, pending: Map<String, String>, scriptBuilderMode: Boolean, onWrite: (String, String) -> String?) {
    var path by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertCard(type = AlertType.WARNING, title = if (scriptBuilderMode) "Power user — staged into script" else "Power user — your risk",
        message = if (scriptBuilderMode) "Write ANY /sys or /proc path. Staged into script rather than live."
        else "Write ANY /sys or /proc path. Wrong values can destabilise or crash. Reverts on reboot.")
    Spacer(Modifier.height(Spacing.group))
    OutlinedTextField(value = path, onValueChange = { path = it; error = null }, label = { Text("/sys/… or /proc/…") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    OutlinedTextField(value = value, onValueChange = { value = it; error = null }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AccentBar.Red) }
    Spacer(Modifier.height(Spacing.group))
    ArsenalButton(
        label = if (scriptBuilderMode) "Stage rule" else "Apply custom rule",
        onClick = { error = null; val err = onWrite(path.trim(), value.trim()); if (err != null) error = err },
        accent = AccentBar.Amber,
        enabled = path.isNotBlank() && value.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.group))
        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(Spacing.dense))
        Text(if (scriptBuilderMode) "Previously staged:" else "Previously applied:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        history.forEach { rule ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.dense), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Color.White)
                    Text("= ${rule.value}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                }
                TextButton(onClick = { path = rule.path; value = rule.value }) { Text("Load") }
                TextButton(onClick = { error = null; val err = onWrite(rule.path, rule.value); if (err != null) error = err }) {
                    Text(if (scriptBuilderMode) "Re-stage" else "Re-apply")
                }
            }
        }
    }
}

// =============================================================================
// Voltage honesty panel
// =============================================================================

@Composable
private fun ArsenalVoltageHonestyPanel() {
    ArsenalPanel(accent = AccentBar.Neutral, title = "Voltage / Undervolt") {
        AlertCard(type = AlertType.INFO, title = "Not available on this device", message = VoltageControl.unavailableSummary)
        Spacer(Modifier.height(Spacing.dense))
        Text("CPU: ${VoltageControl.cpuVoltageUnavailableExplanation}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999))
        Spacer(Modifier.height(Spacing.dense))
        Text("GPU: ${VoltageControl.gpuVoltageUnavailableExplanation}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999))
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Use the EfficiencyAdvisor (AutoTDP screen) for knee-equivalent efficiency — caps clusters at the measured perf-per-watt knee, achieving most undervolt benefit without touching voltage registers.",
            style = MaterialTheme.typography.bodySmall,
            color = AccentBar.Emerald.copy(alpha = 0.85f),
        )
    }
}

// =============================================================================
// Dangerous section expander
// =============================================================================

@Composable
private fun ArsenalDangerousExpander(expanded: Boolean, onToggle: () -> Unit) {
    ArsenalPanel(accent = AccentBar.Red) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                StatusPill(text = "THERMAL / DANGEROUS CONTROLS", accent = AccentBar.Red)
                Spacer(Modifier.height(Spacing.dense))
                Text("Trip points, zone modes, cooling states — real damage risk", style = MaterialTheme.typography.labelSmall, color = Color(0xFF999999))
            }
            IconButton(onClick = onToggle) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = AccentBar.Red)
            }
        }
    }
}

@Composable
private fun ArsenalThermalDangerousSection(
    report: CapabilityReport,
    thermalExtras: List<ThermalZoneExtras>,
    coolingDevices: List<CoolingDeviceProbe>,
    onWrite: (TunableId, String, String) -> String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        AlertCard(type = AlertType.ERROR, title = "Thermal controls — real damage risk",
            message = "Disabling thermal protection on a fanless device usually makes performance WORSE and risks thermal shutdown or permanent SoC damage. Reverts on reboot, but heat damage before then is permanent.")

        if (thermalExtras.isNotEmpty()) {
            ArsenalExpandableSection("THERMAL ZONES", Icons.Outlined.DeviceThermostat, AccentBar.Red, initiallyExpanded = false) {
                thermalExtras.forEachIndexed { i, zone ->
                    if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
                    Text("thermal_zone${zone.zoneId}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AccentBar.Red)
                    zone.mode?.let { mode ->
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                        TunableControl(id = KernelTunables.thermalZoneMode(zone.zoneId), currentValue = mode, report = report, onWrite = onWrite)
                    }
                    zone.tripPoints.forEach { trip ->
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                        Text("Trip ${trip.index} — ${trip.type} (${trip.tempMilliC / 1000}°C)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        TunableControl(id = KernelTunables.thermalTripPointTemp(zone.zoneId, trip.index), currentValue = trip.tempMilliC.toString(), report = report, onWrite = onWrite)
                    }
                }
            }
        }

        if (coolingDevices.isNotEmpty()) {
            ArsenalExpandableSection("COOLING DEVICES", Icons.Outlined.DeviceThermostat, AccentBar.Red, initiallyExpanded = false) {
                coolingDevices.forEachIndexed { i, dev ->
                    if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.group))
                    Text("cooling_device${dev.id} — ${dev.type}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AccentBar.Red)
                    Text("Max state: ${dev.maxState} | Current: ${dev.currentState}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = Spacing.dense))
                    TunableControl(id = KernelTunables.coolingDeviceCurState(dev.id), currentValue = dev.currentState.toString(), report = report, onWrite = onWrite)
                }
            }
        }
    }
}

// =============================================================================
// Footer
// =============================================================================

@Composable
private fun ArsenalRevertFooter(scriptBuilderMode: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.group), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF555555))
        Spacer(Modifier.height(Spacing.dense))
        Text(
            if (scriptBuilderMode) "Staged knobs → shell script. Run via Settings → Run script as Root. Reverts on reboot."
            else "All writes snapshotted. Reverts automatically on next reboot. Nothing persists permanently without a boot script.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF555555),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// =============================================================================
// Arsenal expandable section — replaces Material ExpandableSectionCard
// =============================================================================

@Composable
private fun ArsenalExpandableSection(
    title: String,
    icon: ImageVector,
    accent: Color,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    ArsenalPanel(accent = accent) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 0.07.sp)
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand", tint = Color(0xFF999999))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(Spacing.group))
            content()
        }
    }
}

// =============================================================================
// Governor dropdown (Arsenal styled)
// =============================================================================

@Composable
private fun GovernorDropdown(current: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(Color(0xFF0C0C10), RoundedCornerShape(4.dp))
            .border(1.dp, if (enabled) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
            .let { if (enabled) it.clickable { expanded = true } else it }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(current, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (enabled) Color.White else Color(0xFF555555))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = Color(0xFF999999), modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option, fontFamily = FontFamily.Monospace) }, onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}

// =============================================================================
// Risk badge (Arsenal styled)
// =============================================================================

@Composable
private fun ArsenalRiskBadge(risk: Risk) {
    val (label, accent) = when (risk) {
        Risk.SAFE -> "SAFE" to AccentBar.Neutral
        Risk.LOW -> "LOW" to AccentBar.Neutral
        Risk.MEDIUM -> "MEDIUM" to AccentBar.Amber
        Risk.HIGH -> "HIGH" to AccentBar.Red
        Risk.DANGEROUS -> "DANGEROUS" to AccentBar.Red
    }
    StatusPill(text = label, accent = accent)
}

// =============================================================================
// Tunable control — same logic, Arsenal styled risk badge
// =============================================================================

@Composable
private fun TunableControl(
    id: TunableId,
    currentValue: String,
    report: CapabilityReport,
    onWrite: (TunableId, String, String) -> String?,
    pending: Map<String, String> = emptyMap(),
    scriptBuilderMode: Boolean = false,
    enumOptions: List<String>? = null,
    modifier: Modifier = Modifier,
) {
    val meta = TunableMetadata.forId(id)
    val denyReason = Tunables.whyWriteDenied(id, report)
    val isDisabled = if (scriptBuilderMode) false else denyReason != null
    val isStaged = id.target in pending

    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(meta.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense), verticalAlignment = Alignment.CenterVertically) {
                if (isStaged) StatusPill(text = "staged", accent = AccentBar.Blue)
                ArsenalRiskBadge(meta.risk)
            }
        }
        Text(meta.description, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        when {
            scriptBuilderMode && denyReason != null && !isStaged ->
                Text("Will be included in generated script when staged", style = MaterialTheme.typography.labelSmall, color = AccentBar.Blue)
            scriptBuilderMode && denyReason != null && isStaged ->
                Text("Staged — will be written when you run the script", style = MaterialTheme.typography.labelSmall, color = AccentBar.Emerald)
            denyReason != null ->
                Text("Write blocked: $denyReason", style = MaterialTheme.typography.labelSmall, color = AccentBar.Red)
        }
        inlineError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AccentBar.Red) }

        val effectiveOptions = enumOptions ?: (meta.valueKind as? ValueKind.ENUM)?.options ?: emptyList()
        val doWrite: (String) -> Unit = { value ->
            inlineError = null
            val writeAction = { val err = onWrite(id, value, "Advanced Tuning: ${meta.name}"); if (err != null) inlineError = err }
            if (meta.risk == Risk.HIGH || meta.risk == Risk.DANGEROUS) pendingConfirm = writeAction else writeAction()
        }

        when (val kind = meta.valueKind) {
            is ValueKind.BOOL -> {
                val on = currentValue.trim() == "1"
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall, color = if (isDisabled) Color(0xFF555555) else Color(0xFFCCCCCC))
                    Box(
                        modifier = Modifier
                            .background(if (on) AccentBar.Emerald.copy(alpha = 0.15f) else Color(0xFF0C0C10), RoundedCornerShape(4.dp))
                            .border(1.dp, if (on) AccentBar.Emerald.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .let { if (!isDisabled) it.clickable { doWrite(if (!on) "1" else "0") } else it }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            color = if (on) AccentBar.Emerald else Color(0xFF888888), letterSpacing = 0.06.sp)
                    }
                }
            }
            is ValueKind.ENUM -> {
                if (effectiveOptions.isNotEmpty()) {
                    GovernorDropdown(current = currentValue.trim(), options = effectiveOptions, enabled = !isDisabled, onSelect = { doWrite(it) })
                } else {
                    RawStringControl(current = currentValue, enabled = !isDisabled, onCommit = { doWrite(it) },
                        actionLabel = if (scriptBuilderMode && denyReason != null) "Stage" else "Apply")
                }
            }
            is ValueKind.INT_RANGE -> IntRangeControl(current = currentValue.trim().toIntOrNull() ?: kind.min, min = kind.min, max = kind.max, step = kind.step, unit = kind.unit, enabled = !isDisabled, onCommit = { doWrite(it.toString()) })
            is ValueKind.FREQ_KHZ -> FreqKhzControl(currentKhz = currentValue.trim().toIntOrNull() ?: kind.minKhz, minKhz = kind.minKhz, maxKhz = kind.maxKhz, enabled = !isDisabled, onCommit = { doWrite(it.toString()) })
            is ValueKind.RAW_STRING -> RawStringControl(current = currentValue, enabled = !isDisabled, onCommit = { doWrite(it) },
                actionLabel = if (scriptBuilderMode && denyReason != null) "Stage" else "Apply")
        }
    }

    pendingConfirm?.let { action ->
        DangerousConfirmDialog(meta = meta, scriptBuilderMode = scriptBuilderMode && denyReason != null,
            onConfirm = { action(); pendingConfirm = null }, onDismiss = { pendingConfirm = null })
    }
}

// =============================================================================
// Widget primitives
// =============================================================================

@Composable
private fun IntRangeControl(current: Int, min: Int, max: Int, step: Int, unit: String, enabled: Boolean, onCommit: (Int) -> Unit) {
    val steps = if (step > 1 && max > min) (max - min) / step else 0
    var position by remember(current) { mutableFloatStateOf(current.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${position.toInt()} $unit".trim(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color.White)
            Text("$min … $max $unit".trim(), style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        }
        Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { onCommit(position.toInt()) }, valueRange = min.toFloat()..max.toFloat(), steps = steps, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FreqKhzControl(currentKhz: Int, minKhz: Int, maxKhz: Int, enabled: Boolean, onCommit: (Int) -> Unit) {
    var position by remember(currentKhz) { mutableFloatStateOf(currentKhz.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${"%.1f".format(position / 1000f)} MHz", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color.White)
            Text("${"%.0f".format(minKhz / 1000f)} … ${"%.0f".format(maxKhz / 1000f)} MHz", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        }
        Slider(value = position, onValueChange = { position = it }, onValueChangeFinished = { onCommit(position.toInt()) }, valueRange = minKhz.toFloat()..maxKhz.toFloat(), enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RawStringControl(current: String, enabled: Boolean, onCommit: (String) -> Unit, actionLabel: String = "Apply") {
    var text by remember(current) { mutableStateOf(current) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.group), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
        ArsenalButton(label = actionLabel, onClick = { onCommit(text) },
            accent = AccentBar.Amber, style = ArsenalButtonStyle.Secondary, enabled = enabled && text.isNotBlank() && text != current)
    }
}

// =============================================================================
// Dangerous confirm dialog
// =============================================================================

@Composable
private fun DangerousConfirmDialog(meta: TunableMetadata.TunableInfo, onConfirm: () -> Unit, onDismiss: () -> Unit, scriptBuilderMode: Boolean = false) {
    val warningText = when {
        meta.name.contains("Throttling", ignoreCase = true) ->
            "Disabling thermal protection on a fanless device usually makes performance WORSE from heat buildup and risks thermal shutdown or hardware damage. Reverts on reboot."
        meta.name.contains("Thermal Zone", ignoreCase = true) ->
            "Disabling a thermal zone removes that temperature limit entirely. Device may overheat silently. Reverts on reboot — but heat damage before then is permanent."
        meta.name.contains("Trip Point", ignoreCase = true) ->
            "Raising a thermal trip point delays throttling. Hardware damage risk on repeated exposure. Reverts on reboot."
        meta.name.contains("Cooling Device", ignoreCase = true) ->
            "Setting cur_state to 0 removes the thermal cap on this cooling device. Reverts on reboot, but heat damage before then is permanent."
        meta.name.contains("CPU0", ignoreCase = true) ->
            "CPU0 is the boot processor. Taking it offline will crash the device immediately."
        else -> "This is a ${meta.risk.name} risk change: ${meta.description} Reverts on reboot."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = AccentBar.Red, modifier = Modifier.size(32.dp)) },
        title = { Text("${meta.risk.name} RISK — ${meta.name}", style = MaterialTheme.typography.titleMedium, color = AccentBar.Red) },
        text = { Text(warningText, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = AccentBar.Red, contentColor = Color.White)) {
                Text(if (scriptBuilderMode) "I understand — add to script" else "I understand — apply anyway")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Used by ClusterTierLabelTest — exported from TuneScreen package, referenced here for type correctness
// (actual clusterTierLabel is in TuneScreen.kt in the tune package)
private fun clusterTierLabel(thisPolicyMaxKhz: Int, allPolicyMaxKhz: List<Int>): String {
    if (allPolicyMaxKhz.size <= 1) return "efficiency"
    return when (thisPolicyMaxKhz) {
        allPolicyMaxKhz.first() -> "efficiency"
        allPolicyMaxKhz.last() -> if (allPolicyMaxKhz.size >= 3) "prime" else "big"
        else -> "big"
    }
}
