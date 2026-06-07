package io.github.mayusi.calibratesoc.ui.profiles

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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.profiles.UserProfile

/**
 * Profiles screen. Two sections:
 *   1. Saved profiles list. Each profile: name, summary, apply / boot
 *      toggle / delete actions.
 *   2. Per-app overrides. Tap an entry to pick which profile fires
 *      when that app comes to the foreground (requires the
 *      Accessibility service grant — surfaced as a yellow banner if
 *      not granted yet).
 */
@Composable
fun ProfilesScreen(viewModel: ProfilesViewModel = hiltViewModel()) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    val installed by viewModel.installedApps.collectAsStateWithLifecycle()
    var assigning by remember { mutableStateOf<String?>(null) }
    var editingApp by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Profiles", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Saved tunes you can reapply. Mark a profile Apply on boot to keep it across reboots — otherwise everything reverts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (store.profiles.isEmpty()) {
            item {
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
                    onApply = { viewModel.apply(profile) },
                    onDelete = { viewModel.delete(profile) },
                    onToggleBoot = { viewModel.toggleApplyOnBoot(profile) },
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

        if (store.perAppOverrides.isEmpty()) {
            item {
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
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onToggleBoot: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            if (profile.description.isNotBlank()) {
                Text(
                    profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val capsLine = profile.cpuPolicyMaxKhz.entries
                .sortedBy { it.key }
                .joinToString("  ") { "p${it.key}=${it.value / 1000}MHz" }
            if (capsLine.isNotBlank()) {
                Text(capsLine, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = profile.applyOnBoot, onCheckedChange = { onToggleBoot() })
                Text("Apply on boot", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
                Button(onClick = onApply) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun OverrideCard(
    packageName: String,
    profileName: String,
    onChange: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(packageName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text("→ $profileName", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onChange) { Text("Change") }
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }
    }
}

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
