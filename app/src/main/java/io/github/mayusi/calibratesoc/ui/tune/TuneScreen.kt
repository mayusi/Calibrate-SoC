package io.github.mayusi.calibratesoc.ui.tune

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
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune as TuneIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.presets.Preset
import io.github.mayusi.calibratesoc.data.presets.VerificationTier
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.PanelAccentEdge
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatBar
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Tune screen — Direction-C Arsenal restyle.
 *
 * The preset list is the primary surface here. Each preset = an
 * [ArsenalPanel] with accent-color-coded cluster [StatBar]s, a
 * [StatusPill] for the verification tier, and [ArsenalButton]s for
 * Apply (Red primary) / Script / Boot (secondary). Policy sliders are
 * framed in [ArsenalPanel] with Blue CPU accent. GPU is Purple.
 * Advanced / AutoTDP navigation buttons keep the same behavior.
 *
 * Device-scoping: the preset list is rendered from [TuneViewModel.presets]
 * which is already device-scoped by the repository layer. No RP6 presets
 * appear on Odin 3 and vice-versa — the manifest-v3 scoping is upstream
 * of this composable.
 */
@Composable
fun TuneScreen(
    onOpenHistory: () -> Unit = {},
    onOpenAdvancedTuning: () -> Unit = {},
    onOpenAutoTdp: () -> Unit = {},
    viewModel: TuneViewModel = hiltViewModel(),
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val results by viewModel.lastResults.collectAsStateWithLifecycle()
    val gpuPending by viewModel.gpuPending.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val ocAck by viewModel.oneTimeOcAcknowledged.collectAsStateWithLifecycle()
    val adapter by viewModel.adapter.collectAsStateWithLifecycle()
    val lastDeploy by viewModel.lastDeploy.collectAsStateWithLifecycle()
    val lastDeployPreset by viewModel.lastDeployPreset.collectAsStateWithLifecycle()
    val verifyResult by viewModel.verifyResult.collectAsStateWithLifecycle()
    val lastBootDeploy by viewModel.lastBootDeploy.collectAsStateWithLifecycle()
    val latestTelemetry by viewModel.latestTelemetry.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingFirstOcConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingUnknownDeviceConfirm by remember { mutableStateOf<Pair<Preset, () -> Unit>?>(null) }
    var showSaveProfileDialog by remember { mutableStateOf(false) }

    val report = capability
    if (report == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "PROBING DEVICE…",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBar.Neutral,
                letterSpacing = 0.08.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        return
    }

    val canWriteSysfs = report.privilege == PrivilegeTier.ROOT
    val hasVendorControls = adapter?.vendorAppPackage?.startsWith("com.odin") == true ||
        adapter?.vendorAppPackage?.startsWith("com.ayn") == true ||
        adapter?.vendorAppPackage?.startsWith("com.rp") == true ||
        adapter?.fanAdapter?.supportsCurve == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item { TuneHeader(report, onOpenHistory = onOpenHistory) }
        item { DisplayRefreshCard() }
        item { AdvancedUnlockCard() }

        adapter?.takeIf { hasVendorControls }?.let { ad ->
            item {
                VendorCard(
                    adapter = ad,
                    onOpenFanCurve = {
                        runCatching { OdinIntents.openFanCurveEditor(context) }
                    },
                )
            }
        }

        // Device-scoped preset list — the ViewModel already filters by device;
        // RP6 presets do NOT appear on Odin 3 and vice-versa.
        if (presets.isNotEmpty()) {
            item {
                PresetsCard(
                    presets = presets,
                    canApply = canWriteSysfs,
                    canGenerateScript = true,
                ) { preset, action ->
                    when (action) {
                        PresetAction.APPLY -> {
                            val apply = { viewModel.applyPreset(preset) }
                            val gated: () -> Unit = if (preset.verification == VerificationTier.GENERIC_UNKNOWN_FAMILY) {
                                { pendingUnknownDeviceConfirm = preset to apply }
                            } else apply
                            if (!ocAck) pendingFirstOcConfirm = gated else gated()
                        }
                        PresetAction.GENERATE_SCRIPT -> viewModel.generateAynScript(preset)
                        PresetAction.GENERATE_SCRIPT_WITH_REMINDER -> viewModel.generateScriptWithReminder(preset)
                        PresetAction.INSTALL_AT_BOOT -> viewModel.installScriptAsBootService(preset)
                    }
                }
            }
        }

        lastDeploy?.let { deployed ->
            item {
                LastScriptCard(
                    deployed = deployed,
                    preset = lastDeployPreset,
                    verifyResult = verifyResult,
                    onVerify = { lastDeployPreset?.let { viewModel.verifyApplied(it) } },
                    onDismiss = {
                        viewModel.clearLastDeploy()
                        viewModel.clearVerifyResult()
                    },
                )
            }
        }

        // Pre-compute sorted distinct max-freqs for cluster-tier ranking
        val allPolicyMaxKhz = report.cpuPolicies
            .map { it.availableFreqsKhz.lastOrNull() ?: it.currentMaxKhz }
            .distinct()
            .sorted()

        items(report.cpuPolicies) { policy ->
            PolicyCard(
                policy = policy,
                edit = pending[policy.policyId],
                liveCoreFreqsKhz = latestTelemetry?.perCoreCpuFreqKhz,
                allPolicyMaxKhz = allPolicyMaxKhz,
                onChange = { viewModel.setEdit(policy.policyId, it) },
            )
        }

        report.gpu?.let { gpu ->
            item {
                GpuCard(
                    gpu = gpu,
                    presets = presets,
                    edit = gpuPending,
                    onChange = { viewModel.setGpuEdit(it) },
                )
            }
        }

        item {
            val gpuPendingCount = gpuPending?.let {
                (if (it.minHz != null) 1 else 0) +
                    (if (it.maxHz != null) 1 else 0) +
                    (if (it.powerLevel != null) 1 else 0)
            } ?: 0
            ApplyBar(
                pendingCount = pending.values.count { it.minKhz != null || it.maxKhz != null || it.governor != null } + gpuPendingCount,
                canApply = canWriteSysfs,
                onApply = {
                    val apply = { viewModel.apply() }
                    if (!ocAck) pendingFirstOcConfirm = apply else apply()
                },
                onClear = viewModel::clearPending,
            )
        }

        // Advanced tuning entry point
        item {
            ArsenalButton(
                label = "Advanced Tuning →",
                onClick = onOpenAdvancedTuning,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // AutoTDP entry point
        item {
            ArsenalButton(
                label = "AutoTDP — Dynamic Power Management →",
                onClick = onOpenAutoTdp,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Emerald,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Save as profile
        item {
            ArsenalButton(
                label = "Save As Profile",
                onClick = { showSaveProfileDialog = true },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (results.isNotEmpty()) item { ResultsCard(results) }
    }

    if (showSaveProfileDialog) {
        SaveProfileDialog(
            onSave = { name, description, applyOnBoot ->
                viewModel.saveAsProfile(name, description, applyOnBoot)
                showSaveProfileDialog = false
            },
            onDismiss = { showSaveProfileDialog = false },
        )
    }

    if (pendingFirstOcConfirm != null) {
        FirstOcConfirmDialog(
            onConfirm = {
                viewModel.acknowledgeOc()
                pendingFirstOcConfirm?.invoke()
                pendingFirstOcConfirm = null
            },
            onDismiss = { pendingFirstOcConfirm = null },
        )
    }

    pendingUnknownDeviceConfirm?.let { (preset, apply) ->
        UnknownDeviceConfirmDialog(
            preset = preset,
            onConfirm = {
                apply()
                pendingUnknownDeviceConfirm = null
            },
            onDismiss = { pendingUnknownDeviceConfirm = null },
        )
    }

    lastDeploy?.let { deployed ->
        ScriptDeployedDialog(
            deployed = deployed,
            preset = lastDeployPreset,
            verifyResult = verifyResult,
            onVerify = { lastDeployPreset?.let { viewModel.verifyApplied(it) } },
            onDismiss = {
                viewModel.clearLastDeploy()
                viewModel.clearVerifyResult()
            },
        )
    }

    lastBootDeploy?.let { bootDeployed ->
        BootDeployedDialog(
            deployed = bootDeployed,
            onDismiss = viewModel::clearLastBootDeploy,
        )
    }
}

enum class PresetAction {
    APPLY,
    GENERATE_SCRIPT,
    GENERATE_SCRIPT_WITH_REMINDER,
    INSTALL_AT_BOOT,
}

// --- Tune header ------------------------------------------------------

@Composable
private fun TuneHeader(report: CapabilityReport, onOpenHistory: () -> Unit = {}) {
    val vb = io.github.mayusi.calibratesoc.data.vendor.VendorBranding.of(report)
    val tierChip = when (report.privilege) {
        PrivilegeTier.AYN_SETTINGS -> vb.tierLabel
        else -> report.privilege.name
    }
    val tierAccent = when (report.privilege) {
        PrivilegeTier.ROOT -> AccentBar.Emerald
        PrivilegeTier.AYN_SETTINGS -> AccentBar.Emerald
        PrivilegeTier.SHIZUKU -> AccentBar.Blue
        PrivilegeTier.NONE -> AccentBar.Neutral
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TUNE",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.04.sp,
                modifier = Modifier.weight(1f),
            )
            ArsenalButton(
                label = "History",
                onClick = onOpenHistory,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            StatusPill(text = tierChip, accent = tierAccent)
            report.device.knownHandheldKey?.let { key ->
                StatusPill(text = key, accent = AccentBar.Neutral)
            }
        }
        val explainer = when (report.privilege) {
            PrivilegeTier.ROOT ->
                "Magisk/KernelSU detected. Direct sysfs writes available — Apply works for everything."
            PrivilegeTier.AYN_SETTINGS ->
                "${vb.brand} tier active. Vendor tuning is owned by the firmware. For custom MHz caps, generate a script and run it via ${vb.settingsApp} → Run script as Root."
            PrivilegeTier.SHIZUKU ->
                "Shizuku bound. Custom MHz needs root or the script path. Vendor tuning pending UserService support."
            PrivilegeTier.NONE ->
                "Read-only tier. Generate a script for custom MHz caps, or grant WRITE_SECURE_SETTINGS via adb to unlock vendor tunes:\nadb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS"
        }
        Text(
            explainer,
            style = MaterialTheme.typography.bodySmall,
            color = when (report.privilege) {
                PrivilegeTier.ROOT, PrivilegeTier.AYN_SETTINGS -> Color(0xFF999999)
                else -> AccentBar.Blue
            },
        )
    }
}

// --- Vendor controls card --------------------------------------------

@Composable
private fun VendorCard(
    adapter: DeviceAdapter,
    onOpenFanCurve: () -> Unit,
) {
    val brand = when {
        adapter.key.startsWith("ayn") || "odin" in adapter.key ->
            io.github.mayusi.calibratesoc.data.vendor.VendorBrand.AYN
        adapter.key.startsWith("retroid") ->
            io.github.mayusi.calibratesoc.data.vendor.VendorBrand.RETROID
        adapter.key.startsWith("ayaneo") ->
            io.github.mayusi.calibratesoc.data.vendor.VendorBrand.AYANEO
        adapter.key.startsWith("anbernic") ->
            io.github.mayusi.calibratesoc.data.vendor.VendorBrand.ANBERNIC
        else -> io.github.mayusi.calibratesoc.data.vendor.VendorBrand.GENERIC
    }
    ArsenalPanel(accent = AccentBar.Neutral, title = "VENDOR CONTROLS — ${adapter.displayName}") {
        Text(
            "${brand.brand}'s performance and fan modes are owned by the firmware's vendor daemon, which only accepts root-elevated writes. Pull down Quick Settings to flip those modes with ${brand.brand}'s own tiles — they work perfectly and we don't duplicate them here.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Text(
            "What this app DOES control, no root needed: custom CPU & GPU MHz caps via the tune list below — tap Generate script and run it via ${brand.settingsApp} → Run script as Root. With Magisk/KernelSU you also get Apply once and Install at boot.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        if (adapter.fanAdapter?.supportsCurve == true) {
            Spacer(Modifier.height(Spacing.group))
            ArsenalButton(
                label = "Open ${brand.brand} Fan Curve Editor",
                onClick = onOpenFanCurve,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Blue,
            )
        }
    }
}

// --- Script-deployed dialog ------------------------------------------

@Composable
private fun ScriptDeployedDialog(
    deployed: AynScriptDeployer.Deployed,
    preset: Preset?,
    verifyResult: TuneViewModel.VerifyResult?,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vendorName = remember {
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.vendorSettingsName(context)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script generated") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text("Saved to:", style = MaterialTheme.typography.labelMedium)
                Text(
                    deployed.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(Spacing.dense))
                if (deployed.visibleToOdinPicker) {
                    Text("To run it:")
                    Text("1. Tap \"Open $vendorName\" below")
                    Text("2. Tap \"Run script as Root\"")
                    Text("3. Pick the .sh from the CalibrateSoC folder")
                    Text("4. Come back here and tap \"Verify it worked\".", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Note: sysfs writes don't survive reboot. Re-run after each restart, or use Apply on boot once Magisk is installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                } else {
                    Text(
                        "Public storage wasn't writable — the script ended up in our app-private folder where the runner probably can't see it. Copy it to /sdcard/CalibrateSoC/ manually or grant the app storage permission.",
                        color = AccentBar.Red,
                    )
                }
                verifyResult?.let { vr ->
                    Spacer(Modifier.height(Spacing.dense))
                    HorizontalDivider()
                    VerifyResultBlock(vr)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openVendorSettings(context)
            }) { Text("Open $vendorName") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                if (preset != null && preset.cpuPolicyMaxKhz.isNotEmpty()) {
                    TextButton(onClick = onVerify) { Text("Verify it worked") }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

// --- Persistent "last generated script" card -------------------------

@Composable
private fun LastScriptCard(
    deployed: AynScriptDeployer.Deployed,
    preset: Preset?,
    verifyResult: TuneViewModel.VerifyResult?,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Neutral, title = "LAST GENERATED SCRIPT") {
        val fileName = remember(deployed.path) { deployed.path.substringAfterLast('/') }
        Text(
            fileName,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text(
            "Run it via your device's 'Run script as Root', then tap Verify.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArsenalButton(
                label = "Verify It Worked",
                onClick = onVerify,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Emerald,
                enabled = preset != null && preset.cpuPolicyMaxKhz.isNotEmpty(),
            )
            ArsenalButton(
                label = "Dismiss",
                onClick = onDismiss,
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
        verifyResult?.let { vr ->
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            VerifyResultBlock(vr)
        }
    }
}

@Composable
private fun VerifyResultBlock(vr: TuneViewModel.VerifyResult) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
        when {
            vr.allOk -> Text(
                "✓ Applied — kernel confirms the new clocks.",
                color = AccentBar.Emerald,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            vr.anyMismatch -> Text(
                "✗ Not applied — a cluster is still at its old clock. Re-run the script (a vendor daemon may have clamped it).",
                color = AccentBar.Red,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            !vr.anyMismatch && vr.readableOk && vr.anyUnreadable -> Text(
                "✓ Looks applied — confirmed readable clusters; some clusters can't be read back on this firmware (SELinux), but they were in the same script.",
                color = AccentBar.Emerald,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            vr.allUnreadable -> Text(
                "Can't read the clocks back on this firmware (SELinux blocks it). The script runs as root — if you saw its output it applied. Check the Dashboard CPU MHz to confirm.",
                color = AccentBar.Amber,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        vr.policies.forEach { p ->
            val mark = when {
                !p.readable -> "•"
                p.ok -> "✓"
                else -> "✗"
            }
            val got = if (!p.readable) "restricted"
                else "${p.gotMaxKhz?.let { it / 1000 }?.toString() ?: "?"} MHz"
            Text(
                "$mark policy${p.policyId}: want ${(p.wantMaxKhz ?: 0) / 1000} MHz, got $got",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !p.readable -> Color(0xFF999999)
                    p.ok -> Color.White
                    else -> AccentBar.Red
                },
            )
        }
    }
}

// --- Community presets — Arsenal panels per preset -------------------

@Composable
private fun PresetsCard(
    presets: List<Preset>,
    canApply: Boolean,
    canGenerateScript: Boolean,
    onAction: (Preset, PresetAction) -> Unit,
) {
    val canInstallAtBoot = canApply
    PresetsCardInner(presets, canApply, canGenerateScript, canInstallAtBoot, onAction)
}

@Composable
private fun PresetsCardInner(
    presets: List<Preset>,
    canApply: Boolean,
    canGenerateScript: Boolean,
    canInstallAtBoot: Boolean,
    onAction: (Preset, PresetAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
        // Section header
        SectionHeader(title = "TUNES", accent = AccentBar.Red)

        // Safety primer as an info panel
        ArsenalPanel(accent = AccentBar.Emerald, accentEdge = PanelAccentEdge.Start) {
            Text(
                "ARE THESE SAFE?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = AccentBar.Emerald,
                letterSpacing = 0.08.sp,
            )
            Text(
                "Yes. Every tune only moves your CPU/GPU clock caps WITHIN the range " +
                    "your kernel already allows — it can't set a voltage, raise a thermal " +
                    "limit, or push past what the chip ships with. Worst case is \"a bit slower\" " +
                    "or \"a bit warmer,\" never a damaged device. Every tune also resets the " +
                    "minimum clock so cores can still idle. And nothing survives a reboot unless " +
                    "you pick \"Install at boot,\" so a restart always returns you to stock.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            Text(
                "New here? Start with Balanced — the safe all-rounder. Battery Saver for " +
                    "longer play, Performance for demanding games.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        }

        Text(
            "Community Tuned recipes are verified on your exact device; built-in tunes are " +
                "generated from your kernel's own OPP table. Without root, use Generate script " +
                "to run the same tune via your device's settings → Run script as Root.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF777777),
        )

        val uri = LocalUriHandler.current

        // Each preset = one ArsenalPanel
        presets.forEach { preset ->
            val presetAccent = when (preset.id) {
                "builtin_performance" -> AccentBar.Red
                "builtin_balanced" -> AccentBar.Emerald
                "builtin_battery_saver" -> AccentBar.Amber
                else -> if (preset.verification == VerificationTier.COMMUNITY_TUNED) AccentBar.Blue else AccentBar.Neutral
            }

            ArsenalPanel(accent = presetAccent) {
                // Preset name + verification pill + recommended chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                ) {
                    Text(
                        preset.name.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.06.sp,
                        modifier = Modifier.weight(1f),
                    )
                    VerificationPill(preset.verification)
                    if (preset.id == "builtin_balanced") {
                        StatusPill(text = "RECOMMENDED", accent = AccentBar.Emerald)
                    }
                }

                // Description
                Text(
                    preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )

                // Per-cluster MHz cap StatBars — the core Arsenal pattern
                if (preset.cpuPolicyMaxKhz.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.dense))
                    preset.cpuPolicyMaxKhz.entries.forEach { (policyId, capKhz) ->
                        // Ceiling from capability would be ideal, but we don't have it here
                        // so we use the cap itself as the bar endpoint, showing 100%.
                        // A relative bar needs context from the VM — for now render raw MHz.
                        StatBar(
                            label = "POLICY$policyId CAP",
                            value = "${capKhz / 1000} MHz",
                            fraction = 1f, // relative bar — full width at the cap value
                            accent = presetAccent,
                            slanted = true,
                        )
                    }
                }

                if (preset.cpuPolicyGovernor.isNotEmpty()) {
                    Text(
                        "GOV: ${preset.cpuPolicyGovernor.values.toSet().joinToString(",")}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF777777),
                        letterSpacing = 0.06.sp,
                    )
                }

                preset.sourceUrl?.let { url ->
                    TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            "SOURCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentBar.Blue,
                            letterSpacing = 0.06.sp,
                        )
                    }
                }

                // Action buttons row
                Spacer(Modifier.height(Spacing.dense))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.group),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canApply) {
                        ArsenalButton(
                            label = "Apply",
                            onClick = { onAction(preset, PresetAction.APPLY) },
                            style = ArsenalButtonStyle.Primary,
                            accent = AccentBar.Red,
                        )
                    }
                    if (canInstallAtBoot) {
                        ArsenalButton(
                            label = "Boot",
                            onClick = { onAction(preset, PresetAction.INSTALL_AT_BOOT) },
                            style = ArsenalButtonStyle.Secondary,
                            accent = presetAccent,
                        )
                    }
                    if (canGenerateScript) {
                        ArsenalButton(
                            label = "Script",
                            onClick = { onAction(preset, PresetAction.GENERATE_SCRIPT) },
                            style = ArsenalButtonStyle.Secondary,
                            accent = AccentBar.Neutral,
                        )
                        ArsenalButton(
                            label = "+Boot Remind",
                            onClick = { onAction(preset, PresetAction.GENERATE_SCRIPT_WITH_REMINDER) },
                            style = ArsenalButtonStyle.Secondary,
                            accent = AccentBar.Neutral,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationPill(tier: VerificationTier) {
    val (label, accent) = when (tier) {
        VerificationTier.COMMUNITY_TUNED -> "COMMUNITY" to AccentBar.Blue
        VerificationTier.GENERIC_KNOWN_FAMILY -> "BUILT-IN" to AccentBar.Neutral
        VerificationTier.GENERIC_UNKNOWN_FAMILY -> "UNKNOWN SOC" to AccentBar.Red
        VerificationTier.USER_CUSTOM -> "CUSTOM" to AccentBar.Amber
    }
    StatusPill(text = label, accent = accent)
}

// --- GPU card (full info + sliders) ----------------------------------

@Composable
private fun GpuCard(
    gpu: io.github.mayusi.calibratesoc.data.capability.GpuProbe,
    presets: List<Preset>,
    edit: TuneViewModel.GpuEdit?,
    onChange: (TuneViewModel.GpuEdit) -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Purple, title = "GPU — ${gpu.family}") {
        if (gpu.availableFreqsHz.isEmpty()) {
            Text(
                "No OPP table — kernel doesn't expose available GPU frequencies on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }
        val sorted = gpu.availableFreqsHz.sorted()
        val low = sorted.first() / 1_000_000L
        val high = sorted.last() / 1_000_000L

        Text(
            "CURRENT CAP",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF999999),
            letterSpacing = 0.08.sp,
        )
        Text(
            "${gpu.currentMinHz / 1_000_000L} – ${gpu.currentMaxHz / 1_000_000L} MHz",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        val currentMin = edit?.minHz ?: gpu.currentMinHz
        val currentMax = edit?.maxHz ?: gpu.currentMaxHz
        GpuLabeledSlider(
            label = "MIN FREQ",
            valueHz = currentMin,
            stepsHz = sorted,
            onChange = { snapped -> onChange((edit ?: TuneViewModel.GpuEdit()).copy(minHz = snapped)) },
        )
        GpuLabeledSlider(
            label = "MAX FREQ",
            valueHz = currentMax,
            stepsHz = sorted,
            onChange = { snapped -> onChange((edit ?: TuneViewModel.GpuEdit()).copy(maxHz = snapped)) },
        )

        gpu.powerLevelRange?.let { range ->
            val currentLevel = edit?.powerLevel ?: range.low
            GpuPowerLevelSlider(
                low = range.low,
                high = range.high,
                value = currentLevel,
                onChange = { lvl -> onChange((edit ?: TuneViewModel.GpuEdit()).copy(powerLevel = lvl)) },
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        Text(
            "HARDWARE OPP TABLE",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF999999),
            letterSpacing = 0.08.sp,
        )
        Text(
            "$low – $high MHz · ${sorted.size} steps",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text(
            sorted.joinToString(", ") { (it / 1_000_000L).toString() } + " MHz",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        Text(
            "GOVERNOR",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF999999),
            letterSpacing = 0.08.sp,
        )
        Text(
            gpu.currentGovernor.ifBlank { "(none reported)" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        if (gpu.availableGovernors.isNotEmpty()) {
            Text(
                "Available: ${gpu.availableGovernors.joinToString(", ")}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
            )
        }
        gpu.powerLevelRange?.let {
            Text(
                "Adreno power-level range: ${it.low}–${it.high} (0 = fastest)",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF777777),
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        Text(
            "WHAT EACH TUNE WOULD SET",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF999999),
            letterSpacing = 0.08.sp,
        )
        val presetsWithGpu = presets.filter { it.gpuMaxHz != null }
        if (presetsWithGpu.isEmpty()) {
            Text(
                "No bundled tune writes the GPU on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
        } else {
            presetsWithGpu.forEach { p ->
                val mhz = (p.gpuMaxHz ?: 0L) / 1_000_000L
                val gov = p.gpuGovernor?.let { " · gov $it" } ?: ""
                Text(
                    "${p.name.uppercase()}:  $mhz MHz$gov",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Text(
            "Adjust the sliders above and tap Apply to write a custom GPU cap, " +
                "or apply a tune to use one of these.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF777777),
        )
    }
}

@Composable
private fun GpuLabeledSlider(
    label: String,
    valueHz: Long,
    stepsHz: List<Long>,
    onChange: (Long) -> Unit,
) {
    val low = stepsHz.first().toFloat()
    val high = stepsHz.last().toFloat()
    val sliderRange = (high - low).coerceAtLeast(1f)
    val pos = ((valueHz.toFloat() - low) / sliderRange).coerceIn(0f, 1f)

    Column {
        Row {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF999999),
                letterSpacing = 0.06.sp,
            )
            Text(
                "${valueHz / 1_000_000L} MHz",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Slider(
            value = pos,
            steps = (stepsHz.size - 2).coerceAtLeast(0),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                val rawHz = low + fraction * sliderRange
                val snapped = stepsHz.minByOrNull { kotlin.math.abs(it - rawHz) } ?: valueHz
                if (snapped != valueHz) onChange(snapped)
            },
        )
    }
}

@Composable
private fun GpuPowerLevelSlider(
    low: Int,
    high: Int,
    value: Int,
    onChange: (Int) -> Unit,
) {
    val span = (high - low).coerceAtLeast(1)
    Column {
        Row {
            Text(
                "MAX POWER LEVEL",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF999999),
                letterSpacing = 0.06.sp,
            )
            Text(
                "$value ${if (value == low) "(fastest)" else if (value == high) "(slowest)" else ""}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Slider(
            value = (value - low).toFloat(),
            steps = (span - 1).coerceAtLeast(0),
            valueRange = 0f..span.toFloat(),
            onValueChange = { f ->
                val lvl = low + f.toInt().coerceIn(0, span)
                if (lvl != value) onChange(lvl)
            },
        )
        Text(
            "0 = fastest. Higher numbers cap the GPU at a slower power level (cooler / less battery).",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )
    }
}

// --- Per-policy card — Blue accent + StatBar cluster caps ------------

@Composable
private fun PolicyCard(
    policy: CpuPolicyProbe,
    edit: TuneViewModel.PolicyEdit?,
    liveCoreFreqsKhz: List<Int>?,
    allPolicyMaxKhz: List<Int>,
    onChange: (TuneViewModel.PolicyEdit) -> Unit,
) {
    val thisPolicyMaxKhz = policy.availableFreqsKhz.lastOrNull() ?: policy.currentMaxKhz
    val tierLabel = clusterTierLabel(thisPolicyMaxKhz, allPolicyMaxKhz)
    val clusterAccent = when (tierLabel) {
        "prime" -> AccentBar.Red
        "big" -> AccentBar.Blue
        else -> AccentBar.Neutral // efficiency
    }
    val title = "CPU POLICY ${policy.policyId} — CORES ${policy.onlineCores.joinToString(",")} · ${tierLabel.uppercase()}"

    ArsenalPanel(accent = clusterAccent, title = title) {
        val freqs = policy.availableFreqsKhz
        if (freqs.isEmpty()) {
            Text(
                "No OPP table — kernel does not expose available freqs.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            return@ArsenalPanel
        }
        val low = freqs.first()
        val high = freqs.last()
        val currentMin = edit?.minKhz ?: policy.currentMinKhz
        val currentMax = edit?.maxKhz ?: policy.currentMaxKhz

        // Live MHz readback from telemetry
        val liveMhzLabel = liveCoreFreqsKhz
            ?.let { allCores ->
                policy.onlineCores
                    .mapNotNull { coreIdx -> allCores.getOrNull(coreIdx) }
                    .maxOrNull()
                    ?.let { khz -> "LIVE: ${khz / 1000} MHz" }
            }

        // Show live freq as a StatusPill when available
        if (liveMhzLabel != null) {
            StatusPill(text = liveMhzLabel, accent = AccentBar.Emerald)
        }

        LabeledSlider(
            label = "MIN FREQ",
            valueKhz = currentMin,
            steps = freqs,
            onChange = { snapped -> onChange((edit ?: TuneViewModel.PolicyEdit()).copy(minKhz = snapped)) },
        )
        LabeledSlider(
            label = "MAX FREQ",
            valueKhz = currentMax,
            steps = freqs,
            liveSuffix = null, // shown as pill above
            onChange = { snapped -> onChange((edit ?: TuneViewModel.PolicyEdit()).copy(maxKhz = snapped)) },
        )
        Text(
            "HARDWARE RANGE: ${low / 1000}–${high / 1000} MHz · ${freqs.size} STEPS",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
            letterSpacing = 0.06.sp,
            fontFamily = FontFamily.Monospace,
        )

        if (policy.availableGovernors.isNotEmpty()) {
            GovernorDropdown(
                available = policy.availableGovernors,
                current = edit?.governor ?: policy.currentGovernor,
                onChange = { onChange((edit ?: TuneViewModel.PolicyEdit()).copy(governor = it)) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueKhz: Int,
    steps: List<Int>,
    onChange: (Int) -> Unit,
    liveSuffix: String? = null,
) {
    val low = steps.first().toFloat()
    val high = steps.last().toFloat()
    val sliderRange = (high - low).coerceAtLeast(1f)
    val pos = ((valueKhz.toFloat() - low) / sliderRange).coerceIn(0f, 1f)

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF999999),
                letterSpacing = 0.06.sp,
            )
            if (liveSuffix != null) {
                Text(
                    liveSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBar.Emerald,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = Spacing.group),
                )
            }
            Text(
                "${valueKhz / 1000} MHz",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Slider(
            value = pos,
            steps = (steps.size - 2).coerceAtLeast(0),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                val rawKhz = low + fraction * sliderRange
                val snapped = steps.minByOrNull { kotlin.math.abs(it - rawKhz) } ?: valueKhz
                if (snapped != valueKhz) onChange(snapped)
            },
        )
    }
}

@Composable
private fun GovernorDropdown(
    available: List<String>,
    current: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            label = { Text("Governor") },
            readOnly = true,
            trailingIcon = {
                Icon(
                    Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Spacing.group),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = Spacing.group),
        ) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("") }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { gov ->
                DropdownMenuItem(
                    text = { Text(gov) },
                    onClick = { onChange(gov); expanded = false },
                )
            }
        }
    }
}

// --- Apply bar -------------------------------------------------------

@Composable
private fun ApplyBar(
    pendingCount: Int,
    canApply: Boolean,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        ArsenalButton(
            label = "Discard",
            onClick = onClear,
            style = ArsenalButtonStyle.Secondary,
            accent = AccentBar.Neutral,
            modifier = Modifier.weight(1f),
        )
        ArsenalButton(
            label = if (pendingCount > 0) "Apply ($pendingCount)" else "Apply",
            onClick = onApply,
            style = ArsenalButtonStyle.Primary,
            accent = AccentBar.Red,
            enabled = canApply && pendingCount > 0,
            modifier = Modifier.weight(2f),
        )
    }
}

// --- Results card ----------------------------------------------------

@Composable
private fun ResultsCard(results: List<WriteResult>) {
    ArsenalPanel(accent = AccentBar.Neutral, title = "LAST APPLY") {
        results.forEach { r ->
            val (label, color) = when (r) {
                is WriteResult.Success ->
                    "✓ ${r.id.target.substringAfterLast('/')}: ${r.previousValue ?: "—"} → ${r.newValue}" to AccentBar.Emerald
                is WriteResult.CapabilityDenied ->
                    "✗ ${r.id.target.substringAfterLast('/')}: ${r.reason}" to Color(0xFF777777)
                is WriteResult.Rejected ->
                    "✗ ${r.id.target.substringAfterLast('/')}: ${r.message}" to AccentBar.Red
                is WriteResult.Failed ->
                    "✗ ${r.id.target.substringAfterLast('/')}: ${r.error.message ?: "failed"}" to AccentBar.Red
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// --- First-OC confirm modal ------------------------------------------

@Composable
private fun FirstOcConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val required = "I understand"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("First-time write to kernel sysfs") },
        text = {
            Column {
                Text("You're about to change kernel CPU/GPU frequency caps. Calibrate SoC reverts every write at boot by default, and v1 never touches voltage — but you should know:")
                Spacer(Modifier.height(Spacing.group))
                Text("• Aggressive caps can cause UI stutter or app crashes")
                Text("• Disabling thermal protection is not possible here")
                Text("• Revert to stock by rebooting, or by tapping 'Discard'")
                Spacer(Modifier.height(Spacing.item))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type \"$required\" to continue") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = typed.trim() == required) { Text("Continue") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// --- Boot deployed / unknown device dialogs --------------------------

@Composable
private fun BootDeployedDialog(
    deployed: AynScriptDeployer.BootDeployed,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deployed.success) "Installed at boot" else "Boot install failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                if (deployed.success) {
                    Text("Installed via ${deployed.manager} at:")
                    Text(deployed.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(Spacing.dense))
                    Text("The tune will re-apply on every boot. To remove it later, delete the file via your root manager or run a fresh Apply on a different tune.")
                } else {
                    Text(deployed.error ?: "Unknown failure.", color = AccentBar.Red)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Got it") } },
    )
}

@Composable
private fun UnknownDeviceConfirmDialog(
    preset: Preset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unknown SoC family") },
        text = {
            Column {
                Text(
                    "Calibrate SoC doesn't yet have a tested adapter for your device. The " +
                        "tune \"${preset.name}\" was generated from your kernel's own OPP " +
                        "table — every frequency it writes is one the kernel already publishes " +
                        "as valid, so it shouldn't crash. But power and thermal behaviour on " +
                        "this exact silicon haven't been profiled.",
                )
                Spacer(Modifier.height(Spacing.group))
                Text("Sensible first move: try Battery Saver before Performance.")
                Spacer(Modifier.height(Spacing.group))
                Text(
                    "Help others: in Device Info, tap \"Report unknown device\" to share an " +
                        "anonymized capability report so we can ship a tuned adapter in a future release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Apply anyway") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Save-as-profile dialog ------------------------------------------

@Composable
private fun SaveProfileDialog(
    onSave: (name: String, description: String, applyOnBoot: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var applyOnBoot by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Captures the current CPU (and GPU) clock caps and governors " +
                        "shown above — including any pending slider edits — as a " +
                        "reusable profile you can re-apply from the Profiles tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyOnBoot, onCheckedChange = { applyOnBoot = it })
                    Text("Re-apply on boot", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), description.trim(), applyOnBoot) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Display refresh-rate card (Arsenal-framed) ----------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DisplayRefreshCard(
    viewModel: io.github.mayusi.calibratesoc.ui.tune.DisplayRefreshViewModel =
        hiltViewModel(),
) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val preferredHz by viewModel.preferredHz.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var liveHz by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        viewModel.refresh(context)
        while (true) {
            val act = context as? android.app.Activity
            val display = act?.windowManager?.defaultDisplay
            liveHz = display?.refreshRate ?: 0f
            kotlinx.coroutines.delay(1000)
        }
    }

    ArsenalPanel(accent = AccentBar.Neutral, title = "DISPLAY REFRESH RATE") {
        // Live rate indicator
        val liveAccent = if (liveHz >= 119f) AccentBar.Emerald else AccentBar.Neutral
        if (liveHz > 0) {
            StatusPill(text = "LIVE: ${"%.0f".format(liveHz)} Hz", accent = liveAccent)
        }

        if (modes.isEmpty()) {
            Text(
                "No modes reported yet. Tap Re-scan after the app is fully open.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )
            Spacer(Modifier.height(Spacing.group))
            ArsenalButton(
                label = "Re-scan",
                onClick = { viewModel.refresh(context) },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
            return@ArsenalPanel
        }
        Text(
            "Pick a refresh rate. Many panels report more modes than the system uses by default — pinning a higher Hz here also calls Surface.setFrameRate so the OS actually honors it.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Spacer(Modifier.height(Spacing.group))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalArrangement = Arrangement.spacedBy(Spacing.dense),
            modifier = Modifier.fillMaxWidth(),
        ) {
            modes.forEach { mode ->
                val on = preferredHz != null && kotlin.math.abs((preferredHz ?: 0f) - mode.hz) < 0.5f
                ArsenalButton(
                    label = mode.displayLabel,
                    onClick = {
                        viewModel.pick(mode.hz)
                        val result = applyRefreshRate(context, mode.id, mode.hz, viewModel.refreshScript)
                        if (result is RefreshApplyResult.NeedsScript) {
                            val vs = OdinIntents.vendorSettingsName(context)
                            android.widget.Toast.makeText(
                                context,
                                "Opened $vs — pick calibratesoc_${mode.hz.toInt()}hz.sh to apply",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    style = if (on) ArsenalButtonStyle.Primary else ArsenalButtonStyle.Secondary,
                    accent = if (on) AccentBar.Blue else AccentBar.Neutral,
                )
            }
            ArsenalButton(
                label = "AUTO",
                onClick = {
                    viewModel.pick(null)
                    applyRefreshRate(context, 0, 0f, viewModel.refreshScript)
                },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
        preferredHz?.let {
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "PINNED: ${"%.0f".format(it)} Hz (re-applied on app launch and HUD show)",
                style = MaterialTheme.typography.labelSmall,
                color = AccentBar.Blue,
                letterSpacing = 0.06.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// --- applyRefreshRate helpers (behavior unchanged) -------------------

internal sealed interface RefreshApplyResult {
    data object Direct : RefreshApplyResult
    data class NeedsScript(val path: String, val visibleToOdin: Boolean) : RefreshApplyResult
}

internal fun applyRefreshRate(
    context: android.content.Context,
    modeId: Int,
    hz: Float,
    refreshScript: io.github.mayusi.calibratesoc.data.script.RefreshRateScript?,
): RefreshApplyResult {
    val target = if (hz > 0f) hz.toString() else "60.0"
    val resolver = context.contentResolver
    val directWrote = runCatching {
        android.provider.Settings.System.putString(resolver, "peak_refresh_rate", target)
        android.provider.Settings.System.putString(resolver, "min_refresh_rate", "60.0")
    }.isSuccess

    val result: RefreshApplyResult = if (directWrote) {
        RefreshApplyResult.Direct
    } else if (refreshScript != null && hz > 0f) {
        val deployed = refreshScript.deploy(hz)
        OdinIntents.openOdinSettings(context)
        RefreshApplyResult.NeedsScript(deployed.path, deployed.visibleToOdinPicker)
    } else {
        RefreshApplyResult.Direct
    }

    val activity = context as? android.app.Activity
    if (activity != null) {
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = modeId
        if (hz > 0f) attrs.preferredRefreshRate = hz
        activity.window.attributes = attrs
    }
    return result
}

// --- Advanced unlock card (Arsenal-framed) ---------------------------

@Composable
private fun AdvancedUnlockCard(
    viewModel: io.github.mayusi.calibratesoc.ui.tune.AdvancedUnlockViewModel =
        hiltViewModel(),
) {
    val grants by viewModel.grants.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastPath by remember { mutableStateOf<String?>(null) }
    val vs = remember {
        OdinIntents.vendorSettingsName(context)
    }

    ArsenalPanel(accent = AccentBar.Neutral, title = "ADVANCED UNLOCK") {
        Text(
            "Tuning already works — tunes generate a script you run as root (no setup needed). " +
                "This section only unlocks OPTIONAL extras: live FPS overlay, vendor-key writes, " +
                "and the experimental instant ± HUD steppers.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Text(
            "Two settings unlock everything:\n\n" +
                "1. $vs → toggle \"Force SELinux\" ON (if your firmware has it).\n" +
                "2. Generate + Run the unlock script (below) once per boot.\n\n" +
                "When both are done, the SYSFS indicator goes green and the experimental HUD ± buttons can fire instant clock changes.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF999999),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            GrantIndicator("DUMP", grants.dump, "fg app")
            GrantIndicator("USAGE", grants.usageStats, "app label")
            GrantIndicator("SECURE", grants.writeSecureSettings, "vendor keys")
            GrantIndicator("SYSFS", grants.sysfsWritable, "instant ±")
        }
        if (grants.allHeld) {
            StatusPill(text = "ALL GRANTED — HUD LIVE", accent = AccentBar.Emerald)
        }
        Spacer(Modifier.height(Spacing.dense))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            ArsenalButton(
                label = if (grants.anyHeld) "Re-run Unlock" else "Generate + Open $vs",
                onClick = {
                    val deployed = viewModel.deployScript()
                    lastPath = deployed.path
                    OdinIntents.openOdinSettings(context)
                },
                style = ArsenalButtonStyle.Primary,
                accent = AccentBar.Red,
            )
            ArsenalButton(
                label = "Refresh Status",
                onClick = { viewModel.refresh() },
                style = ArsenalButtonStyle.Secondary,
                accent = AccentBar.Neutral,
            )
        }
        lastPath?.let {
            Text(
                "Wrote $it",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = AccentBar.Emerald,
            )
        }
    }
}

@Composable
private fun GrantIndicator(label: String, on: Boolean, hint: String) {
    val accent = if (on) AccentBar.Emerald else AccentBar.Neutral
    Column {
        StatusPill(text = if (on) "✓ $label" else "— $label", accent = accent)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF777777),
        )
    }
}

// --- Cluster tier inference (behavior unchanged) ---------------------

internal fun clusterTierLabel(thisPolicyMaxKhz: Int, allPolicyMaxKhz: List<Int>): String {
    if (allPolicyMaxKhz.size <= 1) return "efficiency"
    return when (thisPolicyMaxKhz) {
        allPolicyMaxKhz.first() -> "efficiency"
        allPolicyMaxKhz.last() -> if (allPolicyMaxKhz.size >= 3) "prime" else "big"
        else -> "big"
    }
}
