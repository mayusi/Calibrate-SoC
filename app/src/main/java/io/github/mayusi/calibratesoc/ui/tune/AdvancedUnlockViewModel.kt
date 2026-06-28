package io.github.mayusi.calibratesoc.ui.tune

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.script.AdvancedPermissionsScript
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /**
     * State of the one-trust auto-setup (self-grant via PServer). Drives the
     * onboarding "Set up automatically?" card and the re-runnable Settings/Tune
     * button. Honest by construction: it only ever reflects what
     * [AdvancedPermissionsScript.grantViaPServer] actually reported.
     */
    sealed interface AutoGrantState {
        /** Nothing attempted yet this session. */
        object Idle : AutoGrantState

        /** Grant in progress — show a brief "Setting up…". */
        object Running : AutoGrantState

        /**
         * PServer ran the grants. [allGranted] is true only when all three advanced
         * perms actually landed (re-read after granting); otherwise the UI shows the
         * honest partial state + the script fallback for what's missing.
         */
        data class Completed(
            val grants: AdvancedPermissionsScript.Grants,
            val allGranted: Boolean,
        ) : AutoGrantState

        /**
         * PServer is not transactable here — NO grants were issued. The UI must NOT
         * present this as success; it falls back to the script path.
         */
        object NotAvailable : AutoGrantState
    }

    /**
     * State of the FULL one-trust setup ([setupEverything]) — the superset that, in a
     * single click, grants the 3 pm-perms AND the four special-access toggles (Usage
     * Access, Overlay, Battery-unrestricted, Notifications) via PServer-root. Drives
     * the onboarding "Set up everything" card + the Settings re-run button: the
     * [FullCompleted.held] map renders a live per-row checklist.
     *
     * Honest by construction — it only ever reflects what
     * [AdvancedPermissionsScript.grantAllViaPServer] reported off its post-grant
     * platform readback. Separate from [AutoGrantState] so the existing 3-perm card
     * keeps working unchanged.
     */
    sealed interface FullSetupState {
        /** Nothing attempted yet this session. */
        object Idle : FullSetupState

        /** Setup in progress — show "Setting everything up…". */
        object Running : FullSetupState

        /**
         * PServer ran the full setup. [held] is the HONEST per-item re-read; the UI
         * renders one checklist row per [AdvancedPermissionsScript.SetupItem].
         * [allGranted] is true only when EVERY item landed (computed off [held]).
         */
        data class FullCompleted(
            val held: Map<AdvancedPermissionsScript.SetupItem, Boolean>,
            val allGranted: Boolean,
        ) : FullSetupState

        /**
         * PServer is not transactable here — NO commands were issued. The UI must NOT
         * present this as success; it falls back to the script + manual-Settings path.
         */
        object NotAvailable : FullSetupState
    }

    private val _autoGrantState = MutableStateFlow<AutoGrantState>(AutoGrantState.Idle)
    val autoGrantState: StateFlow<AutoGrantState> = _autoGrantState.asStateFlow()

    private val _fullSetupState = MutableStateFlow<FullSetupState>(FullSetupState.Idle)
    val fullSetupState: StateFlow<FullSetupState> = _fullSetupState.asStateFlow()

    /**
     * The latest capability snapshot, so the onboarding wizard can decide
     * whether a no-Permissive live-tuning path (PServer / AYANEO binder /
     * root) already exists — and therefore whether the Force SELinux
     * last-resort step should be offered at all. Null until the first
     * [CapabilityProbe.refresh] lands.
     */
    val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /**
     * Convenience signals for the "already unlocked" UX copy. Derived straight
     * off [capability] so the advanced-unlock flow can render the honest state
     * without each call-site re-reading the report:
     *
     *   - [pserverSysfsLive]: PServer-root live tuning is active (nothing to do).
     *   - [selinuxEnforcing]: SELinux mode (true=Enforcing, false=Permissive,
     *     null=unknown) — the REAL gate, used to distinguish "needs Force-SELinux"
     *     (Enforcing + binder present, no live path) from the normal ladder.
     */
    val pserverSysfsLive: StateFlow<Boolean> = capabilityProbe.report
        .map { it?.pserverSysfsLive == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val selinuxEnforcing: StateFlow<Boolean?> = capabilityProbe.report
        .map { it?.selinuxEnforcing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun deployScript(): AdvancedPermissionsScript.Deployed = script.deploy()

    /**
     * One-trust auto-setup: grant the three advanced perms to ourselves via
     * PServer (no script, no file picker). Runs off the main thread, updates
     * [autoGrantState] honestly with what actually landed, then invalidates the
     * transactable cache + re-probes capability so the rest of the UI (Tune
     * PServer-LIVE pill, grants checklist) lights up immediately.
     *
     * HONESTY: the result mirrors [AdvancedPermissionsScript.grantViaPServer]
     * exactly — [AutoGrantState.NotAvailable] when PServer can't transact (the
     * caller falls back to the script), [AutoGrantState.Completed] with the
     * RE-READ grant state otherwise.
     */
    fun autoGrantViaPServer() {
        // Don't double-fire if a grant is already running.
        if (_autoGrantState.value is AutoGrantState.Running) return
        _autoGrantState.value = AutoGrantState.Running
        viewModelScope.launch {
            val result = script.grantViaPServer()
            _autoGrantState.value = when (result) {
                is AdvancedPermissionsScript.GrantResult.NotAvailable ->
                    AutoGrantState.NotAvailable
                is AdvancedPermissionsScript.GrantResult.Completed ->
                    AutoGrantState.Completed(
                        grants = result.held,
                        allGranted = result.allGranted,
                    )
            }
            // Reflect the freshly re-read grants in the shared flow so every
            // grants-driven UI (checklist, indicators) updates without a refresh tap.
            _grants.value = script.grantsCurrentlyHeld()
            // Re-probe PServer/capability so the WriterRegistry + LIVE pill pick up
            // any state change (e.g. WRITE_SECURE_SETTINGS now enabling vendor keys).
            pServerWriter.invalidateTransactableCache()
            capabilityProbe.refresh()
        }
    }

    /**
     * FULL one-trust setup: grant EVERYTHING the app needs in one click via
     * PServer-root — the 3 pm-perms PLUS the four special-access toggles (Usage
     * Access, Overlay, Battery-unrestricted, Notifications). The onboarding "Set up
     * everything" button and the Settings re-run button both call this.
     *
     * Runs off the main thread, updates [fullSetupState] honestly with the per-item
     * readback, then invalidates the transactable cache + re-probes capability so the
     * rest of the app (overlay availability, FPS, per-app auto-switch, LIVE pill)
     * lights up immediately without a manual refresh.
     *
     * HONESTY: the result mirrors [AdvancedPermissionsScript.grantAllViaPServer]
     * exactly — [FullSetupState.NotAvailable] when PServer can't transact (the caller
     * falls back to the script + manual Settings), [FullSetupState.FullCompleted] with
     * the RE-READ per-item held map otherwise.
     */
    fun setupEverything() {
        // Don't double-fire if a full setup is already running.
        if (_fullSetupState.value is FullSetupState.Running) return
        _fullSetupState.value = FullSetupState.Running
        viewModelScope.launch {
            val result = script.grantAllViaPServer()
            _fullSetupState.value = when (result) {
                is AdvancedPermissionsScript.FullSetupResult.NotAvailable ->
                    FullSetupState.NotAvailable
                is AdvancedPermissionsScript.FullSetupResult.Completed ->
                    FullSetupState.FullCompleted(
                        held = result.held,
                        allGranted = result.allGranted,
                    )
            }
            // Reflect the freshly re-read pm-grants in the shared flow so every
            // grants-driven UI (checklist, indicators) updates without a refresh tap.
            _grants.value = script.grantsCurrentlyHeld()
            // Re-probe PServer/capability so the WriterRegistry + LIVE pill + overlay
            // availability pick up the new state (WRITE_SECURE_SETTINGS, usage access).
            pServerWriter.invalidateTransactableCache()
            capabilityProbe.refresh()
        }
    }

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
