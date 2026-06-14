package io.github.mayusi.calibratesoc.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.update.AutoUpdateChecker
import io.github.mayusi.calibratesoc.data.update.UpdateInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin bridge between [AutoUpdateChecker] (singleton, process-scoped) and
 * the app-level Scaffold. Exposes [pendingUpdate] so the Compose root can
 * show a dismissible banner without blocking startup.
 *
 * Actions:
 *   [snooze]   — "Later": hides banner for 7 days, tag stays eligible.
 *   [dismiss]  — "×": remembers the tag; won't nag about this version again.
 *   [consume]  — "Update": routes to Settings; hides the banner in-session
 *               without persisting any preference (re-appears next launch
 *               if the snooze/dismiss logic still passes).
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val autoUpdateChecker: AutoUpdateChecker,
) : ViewModel() {

    /** Non-null when an update should be shown — drives the banner composable. */
    val pendingUpdate: StateFlow<UpdateInfo?> = autoUpdateChecker.pendingUpdate

    fun snooze() {
        viewModelScope.launch { autoUpdateChecker.snooze() }
    }

    fun dismiss() {
        viewModelScope.launch { autoUpdateChecker.dismiss() }
    }

    fun consume() {
        autoUpdateChecker.consume()
    }
}
