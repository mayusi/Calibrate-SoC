package io.github.mayusi.calibratesoc.ui.autotdp

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpRunState
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.autotdp.BatteryTarget
import io.github.mayusi.calibratesoc.data.autotdp.SavingsResult
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
import io.github.mayusi.calibratesoc.data.vendor.OdinIntents
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.components.StatTile
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * AutoTDP screen — the full AutoTDP + companion surface.
 *
 * Top-level sections (in order):
 *  1. Rung banner (LIVE / SCRIPT / ADVISORY) — always visible, honesty-critical.
 *  2. Main On/Off card (LIVE rung) or Generate-script card (SCRIPT/ADVISORY).
 *  3. Live state card — current decision reason, parked cores, draw delta.
 *  4. Efficiency Curve Finder entry.
 *  5. Battery Target input (when BATTERY_TARGET profile is selected).
 *  6. Companion toggles: idle/charge trigger + per-app map.
 */
@Composable
fun AutoTdpScreen(
    onBack: () -> Unit = {},
    viewModel: AutoTdpViewModel = hiltViewModel(),
) {
    val rung by viewModel.rung.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val targetHours by viewModel.targetHours.collectAsStateWithLifecycle()
    val batteryTargetResult by viewModel.batteryTargetResult.collectAsStateWithLifecycle()
    val sweepProgress by viewModel.sweepProgress.collectAsStateWithLifecycle()
    val kneeKhz by viewModel.kneeKhz.collectAsStateWithLifecycle()
    val idleChargeTriggerEnabled by viewModel.idleChargeTriggerEnabled.collectAsStateWithLifecycle()
    val manuallyOn by viewModel.manuallyOn.collectAsStateWithLifecycle()
    val lastScriptDeploy by viewModel.lastScriptDeploy.collectAsStateWithLifecycle()
    val perAppMap by viewModel.perAppMap.collectAsStateWithLifecycle()
    val showPServerUnlockCta by viewModel.showPServerUnlockCta.collectAsStateWithLifecycle()
    val lastUnlockDeploy by viewModel.lastUnlockDeploy.collectAsStateWithLifecycle()
    val liveSnapshot by viewModel.liveSnapshot.collectAsStateWithLifecycle()

    var showPerAppDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        // ── 1. Header ────────────────────────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AutoTDP",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
            }
        }

        // ── 2. Rung banner ────────────────────────────────────────────────────
        item { RungBanner(rung = rung, runState = runState) }

        // ── 2a. PServer unlock CTA (AYN/Odin devices not yet whitelisted) ────
        // Shown only when the device could be LIVE via PServer but the one-time
        // unlock script has not been run yet. Dismissed once pserverSysfsLive flips.
        if (showPServerUnlockCta) {
            item {
                PServerUnlockCtaCard(
                    lastDeploy = lastUnlockDeploy,
                    onDeploy = { viewModel.deployUnlockScript() },
                    onRefresh = { viewModel.refreshCapability() },
                    onDismiss = { viewModel.clearUnlockDeploy() },
                )
            }
        }

        // ── 3. Main control ───────────────────────────────────────────────────
        item {
            when (rung) {
                AutoTdpRung.LIVE -> LiveControlCard(
                    isOn = manuallyOn,
                    runState = runState,
                    selectedProfile = selectedProfile,
                    onToggle = { viewModel.setManualEnabled(it) },
                    onProfileSelected = { viewModel.selectProfile(it) },
                )
                AutoTdpRung.SCRIPT, AutoTdpRung.ADVISORY -> ScriptControlCard(
                    rung = rung,
                    selectedProfile = selectedProfile,
                    onProfileSelected = { viewModel.selectProfile(it) },
                    onGenerateScript = { viewModel.generateEfficiencyScript() },
                )
            }
        }

        // ── 4. Battery target input (when selected) ────────────────────────────
        if (selectedProfile == AutoTdpProfile.BATTERY_TARGET) {
            item {
                BatteryTargetCard(
                    targetHours = targetHours,
                    result = batteryTargetResult,
                    onTargetHoursChanged = { viewModel.setTargetHours(it) },
                )
            }
        }

        // ── 5. Live state card ─────────────────────────────────────────────────
        if (runState.status != AutoTdpStatus.IDLE) {
            item { LiveStateCard(runState = runState, liveSnapshot = liveSnapshot) }
        }

        // ── 6. Efficiency Curve Finder ─────────────────────────────────────────
        item {
            EfficiencyCurveCard(
                sweepState = sweepProgress,
                kneeKhz = kneeKhz,
                onStart = { viewModel.startEfficiencySweep() },
                onCancel = { viewModel.cancelSweep() },
            )
        }

        // ── 7. Last script deploy (SCRIPT rung) ───────────────────────────────
        lastScriptDeploy?.let { deployed ->
            item {
                LastScriptDeployCard(
                    deployed = deployed,
                    onDismiss = { viewModel.clearLastScriptDeploy() },
                )
            }
        }

        // ── 8. Companion toggles ──────────────────────────────────────────────
        item {
            CompanionTogglesCard(
                idleChargeTriggerEnabled = idleChargeTriggerEnabled,
                perAppMapCount = perAppMap.size,
                onIdleChargeToggle = { viewModel.setIdleChargeTriggerEnabled(it) },
                onOpenPerApp = { showPerAppDialog = true },
            )
        }

        // Bottom padding
        item { Spacer(Modifier.height(16.dp)) }
    }

    // Per-app efficiency dialog
    if (showPerAppDialog) {
        PerAppEfficiencyDialog(
            currentMap = perAppMap,
            onSetProfile = { pkg, profile -> viewModel.setPerAppProfile(pkg, profile) },
            onDismiss = { showPerAppDialog = false },
        )
    }
}

// ─── Rung Banner ─────────────────────────────────────────────────────────────

/**
 * Always-visible rung disclosure. Tells the user exactly what AutoTDP can
 * and cannot do on this device. Never shows a fake control tier.
 *
 * For the LIVE rung the body text adapts to the specific live path active:
 *   - PServer (no root needed — AYN/Odin vendor service): most common live path.
 *   - sysfs directly writable (one-time unlock + SELinux permissive).
 *   - Root (full kernel access).
 */
@Composable
private fun RungBanner(rung: AutoTdpRung, runState: AutoTdpRunState) {
    // Derive a more specific LIVE body from the run-state when available.
    // runState.liveAvailable == true after the first RUNNING tick; before that,
    // we fall back to a generic LIVE body (the rung resolved to LIVE from the
    // capability report, so we know at least one live path is valid).
    val liveBody = if (runState.liveAvailable || runState.status == AutoTdpStatus.RUNNING) {
        // Post-start: we know exactly which path the daemon is using.
        // The daemon doesn't currently annotate WHICH path it used, so we rely on
        // the run-state being RUNNING as confirmation that writes are working.
        "Live control confirmed — daemon is writing every second. " +
            "Prime cores park when GPU-bound; big cluster is capped at the efficiency knee; " +
            "GPU is prioritised. All writes revert automatically on stop."
    } else {
        // Pre-start: capability report says LIVE but daemon hasn't run yet.
        // We still say LIVE confidently — the probe confirmed a real write path.
        "Sysfs nodes are live-writable on this device (via PServer vendor service, " +
            "direct sysfs access, or root). AutoTDP runs a real closed-loop daemon: " +
            "parks prime cores when GPU-bound, caps the big cluster at the efficiency knee, " +
            "and reverts everything on stop. No root required when using the PServer path."
    }

    val (icon, labelText, bodyText) = when (rung) {
        AutoTdpRung.LIVE -> Triple(Icons.Outlined.PowerSettingsNew, "RUNG: LIVE", liveBody)
        AutoTdpRung.SCRIPT -> Triple(
            Icons.Outlined.Code,
            "RUNG: SCRIPT",
            "Live auto-adjust needs the one-time unlock (see below) or root. " +
                "This rung generates the best static efficiency tune as a shell script — " +
                "run it once via your device's 'Run script as Root'. No live loop; " +
                "settings are static until rebooted or a new script is run.",
        )
        AutoTdpRung.ADVISORY -> Triple(
            Icons.Outlined.Info,
            "RUNG: ADVISORY",
            "No write access on this device. AutoTDP can read live telemetry and show " +
                "efficiency advice (e.g. \"GPU-bound — parking 2 prime cores would save ~X mW\"), " +
                "but cannot apply any changes. Run the unlock script or use root to reach " +
                "SCRIPT or LIVE.",
        )
    }

    // If the daemon itself confirmed writes are unavailable despite our LIVE probe,
    // append that confirmation so the user isn't confused.
    val liveSuffix = if (runState.status == AutoTdpStatus.LIVE_UNAVAILABLE) {
        "\n\nDaemon confirmed: ${runState.liveUnavailableReason ?: "live writes not available — check sysfs permissions."}"
    } else ""

    val containerColor = when (rung) {
        AutoTdpRung.LIVE -> MaterialTheme.colorScheme.tertiaryContainer
        AutoTdpRung.SCRIPT -> MaterialTheme.colorScheme.secondaryContainer
        AutoTdpRung.ADVISORY -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (rung) {
        AutoTdpRung.LIVE -> MaterialTheme.colorScheme.onTertiaryContainer
        AutoTdpRung.SCRIPT -> MaterialTheme.colorScheme.onSecondaryContainer
        AutoTdpRung.ADVISORY -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.card),
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp).padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    labelText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
                Text(
                    bodyText + liveSuffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

// ─── PServer unlock CTA ───────────────────────────────────────────────────────

/**
 * Shown on AYN/Odin devices where PServer is present but our package is NOT
 * yet in the whitelist. After the user runs the unlock script once, the next
 * capability refresh will set [CapabilityReport.pserverSysfsLive]=true and
 * the rung will flip to LIVE automatically — no root, no per-boot steps.
 *
 * The script is honest: it's plain text the user can audit before running.
 * The CTA disappears once [showPServerUnlockCta] becomes false (rung flipped).
 */
@Composable
private fun PServerUnlockCtaCard(
    lastDeploy: AdvancedPermissionsScript.Deployed?,
    onDeploy: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vendorName = remember {
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.vendorSettingsName(context)
    }

    SectionCard(
        title = "One-Time Unlock → LIVE",
        icon = Icons.Outlined.LockOpen,
    ) {
        Text(
            "This device has AYN's PServer vendor service — which lets Calibrate SoC " +
                "write CPU clocks and GPU levels with root authority, no Magisk needed. " +
                "Run the unlock script ONCE via $vendorName → Run script as Root. " +
                "After that, AutoTDP controls your clocks live until the next reboot. " +
                "Re-running the script after a reboot re-enables it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.dense))

        // What the script does — honesty-first
        AlertCard(
            type = AlertType.INFO,
            title = "What the script does",
            message = "• Adds this app to PServer's app_whiteList (Settings.System key)\n" +
                "• Grants DUMP + PACKAGE_USAGE_STATS + WRITE_SECURE_SETTINGS (persist across reboot)\n" +
                "• chmod 666 on cpufreq/GPU/governor sysfs nodes (requires Force SELinux ON; resets at reboot)\n" +
                "The script is plain text — you can read it before running.",
        )

        Spacer(Modifier.height(Spacing.group))

        if (lastDeploy == null) {
            // No deploy yet — show the primary action
            Button(
                onClick = onDeploy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.group))
                Text("Generate unlock script")
            }
        } else {
            // Script has been generated — guide them through running it
            val fileName = remember(lastDeploy.path) { lastDeploy.path.substringAfterLast('/') }
            Text(
                fileName,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (lastDeploy.visibleToOdinPicker) {
                Text(
                    "1. Tap 'Open $vendorName' below\n" +
                        "2. Tap 'Run script as Root'\n" +
                        "3. Pick the .sh from the CalibrateSoC folder\n" +
                        "4. Tap 'Check again' once it finishes — the rung should flip to LIVE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Script saved to app-private folder. Copy to /sdcard/CalibrateSoC/ manually " +
                        "or grant storage permission, then run via $vendorName → Run script as Root.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(Spacing.group))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
                if (lastDeploy.visibleToOdinPicker) {
                    Button(onClick = {
                        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.openVendorSettings(context)
                    }) { Text("Open $vendorName") }
                }
                FilledTonalButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Check again")
                }
                TextButton(onClick = {
                    onDismiss()
                }) { Text("Dismiss") }
            }
        }
    }
}

// ─── LIVE control card ────────────────────────────────────────────────────────

@Composable
private fun LiveControlCard(
    isOn: Boolean,
    runState: AutoTdpRunState,
    selectedProfile: AutoTdpProfile,
    onToggle: (Boolean) -> Unit,
    onProfileSelected: (AutoTdpProfile) -> Unit,
) = SectionCard(
    title = "AutoTDP Control",
    icon = Icons.Outlined.PowerSettingsNew,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                if (isOn) "On" else "Off",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    isOn && runState.status == AutoTdpStatus.RUNNING -> "Daemon active — writing every second"
                    isOn -> "Starting…"
                    else -> "Stopped — all kernel writes reverted"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isOn,
            onCheckedChange = onToggle,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

    Text(
        "Profile",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProfilePicker(
        selected = selectedProfile,
        onSelected = onProfileSelected,
    )
}

// ─── SCRIPT / ADVISORY control card ──────────────────────────────────────────

@Composable
private fun ScriptControlCard(
    rung: AutoTdpRung,
    selectedProfile: AutoTdpProfile,
    onProfileSelected: (AutoTdpProfile) -> Unit,
    onGenerateScript: () -> Unit,
) = SectionCard(
    title = if (rung == AutoTdpRung.SCRIPT) "Generate Efficiency Tune" else "Efficiency Advisor",
    icon = Icons.Outlined.Code,
) {
    if (rung == AutoTdpRung.SCRIPT) {
        Text(
            "Generates the best static efficiency tune for your device as a one-shot script — " +
                "parks prime cores, caps big-cluster at the efficiency knee (or heuristic ~67%), " +
                "prioritises GPU. Run via your device's 'Run script as Root'. " +
                "Re-run after each reboot (changes don't survive without the boot-install).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.dense))

        Text(
            "Profile (determines aggressiveness)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // BATTERY_TARGET makes no sense for a static script — filter it out.
        ProfilePicker(
            selected = selectedProfile.takeIf { it != AutoTdpProfile.BATTERY_TARGET }
                ?: AutoTdpProfile.EFFICIENCY,
            onSelected = { if (it != AutoTdpProfile.BATTERY_TARGET) onProfileSelected(it) },
            excludeBatteryTarget = true,
        )
        Spacer(Modifier.height(Spacing.group))
        Button(
            onClick = onGenerateScript,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.group))
            Text("Generate efficiency tune script")
        }
    } else {
        // ADVISORY: no writes at all
        AlertCard(
            type = AlertType.INFO,
            title = "Advisory mode",
            message = "This device has no writable sysfs nodes for AutoTDP. " +
                "The efficiency analysis below shows what AutoTDP would do if writes were available. " +
                "Run the unlock script (Advanced Tuning → unlock sysfs) to move to SCRIPT rung, " +
                "or enable root mode (Settings) for the LIVE rung.",
        )
    }
}

// ─── Profile picker ──────────────────────────────────────────────────────────

@Composable
private fun ProfilePicker(
    selected: AutoTdpProfile,
    onSelected: (AutoTdpProfile) -> Unit,
    excludeBatteryTarget: Boolean = false,
) {
    val profiles = if (excludeBatteryTarget) {
        listOf(AutoTdpProfile.EFFICIENCY, AutoTdpProfile.BALANCED)
    } else {
        listOf(AutoTdpProfile.EFFICIENCY, AutoTdpProfile.BALANCED, AutoTdpProfile.BATTERY_TARGET)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
        profiles.forEach { profile ->
            FilterChip(
                selected = selected == profile,
                onClick = { onSelected(profile) },
                label = {
                    Text(
                        when (profile) {
                            AutoTdpProfile.EFFICIENCY -> "Efficiency"
                            AutoTdpProfile.BALANCED -> "Balanced"
                            AutoTdpProfile.BATTERY_TARGET -> "Battery Target"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }

    // Brief profile description
    Text(
        when (selected) {
            AutoTdpProfile.EFFICIENCY ->
                "Aggressive: parks prime cores, caps big-cluster at ~67% of max. Best for GPU-bound emulation."
            AutoTdpProfile.BALANCED ->
                "Mild: parks prime cores on GPU-bound load, caps big at ~75%. Good for mixed workloads."
            AutoTdpProfile.BATTERY_TARGET ->
                "Target mode: you set desired battery life; AutoTDP computes the cap needed to hit it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─── Battery Target card ──────────────────────────────────────────────────────

@Composable
private fun BatteryTargetCard(
    targetHours: Double,
    result: BatteryTarget.BatteryTargetResult?,
    onTargetHoursChanged: (Double) -> Unit,
) = SectionCard(title = "Battery Target", icon = Icons.Outlined.BatteryChargingFull) {
    Text(
        "Set how many hours you want. AutoTDP computes the big-cluster cap that would " +
            "sustain that life at the current draw. The result below is an estimate — " +
            "real life depends on your actual workload.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.dense))

    var hoursText by remember(targetHours) { mutableStateOf("%.1f".format(targetHours)) }
    OutlinedTextField(
        value = hoursText,
        onValueChange = { text ->
            hoursText = text
            text.toDoubleOrNull()?.takeIf { it > 0 }?.let { onTargetHoursChanged(it) }
        },
        label = { Text("Target hours") },
        suffix = { Text("h") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    result?.let { r ->
        Spacer(Modifier.height(Spacing.dense))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(Spacing.dense))

        // Honesty note always shown first
        Text(
            r.honestyNote,
            style = MaterialTheme.typography.bodySmall,
            color = if (r.achievable) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.secondary,
        )

        if (r.achievable && r.mappedCapKhz != null) {
            Spacer(Modifier.height(Spacing.dense))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                StatTile(
                    label = "Required cap",
                    value = "${r.mappedCapKhz / 1000}",
                    unit = "MHz",
                )
                StatTile(
                    label = "Budget",
                    value = "%.1f".format(r.budgetW),
                    unit = "W",
                )
            }
        }
    }
}

// ─── Live state card ──────────────────────────────────────────────────────────

/**
 * Shown when the daemon is running (or in a terminal state).
 * Displays: current decision reason, parked cores, cap, draw delta,
 * and a "Live now" mini-grid fed from [liveSnapshot].
 * Draw savings are ALWAYS labeled "measured on your device, this session"
 * and NEVER shown until [SavingsResult.enoughData] is true.
 */
@Composable
private fun LiveStateCard(
    runState: AutoTdpRunState,
    liveSnapshot: AutoTdpViewModel.LiveSnapshot?,
) = SectionCard(
    title = "Live State",
    icon = Icons.Outlined.QueryStats,
) {
    when (runState.status) {
        AutoTdpStatus.RUNNING -> RunningStateContent(runState, liveSnapshot)
        AutoTdpStatus.KILLED_BY_SAFETY -> AlertCard(
            type = AlertType.WARNING,
            title = "Stopped by safety guard",
            message = runState.killReason
                ?: "AutoTDP stopped itself. All kernel writes have been reverted.",
        )
        AutoTdpStatus.WRITE_DENIED -> AlertCard(
            type = AlertType.ERROR,
            title = "Kernel write denied",
            message = runState.writeFailure
                ?: "A mid-run write was rejected by the kernel. All writes reverted. " +
                "Try the unlock script or root.",
        )
        AutoTdpStatus.STOPPED -> Text(
            "Stopped cleanly. All kernel writes reverted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AutoTdpStatus.LIVE_UNAVAILABLE -> AlertCard(
            type = AlertType.INFO,
            title = "Live writes unavailable",
            message = runState.liveUnavailableReason
                ?: "This device cannot do live sysfs writes. Use the SCRIPT rung instead.",
        )
        AutoTdpStatus.IDLE -> Unit // nothing to show
    }
}

@Composable
private fun RunningStateContent(
    runState: AutoTdpRunState,
    liveSnapshot: AutoTdpViewModel.LiveSnapshot?,
) {
    val state = runState.appliedState

    // ── "Live now" mini-grid ──────────────────────────────────────────────────
    // Shows four at-a-glance telemetry tiles sourced from MonitorService.
    // Always rendered at the top of RunningStateContent; individual tiles show
    // "–" when the corresponding telemetry field is null.
    LiveNowMiniGrid(snapshot = liveSnapshot)

    Spacer(Modifier.height(Spacing.dense))

    // Decision reason — the engine's human-readable explanation of the current action.
    // Shown monospace so core indices / MHz values line up cleanly.
    if (runState.lastReason.isNotBlank()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Text(
                runState.lastReason,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(Spacing.group),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(Spacing.dense))
    }

    // Applied kernel state — what is actually written to the kernel right now.
    if (state != null) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Current kernel writes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.dense))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            if (state.parkedPrimeCores.isNotEmpty()) {
                KvRow(
                    label = "Parked prime cores",
                    value = state.parkedPrimeCores.sorted().joinToString(", ") { "cpu$it" },
                    explainer = "Offlined to shed thermal load and cut power draw.",
                )
            } else {
                KvRow(
                    label = "Prime cores",
                    value = "all online",
                    explainer = "No parking — CPU-bound or load below threshold.",
                )
            }
            state.bigClusterCapKhz?.let { cap ->
                KvRow(
                    label = "Big-cluster cap",
                    value = "${cap / 1000} MHz",
                    explainer = "Frequency ceiling for big/prime cluster. Efficiency knee or target.",
                )
            }
            state.gpuFloorLevel?.let { lvl ->
                KvRow(
                    label = "GPU max power level",
                    value = "$lvl${if (lvl == 0) " (max performance)" else ""}",
                    explainer = "0 = fastest; higher = slower. AutoTDP keeps GPU fast when CPU is parked.",
                )
            }
            if (state.governorOverrides.isNotEmpty()) {
                KvRow(
                    label = "Governor overrides",
                    value = state.governorOverrides.entries.joinToString(", ") { (p, g) -> "policy$p→$g" },
                )
            }
        }
    }

    // Measured draw savings — NEVER fabricated. Shown prominently when data is ready.
    Spacer(Modifier.height(Spacing.group))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    Spacer(Modifier.height(Spacing.group))
    DrawSavingsBlock(savings = runState.savings)
}

/**
 * Renders the measured draw-delta with honest labeling.
 * Shows "measuring…" until [SavingsResult.enoughData] is true.
 * Never shows a fabricated number — always labeled "measured on your device, this session".
 *
 * When data is available, the saving is shown prominently as both absolute (mW)
 * and percentage so the user can trust it is real.
 */
@Composable
private fun DrawSavingsBlock(savings: SavingsResult?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Draw savings",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Measured on your device, this session",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(Spacing.dense))

    when {
        savings == null -> {
            Text(
                "Measuring baseline… sampling battery draw before AutoTDP enables (~20 s). " +
                    "Keep the same workload running for an accurate comparison.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !savings.enoughData -> {
            LinearProgressIndicator(
                progress = {
                    savings.sampleCount.toFloat() / io.github.mayusi.calibratesoc.data.autotdp.AutoTdpSavings.MIN_SAMPLES_FOR_REPORT
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "Measuring… (${savings.sampleCount} / ${io.github.mayusi.calibratesoc.data.autotdp.AutoTdpSavings.MIN_SAMPLES_FOR_REPORT} samples)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val saving = savings.deltaMw
            val pct = kotlin.math.abs(savings.deltaPct)
            val isSaving = saving > 0
            val highlightColor = if (isSaving) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error

            // Prominent saving headline
            if (isSaving) {
                Text(
                    "Saving ~${saving} mW / ${"%.1f".format(pct)}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = highlightColor,
                )
            }

            Spacer(Modifier.height(Spacing.dense))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                StatTile(label = "Baseline", value = "${savings.baselineMw}", unit = "mW")
                StatTile(label = "With AutoTDP", value = "${savings.tunedMw}", unit = "mW")
                val deltaLabel = if (isSaving) "-$saving" else "+${-saving}"
                StatTile(
                    label = "Delta",
                    value = "$deltaLabel mW",
                    valueColor = highlightColor,
                )
            }
            Spacer(Modifier.height(Spacing.dense))
            Text(
                "${savings.sampleCount} samples — measured on this device, this session. " +
                    "Keep the same workload running for a stable reading.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Live now mini-grid ───────────────────────────────────────────────────────

/**
 * Compact 2×2 grid of live telemetry tiles shown at the top of [RunningStateContent].
 *
 * Sources: [AutoTdpViewModel.liveSnapshot] — updated every MonitorService tick (1 Hz).
 * Fields show "–" when the corresponding sensor value is unavailable.
 *
 * Tiles:
 *   CPU max MHz  |  GPU MHz
 *   Hot °C       |  Draw W
 */
@Composable
private fun LiveNowMiniGrid(snapshot: AutoTdpViewModel.LiveSnapshot?) {
    Text(
        "Live now",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.dense))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        val cpuMhz = snapshot?.cpuMaxMhz?.toString() ?: "–"
        val gpuMhz = snapshot?.gpuMhz?.toString() ?: "–"
        val tempC  = snapshot?.hottestTempC?.toString() ?: "–"
        val drawW  = snapshot?.batteryDrawW?.let { "%.1f".format(it) } ?: "–"
        StatTile(label = "CPU max", value = cpuMhz, unit = if (snapshot?.cpuMaxMhz != null) "MHz" else null, modifier = Modifier.weight(1f))
        StatTile(label = "GPU",     value = gpuMhz, unit = if (snapshot?.gpuMhz   != null) "MHz" else null, modifier = Modifier.weight(1f))
        StatTile(label = "Hot",     value = tempC,  unit = if (snapshot?.hottestTempC != null) "°C" else null, modifier = Modifier.weight(1f))
        StatTile(label = "Draw",    value = drawW,  unit = if (snapshot?.batteryDrawW != null) "W"  else null, modifier = Modifier.weight(1f))
    }
}

// ─── Efficiency Curve Finder ──────────────────────────────────────────────────

@Composable
private fun EfficiencyCurveCard(
    sweepState: SweepUiState,
    kneeKhz: Int?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) = SectionCard(title = "Efficiency Curve Finder", icon = Icons.Outlined.Search) {
    Text(
        "Steps the big-cluster cap down through its OPP table under a fixed synthetic load, " +
            "measures performance and battery draw at each step, then finds the 'knee' — " +
            "the cap with the best perf-per-watt on your exact silicon. " +
            "Takes ~${estimateSweepMinutes(sweepState)} minutes. Run while discharging for accurate draw readings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    kneeKhz?.let { knee ->
        Spacer(Modifier.height(Spacing.dense))
        Text(
            "Knee from last sweep: ${knee / 1000} MHz (approximate, measured on your device)",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "This cap is used as the efficiency target in the active profile and script generation.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(Spacing.dense))

    when (val s = sweepState) {
        is SweepUiState.Idle -> {
            OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.group))
                Text("Run efficiency sweep")
            }
        }
        is SweepUiState.Running -> {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    "Measuring step ${s.stepIndex} / ${s.totalSteps} — ${s.currentCapKhz / 1000} MHz cap",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(
                    progress = { if (s.totalSteps > 0) s.stepIndex.toFloat() / s.totalSteps else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onCancel) { Text("Cancel sweep") }
            }
        }
        is SweepUiState.Done -> {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                Text(
                    s.result.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.dense))
                // Bar chart: one row per OPP step, bar width proportional to perf/W,
                // knee highlighted in tertiary (emerald in the dark theme), others muted.
                val ranked = remember(s.result) {
                    AutoTdpViewModel.rankOppPoints(s.result.points, s.result.knee?.capKhz)
                }
                ranked.forEach { pt -> OppBarRow(pt) }
                Text(
                    "Measured on your device, approximate. " +
                        "Bar width = perf/W relative to best step.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.dense))
                OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-run sweep")
                }
            }
        }
        is SweepUiState.Failed -> {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                AlertCard(
                    type = AlertType.WARNING,
                    title = "Sweep failed",
                    message = s.reason,
                )
                OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry sweep")
                }
            }
        }
    }
}

/**
 * One horizontal bar row for an OPP efficiency-curve step.
 *
 * Layout:
 *   [capMhz label]  [████████░░  bar fills to ppwFraction]  [ppW value + draw]
 *
 * Knee / best step:  bar + MHz label rendered in [MaterialTheme.colorScheme.tertiary].
 * Other steps:       bar rendered at 30 % alpha of onSurfaceVariant (muted).
 *
 * The bar is purely visual — no accessibility content needed beyond the text labels.
 */
@Composable
private fun OppBarRow(pt: RankedOppPoint) {
    val isKnee = pt.isKnee
    val barColor = if (isKnee) MaterialTheme.colorScheme.tertiary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val labelColor = if (isKnee) MaterialTheme.colorScheme.tertiary
                     else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.group),
    ) {
        // MHz label (fixed width via weight so bars align)
        Text(
            text = "${pt.capMhz}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = labelColor,
            modifier = Modifier.width(48.dp),
        )
        // Bar track
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(5.dp),
                ),
        ) {
            if (pt.ppwFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pt.ppwFraction)
                        .height(10.dp)
                        .background(color = barColor, shape = RoundedCornerShape(5.dp)),
                )
            }
        }
        // ppW + draw label
        Text(
            text = "${"%.1f".format(pt.perfPerWatt)} p/W  ${pt.drawMw}mW",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = labelColor,
        )
    }
}

private fun estimateSweepMinutes(state: SweepUiState): String {
    val steps = when (state) {
        is SweepUiState.Running -> state.totalSteps
        else -> 8 // typical OPP table size
    }
    // 4s per step + overhead
    val totalSeconds = steps * 5
    return if (totalSeconds < 60) "<1" else "${totalSeconds / 60}"
}

// ─── Last script deploy card ──────────────────────────────────────────────────

@Composable
private fun LastScriptDeployCard(
    deployed: AynScriptDeployer.Deployed,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val vendorName = remember {
        io.github.mayusi.calibratesoc.data.vendor.OdinIntents.vendorSettingsName(context)
    }
    SectionCard(title = "Script generated", icon = Icons.Outlined.Code) {
        val fileName = remember(deployed.path) { deployed.path.substringAfterLast('/') }
        Text(fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        if (deployed.visibleToOdinPicker) {
            Text(
                "1. Tap 'Open $vendorName' (or navigate there manually)\n" +
                    "2. Tap 'Run script as Root'\n" +
                    "3. Pick the .sh from the CalibrateSoC folder\n" +
                    "Re-run after each reboot (changes don't survive without boot-install).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Script saved to app-private folder — copy to /sdcard/CalibrateSoC/ manually " +
                    "or grant storage permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.group)) {
            if (deployed.visibleToOdinPicker) {
                Button(onClick = {
                    OdinIntents.openVendorSettings(context)
                }) { Text("Open $vendorName") }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

// ─── Companion toggles ────────────────────────────────────────────────────────

@Composable
private fun CompanionTogglesCard(
    idleChargeTriggerEnabled: Boolean,
    perAppMapCount: Int,
    onIdleChargeToggle: (Boolean) -> Unit,
    onOpenPerApp: () -> Unit,
) = SectionCard(title = "Companion Features", icon = Icons.Outlined.TipsAndUpdates) {
    // Idle/charge auto-downclock
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Idle / charge auto-downclock", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Automatically applies EFFICIENCY floor when screen turns off or device is charging. " +
                    "Restores on screen-on / unplug. Never silent — toggle is always shown here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.group))
        Switch(
            checked = idleChargeTriggerEnabled,
            onCheckedChange = onIdleChargeToggle,
        )
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

    // Per-app efficiency map
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Per-app AutoTDP profiles", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Bind an AutoTDP profile to a specific app — e.g. this emulator → EFFICIENCY, " +
                    "this 3D game → BALANCED. Applies automatically when that app is in the foreground.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (perAppMapCount > 0) {
                Text(
                    "$perAppMapCount app${if (perAppMapCount == 1) "" else "s"} mapped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(Spacing.group))
        OutlinedButton(onClick = onOpenPerApp) { Text("Edit") }
    }
}

// ─── Per-app efficiency dialog ────────────────────────────────────────────────

@Composable
private fun PerAppEfficiencyDialog(
    currentMap: Map<String, AutoTdpProfile>,
    onSetProfile: (String, AutoTdpProfile?) -> Unit,
    onDismiss: () -> Unit,
) {
    var packageInput by remember { mutableStateOf("") }
    var selectedProfile by remember { mutableStateOf(AutoTdpProfile.EFFICIENCY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Per-app AutoTDP profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Map a package name to an AutoTDP profile. When that app is in the foreground " +
                        "and AutoTDP is not manually ON, this profile is automatically applied.",
                    style = MaterialTheme.typography.bodySmall,
                )

                // Show current map
                if (currentMap.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Current mappings:", style = MaterialTheme.typography.labelMedium)
                    currentMap.forEach { (pkg, profile) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pkg.substringAfterLast('.'), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text(profile.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { onSetProfile(pkg, null) }) { Text("Remove") }
                        }
                    }
                    HorizontalDivider()
                }

                // Add new mapping
                OutlinedTextField(
                    value = packageInput,
                    onValueChange = { packageInput = it },
                    label = { Text("Package name (e.g. com.example.game)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ProfilePicker(
                    selected = selectedProfile,
                    onSelected = { selectedProfile = it },
                    excludeBatteryTarget = true,
                )

                Button(
                    onClick = {
                        val pkg = packageInput.trim()
                        if (pkg.isNotEmpty()) {
                            onSetProfile(pkg, selectedProfile)
                            packageInput = ""
                        }
                    },
                    enabled = packageInput.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add mapping") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}
