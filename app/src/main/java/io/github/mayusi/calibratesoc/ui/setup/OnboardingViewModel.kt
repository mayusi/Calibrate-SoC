package io.github.mayusi.calibratesoc.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.prefs.UserPrefs
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin VM for the onboarding wizard. Only job: flip the
 * onboarding-complete flag in UserPrefs when the user finishes or
 * explicitly skips. Everything else (live perm probes, system
 * launches) is done synchronously on the UI thread inside the
 * composable because they're cheap and don't need a state holder.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userPrefs: UserPrefs,
) : ViewModel() {
    fun markComplete() {
        viewModelScope.launch { userPrefs.setOnboardingComplete(true) }
    }

    /**
     * Record whether the user confirmed the "Skip — read-only" scary dialog
     * on an applicable (AYN/vendor-runner) device.
     *
     * Pass true when the user confirms the full-screen ScarySkipDialog.
     * Pass false when they complete the advanced setup (so gated-feature
     * re-surface logic knows they did it properly).
     */
    fun setAdvancedSetupSkipped(skipped: Boolean) {
        viewModelScope.launch { userPrefs.setAdvancedSetupSkipped(skipped) }
    }
}
