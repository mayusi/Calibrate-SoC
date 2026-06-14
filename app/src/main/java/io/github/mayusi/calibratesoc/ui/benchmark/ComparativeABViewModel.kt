package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.benchmark.BenchConfig
import io.github.mayusi.calibratesoc.data.benchmark.BenchFlavor
import io.github.mayusi.calibratesoc.data.benchmark.BenchRun
import io.github.mayusi.calibratesoc.data.benchmark.BenchmarkRunner
import io.github.mayusi.calibratesoc.data.benchmark.ComparativeResult
import io.github.mayusi.calibratesoc.data.benchmark.ComparativeSlot
import io.github.mayusi.calibratesoc.data.benchmark.ApplyStatus
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.profiles.ProfileApplier
import io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
import io.github.mayusi.calibratesoc.data.profiles.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the A/B comparative benchmark screen.
 *
 * Orchestration sequence (runs in a single coroutine Job):
 *   1. Apply profile A via ProfileApplier → derive ApplyStatus from WriteResults.
 *   2. If ApplyStatus == FAILED, abort entirely — surface error, do not run benchmark.
 *   3. If ApplyStatus == UNVERIFIABLE, continue but mark the slot honestly.
 *   4. Run BenchmarkRunner.run() for profile A → capture BenchRun A.
 *   5. Apply profile B → same honesty gate.
 *   6. Run BenchmarkRunner.run() for profile B → capture BenchRun B.
 *   7. Emit ComparativeResult(slotA, slotB).
 *
 * Cancellation: the Job is cancelled on [cancel]. BenchmarkRunner resets to Idle
 * in its finally block. We emit a partial result (slot A only) if slot A has run.
 *
 * The runner is single-flight — only one BenchRun executes at a time. The
 * A/B sequence respects this by awaiting each run sequentially (no parallelism).
 */
@HiltViewModel
class ComparativeABViewModel @Inject constructor(
    private val runner: BenchmarkRunner,
    private val profileRepository: ProfileRepository,
    private val profileApplier: ProfileApplier,
    private val capabilityProbe: CapabilityProbe,
) : ViewModel() {

    /** All saved user profiles — drives the profile pickers. */
    val profiles: StateFlow<List<UserProfile>> = profileRepository.store
        .map { it.profiles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live capability report — needed by ProfileApplier. */
    private val capability: StateFlow<CapabilityReport?> = capabilityProbe.report

    /** Runner state forwarded to the UI for the progress indicator. */
    val runnerState: StateFlow<BenchmarkRunner.State> = runner.state

    // ── Picker state ──────────────────────────────────────────────────

    private val _profileA = MutableStateFlow<UserProfile?>(null)
    val profileA: StateFlow<UserProfile?> = _profileA.asStateFlow()

    private val _profileB = MutableStateFlow<UserProfile?>(null)
    val profileB: StateFlow<UserProfile?> = _profileB.asStateFlow()

    private val _flavor = MutableStateFlow(BenchFlavor.STANDARD)
    val flavor: StateFlow<BenchFlavor> = _flavor.asStateFlow()

    fun selectProfileA(profile: UserProfile) { _profileA.value = profile }
    fun selectProfileB(profile: UserProfile) { _profileB.value = profile }
    fun selectFlavor(f: BenchFlavor) { _flavor.value = f }

    // ── Sequence state ────────────────────────────────────────────────

    sealed interface ABState {
        /** Nothing running yet — pickers are visible. */
        data object Idle : ABState

        /** Applying profile A. */
        data object ApplyingA : ABState

        /** Benchmark running under profile A. */
        data class RunningA(val runnerState: BenchmarkRunner.State.Running) : ABState

        /** Applying profile B (between the two runs). */
        data object ApplyingB : ABState

        /** Benchmark running under profile B. */
        data class RunningB(val runnerState: BenchmarkRunner.State.Running) : ABState

        /** Both runs finished (or cancelled). Result is available. */
        data class Done(val result: ComparativeResult) : ABState

        /** A hard error occurred before a result could be produced. */
        data class Error(val message: String) : ABState
    }

    private val _abState = MutableStateFlow<ABState>(ABState.Idle)
    val abState: StateFlow<ABState> = _abState.asStateFlow()

    private var sequenceJob: Job? = null

    // ── Public actions ────────────────────────────────────────────────

    /** Start the A/B sequence. No-op if already running. */
    fun start() {
        if (sequenceJob?.isActive == true) return
        val profA = _profileA.value ?: return
        val profB = _profileB.value ?: return
        val flav = _flavor.value

        sequenceJob = viewModelScope.launch {
            runSequence(profA, profB, flav)
        }
    }

    /** Cancel a running sequence. If slot A already ran, emits a partial result. */
    fun cancel() {
        sequenceJob?.cancel()
        sequenceJob = null
        // Note: BenchmarkRunner.run() co-operatively handles CancellationException
        // and resets state in its finally block. We produce a partial result in the
        // catch block below (runSequence handles CancellationException).
    }

    /** Reset back to Idle so the user can re-configure pickers. */
    fun reset() {
        cancel()
        _abState.value = ABState.Idle
    }

    // ── Sequence implementation ───────────────────────────────────────

    private suspend fun runSequence(
        profA: UserProfile,
        profB: UserProfile,
        flavor: BenchFlavor,
    ) {
        val report = capabilityProbe.refresh()

        // ── Slot A ────────────────────────────────────────────────────

        _abState.value = ABState.ApplyingA

        val slotA: ComparativeSlot
        try {
            slotA = applyAndRun(profA, flavor, report, isSlotA = true)
        } catch (ex: kotlinx.coroutines.CancellationException) {
            // Cancelled before slot A ran — nothing to show.
            _abState.value = ABState.Idle
            throw ex
        } catch (ex: HardAbortException) {
            _abState.value = ABState.Error(ex.message ?: "Unknown error applying profile A")
            return
        }

        // ── Slot B ────────────────────────────────────────────────────

        _abState.value = ABState.ApplyingB

        val slotB: ComparativeSlot
        var partial = false
        try {
            slotB = applyAndRun(profB, flavor, report, isSlotA = false)
        } catch (ex: kotlinx.coroutines.CancellationException) {
            // Cancelled during slot B — emit a partial result with only slot A.
            val partialSlotB = ComparativeSlot(
                profile = profB,
                applyStatus = ApplyStatus.FAILED,
                applyDetails = "Cancelled before Profile B ran.",
                run = null,
                cancelledMidRun = true,
            )
            _abState.value = ABState.Done(
                ComparativeResult.build(slotA, partialSlotB, flavor, partial = true),
            )
            throw ex
        } catch (ex: HardAbortException) {
            // Apply for B failed hard — show partial result with slot A's data.
            val failedSlotB = ComparativeSlot(
                profile = profB,
                applyStatus = ApplyStatus.FAILED,
                applyDetails = ex.message ?: "Profile B apply failed.",
                run = null,
            )
            _abState.value = ABState.Done(
                ComparativeResult.build(slotA, failedSlotB, flavor, partial = true),
            )
            return
        }

        _abState.value = ABState.Done(
            ComparativeResult.build(slotA, slotB, flavor, partial = false),
        )
    }

    /**
     * Apply [profile] then run the benchmark. Updates [_abState] as each phase
     * transitions. Returns a filled [ComparativeSlot].
     *
     * @throws HardAbortException when the apply returned FAILED status (empty preset
     *   or total IO crash) — caller should surface the error without running.
     */
    private suspend fun applyAndRun(
        profile: UserProfile,
        flavor: BenchFlavor,
        report: CapabilityReport,
        isSlotA: Boolean,
    ): ComparativeSlot {
        val preset = profile.toPreset()
        val hasTunables = ComparativeResult.presetHasTunables(profile)

        val writeResults = if (hasTunables) {
            profileApplier.apply(preset, report, reason = "A/B benchmark: ${profile.name}")
        } else {
            emptyList()
        }

        val applyPair = ComparativeResult.applyStatusFrom(writeResults, hasTunables)
        val applyStatus = applyPair.first
        val applyDetails = applyPair.second

        // Hard abort: profile has nothing to tune or completely failed IO — no point running.
        if (applyStatus == ApplyStatus.FAILED) {
            throw HardAbortException(
                "Profile \"${profile.name}\": $applyDetails",
            )
        }

        // Collect runner state for the UI while the benchmark is in flight.
        // The runner's own StateFlow reflects the running state; we mirror it.
        val runName = "${profile.name} (A/B)"
        val run: BenchRun = try {
            runner.run(
                flavor = flavor,
                config = BenchConfig(),
                appVersion = BuildConfig.VERSION_NAME,
                name = runName,
            )
        } catch (ex: kotlinx.coroutines.CancellationException) {
            // Re-throw so the caller's try/catch can handle partial results.
            throw ex
        }

        return ComparativeSlot(
            profile = profile,
            applyStatus = applyStatus,
            applyDetails = applyDetails,
            run = run,
        )
    }

    /** Thrown internally when an apply result is so bad that running is pointless. */
    private class HardAbortException(message: String) : Exception(message)
}
