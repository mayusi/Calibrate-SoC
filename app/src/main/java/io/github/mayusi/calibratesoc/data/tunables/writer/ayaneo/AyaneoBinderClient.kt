package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "CalibrateSoC-AyaBinder"

/**
 * Production transport to the AYANEO vendor perf binder — the AYANEO analog of
 * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter]'s transport, but
 * for `com.ayaneo.gamewindow`'s exported `AyaAidlService` instead of AYN's PServer.
 *
 * ## What this proves (verified LIVE on AYANEO Pocket DS — SG8275 / Android 13)
 *
 * A normal (even debug-signed, non-system) app CAN bind
 * `com.ayaneo.gamewindow/.utils.aidl.AyaAidlService` (exported=true, NO permission
 * guard) and send perf commands; the overlay (uid=system) actuates the privileged
 * sysfs write. Binding succeeded, the transact returned without exception, and the
 * GPU kgsl node moved (680000000 → 585000000, then restored). This client reuses the
 * EXACT working binder call from the de-risk probe
 * ([io.github.mayusi.calibratesoc.debug.AyaneoBindProbe]) as the basis for production.
 *
 * ## Wire protocol
 *  - Bind: Intent component {pkg=[TARGET_PKG], cls=[TARGET_CLS]}, BIND_AUTO_CREATE.
 *  - On the IBinder: `transact(1, data, reply, 0)` where data is
 *    `writeInterfaceToken([IFACE_DESCRIPTOR])` then `writeString(payload)`; then
 *    `reply.readException()` (throws if the server wrote an exception).
 *  - payload = the full `<clientId>:<tag>:<command>` string from [AyaneoCommands].
 *
 * ## Lifecycle
 *  - The connection is CACHED across calls (re-bind only when the binder is null/dead),
 *    so the steady-state cost per command is a single `transact`, not a bind round-trip.
 *  - A [Mutex] serialises bind/rebind so concurrent callers never race two binds.
 *  - [isAvailable] probes ONCE (gamewindow installed + a real bind succeeds) and caches
 *    the result, mirroring [PServerWriter.isTransactable]'s honest-probe pattern. A mere
 *    "package installed" check is NOT sufficient — we require a real bind.
 *
 * ## Safety
 *  - Every failure mode (package absent, bind denied, bind timeout, dead binder,
 *    SecurityException, server exception) degrades to `false`/no-op — this NEVER throws
 *    and NEVER crashes the app.
 *  - All binder work runs off the main thread (callers dispatch on Dispatchers.IO).
 */
@Singleton
class AyaneoBinderClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Cached live connection + its binder. Rebound on death. */
    private var connection: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null

    /** Serialises bind / rebind so two callers never launch parallel binds. */
    private val bindMutex = Mutex()

    /** Memoised availability probe result. null = not yet probed. */
    @Volatile private var availableCache: Boolean? = null

    /**
     * True when `com.ayaneo.gamewindow` is installed AND we could bind its
     * `AyaAidlService` at least once. Probed lazily and cached for the session.
     *
     * HONESTY: this performs a REAL bind (not just a package-presence check) — the
     * service could be present-but-unbindable on a future firmware, and we must not
     * report a live path we can't actually drive. Mirrors
     * [PServerWriter.isTransactable]'s real-transact-then-cache discipline.
     *
     * Cheap short-circuit: if the package isn't installed we return false with no IPC.
     */
    suspend fun isAvailable(): Boolean {
        availableCache?.let { return it }
        if (!isGamewindowInstalled()) {
            availableCache = false
            return false
        }
        // A successful ensureBinder() means bindService returned true and
        // onServiceConnected delivered a non-null binder within the timeout.
        val ok = ensureBinder() != null
        if (BuildConfig.DEBUG) Log.i(TAG, "isAvailable() probe → $ok")
        availableCache = ok
        return ok
    }

    /**
     * Send a single performance [command] (the full `<clientId>:<tag>:<command>` payload
     * built by [AyaneoCommands]). Returns true when the binder's `transact` returned and
     * the server raised NO exception — i.e. the command was accepted at the binder layer.
     *
     * NOTE: the AYANEO server's `send` is fire-and-forget (no perf reply), so a `true`
     * here means "accepted", NOT "the sysfs node moved". The caller
     * ([AyaneoVendorWriter]) confirms the actual effect by reading back the actuated
     * node. Any failure (no binder, dead binder, SecurityException, server exception)
     * returns false — never throws.
     */
    suspend fun sendCommand(command: String): Boolean {
        val live = ensureBinder() ?: run {
            Log.w(TAG, "sendCommand(): no binder available — bind failed/denied")
            return false
        }
        return when (val r = transact(live, command)) {
            is TransactOutcome.Ok -> true
            is TransactOutcome.DeadObject -> {
                // The cached binder died between calls. Drop it, rebind once, retry.
                Log.w(TAG, "sendCommand(): dead binder, rebinding once: ${r.reason}")
                dropConnection()
                val rebound = ensureBinder() ?: return false
                transact(rebound, command) is TransactOutcome.Ok
            }
            is TransactOutcome.Failed -> {
                Log.w(TAG, "sendCommand(): transact failed: ${r.reason}")
                false
            }
        }
    }

    /**
     * Invalidate the cached availability result so the next [isAvailable] re-probes.
     * Call after the user (re)installs gamewindow or on an onResume re-probe trigger.
     */
    fun invalidateAvailabilityCache() {
        availableCache = null
        if (BuildConfig.DEBUG) Log.i(TAG, "invalidateAvailabilityCache(): cleared")
    }

    // ── Binder acquisition ─────────────────────────────────────────────────────

    /**
     * Returns a live binder, binding if necessary. Cached across calls; only the
     * first caller (or the first caller after a death) actually binds. Serialised by
     * [bindMutex] so concurrent callers share one bind. Returns null when the bind is
     * denied / times out / the service hands back a null binder.
     */
    private suspend fun ensureBinder(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it else dropConnection() }
        return bindMutex.withLock {
            // Re-check inside the lock — another caller may have bound while we waited.
            binder?.let { if (it.isBinderAlive) return@withLock it }
            bindOnce()
        }
    }

    /**
     * Perform a single bind and suspend until onServiceConnected delivers the binder
     * or the timeout elapses. Caches the connection + binder on success. Never throws.
     */
    private suspend fun bindOnce(): IBinder? {
        val appContext = context.applicationContext
        var localConn: ServiceConnection? = null
        var bound = false
        return try {
            withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (BuildConfig.DEBUG) {
                                Log.i(TAG, "onServiceConnected: ${name?.flattenToShortString()}")
                            }
                            cont.resumeIfActive(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            Log.w(TAG, "onServiceDisconnected: ${name?.flattenToShortString()}")
                            // The cached binder is now stale — drop it so the next call rebinds.
                            binder = null
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            Log.w(TAG, "onNullBinding: ${name?.flattenToShortString()}")
                            cont.resumeIfActive(null)
                        }
                    }
                    localConn = conn

                    val intent = Intent().apply {
                        component = ComponentName(TARGET_PKG, TARGET_CLS)
                    }
                    bound = try {
                        appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "bindService SecurityException: ${e.message}")
                        false
                    } catch (t: Throwable) {
                        Log.w(TAG, "bindService threw: ${t.javaClass.simpleName}: ${t.message}")
                        false
                    }
                    if (!bound) {
                        Log.w(TAG, "bindService returned false — framework refused the bind")
                        cont.resumeIfActive(null)
                    }
                    cont.invokeOnCancellation {
                        // Timed out before onServiceConnected — unbind so we don't leak.
                        if (bound) runCatching { appContext.unbindService(conn) }
                    }
                }
            }?.also { live ->
                // Success: keep the connection + binder for reuse.
                connection = localConn
                binder = live
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "bindOnce(): bind timed out after ${BIND_TIMEOUT_MS}ms")
            if (bound) localConn?.let { runCatching { appContext.unbindService(it) } }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "bindOnce(): unexpected ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Unbind + clear the cached connection. Safe to call when nothing is bound. */
    private fun dropConnection() {
        val conn = connection
        if (conn != null) {
            runCatching { context.applicationContext.unbindService(conn) }
                .onFailure { Log.w(TAG, "dropConnection(): unbind error: ${it.message}") }
        }
        connection = null
        binder = null
    }

    // ── Transact ───────────────────────────────────────────────────────────────

    private sealed interface TransactOutcome {
        data object Ok : TransactOutcome
        data class DeadObject(val reason: String) : TransactOutcome
        data class Failed(val reason: String) : TransactOutcome
    }

    /**
     * Issue transaction 1 (send) with the interface-tokenised + string payload, then
     * read the reply's exception slot. The decompiled server uses the standard
     * writeNoException()/readException() handshake, so a clean readException() means
     * the server accepted the command.
     */
    private fun transact(target: IBinder, payload: String): TransactOutcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeString(payload)
            target.transact(TXN_SEND, data, reply, 0)
            reply.readException()
            TransactOutcome.Ok
        } catch (e: android.os.DeadObjectException) {
            TransactOutcome.DeadObject(e.message ?: "DeadObjectException")
        } catch (e: RemoteException) {
            // Other RemoteException subclasses (TransactionTooLarge etc.) are not
            // recoverable by a rebind — treat as a plain failure.
            TransactOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            TransactOutcome.Failed("SecurityException: ${e.message}")
        } catch (t: Throwable) {
            TransactOutcome.Failed("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ── Package presence ───────────────────────────────────────────────────────

    private fun isGamewindowInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TARGET_PKG, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun CancellableContinuation<IBinder?>.resumeIfActive(value: IBinder?) {
        if (isActive) resume(value)
    }

    companion object {
        /** Target package + service class of the exported AIDL endpoint. */
        const val TARGET_PKG = "com.ayaneo.gamewindow"
        const val TARGET_CLS = "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"

        /** Binder interface descriptor the server enforces on transaction 1. */
        const val IFACE_DESCRIPTOR = "com.ayaneo.gamewindow.AyaAidlInterface"

        /** send(String) is transaction code 1 (IBinder.FIRST_CALL_TRANSACTION). */
        const val TXN_SEND = IBinder.FIRST_CALL_TRANSACTION // == 1

        /** How long we wait for onServiceConnected before giving up. */
        const val BIND_TIMEOUT_MS = 3_000L
    }
}
