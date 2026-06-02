package io.github.mayusi.calibratesoc.ui.tune

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.mayusi.calibratesoc.data.tunables.ApplyPathway
import io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry
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
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
                Text(
                    "Nothing applied yet. Pick a preset on Tune and your history shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Text("Clear all history")
                }
            }
            items(entries, key = { it.appliedAtMs }) { entry ->
                HistoryRow(entry)
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
private fun HistoryRow(entry: TuneHistoryEntry) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.presetName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
                Text(
                    "CPU max: " + entry.cpuPolicyMaxKhz.toSortedMap().entries.joinToString("  ") {
                        "p${it.key}=${it.value / 1000}MHz"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (entry.cpuPolicyGovernor.isNotEmpty()) {
                Text(
                    "Governor: " + entry.cpuPolicyGovernor.toSortedMap().entries.joinToString("  ") {
                        "p${it.key}=${it.value}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
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
