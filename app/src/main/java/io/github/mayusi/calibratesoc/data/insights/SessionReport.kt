package io.github.mayusi.calibratesoc.data.insights

/**
 * Derived performance report for a completed game session.
 *
 * All numeric fields are derived solely from measured [SessionSample] data —
 * nothing is fabricated. Fields are nullable where the underlying data may be
 * genuinely absent (e.g. FPS is null when the HUD was not running; power is
 * null when the battery HAL did not report draw).
 *
 * [energyMwh] uses the same trapezoid integral as [ThrottleAnalysis]: no abs()
 * on the power values because [SessionSample.batteryW] is already
 * positive = discharging.
 *
 * [autoTdpSavedMwh] is derived from measured AutoTDP savings (deltaMw × elapsed
 * time) stored with the session. Null means AutoTDP was not active or did not
 * have enough samples to produce a [SavingsResult.enoughData] == true result.
 *
 * [verdict] is a single plain-English sentence summarising the session for the
 * user (e.g. "Held 59 fps, peaked 62 °C, AutoTDP saved ~1.8 W."). Always
 * non-null; says "Not enough data" when the session is too short.
 */
data class SessionReport(
    /** DB row id of the originating [SessionEntity]. */
    val sessionId: Long,
    /** Wall-clock start time, epoch ms. */
    val startedAtMs: Long,
    /** Duration of the session in milliseconds. */
    val durationMs: Long,
    /** Foreground app label (may be null when PACKAGE_USAGE_STATS was not granted). */
    val appLabel: String?,
    /** Last-applied tune profile name (null when no preset was ever applied). */
    val profileName: String?,

    // ── FPS ────────────────────────────────────────────────────────────────
    /** Mean FPS across all samples that had FPS data; null when HUD was not active. */
    val avgFps: Float?,
    /** Maximum FPS observed during the session; null when no FPS data. */
    val peakFps: Float?,
    /** 1st-percentile FPS (worst 1% of frames); null when fewer than 100 FPS samples. */
    val p1LowFps: Float?,

    // ── Thermal ────────────────────────────────────────────────────────────
    /** Peak CPU temperature across all samples; null when no thermal data. */
    val peakCpuTempC: Float?,
    /** Peak GPU temperature across all samples; null when GPU zone not reported. */
    val peakGpuTempC: Float?,

    // ── Power / energy ─────────────────────────────────────────────────────
    /** Mean battery draw in Watts; null when no power data in samples. */
    val avgPowerW: Double?,
    /**
     * Total energy consumed during the session (mWh), computed via trapezoid
     * integral over real elapsed-ms deltas between consecutive samples that have
     * power data. Null when fewer than 2 samples with power data exist.
     * batteryW is already positive = discharging — no abs() applied.
     */
    val energyMwh: Double?,
    /**
     * Estimated energy saved by AutoTDP this session (mWh).
     * Derived as: deltaMw × durationMs / 3_600_000.
     * Null when AutoTDP was not active, or [SavingsResult.enoughData] was false.
     */
    val autoTdpSavedMwh: Double?,

    // ── Throttle ───────────────────────────────────────────────────────────
    /** Number of heuristic throttle events detected (hot-temp AND FPS-dip coinciding). */
    val throttleEventCount: Int,

    // ── Summary ────────────────────────────────────────────────────────────
    /**
     * One-line plain-English verdict. Examples:
     *   "Held 59 fps, peaked 62 °C, AutoTDP saved ~1.8 W."
     *   "No FPS data (HUD was not active); peaked 74 °C at 8.4 W avg."
     *   "Not enough data (only 3 samples recorded)."
     */
    val verdict: String,
)
