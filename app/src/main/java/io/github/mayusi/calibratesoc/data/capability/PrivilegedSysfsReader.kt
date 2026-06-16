package io.github.mayusi.calibratesoc.data.capability

import android.util.Log
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a single kernel node through the PServer root shell (`cat <path>`) for the
 * narrow set of nodes that the app UID cannot read directly via Okio/`FileInputStream`.
 *
 * ## Why this exists (the cross-device bug)
 *
 * On the AYN Odin 3, two node families are **SELinux-denied to the app UID** even
 * though they are readable from a more-privileged context:
 *   - the cgroup uclamp slice `/dev/cpuctl/top-app/cpu.uclamp.min`
 *   - the Adreno devfreq bounds `/sys/class/kgsl/kgsl-3d0/devfreq/{min,max}_freq`
 *
 * `adb shell cat …` returns a value — but `adb shell` runs in the permissive `shell`
 * domain, NOT the app's `untrusted_app` domain. The app's own `open()` (which is what
 * Okio's `FileSystem.SYSTEM.read()` does — it goes straight to `FileInputStream`, no
 * stat first) gets `EACCES`, so the probe's direct read returns null and the
 * CapabilityReport honestly recorded `uclampAvailable=false` / `gpuDevfreq*=null`.
 *
 * PServer runs as root, so `cat <path>` through it succeeds exactly as `adb shell cat`
 * does. This is the SAME root cause and the SAME remedy that
 * [io.github.mayusi.calibratesoc.data.monitor.CpuLoadSourceSelector] already uses for
 * `/proc/stat` (Android 12+ hidepid restriction): when the app UID is blocked, read via
 * the privileged PServer shell.
 *
 * ## Honesty contract
 *
 * This is a **fallback**, never the primary path. Callers read directly first and only
 * consult [catOrNull] when the direct read returned null. When PServer is absent or not
 * whitelisted ([PServerWriter.transactableNow] == false) this returns null with zero
 * IPC — so on a device where a node is genuinely unreadable by BOTH the app AND PServer
 * (e.g. the RP6, whose GPU devfreq is SELinux-denied to every non-root context the app
 * can reach), the probe still records the honest null. We never fabricate a value.
 */
@Singleton
open class PrivilegedSysfsReader @Inject constructor(
    /**
     * The PServer root channel. Hilt always injects the real singleton. It is nullable
     * only so a unit-test subclass that fully overrides [catOrNull] can construct without
     * an Android [android.content.Context] — production code never passes null.
     */
    private val pServerWriter: PServerWriter?,
) {

    /**
     * `cat <path>` through the PServer root shell. Returns the trimmed stdout, or null
     * when PServer is unavailable / the command failed / stdout was blank.
     *
     * Non-suspend so the synchronous [SysfsProber] probe methods can call it. The PServer
     * round-trip is a one-shot per node during a capability refresh (not a hot path), and
     * the bounded [runBlocking] on [Dispatchers.IO] mirrors the established pattern in
     * [ShizukuProbe.probeSysfsWriteAllowed] and [io.github.mayusi.calibratesoc.data.prefs.UserPrefs.rootModeEnabledBlocking].
     *
     * Warms PServer transactability first (one harmless `true` transact, memoised) so the
     * read works even when this is the first PServer touch of the refresh — the capability
     * probe runs the GPU/uclamp probes BEFORE it warms the cache elsewhere.
     */
    open fun catOrNull(path: String): String? {
        val pserver = pServerWriter ?: return null
        // Fast path: never pay IPC when PServer can't help. transactableNow() reads the
        // memoised probe result; isTransactable() (below) does the real one-shot warm.
        return try {
            runBlocking(Dispatchers.IO) {
                // Warm + confirm transactability. Cheap when already cached true; one
                // no-op transact otherwise. Returns false fast on non-AYN (binder null).
                if (!pserver.isTransactable()) return@runBlocking null
                val cmd = "cat ${path.shellQuote()}"
                val result = pserver.executeShell(cmd) ?: return@runBlocking null
                val (status, stdout) = result
                if (status != 0) return@runBlocking null
                stdout.trim().ifBlank { null }
            }
        } catch (t: Throwable) {
            // A privileged-read failure must never crash a capability refresh — degrade to
            // null and let the caller keep the honest "unreadable" result.
            Log.w(TAG, "catOrNull('$path') failed: ${t.message}")
            null
        }
    }

    /**
     * POSIX single-quote escaping for a shell argument — identical contract to
     * [PServerWriter]'s private `shellQuote`. Defined here so the reader is self-contained.
     * Safe to embed between outer single quotes even when [this] contains quotes, `$`,
     * backticks, `;`, etc.
     */
    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "CalibrateSoC-PrivRead"
    }
}
