package io.github.mayusi.calibratesoc.ui.profiles

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import io.github.mayusi.calibratesoc.data.profiles.PerAppBundle
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * Profiles screen. Two sections:
 *   1. Saved profiles list. Each profile: name, summary, apply / boot
 *      toggle / delete actions. Active profile (last applied) is
 *      highlighted with an emerald "Active" chip.
 *   2. Per-app overrides. Tap an entry to pick which profile fires
 *      when that app comes to the foreground (requires the
 *      Accessibility service grant — surfaced as a warning if not
 *      granted when overrides exist).
 */
@Composable
fun ProfilesScreen(viewModel: ProfilesViewModel = hiltViewModel()) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    val installed by viewModel.installedApps.collectAsStateWithLifecycle()
    val shareCode by viewModel.shareCode.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val accessibilityGranted by viewModel.accessibilityGranted.collectAsStateWithLifecycle()
    val capability by viewModel.capability.collectAsStateWithLifecycle()

    // Per-tier honesty for the "Apply on boot" toggle. On a PServer-live / root /
    // AYANEO-binder device the boot-reapply engine writes the tune automatically
    // each boot — nothing for the user to do. On a chmod-only vendor device the
    // tune still needs the script-at-boot path, so the toggle just flags intent +
    // posts the boot reminder. Honest copy per tier; null until the probe settles.
    val bootIsAutomatic = capability?.let { cap ->
        cap.pserverSysfsLive ||
            cap.ayaneoBinderLive ||
            cap.privilege == io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT ||
            cap.sysfsDirectlyWritable
    } ?: false
    val bootHonestyNote = when {
        capability == null -> null
        bootIsAutomatic ->
            "Works automatically on this device — the tune re-applies itself each boot, no script needed."
        else ->
            "On this device the tune still needs the one-tap boot script — we'll remind you to run it after a reboot."
    }

    var editingApp by remember { mutableStateOf<String?>(null) }
    var bundleEditingApp by remember { mutableStateOf<String?>(null) }
    var gameTuneApp by remember { mutableStateOf<String?>(null) }
    var sharingProfile by remember { mutableStateOf<UserProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Refresh accessibility grant status each time the screen is composed.
    LaunchedEffect(Unit) { viewModel.refreshAccessibility() }

    // Warm the capability report so the "Apply on boot" per-tier note settles.
    LaunchedEffect(Unit) { viewModel.refreshCapability() }

    // When shareCode is populated, update the sharingProfile trigger.
    LaunchedEffect(shareCode) {
        if (shareCode == null) sharingProfile = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Profiles", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Saved tunes you can reapply. Mark a profile Apply on boot to keep it across reboots — otherwise everything reverts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Import tune from code")
            }
        }

        if (store.profiles.isEmpty()) {
            item {
                // Fix 5: use shared EmptyState from SharedComponents.kt
                EmptyState(
                    icon = Icons.Outlined.Tune,
                    title = "No profiles yet",
                    body = "Tune your clocks, then tap Save as profile.",
                )
            }
        } else {
            items(store.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    // Fix 3: pass active-profile awareness
                    isActive = profile.id == activeProfileId,
                    bootHonestyNote = bootHonestyNote,
                    onApply = { viewModel.apply(profile) },
                    onDelete = { viewModel.delete(profile) },
                    onToggleBoot = { viewModel.toggleApplyOnBoot(profile) },
                    onShare = {
                        viewModel.generateShareCode(profile)
                        sharingProfile = profile
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("Per-app overrides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "When you switch to an app on this list, its profile auto-applies. Requires the Accessibility grant in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Fix 4: inline warning when overrides exist but accessibility is not granted.
        if (store.perAppOverrides.isNotEmpty() && !accessibilityGranted) {
            item {
                AlertCard(
                    type = AlertType.WARNING,
                    title = "Accessibility not granted",
                    message = "Per-app auto-switch is silently disabled until you enable the Calibrate SoC Accessibility service.",
                    action = {
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        ) { Text("Open Settings") }
                    },
                )
            }
        }

        if (store.perAppOverrides.isEmpty()) {
            item {
                // Fix 5: use shared EmptyState
                EmptyState(
                    icon = Icons.Outlined.Apps,
                    title = "No per-app profiles",
                    body = "Add an override below to auto-apply a profile when an app opens.",
                )
            }
        }

        items(store.perAppOverrides.entries.toList(), key = { it.key }) { (pkg, profileId) ->
            val profile = store.profiles.firstOrNull { it.id == profileId }
            OverrideCard(
                packageName = pkg,
                // Fix 1: resolved app label from PackageManager via ViewModel cache
                appLabel = viewModel.resolveAppLabel(pkg),
                profileName = profile?.name ?: "(missing profile)",
                onChange = { editingApp = pkg },
                onClear = { viewModel.setOverride(pkg, null) },
            )
        }

        item {
            Button(
                onClick = {
                    viewModel.loadInstalledApps()
                    editingApp = ""
                },
                enabled = store.profiles.isNotEmpty(),
            ) { Text("Add per-app override") }
        }

        // Wave 4b: per-app full bundle editor entry points
        if (store.perAppOverrides.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Full bundle editor",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Edit AutoTDP goal, refresh rate, fan mode, Game Boost on launch, and background app reaper per game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(store.perAppOverrides.keys.toList(), key = { "bundle:$it" }) { pkg ->
                OutlinedButton(
                    onClick = { bundleEditingApp = pkg },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(viewModel.resolveAppLabel(pkg).let { it.ifBlank { pkg } })
                }
            }
        }
    }

    // ── Share dialog ────────────────────────────────────────────────────────────
    if (sharingProfile != null && shareCode != null) {
        SharePresetDialog(
            profile = sharingProfile!!,
            code = shareCode!!,
            onDismiss = {
                viewModel.clearShareCode()
                sharingProfile = null
            },
        )
    }

    // ── Import dialog ───────────────────────────────────────────────────────────
    if (showImportDialog) {
        ImportPresetDialog(
            importState = importState,
            onCodeChange = { viewModel.decodeImportCode(it) },
            onConfirm = { profile ->
                viewModel.confirmImport(profile) { /* errors shown inline */ }
                showImportDialog = false
                viewModel.dismissImport()
            },
            onDismiss = {
                showImportDialog = false
                viewModel.dismissImport()
            },
        )
    }

    if (editingApp != null) {
        AppPickerDialog(
            apps = installed,
            existingOverrides = store.perAppOverrides,
            profiles = store.profiles,
            initialPackage = editingApp,
            onAssign = { pkg, profileId ->
                viewModel.setOverride(pkg, profileId)
                editingApp = null
            },
            onDismiss = { editingApp = null },
        )
    }

    // Wave 4b: full per-app bundle editor
    bundleEditingApp?.let { pkg ->
        PerAppBundleScreen(
            packageName = pkg,
            onDone = { bundleEditingApp = null },
            onOpenGameTunes = { gameTuneApp = pkg },
        )
    }

    // Game Tunes hub — share / import / community for a specific game.
    // Launched inline (same pattern as PerAppBundleScreen) so no nav
    // controller threading is needed through TuneHubScreen.
    gameTuneApp?.let { pkg ->
        val gameTuneStore by viewModel.store.collectAsStateWithLifecycle()
        val bundle: PerAppBundle? = gameTuneStore.perAppBundles[pkg]
        val profile: UserProfile? = bundle?.profileId?.let { id ->
            gameTuneStore.profiles.firstOrNull { it.id == id }
        }
        GameTuneScreen(
            packageName = pkg,
            gameDisplayName = viewModel.resolveAppLabel(pkg).ifBlank { pkg },
            bundle = bundle,
            profile = profile,
            currentDeviceKey = null,
            onDone = { gameTuneApp = null },
        )
    }
}

// ── Active chip ─────────────────────────────────────────────────────────────────

/**
 * Emerald pill chip indicating the profile was the last applied tune.
 * Colors are hardcoded from the theme palette (Theme.kt tertiary / tertiary-container).
 */
private val EmeraldChipBg = Color(0xFF064E3B)
private val EmeraldChipFg = Color(0xFF34D399)

@Composable
private fun ActiveChip() {
    Text(
        text = "Active",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = EmeraldChipFg,
        modifier = Modifier
            .background(
                color = EmeraldChipBg,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ── Profile card ────────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(
    profile: UserProfile,
    isActive: Boolean,
    bootHonestyNote: String?,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onToggleBoot: () -> Unit,
    onShare: () -> Unit,
) {
    // Fix 2: use SectionCard instead of raw Card — gives consistent
    // surfaceVariant container + SemiBold titleMedium header.
    SectionCard(title = profile.name) {
        // Trailing action row: Active chip (when applicable) + share icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isActive) {
                ActiveChip()
                Spacer(Modifier.size(8.dp))
            }
            androidx.compose.material3.IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share tune code",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (profile.description.isNotBlank()) {
            Text(
                profile.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val originLabel = profile.createdOnDeviceName ?: profile.createdOnDeviceKey
        if (originLabel != null) {
            Text(
                "from $originLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        // Fix 6: render each CPU policy's MHz cap as a clean KvRow.
        val capsEntries = profile.cpuPolicyMaxKhz.entries.sortedBy { it.key }
        if (capsEntries.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                capsEntries.forEach { (policy, khz) ->
                    KvRow(
                        label = "p$policy max",
                        value = "${khz / 1000} MHz",
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = profile.applyOnBoot, onCheckedChange = { onToggleBoot() })
            Text("Apply on boot", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete") }
            Button(onClick = onApply) { Text("Apply") }
        }
        // Per-tier honesty: PServer-live → automatic each boot; chmod-only → needs
        // the boot script. Only shown once the profile is flagged + the note is known.
        if (profile.applyOnBoot && bootHonestyNote != null) {
            Text(
                bootHonestyNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Override card ────────────────────────────────────────────────────────────────

@Composable
private fun OverrideCard(
    packageName: String,
    appLabel: String,
    profileName: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    // Fix 1 + 2: SectionCard with resolved app label as the title (SemiBold,
    // titleMedium).  Package name rendered below as small secondary text.
    SectionCard(title = appLabel) {
        Text(
            packageName,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Profile: $profileName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChange) { Text("Change") }
            TextButton(onClick = onClear) { Text("Remove") }
        }
    }
}

// ── App-picker dialog ─────────────────────────────────────────────────────────

@Composable
private fun AppPickerDialog(
    apps: List<ProfilesViewModel.InstalledApp>,
    existingOverrides: Map<String, String>,
    profiles: List<UserProfile>,
    initialPackage: String?,
    onAssign: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPackage by remember { mutableStateOf(initialPackage?.takeIf { it.isNotBlank() } ?: "") }
    var selectedProfile by remember {
        mutableStateOf(initialPackage?.let { existingOverrides[it] } ?: profiles.firstOrNull()?.id ?: "")
    }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { /* nothing — list loaded by parent */ }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPackage.isNullOrBlank()) "Add per-app override" else "Change override") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("App", style = MaterialTheme.typography.labelMedium)
                val filtered = apps.filter {
                    filter.isBlank() || it.label.contains(filter, ignoreCase = true) ||
                        it.packageName.contains(filter, ignoreCase = true)
                }.take(40)
                if (apps.isEmpty()) {
                    Text("Loading installed apps…", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(modifier = Modifier.height(180.dp)) {
                    items(filtered) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName == selectedPackage,
                                onCheckedChange = { selectedPackage = app.packageName },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }

                Text("Profile", style = MaterialTheme.typography.labelMedium)
                profiles.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedProfile == p.id,
                            onCheckedChange = { selectedProfile = p.id },
                        )
                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAssign(selectedPackage, selectedProfile) },
                enabled = selectedPackage.isNotBlank() && selectedProfile.isNotBlank(),
            ) { Text("Assign") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Share / Import dialogs ─────────────────────────────────────────────────────

/**
 * Dialog that shows the shareable base-64 preset code for [profile] and
 * lets the user copy it or share it via the Android share sheet.
 */
@Composable
private fun SharePresetDialog(
    profile: UserProfile,
    code: String,
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share \"${profile.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Anyone with this code can import your tune exactly as-is.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Tune code") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier
                                .size(20.dp)
                                .padding(2.dp),
                        )
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboardManager.setText(AnnotatedString(code))
                scope.launch {
                    android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, code)
                        putExtra(Intent.EXTRA_SUBJECT, "Calibrate SoC tune: ${profile.name}")
                    }
                    context.startActivity(Intent.createChooser(intent, "Share tune"))
                }
                onDismiss()
            }) { Text("Share…") }
        },
    )
}

/**
 * Dialog that lets the user paste an imported preset code and preview
 * the decoded [UserProfile] before saving it.
 */
@Composable
private fun ImportPresetDialog(
    importState: ProfilesViewModel.ImportState,
    onCodeChange: (String) -> Unit,
    onConfirm: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import tune") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a tune code shared by another user.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        onCodeChange(it)
                    },
                    label = { Text("Tune code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                )
                when (val s = importState) {
                    is ProfilesViewModel.ImportState.Preview -> {
                        Text(
                            "Preview: ${s.profile.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val caps = s.profile.cpuPolicyMaxKhz.entries
                            .sortedBy { it.key }
                            .joinToString("  ") { "p${it.key}=${it.value / 1000}MHz" }
                        if (caps.isNotBlank()) {
                            Text(caps, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is ProfilesViewModel.ImportState.Error ->
                        Text(s.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    is ProfilesViewModel.ImportState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            val preview = importState as? ProfilesViewModel.ImportState.Preview
            Button(
                onClick = { preview?.let { onConfirm(it.profile) } },
                enabled = preview != null,
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
