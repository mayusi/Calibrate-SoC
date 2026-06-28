package io.github.mayusi.calibratesoc.data.monitor

import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the foreground app package name via the PServer root shell when
 * [PServerWriter] is transactable (i.e. [PServerWriter.transactableNow] is true
 * and the device runs PServer-LIVE firmware).
 *
 * ## Why this exists
 *
 * [GameFpsSampler.currentForegroundPkg] uses [UsageStatsManager] which:
 *   1. Requires the `PACKAGE_USAGE_STATS` permission (granted by our unlock script
 *      on most devices, but the unlock may not always have run).
 *   2. Queries a 10-second event window — so if the user switches apps quickly the
 *      very latest foreground package may be one event behind.
 *   3. Is denied outright on some AOSP / custom ROMs that restrict usage-stats.
 *
 * Via PServer root, `dumpsys activity activities | grep mResumedActivity` always
 * returns the CURRENT foreground activity package in real time, with no permission
 * requirement on the app UID, making this the most-reliable source when PServer is
 * live. The fallback is the existing UsageStats path — honesty is preserved either
 * way (both sources return null on failure, never a fabricated package name).
 *
 * ## Guard contract
 *
 * Both commands used by this reader are on the [PServerCommandGuard] ALLOW list:
 *   - `dumpsys activity activities` — allowed since Wave 3B (narrowly gated to the
 *     `activities` sub-command in [DUMPSYS_ACTIVITY_ALLOWED_SUBCOMMANDS]).
 *   - `dumpsys window` — allowed since Wave 3B (bare form only).
 *   - `grep` — already allowed (stdin-filter form, no file operands).
 * They go through [PServerWriter.executeShell], which validates every command
 * through the guard's single chokepoint. No direct binder bypass is possible.
 *
 * ## Honesty contract
 *
 * - Returns null when PServer is unavailable, the command fails, stdout is blank,
 *   or no package pattern is found in the output — never a fabricated name.
 * - The caller ([GameFpsSampler]) decides which source to trust: root if non-null,
 *   else UsageStats, else null.
 * - Runs on [Dispatchers.IO] (the executeShell call already switches internally;
 *   the outer withContext is belt-and-suspenders and avoids main-thread blocking).
 *
 * ## Source selection and parse logic
 *
 * Primary: `dumpsys activity activities | grep mResumedActivity`
 *   Output form (Android 10+): `mResumedActivity: ActivityRecord{… com.foo/.MainActivity …}`
 *   We split on whitespace and find the token containing a dot and a slash —
 *   `com.foo/.MainActivity` — then strip the class path to get `com.foo`.
 *
 * Fallback: `dumpsys window | grep mCurrentFocus`
 *   Output form: `mCurrentFocus=Window{… com.foo/com.foo.MainActivity}`
 *   Same extraction approach: find the `pkg/class` token and strip after `/`.
 *
 * Both patterns are well-established across AOSP 10–14 and OEM kernels.
 */
@Singleton
class RootForegroundReader @Inject constructor(
    private val pServerWriter: PServerWriter,
) {

    /**
     * Return the current foreground package via PServer root, or null when PServer
     * is not transactable or the read fails.
     *
     * Tries `dumpsys activity activities | grep mResumedActivity` first. Falls back
     * to `dumpsys window | grep mCurrentFocus` if that yields nothing (some kernels
     * suppress the activities sub-command output but window dump always works).
     */
    suspend fun readForegroundPkg(): String? = withContext(Dispatchers.IO) {
        if (!pServerWriter.transactableNow()) return@withContext null
        readViaActivityDump() ?: readViaWindowDump()
    }

    // ── Primary: dumpsys activity activities ─────────────────────────────────

    private suspend fun readViaActivityDump(): String? {
        // Guard allows: `dumpsys activity activities` (one segment) piped to grep.
        // The pipe is split by the guard's quote-aware segment splitter — each
        // segment (`dumpsys activity activities` and `grep 'mResumedActivity'`) is
        // independently validated.
        val cmd = "dumpsys activity activities | grep 'mResumedActivity'"
        val result = pServerWriter.executeShell(cmd) ?: return null
        val (_, stdout) = result
        // Exit status from a piped command reflects the LAST segment (grep). grep
        // exits 1 when no match found — treat both 0 and 1 as valid (non-null stdout
        // is the success signal here; a non-zero exit with blank stdout means no match).
        if (stdout.isBlank()) return null
        // Parse: `mResumedActivity: ActivityRecord{… com.foo/.MainActivity …}`
        // or on older Android: `mResumedActivity=ActivityRecord{… com.foo/.Main …}`.
        return extractPkgFromActivityRecord(stdout)
    }

    // ── Fallback: dumpsys window ──────────────────────────────────────────────

    private suspend fun readViaWindowDump(): String? {
        // Guard allows: `dumpsys window` (bare, no args) piped to grep.
        val cmd = "dumpsys window | grep 'mCurrentFocus'"
        val result = pServerWriter.executeShell(cmd) ?: return null
        val (_, stdout) = result
        if (stdout.isBlank()) return null
        // Parse: `mCurrentFocus=Window{… u0 com.foo/com.foo.MainActivity}`
        return extractPkgFromWindowFocus(stdout)
    }

    // ── Parse helpers ────────────────────────────────────────────────────────

    /**
     * Extract the package name from a `mResumedActivity: ActivityRecord{…}` line.
     * The component token has the form `com.pkg.name/.ActivityClass` or
     * `com.pkg.name/com.pkg.name.ActivityClass`. We split on whitespace, find
     * the token containing a `/`, and strip from the `/` onward.
     *
     * Returns null when no recognisable token is found (honest absence).
     */
    internal fun extractPkgFromActivityRecord(output: String): String? {
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (!trimmed.contains("mResumedActivity", ignoreCase = true)) continue
            // Tokens like `com.foo/.Main` or `com.foo/com.foo.Main`
            val tokens = trimmed.split(WHITESPACE)
            for (token in tokens) {
                val slashIdx = token.indexOf('/')
                if (slashIdx > 0) {
                    val pkg = token.substring(0, slashIdx)
                    if (PACKAGE_PATTERN.matches(pkg)) return pkg
                }
            }
        }
        return null
    }

    /**
     * Extract the package name from a `mCurrentFocus=Window{… com.pkg/…}` line.
     * The token before `/` in the last `pkg/class` pair is the package.
     *
     * Returns null when no recognisable token is found (honest absence).
     */
    internal fun extractPkgFromWindowFocus(output: String): String? {
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (!trimmed.contains("mCurrentFocus", ignoreCase = true)) continue
            val tokens = trimmed.split(WHITESPACE)
            for (token in tokens) {
                val slashIdx = token.indexOf('/')
                if (slashIdx > 0) {
                    val pkg = token.substring(0, slashIdx)
                    if (PACKAGE_PATTERN.matches(pkg)) return pkg
                }
            }
        }
        return null
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /**
         * Android package name pattern: two or more dot-separated segments,
         * each [A-Za-z0-9_]+. Rejects paths, raw class names without a dot,
         * and anything that slipped through as a stray token.
         */
        val PACKAGE_PATTERN = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
    }
}
