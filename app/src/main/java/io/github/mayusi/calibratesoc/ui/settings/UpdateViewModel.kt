package io.github.mayusi.calibratesoc.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.update.ApkDownloader
import io.github.mayusi.calibratesoc.data.update.UpdateChecker
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
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
 *                                       → Error on network failure
 *
 * When [Available.info.apkUrl] is null (a release was published without an
 * APK asset) the UI shows an "Update available on GitHub" fallback message
 * so the user can still download manually.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateChecker: UpdateChecker,
    private val apkDownloader: ApkDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

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
     *              → Error on failure
     */
    fun download(info: UpdateInfo) {
        val url = info.apkUrl ?: return // guard: no APK asset; UI should show fallback
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(0)

            val file = apkDownloader.download(url) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 100L) / total).toInt() else -1
                _state.value = UpdateUiState.Downloading(pct)
            }

            if (file == null) {
                _state.value = UpdateUiState.Error("Download failed. Check your connection and try again.")
                return@launch
            }

            _state.value = UpdateUiState.ReadyToInstall(file)
            apkDownloader.installApk(file)
        }
    }

    /**
     * (Re-)launch the system installer for a file that was already downloaded.
     * Useful when the user dismissed the installer prompt accidentally.
     */
    fun install(file: File) {
        apkDownloader.installApk(file)
    }

    /** Reset to [UpdateUiState.Idle] — user dismissed an error card. */
    fun reset() {
        _state.value = UpdateUiState.Idle
    }
}
