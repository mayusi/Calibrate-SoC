package io.github.mayusi.calibratesoc.data.profiles

import io.github.mayusi.calibratesoc.data.autotdp.GoalProfile
import kotlinx.serialization.Serializable

/**
 * A FULL per-app tune bundle — everything that fires when a mapped game/app
 * comes to the foreground.
 *
 * All fields are OPTIONAL. A bundle where only [profileId] is set is exactly
 * equivalent to today's single-profile override (back-compat guarantee).
 *
 * ## Fields
 *
 * [profileId]      — existing UserProfile to apply (same as the old override).
 *                    null = do not apply a profile/preset on launch.
 *
 * [autoTdpGoal]    — start the AutoTDP daemon with this goal (one of the 5
 *                    [GoalProfile] modes: MAX_FPS, BALANCED_SMART, COOL_QUIET,
 *                    BATTERY_SAVER, AUTO). null = do not touch AutoTDP.
 *
 * [refreshRateHz]  — pin the display to this refresh rate (float, e.g. 90f, 120f).
 *                    null = do not change the refresh-rate preference.
 *                    Note: actual application happens via [RefreshRateController]
 *                    at the Activity level — the bundle records the *intent*;
 *                    Wave 4 UI and ForegroundAppWatcher use [RefreshRateController]
 *                    to carry it out.
 *
 * [fanMode]        — vendor fan_mode preset index (AYN/Retroid Settings.System
 *                    key "fan_mode"). null = do not change fan mode.
 *
 * [gameBoostOnLaunch] — fire Wave 3a's GameBoost service on launch.
 *                    null / false = do not boost. Set to true only on supported
 *                    devices (Wave 4 UI gates this behind a capability check).
 *
 * ## Back-compat
 *
 * The old [ProfileStore.perAppOverrides] Map<String,String> (pkg → profileId)
 * is preserved in [ProfileStore] so existing serialized stores load correctly.
 * [ForegroundAppWatcher] now consults [ProfileStore.perAppBundles] FIRST, and
 * falls back to [ProfileStore.perAppOverrides] when no bundle exists for a
 * package. This makes a bundle with only [profileId] set == the old behavior,
 * so zero data migration is needed.
 *
 * ## Honesty
 *
 * This struct is pure data — no I/O, no Android context. It is serialized into
 * [ProfileStore.perAppBundles] alongside the existing override map.
 *
 * The [autoTdpGoal] field records a GOAL enum, not a service intent. Actual
 * start/stop is wired through [GameBoostOrAutoTdpLauncher] (a thin interface so
 * ForegroundAppWatcher can be tested without starting a real Service). The
 * launcher is implemented to call [AutoTdpController.start] + stop when Wave 3a's
 * GameBoost surface is available; it falls back to AutoTDP-only until then.
 */
@Serializable
data class PerAppBundle(
    /**
     * Profile / preset to apply on launch. Null = skip profile apply.
     * Matches the profile id key used in [ProfileStore.perAppOverrides].
     */
    val profileId: String? = null,

    /**
     * AutoTDP goal to start on launch. Null = do not start AutoTDP.
     * One of the 5 [GoalProfile] modes (MAX_FPS, BALANCED_SMART, COOL_QUIET,
     * BATTERY_SAVER, AUTO).
     */
    val autoTdpGoal: GoalProfile? = null,

    /**
     * Preferred display refresh rate in Hz (e.g. 90f, 120f). Null = leave
     * the current refresh-rate preference unchanged.
     * Applied via [RefreshRateController.setPreferredHz] on game launch;
     * reverted to null (system default) when the game exits.
     */
    val refreshRateHz: Float? = null,

    /**
     * AYN/Retroid fan_mode index (0=Quiet, 4=Smart, 5=Sport). Null = no change.
     * Written via the existing SETTINGS_SYSTEM writer path on launch; reverted on
     * game exit. The fan key is looked up from DeviceAdapterRegistry at apply time
     * (the bundle stores the abstract "desired mode", not a key name).
     */
    val fanMode: Int? = null,

    /**
     * When true, trigger Wave 3a's GameBoost service on launch.
     * False / null = do not boost.
     *
     * HONESTY: this flag calls the [GameBoostLauncher] interface defined in this
     * file, bound to [GameBoostLauncherImpl] (→ Wave 3a's GameBoostService).
     */
    val gameBoostOnLaunch: Boolean = false,

    /**
     * PROVENANCE (Wave: auto-configure known games).
     *
     * True when this bundle was AUTO-CREATED by the app from a [KnownGames]
     * hint — i.e. the user never tapped anything; Calibrate filled in a sensible
     * starting tune the first time a recognised game launched. False (the default)
     * means the user built this bundle themselves on the Per-App sheet.
     *
     * ## Why this field matters
     *  - HONESTY: the UI shows "Auto-configured" vs "You set this" so the user is
     *    never misled about who chose the settings.
     *  - SAFE UNDO: the auto-create undo path only removes a bundle when this flag
     *    is true, so it can never silently delete a tune the user hand-built.
     *  - NON-OVERRIDE: the auto-create path only fills the EMPTY case — it never
     *    replaces an existing bundle (user-set or already auto-created), so a user
     *    edit to an auto-created bundle is preserved on the next launch.
     *
     * ## Back-compat
     * Appended last with a default of `false`. Older persisted [ProfileStore] JSON
     * that predates this field deserializes with [autoCreated] == false (the
     * correct interpretation: anything that already existed was user-set). New
     * writes encode it; round-trips are lossless. No migration needed.
     */
    val autoCreated: Boolean = false,
)

/**
 * Minimal interface the [ForegroundAppWatcher] calls to start/stop Game Boost.
 *
 * Bound to [GameBoostLauncherImpl] (which delegates to Wave 3a's GameBoostService)
 * in [ProfilesModule]. The Wave 3b code calls [startBoost] when a bundle with
 * [PerAppBundle.gameBoostOnLaunch] is true fires, and [stopBoost] when the game
 * leaves the foreground.
 */
interface GameBoostLauncher {
    /**
     * Start the Game Boost session. Called on foreground-enter of a mapped game
     * that has [PerAppBundle.gameBoostOnLaunch] == true.
     */
    suspend fun startBoost(packageName: String)

    /**
     * Stop the active Game Boost session (if any). Called when the boosted game
     * leaves the foreground.
     */
    suspend fun stopBoost(packageName: String)
}
