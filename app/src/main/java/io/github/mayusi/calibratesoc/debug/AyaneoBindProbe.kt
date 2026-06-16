package io.github.mayusi.calibratesoc.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * DE-RISK PROBE — debug-only, isolated, removable.
 *
 * Single question this answers: can a NORMAL (non-system, non-root) app on
 * AYANEO Pocket DS firmware bind `com.ayaneo.gamewindow`'s exported
 * `AyaAidlService` and drive a real perf change through it?
 *
 * The Pocket DS vendor app `com.ayaneo.gamewindow` (system UID) exports, with
 * NO permission guard:
 *
 *   <service android:name="com.ayaneo.gamewindow.utils.aidl.AyaAidlService"
 *            android:exported="true"/>
 *
 * Wire protocol (decompiled from the device's own AYAWindow app — exact):
 *   - Interface descriptor: "com.ayaneo.gamewindow.AyaAidlInterface"
 *   - Transaction 1 = send(String): server does data.enforceInterface(descriptor)
 *     then data.readString(). No permission check.
 *   - The server splits the string `data.split(":", limit = 3)` into
 *     [clientId, tag, msg]. For a perf command the tag must be
 *     "msg_type_performance"; `msg` keeps any further colons (limit-3 split).
 *   - GPU max command: "com_set_performance_gpu:<maxFreqHz>" → server (system
 *     UID) writes /sys/class/kgsl/kgsl-3d0/devfreq/max_freq + max_gpuclk.
 *
 * So the full payload string we send is:
 *   "calibrate:msg_type_performance:com_set_performance_gpu:<targetHz>"
 *
 * We deliberately do NOT generate or ship an AIDL stub. We talk to the raw
 * IBinder via transact() so this probe stays self-contained and trivially
 * deletable once the question is answered.
 *
 * SAFETY:
 *   - GPU max is the ONLY target. Worst case it temporarily caps the GPU to the
 *     585 MHz OPP — fully reversible by a reboot or by re-sending 680000000.
 *   - We never touch CPU min/max (avoids the 384 MHz-collapse class of risk).
 *   - Any failure (can't bind, timeout, SecurityException, dead binder, bad
 *     reply) degrades to a logged [ProbeResult]; it must NEVER crash or ANR.
 *   - All binder work runs on the caller's coroutine (call from Dispatchers.IO),
 *     never the main thread.
 */
object AyaneoBindProbe {

    const val TAG = "AYANEO_PROBE"

    /** Target package + service class of the exported AIDL endpoint. */
    private const val TARGET_PKG = "com.ayaneo.gamewindow"
    private const val TARGET_CLS = "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"

    /** Binder interface descriptor the server enforces on transaction 1. */
    private const val IFACE_DESCRIPTOR = "com.ayaneo.gamewindow.AyaAidlInterface"

    /** send(String) is transaction code 1 in the decompiled server. */
    private const val TXN_SEND = IBinder.FIRST_CALL_TRANSACTION // == 1

    /** Our arbitrary clientId; the server only uses it for routing replies. */
    private const val CLIENT_ID = "calibrate"

    /** Performance-channel tag the server matches on. */
    private const val TAG_PERFORMANCE = "msg_type_performance"

    /** How long we wait for onServiceConnected before giving up. */
    private const val BIND_TIMEOUT_MS = 3_000L

    /**
     * Result of a single probe attempt. Exhaustive + non-throwing: every
     * failure mode maps to one of these so callers can log and move on.
     */
    sealed interface ProbeResult {
        /** bindService() returned false — the framework refused to bind. */
        data object BindDenied : ProbeResult

        /** Bound, but onServiceConnected never fired within the timeout. */
        data object BindTimeout : ProbeResult

        /**
         * transact() returned and the server raised no exception. The perf
         * command was accepted at the binder layer. Whether the kgsl node
         * actually moved must be confirmed out-of-band (cat the sysfs node).
         */
        data class SentOk(val payload: String) : ProbeResult

        /**
         * Bound and connected, but the transact() failed (binder threw, server
         * raised an exception, SecurityException, DeadObjectException, etc.).
         */
        data class TransactFailed(val reason: String) : ProbeResult
    }

    /**
     * Attempt to drive the GPU max-frequency cap to [targetHz] through the
     * AYANEO vendor binder. Suspends until the call completes, the bind times
     * out, or a failure is classified. Never throws.
     *
     * @param targetHz GPU max in Hz. Use 585000000 (a real sub-ceiling OPP) for
     *   an observable-yet-harmless test on the Pocket DS (stock ceiling is
     *   680000000).
     */
    suspend fun tryDriveGpuMax(context: Context, targetHz: Long): ProbeResult {
        val payload = "$CLIENT_ID:$TAG_PERFORMANCE:com_set_performance_gpu:$targetHz"
        Log.i(TAG, "probe start → target=${targetHz}Hz payload=\"$payload\"")

        val appContext = context.applicationContext
        var connection: ServiceConnection? = null
        var bindReturnedTrue = false

        return try {
            val result = withTimeout(BIND_TIMEOUT_MS) {
                val binder = awaitBinder(appContext) { conn, ok ->
                    connection = conn
                    bindReturnedTrue = ok
                } ?: return@withTimeout ProbeResult.BindDenied
                sendPayload(binder, payload)
            }
            logResult(result)
            result
        } catch (_: TimeoutCancellationException) {
            val r = ProbeResult.BindTimeout
            logResult(r)
            r
        } catch (t: Throwable) {
            // Belt-and-suspenders: nothing above should escape, but a probe must
            // never take the app down. Classify anything unexpected as a failure.
            val r = ProbeResult.TransactFailed("unexpected: ${t.javaClass.simpleName}: ${t.message}")
            logResult(r)
            r
        } finally {
            // Unbind in finally so we never leak the connection, even on timeout
            // cancellation. Only unbind if bindService actually returned true —
            // unbinding a never-bound connection throws IllegalArgumentException.
            val conn = connection
            if (conn != null && bindReturnedTrue) {
                runCatching { appContext.unbindService(conn) }
                    .onFailure { Log.w(TAG, "unbindService error: ${it.message}") }
            }
        }
    }

    /**
     * bindService() to the exported AyaAidlService and suspend until either
     * onServiceConnected delivers the IBinder or [withTimeout] cancels us.
     *
     * Returns the IBinder on success, or null if the framework refused to bind
     * (bindService returned false / threw SecurityException). The [onBound]
     * callback hands the [ServiceConnection] + the bindService return value back
     * to the caller so it can be unbound in the outer finally.
     */
    private suspend fun awaitBinder(
        context: Context,
        onBound: (ServiceConnection, Boolean) -> Unit,
    ): IBinder? = suspendCancellableCoroutine { cont ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                Log.i(TAG, "onServiceConnected: ${name?.flattenToShortString()}")
                cont.resumeIfActive(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "onServiceDisconnected: ${name?.flattenToShortString()}")
                // If the service drops before connecting, treat as no-binder.
                cont.resumeIfActive(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.w(TAG, "onNullBinding: ${name?.flattenToShortString()}")
                cont.resumeIfActive(null)
            }
        }

        val intent = Intent().apply {
            component = ComponentName(TARGET_PKG, TARGET_CLS)
        }

        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "bindService SecurityException: ${e.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "bindService threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }

        onBound(connection, bound)

        if (!bound) {
            Log.w(TAG, "bindService returned false — framework refused the bind")
            cont.resumeIfActive(null)
        }
        // else: wait for onServiceConnected / timeout. unbind handled by caller.
    }

    /**
     * Issue transaction 1 (send) with a properly tokenized + string-payloaded
     * Parcel, then read the reply's exception slot. The decompiled server uses
     * the standard writeNoException()/readException() handshake, so reading the
     * exception tells us whether the server accepted the command.
     */
    private fun sendPayload(binder: IBinder, payload: String): ProbeResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeString(payload)
            // Synchronous (non-oneway) call so we get the reply parcel back.
            binder.transact(TXN_SEND, data, reply, 0)
            // Throws if the server wrote an exception into the reply.
            reply.readException()
            Log.i(TAG, "transact OK — server accepted the perf command")
            ProbeResult.SentOk(payload)
        } catch (e: SecurityException) {
            ProbeResult.TransactFailed("SecurityException: ${e.message}")
        } catch (e: RemoteException) {
            // DeadObjectException is a RemoteException subclass.
            ProbeResult.TransactFailed("${e.javaClass.simpleName}: ${e.message}")
        } catch (t: Throwable) {
            ProbeResult.TransactFailed("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun logResult(result: ProbeResult) {
        when (result) {
            is ProbeResult.BindDenied ->
                Log.w(TAG, "RESULT = BIND_DENIED — a normal app cannot bind AyaAidlService on this firmware.")
            is ProbeResult.BindTimeout ->
                Log.w(TAG, "RESULT = BIND_TIMEOUT — bound but onServiceConnected never fired within ${BIND_TIMEOUT_MS}ms.")
            is ProbeResult.TransactFailed ->
                Log.w(TAG, "RESULT = TRANSACT_FAILED (${result.reason}) — bound but the perf command was rejected.")
            is ProbeResult.SentOk -> {
                Log.i(TAG, "RESULT = SENT_OK — binder accepted \"${result.payload}\".")
                Log.i(TAG, "VERIFY NOW → cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq")
                Log.i(TAG, "  (stock ceiling = 680000000; if it now reads 585000000 the vendor path WORKS)")
                Log.i(TAG, "RESTORE → re-fire the probe with target 680000000, or just reboot.")
            }
        }
    }

    /** Resume the continuation only once, only while it's still active. */
    private fun CancellableContinuation<IBinder?>.resumeIfActive(value: IBinder?) {
        if (isActive) resume(value)
    }
}
