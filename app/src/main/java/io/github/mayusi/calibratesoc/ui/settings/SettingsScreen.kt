package io.github.mayusi.calibratesoc.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier

/**
 * Settings screen. Surfaces:
 *   - Privilege-tier badge with explainer.
 *   - Accessibility-service grant status + Settings deep-link.
 *   - App version + license + repo link.
 *
 * Many of the toggles called out in the design (boot-restore default,
 * telemetry opt-in, theme) live closer to per-profile / per-feature
 * UX in v1. Settings is intentionally short — only the state that
 * doesn't have a natural home elsewhere.
 */
@Composable
fun SettingsScreen(
    onOpenDeviceInfo: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val capability by viewModel.capability.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.accessibilityGranted.collectAsStateWithLifecycle()
    val rootModeEnabled by viewModel.rootModeEnabled.collectAsStateWithLifecycle()
    val rootDetected by viewModel.rootDetected.collectAsStateWithLifecycle()
    val baseline by viewModel.baseline.collectAsStateWithLifecycle()
    val restoreSummary by viewModel.restoreSummary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var pendingFactoryConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }

        // Setup checklist — same items as the first-launch wizard,
        // surfaced here so users can re-grant after a wipe / system
        // change without restarting the wizard. Live status updates
        // every second.
        item { SetupChecklistCard() }

        // Experimental features — gates the HUD ± steppers etc.
        // Default OFF. Flip-to-ON shows a typed-confirm modal so
        // the user owns any consequences.
        item {
            ExperimentalCard(
                enabled = viewModel.experimentalEnabled.collectAsStateWithLifecycle().value,
                onToggle = viewModel::setExperimentalEnabled,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privilege tier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val tier = capability?.privilege ?: PrivilegeTier.NONE
                    val vb = io.github.mayusi.calibratesoc.data.vendor.VendorBranding.of(capability)
                    val tierColor = when (tier) {
                        PrivilegeTier.ROOT -> MaterialTheme.colorScheme.tertiary
                        PrivilegeTier.AYN_SETTINGS -> MaterialTheme.colorScheme.tertiary
                        PrivilegeTier.SHIZUKU -> MaterialTheme.colorScheme.secondary
                        PrivilegeTier.NONE -> MaterialTheme.colorScheme.outline
                    }
                    // Show the vendor tier in brand terms, not the raw enum.
                    val tierChip = if (tier == PrivilegeTier.AYN_SETTINGS) vb.tierLabel else tier.name
                    AssistChip(
                        onClick = {},
                        label = { Text(tierChip) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = tierColor),
                    )
                    val tierExplainer = when (tier) {
                        PrivilegeTier.ROOT ->
                            "Full kernel-level CPU/GPU clocking + fan control + custom presets. Magisk / KernelSU detected and root mode enabled."
                        PrivilegeTier.AYN_SETTINGS ->
                            "${vb.brand} tier active. Vendor performance + fan modes apply instantly via the same Settings.System keys ${vb.brand}'s own Quick Settings tile uses. Custom MHz caps via Generate script."
                        PrivilegeTier.SHIZUKU ->
                            "Monitoring + vendor preset switching. Full sysfs writes pending a Shizuku UserService update."
                        PrivilegeTier.NONE ->
                            "Read-only monitoring + benchmark. On supported handhelds, grant WRITE_SECURE_SETTINGS via adb to enable vendor preset switching. Magisk / KernelSU unlocks the ROOT tier (opt in below). Custom MHz caps work on ANY device via Generate script."
                    }
                    Text(tierExplainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Root mode (advanced)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (rootDetected) {
                                    "Magisk / KernelSU detected. Enable to let Calibrate SoC use it for direct kernel writes (custom MHz caps without the script step)."
                                } else {
                                    "No su binary found. Install Magisk or KernelSU first if you want this tier."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = rootModeEnabled,
                            enabled = rootDetected,
                            onCheckedChange = { viewModel.setRootModeEnabled(it) },
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Per-app auto-switch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    AssistChip(
                        onClick = {},
                        label = { Text(if (accessibilityGranted) "ENABLED" else "DISABLED") },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = if (accessibilityGranted) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        ),
                    )
                    Text(
                        if (accessibilityGranted) {
                            "Per-app overrides will fire on app switch. Configure them on the Profiles tab."
                        } else {
                            "Grant Accessibility access so Calibrate SoC can detect app switches and auto-apply your per-app profiles."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(if (accessibilityGranted) "Manage in Settings" else "Open Accessibility settings") }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Restore to factory state", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val b = baseline
                    if (b == null) {
                        Text(
                            "Capturing baseline... if this persists, restart the app once.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val date = java.text.SimpleDateFormat("MMM d yyyy, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(b.capturedAtMs))
                        Text(
                            "Captured $date • ${b.tunables.size} tunables recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Replays every CPU/GPU cap, governor, and vendor key that was set when the app first launched. The actual surfaces restored depend on your current privilege tier — read-only tier will skip kernel writes.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { pendingFactoryConfirm = true }) {
                            Text("Restore to first-launch state")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Open the full capability report — what the probe found on this device. Includes the Report unknown device share button.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenDeviceInfo) { Text("Open Device Info") }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Calibrate SoC v${viewModel.appVersion}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Universal Handheld Tuner — Apache-2.0", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Tuned for AYN Thor · Odin 3 · Odin 2, Retroid Pocket 6 · 5, and high-end Android phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    OutlinedButton(onClick = {
                        uri.openUri("https://github.com/TheOldTaylor/Odin3-CPU-Underclock")
                    }) { Text("Credits: TheOldTaylor (Odin 3 underclock seed)") }
                }
            }
        }
    }

    if (pendingFactoryConfirm) {
        FactoryConfirmDialog(
            onConfirm = {
                viewModel.restoreToFactory()
                pendingFactoryConfirm = false
            },
            onDismiss = { pendingFactoryConfirm = false },
        )
    }

    restoreSummary?.let { summary ->
        RestoreSummaryDialog(
            summary = summary,
            onDismiss = viewModel::clearRestoreSummary,
        )
    }
}

@Composable
private fun FactoryConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val required = "RESTORE"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore to first-launch state?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Every CPU/GPU cap, governor, and vendor key that was recorded at first launch will be written back. Anything not in the baseline (manual sysfs writes, new vendor keys, profiles created after first launch) is unaffected.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type \"$required\" to confirm") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = typed.trim() == required) { Text("Restore") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RestoreSummaryDialog(
    summary: io.github.mayusi.calibratesoc.data.baseline.FactoryRestorer.RestoreSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore complete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${summary.ok} of ${summary.total} tunables restored.", fontFamily = FontFamily.Monospace)
                if (summary.denied > 0) {
                    Text("${summary.denied} skipped (current privilege tier can't write them).", style = MaterialTheme.typography.bodySmall)
                }
                if (summary.failed > 0) {
                    Text("${summary.failed} failed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    summary.errors.take(3).forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Experimental features toggle. Off by default. ON requires the
 * user to type "ENABLE" — same idiom as the first-OC ack — so the
 * user definitively opts in. Unlocks the HUD ± steppers and any
 * future "this might brick your day" features.
 */
@Composable
private fun ExperimentalCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var pendingConfirm by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Experimental features", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "ON — risky features unlocked"
                            else "OFF — safe defaults only",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            confirmText = ""
                            pendingConfirm = true
                        } else {
                            onToggle(false)
                        }
                    },
                )
            }
            Text(
                "Unlocks the HUD ± clock steppers and other features that " +
                    "can put the device in a bad state if used without root. " +
                    "Anything that happens with experimental features ON is on you, not the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (pendingConfirm) {
        AlertDialog(
            onDismissRequest = { pendingConfirm = false },
            title = { Text("Enable experimental features?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Experimental features include HUD ± clock steppers, " +
                            "direct sysfs writes, and other tools that need root " +
                            "or a rooted-tier permission to work.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "If you turn them on without root, they generate scripts " +
                            "you have to run through your device's settings. If you " +
                            "DO have root and you do something stupid, it's on you — " +
                            "the app won't stop you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Type ENABLE below to continue:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it.uppercase() },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onToggle(true)
                        pendingConfirm = false
                    },
                    enabled = confirmText.trim() == "ENABLE",
                ) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Quick-glance setup checklist. Mirrors the first-launch wizard
 * items so users can re-grant after a wipe / reboot without
 * restarting the wizard. Each row polls its own status every second
 * so the ✓ appears immediately when the user returns from a system
 * grant page.
 */
@Composable
private fun SetupChecklistCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    val items = io.github.mayusi.calibratesoc.ui.setup.AllSetupItemsWithAdvanced
    val statuses by remember(tick) {
        androidx.compose.runtime.derivedStateOf {
            items.associate { it.id to it.isDone(context) }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Setup checklist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "These are the perms + toggles the app needs. Tap any row " +
                    "to open the matching system page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            items.forEach { item ->
                val applicable = item.isApplicable(context)
                val done = statuses[item.id] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!applicable) {
                        // Item can never be granted on this device (no
                        // firmware/hardware for it). Render muted with no
                        // Grant button so the user isn't sent chasing an
                        // un-grantable toggle.
                        Text(
                            "—",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Not available on this device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        Text(
                            if (done) "✓" else "○",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (done) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (done) "Granted" else "Not yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { item.launch(context) }) {
                            Text(if (done) "Re-open" else "Grant")
                        }
                    }
                }
            }
        }
    }
}
