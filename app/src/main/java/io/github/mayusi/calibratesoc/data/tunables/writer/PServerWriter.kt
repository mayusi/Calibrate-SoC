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
 * Writes via the `PServerBinder` root runner — a CROSS-VENDOR firmware signature
 * (the same `/system/bin/pservice` root shell langerhans OdinTools uses), NOT
 * AYN-only. Confirmed LIVE on BOTH the AYN Odin 3 AND the Retroid Pocket 6: any
 * device where `getService("PServerBinder")` + a transact probe succeeds runs
 * shell commands as root without the user installing Magisk.
 *
 * ## Two modes
 *
 * ### 1. SETTINGS_SYSTEM writes (always available when binder is present)
 * Routes `settings put system KEY VALUE` through PServer's root shell.
 * Used for fan_mode / performance_mode / etc. vendor keys.
 *
 * ### 2. SYSFS live writes (requires PServer transactable — see below)
 * When [isTransactable] returns true, PServerWriter also accepts SYSFS
 * tunables and writes them by running `printf %s VALUE > PATH` through
 * PServer's root shell. Because PServer already runs as root, this needs
 * NO per-boot chmod — the write succeeds every time regardless of the
 * node's DAC mode. This is the "PServer-LIVE" tier for AutoTDP on ANY
 * device that has the binder.
 *
 * ## The real gate is SELinux mode — NOT `app_whiteList`
 *
 * `getService("PServerBinder")` can return non-null while `transact()` still
 * fails. The gate is the device's SELINUX MODE, not an `app_whiteList`:
 *   - PERMISSIVE / transactable firmware → transact succeeds, zero-setup.
 *   - ENFORCING and our UID blocked → transact fails; there is no app-only
 *     fix. The only lever is the vendor "Force SELinux" toggle (a LAST RESORT
 *     — it breaks many emulators, so most users should never touch it).
 *
 * The old `app_whiteList` narrative is FALSE and has been removed: `pservice`
 * never checks that key (proven on-device — the app ran root with our package
 * REMOVED from the whitelist). Adding our package to `app_whiteList` is a no-op
 * for PServer, so the unlock script no longer does it.
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
     *
     * After the write, we READ BACK the node via PServer (cat) and compare to
     * the intended value. We accept exact matches AND kernel-snapped neighbors
     * (e.g. OPP tables may round freq to the nearest available step). If the
     * readback does not match either, we return [WriteResult.Rejected] with the
     * actual value — we NEVER claim success without confirming the write landed.
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

            val binder = binder()
            if (binder == null) {
                Log.w(TAG, "writeSysfs(): binder() is null — PServer gone?")
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServerBinder service disappeared unexpectedly.",
                )
            }

            // Read the current value THROUGH PServer for the snapshot — a plain
            // File.readText() is SELinux-denied to our app UID. Best-effort.
            val previous = runCatching {
                transact(binder, "cat $qpath").second.trim().ifBlank { null }
            }.getOrNull()

            // THE WRITE: the sysfs node is mode 444 (read-only). PServer runs as a
            // privileged (non-shell) UID that CAN chmod it, so we chmod 666 → write →
            // chmod 444 in sequence — the exact technique OdinTools/ClusterTune use and
            // the only one that makes the redirect actually land on AYN firmware.
            // (A bare `printf > node` fails silently against the read-only file.)
            val cmd = "chmod 666 $qpath && printf %s ${value.shellQuote()} > $qpath; chmod 444 $qpath"
            val (status, stdout) = try {
                transact(binder, cmd)
            } catch (se: SecurityException) {
                Log.w(TAG, "writeSysfs(): SecurityException — PServer rejected our UID (likely Enforcing SELinux)", se)
                // If transact suddenly fails with SecurityException after isTransactable()
                // returned true (e.g. an OTA flipped SELinux back to Enforcing), clear the
                // memoised cache so the next isTransactable() re-probes honestly.
                transactableCache = null
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServer rejected our UID for this sysfs write — likely Enforcing SELinux " +
                        "blocks app-UID transacts. Live sysfs tuning needs Force-SELinux " +
                        "(vendor toggle) as a last resort; it can break emulators, so most " +
                        "users should leave it off.",
                )
            } catch (t: Throwable) {
                Log.e(TAG, "writeSysfs(): transact threw", t)
                return@withContext WriteResult.Failed(id, t)
            }

            if (BuildConfig.DEBUG) Log.i(TAG, "writeSysfs(): status=$status stdout='$stdout'")
            else Log.i(TAG, "writeSysfs(): path=${id.target} status=$status")

            // READBACK VERIFICATION — the shell exit code alone is unreliable:
            // the chmod+write+chmod sandwich always returns 0 even when the kernel
            // silently ignores the write (e.g. an OPP/range clamp, a DAC issue after
            // reboot, etc.). We cat the node back through PServer and compare.
            //
            // OPP-snap tolerance: cpufreq and GPU OPP tables quantise incoming values
            // to the nearest available operating point. We accept the intended value,
            // an exact match, OR any snapped neighbor within OPP_SNAP_TOLERANCE_HZ of
            // the intended value when both are numeric (freq nodes). Non-numeric values
            // (governors, strings) must match exactly (trimmed).
            val readback = runCatching {
                transact(binder, "cat $qpath").second.trim().ifBlank { null }
            }.getOrNull()

            classifyReadback(
                id = id,
                intended = value.trim(),
                readback = readback,
                previous = previous,
                status = status,
            )
        }
    }

    /**
     * Pure classification of a sysfs write's readback into a [WriteResult]. Shared by the
     * live [writeSysfs] path and unit tests so the BUG 10 honesty invariant is verifiable
     * without a real binder.
     *
     * - [readback] == null  → could NOT confirm. Returns Success(verified=FALSE): we never
     *   claim the node moved, and the unverified flag keeps the boot-revert backstop armed
     *   for critical nodes (see [TunableWriter.revertAll]). This is the core BUG 10 fix —
     *   the old path returned the default verified=true here, journaling an unconfirmed
     *   write as confirmed (the 384 MHz-collapse class).
     * - readback accepted (exact or OPP-snapped) → Success(verified=true), newValue=readback.
     * - readback mismatch → Rejected with the actual value; we NEVER claim success.
     */
    internal fun classifyReadback(
        id: TunableId,
        intended: String,
        readback: String?,
        previous: String?,
        status: Int,
    ): WriteResult {
        if (readback == null) {
            Log.w(TAG, "writeSysfs(): readback failed for ${id.target} — cannot confirm write landed (verified=false)")
            return WriteResult.Success(
                id = id,
                previousValue = previous,
                newValue = intended,
                verified = false,
            )
        }
        return if (readbackAccepted(intended, readback)) {
            if (BuildConfig.DEBUG) Log.i(TAG, "writeSysfs(): readback confirmed: path=${id.target} readback='$readback'")
            WriteResult.Success(id, previousValue = previous, newValue = readback)
        } else {
            Log.w(TAG, "writeSysfs(): readback MISMATCH: path=${id.target} intended='$intended' actual='$readback'")
            WriteResult.Rejected(
                id = id,
                errno = status,
                message = "Write appeared to succeed (status $status) but readback returned " +
                    "'$readback' instead of '$intended'. Kernel may have rejected the value.",
            )
        }
    }

    /**
     * Returns true when [readback] is an acceptable result for a write of [intended].
     *
     * Exact match always passes. For numeric values (freq nodes reported in kHz) we
     * also accept a snapped neighbor: the kernel's OPP table may round the intended
     * frequency to the nearest available operating point, so we allow any numeric
     * readback within [OPP_SNAP_TOLERANCE_KHZ] of the intended value.
     *
     * Non-numeric values (governor names, "Y"/"N" toggles, etc.) must match exactly.
     */
    internal fun readbackAccepted(intended: String, readback: String): Boolean {
        if (intended == readback) return true
        val intendedLong = intended.toLongOrNull() ?: return false
        val readbackLong = readback.toLongOrNull() ?: return false
        // OPP-snap tolerance applies ONLY to large frequency values (kHz: cpufreq /
        // GPU clocks, which the kernel quantises to the nearest OPP step). Small
        // control knobs — GPU power levels (0-7), online flags (0/1), small indices —
        // must match EXACTLY: a 100 MHz tolerance on a 0-7 pwrlevel would accept ANY
        // value as success, re-introducing the false-success bug we are killing.
        if (intendedLong < FREQ_TOLERANCE_FLOOR_KHZ || readbackLong < FREQ_TOLERANCE_FLOOR_KHZ) {
            return false // small control knob — exact match was required and already failed
        }
        return kotlin.math.abs(intendedLong - readbackLong) <= OPP_SNAP_TOLERANCE_KHZ
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
                Log.w(TAG, "write(): SecurityException from transact — PServer rejected our UID (likely Enforcing SELinux)", se)
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServer rejected our UID — likely Enforcing SELinux blocks app-UID " +
                        "transacts. This is gated by SELinux mode, not app_whiteList " +
                        "(adding our package to app_whiteList does nothing for PServer). " +
                        "Live tuning here needs the vendor Force-SELinux toggle as a last " +
                        "resort; it can break emulators, so most users should leave it off.",
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
    // Can be reset via [invalidateTransactableCache] so re-entry to the app
    // after a SELinux-mode change (e.g. the user flipped Force-SELinux) picks up
    // the new transactability state.
    @Volatile internal var transactableCache: Boolean? = null

    /**
     * Invalidates the memoised transactability result so the next call to
     * [isTransactable] re-probes PServer honestly.
     *
     * Call sites:
     *   - [io.github.mayusi.calibratesoc.ui.tune.AdvancedUnlockViewModel.refresh]
     *     so the Tune-screen Refresh button re-evaluates PServer after the
     *     user runs the unlock script.
     *   - Any onResume / overlay-start re-probe trigger so returning to the
     *     app after running the script lights up PServer-LIVE automatically.
     */
    fun invalidateTransactableCache() {
        transactableCache = null
        Log.i(TAG, "invalidateTransactableCache(): cache cleared — next isTransactable() will re-probe")
    }

    /**
     * The ONLY honest way to know whether the HUD ± steppers will work:
     * actually send PServer a harmless command and see if it executes.
     *
     * `getService("PServerBinder") != null` is a FALSE POSITIVE — on the
     * Odin 3 and Thor the service is registered (`service list` shows
     * "PServerBinder: []") but transacting from our app's UID can still be
     * blocked by the firmware's SELinux mode (Enforcing → transact fails;
     * Permissive / transactable firmware → succeeds). So we run `true` (a shell
     * no-op that exits 0) once and cache whether the round-trip returns status 0.
     *
     * Returns true ONLY when PServer genuinely ran our command. Safe to
     * call from any thread; result is memoized until [invalidateTransactableCache].
     *
     * Cheap guard: if the cache is false but [binder] is now non-null we
     * allow a re-probe — binder presence after a cached-false means either the
     * SELinux mode just changed (binder existed but blocked us before) or the
     * service came up after an earlier null. Either way, one honest transact is
     * cheaper than a stale false.
     */
    suspend fun isTransactable(): Boolean = withContext(Dispatchers.IO) {
        val cached = transactableCache
        // If we have a cached true, keep it.
        if (cached == true) return@withContext true
        // If we have a cached false, still allow a re-probe when binder is live —
        // this catches the case where the app warmed before a SELinux-mode change.
        if (cached == false && binder() == null) return@withContext false

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
        // ── PROVABLY-SAFE COMMAND GUARD ───────────────────────────────────────
        // This is the ONE binder.transact() in the entire app: every root command
        // (writeSysfs chmod-sandwich, writeSettingsSystem, and every executeShell
        // caller — AppReaper, FanCurveController, HardwareScanner, GameFpsSampler,
        // StorageSpeedTester, CpuLoadSource, PrivilegedSysfsReader, the daemon
        // stop/start hooks) funnels through here. Inspecting the command FIRST,
        // before the parcel is built, makes it structurally impossible for any
        // command to reach PServer's root shell without passing the default-deny
        // allow-list. A destructive command (rm/dd/mkfs/reboot/setenforce/…) is
        // blocked here and never crosses the binder.
        when (val verdict = PServerCommandGuard.inspect(command)) {
            is PServerCommandGuard.Verdict.Deny -> {
                Log.e(TAG, "PServerCommandGuard BLOCKED: ${verdict.reason} | cmd=$command")
                return -1 to "BLOCKED: ${verdict.reason}"
            }
            PServerCommandGuard.Verdict.Allow -> { /* fall through to transact */ }
        }
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

        /**
         * OPP-snap tolerance for numeric sysfs readback verification.
         *
         * Linux cpufreq sysfs nodes (scaling_max_freq, scaling_min_freq, etc.) report
         * values in **kHz** (e.g. 3187200 = 3187.2 MHz). GPU kgsl nodes also use kHz.
         * OPP tables quantise incoming values to the nearest available operating point.
         *
         * On the Snapdragon 8 Elite (Odin 3), adjacent OPP steps are ~38400 kHz
         * (38.4 MHz) apart (e.g. 3187200 -> 3148800 kHz). We accept any numeric
         * readback within 100000 kHz (100 MHz) of the intended value, which covers
         * all known Snapdragon cpufreq OPP grids without being so wide that it masks
         * genuine rejections (a kernel that rejects a value typically reads back the
         * previous value, 0, or the hard-capped min/max -- far outside 100 MHz).
         *
         * Non-numeric values (governor names, "Y"/"N" toggles, etc.) must match
         * exactly -- this tolerance only applies when both intended and readback
         * parse as Long.
         */
        const val OPP_SNAP_TOLERANCE_KHZ = 100_000L   // 100 MHz in kHz units

        /**
         * Below this, a numeric value is a control knob (GPU pwrlevel 0-7, online
         * 0/1, small index), NOT a frequency — the OPP-snap tolerance must NOT apply,
         * so these require an exact readback match. Real cpufreq/GPU clock values are
         * always >= ~300 MHz, so 200 MHz is a safe floor below any real frequency and
         * above any control-knob index.
         */
        const val FREQ_TOLERANCE_FLOOR_KHZ = 200_000L  // 200 MHz in kHz units
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
     * Rules (mirrors [io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.validateCustomSysfsPath]):
     *   - Must start with /sys/ or /proc/ (kernel surfaces only).
     *   - Must not contain path-traversal sequences (`..`).
     *   - Must not contain a real NUL byte. A NUL truncates the C string the
     *     kernel/shell ultimately sees, so `'/sys/safe; rm -rf /'` could be
     *     read as `/sys/safe` by the path check yet do something else downstream.
     *     The doc previously claimed this check existed; SEC-2 makes the code match.
     *   - Must not contain newlines/spaces that would break the single-quote
     *     escaping contract.
     *   - Must not be on the dangerous-node block list. The door validator
     *     ([TunableMetadata.validateCustomSysfsPath]) already rejects these, but
     *     PServer runs the write as ROOT, so the writer-local validator must NOT
     *     be weaker than the door — defence-in-depth in case a caller reaches the
     *     writer with a path that bypassed the door (e.g. an internally-constructed
     *     [TunableId]).
     */
    internal fun validateSysfsPath(path: String): String? {
        // Control-character / traversal checks apply to EVERY accepted family.
        if (path.contains("..")) {
            return "path contains path-traversal sequence '..' (got '$path')"
        }
        // SEC-2: reject real NUL bytes (the doc above always claimed this guard).
        if (path.any { it.code == 0 }) {
            return "path contains a null byte"
        }
        if (path.contains('\n') || path.contains('\r') || path.contains(' ')) {
            return "path contains disallowed control characters"
        }
        // CRITICAL-2: reject the FULL shell-metacharacter set, via the single-source
        // [TunableMetadata.containsShellMetachar] (same set the door + the guard use —
        // no second copy). The writer runs the result as ROOT, so it must not be weaker
        // than the door: a metachar path ($(), backtick, ;, |, …) is denied here too.
        if (io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.containsShellMetachar(path)) {
            return "path contains a disallowed shell metacharacter (got '$path')"
        }
        // Narrow cgroup-boost carve-out: uclamp (/dev/cpuctl) and schedtune (/dev/stune)
        // are legitimate perf levers PServer-LIVE now writes. Permit ONLY the exact
        // modeled node shapes — kept in lockstep with PServerCommandGuard's allow-list,
        // which is the unbypassable chokepoint that ALSO permits them. Never a blanket /dev/.
        if (isCgroupBoostNode(path)) {
            return null
        }
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) {
            return "path must start with /sys/ or /proc/ (or a modeled cgroup-boost node) (got '$path')"
        }
        // SEC-2: apply the SAME dangerous-node block list the door validator uses.
        if (io.github.mayusi.calibratesoc.data.tunables.TunableMetadata.isDangerousPath(path)) {
            return "path '$path' is on the dangerous-node block list"
        }
        return null
    }

    /**
     * Exact-shape allow for the cgroup-boost perf nodes (uclamp on cpuctl, schedtune
     * on stune) — the only non-/sys, non-/proc paths this validator accepts. Mirrors
     * [PServerCommandGuard.isCgroupBoostNode] so the writer-local check and the
     * unbypassable guard never drift. Slice names restricted to `[A-Za-z0-9_-]+`.
     */
    internal fun isCgroupBoostNode(path: String): Boolean = CGROUP_BOOST_NODE.matches(path)
}

private val CGROUP_BOOST_NODE = Regex(
    """/dev/cpuctl/[A-Za-z0-9_-]+/cpu\.uclamp\.(min|max)""" +
        """|/dev/stune/[A-Za-z0-9_-]+/schedtune\.(boost|prefer_idle)""",
)
