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

    /**
     * Cached live connection + its binder. Rebound on death.
     *
     * MEDIUM-2: [connection] is @Volatile and mutated only under [bindMutex] (the lone
     * exception is [onServiceDisconnected]'s teardown, which calls [dropConnection]).
     * Without @Volatile a torn read across threads could unbind a connection another
     * thread just replaced.
     */
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null

    /** Serialises bind / rebind so two callers never launch parallel binds. */
    private val bindMutex = Mutex()

    /**
     * MEDIUM-2: serialises a single [sendCommand] send + its dead-binder retry so a
     * concurrent probe rebind cannot swap [binder] out from under an in-flight retry.
     * Distinct from [bindMutex] (which only guards bind/rebind) so a send never holds
     * the bind lock across the transact — the critical section here is just the
     * send-then-maybe-rebind-then-retry sequence, all of which is fast.
     */
    private val sendMutex = Mutex()

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
    suspend fun sendCommand(command: String): Boolean = sendMutex.withLock {
        // MEDIUM-2: the whole send (+ dead-binder rebind + retry) is serialised so a
        // concurrent probe rebind can't swap the binder under our in-flight retry. The
        // transact itself is fast and oneway-ish, so a single send holding this lock is
        // a short critical section; it does NOT hold bindMutex across the transact.
        val live = ensureBinder() ?: run {
            Log.w(TAG, "sendCommand(): no binder available — bind failed/denied")
            return@withLock false
        }
        when (val r = transact(live, command)) {
            is TransactOutcome.Ok -> true
            is TransactOutcome.DeadObject -> {
                // The cached binder died between calls. Drop it, rebind once, retry.
                Log.w(TAG, "sendCommand(): dead binder, rebinding once: ${r.reason}")
                // HIGH-2: dropConnection() unbinds the dead connection before we rebind so
                // we never leak it. MEDIUM-3: the availability we cached referred to this
                // now-dead binder — clear it so availability can't diverge from liveness.
                dropConnection()
                availableCache = null
                val rebound = ensureBinder() ?: return@withLock false
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
     * Call after the user (re)installs gamewindow or on an onResume / daemon-start
     * re-probe trigger.
     *
     * CRITICAL-1: this MUST be called immediately before every [CapabilityProbe.refresh]
     * that feeds a LIVE gate (mirroring [PServerWriter.invalidateTransactableCache]) so a
     * stale `true` from before a gamewindow force-stop/restart can't make us claim LIVE on
     * a binder we can no longer drive. Production call sites:
     *   - [io.github.mayusi.calibratesoc.data.autotdp.AutoTdpService.runDaemon] before each
     *     `capabilityProbe.refresh()` (the initial probe AND the PServer-retry probe).
     *   - [io.github.mayusi.calibratesoc.MainActivity.onResume] before `capabilityProbe.refresh()`.
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
        // Fast path: a live cached binder needs no lock. A DEAD binder must NOT be
        // dropped here (MEDIUM-1) — dropConnection() mutates `connection`/`binder` and
        // must run under bindMutex so concurrent callers don't double-unbind / churn.
        binder?.let { if (it.isBinderAlive) return it }
        return bindMutex.withLock {
            // Re-check inside the lock — another caller may have bound while we waited.
            binder?.let { if (it.isBinderAlive) return@withLock it }
            // A stale (dead) binder may still be cached with its connection bound.
            // HIGH-2: unbind the old connection BEFORE binding a fresh one so we never
            // leak a ServiceConnection (ServiceConnectionLeaked) across rebinds. Safe to
            // call when nothing is bound. Exactly one live binding at a time.
            dropConnection()
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
                            // HIGH-2: the binding is dead — UNBIND the old connection (not
                            // just null the binder) so the next ensureBinder() doesn't bind a
                            // fresh ServiceConnection on top of a leaked one. dropConnection()
                            // unbinds + nulls both connection and binder.
                            dropConnection()
                            // MEDIUM-3: the binder we probed as available is gone, so the
                            // memoised availability is now potentially stale. Clear it so the
                            // next isAvailable() re-probes (a re-bind) rather than returning a
                            // `true` that no longer holds — availability can't diverge from
                            // binder-liveness.
                            availableCache = null
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
