package io.github.mayusi.calibratesoc.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.update.ApkDownloader
import io.github.mayusi.calibratesoc.data.update.UpdateChecker
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Sealed hierarchy that drives the updater UI. */
sealed class UpdateUiState {
    /** Initial state — nothing started yet. */
    data object Idle : UpdateUiState()

    /** Network request in flight. */
    data object Checking : UpdateUiState()

    /** The installed build is already the latest. [currentVersion] shown in the UI. */
    data class UpToDate(val currentVersion: String) : UpdateUiState()

    /** A newer release exists and is ready to download. */
    data class Available(val info: UpdateInfo) : UpdateUiState()

    /**
     * APK download in progress.
     * @param pct 0–100, or -1 when total size is unknown (indeterminate).
     */
    data class Downloading(val pct: Int) : UpdateUiState()

    /**
     * Download complete. The installer has been (or will be) launched.
     * @param file points to the downloaded APK.
     */
    data class ReadyToInstall(val file: File) : UpdateUiState()

    /**
     * Something went wrong.
     * @param message Human-readable description.
     */
    data class Error(val message: String) : UpdateUiState()
}

/**
 * Drives the in-app updater section of the Settings screen.
 *
 * Flow: Idle → Checking → {UpToDate | Available | Error}
 *               Available → Downloading → ReadyToInstall (auto-launches installer)
 *                                       → Error on network / signature failure
 *
 * When [Available.info.apkUrl] is null (a release was published without an
 * APK asset) the UI shows an "Update available on GitHub" fallback message
 * so the user can still download manually.
 *
 * Security: [download] validates the URL against an HTTPS-and-GitHub-host
 * allowlist, verifies the downloaded APK's signing certificate matches the
 * installed app before launching the installer, and rejects files whose
 * content-length deviates from the expected asset size.
 *
 * Race guard: [download] is a no-op while a download [Job] is already active
 * or while a release check ([UpdateUiState.Checking]) is in progress.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val apkDownloader: ApkDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /**
     * Fix 4: Track the active download Job so we can guard against
     * concurrent downloads started by a double-tap or two quick button
     * presses. Null when no download is running.
     */
    private var downloadJob: Job? = null

    /**
     * Kick off a GitHub release check. Sets state to [UpdateUiState.Checking]
     * while the network call is in flight, then resolves to [UpdateUiState.Available],
     * [UpdateUiState.UpToDate], or [UpdateUiState.Error].
     */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            val info = updateChecker.fetchLatest()
            _state.value = when {
                info == null -> UpdateUiState.Error(
                    "Could not reach GitHub. Check your connection and try again.",
                )
                info.isNewer -> UpdateUiState.Available(info)
                else -> UpdateUiState.UpToDate(info.versionName)
            }
        }
    }

    /**
     * Start downloading the APK for [info]. Transitions:
     *   Available → Downloading(pct) → ReadyToInstall (installer auto-launched)
     *              → Error on failure or signature mismatch
     *
     * Fix 4 (race guard): returns immediately if a download is already
     * active (downloadJob?.isActive == true) or if a release check is still
     * in progress. This prevents file corruption from two concurrent writes
     * to the same APK path.
     *
     * Fix 2: the URL is validated inside [ApkDownloader.download]; an
     * invalid host / non-HTTPS URL surfaces as [UpdateUiState.Error].
     *
     * Fix 1: after a successful download the APK's signing certificate is
     * compared against the installed app's certificate. A mismatch blocks
     * the installer and surfaces [UpdateUiState.Error].
     *
     * Fix 5: [UpdateInfo.apkSize] is forwarded so the downloader can
     * reject a file whose Content-Length deviates more than 1 MiB from
     * the expected size.
     */
    fun download(info: UpdateInfo) {
        val url = info.apkUrl ?: return // guard: no APK asset; UI should show fallback

        // Fix 4: guard against re-entry.
        if (downloadJob?.isActive == true) return
        if (_state.value is UpdateUiState.Checking) return

        downloadJob = viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(0)

            val file = apkDownloader.download(
                url = url,
                expectedSize = info.apkSize,
            ) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 100L) / total).toInt() else -1
                _state.value = UpdateUiState.Downloading(pct)
            }

            if (file == null) {
                _state.value = UpdateUiState.Error(
                    "Download failed. The file may be unavailable, the URL may be invalid, " +
                        "or the size was unexpected. Check your connection and try again."
                )
                downloadJob = null
                return@launch
            }

            // Fix 1: verify the downloaded APK is signed by the same key as
            // the installed app. Reject and delete the file if it doesn't match
            // so we never leave an unverified APK on disk.
            if (!apkDownloader.verifySignature(file)) {
                file.delete()
                _state.value = UpdateUiState.Error(
                    "This update's signature doesn't match the installed app and was not " +
                        "installed (it may be corrupted or tampered with). " +
                        "Please download it manually from the GitHub releases page."
                )
                downloadJob = null
                return@launch
            }

            downloadJob = null
            _state.value = UpdateUiState.ReadyToInstall(file)
            // installApk also verifies internally (defense-in-depth), but we
            // already verified above; installApk's second check is a no-cost
            // re-read of the same path, protecting against a TOCTOU gap.
            apkDownloader.installApk(file)
        }
    }

    /**
     * (Re-)launch the system installer for a file that was already downloaded.
     * Useful when the user dismissed the installer prompt accidentally.
     *
     * Fix 1 (defense-in-depth): signature is re-verified before re-launching
     * the installer even for an already-downloaded file, because the file
     * could have been modified since the original [download] call.
     */
    fun install(file: File) {
        val launched = apkDownloader.installApk(file)
        if (!launched) {
            _state.value = UpdateUiState.Error(
                "Could not verify the update file's signature. " +
                    "Please download the update again."
            )
        }
    }

    /** Reset to [UpdateUiState.Idle] — user dismissed an error card. */
    fun reset() {
        // Cancel any in-flight download so the job reference is cleared.
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }
}
