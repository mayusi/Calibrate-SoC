package io.github.mayusi.calibratesoc.data.session

/**
 * Domain type for a completed game session.
 *
 * [appLabel] is the human-readable name of the foreground app at session
 * start (e.g. "PPSSPP"). Null when PACKAGE_USAGE_STATS was not granted
 * or no foreground app was detected.
 *
 * [profileName] is the last-applied tune preset name, captured at session
 * start. Useful for comparing "did my 'Performance' profile run cooler
 * than 'Balanced'?". Null when no preset has ever been applied.
 *
 * [fpsAvailableDuringSampling] is an honest flag: if the HUD was NOT
 * running during the session (or PServer was unavailable), FPS data will
 * be absent across all samples. The UI checks this flag to show the
 * correct honesty disclaimer.
 */
data class GameSession(
    val id: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val appLabel: String?,
    /**
     * Foreground app package name at session end (e.g. "com.rp.retroarch").
     * Null when PACKAGE_USAGE_STATS was not granted or no foreground app was detected.
     * This is the stable machine-readable key for per-game learned params.
     */
    val packageName: String?,
    val profileName: String?,
    val samples: List<SessionSample>,
    val summary: SessionSummary,
    val fpsAvailableDuringSampling: Boolean,
)
