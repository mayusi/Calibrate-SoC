package io.github.mayusi.calibratesoc.data.tunables.writer

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-PServer"

/**
 * Writes vendor Settings.System keys through AYN's private
 * `PServerBinder` service — the same path langerhans OdinTools uses
 * on Odin 2 to elevate `settings put system ...` commands to root
 * without the user installing Magisk.
 *
 * The mechanism (reverse-engineered from langerhans' ShellExecutor):
 *
 *   1. Reflect android.os.ServiceManager.getService("PServerBinder")
 *      → returns an IBinder for AYN's vendor service. NULL on devices
 *      where the service isn't published (non-AYN, AYN firmware where
 *      AYN removed PServer, factory tools, etc.).
 *   2. Write a Parcel containing the shell command string.
 *   3. binder.transact(code, data, reply, flags) executes the command
 *      as the binder's owning process (root, because PServer runs as
 *      a privileged system service). On success the reply parcel
 *      contains a status int and the command's stdout/stderr.
 *
 * Risk surface:
 *   - ServiceManager.getService is @hide. Android 11+ blocks calls
 *     to hidden methods from non-system apps unless they're allowlisted.
 *     We try reflection anyway and check the result; on SecurityException
 *     or null return, we report CapabilityDenied.
 *   - AYN's PServer may check the caller UID against app_whiteList
 *     and refuse unrecognised apps. langerhans is in the list by
 *     default; we are not. If the transact() returns a non-zero
 *     status code we report Rejected with the code.
 *   - The transaction code and parcel layout are private API. The
 *     code 1 + simple string parcel is what langerhans uses; if AYN
 *     changes their wire format on a future firmware update, this
 *     writer will silently start returning Rejected. The detection
 *     fires once at app launch via [PServerProbe].
 *
 * Only handles SETTINGS_SYSTEM tunables. SYSFS writes via PServer
 * are technically possible (PServer runs as root, so `chmod 666 ... &&
 * echo ... > ...` would work) but we keep that path on the dedicated
 * RootWriter / AynScriptDeployer route to avoid one writer doing
 * everything.
 */
@Singleton
class PServerWriter @Inject constructor() : SysfsWriter {

    override suspend fun read(id: TunableId): String? = null

    override suspend fun canWrite(id: TunableId): Boolean {
        if (id.kind != TunableKind.SETTINGS_SYSTEM) return false
        return withContext(Dispatchers.IO) { binder() != null }
    }

    override suspend fun write(id: TunableId, value: String): WriteResult {
        if (id.kind != TunableKind.SETTINGS_SYSTEM) {
            return WriteResult.CapabilityDenied(id, "PServerWriter handles SETTINGS_SYSTEM only.")
        }
        return withContext(Dispatchers.IO) {
            Log.i(TAG, "write(): target=${id.target} value=$value")
            val binder = binder()
            if (binder == null) {
                Log.w(TAG, "write(): binder() returned null — PServer not bindable")
                return@withContext WriteResult.CapabilityDenied(
                    id,
                    "PServerBinder service not present on this device.",
                )
            }
            Log.i(TAG, "write(): got binder=$binder, transacting...")

            val cmd = "settings put system ${id.target} $value"
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

            Log.i(TAG, "write(): transact returned status=$status stdout='$stdout'")
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
     * Submit one command, parse the reply parcel. Returns
     * (statusCode, stdout). Status convention is what langerhans
     * observes: 0 = success, non-zero = failure with details in
     * stdout.
     *
     * The transaction code (1) and parcel layout are the ones
     * langerhans' ShellExecutor uses; reverse-engineering AYN's
     * PServer to confirm a different code on Odin 3 firmware is
     * left for future investigation if status keeps returning the
     * "unknown transaction" error code (Android binder returns
     * UNKNOWN_TRANSACTION when the code is wrong).
     */
    private fun transact(binder: IBinder, command: String): Pair<Int, String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(BINDER_NAME)
            data.writeString(command)
            val ok = binder.transact(TRANSACT_CODE_EXEC, data, reply, 0)
            if (!ok) {
                return -1 to "binder.transact returned false (UNKNOWN_TRANSACTION)"
            }
            reply.setDataPosition(0)
            // PServer reply format: [int exceptionCode, string result].
            // exceptionCode==0 → success; we read string. !=0 → error.
            val status = runCatching { reply.readInt() }.getOrDefault(-1)
            if (status == 0) {
                val out = runCatching { reply.readString() }.getOrDefault(null).orEmpty()
                0 to out
            } else {
                -1 to "PServer status=$status"
            }
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val BINDER_NAME = "PServerBinder"
        const val TRANSACT_CODE_EXEC = 1
    }
}
