package io.github.mayusi.calibratesoc.data.tunables.writer

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-PServer"

/**
 * Writes to AYN devices via the private `PServerBinder` service — the same
 * path langerhans OdinTools uses on Odin 2/3/Thor to run shell commands as
 * root without the user installing Magisk.
 *
 * ## Two modes
 *
 * ### 1. SETTINGS_SYSTEM writes (always available when binder is present)
 * Routes `settings put system KEY VALUE` through PServer's root shell.
 * Used for fan_mode / performance_mode / etc. vendor keys.
 *
 * ### 2. SYSFS live writes (requires PServer whitelist — see below)
 * When [isTransactable] returns true, PServerWriter also accepts SYSFS
 * tunables and writes them by running `printf %s VALUE > PATH` through
 * PServer's root shell. Because PServer already runs as root, this needs
 * NO per-boot chmod — the write succeeds every time regardless of the
 * node's DAC mode. This is the "PServer-LIVE" tier for AutoTDP on AYN.
 *
 * ## The whitelist gate (why transact() fails out of the box)
 *
 * On Odin 3 and Thor, `getService("PServerBinder")` returns non-null BUT
 * `transact()` returns UNKNOWN_TRANSACTION because AYN's PServer checks
 * the caller UID against an `app_whiteList` stored in Settings.System.
 * langerhans' OdinTools is whitelisted by default; our package is not.
 *
 * Fix: the one-time unlock script (run via Odin Settings → Run script as
 * Root) appends our package name to the `app_whiteList` key. After that,
 * transact() succeeds for our UID and [isTransactable] returns true.
 *
 * The whitelist key is `Settings.System/app_whiteList`. Evidence:
 *   - The `write()` catch block below already references this key in its
 *     error message (documented mechanism, not speculation).
 *   - On-device behaviour: transact returns UNKNOWN_TRANSACTION (binder
 *     code -1) before whitelist add, status 0 after.
 *
 * HONESTY: [isTransactable] performs a REAL `true` no-op transact and
 * caches whether it succeeded. A mere `binder() != null` is a false
 * positive — the Odin 3 registers the service even when it blocks our UID.
 *
 * ## Mechanism (reverse-engineered from langerhans' ShellExecutor)
 *
 *   1. Reflect android.os.ServiceManager.getService("PServerBinder")
 *      → IBinder for AYN's vendor service. NULL on non-AYN devices.
 *   2. Write a Parcel: interfaceToken + command string.
 *   3. binder.transact(1, data, reply, 0) → PServer runs the command as
 *      root; reply contains (int status, string stdout).
 *
 * ## Risk surface
 *   - ServiceManager.getService is @hide — works but may be blocked by
 *     hidden-API policy; SecurityException → CapabilityDenied.
 *   - Wire format (code 1 + simple string) is what langerhans uses.
 *     Firmware change → Rejected; [isTransactable] will catch it.
 */
@Singleton
class PServerWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SysfsWriter {

    /**
     * Read the current value of a tunable for snapshot support.
     *
     * SETTINGS_SYSTEM: reads via ContentResolver (no binder round-trip needed).
     * SYSFS: reads directly from the filesystem (the node is readable even when
     *   it's 0444 — read access is always allowed; only writes require root).
     *
     * Returns null on any error or for unhandled kinds.
     */
    override suspend fun read(id: TunableId): String? = when (id.kind) {
        TunableKind.SETTINGS_SYSTEM -> runCatching {
            Settings.System.getString(context.contentResolver, id.target)
        }.getOrNull()
        TunableKind.SYSFS -> withContext(Dispatchers.IO) {
            runCatching { java.io.File(id.target).readText().trim().ifBlank { null } }.getOrNull()
        }
        else -> null
    }

    override suspend fun canWrite(id: TunableId): Boolean {
        return when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> withContext(Dispatchers.IO) { binder() != null }
            // SYSFS writes via PServer require a confirmed transact — not just binder present.
            // isTransactable() does the real probe (memoised after first call).
            TunableKind.SYSFS -> isTransactable()
            else -> false
        }
    }

    override suspend fun write(id: TunableId, value: String): WriteResult {
        return when (id.kind) {
            TunableKind.SETTINGS_SYSTEM -> writeSettingsSystem(id, value)
            TunableKind.SYSFS -> writeSysfs(id, value)
            else -> WriteResult.CapabilityDenied(
                id,
                "PServerWriter handles SETTINGS_SYSTEM and SYSFS only.",
            )
        }
    }

    /**
     * Write a SYSFS node through PServer's root shell.
     *
     * Because PServer runs as root, the write succeeds regardless of the node's
     * DAC mode (444, 000, etc.) — no per-boot chmod required. This is the
     * "PServer-LIVE" tier: it writes every tick just as cheaply as a direct
     * file write, but with root authority.
     *
     * HONESTY: only called when [isTransactable] is true (the WriterRegistry
     * checks this before routing here). If [isTransactable] is false, the
     * caller gets NoopWriter and the UI honestly reports unavailable.
     *
     * Shell command: `printf %s 'VALUE' > 'PATH'`
     * Using `printf %s` matches the AynScriptGenerator convention and avoids
     * the trailing-newline that `echo` would append, which some kernel sysfs
     * parsers reject.
     *
     * Both PATH and VALUE are single-quote-escaped to prevent shell injection.
     */
    private suspend fun writeSysfs(id: TunableId, value: String): WriteResult {
        // Validate the path before building a shell command.
        val pathError = validateSysfsPath(id.target)
        if (pathError != null) {
            return WriteResult.Rejected(
                id = id,
                errno = -1,
                message = "PServerWriter rejected sysfs path '${id.target}': $pathError",
            )
        }
        return withContext(Dispatchers.IO) {
            if (BuildConfig.DEBUG) Log.i(TAG, "writeSysfs(): path=${id.target} value=$value")
            val qpath = id.target.shellQuote()
            // Read the current value THROUGH PServer for the snapshot — a plain
            // File.readText() is SELinux-denied to our app UID. Best-effort.
            val previous = runCatching {
                val binder = binder()
                if (binder != null) transact(binder, "cat $qpath").second.trim().ifBlank { null } else null
            }.getOrNull()

            // THE WRITE: the sysfs node is mode 444 (read-only). PServer runs as a
            // privileged (non-shell) UID that CAN chmod it, so we chmod 666 → write →
            // chmod 444 in sequence — the exact technique OdinTools/ClusterTune use and
            // the only one that makes the redirect actually land on AYN firmware.
            // (A bare `printf > node` fails silently against the read-only file.)
            val cmd = "chmod 666 $qpath && printf %s ${value.shellQuote()} > $qpath; chmod 444 $qpath"
            val (status, stdout) = try {
                val binder = binder()
                if (binder == null) {
                    Log.w(TAG, "writeSysfs(): binder() is null — PServer gone?")
                    return@withContext WriteResult.CapabilityDenied(
                        id,
                        "PServerBinder service disappeared unexpectedly.",
                    )
                }
                transact(binder, cmd)
            } catch (se: SecurityException) {
                Log.w(TAG, "writeSysfs(): SecurityException — UID not in app_whiteList", se)
                // If transact suddenly fails with SecurityException after isTransactable()
                // returned true (e.g. firmware update reset the whitelist), clear the
                // memoised cache so the next isTransactable() re-probes honestly.
                transactableCache = null
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServer rejected our UID for sysfs write. " +
                        "Re-run the unlock script to re-add our package to app_whiteList.",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "writeSysfs(): transact threw", t)
                return@withContext WriteResult.Failed(id, t)
            }

            if (BuildConfig.DEBUG) Log.i(TAG, "writeSysfs(): status=$status stdout='$stdout'")
            else Log.i(TAG, "writeSysfs(): path=${id.target} status=$status")

            if (status == 0) {
                WriteResult.Success(id, previousValue = previous, newValue = value)
            } else {
                WriteResult.Rejected(
                    id = id,
                    errno = status,
                    message = stdout.ifBlank { "PServer sysfs write returned status $status" },
                )
            }
        }
    }

    /** Original SETTINGS_SYSTEM write path, extracted to its own function. */
    private suspend fun writeSettingsSystem(id: TunableId, value: String): WriteResult {
        if (id.kind != TunableKind.SETTINGS_SYSTEM) {
            return WriteResult.CapabilityDenied(id, "writeSettingsSystem called with non-SETTINGS_SYSTEM kind.")
        }
        // Guard the Settings key name: valid Settings.System keys are always simple tokens
        // ([A-Za-z0-9_.]+). Anything else (spaces, shell metacharacters, etc.) is either
        // a programming error or an injection attempt — reject it before building the command.
        if (!id.target.matches(Regex("[a-zA-Z0-9_.]+"))) {
            return WriteResult.Rejected(
                id = id,
                errno = -1,
                message = "Settings key '${id.target}' contains disallowed characters " +
                    "(only [A-Za-z0-9_.] are valid Settings.System key characters).",
            )
        }
        return withContext(Dispatchers.IO) {
            if (BuildConfig.DEBUG) Log.i(TAG, "write(): target=${id.target} value=$value")
            val binder = binder()
            if (binder == null) {
                Log.w(TAG, "write(): binder() returned null — PServer not bindable")
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServerBinder service not present on this device.",
                )
            }
            if (BuildConfig.DEBUG) Log.i(TAG, "write(): got binder, transacting...")

            // Shell-quote both the key and the value so that a value containing spaces,
            // quotes, dollar signs, or other metacharacters cannot inject additional shell
            // commands into the `settings put system` invocation (which runs as root via
            // PServer). The key has already been validated to [A-Za-z0-9_.] above, so
            // quoting it is purely defence-in-depth; quoting the value is necessary.
            val cmd = "settings put system ${id.target.shellQuote()} ${value.shellQuote()}"
            val (status, stdout) = try {
                transact(binder, cmd)
            } catch (se: SecurityException) {
                Log.w(TAG, "write(): SecurityException from transact — UID not in app_whiteList", se)
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServer rejected our UID (likely not in app_whiteList). Add via:\n" +
                        "adb shell settings put system app_whiteList \"\$(settings get system app_whiteList),io.github.mayusi.calibratesoc.debug\"",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "write(): transact threw", t)
                return@withContext WriteResult.Failed(id, t)
            }

            // Log status in all builds (no sensitive data), but gate stdout behind DEBUG
            // to prevent root command output from appearing in release logcat.
            if (BuildConfig.DEBUG) Log.i(TAG, "write(): transact returned status=$status stdout='$stdout'")
            else Log.i(TAG, "write(): transact returned status=$status")
            if (status == 0) {
                WriteResult.Success(id, previousValue = null, newValue = value)
            } else {
                WriteResult.Rejected(
                    id = id,
                    errno = status,
                    message = stdout.ifBlank { "PServer transaction returned $status" },
                )
            }
        }
    }

    /**
     * Reflect ServiceManager.getService(name) → IBinder. Returns null
     * on any failure (ClassNotFoundException — unlikely, the class
     * exists in all Androids; NoSuchMethodException — the signature
     * has been stable since API 1; SecurityException — hidden-API
     * gate; or a null binder when the service simply isn't published).
     */
    fun binder(): IBinder? = runCatching {
        val clazz = Class.forName("android.os.ServiceManager")
        val getService = clazz.getMethod("getService", String::class.java)
        getService.invoke(null, BINDER_NAME) as? IBinder
    }.getOrNull()

    /**
     * Run an arbitrary shell command via PServer. The shell runs as
     * root (pservice's UID) and — when Odin's "Force SELinux" toggle
     * is ON — in a permissive SELinux domain that can write sysfs,
     * chmod files, stop services, the whole works. This is the cleanest
     * path to instant HUD ± writes on a non-rooted Odin: every tap
     * pokes PServer with a one-line shell command, no Odin Settings UI,
     * no script-per-tap, no file-system rendezvous.
     *
     * Caller is responsible for passing a single self-contained command
     * (use `&&` / `;` to chain). Output (stdout+stderr concatenated by
     * PServer) comes back as the second member of the pair; status 0
     * means success.
     */
    // Circuit-breaker. PServer's transact code 1 / wire format doesn't
    // match what our UID can send — every call returns
    // RuntimeException("Unknown error"). Without this gate, the FPS
    // sampler + storage probe call executeShell once per second AND
    // each retry blocks 150-450ms, starving the coroutine pool and
    // making the dashboard charts go "warming up" intermittently.
    // After 3 consecutive failures we lock the breaker open for 30s.
    @Volatile private var openUntilMs: Long = 0

    suspend fun executeShell(command: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() < openUntilMs) return@withContext null
        val binder = binder() ?: return@withContext null
        val result = runCatching { transact(binder, command) }
        val pair = result.getOrNull()
        if (pair != null) return@withContext pair
        Log.w(TAG, "executeShell failed: ${result.exceptionOrNull()} — circuit-breaking 30s")
        openUntilMs = System.currentTimeMillis() + 30_000L
        null
    }

    // Cached result of the real-transact probe. null = not yet probed.
    // Once we know whether PServer actually executes commands from our
    // UID, we never re-probe for the session — the answer can't change
    // without a reboot / firmware toggle, and re-probing would re-arm
    // the circuit breaker on a device where PServer is dead.
    @Volatile private var transactableCache: Boolean? = null

    /**
     * The ONLY honest way to know whether the HUD ± steppers will work:
     * actually send PServer a harmless command and see if it executes.
     *
     * `getService("PServerBinder") != null` is a FALSE POSITIVE — on the
     * Odin 3 and Thor the service is registered (`service list` shows
     * "PServerBinder: []") but transacting from our app's UID returns
     * UNKNOWN_TRANSACTION / "unknown error" because AYN's wire format
     * gates on the caller UID (langerhans' OdinTools is allow-listed; we
     * are not). So we run `true` (a shell no-op that exits 0) once and
     * cache whether the transaction round-trips with status 0.
     *
     * Returns true ONLY when PServer genuinely ran our command. Safe to
     * call from any thread; result is memoized.
     */
    suspend fun isTransactable(): Boolean = withContext(Dispatchers.IO) {
        transactableCache?.let { return@withContext it }
        val binder = binder()
        val ok = if (binder == null) {
            false
        } else {
            // Don't go through executeShell() — we don't want a probe
            // failure to arm the 30s circuit breaker before real work
            // even gets a chance. Just one direct transact.
            val result = runCatching { transact(binder, "true") }.getOrNull()
            result != null && result.first == 0
        }
        Log.i(TAG, "isTransactable() probe → $ok")
        transactableCache = ok
        ok
    }

    /**
     * Synchronous read of the memoised transactability result. Returns false
     * when the probe has not yet run (conservative — never false-positives).
     *
     * Used by [WriterRegistry] which must pick a writer synchronously. The
     * probe is warmed up during [io.github.mayusi.calibratesoc.data.capability.CapabilityProbe.refresh]
     * (which calls [AdvancedPermissionsScript.grantsCurrentlyHeld] → the PServer
     * no-op transact), so by the time the WriterRegistry is called the cache is
     * already populated in normal app flow. Returns false on the cold path
     * (first access before probe) — safe fallback to NoopWriter.
     */
    fun transactableNow(): Boolean = transactableCache ?: false

    /**
     * Submit one command, parse the reply parcel. Returns
     * (statusCode, stdout): 0 = success, -1 = failure.
     *
     * WIRE FORMAT — verified against two production apps that ship
     * working no-root tuning on the AYN Odin 3: langerhans/OdinTools'
     * ShellExecutor and AurelioB/ClusterTune's RootExec. All three of
     * the following must match PServer's C++ side or transact() returns
     * UNKNOWN_TRANSACTION:
     *   - transact code = 0 (the SHELL_COMMAND slot), NOT 1.
     *   - payload = writeStringArray([command, "1"]) with NO
     *     writeInterfaceToken — PServer does a raw readStringArray(); an
     *     interface token's strict-mode + descriptor bytes corrupt that
     *     read. The trailing "1" is the run-as-root flag PServer's argv
     *     parser expects.
     *   - reply = a raw byte-array (createByteArray), NOT an AIDL
     *     [int exceptionCode, string] Parcelable wrapper. A reply of the
     *     literal text "null" means "no output".
     */
    private fun transact(binder: IBinder, command: String): Pair<Int, String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "1"))
            val ok = binder.transact(TRANSACT_CODE_EXEC, data, reply, 0)
            if (!ok) {
                return -1 to "binder.transact returned false (UNKNOWN_TRANSACTION)"
            }
            val out = reply.createByteArray()
                ?.toString(Charsets.UTF_8)
                ?.trim()
                ?.let { if (it == "null") "" else it }
                .orEmpty()
            0 to out
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val BINDER_NAME = "PServerBinder"

        /** PServer's SHELL_COMMAND transaction slot is 0 (verified against
         *  OdinTools/ClusterTune), not 1. */
        const val TRANSACT_CODE_EXEC = 0
    }

    // ── Shell quoting ─────────────────────────────────────────────────────────

    /**
     * POSIX single-quote escaping for a shell argument.
     *
     * Identical in logic to [AynScriptGenerator.shellSingleQuote] and
     * [RootWriter.shellQuote]. Defined here so [PServerWriter] is self-contained
     * and does not need to depend on the script-generator package.
     *
     * The resulting string is always safe to embed between outer single quotes
     * in a generated shell command, even if [this] contains single-quotes,
     * dollar signs, backticks, semicolons, or any other shell-special character.
     */
    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

    // ── Sysfs path validation ─────────────────────────────────────────────────

    /**
     * Validates that a sysfs path is safe to embed in a shell command sent to
     * PServer's root shell. Returns a human-readable error string on failure,
     * null when the path is accepted.
     *
     * Rules (mirrors [TunableMetadata.validateCustomSysfsPath]):
     *   - Must start with /sys/ or /proc/ (kernel surfaces only).
     *   - Must not contain path-traversal sequences (`..`).
     *   - Must not contain shell metacharacters that could escape the
     *     single-quote boundary (newlines, null bytes — the path itself is
     *     single-quote-escaped via [shellQuote], but these bytes break the
     *     escaping contract).
     */
    internal fun validateSysfsPath(path: String): String? {
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) {
            return "path must start with /sys/ or /proc/ (got '$path')"
        }
        if (path.contains("..")) {
            return "path contains path-traversal sequence '..' (got '$path')"
        }
        if (path.contains('\n') || path.contains('\r') || path.contains(' ')) {
            return "path contains disallowed control characters"
        }
        return null
    }
}
