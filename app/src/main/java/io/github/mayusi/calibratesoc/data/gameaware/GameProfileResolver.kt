package io.github.mayusi.calibratesoc.data.gameaware

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import kotlinx.serialization.Serializable

/**
 * Game-aware profile resolver — pure logic engine.
 *
 * Maps a foreground package name to a [GamePlan]: the combination of a
 * saved-profile id, an AutoTDP profile, and an optional FPS cap that
 * together represent "the best known setup for this game/emulator."
 *
 * ## Data sources (all pure — no Android)
 *
 *  - [perGameRecords]     — user-defined records; highest priority.
 *    Built by the service layer from a persistent store (DataStore/JSON)
 *    and passed as an immutable snapshot.
 *  - [defaultHints]       — [KnownGames.defaultHintFor]; lowest priority.
 *    Sensible starting points for known emulators/games, always
 *    overridable by a user record.
 *
 * ## Resolution order
 *
 *  1. Exact match in [perGameRecords] → return the record as a [GamePlan].
 *  2. Known-game classifier ([KnownGames.defaultHintFor]) → return the
 *     hint with [GamePlan.isLearnedGood] = false (suggestion, not confirmed).
 *  3. No match → return null.
 *
 * Null means "no mapping" — the caller must NOT apply any profile change.
 * This is the honesty invariant: we never silently apply a random profile
 * to an unmapped app.
 *
 * ## Per-game records
 *
 * A [PerGameRecord] stores:
 *  - [PerGameRecord.packageName]  — exact package id.
 *  - [PerGameRecord.profileId]    — id of the [UserProfile] to apply, or null.
 *  - [PerGameRecord.autoTdpProfile] — AutoTDP control profile, or null.
 *  - [PerGameRecord.fpsCapHz]     — target FPS cap, or null (unlimited).
 *  - [PerGameRecord.learnedGood]  — user has confirmed this works well.
 *
 * ## Usage
 *
 * ```kotlin
 * val snapshot: Map<String, PerGameRecord> = perGameStore.snapshot()
 * val plan = GameProfileResolver.resolve(foregroundPackage, snapshot)
 * if (plan != null) {
 *     plan.profileId?.let { profileApplier.apply(it) }
 *     plan.autoTdpProfile?.let { autoTdpController.setProfile(it) }
 *     plan.fpsCapHz?.let { fpsLimiter.setCap(it) }
 * }
 * ```
 */
object GameProfileResolver {

    /**
     * Resolve the best [GamePlan] for [packageName].
     *
     * @param packageName     Foreground app package (e.g. "org.ppsspp.ppsspp").
     * @param perGameRecords  Snapshot of user-saved per-game records.
     *                        Key = packageName, value = [PerGameRecord].
     *
     * @return The best [GamePlan], or null when the package is completely
     *         unknown (no user record and not in [KnownGames]).
     */
    fun resolve(
        packageName: String,
        perGameRecords: Map<String, PerGameRecord>,
    ): GamePlan? {
        // Priority 1: explicit user record.
        perGameRecords[packageName]?.let { record ->
            return GamePlan(
                packageName = packageName,
                profileId = record.profileId,
                autoTdpProfile = record.autoTdpProfile,
                fpsCapHz = record.fpsCapHz,
                isLearnedGood = record.learnedGood,
                source = GamePlanSource.USER_RECORD,
            )
        }

        // Priority 2: known-game default hint.
        KnownGames.defaultHintFor(packageName)?.let { hint ->
            return GamePlan(
                packageName = packageName,
                profileId = hint.profileId,
                autoTdpProfile = hint.autoTdpProfile,
                fpsCapHz = hint.fpsCapHz,
                isLearnedGood = false, // hints are suggestions; user hasn't confirmed
                source = GamePlanSource.KNOWN_GAME_HINT,
            )
        }

        return null
    }

    /**
     * Convenience overload: resolve from a list of records.
     * Useful when the store hands back a list instead of a map.
     */
    fun resolve(
        packageName: String,
        perGameRecordList: List<PerGameRecord>,
    ): GamePlan? = resolve(
        packageName = packageName,
        perGameRecords = perGameRecordList.associateBy { it.packageName },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * User-defined record for one game/emulator package.
 *
 * Stored in the per-game DataStore as JSON (via kotlinx.serialization).
 * Any field may be null — a null value means "don't change this axis when
 * switching to this app."
 */
@Serializable
data class PerGameRecord(
    /** Exact Android package name. */
    val packageName: String,
    /** Id of a [UserProfile] to apply, or null = don't change profile. */
    val profileId: String?,
    /** AutoTDP control profile, or null = don't change AutoTDP. */
    val autoTdpProfile: AutoTdpProfile?,
    /** Target display FPS cap (Hz), or null = no cap. */
    val fpsCapHz: Int?,
    /**
     * True when the user has explicitly confirmed ("this setup works great
     * for this game").  Used by the UI to show a "verified" badge and by
     * the resolver to set [GamePlan.isLearnedGood].
     */
    val learnedGood: Boolean = false,
)

/**
 * The resolved action set for a foreground package.
 *
 * Each field is nullable: a null value means "no action on that axis."
 * The service layer applies only the non-null axes to avoid clobbering
 * settings the user has manually tuned.
 */
data class GamePlan(
    /** The package this plan was resolved for. */
    val packageName: String,
    /** Profile id to apply; null = leave current profile unchanged. */
    val profileId: String?,
    /** AutoTDP profile to activate; null = leave AutoTDP unchanged. */
    val autoTdpProfile: AutoTdpProfile?,
    /** FPS cap in Hz; null = no cap / unlimited. */
    val fpsCapHz: Int?,
    /**
     * Whether this plan is "learned good" — either the user confirmed it,
     * or it came from a user record (vs. a default hint).
     */
    val isLearnedGood: Boolean,
    /** Where this plan came from — for UI display and telemetry. */
    val source: GamePlanSource,
)

/** Origin of a [GamePlan] — for display and debugging. */
enum class GamePlanSource {
    /** Came from an explicit user-saved [PerGameRecord]. Highest trust. */
    USER_RECORD,
    /**
     * Came from [KnownGames.defaultHintFor].  A reasonable starting suggestion,
     * but the user hasn't confirmed it works well for their specific setup.
     */
    KNOWN_GAME_HINT,
}
