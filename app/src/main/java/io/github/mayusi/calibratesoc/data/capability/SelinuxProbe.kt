package io.github.mayusi.calibratesoc.data.capability

import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device's SELinux enforcement mode — the SINGLE signal that actually
 * gates app-UID live tuning on these handhelds.
 *
 * ## Why this exists (the honesty fix)
 *
 * The app used to claim that AYN's `app_whiteList` was the PServer gate. That is
 * FALSE: `/system/bin/pservice` never checks `app_whiteList`. Proven live on the
 * Odin 3 + Retroid Pocket 6 — the app ran root via PServer with the package
 * REMOVED from the whitelist. The real gate is SELinux mode:
 *   - PERMISSIVE (or a firmware that lets our UID transact) → PServer works
 *     zero-setup.
 *   - ENFORCING and our UID blocked → no app-only fix; the only lever is the
 *     vendor "Force SELinux" toggle (a documented LAST RESORT — it breaks many
 *     emulators, so most users should never touch it).
 *
 * So the honest thing to surface to the user is the SELinux mode, not a
 * whitelist that does nothing.
 *
 * ## Two reads, most-trustworthy-wins
 *
 *  1. **world-readable file**: `/sys/fs/selinux/enforce` — `0` = permissive,
 *     `1` = enforcing. On most devices this is readable by any UID. On some
 *     locked-down firmwares (Odin 3 / Thor) our UID gets `Permission denied`,
 *     so this read may fail (→ null), which is exactly why we have step 2.
 *  2. **authoritative `getenforce`**: when PServer is live we run `getenforce`
 *     through PServer's root shell and parse `Enforcing` / `Permissive`. This is
 *     the truth even on firmwares where the file is unreadable to us, because it
 *     runs as root.
 *
 * Returns `null` (unknown) when neither read produced a clear answer — we never
 * guess. A null is rendered as "SELinux mode unknown", not as a fabricated value.
 *
 * Hermetically unit-testable: the filesystem is injected (use a
 * [okio.fakefilesystem.FakeFileSystem] in tests) and the `getenforce` runner is
 * a plain suspend lambda (pass a fake in tests; the production caller wires
 * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter.executeShell]).
 */
@Singleton
class SelinuxProbe @Inject constructor(
    private val fileSystem: FileSystem,
) {

    /**
     * Resolve SELinux enforcement mode.
     *
     * @param pserverLive whether PServer is transactable. When true we trust the
     *   `getenforce` root read over the world-readable file (it is authoritative
     *   even on firmwares that block the file read for our UID).
     * @param runGetenforce a suspend runner that executes a shell command via
     *   PServer's root shell and returns `(status, stdout)` or null on failure.
     *   Only invoked when [pserverLive] is true. In production this is
     *   `pServerWriter::executeShell`.
     *
     * @return `true` = Enforcing, `false` = Permissive, `null` = could not
     *   determine (both reads failed / produced garbage).
     */
    suspend fun probe(
        pserverLive: Boolean,
        runGetenforce: suspend (String) -> Pair<Int, String>?,
    ): Boolean? {
        // 1. Authoritative root read first WHEN PServer is live — it is the truth
        //    even on firmwares that block the world-readable file for our UID.
        if (pserverLive) {
            val viaRoot = runCatching { runGetenforce("getenforce") }.getOrNull()
            val parsed = viaRoot?.let { (status, out) ->
                if (status == 0) parseGetenforce(out) else null
            }
            if (parsed != null) return parsed
            // fall through to the file read if getenforce gave nothing usable
        }

        // 2. World-readable file: 0 = permissive, 1 = enforcing.
        return readEnforceFile()
    }

    /** Parse a single byte of `/sys/fs/selinux/enforce`. */
    private fun readEnforceFile(): Boolean? {
        val text = runCatching {
            fileSystem.read(ENFORCE_PATH.toPath()) { readUtf8() }
        }.getOrNull()?.trim() ?: return null
        return when (text.firstOrNull()) {
            '1' -> true   // enforcing
            '0' -> false  // permissive
            else -> null  // unexpected content — never guess
        }
    }

    /** Parse `getenforce` stdout: "Enforcing" / "Permissive" (case-insensitive). */
    private fun parseGetenforce(out: String): Boolean? = when {
        out.trim().equals("Enforcing", ignoreCase = true) -> true
        out.trim().equals("Permissive", ignoreCase = true) -> false
        else -> null
    }

    private companion object {
        /** World-readable on most devices; `Permission denied` on some locked firmwares. */
        const val ENFORCE_PATH = "/sys/fs/selinux/enforce"
    }
}
