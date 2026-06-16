package io.github.mayusi.calibratesoc.ui.profiles

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import io.github.mayusi.calibratesoc.data.profiles.InstalledApp
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.data.profiles.ReaperConfig
import io.github.mayusi.calibratesoc.ui.autotdp.GoalPickerPanel
import io.github.mayusi.calibratesoc.ui.autotdp.GoalProfileUi
import io.github.mayusi.calibratesoc.ui.autotdp.goalChipAccent
import io.github.mayusi.calibratesoc.ui.components.AccentBar
import io.github.mayusi.calibratesoc.ui.components.ArsenalButton
import io.github.mayusi.calibratesoc.ui.components.ArsenalButtonStyle
import io.github.mayusi.calibratesoc.ui.components.ArsenalPanel
import io.github.mayusi.calibratesoc.ui.components.SectionHeader
import io.github.mayusi.calibratesoc.ui.components.StatusPill
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Full per-app bundle editor sheet — edits all fields of [PerAppBundle] for a
 * chosen package, plus the reaper config section.
 *
 * Opened as a full-screen composable from ProfilesScreen (or from an entry in
 * the per-app list). The save action writes via [PerAppBundleViewModel.setBundle].
 *
 * Sections:
 *  1. Bundle editor for the selected package:
 *     - AutoTDP goal (5-mode picker, mirrors [GoalPickerPanel] chips).
 *     - Refresh rate (numeric override, nullable).
 *     - Fan mode (numeric override, nullable).
 *     - Game Boost on launch toggle.
 *  2. Reaper section:
 *     - Enable toggle with honest copy.
 *     - Multi-select app picker from [enumerateInstalledUserApps].
 *
 * Back-compat: the existing [ProfilesScreen] per-app override flow (single
 * profileId) is left untouched. This sheet is a NEW entry point accessible via
 * an "Edit full bundle" action on the per-app override list, or by navigating
 * directly to a bundle-edit route.
 */
@Composable
fun PerAppBundleScreen(
    packageName: String,
    viewModel: PerAppBundleViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val appsLoading by viewModel.appsLoading.collectAsStateWithLifecycle()
    val reaperConfig by viewModel.reaperConfig.collectAsState(initial = ReaperConfig())

    // Load apps for the reaper picker.
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }

    // Current bundle for this package (may be null = no bundle yet).
    val existingBundle = store.perAppBundles[packageName]

    // Editable draft state — initialised from existing bundle.
    var draftGoal by remember(packageName, existingBundle) {
        mutableStateOf(existingBundle?.autoTdpGoal)
    }
    var draftGameBoostOnLaunch by remember(packageName, existingBundle) {
        mutableStateOf(existingBundle?.gameBoostOnLaunch ?: false)
    }
    var draftFanMode by remember(packageName, existingBundle) {
        mutableStateOf(existingBundle?.fanMode)
    }
    var showReaperPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Per-app tune",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF666666),
                    )
                }
                IconButton(onClick = {
                    viewModel.clearBundle(packageName)
                    onDone()
                }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Clear bundle",
                        tint = AccentBar.Red.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // ── AutoTDP goal ──────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Purple, title = "AutoTDP goal on launch") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text(
                        text = "Daemon starts with this goal when the app comes to the foreground. Null = do not start AutoTDP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                    Spacer(Modifier.height(Spacing.dense))

                    // None chip
                    GoalChipBundleOption(
                        label = "None (no AutoTDP)",
                        isSelected = draftGoal == null,
                        accent = AccentBar.Neutral,
                        onSelect = { draftGoal = null },
                    )

                    // 5 goal chips
                    listOf(
                        GoalProfile.AUTO,
                        GoalProfile.BALANCED_SMART,
                        GoalProfile.MAX_FPS,
                        GoalProfile.COOL_QUIET,
                        GoalProfile.BATTERY_SAVER,
                    ).forEach { goal ->
                        GoalChipBundleOption(
                            label = GoalProfileUi.goalLabel(goal),
                            sublabel = GoalProfileUi.goalDescription(goal),
                            isSelected = draftGoal == goal,
                            accent = goalChipAccent(goal),
                            onSelect = { draftGoal = goal },
                        )
                    }
                }
            }
        }

        // ── Game Boost on launch ──────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Red, title = "Game Boost on launch") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Start Game Boost when this app launches",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                        Text(
                            text = "Max heat for max FPS. Time-boxed and auto-reverts. Mutually exclusive with AutoTDP.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF999999),
                        )
                    }
                    Switch(
                        checked = draftGameBoostOnLaunch,
                        onCheckedChange = { draftGameBoostOnLaunch = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0A0A0E),
                            checkedTrackColor = AccentBar.Red,
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF2A2A2A),
                        ),
                    )
                }
            }
        }

        // ── Fan mode ──────────────────────────────────────────────────────
        item {
            ArsenalPanel(accent = AccentBar.Amber, title = "Fan mode on launch") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
                    Text(
                        text = "Set the AYN/Retroid fan mode when this app launches. Null = leave fan unchanged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF999999),
                    )
                    // Compact fan-mode selector (Quiet=0, Smart=4, Sport=5, None=null)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.dense),
                    ) {
                        listOf(null to "None", 0 to "Quiet", 4 to "Smart", 5 to "Sport").forEach { (mode, label) ->
                            val isSelected = draftFanMode == mode
                            val chipAccent = when (mode) {
                                null -> AccentBar.Neutral
                                0 -> AccentBar.Blue
                                4 -> AccentBar.Emerald
                                else -> AccentBar.Red
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) chipAccent.copy(alpha = 0.18f) else Color(0xFF0C0C10),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(1.dp, if (isSelected) chipAccent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                                    .clickable { draftFanMode = mode }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) chipAccent else Color(0xFF888888),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Save button ───────────────────────────────────────────────────
        item {
            ArsenalButton(
                label = "Save tune bundle",
                onClick = {
                    val bundle = PerAppBundle(
                        profileId = existingBundle?.profileId,
                        autoTdpGoal = draftGoal,
                        refreshRateHz = existingBundle?.refreshRateHz,
                        fanMode = draftFanMode,
                        gameBoostOnLaunch = draftGameBoostOnLaunch,
                    )
                    viewModel.setBundle(packageName, bundle)
                    onDone()
                },
                accent = AccentBar.Emerald,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Reaper section ────────────────────────────────────────────────
        item {
            ReaperConfigSection(
                reaperConfig = reaperConfig,
                installedApps = viewModel.filterReaperEligible(installedApps),
                appsLoading = appsLoading,
                onSetEnabled = { viewModel.setReaperEnabled(it) },
                onShowPicker = { showReaperPicker = true },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // Reaper app-picker dialog
    if (showReaperPicker) {
        val eligibleApps = viewModel.filterReaperEligible(installedApps)
        ReaperAppPickerDialog(
            apps = eligibleApps,
            selectedPackages = reaperConfig.denylist,
            onSave = { selected ->
                viewModel.setReaperDenylist(selected)
                showReaperPicker = false
            },
            onDismiss = { showReaperPicker = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reaper config section
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReaperConfigSection(
    reaperConfig: ReaperConfig,
    installedApps: List<InstalledApp>,
    appsLoading: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onShowPicker: () -> Unit,
) {
    ArsenalPanel(accent = AccentBar.Neutral, title = "Background app reaper") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.dense)) {
            Text(
                text = "Selected apps are closed (not deleted) when a game starts. They relaunch on next interaction.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Enable reaper",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
                Switch(
                    checked = reaperConfig.enabled,
                    onCheckedChange = onSetEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF0A0A0E),
                        checkedTrackColor = AccentBar.Emerald,
                        uncheckedThumbColor = Color(0xFF888888),
                        uncheckedTrackColor = Color(0xFF2A2A2A),
                    ),
                )
            }

            if (reaperConfig.enabled) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${reaperConfig.denylist.size} app(s) selected to close on game launch",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                        )
                        if (reaperConfig.denylist.isNotEmpty()) {
                            Text(
                                text = reaperConfig.denylist.take(3).joinToString(", ") +
                                    if (reaperConfig.denylist.size > 3) " + ${reaperConfig.denylist.size - 3} more" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF666666),
                            )
                        }
                    }
                    ArsenalButton(
                        label = if (appsLoading) "Loading…" else "Edit list",
                        onClick = onShowPicker,
                        accent = AccentBar.Blue,
                        style = ArsenalButtonStyle.Secondary,
                        enabled = !appsLoading,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reaper app picker dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReaperAppPickerDialog(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selectedPackages) { mutableStateOf(selectedPackages.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Select apps to close on game launch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = "These apps are closed (not deleted) when a game starts. They relaunch on next tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        val isSelected = app.packageName in draft
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) draft.remove(app.packageName)
                                    else draft.add(app.packageName)
                                    // Force recompose by reassigning.
                                    draft = draft.toMutableSet()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) draft.add(app.packageName)
                                    else draft.remove(app.packageName)
                                    draft = draft.toMutableSet()
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentBar.Emerald,
                                    uncheckedColor = Color(0xFF888888),
                                    checkmarkColor = Color(0xFF0A0A0E),
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.displayLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF666666),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("Save", color = AccentBar.Emerald, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF888888))
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bundle-editor goal chip (vertical list variant)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GoalChipBundleOption(
    label: String,
    isSelected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
    sublabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) accent.copy(alpha = 0.13f) else Color(0xFF0C0C10),
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, if (isSelected) accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) accent else Color.White,
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666),
                )
            }
        }
        if (isSelected) {
            StatusPill(text = "SELECTED", accent = accent)
        }
    }
}
