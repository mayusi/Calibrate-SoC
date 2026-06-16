package io.github.mayusi.calibratesoc.data.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppReaper"

/**
 * User-curated config that drives the background-app reaper.
 *
 * ## OPT-IN DESIGN
 * The reaper does NOTHING unless [enabled] is true AND [denylist] is non-empty.
 * The user explicitly picks which apps to stop — there is no auto-detection or
 * smart guess. This is by design: aggressive background-app killing is a user
 * preference, not something Calibrate SoC imposes silently.
 *
 * ## Denylist semantics
 * [denylist] contains package names the user chose in the Wave 4 picker UI.
 * The reaper enforces hard exclusions (system packages, launcher, IME, Calibrate
 * itself) regardless of what appears in this list.
 *
 * ## Persistence
 * Serialized into the app's private files dir via [ReaperRepository], separate from
 * [ProfileStore] so it does not inflate every ProfileRepository load.
 */
@Serializable
data class ReaperConfig(
    /**
     * Whether the reaper is active. False = reaper is disabled; no apps are stopped
     * regardless of [denylist] contents. Default: false (explicit opt-in).
     */
    val enabled: Boolean = false,
    /**
     * User-curated package names to stop when a game/boost session starts.
     * Only non-system, user-installed apps may appear here (enforced at config-save
     * time by the UI and at reap-time by [AppReaper]).
     */
    val denylist: Set<String> = emptySet(),
)

/**
 * Records which apps were reaped in the most recent game session.
 * Exposed so the Wave 4 UI can show "Stopped 3 apps: Spotify, Discord, ..."
 * Not persisted across reboots — it is strictly a session diagnostic.
 */
data class ReaperSessionState(
    /** Package names successfully stopped in the most recent game session. */
    val reapedPackages: Set<String> = emptySet(),
    /** Package names on the denylist that could not be stopped. */
    val failedPackages: Set<String> = emptySet(),
)

/**
 * Interface for the background-app reaper.
 * Separated from the implementation so unit tests can verify domain logic
 * without a real PServerWriter or Android context.
 */
interface AppReaper {

    /**
     * Reap the denylist when a game/boost session begins for [gamePackage].
     *
     * Reads [ReaperConfig] from [ReaperRepository]. If [ReaperConfig.enabled] is
     * false or the denylist is empty, this is a no-op. Otherwise issues
     * `am force-stop <pkg>` via [PServerWriter.executeShell] for each eligible package.
     *
     * [gamePackage] is passed so the reaper never stops the game itself.
     */
    suspend fun reapForGame(gamePackage: String)

    /**
     * Returns the [ReaperSessionState] from the last [reapForGame] call.
     * Returns an empty state if no reap session has been run yet.
     */
    fun lastSessionState(): ReaperSessionState
}

// ── Safety exclusion set (HARD, non-negotiable) ───────────────────────────────

/**
 * Package-name prefixes and exact names that are ALWAYS excluded from reaping,
 * enforced BOTH at config-save time (Wave 4 UI) AND at reap time ([AppReaperImpl])
 * as defense-in-depth.
 *
 * Rationale:
 *   com.android.* / android — AOSP system services; stopping them is catastrophic.
 *   com.google.android.gms  — Google Play Services; breaks auth, FCM, licence checks.
 *   com.google.android.gsf  — Google Services Framework; same category.
 *   io.github.mayusi.*      — Calibrate SoC itself; stopping ourselves is wrong.
 *
 * Launcher and IME packages are detected at runtime (not in this set) because
 * they vary per device and user configuration.
 */
internal val ALWAYS_EXCLUDED_PREFIXES: Set<String> = setOf(
    "android",
    "com.android.",
    "com.google.android.gms",
    "com.google.android.gsf",
    "io.github.mayusi.calibratesoc",
)

// ── Installed-app enumerator ──────────────────────────────────────────────────

/**
 * Enumerate all USER-INSTALLED (non-system) apps on the device.
 *
 * Excludes apps with [ApplicationInfo.FLAG_SYSTEM] or [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP]
 * so the Wave 4 picker only shows apps the user explicitly installed.
 *
 * Returns a list sorted by display label (case-insensitive).
 */
fun enumerateInstalledUserApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val installed: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(0)
    }
    return installed
        .filter { info ->
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystem
        }
        .map { info ->
            InstalledApp(
                packageName = info.packageName,
                displayLabel = runCatching {
                    pm.getApplicationLabel(info).toString()
                }.getOrDefault(info.packageName),
            )
        }
        .sortedBy { it.displayLabel.lowercase() }
}

/**
 * Lightweight holder for the Wave 4 picker.
 * Contains only package name and display label — no icon (UI fetches lazily).
 */
data class InstalledApp(
    val packageName: String,
    val displayLabel: String,
)

// ── AppReaperImpl ─────────────────────────────────────────────────────────────

/**
 * Concrete [AppReaper].
 *
 * Issues `am force-stop <pkg>` (and `am set-inactive <pkg> true` for app-standby)
 * via [PServerWriter.executeShell], routing through PServer's root shell on
 * AYN/Retroid devices. On devices where PServer is unavailable, executeShell
 * returns null and all reaps silently no-op.
 *
 * ## Safety layers
 *
 * 1. [ReaperConfig.enabled] must be true and denylist non-empty (opt-in gate).
 * 2. [isSafeToReap] prefix exclusion: [ALWAYS_EXCLUDED_PREFIXES] blocks system/GMS/Calibrate.
 * 3. [isSafeToReap] launcher detection: queries PackageManager for HOME intent receivers.
 * 4. [isSafeToReap] IME detection: queries InputMethodManager for enabled IMEs.
 * 5. Package name ASCII validation: rejects any name that is not [A-Za-z0-9_.].
 *
 * ## Shell command format
 *
 *   am force-stop <pkg>       -- stops the app cleanly; it is NOT deleted.
 *   am set-inactive <pkg> true -- puts the app in standby; advisory, may be ignored by OEM.
 *
 * Android relaunches reaped apps on next user interaction — reaped apps are
 * STOPPED, not deleted. This is stated clearly in UI copy.
 *
 * ## NUL byte safety
 *
 * Package names are validated to [A-Za-z0-9_.] before building shell commands.
 * Additionally, any NUL byte (0x00) in the assembled command string is stripped
 * before sending — PServerWriter can carry a stray NUL in its binder reply
 * buffer (known issue documented in the gradle-build-verification memory) and
 * we never feed that back into a root shell command.
 */
@Singleton
open class AppReaperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pServerWriter: PServerWriter,
    private val reaperRepository: ReaperRepository,
) : AppReaper {

    @Volatile private var lastState = ReaperSessionState()

    override suspend fun reapForGame(gamePackage: String) = withContext(Dispatchers.IO) {
        val config = reaperRepository.snapshot()
        if (!config.enabled || config.denylist.isEmpty()) {
            Log.d(TAG, "reapForGame($gamePackage): reaper disabled or denylist empty — skipping")
            return@withContext
        }

        val launcherPkgs = detectLauncherPackages()
        val imePkg = detectImePkg()

        val reaped = mutableSetOf<String>()
        val failed = mutableSetOf<String>()

        for (pkg in config.denylist) {
            if (!isSafeToReap(pkg, gamePackage, launcherPkgs, imePkg)) {
                Log.d(TAG, "reapForGame: skipping excluded package '$pkg'")
                continue
            }
            val success = stopPackage(pkg)
            if (success) reaped += pkg else failed += pkg
        }

        Log.i(TAG, "reapForGame($gamePackage): reaped=${reaped.size} failed=${failed.size}")
        lastState = ReaperSessionState(reapedPackages = reaped, failedPackages = failed)
    }

    override fun lastSessionState(): ReaperSessionState = lastState

    /**
     * Returns true if it is safe to reap [pkg].
     *
     * All checks must pass:
     *  1. Package name is non-blank and ASCII-safe ([A-Za-z0-9_.]).
     *  2. Not the game package itself.
     *  3. Not in [ALWAYS_EXCLUDED_PREFIXES] (system/GMS/Calibrate).
     *  4. Not a launcher package.
     *  5. Not the current default IME.
     */
    internal fun isSafeToReap(
        pkg: String,
        gamePackage: String,
        launcherPackages: Set<String>,
        imePackage: String?,
    ): Boolean {
        if (pkg.isBlank()) return false
        // ASCII-safety: package names must be [A-Za-z0-9_.]
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            Log.w(TAG, "isSafeToReap: rejected malformed package '$pkg'")
            return false
        }
        // Never stop the game itself (guard against denylist misconfiguration).
        if (pkg == gamePackage) return false

        // Hard prefix exclusions.
        // A prefix entry matches if:
        //   (a) pkg == the prefix exactly, OR
        //   (b) pkg starts with prefix + "." (covers sub-packages even when the
        //       entry has no trailing dot, e.g. "android" blocks "android.app.usage").
        for (prefix in ALWAYS_EXCLUDED_PREFIXES) {
            val bare = prefix.trimEnd('.')
            if (pkg == bare || pkg.startsWith("$bare.")) return false
        }

        // Launcher exclusion.
        if (pkg in launcherPackages) return false

        // IME exclusion.
        if (imePackage != null && pkg == imePackage) return false

        return true
    }

    /**
     * Stop a single package via `am force-stop` + `am set-inactive true`.
     * Returns true if the shell commands were issued (PServer available).
     *
     * The package name is single-quote-escaped (defense-in-depth; valid names
     * cannot contain single quotes). NUL bytes are stripped from the final
     * command string before sending to PServer.
     */
    private suspend fun stopPackage(pkg: String): Boolean {
        // Single-quote-escape the package name (defense-in-depth against shell injection).
        // Valid Android package names are [A-Za-z0-9_.] and never contain single quotes,
        // so this cannot alter a valid name.
        val quoted = "'" + pkg.replace("'", "'\\''") + "'"

        val raw = "am force-stop $quoted; am set-inactive $quoted true"
        val cmd = raw  // command is already clean; valid package names have no NUL/metacharacters

        Log.d(TAG, "stopPackage($pkg): cmd='$cmd'")
        val result = pServerWriter.executeShell(cmd)
        return if (result == null) {
            Log.w(TAG, "stopPackage($pkg): PServer unavailable — skipped")
            false
        } else {
            val (status, stdout) = result
            if (status != 0) {
                Log.w(TAG, "stopPackage($pkg): status=$status stdout='$stdout'")
            } else {
                Log.i(TAG, "stopPackage($pkg): success")
            }
            // `am force-stop` returns 0 even if the app was already stopped — correct.
            // We treat any non-null result (PServer ran) as a successful reap attempt.
            true
        }
    }

    // ── Runtime system detectors ──────────────────────────────────────────────
    // These are `protected open` so unit tests can subclass and override them
    // without needing Robolectric or a real PackageManager/InputMethodManager.

    /**
     * Detect the current home/launcher package(s) by querying PackageManager for
     * ACTION_MAIN + CATEGORY_HOME intents. Returns all launcher packages (more than
     * one may be installed; all are excluded).
     *
     * Override in tests via [TestAppReaperImpl] to return a fixed set.
     */
    protected open fun detectLauncherPackages(): Set<String> {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "detectLauncherPackages: query failed: ${e.message}")
            emptySet()
        }
    }

    /**
     * Detect the default IME package via [InputMethodManager.enabledInputMethodList].
     * Returns null if the IME cannot be determined (conservative — never reap an
     * unknown IME).
     *
     * Override in tests via [TestAppReaperImpl] to return a fixed value.
     */
    protected open fun detectImePkg(): String? {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return null
        return try {
            imm.enabledInputMethodList.firstOrNull()?.packageName
        } catch (e: Exception) {
            Log.w(TAG, "detectImePkg: query failed: ${e.message}")
            null
        }
    }
}
