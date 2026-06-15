package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdvancedUnlockViewModel @Inject constructor(
    private val script: AdvancedPermissionsScript,
    private val pServerWriter: PServerWriter,
    private val capabilityProbe: CapabilityProbe,
) : ViewModel() {

    private val _grants = MutableStateFlow(script.grantsCurrentlyHeld())
    val grants: StateFlow<AdvancedPermissionsScript.Grants> = _grants.asStateFlow()

    fun deployScript(): AdvancedPermissionsScript.Deployed = script.deploy()

    /**
     * Re-evaluates all advanced permissions, including PServer transactability.
     *
     * Clears the stale transactableCache in [PServerWriter] first — if the app
     * launched before the user ran the unlock script the cache would be false
     * permanently until this is called. After invalidation we kick a full
     * [CapabilityProbe.refresh] so PServer is re-probed via a real transact and
     * the WriterRegistry sees the updated result.
     */
    fun refresh() {
        // FIX 2: invalidate stale cache before re-probing so a cached-false from
        // app-launch-before-whitelist doesn't persist across a Refresh tap.
        pServerWriter.invalidateTransactableCache()
        _grants.value = script.grantsCurrentlyHeld()
        // Re-probe capability (includes pServerWriter.isTransactable()) so the
        // Tune screen's PServer-LIVE indicator lights up without a full app restart.
        viewModelScope.launch {
            capabilityProbe.refresh()
        }
    }
}
