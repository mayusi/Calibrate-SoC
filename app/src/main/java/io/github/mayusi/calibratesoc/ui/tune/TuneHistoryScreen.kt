package io.github.mayusi.calibratesoc.ui.tune

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
import io.github.mayusi.calibratesoc.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tune history. Lists every preset / vendor key / script the user has
 * applied, newest first. Each row shows what was applied, how
 * (direct sysfs / vendor key / script / boot install), and the
 * specific frequency caps that landed.
 *
 * Two intentional design choices:
 *   - "Reapply" lives next to each entry so the user can roll back to
 *     a known-good config in one tap. The reapply is just a fresh
 *     GENERATED_SCRIPT — never a silent root write — so the user has
 *     to confirm via Odin Settings, matching the rest of the no-root
 *     flow.
 *   - "Clear all" is a destructive dialog because users have built a
 *     mental model that the list IS their tuning notebook. We don't
 *     want a one-tap wipe.
 */
@Composable
fun TuneHistoryScreen(viewModel: TuneHistoryViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { it.presetName.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        "Tune history",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Every preset, vendor flip, and script run. Newest first. Up to 100 entries.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Delete,
                        title = "Nothing applied yet",
                        body = "Pick a preset on Tune and your history shows up here.",
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search by preset name") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedButton(onClick = { confirmClear = true }) {
                        Text("Clear all history")
                    }
                }
                if (filteredEntries.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Delete,
                            title = "No matches",
                            body = "No presets match \"$searchQuery\".",
                        )
                    }
                } else {
                    items(filteredEntries, key = { it.appliedAtMs }) { entry ->
                        HistoryRow(
                            entry = entry,
                            onDelete = {
                                viewModel.deleteEntry(entry)
                                coroutineScope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Deleted",
                                        actionLabel = "Undo",
                                        withDismissAction = false,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.reinsertEntry(entry)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear tune history?") },
            text = { Text("This deletes the full log of your applied tunes. Saved profiles and benchmarks are not affected.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(entry: TuneHistoryEntry, onDelete: () -> Unit = {}) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.presetName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete entry",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    dateFmt.format(Date(entry.appliedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PathwayChip(entry.pathway)
            }
            if (entry.presetDescription.isNotBlank()) {
                Text(
                    entry.presetDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.cpuPolicyMaxKhz.isNotEmpty()) {
                val cpuMaxLine = "CPU max: " + entry.cpuPolicyMaxKhz.toSortedMap().entries.joinToString("  ") {
                    "p${it.key}=${it.value / 1000}MHz"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(cpuMaxLine))
                    },
                ) {
                    Text(
                        cpuMaxLine,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy CPU policy line",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (entry.cpuPolicyGovernor.isNotEmpty()) {
                val govLine = "Governor: " + entry.cpuPolicyGovernor.toSortedMap().entries.joinToString("  ") {
                    "p${it.key}=${it.value}"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(govLine))
                    },
                ) {
                    Text(
                        govLine,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy governor line",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            entry.gpuMaxHz?.let {
                Text(
                    "GPU max: ${it / 1_000_000L} MHz",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (entry.notes.isNotBlank()) {
                Text(
                    "Notes: ${entry.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun PathwayChip(pathway: ApplyPathway) {
    val label = when (pathway) {
        ApplyPathway.DIRECT_ROOT -> "ROOT write"
        ApplyPathway.AYN_SETTINGS_KEY -> "vendor key"
        ApplyPathway.GENERATED_SCRIPT -> "script"
        ApplyPathway.BOOT_SCRIPT_INSTALL -> "boot install"
        ApplyPathway.BOOT_REMINDER_REGISTERED -> "boot reminder"
    }
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}
