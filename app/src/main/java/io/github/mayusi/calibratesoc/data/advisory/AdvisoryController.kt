package io.github.mayusi.calibratesoc.data.advisory

import android.util.Log
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ADVISORY controller — the clean API surface the UI binds to for the
 * ADVISORY rung (fully-stock, no root, no unlock).
 *
 * Responsibilities:
 *  1. Collect telemetry from [MonitorService] into a rolling window.
 *  2. Feed the window into [AdvisoryEngine.advise] on each new sample.
 *  3. Expose [observeAdvice] — a [Flow<List<Advice>>] the UI collects.
 *  4. Expose the available [PowerHintController] hints and delegate
 *     apply/clear to it with honest [HintResult] feedback.
 *  5. Never run when [isAdvisoryRungActive] is false (the UI should only
 *     route to this controller when the LIVE rung is unavailable).
 *
 * HONESTY INVARIANTS:
 *  - We do NOT start collecting telemetry unless explicitly [start]ed.
 *  - Power hints expose [isSupported] before the UI shows a toggle.
 *  - Every [HintResult] returned to the UI includes an honest description.
 *  - The word "estimated" must appear in every UI string that quotes a saving.
 *
 * DESIGN NOTE on GameManager:
 *   We intentionally DO NOT expose a "setGameMode(BATTERY)" toggle because
 *   [PowerHintController.isGameModeSupported] is permanently false — the API
 *   cannot legitimately be used by a third-party app to change another process's
 *   game mode. Instead we surface [gameModeConstraintExplanation] as an
 *   informational note so the user understands their actual options.
 */
@Singleton
class AdvisoryController @Inject constructor(
    private val monitorService: MonitorService,
    private val capabilityProbe: CapabilityProbe,
    val powerHints: PowerHintController,
) {

    private val TAG = "AdvisoryController"

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    /** Rolling telemetry window — last [WINDOW_SIZE] samples. */
    private val _window = ArrayDeque<io.github.mayusi.calibratesoc.data.monitor.Telemetry>(WINDOW_SIZE)

    /** Mutable backing state for the advice list. */
    private val _advice = MutableStateFlow<List<Advice>>(emptyList())

    /** Mutable backing state for hint results (last result per hint type). */
    private val _hintState = MutableStateFlow(AdvisoryHintState())

    /** True while this controller is actively collecting and advising. */
    private val _active = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _active.asStateFlow()

    // ── Capability snapshot ────────────────────────────────────────────────────

    /** Cached capability report used by AdvisoryEngine. Refreshed on start(). */
    private var cachedReport: CapabilityReport? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Live stream of advisory recommendations, updated each telemetry tick.
     *
     * Collects emits an empty list until [start] is called and the first
     * telemetry samples have been processed.
     */
    fun observeAdvice(): Flow<List<Advice>> = _advice.asStateFlow()

    /**
     * Live stream of the current power-hint state (which hints are supported,
     * which are active, and the last result message for each).
     */
    fun observeHintState(): StateFlow<AdvisoryHintState> = _hintState.asStateFlow()

    /**
     * Human-readable explanation of why GameManager.setGameMode is not
     * directly available from this app. Surface in the UI as a note below
     * the Game Mode section, not as a disabled button.
     */
    val gameModeConstraintExplanation: String
        get() = powerHints.gameModeConstraintExplanation

    /**
     * Start collecting telemetry and generating advice.
     *
     * Safe to call multiple times — a running collection is cancelled and
     * restarted. Refreshes the [CapabilityReport] snapshot.
     *
     * Also attempts to open an ADPF hint session if supported.
     */
    fun start() {
        Log.i(TAG, "ADVISORY rung starting")
        _active.value = true
        _window.clear()

        // Refresh capability report.
        cachedReport = capabilityProbe.report.value

        // Try to open ADPF session — advisory bias for our own scheduler.
        if (powerHints.isAdpfSupported) {
            val result = powerHints.openAdpfSession()
            updateHintState { copy(adpfSessionResult = result) }
            Log.i(TAG, "ADPF: ${result.message}")
        } else {
            updateHintState { copy(adpfSessionResult = HintResult.UNSUPPORTED(
                "PerformanceHintManager requires API 31+ (Android 12). " +
                "This device reports API ${android.os.Build.VERSION.SDK_INT}."
            )) }
        }

        // Update hint capability flags.
        updateHintState {
            copy(
                isAdpfSupported = powerHints.isAdpfSupported,
                isSustainedPerfSupported = powerHints.isSustainedPerformanceModeSupported,
                isGameModeSupported = powerHints.isGameModeSupported,
            )
        }

        collectJob?.cancel()
        collectJob = controllerScope.launch {
            val report = cachedReport
            if (report == null) {
                Log.w(TAG, "No CapabilityReport available — advice will be sparse")
            }

            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { sample ->
                // Maintain rolling window of WINDOW_SIZE samples.
                if (_window.size >= WINDOW_SIZE) {
                    _window.removeFirst()
                }
                _window.addLast(sample)

                // Report ADPF work duration — we measured our own sample tick.
                if (powerHints.isAdpfSupported) {
                    // Rough wall-clock proxy: 1 Hz tick = 1 s target.
                    powerHints.reportAdpfWorkDuration(MonitorService.DEFAULT_INTERVAL_MS * 1_000_000L)
                }

                // Generate advice from the current window.
                if (report != null) {
                    val advice = AdvisoryEngine.advise(_window.toList(), report)
                    _advice.value = advice
                }
            }
        }
    }

    /**
     * Stop collecting telemetry and clear all active hints.
     *
     * Does NOT clear the last generated [_advice] so the UI can show the
     * final state while transitioning away.
     */
    fun stop() {
        Log.i(TAG, "ADVISORY rung stopping")
        collectJob?.cancel()
        collectJob = null
        _active.value = false
        powerHints.clearAll()
        updateHintState {
            copy(
                adpfSessionResult = HintResult.UNSUPPORTED("Advisory stopped"),
                sustainedPerfResult = null,
                sustainedPerfActive = false,
            )
        }
    }

    // ── Hint delegates ────────────────────────────────────────────────────────

    /**
     * Returns an instruction result for sustained performance mode.
     *
     * IMPORTANT: The actual toggle (Window.setSustainedPerformanceMode) cannot
     * be called from a Service context — the UI layer's Activity must call it
     * via its Window reference. This method returns a [HintResult] that tells
     * the UI what to do:
     *   - [HintResult.UNSUPPORTED] → device does not support it; hide the toggle.
     *   - [HintResult.APPLIED]     → supported; the message tells the UI to call
     *                                window.setSustainedPerformanceMode(enable).
     *
     * Result is exposed via [observeHintState] so the UI can render the instruction.
     */
    fun sustainedPerformanceModeInstruction(enable: Boolean): HintResult {
        val result = powerHints.sustainedPerformanceModeInstruction(enable)
        updateHintState {
            copy(
                sustainedPerfResult = result,
                // Mark active only when supported AND the UI has presumably acted on it.
                sustainedPerfActive = enable && result.wasSent,
            )
        }
        Log.i(TAG, "Sustained perf instruction ${if (enable) "on" else "off"}: ${result.message}")
        return result
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private inline fun updateHintState(block: AdvisoryHintState.() -> AdvisoryHintState) {
        _hintState.value = _hintState.value.block()
    }

    companion object {
        /** Number of telemetry samples in the rolling window fed to AdvisoryEngine. */
        const val WINDOW_SIZE = 5
    }
}

/**
 * Snapshot of which advisory power hints are supported and currently active,
 * plus the last [HintResult] message for each. The UI observes this to decide
 * which controls to show and what status to display — never expose a toggle for
 * an unsupported or rejected hint.
 */
data class AdvisoryHintState(
    /** True if PerformanceHintManager (ADPF) is available on this device. */
    val isAdpfSupported: Boolean = false,
    /** Last result from opening or reporting to the ADPF session. */
    val adpfSessionResult: HintResult? = null,

    /**
     * Always false — GameManager.setGameMode cannot be legitimately called
     * by this app for another process. See [PowerHintController] kdoc.
     * Exposed here so the UI can query it in one place without reaching
     * into [PowerHintController] directly.
     */
    val isGameModeSupported: Boolean = false,

    /** True if PowerManager.isSustainedPerformanceModeSupported on this device. */
    val isSustainedPerfSupported: Boolean = false,
    /** Last result from setSustainedPerformanceMode. */
    val sustainedPerfResult: HintResult? = null,
    /** True when sustained performance mode was successfully requested. */
    val sustainedPerfActive: Boolean = false,
)
