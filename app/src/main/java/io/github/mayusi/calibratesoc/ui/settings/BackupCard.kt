package io.github.mayusi.calibratesoc.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.calibratesoc.ui.components.AlertCard
import io.github.mayusi.calibratesoc.ui.components.AlertType
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-contained backup/restore card for the Settings screen.
 *
 * Allows the user to:
 * - Export all data (profiles, tune history, benchmark runs) to a JSON file,
 *   which can be shared or saved.
 * - Import from a previously-saved JSON backup to restore data.
 *
 * Shows progress spinners during export/import and displays result messages
 * (success or errors).
 */
@Composable
fun BackupCard(
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()

    // Launcher for the "create document" intent (export / save to file).
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            // User selected a save location. Trigger the export to that URI.
            // (In this implementation, we export to our own file and then share the URI,
            // so this is used for sharing the already-created backup.)
            viewModel.export { backupUri ->
                if (backupUri != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, backupUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share backup"))
                }
            }
        }
    }

    // Launcher for picking an import file.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.import(uri)
        }
    }

    SectionCard(
        title = "Backup & restore",
        icon = Icons.Outlined.Backup,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Export everything — profiles, per-app overrides, tune history, and benchmark runs — to a file you can save or move to another device. Import a backup to restore your data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Export button + status.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        viewModel.export { backupUri ->
                            if (backupUri != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, backupUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share backup"))
                            }
                        }
                    },
                    enabled = !isExporting && !isImporting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Export backup")
                }
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(8.dp),
                    )
                }
            }

            // Import button + status.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    },
                    enabled = !isExporting && !isImporting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Import backup")
                }
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(8.dp),
                    )
                }
            }

            // Export result message.
            exportResult?.let { msg ->
                val isSuccess = msg.startsWith("Backup created")
                AlertCard(
                    type = if (isSuccess) AlertType.INFO else AlertType.ERROR,
                    title = "Export",
                    message = msg,
                    action = {
                        OutlinedButton(onClick = { viewModel.clearExportResult() }) {
                            Text("Dismiss")
                        }
                    },
                )
            }

            // Import result summary.
            importResult?.let { result ->
                val summary = result.summary()
                val errorMsg = if (result.errors.isNotEmpty()) {
                    "\n\nErrors:\n" + result.errors.take(2).joinToString("\n")
                } else {
                    ""
                }
                val type = if (result.allOk) AlertType.INFO else AlertType.ERROR

                AlertCard(
                    type = type,
                    title = if (result.allOk) "Import complete" else "Import with errors",
                    message = summary + errorMsg,
                    action = {
                        OutlinedButton(onClick = { viewModel.clearImportResult() }) {
                            Text("Dismiss")
                        }
                    },
                )
            }
        }
    }
}
