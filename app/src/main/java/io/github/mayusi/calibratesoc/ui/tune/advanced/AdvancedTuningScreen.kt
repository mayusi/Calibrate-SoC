package io.github.mayusi.calibratesoc.ui.tune.advanced

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.Risk
import io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.ValueKind
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.VoltageControl
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Advanced Tuning screen: exposes the extended kernel knob set discovered
 * by the foundation's probe layer. Every control is gated by three
 * independent safety checks:
 *
 *   1. CAPABILITY — only shown when the [CapabilityReport] confirms the
 *      sysfs node actually exists on this device (no phantom controls).
 *   2. PRIVILEGE  — when [Tunables.whyWriteDenied] returns non-null (e.g.
 *      no root), the control is greyed out with the honest reason.
 *   3. RISK       — HIGH/DANGEROUS knobs require an explicit confirm dialog
 *      with a blunt warning before any write is dispatched.
 *
 * All writes go through [AdvancedTuningViewModel.write] → [TunableWriter],
 * which snapshots before writing and reverts on next boot.
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

    val r = report
    if (r == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Probing device…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    // Script-builder mode: stock device (AYN_SETTINGS or NONE) AND no
    // sysfsDirectlyWritable. In this mode controls stage knobs instead of
    // writing directly. Even in script-builder mode, nodes that the unlock
    // script covered AND the unlock was run go live (isScriptBuilderMode
    // returns false for those).
    val globalScriptBuilderMode = r.privilege != PrivilegeTier.ROOT

    // Dangerous-section expander state (collapsed by default for safety).
    var dangerousExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        // ── Page header ───────────────────────────────────────────────────
        item {
            AdvancedHeader(
                report = r,
                onBack = onBack,
                scriptBuilderMode = globalScriptBuilderMode,
                stagedCount = pendingAdvanced.size,
                onGenerateScript = { viewModel.generateAdvancedScript() },
                onClearStaged = { viewModel.clearPendingAdvanced() },
            )
        }

        // ── Script deploy result ──────────────────────────────────────────
        lastDeploy?.let { deployed ->
            item {
                ScriptDeployedCard(deployed = deployed, onDismiss = viewModel::clearLastDeploy)
            }
        }

        // ── Last write result feedback (live-write mode) ──────────────────
        lastResult?.let { result ->
            item {
                WriteResultCard(result = result, onDismiss = viewModel::clearLastResult)
            }
        }

        // ── 1. CPU CORES ──────────────────────────────────────────────────
        item {
            CpuCoresSection(
                report = r,
                pending = pendingAdvanced,
                onWrite = { id, value, reason ->
                    if (viewModel.isScriptBuilderMode(id, r)) {
                        viewModel.stageAdvancedKnob(id, value)
                    } else {
                        viewModel.write(id, value, reason)
                    }
                },
                onStage = { id, value -> viewModel.stageAdvancedKnob(id, value) },
                onUnstage = { id -> viewModel.unstageAdvancedKnob(id) },
                scriptBuilderMode = globalScriptBuilderMode,
            )
        }

        // ── 2. CPU GOVERNOR TUNING ────────────────────────────────────────
        if (r.cpuGovernorTunables.isNotEmpty()) {
            item {
                CpuGovernorTunablesSection(
                    report = r,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) {
                            viewModel.stageAdvancedKnob(id, value)
                        } else {
                            viewModel.write(id, value, reason)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 3. GPU ADVANCED ───────────────────────────────────────────────
        r.gpu?.let { gpu ->
            item {
                GpuAdvancedSection(
                    report = r,
                    gpu = gpu,
                    adrenoExtras = r.adrenoExtras,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) {
                            viewModel.stageAdvancedKnob(id, value)
                        } else {
                            viewModel.write(id, value, reason)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 4. SCHEDULER BOOST ───────────────────────────────────────────
        // /dev/stune and /dev/cpuctl are cgroup paths — NOT scriptable from
        // app UID. Show read-only info with honest "Root only" label on stock.
        if (r.schedBoostInterface != SchedBoostInterface.NONE && r.schedBoostValues.isNotEmpty()) {
            item {
                SchedBoostSection(
                    report = r,
                    scriptBuilderMode = globalScriptBuilderMode,
                    onWrite = { id, value, reason ->
                        // cgroup paths are always root-only — write() will gate them
                        viewModel.write(id, value, reason)
                    },
                )
            }
        }

        // ── 5. INPUT BOOST ────────────────────────────────────────────────
        if (r.inputBoostPresent) {
            item {
                InputBoostSection(
                    report = r,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) {
                            viewModel.stageAdvancedKnob(id, value)
                        } else {
                            viewModel.write(id, value, reason)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 6. MEMORY / BUS (devfreq) ────────────────────────────────────
        if (r.devfreqDevices.isNotEmpty()) {
            item {
                MemoryBusSection(
                    report = r,
                    devices = r.devfreqDevices,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) {
                            viewModel.stageAdvancedKnob(id, value)
                        } else {
                            viewModel.write(id, value, reason)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 7. I/O SCHEDULER ─────────────────────────────────────────────
        if (r.blockDevices.isNotEmpty()) {
            item {
                IoSection(
                    report = r,
                    devices = r.blockDevices,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        if (viewModel.isScriptBuilderMode(id, r)) {
                            viewModel.stageAdvancedKnob(id, value)
                        } else {
                            viewModel.write(id, value, reason)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 8. VM / KERNEL ────────────────────────────────────────────────
        // /proc/sys writes cannot be done from app UID under SELinux.
        // These are SCRIPT-ONLY on stock (no live writes from app UID).
        r.vmSysctls?.let { vm ->
            item {
                VmKernelSection(
                    report = r,
                    vm = vm,
                    pending = pendingAdvanced,
                    onWrite = { id, value, reason ->
                        // /proc/sys = script-only on stock (SELinux blocks app UID)
                        if (r.privilege == PrivilegeTier.ROOT) {
                            viewModel.write(id, value, reason)
                        } else {
                            viewModel.stageAdvancedKnob(id, value)
                        }
                    },
                    scriptBuilderMode = globalScriptBuilderMode,
                )
            }
        }

        // ── 9. CUSTOM SYSFS RULE ─────────────────────────────────────────
        item {
            CustomSysfsSection(
                report = r,
                history = customHistory,
                pending = pendingAdvanced,
                scriptBuilderMode = globalScriptBuilderMode,
                onWrite = { path, value ->
                    viewModel.writeCustomRule(path, value)
                },
            )
        }

        // ── 10. VOLTAGE / UNDERVOLT HONESTY CARD ─────────────────────────
        item {
            VoltageHonestyCard()
        }

        // ── DANGEROUS section — thermal gating ───────────────────────────
        // Thermal is NEVER scriptable (safety). Shows current values
        // with an honest "Root only — thermal controls are never scriptable" label.
        if (r.thermalExtras.isNotEmpty() || r.coolingDevices.isNotEmpty()) {
            item {
                DangerousExpander(
                    expanded = dangerousExpanded,
                    onToggle = { dangerousExpanded = !dangerousExpanded },
                )
            }
            if (dangerousExpanded) {
                item {
                    ThermalDangerousSection(
                        report = r,
                        thermalExtras = r.thermalExtras,
                        coolingDevices = r.coolingDevices,
                        onWrite = { id, value, reason ->
                            viewModel.write(id, value, reason)
                        },
                    )
                }
            }
        }

        // ── Bottom Generate Script CTA ────────────────────────────────────
        if (globalScriptBuilderMode && pendingAdvanced.isNotEmpty()) {
            item {
                GenerateScriptCta(
                    stagedCount = pendingAdvanced.size,
                    onGenerate = { viewModel.generateAdvancedScript() },
                    onClear = { viewModel.clearPendingAdvanced() },
                )
            }
        }

        // ── Footer ───────────────────────────────────────────────────────
        item {
            RevertFooter(scriptBuilderMode = globalScriptBuilderMode)
        }
    }
}

// =============================================================================
// Header
// =============================================================================

@Composable
private fun AdvancedHeader(
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
                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Back")
            }
            Text(
                "Advanced Tuning",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            report.privilege == PrivilegeTier.ROOT -> {
                AlertCard(
                    type = AlertType.INFO,
                    title = "Root tier active — writes go live immediately",
                    message = "Every change is snapshotted before writing and reverts automatically on the next reboot.",
                )
            }
            scriptBuilderMode && !report.sysfsDirectlyWritable -> {
                // No unlock run. Show prominent script-builder explanation + unlock path.
                AlertCard(
                    type = AlertType.INFO,
                    title = "Script builder — configure knobs, then Generate Script",
                    message = "Nothing is written until you run the script via your device's Settings → Run script as Root. " +
                        "Controls are enabled — select values, then tap Generate Script to bundle them into a runnable .sh file.",
                )
                // Surface the one-time unlock card: makes CPU/GPU/I/O controls live-writable.
                UnlockBannerCard()
            }
            scriptBuilderMode && report.sysfsDirectlyWritable -> {
                // Unlock ran. CPU/GPU/I/O knobs are now live; only procfs + cgroups are script-only.
                AlertCard(
                    type = AlertType.INFO,
                    title = "Unlock active — CPU/GPU/I/O knobs write live",
                    message = "The one-time unlock script has been run. CPU, GPU, DDR, I/O, and input-boost controls " +
                        "write directly. Scheduler boost (cgroups) and VM sysctls are script-only — " +
                        "stage them and use Generate Script.",
                )
            }
            else -> {
                // SHIZUKU or other
                AlertCard(
                    type = AlertType.WARNING,
                    title = "Script builder — configure knobs, then Generate Script",
                    message = "Direct sysfs writes are not available on this tier. Configure knobs and Generate Script to apply.",
                )
            }
        }

        // Generate Script CTA at the top (visible when knobs are staged).
        if (scriptBuilderMode && stagedCount > 0) {
            GenerateScriptCta(
                stagedCount = stagedCount,
                onGenerate = onGenerateScript,
                onClear = onClearStaged,
            )
        }
    }
}

// =============================================================================
// Unlock banner (one-time prompt to run the unlock script)
// =============================================================================

@Composable
private fun UnlockBannerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.dense),
        ) {
            Text(
                "Run the one-time unlock to make CPU/GPU/I/O controls live-adjustable",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Without the unlock: controls work as a Script Builder — configure values, Generate Script, run it once. " +
                    "With the unlock: those same knobs write instantly without generating a script each time. " +
                    "Go to the Tune screen → Advanced unlock → Generate + Run script.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// =============================================================================
// Generate Script CTA
// =============================================================================

@Composable
private fun GenerateScriptCta(
    stagedCount: Int,
    onGenerate: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            Text(
                "$stagedCount knob${if (stagedCount != 1) "s" else ""} staged",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Generate a script with all staged knobs, then run it via your device's Settings → Run script as Root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Button(
                    onClick = onGenerate,
                    enabled = stagedCount > 0,
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Generate Script ($stagedCount)")
                }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

// =============================================================================
// Script deploy feedback card (replaces transient dialog in script-builder mode)
// =============================================================================

@Composable
private fun ScriptDeployedCard(
    deployed: AynScriptDeployer.Deployed,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertCard(
        type = AlertType.INFO,
        title = "Script generated",
        message = "Saved to: ${deployed.path}\n\n" +
            if (deployed.visibleToOdinPicker) {
                "Open your device Settings → Run script as Root → pick the .sh from the CalibrateSoC folder."
            } else {
                "The script is in the app-private folder — copy it to /sdcard/CalibrateSoC/ manually or grant storage permission."
            },
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                if (deployed.visibleToOdinPicker) {
                    TextButton(onClick = {
                        io.github.mayusi.calibratesoc.data.vendor.OdinIntents
                            .openVendorSettings(context)
                    }) { Text("Open Settings") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        },
    )
}

// =============================================================================
// Write result feedback card
// =============================================================================

@Composable
private fun WriteResultCard(result: WriteResult, onDismiss: () -> Unit) {
    val (alertType, title, message) = when (result) {
        is WriteResult.Success -> Triple(
            AlertType.INFO,
            "Write accepted",
            "${result.id.target.substringAfterLast("/")} = ${result.newValue}" +
                (result.previousValue?.let { " (was: $it)" } ?: ""),
        )
        is WriteResult.CapabilityDenied -> Triple(
            AlertType.WARNING,
            "Write blocked",
            result.reason,
        )
        is WriteResult.Rejected -> Triple(
            AlertType.ERROR,
            "Kernel rejected write",
            result.message + (result.errno?.let { " (errno $it)" } ?: ""),
        )
        is WriteResult.Failed -> Triple(
            AlertType.ERROR,
            "Write failed",
            result.error.message ?: result.error.javaClass.simpleName,
        )
    }
    AlertCard(
        type = alertType,
        title = title,
        message = message,
        action = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

// =============================================================================
// Risk badge
// =============================================================================

@Composable
private fun RiskBadge(risk: Risk) {
    val (label, color) = when (risk) {
        Risk.SAFE -> "SAFE" to MaterialTheme.colorScheme.onSurfaceVariant
        Risk.LOW -> "LOW" to MaterialTheme.colorScheme.onSurfaceVariant
        Risk.MEDIUM -> "MEDIUM" to Color(0xFFFFB300)
        Risk.HIGH -> "HIGH" to MaterialTheme.colorScheme.error
        Risk.DANGEROUS -> "DANGEROUS" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

// =============================================================================
// Generic tunable control — pick widget by ValueKind
// =============================================================================

/**
 * A single editable kernel knob. Renders the appropriate control based on
 * [TunableMetadata.ValueKind]. Validates before dispatching the write.
 * HIGH/DANGEROUS knobs require a confirm dialog first.
 *
 * @param id              The [TunableId] for this knob.
 * @param currentValue    String value currently in the kernel (from probe).
 * @param report          For privilege pre-flight.
 * @param onWrite         Callback returning an error string or null on success.
 *                        In script-builder mode this callback stages rather than writes.
 * @param pending         The current [pendingAdvanced] map — used to show "staged" chip.
 * @param scriptBuilderMode  If true the control is ENABLED even when [Tunables.whyWriteDenied]
 *                        is non-null: the user can select values to stage for script generation.
 * @param enumOptions     Override the options list for ENUM controls that need
 *                        live values from the probe (e.g. governors). Pass null
 *                        to use the options from [TunableMetadata].
 */
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
    // In script-builder mode, controls are ENABLED even without root.
    // They stage knobs into pendingAdvanced instead of writing directly.
    val isDisabled = if (scriptBuilderMode) false else denyReason != null
    val isStaged = id.target in pending

    // Confirmation pending: used for HIGH/DANGEROUS controls.
    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        // Header row: name + risk badge + staged chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                meta.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStaged) {
                    AssistChip(
                        onClick = {},
                        label = { Text("staged", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                RiskBadge(meta.risk)
            }
        }

        // Description
        Text(
            meta.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Privilege notice — honest context-sensitive label
        when {
            scriptBuilderMode && denyReason != null && !isStaged -> {
                Text(
                    "Will be included in generated script when staged",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            scriptBuilderMode && denyReason != null && isStaged -> {
                Text(
                    "Staged — will be written when you run the script",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            denyReason != null -> {
                Text(
                    "Write blocked: $denyReason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Inline validation error
        inlineError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── Control widget ────────────────────────────────────────────────
        val effectiveOptions = enumOptions ?: (meta.valueKind as? ValueKind.ENUM)?.options ?: emptyList()

        val doWrite: (String) -> Unit = { value ->
            inlineError = null
            val writeAction = {
                val err = onWrite(id, value, "Advanced Tuning: ${meta.name}")
                if (err != null) inlineError = err
            }
            if (meta.risk == Risk.HIGH || meta.risk == Risk.DANGEROUS) {
                pendingConfirm = writeAction
            } else {
                writeAction()
            }
        }

        when (val kind = meta.valueKind) {
            is ValueKind.BOOL -> {
                val on = currentValue.trim() == "1"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (on) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = on,
                        onCheckedChange = { doWrite(if (it) "1" else "0") },
                        enabled = !isDisabled,
                    )
                }
            }

            is ValueKind.ENUM -> {
                if (effectiveOptions.isNotEmpty()) {
                    EnumDropdown(
                        current = currentValue.trim(),
                        options = effectiveOptions,
                        enabled = !isDisabled,
                        onSelect = { doWrite(it) },
                    )
                } else {
                    // No options available (probe didn't enumerate them)
                    RawStringControl(
                        current = currentValue,
                        enabled = !isDisabled,
                        onCommit = { doWrite(it) },
                        actionLabel = if (scriptBuilderMode && denyReason != null) "Stage" else "Apply",
                    )
                }
            }

            is ValueKind.INT_RANGE -> {
                IntRangeControl(
                    current = currentValue.trim().toIntOrNull() ?: kind.min,
                    min = kind.min,
                    max = kind.max,
                    step = kind.step,
                    unit = kind.unit,
                    enabled = !isDisabled,
                    onCommit = { doWrite(it.toString()) },
                )
            }

            is ValueKind.FREQ_KHZ -> {
                FreqKhzControl(
                    currentKhz = currentValue.trim().toIntOrNull() ?: kind.minKhz,
                    minKhz = kind.minKhz,
                    maxKhz = kind.maxKhz,
                    enabled = !isDisabled,
                    onCommit = { doWrite(it.toString()) },
                )
            }

            is ValueKind.RAW_STRING -> {
                RawStringControl(
                    current = currentValue,
                    enabled = !isDisabled,
                    onCommit = { doWrite(it) },
                    actionLabel = if (scriptBuilderMode && denyReason != null) "Stage" else "Apply",
                )
            }
        }
    }

    // Confirm dialog for HIGH/DANGEROUS knobs.
    // In script-builder mode the dialog says "add to script" instead of "apply".
    pendingConfirm?.let { action ->
        DangerousConfirmDialog(
            meta = meta,
            scriptBuilderMode = scriptBuilderMode && denyReason != null,
            onConfirm = {
                action()
                pendingConfirm = null
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

// =============================================================================
// Widget primitives
// =============================================================================

@Composable
private fun EnumDropdown(
    current: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = {
                IconButton(onClick = { if (enabled) expanded = true }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = "Expand",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun IntRangeControl(
    current: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    val steps = if (step > 1 && max > min) (max - min) / step else 0
    var position by remember(current) { mutableFloatStateOf(current.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${position.toInt()} $unit".trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "$min … $max $unit".trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onCommit(position.toInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FreqKhzControl(
    currentKhz: Int,
    minKhz: Int,
    maxKhz: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var position by remember(currentKhz) { mutableFloatStateOf(currentKhz.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${"%.1f".format(position / 1000f)} MHz",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${"%.0f".format(minKhz / 1000f)} … ${"%.0f".format(maxKhz / 1000f)} MHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onCommit(position.toInt()) },
            valueRange = minKhz.toFloat()..maxKhz.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RawStringControl(
    current: String,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    actionLabel: String = "Apply",
) {
    var text by remember(current) { mutableStateOf(current) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onCommit(text) },
            enabled = enabled && text.isNotBlank() && text != current,
        ) {
            Text(actionLabel)
        }
    }
}

// =============================================================================
// Dangerous-confirm dialog
// =============================================================================

@Composable
private fun DangerousConfirmDialog(
    meta: TunableMetadata.TunableInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    scriptBuilderMode: Boolean = false,
) {
    val warningText = when {
        meta.name.contains("Throttling", ignoreCase = true) ->
            "Disabling thermal protection on a fanless device usually makes performance WORSE " +
                "from heat buildup and risks thermal shutdown or hardware damage. " +
                "Only do this for short benchmarks in a controlled environment. Reverts on reboot."
        meta.name.contains("Thermal Zone", ignoreCase = true) ->
            "Disabling a thermal zone removes that temperature limit entirely. " +
                "The device may overheat silently with no throttle applied. " +
                "Reverts on reboot — but heat damage before then is permanent."
        meta.name.contains("Trip Point", ignoreCase = true) ->
            "Raising a thermal trip point delays throttling. An excessively high value " +
                "means the SoC can sustain dangerous temperatures before the kernel reacts. " +
                "Hardware damage risk on repeated exposure. Reverts on reboot."
        meta.name.contains("Cooling Device", ignoreCase = true) ->
            "Setting cur_state to 0 removes the thermal cap on this cooling device. " +
                "The affected component can run uncapped under thermal load. " +
                "Reverts on reboot, but heat damage before then is permanent."
        meta.name.contains("CPU0", ignoreCase = true) ->
            "CPU0 is the boot processor. Taking it offline will crash the device immediately."
        else ->
            "This is a ${meta.risk.name} risk change: ${meta.description} Reverts on reboot."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                "${meta.risk.name} RISK — ${meta.name}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Text(warningText, style = MaterialTheme.typography.bodySmall)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(if (scriptBuilderMode) "I understand — add to script" else "I understand — apply anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// =============================================================================
// Expandable section card helper
// =============================================================================

@Composable
private fun ExpandableSectionCard(
    title: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(Spacing.card), verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            if (expanded) {
                content()
            }
        }
    }
}

// =============================================================================
// Section: CPU Cores
// =============================================================================

@Composable
private fun CpuCoresSection(
    report: CapabilityReport,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    onStage: (TunableId, String) -> String?,
    onUnstage: (TunableId) -> Unit,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("CPU Cores", Icons.Outlined.Speed) {
        Text(
            "Toggle individual cores online/offline. CPU0 cannot be offlined — it is the boot processor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val maxCore = report.cpuPolicies.flatMap { it.onlineCores }.maxOrNull() ?: 0

        for (coreIdx in 0..maxCore) {
            val cluster = report.cpuPolicies.firstOrNull { coreIdx in it.onlineCores }
            val clusterLabel = cluster?.let { "Cluster (policy${it.policyId})" } ?: ""
            val id = KernelTunables.cpuOnline(coreIdx)
            val meta = TunableMetadata.forId(id)
            val isCpu0 = coreIdx == 0
            val denyReason = Tunables.whyWriteDenied(id, report)
            val isStaged = id.target in pending

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                    ) {
                        Text(
                            "CPU$coreIdx",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (clusterLabel.isNotBlank()) {
                            Text(
                                clusterLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RiskBadge(meta.risk)
                        if (isStaged) {
                            AssistChip(
                                onClick = {},
                                label = { Text("staged", style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                    if (isCpu0) {
                        Text(
                            "Boot processor — cannot be offlined",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (scriptBuilderMode && denyReason != null && !isStaged) {
                        Text(
                            "Will be included in generated script when staged",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(
                    checked = true, // All listed cores are online per probe
                    onCheckedChange = { wantOn ->
                        if (!isCpu0) {
                            onWrite(id, if (wantOn) "1" else "0", "CPU$coreIdx online")
                        }
                    },
                    enabled = !isCpu0 && (scriptBuilderMode || denyReason == null),
                )
            }
        }
    }
}

// =============================================================================
// Section: CPU Governor Tunables
// =============================================================================

@Composable
private fun CpuGovernorTunablesSection(
    report: CapabilityReport,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("CPU Governor Tuning", Icons.Outlined.Settings) {
        Text(
            "Per-policy governor tunables discovered dynamically from the kernel. " +
                "Values shown are from the last capability probe — they may not reflect live changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        report.cpuGovernorTunables.forEachIndexed { i, probe ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text(
                "policy${probe.policyId} — ${probe.governor}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.group),
            )

            probe.tunables.entries.sortedBy { it.key }.forEach { (name, currentValue) ->
                val id = KernelTunables.cpuGovernorTunable(probe.policyId, probe.governor, name)
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                TunableControl(
                    id = id,
                    currentValue = currentValue,
                    report = report,
                    onWrite = onWrite,
                    pending = pending,
                    scriptBuilderMode = scriptBuilderMode,
                )
            }
        }
    }
}

// =============================================================================
// Section: GPU Advanced
// =============================================================================

@Composable
private fun GpuAdvancedSection(
    report: CapabilityReport,
    gpu: GpuProbe,
    adrenoExtras: AdrenoExtrasProbe?,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("GPU Advanced", Icons.Outlined.Bolt) {
        Text(
            "Adreno power levels (0 = fastest). Lower index = more GPU performance + power draw.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Power level controls (when Adreno extras present)
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

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text("Power Levels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            // Min power level
            val minId = KernelTunables.adrenoMinPowerLevel(gpu.rootPath)
            val minMeta = TunableMetadata.forId(minId)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(minMeta.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    RiskBadge(minMeta.risk)
                }
                Text(minMeta.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Current: ${levelLabel(adrenoExtras.currentMinPwrLevel)}", style = MaterialTheme.typography.labelSmall)
                if (numLevels > 1) {
                    IntRangeControl(
                        current = adrenoExtras.currentMinPwrLevel ?: 0,
                        min = 0,
                        max = numLevels - 1,
                        step = 1,
                        unit = "(lower=faster)",
                        enabled = scriptBuilderMode || Tunables.whyWriteDenied(minId, report) == null,
                        onCommit = { onWrite(minId, it.toString(), "GPU min power level") },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))

            // Max power level
            val maxId = KernelTunables.adrenoMaxPowerLevel(gpu.rootPath)
            val maxMeta = TunableMetadata.forId(maxId)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(maxMeta.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    RiskBadge(maxMeta.risk)
                }
                Text(maxMeta.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Current: ${levelLabel(adrenoExtras.currentMaxPwrLevel)}", style = MaterialTheme.typography.labelSmall)
                if (numLevels > 1) {
                    IntRangeControl(
                        current = adrenoExtras.currentMaxPwrLevel ?: 0,
                        min = 0,
                        max = numLevels - 1,
                        step = 1,
                        unit = "(lower=faster)",
                        enabled = scriptBuilderMode || Tunables.whyWriteDenied(maxId, report) == null,
                        onCommit = { onWrite(maxId, it.toString(), "GPU max power level") },
                    )
                }
            }

            // Default power level
            val defaultId = KernelTunables.adrenoDefaultPowerLevel(gpu.rootPath)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = defaultId,
                currentValue = adrenoExtras.currentDefaultPwrLevel?.toString() ?: "—",
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // GPU throttling — HIGH risk, confirm-gated via TunableControl
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            val throttleId = KernelTunables.gpuThrottling(gpu.rootPath)
            TunableControl(
                id = throttleId,
                currentValue = if (adrenoExtras.throttlingEnabled == true) "1" else "0",
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // Force clocks on
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            val forceClkId = KernelTunables.gpuForceClkOn(gpu.rootPath)
            TunableControl(
                id = forceClkId,
                currentValue = if (adrenoExtras.forceClkOn == true) "1" else "0",
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // Idle timer
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            val idleTimerId = KernelTunables.gpuIdleTimer(gpu.rootPath)
            TunableControl(
                id = idleTimerId,
                currentValue = adrenoExtras.idleTimerMs?.toString() ?: "0",
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }

        // GPU governor picker (allow change)
        if (gpu.availableGovernors.size > 1) {
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            val govId = Tunables.gpuGovernor(gpu.rootPath)
            val govMeta = TunableMetadata.forId(govId)
            val govDenyReason = Tunables.whyWriteDenied(govId, report)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(govMeta.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    RiskBadge(govMeta.risk)
                }
                Text(govMeta.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EnumDropdown(
                    current = gpu.currentGovernor,
                    options = gpu.availableGovernors,
                    enabled = scriptBuilderMode || govDenyReason == null,
                    onSelect = { onWrite(govId, it, "GPU governor") },
                )
            }
        }

        // GPU devfreq governor tunables
        if (report.gpuGovernorTunables.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text(
                "GPU Governor Tunables",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            report.gpuGovernorTunables.forEach { probe ->
                val id = KernelTunables.gpuDevfreqGovernorTunable(gpu.rootPath, probe.governor, probe.name)
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                TunableControl(
                    id = id,
                    currentValue = probe.currentValue,
                    report = report,
                    onWrite = onWrite,
                    pending = pending,
                    scriptBuilderMode = scriptBuilderMode,
                )
            }
        }
    }
}

// =============================================================================
// Section: Scheduler Boost
// Root-only: /dev/stune and /dev/cpuctl are cgroup paths — not scriptable.
// On stock, show current values with honest "Root only" label.
// =============================================================================

@Composable
private fun SchedBoostSection(
    report: CapabilityReport,
    scriptBuilderMode: Boolean,
    onWrite: (TunableId, String, String) -> String?,
) {
    val iface = report.schedBoostInterface
    val ifaceLabel = when (iface) {
        SchedBoostInterface.STUNE -> "SchedTune (/dev/stune)"
        SchedBoostInterface.UCLAMP -> "uclamp (/dev/cpuctl)"
        SchedBoostInterface.NONE -> "None"
    }

    ExpandableSectionCard("Scheduler Boost ($ifaceLabel)", Icons.Outlined.Tune) {
        // On stock: cgroup paths can't be written from app UID (SELinux blocks it)
        // and can't be included in a generated script (cgroup writes unverified).
        // Show current values as read-only info with an honest label.
        if (scriptBuilderMode) {
            AlertCard(
                type = AlertType.WARNING,
                title = "Root only — can't be applied via script on this device",
                message = "Scheduler boost (schedtune/uclamp) controls cgroup hierarchies. " +
                    "Writing cgroup files from app UID is blocked by SELinux on stock firmware. " +
                    "These values are shown read-only. To change them, root the device (Magisk/KernelSU).",
            )
        }

        Text(
            when (iface) {
                SchedBoostInterface.STUNE ->
                    "schedtune.boost (0–100) biases tasks in this cgroup toward faster CPUs. " +
                        "prefer_idle (0/1) prefers idle CPUs. Older kernels."
                SchedBoostInterface.UCLAMP ->
                    "cpu.uclamp.min (0–1024) raises the scheduler's minimum utilisation estimate. " +
                        "cpu.uclamp.max (0–1024) caps it. Newer kernels (5.x+)."
                SchedBoostInterface.NONE -> "No cgroup boost interface detected."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        report.schedBoostValues.filter { it.slice in listOf("top-app", "foreground") }
            .forEachIndexed { i, probe ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
                Text(
                    "Slice: ${probe.slice}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.group),
                )

                when (iface) {
                    SchedBoostInterface.STUNE -> {
                        // On stock: show as read-only current values only
                        if (scriptBuilderMode) {
                            Text(
                                "Current boost: ${probe.boostOrUclampMin ?: 0} | prefer_idle: ${probe.preferIdleOrUclampMax ?: 0}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            val boostId = KernelTunables.schedtuneBoost(probe.slice)
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                            TunableControl(id = boostId, currentValue = probe.boostOrUclampMin?.toString() ?: "0", report = report, onWrite = onWrite)
                            val idleId = KernelTunables.schedtunePreferIdle(probe.slice)
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                            TunableControl(id = idleId, currentValue = probe.preferIdleOrUclampMax?.toString() ?: "0", report = report, onWrite = onWrite)
                        }
                    }
                    SchedBoostInterface.UCLAMP -> {
                        if (scriptBuilderMode) {
                            Text(
                                "Current uclamp.min: ${probe.boostOrUclampMin ?: 0} | uclamp.max: ${probe.preferIdleOrUclampMax ?: 1024}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        } else {
                            val minId = KernelTunables.uclampMin(probe.slice)
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                            TunableControl(id = minId, currentValue = probe.boostOrUclampMin?.toString() ?: "0", report = report, onWrite = onWrite)
                            val maxId = KernelTunables.uclampMax(probe.slice)
                            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                            TunableControl(id = maxId, currentValue = probe.preferIdleOrUclampMax?.toString() ?: "1024", report = report, onWrite = onWrite)
                        }
                    }
                    SchedBoostInterface.NONE -> Unit
                }
            }
    }
}

// =============================================================================
// Section: Input Boost
// =============================================================================

@Composable
private fun InputBoostSection(
    report: CapabilityReport,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("Input Boost", Icons.Outlined.TouchApp) {
        Text(
            "CPU frequency boost triggered on touch/key input events to reduce input latency. " +
                "Values revert on reboot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val boost = report.inputBoost

        val freqId = KernelTunables.inputBoostFreq()
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
        TunableControl(
            id = freqId,
            currentValue = boost?.inputBoostFreqRaw ?: "0",
            report = report,
            onWrite = onWrite,
            pending = pending,
            scriptBuilderMode = scriptBuilderMode,
        )

        val msId = KernelTunables.inputBoostMs()
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(
            id = msId,
            currentValue = boost?.inputBoostMs?.toString() ?: "0",
            report = report,
            onWrite = onWrite,
            pending = pending,
            scriptBuilderMode = scriptBuilderMode,
        )
    }
}

// =============================================================================
// Section: Memory / Bus
// =============================================================================

@Composable
private fun MemoryBusSection(
    report: CapabilityReport,
    devices: List<DevfreqDeviceProbe>,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("Memory / Bus (devfreq)", Icons.Outlined.Memory) {
        Text(
            "DDR and bus frequency scaling governors and frequency floors/ceilings per devfreq device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        devices.forEachIndexed { i, dev ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text(
                dev.deviceName,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.group),
            )
            Text(
                "Current: ${"%.0f".format(dev.curFreqHz / 1_000_000f)} MHz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Governor
            if (dev.availableGovernors.size > 1) {
                val govId = KernelTunables.devfreqGovernor(dev.deviceName)
                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                TunableControl(
                    id = govId,
                    currentValue = dev.currentGovernor,
                    report = report,
                    onWrite = onWrite,
                    enumOptions = dev.availableGovernors,
                    pending = pending,
                    scriptBuilderMode = scriptBuilderMode,
                )
            }

            // Min freq
            val minId = KernelTunables.devfreqMinFreq(dev.deviceName)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = minId,
                currentValue = dev.minFreqHz.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // Max freq
            val maxId = KernelTunables.devfreqMaxFreq(dev.deviceName)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = maxId,
                currentValue = dev.maxFreqHz.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }
    }
}

// =============================================================================
// Section: I/O
// =============================================================================

@Composable
private fun IoSection(
    report: CapabilityReport,
    devices: List<BlockDeviceProbe>,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("I/O", Icons.Outlined.Storage) {
        Text(
            "Block device I/O scheduler, read-ahead buffer, and queue depth per block device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        devices.forEachIndexed { i, dev ->
            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text(
                "/dev/block/${dev.deviceName}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = Spacing.group),
            )

            // Scheduler
            val schedId = KernelTunables.ioScheduler(dev.deviceName)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = schedId,
                currentValue = dev.currentScheduler,
                report = report,
                onWrite = onWrite,
                enumOptions = dev.availableSchedulers.ifEmpty { null },
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // Read ahead
            val readAheadId = KernelTunables.ioReadAheadKb(dev.deviceName)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = readAheadId,
                currentValue = dev.readAheadKb.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )

            // NR requests
            val nrReqId = KernelTunables.ioNrRequests(dev.deviceName)
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = nrReqId,
                currentValue = dev.nrRequests.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }
    }
}

// =============================================================================
// Section: VM / Kernel
// =============================================================================

@Composable
private fun VmKernelSection(
    report: CapabilityReport,
    vm: VmSysctlsProbe,
    pending: Map<String, String>,
    onWrite: (TunableId, String, String) -> String?,
    scriptBuilderMode: Boolean,
) {
    ExpandableSectionCard("VM / Kernel Sysctls", Icons.Outlined.Settings) {
        Text(
            "Virtual memory and kernel scheduler tuning via /proc/sys. Changes revert on reboot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // On stock: /proc/sys writes fail under SELinux from app UID, but are
        // included in the generated script (run as root via device Settings).
        if (scriptBuilderMode) {
            Text(
                "Script-builder: these procfs knobs go into the generated script and apply when you run it as Root.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Swappiness
        vm.swappiness?.let { current ->
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            TunableControl(
                id = KernelTunables.vmSwappiness(),
                currentValue = current.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }

        // VFS cache pressure
        vm.vfsCachePressure?.let { current ->
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = KernelTunables.vmVfsCachePressure(),
                currentValue = current.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }

        // Dirty ratio
        vm.dirtyRatio?.let { current ->
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = KernelTunables.vmDirtyRatio(),
                currentValue = current.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }

        // Dirty background ratio
        vm.dirtyBackgroundRatio?.let { current ->
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
            TunableControl(
                id = KernelTunables.vmDirtyBackgroundRatio(),
                currentValue = current.toString(),
                report = report,
                onWrite = onWrite,
                pending = pending,
                scriptBuilderMode = scriptBuilderMode,
            )
        }

        // Kernel sched sysctls (lower priority)
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
        Text(
            "Kernel Scheduler",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(
            id = KernelTunables.schedMigrationCostNs(),
            currentValue = "",
            report = report,
            onWrite = onWrite,
            pending = pending,
            scriptBuilderMode = scriptBuilderMode,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(
            id = KernelTunables.schedMinGranularityNs(),
            currentValue = "",
            report = report,
            onWrite = onWrite,
            pending = pending,
            scriptBuilderMode = scriptBuilderMode,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
        TunableControl(
            id = KernelTunables.schedWakeupGranularityNs(),
            currentValue = "",
            report = report,
            onWrite = onWrite,
            pending = pending,
            scriptBuilderMode = scriptBuilderMode,
        )
    }
}

// =============================================================================
// Section: Custom Sysfs Rule
// =============================================================================

@Composable
private fun CustomSysfsSection(
    report: CapabilityReport,
    history: List<AdvancedTuningViewModel.CustomSysfsRule>,
    pending: Map<String, String>,
    scriptBuilderMode: Boolean,
    onWrite: (String, String) -> String?,
) {
    var path by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ExpandableSectionCard("Custom Sysfs Rule", Icons.Outlined.Code) {
        AlertCard(
            type = AlertType.WARNING,
            title = if (scriptBuilderMode) "Power user — staged into script" else "Power user — your risk",
            message = if (scriptBuilderMode) {
                "Write ANY /sys or /proc path with any value. On this device the rule is STAGED " +
                    "into the generated script rather than written live. No validation beyond path safety."
            } else {
                "Write ANY /sys or /proc path with any value. No validation beyond path safety. " +
                    "Wrong values can destabilise or crash the device. Everything reverts on reboot."
            },
        )

        Spacer(Modifier.height(Spacing.group))

        OutlinedTextField(
            value = path,
            onValueChange = { path = it; error = null },
            label = { Text("/sys/… or /proc/…") },
            placeholder = { Text("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        OutlinedTextField(
            value = value,
            onValueChange = { value = it; error = null },
            label = { Text("Value") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // In script-builder mode the Apply button is ALWAYS enabled (writeCustomRule stages it).
        // In live mode, show deny reason and gate the button on it.
        val denyReason = if (!scriptBuilderMode && path.isNotBlank()) {
            val id = try {
                KernelTunables.customSysfsRule(path)
            } catch (_: IllegalArgumentException) {
                null
            }
            id?.let { Tunables.whyWriteDenied(it, report) }
        } else null

        if (denyReason != null) {
            Text(
                "Write blocked: $denyReason",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Show staged indicator for the current path
        val isStagedHere = pending.containsKey(path.trim())
        if (scriptBuilderMode && isStagedHere) {
            Text(
                "Staged — will be included in Generate Script",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Button(
            onClick = {
                error = null
                val err = onWrite(path.trim(), value.trim())
                if (err != null) error = err
            },
            enabled = path.isNotBlank() && value.isNotBlank() && (scriptBuilderMode || denyReason == null),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (scriptBuilderMode) "Stage rule" else "Apply custom rule")
        }

        // History — re-apply or re-stage previously used rules
        if (history.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
            Text(
                if (scriptBuilderMode) "Previously staged this session:" else "Previously applied this session:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            history.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.dense),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text("= ${rule.value}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = {
                            path = rule.path
                            value = rule.value
                        },
                    ) { Text("Load") }
                    TextButton(
                        onClick = {
                            error = null
                            val err = onWrite(rule.path, rule.value)
                            if (err != null) error = err
                        },
                        enabled = scriptBuilderMode || denyReason == null,
                    ) { Text(if (scriptBuilderMode) "Re-stage" else "Re-apply") }
                }
            }
        }
    }
}

// =============================================================================
// Voltage Honesty Card
// =============================================================================

@Composable
private fun VoltageHonestyCard() {
    SectionCard("Voltage / Undervolt", icon = Icons.Outlined.BatteryAlert) {
        AlertCard(
            type = AlertType.INFO,
            title = "Not available on this device",
            message = VoltageControl.unavailableSummary,
        )
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "CPU: ${VoltageControl.cpuVoltageUnavailableExplanation}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "GPU: ${VoltageControl.gpuVoltageUnavailableExplanation}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =============================================================================
// Dangerous section expander
// =============================================================================

@Composable
private fun DangerousExpander(expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.card),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        "Thermal / Dangerous controls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Trip points, zone modes, cooling states — can damage hardware",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand dangerous section",
                )
            }
        }
    }
}

// =============================================================================
// Section: Thermal (DANGEROUS — behind expander)
// =============================================================================

@Composable
private fun ThermalDangerousSection(
    report: CapabilityReport,
    thermalExtras: List<ThermalZoneExtras>,
    coolingDevices: List<CoolingDeviceProbe>,
    onWrite: (TunableId, String, String) -> String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        AlertCard(
            type = AlertType.ERROR,
            title = "Thermal controls — real damage risk",
            message = "Disabling thermal protection on a fanless device usually makes performance WORSE " +
                "from heat buildup, and risks thermal shutdown or permanent SoC damage. " +
                "Every change requires confirmation. Everything reverts on reboot, but heat damage before then is permanent.",
        )

        // Thermal zone modes + trip points
        if (thermalExtras.isNotEmpty()) {
            ExpandableSectionCard(
                "Thermal Zones",
                Icons.Outlined.DeviceThermostat,
                initiallyExpanded = false,
            ) {
                thermalExtras.forEachIndexed { i, zone ->
                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
                    Text(
                        "thermal_zone${zone.zoneId}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Spacing.group),
                    )

                    // Zone mode
                    zone.mode?.let { mode ->
                        val modeId = KernelTunables.thermalZoneMode(zone.zoneId)
                        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                        TunableControl(
                            id = modeId,
                            currentValue = mode,
                            report = report,
                            onWrite = onWrite,
                        )
                    }

                    // Trip points
                    zone.tripPoints.forEachIndexed { ti, trip ->
                        val tripId = KernelTunables.thermalTripPointTemp(zone.zoneId, trip.index)
                        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                            Text(
                                "Trip ${trip.index} — ${trip.type} (${trip.tempMilliC / 1000}°C)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TunableControl(
                                id = tripId,
                                currentValue = trip.tempMilliC.toString(),
                                report = report,
                                onWrite = onWrite,
                            )
                        }
                    }
                }
            }
        }

        // Cooling devices
        if (coolingDevices.isNotEmpty()) {
            ExpandableSectionCard(
                "Cooling Devices",
                Icons.Outlined.DeviceThermostat,
                initiallyExpanded = false,
            ) {
                coolingDevices.forEachIndexed { i, dev ->
                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.group))
                    Text(
                        "cooling_device${dev.id} — ${dev.type}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Spacing.group),
                    )
                    Text(
                        "Max state: ${dev.maxState} | Current: ${dev.currentState}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val stateId = KernelTunables.coolingDeviceCurState(dev.id)
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.dense))
                    TunableControl(
                        id = stateId,
                        currentValue = dev.currentState.toString(),
                        report = report,
                        onWrite = onWrite,
                    )
                }
            }
        }
    }
}

// =============================================================================
// Footer
// =============================================================================

@Composable
private fun RevertFooter(scriptBuilderMode: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.group),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.dense),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (scriptBuilderMode) {
                "Staged knobs are bundled into a shell script. Run it via your device's Settings → Run script as Root. " +
                    "All kernel writes revert on reboot — nothing persists permanently without a boot script."
            } else {
                "All writes above are snapshotted before application and revert automatically on the next reboot. " +
                    "Nothing here persists permanently unless you install a boot script via the Tune screen."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
