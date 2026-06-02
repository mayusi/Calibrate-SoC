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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/**
 * Phase 5 Tune screen. Surfaces what the device actually exposes:
 *   * One card per discovered CPU policy with min/max freq sliders +
 *     governor dropdown. Sliders snap to the OPP table values exposed
 *     by the kernel — no point letting the user pick 1234 MHz when the
 *     kernel can only run 1209.6 / 1401.6.
 *   * A Community Tuned card listing bundled presets for the device
 *     (e.g. TheOldTaylor's Odin 3 underclock recipes). Apply writes
 *     directly, no slider scrubbing.
 *   * A first-OC typed-confirm modal that fires once per install.
 *
 * Privilege-tier handling is honest: when the report says NONE or
 * SHIZUKU, the Apply button is disabled with an inline "needs root"
 * explainer rather than letting the user mash a button that silently
 * fails.
 */
@Composable
fun TuneScreen(
    onOpenHistory: () -> Unit = {},
    viewModel: TuneViewModel = hiltViewModel(),
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val results by viewModel.lastResults.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val ocAck by viewModel.oneTimeOcAcknowledged.collectAsStateWithLifecycle()
    val adapter by viewModel.adapter.collectAsStateWithLifecycle()
    val lastDeploy by viewModel.lastDeploy.collectAsStateWithLifecycle()
    val lastDeployPreset by viewModel.lastDeployPreset.collectAsStateWithLifecycle()
    val verifyResult by viewModel.verifyResult.collectAsStateWithLifecycle()
    val lastBootDeploy by viewModel.lastBootDeploy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Pending confirmations. Two kinds:
    //   pendingFirstOcConfirm — one-shot, gated by DataStore
    //   pendingUnknownDeviceConfirm — fires every time for GENERIC_UNKNOWN_FAMILY
    var pendingFirstOcConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingUnknownDeviceConfirm by remember { mutableStateOf<Pair<Preset, () -> Unit>?>(null) }

    val report = capability
    if (report == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Probing device…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    // Tier capabilities derived once for the whole screen. AYN_SETTINGS
    // unlocks the vendor-controls card + script-generation path for
    // presets. ROOT unlocks direct sysfs writes for both presets and
    // slider edits. SHIZUKU + NONE are read-only for now.
    val canWriteSysfs = report.privilege == PrivilegeTier.ROOT
    // hasVendorControls = device has a known vendor companion app with a
    // fan-curve / vendor-preset surface (AYN/Odin, Retroid). Drives ONLY
    // the VendorCard. Script generation is NOT gated on this — see below.
    val hasVendorControls = adapter?.vendorAppPackage?.startsWith("com.odin") == true ||
        adapter?.vendorAppPackage?.startsWith("com.ayn") == true ||
        adapter?.vendorAppPackage?.startsWith("com.rp") == true ||
        adapter?.fanAdapter?.supportsCurve == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(report, onOpenHistory = onOpenHistory) }
        item { DisplayRefreshCard() }
        item { AdvancedUnlockCard() }

        // Vendor controls card: only when an AYN handheld adapter
        // exists. Visible even when WRITE_SECURE_SETTINGS hasn't been
        // granted yet — buttons show a helpful "grant me first" state
        // instead of being hidden, so the user knows what's possible.
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

        if (presets.isNotEmpty()) {
            item {
                PresetsCard(
                    presets = presets,
                    canApply = canWriteSysfs,
                    // Script generation is the UNIVERSAL no-root path —
                    // any device can take the .sh and run it via its own
                    // "Run script as Root", Magisk/KernelSU, or adb. Never
                    // gate it on vendor/root.
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
                        PresetAction.GENERATE_SCRIPT -> {
                            viewModel.generateAynScript(preset)
                        }
                        PresetAction.GENERATE_SCRIPT_WITH_REMINDER -> {
                            viewModel.generateScriptWithReminder(preset)
                        }
                        PresetAction.INSTALL_AT_BOOT -> {
                            viewModel.installScriptAsBootService(preset)
                        }
                    }
                }
            }
        }

        // Persistent "Last generated script" card. Unlike the modal
        // ScriptDeployedDialog (which vanishes when the user leaves to
        // run the script), this survives the round-trip so Verify is
        // always reachable when they come back.
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

        items(report.cpuPolicies) { policy ->
            PolicyCard(
                policy = policy,
                edit = pending[policy.policyId],
                onChange = { viewModel.setEdit(policy.policyId, it) },
            )
        }

        // GPU card — render only when the capability probe found a
        // usable GPU surface. Mali kernels without devfreq fall out
        // silently rather than render a slider that does nothing.
        report.gpu?.let { gpu ->
            item { GpuCard(gpu, presets) }
        }

        item {
            ApplyBar(
                pendingCount = pending.values.count { it.minKhz != null || it.maxKhz != null || it.governor != null },
                canApply = canWriteSysfs,
                onApply = {
                    val apply = { viewModel.apply() }
                    if (!ocAck) pendingFirstOcConfirm = apply else apply()
                },
                onClear = viewModel::clearPending,
            )
        }

        if (results.isNotEmpty()) item { ResultsCard(results) }
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
    /** Root tier only: apply once via direct sysfs writes. */
    APPLY,
    /** Any tier: generate AYN script for one-shot use via Odin Settings. */
    GENERATE_SCRIPT,
    /** Any tier: generate AYN script + register post-boot reminder. */
    GENERATE_SCRIPT_WITH_REMINDER,
    /** Root tier only: drop script into Magisk/KernelSU service.d. */
    INSTALL_AT_BOOT,
}

// --- Header ----------------------------------------------------------

@Composable
private fun Header(report: CapabilityReport, onOpenHistory: () -> Unit = {}) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tune", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.fillMaxWidth(0.0f).weight(1f))
            OutlinedButton(onClick = onOpenHistory) { Text("History") }
        }
        // Vendor-aware brand words so a Retroid user never sees "AYN",
        // an AYANEO user never sees "Odin", etc.
        val vb = io.github.mayusi.calibratesoc.data.vendor.VendorBranding.of(report)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Show the tier in brand terms instead of the raw enum
            // ("AYN_SETTINGS"). Vendor tier → "<Brand> tier".
            val tierChip = when (report.privilege) {
                PrivilegeTier.AYN_SETTINGS -> vb.tierLabel
                else -> report.privilege.name
            }
            AssistChip(onClick = {}, label = { Text(tierChip) })
            report.device.knownHandheldKey?.let { key ->
                AssistChip(onClick = {}, label = { Text(key) })
            }
        }
        val explainer = when (report.privilege) {
            PrivilegeTier.ROOT ->
                "Magisk/KernelSU detected. Direct sysfs writes available — Apply works for everything."
            PrivilegeTier.AYN_SETTINGS ->
                "${vb.brand} tier active. Vendor preset switching is owned by the firmware (use the device's own Quick Settings tile). For custom MHz caps, generate a script and run it via ${vb.settingsApp} → Run script as Root."
            PrivilegeTier.SHIZUKU ->
                "Shizuku bound. Custom MHz needs root or the script path. Vendor preset switching pending UserService support."
            PrivilegeTier.NONE ->
                "Read-only tier. Generate a script for custom MHz caps, or grant WRITE_SECURE_SETTINGS via adb to unlock vendor presets:\n" +
                    "adb shell pm grant io.github.mayusi.calibratesoc android.permission.WRITE_SECURE_SETTINGS"
        }
        Text(
            explainer,
            style = MaterialTheme.typography.bodySmall,
            color = when (report.privilege) {
                PrivilegeTier.ROOT, PrivilegeTier.AYN_SETTINGS -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.secondary
            },
        )
    }
}

// --- Vendor controls — honest read-only card on Odin 3 -------------
//
// History note: we tried two paths to flip AYN's performance_mode /
// fan_mode keys from this app:
//   1. Settings.System.putInt with WRITE_SECURE_SETTINGS granted.
//      Value lands, but AYN's daemon ignores third-party writes.
//   2. PServerBinder transact() via reflection (langerhans-style).
//      Binder kernel returns UNKNOWN_TRANSACTION because Android's
//      AppFreezer has paused PServer for our caller, and the
//      service's SELinux context (pservice) refuses our app domain
//      (untrusted_app) regardless of app_whiteList membership.
//
// Net: on Odin 3 firmware, AYN's vendor controls genuinely cannot
// be driven by a third-party app without Magisk/KernelSU or a
// Shizuku-bound UserService. The honest thing is to NOT render
// non-functional buttons. We point users at Odin's own UI for the
// vendor presets (which works perfectly) and keep ourselves
// focused on what we CAN do well: kernel-level CPU/GPU tuning via
// the AYN script path or root-tier writes.

@Composable
private fun VendorCard(
    adapter: DeviceAdapter,
    onOpenFanCurve: () -> Unit,
) = SectionCard("Vendor controls — ${adapter.displayName}") {
    // Brand words from the adapter key so a Retroid shows "Retroid",
    // an AYANEO shows "AYANEO", etc.
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
    Text(
        "${brand.brand}'s performance and fan modes are owned by the firmware's vendor daemon, which only accepts root-elevated writes. Pull down Quick Settings to flip those modes with ${brand.brand}'s own tiles — they work perfectly and we don't duplicate them here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "What this app DOES control, no root needed: custom CPU & GPU MHz caps via the preset list below — tap Generate script and run it via ${brand.settingsApp} → Run script as Root. With Magisk/KernelSU you also get Apply once and Install at boot.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (adapter.fanAdapter?.supportsCurve == true) {
        OutlinedButton(onClick = onOpenFanCurve) {
            Text("Open ${brand.brand} fan curve editor")
        }
    }
}

// --- Script-deployed confirmation modal ------------------------------

@Composable
private fun ScriptDeployedDialog(
    deployed: AynScriptDeployer.Deployed,
    preset: Preset?,
    verifyResult: TuneViewModel.VerifyResult?,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Device-correct name: "Odin Settings" / "Retroid Settings" /
    // "AYANEO Settings" / "your device's settings".
    val vendorName = remember {
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.vendorSettingsName(context)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Script generated") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to:", style = MaterialTheme.typography.labelMedium)
                Text(
                    deployed.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                if (deployed.visibleToOdinPicker) {
                    Text("To run it:")
                    Text("1. Tap “Open $vendorName” below")
                    Text("2. Tap “Run script as Root”")
                    Text("3. Pick the .sh from the CalibrateSoC folder")
                    Text(
                        "4. Come back here and tap “Verify it worked”.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Note: sysfs writes don't survive reboot. Re-run after each restart, or use Apply on boot once Magisk is installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Public storage wasn't writable — the script ended up in our app-private folder where the runner probably can't see it. Copy it to /sdcard/CalibrateSoC/ manually or grant the app storage permission.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // Verification readback — the definitive "did it work".
                verifyResult?.let { vr ->
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    VerifyResultBlock(vr)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openVendorSettings(context)
                // Don't dismiss — keep the dialog so the user can tap
                // "Verify it worked" after they return.
            }) { Text("Open $vendorName") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (preset != null && preset.cpuPolicyMaxKhz.isNotEmpty()) {
                    TextButton(onClick = onVerify) { Text("Verify it worked") }
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}

// --- Persistent "last generated script" card -------------------------
//
// The modal ScriptDeployedDialog is transient — it's gone the moment
// the user navigates to their vendor settings to run the script. This
// card is the DURABLE home for Verify: it stays on the Tune screen as
// long as a script is the last thing the user generated, so the
// "run it → come back → verify" round-trip always has a reachable
// Verify button.

@Composable
private fun LastScriptCard(
    deployed: AynScriptDeployer.Deployed,
    preset: Preset?,
    verifyResult: TuneViewModel.VerifyResult?,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
) = SectionCard("Last generated script") {
    val fileName = remember(deployed.path) { deployed.path.substringAfterLast('/') }
    Text(
        fileName,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Run it via your device's 'Run script as Root', then tap Verify.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onVerify,
            enabled = preset != null && preset.cpuPolicyMaxKhz.isNotEmpty(),
        ) { Text("Verify it worked") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
    verifyResult?.let { vr ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        VerifyResultBlock(vr)
    }
}

/**
 * Shared verify-result renderer. Four mutually-exclusive cases keyed
 * on the VerifyResult rollups, plus per-policy rows. Reused by both
 * the persistent card and the transient dialog so the messaging stays
 * consistent. "restricted" replaces a null readback (SELinux-denied)
 * so the user never sees a useless "got ?".
 */
@Composable
private fun VerifyResultBlock(vr: TuneViewModel.VerifyResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            vr.allOk -> Text(
                "✓ Applied — kernel confirms the new clocks.",
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
            vr.anyMismatch -> Text(
                "✗ Not applied — a cluster is still at its old clock. Re-run the " +
                    "script (a vendor daemon may have clamped it).",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            !vr.anyMismatch && vr.readableOk && vr.anyUnreadable -> Text(
                "✓ Looks applied — confirmed the readable clusters; some clusters " +
                    "can't be read back on this firmware (SELinux), but they were in " +
                    "the same script.",
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
            vr.allUnreadable -> Text(
                "Can't read the clocks back on this firmware (SELinux blocks it). " +
                    "The script runs as root — if you saw its output it applied. " +
                    "Check the Dashboard CPU MHz to confirm.",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
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
                    !p.readable -> MaterialTheme.colorScheme.onSurfaceVariant
                    p.ok -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

// --- Community presets ----------------------------------------------

@Composable
private fun PresetsCard(
    presets: List<Preset>,
    canApply: Boolean,
    canGenerateScript: Boolean,
    onAction: (Preset, PresetAction) -> Unit,
) {
    // Root means we can also do the boot-install variant. Surface
    // both buttons; the boot-install one is what makes a tune
    // survive reboot without manual re-application each time.
    val canInstallAtBoot = canApply // canApply already == ROOT tier
    PresetsCardInner(presets, canApply, canGenerateScript, canInstallAtBoot, onAction)
}

@Composable
private fun PresetsCardInner(
    presets: List<Preset>,
    canApply: Boolean,
    canGenerateScript: Boolean,
    canInstallAtBoot: Boolean,
    onAction: (Preset, PresetAction) -> Unit,
) = SectionCard("Presets") {
    // Plain-English safety primer. The #1 question from users is
    // "will this brick my device?" — answer it up front so they tune
    // with confidence instead of fear.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Are these safe?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "Yes. Every preset here only moves your CPU/GPU clock caps " +
                    "WITHIN the range your kernel already allows — it can't " +
                    "set a voltage, raise a thermal limit, or push past what " +
                    "the chip ships with. Worst case is \"a bit slower\" or " +
                    "\"a bit warmer,\" never a damaged device. Every preset " +
                    "also resets the minimum clock so cores can still idle. " +
                    "And nothing survives a reboot unless you pick \"Install " +
                    "at boot,\" so a restart always returns you to stock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "New here? Start with Balanced — it's the safe all-rounder. " +
                    "Battery Saver for longer play, Performance for demanding " +
                    "games. The risky stuff (manual ± clock stepping) is hidden " +
                    "in Settings → Experimental and OFF by default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Community Tuned recipes are verified on your exact device; " +
            "built-in presets are generated from your kernel's own OPP table so they work " +
            "anywhere. Without root, use Generate script to run the same " +
            "tune via your device's settings → Run script as Root.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val uri = LocalUriHandler.current
    presets.forEach { preset ->
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        VerificationBadge(preset.verification)
                        // Steer newcomers to the safe all-rounder.
                        if (preset.id == "builtin_balanced") {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    labelColor = MaterialTheme.colorScheme.onTertiary,
                                ),
                            )
                        }
                    }
                    Text(
                        preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preset.cpuPolicyMaxKhz.isNotEmpty()) {
                        Text(
                            preset.cpuPolicyMaxKhz.entries.joinToString(", ") { (k, v) ->
                                "policy$k → ${v / 1000} MHz"
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (preset.cpuPolicyGovernor.isNotEmpty()) {
                        Text(
                            "governor: ${preset.cpuPolicyGovernor.values.toSet().joinToString(",")}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    preset.sourceUrl?.let { url ->
                        TextButton(onClick = { uri.openUri(url) }) {
                            Text("Source", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canApply) {
                        Button(onClick = { onAction(preset, PresetAction.APPLY) }) { Text("Apply once") }
                    }
                    if (canInstallAtBoot) {
                        OutlinedButton(onClick = { onAction(preset, PresetAction.INSTALL_AT_BOOT) }) {
                            Text("Install at boot")
                        }
                    }
                    if (canGenerateScript) {
                        // Two no-root persistence variants:
                        //   - one-shot: file in /sdcard/CalibrateSoC,
                        //     run once via Odin Settings → Run script as Root.
                        //   - + reminder: same script + a post-boot
                        //     notification nudges the user to re-fire it
                        //     after each reboot. Closest to "persistent
                        //     without root" we can get.
                        OutlinedButton(onClick = { onAction(preset, PresetAction.GENERATE_SCRIPT) }) {
                            Text("Script · one-shot")
                        }
                        OutlinedButton(onClick = { onAction(preset, PresetAction.GENERATE_SCRIPT_WITH_REMINDER) }) {
                            Text("Script · remind at boot")
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun VerificationBadge(tier: VerificationTier) {
    val (label, color) = when (tier) {
        VerificationTier.COMMUNITY_TUNED ->
            "Community Tuned" to MaterialTheme.colorScheme.tertiary
        VerificationTier.GENERIC_KNOWN_FAMILY ->
            "Built-in" to MaterialTheme.colorScheme.primary
        VerificationTier.GENERIC_UNKNOWN_FAMILY ->
            "Built-in · Unknown SoC" to MaterialTheme.colorScheme.error
        VerificationTier.USER_CUSTOM ->
            "Custom" to MaterialTheme.colorScheme.secondary
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
    )
}

// --- GPU card (full info) ------------------------------------------
//
// Sliders for the GPU are deferred to a later round — the slider
// machinery currently keys on CpuPolicyProbe.policyId and would need
// extension. For now the card surfaces everything readable so the
// user can verify what the kernel reports + see exactly what each
// built-in preset would do, without writing anything.

@Composable
private fun GpuCard(
    gpu: io.github.mayusi.calibratesoc.data.capability.GpuProbe,
    presets: List<Preset>,
) = SectionCard("GPU — ${gpu.family}") {
    if (gpu.availableFreqsHz.isEmpty()) {
        Text(
            "No OPP table — kernel doesn't expose available GPU frequencies on this device.",
            style = MaterialTheme.typography.bodySmall,
        )
        return@SectionCard
    }
    val sorted = gpu.availableFreqsHz.sorted()
    val low = sorted.first() / 1_000_000L
    val high = sorted.last() / 1_000_000L

    // Current state -------------------------------------------------
    Text("Current cap", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "${gpu.currentMinHz / 1_000_000L} – ${gpu.currentMaxHz / 1_000_000L} MHz",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyLarge,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

    // Hardware envelope --------------------------------------------
    Text("Hardware OPP table", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "$low – $high MHz · ${sorted.size} steps",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        sorted.joinToString(", ") { (it / 1_000_000L).toString() } + " MHz",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

    // Governor info ------------------------------------------------
    Text("Governor", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        gpu.currentGovernor.ifBlank { "(none reported)" },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (gpu.availableGovernors.isNotEmpty()) {
        Text(
            "Available: ${gpu.availableGovernors.joinToString(", ")}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    gpu.powerLevelRange?.let {
        Text(
            "Adreno power-level range: ${it.low}–${it.high} (0 = fastest)",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

    // Per-preset preview -----------------------------------------
    Text("What each preset would set", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val presetsWithGpu = presets.filter { it.gpuMaxHz != null }
    if (presetsWithGpu.isEmpty()) {
        Text(
            "No bundled preset writes the GPU on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        presetsWithGpu.forEach { p ->
            val mhz = (p.gpuMaxHz ?: 0L) / 1_000_000L
            val gov = p.gpuGovernor?.let { " · gov $it" } ?: ""
            Text(
                "${p.name}:  $mhz MHz$gov",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    Text(
        "Apply a preset above to write these. A direct GPU slider is on the roadmap.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Per-policy card ------------------------------------------------

@Composable
private fun PolicyCard(
    policy: CpuPolicyProbe,
    edit: TuneViewModel.PolicyEdit?,
    onChange: (TuneViewModel.PolicyEdit) -> Unit,
) = SectionCard("CPU policy ${policy.policyId} — cores ${policy.onlineCores.joinToString(",")}") {
    val freqs = policy.availableFreqsKhz
    if (freqs.isEmpty()) {
        Text("No OPP table — kernel does not expose available freqs.",
            style = MaterialTheme.typography.bodySmall)
        return@SectionCard
    }
    val low = freqs.first()
    val high = freqs.last()
    val currentMin = edit?.minKhz ?: policy.currentMinKhz
    val currentMax = edit?.maxKhz ?: policy.currentMaxKhz

    LabeledSlider(
        label = "Min freq",
        valueKhz = currentMin,
        steps = freqs,
        onChange = { snapped -> onChange((edit ?: TuneViewModel.PolicyEdit()).copy(minKhz = snapped)) },
    )
    LabeledSlider(
        label = "Max freq",
        valueKhz = currentMax,
        steps = freqs,
        onChange = { snapped -> onChange((edit ?: TuneViewModel.PolicyEdit()).copy(maxKhz = snapped)) },
    )
    Text(
        "Hardware range: ${low / 1000}–${high / 1000} MHz · ${freqs.size} steps",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (policy.availableGovernors.isNotEmpty()) {
        GovernorDropdown(
            available = policy.availableGovernors,
            current = edit?.governor ?: policy.currentGovernor,
            onChange = { onChange((edit ?: TuneViewModel.PolicyEdit()).copy(governor = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueKhz: Int,
    steps: List<Int>,
    onChange: (Int) -> Unit,
) {
    val low = steps.first().toFloat()
    val high = steps.last().toFloat()
    val sliderRange = (high - low).coerceAtLeast(1f)
    val pos = ((valueKhz.toFloat() - low) / sliderRange).coerceIn(0f, 1f)

    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${valueKhz / 1000} MHz",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = pos,
            // steps - 1 internal stops (the endpoints don't count as stops).
            steps = (steps.size - 2).coerceAtLeast(0),
            valueRange = 0f..1f,
            onValueChange = { fraction ->
                val rawKhz = low + fraction * sliderRange
                // Snap to the nearest OPP step.
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
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay catches taps because the read-only TextField
        // swallows clicks on some Compose versions.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp),
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

// --- Apply bar + results -------------------------------------------

@Composable
private fun ApplyBar(
    pendingCount: Int,
    canApply: Boolean,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
            Text("Discard")
        }
        Button(
            onClick = onApply,
            enabled = canApply && pendingCount > 0,
            modifier = Modifier.weight(2f),
        ) {
            Text(if (pendingCount > 0) "Apply ($pendingCount)" else "Apply")
        }
    }
}

@Composable
private fun ResultsCard(results: List<WriteResult>) = SectionCard("Last apply") {
    results.forEach { r ->
        val (label, color) = when (r) {
            is WriteResult.Success -> "✓ ${r.id.target.substringAfterLast('/')}: ${r.previousValue ?: "—"} → ${r.newValue}" to MaterialTheme.colorScheme.tertiary
            is WriteResult.CapabilityDenied -> "✗ ${r.id.target.substringAfterLast('/')}: ${r.reason}" to MaterialTheme.colorScheme.outline
            is WriteResult.Rejected -> "✗ ${r.id.target.substringAfterLast('/')}: ${r.message}" to MaterialTheme.colorScheme.error
            is WriteResult.Failed -> "✗ ${r.id.target.substringAfterLast('/')}: ${r.error.message ?: "failed"}" to MaterialTheme.colorScheme.error
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = color, fontFamily = FontFamily.Monospace)
    }
}

// --- First-OC confirm modal ----------------------------------------

@Composable
private fun FirstOcConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val required = "I understand"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("First-time write to kernel sysfs") },
        text = {
            Column {
                Text(
                    "You're about to change kernel CPU/GPU frequency caps. " +
                        "Calibrate SoC reverts every write at boot by default, " +
                        "and v1 never touches voltage — but you should know:",
                )
                Spacer(Modifier.height(8.dp))
                Text("• Aggressive caps can cause UI stutter or app crashes")
                Text("• Disabling thermal protection is not possible here")
                Text("• Revert to stock by rebooting, or by tapping 'Discard'")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type \"$required\" to continue") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = typed.trim() == required) {
                Text("Continue")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun BootDeployedDialog(
    deployed: AynScriptDeployer.BootDeployed,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deployed.success) "Installed at boot" else "Boot install failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (deployed.success) {
                    Text("Installed via ${deployed.manager} at:")
                    Text(deployed.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("The tune will re-apply on every boot. To remove it later, delete the file via your root manager or run a fresh Apply on a different preset.")
                } else {
                    Text(deployed.error ?: "Unknown failure.", color = MaterialTheme.colorScheme.error)
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
                        "preset \"${preset.name}\" was generated from your kernel's own OPP " +
                        "table — every frequency it writes is one the kernel already publishes " +
                        "as valid, so it shouldn't crash. But power and thermal behaviour on " +
                        "this exact silicon haven't been profiled.",
                )
                Spacer(Modifier.height(8.dp))
                Text("Sensible first move: try Battery Saver before Performance.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Help others: in Device Info, tap \"Report unknown device\" to share an " +
                        "anonymized capability report so we can ship a tuned adapter in a " +
                        "future release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Apply anyway") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Shared section card -------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// --- Display refresh-rate card -------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DisplayRefreshCard(
    viewModel: io.github.mayusi.calibratesoc.ui.tune.DisplayRefreshViewModel =
        androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val preferredHz by viewModel.preferredHz.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Live Hz indicator: polled from the Activity's display. No need
    // for adb — the user sees the panel's actual rate change after
    // they tap a chip.
    var liveHz by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refresh(context)
        // Poll once a second for ~10s after entering screen, then
        // every 3s. Catches the transition without burning battery.
        while (true) {
            val act = context as? android.app.Activity
            val display = act?.windowManager?.defaultDisplay
            liveHz = display?.refreshRate ?: 0f
            kotlinx.coroutines.delay(1000)
        }
    }

    SectionCard("Display refresh rate") {
        // Always-visible "what's the panel doing right now" line.
        // Removes the need for `adb shell dumpsys display`.
        Text(
            "Current: ${if (liveHz > 0) "%.0f Hz".format(liveHz) else "—"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (liveHz >= 119f) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface,
        )
        if (modes.isEmpty()) {
            Text(
                "No modes reported yet. Tap Re-scan after the app is fully open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { viewModel.refresh(context) }) { Text("Re-scan") }
            return@SectionCard
        }
        Text(
            "Pick a refresh rate. Many panels report more modes than the system uses by default — pinning a higher Hz here also calls Surface.setFrameRate so the OS actually honors it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            modes.forEach { mode ->
                val on = preferredHz != null && kotlin.math.abs((preferredHz ?: 0f) - mode.hz) < 0.5f
                AssistChip(
                    onClick = {
                        viewModel.pick(mode.hz)
                        val result = applyRefreshRate(context, mode.id, mode.hz, viewModel.refreshScript)
                        if (result is RefreshApplyResult.NeedsScript) {
                            val vs = io.github.mayusi.calibratesoc.data.vendor.OdinIntents
                                .vendorSettingsName(context)
                            android.widget.Toast.makeText(
                                context,
                                "Opened $vs — pick calibratesoc_${mode.hz.toInt()}hz.sh to apply",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    label = { Text(mode.displayLabel) },
                    colors = if (on) AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) else AssistChipDefaults.assistChipColors(),
                )
            }
            TextButton(onClick = {
                viewModel.pick(null)
                applyRefreshRate(context, 0, 0f, viewModel.refreshScript)
            }) { Text("Auto") }
        }
        preferredHz?.let {
            Text(
                "Pinned: ${"%.0f".format(it)} Hz (re-applied on app launch and HUD show)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * Force the live window to honor the chosen mode. Three mechanisms,
 * applied together — some Odin firmwares ignore some of them.
 *   1. preferredDisplayModeId — by-id selector. Skipped when
 *      mIgnorePreferredRefreshRate=true.
 *   2. preferredRefreshRate — by-Hz hint that bypasses the by-id
 *      ignore on most kernels.
 *   3. View.setRequestedFrameRate (Android 15+) on the decor view —
 *      the modern, framework-honored path.
 */
/**
 * Set the system's peak refresh rate. Three-tier strategy:
 *
 *   1. Direct write to Settings.System (needs WRITE_SECURE_SETTINGS,
 *      granted by the unlock script). Silent + instant when it works.
 *   2. Fallback: deploy a `settings put system peak_refresh_rate N`
 *      script and bounce the user into Odin Settings → Run script as
 *      Root. Slower but works 100% as long as the unlock script
 *      itself ran successfully.
 *   3. Window-level hint (preferredDisplayModeId / preferredRefreshRate)
 *      always applied last — harmless when the global
 *      ignore_app_preferred_refresh_rate_request flag suppresses it.
 *
 * Returns a [RefreshApplyResult] so the caller can show a toast /
 * dialog if we needed the script path.
 */
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
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openOdinSettings(context)
        RefreshApplyResult.NeedsScript(deployed.path, deployed.visibleToOdinPicker)
    } else {
        RefreshApplyResult.Direct
    }

    // Always nudge the live window too — harmless if the system ignores it.
    val activity = context as? android.app.Activity
    if (activity != null) {
        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = modeId
        if (hz > 0f) attrs.preferredRefreshRate = hz
        activity.window.attributes = attrs
    }
    return result
}

/** Kept for MainActivity which doesn't have access to the script
 *  generator — boot-time re-apply only needs the direct path. */
private fun applyRefreshRate(context: android.content.Context, modeId: Int, hz: Float) {
    applyRefreshRate(context, modeId, hz, refreshScript = null)
}

// --- Advanced unlock card (one-time root grant for FPS + direct write)

@Composable
private fun AdvancedUnlockCard(
    viewModel: io.github.mayusi.calibratesoc.ui.tune.AdvancedUnlockViewModel =
        androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val grants by viewModel.grants.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastPath by remember { mutableStateOf<String?>(null) }
    val vs = remember {
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.vendorSettingsName(context)
    }

    SectionCard("Advanced unlock") {
        Text(
            "Tuning already works — presets generate a script you run as root (no setup needed). " +
                "This section only unlocks OPTIONAL extras: live FPS overlay, vendor-key writes, " +
                "and the experimental instant ± HUD steppers. Most devices won't have the " +
                "permissive firmware the last one needs, and that's fine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Two settings unlock everything:\n\n" +
                "1. $vs → toggle \"Force SELinux\" ON (if your firmware has it). This puts the vendor service in a permissive domain so it accepts shell commands from our app.\n" +
                "2. Generate + Run the unlock script (below) once per boot to grant DUMP / USAGE / SECURE permissions.\n\n" +
                "When both are done, the SYSFS indicator goes green and the experimental HUD ± buttons can fire instant clock changes. Optional — skip it if you only use presets.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GrantIndicator("DUMP", grants.dump, "fg app")
            GrantIndicator("USAGE", grants.usageStats, "app label")
            GrantIndicator("SECURE", grants.writeSecureSettings, "vendor keys")
            GrantIndicator("SYSFS", grants.sysfsWritable, "instant ± (optional)")
        }
        if (grants.allHeld) {
            Text(
                "All three granted — HUD already showing live data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val deployed = viewModel.deployScript()
                lastPath = deployed.path
                io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openOdinSettings(context)
            }) {
                Text(if (grants.anyHeld) "Re-run unlock" else "Generate + Open $vs")
            }
            OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh status") }
        }
        lastPath?.let {
            Text(
                "Wrote $it",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun GrantIndicator(label: String, on: Boolean, hint: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (on) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (on) "✓ $hint" else "— $hint",
            style = MaterialTheme.typography.labelSmall,
            color = if (on) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

