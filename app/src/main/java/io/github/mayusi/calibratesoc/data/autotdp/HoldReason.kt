package io.github.mayusi.calibratesoc.data.autotdp

/**
 * Machine-readable classification of WHY the AutoTDP engine produced the
 * decision it did this tick.
 *
 * The engine also keeps a free-form `reason` string (see [TdpDecision.reason]);
 * [HoldReason] is the *clean label* the UI shows by default, while the raw
 * string is shown on expand. The two are complementary — never a substitute for
 * each other.
 *
 * HONESTY INVARIANT — [LOAD_BLIND_HOLDING] vs [IDLE_HOLDING] is mandatory:
 *   When CPU load is unreadable (no /proc/stat access, freq-proxy unavailable),
 *   the engine is "blind" and merely holding because it has nothing to act on.
 *   Surfacing that as "idle" would be a LIE — the CPU may actually be pegged.
 *   The two states are kept distinct so the UI can say "load unreadable — holding"
 *   instead of falsely claiming the device is idle.
 */
enum class HoldReason {
    /**
     * A big/prime core was saturated; the engine RELAXED (unparked a core /
     * stepped the big cap up). The CPU is the bottleneck.
     */
    CPU_BOUND_RELAXING,

    /**
     * The workload was confirmed GPU-bound across the whole window; the engine
     * TIGHTENED (parked a prime core / stepped the big cap down) to redirect the
     * power budget to the GPU.
     */
    GPU_BOUND_CAPPING,

    /**
     * BATTERY_TARGET profile held a proportional big-cluster cap derived from the
     * user's mW budget, even though neither saturation nor GPU-bound fired.
     */
    BATTERY_TARGET_HOLDING,

    /**
     * The engine is holding because CPU load is UNREADABLE on at least one window
     * sample (load-blind). This is NOT idle — the CPU could be busy; we simply
     * cannot measure it, so we conservatively do nothing. Distinct from
     * [IDLE_HOLDING] by design — see the class-level honesty invariant.
     */
    LOAD_BLIND_HOLDING,

    /**
     * The engine is holding because the device is genuinely lightly loaded —
     * CPU load IS readable and below the saturation threshold, and the workload
     * is not GPU-bound. Only emitted when load is known.
     */
    IDLE_HOLDING,

    /**
     * No telemetry was available at all (empty window). The engine made no real
     * decision this tick.
     */
    NO_TELEMETRY,
}
