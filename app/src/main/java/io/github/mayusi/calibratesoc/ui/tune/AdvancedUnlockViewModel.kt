package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AdvancedUnlockViewModel @Inject constructor(
    private val script: AdvancedPermissionsScript,
) : ViewModel() {

    private val _grants = MutableStateFlow(script.grantsCurrentlyHeld())
    val grants: StateFlow<AdvancedPermissionsScript.Grants> = _grants.asStateFlow()

    fun deployScript(): AdvancedPermissionsScript.Deployed = script.deploy()

    fun refresh() {
        _grants.value = script.grantsCurrentlyHeld()
    }
}
