package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DisplayRefreshViewModel @Inject constructor(
    private val controller: RefreshRateController,
    val refreshScript: io.github.mayusi.calibratesoc.data.script.RefreshRateScript,
) : ViewModel() {

    private val _modes = MutableStateFlow<List<RefreshRateController.Mode>>(emptyList())
    val modes: StateFlow<List<RefreshRateController.Mode>> = _modes.asStateFlow()

    val preferredHz: StateFlow<Float?> = controller.preferredHz
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun pick(hz: Float?) {
        viewModelScope.launch { controller.setPreferredHz(hz) }
    }

    /** Refresh the list using the live Activity context — its
     *  windowManager.defaultDisplay is the authoritative source on
     *  Android 14+. The Application-context enumeration sometimes
     *  returns an empty list because no window has been attached yet. */
    fun refresh(activityContext: android.content.Context? = null) {
        _modes.value = controller.supportedModes(activityContext)
    }
}
