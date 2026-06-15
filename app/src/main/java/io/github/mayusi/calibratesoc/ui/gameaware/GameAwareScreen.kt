package io.github.mayusi.calibratesoc.ui.gameaware

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.gameaware.GamePlanSource
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Game-Aware screen — Direction C.
 *
 * Surfaces the per-game AutoTDP and profile mapping engine:
 *   - Lists all known emulators with built-in AutoTDP hints.
 *   - Lets users override profile + AutoTDP profile + FPS cap per game.
 *   - "Mark as learned good" confirmation badge.
 *   - Honest: hints are suggestions, user override always wins.
 */
@Composable
fun GameAwareScreen(viewModel: GameAwareViewModel = hiltViewModel()) {
    val gameEntries by viewModel.gameEntries.collectAsStateWithLifecycle()
    val savedProfiles by viewModel.savedProfiles.collectAsStateWithLifecycle()
    val editingEntry by viewModel.editingEntry.collectAsStateWithLifecycle()

    // Separate: user-configured vs hint-only
    val configured = gameEntries.filter { it.userRecord != null }
    val hintOnly = gameEntries.filter { it.userRecord == null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item {
            Column {
                Text(
                    "GAME-AWARE",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.08.sp,
                )
                Spacer(Modifier.height(Spacing.dense))
                Text(
                    "Auto-apply profile + AutoTDP settings when a game or emulator launches. " +
                        "Hints are built-in suggestions — your overrides always win.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            }
        }

        // ── User-configured games ─────────────────────────────────────────
        if (configured.isNotEmpty()) {
            item {
                SectionHeader(title = "YOUR OVERRIDES", accent = AccentBar.Red)
            }
            items(configured, key = { it.packageName }) { entry ->
                GameEntryPanel(
                    entry = entry,
                    onEdit = { viewModel.openEdit(entry) },
                    onMarkGood = { viewModel.markLearnedGood(entry.packageName) },
                )
            }
        }

        // ── Known games (hint-only) ───────────────────────────────────────
        item {
            SectionHeader(
                title = if (configured.isEmpty()) "KNOWN EMULATORS" else "MORE KNOWN EMULATORS",
                accent = AccentBar.Blue,
            )
        }

        if (hintOnly.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Gamepad,
                    title = "No emulators detected",
                    body = "Install a supported emulator and it will appear here with a suggested AutoTDP profile.",
                )
            }
        } else {
            items(hintOnly, key = { it.packageName }) { entry ->
                GameEntryPanel(
                    entry = entry,
                    onEdit = { viewModel.openEdit(entry) },
                    onMarkGood = null,
                )
            }
        }

        item { Spacer(Modifier.height(Spacing.screen)) }
    }

    // ── Edit dialog ───────────────────────────────────────────────────────────
    editingEntry?.let { entry ->
        GameEditDialog(
            entry = entry,
            savedProfiles = savedProfiles,
            onSave = { profileId, autoTdp, fpsCap, learnedGood ->
                viewModel.saveRecord(
                    packageName = entry.packageName,
                    profileId = profileId,
                    autoTdpProfile = autoTdp,
                    fpsCapHz = fpsCap,
                    learnedGood = learnedGood,
                )
            },
            onRemove = { viewModel.removeRecord(entry.packageName) },
            onDismiss = { viewModel.closeEdit() },
        )
    }
}

// ── Game entry panel ───────────────────────────────────────────────────────────

@Composable
private fun GameEntryPanel(
    entry: GameEntry,
    onEdit: () -> Unit,
    onMarkGood: (() -> Unit)?,
) {
    val accent = when {
        entry.isLearnedGood -> AccentBar.Emerald
        entry.userRecord != null -> AccentBar.Red
        else -> AccentBar.Blue
    }

    ArsenalPanel(accent = accent) {
        // Header: app label + source pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.appLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.06.sp,
                )
                Text(
                    entry.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF777777),
                )
            }
            if (entry.isLearnedGood) {
                StatusPill(text = "VERIFIED", accent = AccentBar.Emerald)
            } else if (entry.userRecord != null) {
                StatusPill(text = "CUSTOM", accent = AccentBar.Red)
            } else {
                StatusPill(text = "HINT", accent = AccentBar.Blue)
            }
        }

        Spacer(Modifier.height(Spacing.dense))

        // AutoTDP tier display
        entry.effectiveAutoTdp?.let { profile ->
            KvRow(
                label = "AutoTDP",
                value = profile.name,
                explainer = if (entry.userRecord?.autoTdpProfile != null) null
                    else "Built-in suggestion — tap Edit to customize",
            )
        }
        entry.userRecord?.profileId?.let {
            KvRow(label = "Profile", value = it)
        }
        entry.userRecord?.fpsCapHz?.let { cap ->
            KvRow(label = "FPS cap", value = "$cap Hz")
        }

        Spacer(Modifier.height(Spacing.dense))

        // Action row
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            ArsenalButton(
                label = if (entry.userRecord != null) "Edit" else "Customize",
                onClick = onEdit,
                style = ArsenalButtonStyle.Secondary,
                accent = accent,
            )
            if (onMarkGood != null && !entry.isLearnedGood) {
                ArsenalButton(
                    label = "Mark good",
                    onClick = onMarkGood,
                    style = ArsenalButtonStyle.Secondary,
                    accent = AccentBar.Emerald,
                )
            }
        }
    }
}

// ── Edit dialog ────────────────────────────────────────────────────────────────

@Composable
private fun GameEditDialog(
    entry: GameEntry,
    savedProfiles: List<UserProfile>,
    onSave: (profileId: String?, autoTdp: AutoTdpProfile?, fpsCap: Int?, learnedGood: Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProfileId by remember { mutableStateOf(entry.userRecord?.profileId) }
    var selectedAutoTdp by remember { mutableStateOf(entry.userRecord?.autoTdpProfile) }
    var fpsCap by remember { mutableStateOf(entry.userRecord?.fpsCapHz?.toString() ?: "") }
    var learnedGood by remember { mutableStateOf(entry.isLearnedGood) }

    var profileDropdownExpanded by remember { mutableStateOf(false) }
    var autoTdpDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(entry.appLabel, fontWeight = FontWeight.Bold)
                Text(
                    entry.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.group)) {
                Text(
                    "Override the auto-settings for this game. Leave blank to use the built-in hint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Built-in hint info
                entry.knownHintAutoTdp?.let { hint ->
                    Text(
                        "Built-in hint: AutoTDP = ${hint.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                // Profile picker
                Text("Profile (optional)", style = MaterialTheme.typography.labelMedium)
                val profileName = savedProfiles.firstOrNull { it.id == selectedProfileId }?.name ?: "None"
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { profileDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(profileName, modifier = Modifier.weight(1f))
                        Text("▾", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = profileDropdownExpanded,
                        onDismissRequest = { profileDropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None — use current profile") },
                            onClick = { selectedProfileId = null; profileDropdownExpanded = false },
                        )
                        savedProfiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { selectedProfileId = p.id; profileDropdownExpanded = false },
                            )
                        }
                    }
                }

                // AutoTDP picker
                Text("AutoTDP Profile (optional)", style = MaterialTheme.typography.labelMedium)
                val autoTdpName = selectedAutoTdp?.name ?: "Use hint / no change"
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(
                        onClick = { autoTdpDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(autoTdpName, modifier = Modifier.weight(1f))
                        Text("▾", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = autoTdpDropdownExpanded,
                        onDismissRequest = { autoTdpDropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Use hint / no change") },
                            onClick = { selectedAutoTdp = null; autoTdpDropdownExpanded = false },
                        )
                        AutoTdpProfile.entries.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
                                onClick = { selectedAutoTdp = profile; autoTdpDropdownExpanded = false },
                            )
                        }
                    }
                }

                // FPS cap
                Text("FPS cap (optional)", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = fpsCap,
                    onValueChange = { new -> if (new.all { it.isDigit() } && new.length <= 3) fpsCap = new },
                    placeholder = { Text("e.g. 60 — leave blank for no cap") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default.copy(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )

                // Learned good toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = learnedGood, onCheckedChange = { learnedGood = it })
                    Column {
                        Text("Confirmed this setup works", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Shows a \"Verified\" badge — for your reference only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (entry.userRecord != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Remove override (revert to built-in hint)",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    selectedProfileId,
                    selectedAutoTdp,
                    fpsCap.toIntOrNull(),
                    learnedGood,
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
