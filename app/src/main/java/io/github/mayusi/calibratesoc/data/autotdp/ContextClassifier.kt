package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.gameaware.KnownGames
import io.github.mayusi.calibratesoc.data.monitor.Telemetry

/**
 * The detected workload context. Drives the AUTO goal's per-tick goal choice and
 * the HUD's "Detected: …" line.
 *
 * Ordinal order is the UPGRADE order (IDLE < VIDEO < LIGHT_GAME < HEAVY_GAME);
 * UNKNOWN sits at the bottom as the honest "can't tell" state. The classifier's
 * asymmetric hysteresis uses this ordering to decide upgrade vs downgrade.
 */
enum class WorkloadContext {
    /** Genuinely lightly loaded — low GPU, no game foreground. */
    IDLE,
    /** Video playback class — moderate steady GPU, a media/launcher foreground. */
    VIDEO,
    /** Light game — a known/foreground game with modest GPU. */
    LIGHT_GAME,
    /** Heavy 3D — sustained high GPU with a game foreground. */
    HEAVY_GAME,
    /** Honest can't-tell: no anchor and no decisive GPU signal. */
    UNKNOWN,
    ;

    /** True when this context is at least a light game (used by the paused guard). */
    val isGameClass: Boolean get() = this == LIGHT_GAME || this == HEAVY_GAME
}

/**
 * Thermal trend hint handed to the classifier so HEAVY_GAME can split between
 * BALANCED_SMART (cool/steady) and COOL_QUIET (heating up). Pure — derived by the
 * engine from the smoothed die/zone temps; the classifier never reads a sensor.
 */
enum class ThermalTrend { COOL, RISING }

/**
 * Carried classifier state. The classifier is PURE given (window + fg-pkg +
 * thermalTrend + this state); the daemon persists the returned [ClassifierState]
 * and threads it back next tick. Nothing here is Android, I/O, or a clock read.
 *
 * @property stable        The currently-committed context (what the HUD shows and
 *                         what AUTO maps to a goal). Only changes once hysteresis
 *                         confirms.
 * @property candidate     The context the raw signal has been suggesting; null when
 *                         the raw read agrees with [stable].
 * @property agreeingTicks How many consecutive ticks the raw read has agreed with
 *                         [candidate]. Drives upgrade (≥2) / downgrade (≥8).
 * @property anchorPackage The foreground package that [stable] was committed under.
 *                         A change here fast-declasses (the paused-game guard only
 *                         holds while the anchor is UNCHANGED).
 */
data class ClassifierState(
    val stable: WorkloadContext = WorkloadContext.UNKNOWN,
    val candidate: WorkloadContext? = null,
    val agreeingTicks: Int = 0,
    val anchorPackage: String? = null,
) {
    companion object {
        val INITIAL = ClassifierState()
    }
}

/** The classifier's output: the committed context plus the next carried state. */
data class ClassificationResult(
    val context: WorkloadContext,
    val state: ClassifierState,
)

/**
 * Pure context classifier for Smart AutoTDP.
 *
 * ## Signals (in trust order)
 *  1. **Foreground package = the ANCHOR.** A known/foreground game pins the raw
 *     read to at least LIGHT_GAME regardless of momentary GPU dips. This is the
 *     paused-game guard's lever.
 *  2. **GPU busy% (smoothed by the caller, raw mean here)** separates HEAVY_GAME
 *     (sustained high) from LIGHT_GAME, and VIDEO from IDLE.
 *  3. **Thermal trend** only splits the AUTO→goal choice for HEAVY_GAME; it does
 *     not change the classification itself.
 *  4. **Real FPS** is consumed ONLY as a don't-tighten floor in the engine, NEVER
 *     here — the classifier ignores it entirely (honesty: FPS is mostly null and
 *     must never drive classification).
 *
 * ## Hysteresis (asymmetric — the LAW)
 *  - **Upgrade** (toward a heavier context) commits after **2** agreeing ticks.
 *  - **Downgrade** (toward a lighter context) commits after **8** agreeing ticks.
 *  - **Paused-game guard:** never declass below LIGHT_GAME while the foreground
 *    package is unchanged and the stable context was HEAVY_GAME. A paused heavy
 *    game (GPU drops to ~idle) stays LIGHT_GAME, not IDLE — so AUTO doesn't yank
 *    the device into battery mode mid-session.
 *  - **Anchor change fast-declass:** if the foreground package changes, the guard
 *    is void and the raw read takes effect on the next confirming tick (a real app
 *    switch is decisive, not noise).
 */
object ContextClassifier {

    /** Upgrade hysteresis: ticks the raw read must agree before committing UP. */
    const val UPGRADE_TICKS = 2

    /** Downgrade hysteresis: ticks the raw read must agree before committing DOWN. */
    const val DOWNGRADE_TICKS = 8

    // ── Raw-read GPU thresholds (busy%) ──────────────────────────────────────────
    /** At/above this smoothed GPU%, a game foreground reads HEAVY_GAME. */
    private const val HEAVY_GPU_PCT = 70
    /** At/above this GPU% (no game anchor) reads VIDEO; below → IDLE. */
    private const val VIDEO_GPU_PCT = 20
    /** Above this GPU% with no anchor we still can't call it a game → UNKNOWN. */
    private const val UNKNOWN_GPU_PCT = 55

    /**
     * Classify one tick.
     *
     * @param window       recent telemetry (the engine's window). The most recent
     *                     sample's [Telemetry.foregroundPackage] is the anchor.
     * @param smoothedGpuPct the EWMA GPU busy% the engine already computed (so the
     *                     classifier and the band controller agree on one number).
     * @param prior        carried [ClassifierState] from last tick.
     * @return [ClassificationResult] with the committed context + next state.
     */
    fun classify(
        window: List<Telemetry>,
        smoothedGpuPct: Int,
        prior: ClassifierState,
    ): ClassificationResult {
        val result = classifyRaw(window, smoothedGpuPct, prior)

        // ── GUARDRAIL 4: foreground-game/wrapper anchor floor ─────────────────────
        // While a KNOWN game or translation-layer wrapper (e.g. GameNative/Winlator)
        // is in the foreground, the context AUTO maps to a goal MUST NOT fall below
        // LIGHT_GAME — even during the hysteresis confirm window where the committed
        // `stable` may still be a stale IDLE/VIDEO from before the wrapper came
        // foreground. Without this floor, AUTO could route to BATTERY_SAVER's aggressive
        // power-cap for a tick or two while a heavy game runs (DEFECT A trigger #1).
        //
        // HONESTY: we floor only the RETURNED context (the belief AUTO acts on); the
        // carried hysteresis state (stable/candidate/agreeingTicks) is left untouched so
        // the tiering stays honest and the normal upgrade/downgrade machinery is
        // unaffected. This is a belief anchored off the foreground package, not a
        // measurement — the DETECTED honesty tier already conveys that.
        val fgPkg = window.lastOrNull()?.foregroundPackage
        val fgIsGame = fgPkg != null && KnownGames.defaultHintFor(fgPkg) != null
        if (fgIsGame && isLighterThan(result.context, WorkloadContext.LIGHT_GAME)) {
            return result.copy(context = WorkloadContext.LIGHT_GAME)
        }
        return result
    }

    /**
     * The hysteresis state machine. Returns the committed context + next carried state
     * WITHOUT the GUARDRAIL-4 foreground floor (applied by [classify]). Kept private so
     * the floor is always enforced for AUTO; tests that need the raw state machine call
     * [classify] and reason about the floored result.
     */
    private fun classifyRaw(
        window: List<Telemetry>,
        smoothedGpuPct: Int,
        prior: ClassifierState,
    ): ClassificationResult {
        if (window.isEmpty()) {
            // No data: hold whatever was stable; do not invent a context.
            return ClassificationResult(prior.stable, prior)
        }

        val latest = window.last()
        val fgPkg = latest.foregroundPackage
        val anchorChanged = fgPkg != prior.anchorPackage

        // ── Raw read for THIS tick ────────────────────────────────────────────────
        val raw = rawRead(fgPkg, smoothedGpuPct)

        // ── Paused-game guard ─────────────────────────────────────────────────────
        // While the SAME game is still in the foreground (a paused heavy game looks
        // idle but is NOT a context change), never let the raw read fall below
        // LIGHT_GAME. The guard is VOID the moment the foreground leaves that game —
        // either the package changes (anchorChanged) OR the current foreground is no
        // longer a game-class anchor (switched to a launcher/non-game). This is what
        // separates "paused game" (pin) from "switched away" (fast-declass).
        val fgStillGame = fgPkg != null && KnownGames.defaultHintFor(fgPkg) != null
        val guarded = if (
            !anchorChanged &&
            fgStillGame &&
            prior.stable == WorkloadContext.HEAVY_GAME &&
            isLighterThan(raw, WorkloadContext.LIGHT_GAME)
        ) {
            WorkloadContext.LIGHT_GAME
        } else {
            raw
        }

        // ── Anchor-change fast path ───────────────────────────────────────────────
        // A real foreground switch is decisive. Reset hysteresis to the new anchor
        // and start confirming the fresh raw read immediately (no stale guard, no
        // carried agreeingTicks against the old anchor).
        if (anchorChanged) {
            // Commit the new anchor immediately but require the normal confirm count
            // before changing the *context label* away from stable, EXCEPT we re-seat
            // the candidate so the next tick can confirm fast. The committed context
            // stays `stable` for this very tick unless the raw read equals it.
            val newState = ClassifierState(
                stable = prior.stable,
                candidate = if (guarded != prior.stable) guarded else null,
                agreeingTicks = if (guarded != prior.stable) 1 else 0,
                anchorPackage = fgPkg,
            )
            return ClassificationResult(prior.stable, newState)
        }

        // ── No change vs stable ───────────────────────────────────────────────────
        if (guarded == prior.stable) {
            // Raw agrees with what we already show: clear any pending candidate.
            return ClassificationResult(
                prior.stable,
                prior.copy(candidate = null, agreeingTicks = 0, anchorPackage = fgPkg),
            )
        }

        // ── Pending transition: accumulate agreeing ticks ─────────────────────────
        val sameCandidate = prior.candidate == guarded
        val agreeing = if (sameCandidate) prior.agreeingTicks + 1 else 1

        // Asymmetric threshold: upgrades commit fast (2), downgrades slow (8).
        val isUpgrade = isHeavierThan(guarded, prior.stable)
        val threshold = if (isUpgrade) UPGRADE_TICKS else DOWNGRADE_TICKS

        return if (agreeing >= threshold) {
            // Commit the transition.
            ClassificationResult(
                guarded,
                ClassifierState(
                    stable = guarded,
                    candidate = null,
                    agreeingTicks = 0,
                    anchorPackage = fgPkg,
                ),
            )
        } else {
            // Still confirming — keep showing the stable context.
            ClassificationResult(
                prior.stable,
                prior.copy(candidate = guarded, agreeingTicks = agreeing, anchorPackage = fgPkg),
            )
        }
    }

    /**
     * AUTO goal mapping: detected context → concrete [GoalProfile].
     *
     *   IDLE / VIDEO  → BATTERY_SAVER  (no need to feed clocks to idle/video)
     *   LIGHT_GAME    → BALANCED_SMART
     *   HEAVY_GAME    → BALANCED_SMART when cool/steady; COOL_QUIET when heating
     *   UNKNOWN       → BALANCED_SMART  (the safe default — never batters battery on
     *                                   a can't-tell tick, never starves a real game)
     */
    fun goalFor(context: WorkloadContext, thermalTrend: ThermalTrend): GoalProfile = when (context) {
        WorkloadContext.IDLE,
        WorkloadContext.VIDEO -> GoalProfile.BATTERY_SAVER
        WorkloadContext.LIGHT_GAME -> GoalProfile.BALANCED_SMART
        WorkloadContext.HEAVY_GAME -> when (thermalTrend) {
            ThermalTrend.COOL -> GoalProfile.BALANCED_SMART
            ThermalTrend.RISING -> GoalProfile.COOL_QUIET
        }
        WorkloadContext.UNKNOWN -> GoalProfile.BALANCED_SMART
    }

    // ── Raw read ─────────────────────────────────────────────────────────────────

    /**
     * The instantaneous (pre-hysteresis) context from the anchor + GPU%.
     *
     * Anchor logic: a foreground package that [KnownGames] recognises (or any
     * non-null foreground package paired with a game-class GPU level) anchors to at
     * least LIGHT_GAME. With no anchor we fall back to GPU-only heuristics.
     */
    private fun rawRead(fgPkg: String?, gpuPct: Int): WorkloadContext {
        val isKnownGame = fgPkg != null && KnownGames.defaultHintFor(fgPkg) != null

        if (isKnownGame) {
            return if (gpuPct >= HEAVY_GPU_PCT) WorkloadContext.HEAVY_GAME else WorkloadContext.LIGHT_GAME
        }

        // No game anchor. A foreground package that is NOT a known game with high
        // sustained GPU is ambiguous (could be a non-listed game) → UNKNOWN, never
        // silently HEAVY (honesty: we did not detect a game).
        return when {
            gpuPct >= UNKNOWN_GPU_PCT -> WorkloadContext.UNKNOWN
            gpuPct >= VIDEO_GPU_PCT -> WorkloadContext.VIDEO
            else -> WorkloadContext.IDLE
        }
    }

    // ── Ordering helpers ─────────────────────────────────────────────────────────
    // We treat the chain IDLE < VIDEO < LIGHT_GAME < HEAVY_GAME as the "heaviness"
    // ladder. UNKNOWN is OFF the ladder — transitions into/out of it are neither
    // upgrades nor downgrades in the battery-risk sense, so we treat a move toward
    // UNKNOWN as a downgrade (slow to commit; conservative).

    private fun weight(c: WorkloadContext): Int = when (c) {
        WorkloadContext.IDLE -> 0
        WorkloadContext.VIDEO -> 1
        WorkloadContext.LIGHT_GAME -> 2
        WorkloadContext.HEAVY_GAME -> 3
        WorkloadContext.UNKNOWN -> -1 // off-ladder; moving here is a (slow) downgrade
    }

    private fun isHeavierThan(a: WorkloadContext, b: WorkloadContext): Boolean =
        weight(a) > weight(b)

    private fun isLighterThan(a: WorkloadContext, threshold: WorkloadContext): Boolean =
        weight(a) < weight(threshold)
}
