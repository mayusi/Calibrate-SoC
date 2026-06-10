package io.github.mayusi.calibratesoc.ui.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.backup.BackupManager
import io.github.mayusi.calibratesoc.data.backup.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for the backup/restore card.
 *
 * Handles export (write to file + return URI for sharing) and import
 * (read from user-selected URI + restore data).
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    /**
     * Export all data to a JSON file in the app's external files directory.
     * Returns a FileProvider URI ready to share.
     */
    fun export(onResult: (uri: Uri?) -> Unit) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val bundle = backupManager.export()
                val json = backupManager.serialize(bundle)

                val backupDir = File(context.getExternalFilesDir(null), "backups")
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }

                val timestamp = System.currentTimeMillis()
                val fileName = "calibratesoc_backup_${timestamp}.json"
                val file = File(backupDir, fileName)

                withContext(Dispatchers.IO) {
                    file.writeText(json)
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                _exportResult.value = "Backup created: $fileName"
                onResult(uri)
            } catch (e: Exception) {
                _exportResult.value = "Export failed: ${e.message}"
                onResult(null)
            } finally {
                _isExporting.value = false
            }
        }
    }

    /**
     * Import data from a user-selected backup JSON file (via URI).
     */
    fun import(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().use { it.readText() }
                    } ?: ""
                }

                val result = backupManager.import(json)
                _importResult.value = result
            } catch (e: Exception) {
                _importResult.value = ImportResult(
                    profilesRestored = 0,
                    tuneEntriesRestored = 0,
                    benchRunsRestored = 0,
                    stabilityRunsRestored = 0,
                    errors = listOf("Failed to read backup file: ${e.message}"),
                )
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    fun clearImportResult() {
        _importResult.value = null
    }
}
