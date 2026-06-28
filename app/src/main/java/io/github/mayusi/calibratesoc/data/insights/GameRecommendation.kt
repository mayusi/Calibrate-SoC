package io.github.mayusi.calibratesoc.data.insights

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.gameaware.KnownGames

/**
 * Tier indicating the confidence and source of a [GameRecommendation].
 *
 * MEASURED  — backed by at least [InsightsAggregator.MIN_SESSIONS_FOR_PROFILE_RANK]
 *             real recorded sessions for this package with the named profile.
 *             All numbers are real measured values from the user's own data.
 *
 * SUGGESTED — backed by [KnownGames.defaultHintFor] only; no personal session data
 *             exists yet. Displayed as a starting suggestion, not a proven result.
 *             Never labelled "best" or "proven."
 */
enum class RecommendationTier { MEASURED, SUGGESTED }

/**
 * A profile recommendation for one game package.
 *
 * [packageName]            — the exact Android package name this is for.
 * [appLabel]               — display name (best-effort; may be null).
 * [profileName]            — the user profile to apply, or null when tier==SUGGESTED
 *                            (no profile can be named without session data).
 * [tier]                   — whether this recommendation is MEASURED (proven by the
 *                            user's own sessions) or SUGGESTED (classifier default).
 * [avgFps]                 — measured average FPS for this profile, or null when no
 *                            FPS data exists. NEVER populated for SUGGESTED.
 * [avgThrottlePerSession]  — measured throttle events/session. null for SUGGESTED.
 * [sessionCount]           — how many sessions contributed. 0 for SUGGESTED.
 * [suggestedAutoTdpProfile]— for SUGGESTED tier only: the KnownGames AutoTDP hint.
 *                            null for MEASURED (AutoTDP is separate from profile).
 * [evidence]               — plain-English explanation using ONLY real numbers.
 *                            Never fabricates percentages or vague claims.
 */
data class GameRecommendation(
    val packageName: String,
    val appLabel: String?,
    val profileName: String?,
    val tier: RecommendationTier,
    val avgFps: Float?,
    val avgThrottlePerSession: Double?,
    val sessionCount: Int,
    val suggestedAutoTdpProfile: AutoTdpProfile?,
    val evidence: String,
)

/**
 * Pure, testable resolver: returns a [GameRecommendation] for a given package
 * from the aggregated insights summary, or null when no recommendation can
 * honestly be made.
 *
 * Resolution order:
 *  1. MEASURED — bestProfilePerPackage entry with sessionCount >= 2 exists.
 *  2. SUGGESTED — KnownGames.defaultHintFor(packageName) is non-null.
 *  3. null — not enough information; caller shows nothing (not even a placeholder).
 *
 * Honesty invariants:
 *  - "Proven for you" / "MEASURED" only appears at >= 2 sessions (the aggregator
 *    already enforces MIN_SESSIONS_FOR_PROFILE_RANK=2 before placing an entry in
 *    bestProfilePerPackage, so every MEASURED entry is already >=2).
 *  - Avg FPS is only included in the evidence string when non-null.
 *  - SUGGESTED tier never claims "best," "proven," or shows made-up numbers.
 */
object GameRecommender {

    fun recommendFor(
        packageName: String,
        appLabel: String?,
        summary: InsightsAggregator.InsightsSummary,
    ): GameRecommendation? {
        // ── 1. MEASURED tier: real per-package data ──────────────────────────
        val measured = summary.bestProfilePerPackage[packageName]
        if (measured != null) {
            val evidenceParts = mutableListOf<String>()
            evidenceParts += "Proven for you: '${measured.profileName}'"
            if (measured.avgFps != null) {
                evidenceParts += "averaged ${"%.0f".format(measured.avgFps)} fps"
            }
            evidenceParts += "with ${"%.1f".format(measured.avgThrottleEventsPerSession)} throttles/session"
            evidenceParts += "over ${measured.sessionCount} sessions."
            val evidence = evidenceParts.joinToString(" ")

            return GameRecommendation(
                packageName = packageName,
                appLabel = measured.appLabel ?: appLabel,
                profileName = measured.profileName,
                tier = RecommendationTier.MEASURED,
                avgFps = measured.avgFps,
                avgThrottlePerSession = measured.avgThrottleEventsPerSession,
                sessionCount = measured.sessionCount,
                suggestedAutoTdpProfile = null,
                evidence = evidence,
            )
        }

        // ── 2. SUGGESTED tier: KnownGames hint ──────────────────────────────
        val hint = KnownGames.defaultHintFor(packageName)
        if (hint != null) {
            val profileLabel = hint.autoTdpProfile?.name
                ?.replace('_', ' ')
                ?.lowercase()
                ?.replaceFirstChar { it.uppercase() }
                ?: "Balanced"
            val evidence =
                "Suggested starting point (no sessions recorded yet for this game): " +
                "a $profileLabel AutoTDP goal tends to suit this title. " +
                "Play a session to get a personalized profile recommendation."
            return GameRecommendation(
                packageName = packageName,
                appLabel = appLabel,
                profileName = null,
                tier = RecommendationTier.SUGGESTED,
                avgFps = null,
                avgThrottlePerSession = null,
                sessionCount = 0,
                suggestedAutoTdpProfile = hint.autoTdpProfile,
                evidence = evidence,
            )
        }

        // ── 3. No recommendation ─────────────────────────────────────────────
        return null
    }
}
