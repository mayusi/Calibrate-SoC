package io.github.mayusi.calibratesoc.data.tunables.writer.retroid

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-RetroidFan"

/**
 * Production transport to the Retroid vendor fan binder — the Retroid analog of
 * [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoBinderClient]
 * (AYANEO's gamewindow binder) and
 * [io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter] (AYN PServer).
 *
 * ## What this proves (decompiled from `com.rp.gameassistant` + LIVE on RP6 13f60c13)
 *
 * A normal (even debug-signed, non-system) app CAN reach the Retroid fan governor
 * with NO root: acquire the registered `SettingsController` service, ask it for the
 * `FanProvider` binder, and drive the fan via that provider. Confirmed on-device:
 * SettingsController binder acquired, FanProvider binder acquired, txn 7 accepted,
 * txn 2 read-back returned 25000 (the configured `fan_speed`).
 *
 * ## Wire protocol (EXACT)
 *
 * Unlike AYANEO (one bindService Intent), Retroid uses a TWO-HOP registered-service
 * chain reached via reflection on the hidden `android.os.ServiceManager` (the SAME
 * hidden-API technique PServerWriter/AyaneoBinderClient already rely on):
 *
 *  1. `IBinder settingsController = ServiceManager.getService("SettingsController")`
 *  2. Get FanProvider from it (txn 1):
 *       data.writeInterfaceToken("com.ro.settings.IExternalControlManager")
 *       data.writeString("FanProvider")
 *       settingsController.transact(1, data, reply, 0); reply.readException()
 *       IBinder fanProvider = reply.readStrongBinder()
 *  3. On `fanProvider` (descriptor `com.ro.settings.IFanControlProvider`):
 *       - txn 5 `c(int mode)`  = SET FAN MODE          ([setMode])
 *       - txn 7 `r(int speed)` = SET CUSTOM FAN SPEED  ([setSpeed])
 *       - txn 2 `b() -> int`   = READ current fan value ([readFanValue]) — readback verify
 *       - txn 6 `j(boolean)`   = enable/toggle (declared for completeness, [setEnabled])
 *     Each: writeInterfaceToken("com.ro.settings.IFanControlProvider"), write the
 *     arg, transact(code), reply.readException(), and for txn 2 reply.readInt().
 *
 * ## Lifecycle (REUSES AyaneoBinderClient's discipline)
 *  - Both binders are CACHED and only re-acquired when null/dead (@Volatile, dropped
 *    on disconnect via [IBinder.isBinderAlive] checks + death recipients).
 *  - A [Mutex] serialises (re)acquisition so concurrent callers never race two binds.
 *  - [isAvailable] probes ONCE (SettingsController present + FanProvider obtainable)
 *    and caches the result, mirroring AyaneoBinderClient.isAvailable's
 *    real-acquire-then-cache discipline. A "service registered" check is NOT
 *    sufficient — we require the FanProvider binder to actually come back.
 *
 * ## Safety
 *  - Every failure mode (ServiceManager absent/blocked, provider null, SecurityException,
 *    dead binder, server exception) degrades to `null`/`false`/no-op — this NEVER throws
 *    and NEVER crashes the app.
 *  - All binder work runs off the main thread (every public method hops to Dispatchers.IO).
 */
@Singleton
class RetroidFanBinderClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Cached FanProvider binder. Re-acquired (with its SettingsController) on death. */
    @Volatile private var fanProvider: IBinder? = null

    /** Death recipient attached to the live FanProvider so we drop the cache on death. */
    @Volatile private var deathRecipient: IBinder.DeathRecipient? = null

    /** Serialises (re)acquisition of the SettingsController → FanProvider chain. */
    private val acquireMutex = Mutex()

    /** Serialises a single transact + its dead-binder rebind+retry. */
    private val callMutex = Mutex()

    /** Memoised availability probe result. null = not yet probed. */
    @Volatile private var availableCache: Boolean? = null

    // ── Availability ─────────────────────────────────────────────────────────────

    /**
     * True when the `SettingsController` service is registered AND we could obtain a
     * `FanProvider` binder from it at least once. Probed lazily and cached for the
     * session.
     *
     * HONESTY: this performs a REAL acquire (ServiceManager.getService + the txn-1
     * getProvider round-trip), not just a service-registered check — the provider
     * could be present-but-unobtainable on a firmware variant, and we must not report
     * a live path we can't actually drive. Mirrors AyaneoBinderClient.isAvailable.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        availableCache?.let { return@withContext it }
        val ok = ensureProvider() != null
        if (BuildConfig.DEBUG) Log.i(TAG, "isAvailable() probe → $ok")
        availableCache = ok
        ok
    }

    /**
     * Invalidate the cached availability result so the next [isAvailable] re-probes.
     * Call before any capability refresh that feeds a LIVE gate (mirrors
     * AyaneoBinderClient.invalidateAvailabilityCache and
     * PServerWriter.invalidateTransactableCache) so a stale `true` from before a
     * vendor-service restart can't make us claim LIVE on a provider we can no longer
     * reach.
     */
    fun invalidateAvailabilityCache() {
        availableCache = null
        if (BuildConfig.DEBUG) Log.i(TAG, "invalidateAvailabilityCache(): cleared")
    }

    // ── Public fan operations ────────────────────────────────────────────────────

    /**
     * Set the fan MODE via txn 5 `c(int)`. Used to enter CUSTOM mode
     * ([RetroidFanConfig.CUSTOM_MODE]) before a [setSpeed], and to restore the stock
     * governor ([RetroidFanConfig.SMART_MODE]) when the user turns custom fan off.
     * Returns true when the transact returned with no server exception. Never throws.
     */
    suspend fun setMode(mode: Int): Boolean = withContext(Dispatchers.IO) {
        callMutex.withLock { intTxn(TXN_SET_MODE, mode, "setMode") }
    }

    /**
     * Set the CUSTOM fan speed via txn 7 `r(int)`. The [speed] is on the device's
     * ~25000 scale (NOT 0-255 / 0-100); the caller ([RetroidFanController]) maps the
     * curve's representative duty onto it and clamps it to the safe floor. Returns
     * true when the transact returned with no server exception. Never throws.
     *
     * NOTE: this only takes effect when the fan is in CUSTOM mode (call [setMode]
     * with [RetroidFanConfig.CUSTOM_MODE] first) — in Smart/auto the governor ignores
     * the manual speed. The caller confirms the effect via [readFanValue].
     */
    suspend fun setSpeed(speed: Int): Boolean = withContext(Dispatchers.IO) {
        callMutex.withLock { intTxn(TXN_SET_SPEED, speed, "setSpeed") }
    }

    /**
     * Enable/toggle the custom-fan path via txn 6 `j(boolean)`. Declared for
     * completeness (the decompile shows the txn); the CUSTOM-mode flow drives the fan
     * through [setMode] + [setSpeed], so this is only used if the live pass shows a
     * separate enable gate is needed. Never throws.
     */
    suspend fun setEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        callMutex.withLock { boolTxn(TXN_SET_ENABLED, enabled, "setEnabled") }
    }

    /**
     * Read the current fan value via txn 2 `b() -> int`. This is the READBACK-VERIFY
     * signal: after a [setMode]+[setSpeed] the controller calls this and checks
     * whether the value moved toward the target. Returns null when the read failed
     * (no provider, dead binder, server exception, bad reply). Never throws.
     */
    suspend fun readFanValue(): Int? = withContext(Dispatchers.IO) {
        callMutex.withLock { readIntTxn(TXN_READ_FAN) }
    }

    // ── Transact helpers (run under callMutex) ───────────────────────────────────

    /** A write txn that carries a single int arg (txn 5 / txn 7). */
    private fun intTxn(code: Int, arg: Int, label: String): Boolean =
        writeTxn(code, label) { it.writeInt(arg) }

    /** A write txn that carries a single boolean arg (txn 6). */
    private fun boolTxn(code: Int, arg: Boolean, label: String): Boolean =
        writeTxn(code, label) { it.writeInt(if (arg) 1 else 0) }

    /**
     * Issue a write transaction (interface-tokenised, [writeArg] supplies the payload)
     * and read the reply's exception slot. On a DEAD binder, drop the cache, re-acquire
     * once, and retry. Any other failure returns false. Never throws.
     */
    private fun writeTxn(code: Int, label: String, writeArg: (Parcel) -> Unit): Boolean {
        val provider = ensureProviderBlocking() ?: run {
            Log.w(TAG, "$label: no FanProvider — acquire failed/denied")
            return false
        }
        return when (val r = doWriteTxn(provider, code, writeArg)) {
            is TxnOutcome.Ok -> true
            is TxnOutcome.Dead -> {
                Log.w(TAG, "$label: dead binder, re-acquiring once: ${r.reason}")
                dropProvider()
                availableCache = null
                val fresh = ensureProviderBlocking() ?: return false
                doWriteTxn(fresh, code, writeArg) is TxnOutcome.Ok
            }
            is TxnOutcome.Failed -> {
                Log.w(TAG, "$label: transact failed: ${r.reason}")
                false
            }
        }
    }

    internal sealed interface TxnOutcome {
        data object Ok : TxnOutcome
        data class Dead(val reason: String) : TxnOutcome
        data class Failed(val reason: String) : TxnOutcome
    }

    /**
     * Issue ONE write transaction against [target]: write the FanProvider interface
     * token, the [writeArg] payload, transact([code]), and read the reply's exception.
     * Exposed (internal) so unit tests can verify the EXACT interface token + txn code
     * the wire carries by static-mocking [Parcel] and passing a mock [IBinder] — without
     * needing a live ServiceManager. Never throws.
     */
    @VisibleForTesting
    internal fun doWriteTxn(target: IBinder, code: Int, writeArg: (Parcel) -> Unit): TxnOutcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            writeArg(data)
            target.transact(code, data, reply, 0)
            reply.readException()
            TxnOutcome.Ok
        } catch (e: android.os.DeadObjectException) {
            TxnOutcome.Dead(e.message ?: "DeadObjectException")
        } catch (e: RemoteException) {
            TxnOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            TxnOutcome.Failed("SecurityException: ${e.message}")
        } catch (t: Throwable) {
            TxnOutcome.Failed("${t.javaClass.simpleName}: ${t.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** Read txn 2 `b() -> int`. Returns null on any failure. */
    private fun readIntTxn(code: Int): Int? {
        val provider = ensureProviderBlocking() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            provider.transact(code, data, reply, 0)
            reply.readException()
            val value = reply.readInt()
            if (BuildConfig.DEBUG) Log.i(TAG, "readFanValue(): txn $code → $value")
            value
        } catch (e: android.os.DeadObjectException) {
            // Drop + re-acquire once, then retry the read.
            Log.w(TAG, "readFanValue(): dead binder, re-acquiring once: ${e.message}")
            dropProvider()
            availableCache = null
            val fresh = ensureProviderBlocking() ?: return null
            readIntOnce(fresh, code)
        } catch (e: RemoteException) {
            Log.w(TAG, "readFanValue(): ${e.javaClass.simpleName}: ${e.message}"); null
        } catch (e: SecurityException) {
            Log.w(TAG, "readFanValue(): SecurityException: ${e.message}"); null
        } catch (t: Throwable) {
            Log.w(TAG, "readFanValue(): ${t.javaClass.simpleName}: ${t.message}"); null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Issue ONE read transaction ([TXN_READ_FAN] = `b() -> int`) against [provider]:
     * write the interface token, transact([code]), read the reply's exception, then the
     * int. Exposed (internal) for the same token+code verification as [doWriteTxn].
     * Returns null on any failure. Never throws.
     */
    @VisibleForTesting
    internal fun readIntOnce(provider: IBinder, code: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(FAN_PROVIDER_DESCRIPTOR)
            provider.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            Log.w(TAG, "readFanValue() retry: ${t.javaClass.simpleName}: ${t.message}"); null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Acquire ONE FanProvider from a given [settingsController] (txn 1, token
     * [EXTERNAL_CONTROL_DESCRIPTOR], string [PROVIDER_NAME]). Exposed (internal) so a
     * unit test can verify the EXACT getProvider wire (token + txn 1 + provider name)
     * against a mock SettingsController without a live ServiceManager.
     */
    @VisibleForTesting
    internal fun acquireFanProviderFrom(settingsController: IBinder): IBinder? =
        getFanProvider(settingsController)

    // ── Provider acquisition (SettingsController → FanProvider) ───────────────────

    /**
     * Suspending acquire used by [isAvailable]. Returns a live FanProvider, acquiring
     * the SettingsController → FanProvider chain if necessary. Cached; serialised by
     * [acquireMutex].
     */
    private suspend fun ensureProvider(): IBinder? {
        fanProvider?.let { if (it.isBinderAlive) return it }
        return acquireMutex.withLock {
            fanProvider?.let { if (it.isBinderAlive) return@withLock it }
            dropProvider()
            acquireOnce()
        }
    }

    /**
     * Blocking acquire used by the transact helpers (already on Dispatchers.IO under
     * [callMutex]). Fast path returns the cached live provider; otherwise acquires
     * once. Kept non-suspend so the transact helpers stay synchronous (Parcels are
     * not safe to hold across suspension points).
     */
    private fun ensureProviderBlocking(): IBinder? {
        fanProvider?.let { if (it.isBinderAlive) return it }
        synchronized(this) {
            fanProvider?.let { if (it.isBinderAlive) return it }
            dropProvider()
            return acquireOnce()
        }
    }

    /**
     * Acquire SettingsController via reflection, then its FanProvider via txn 1.
     * Caches the provider + attaches a death recipient. Returns null on any failure.
     * Never throws.
     */
    private fun acquireOnce(): IBinder? {
        val controller = getSettingsController() ?: run {
            Log.w(TAG, "acquireOnce(): SettingsController service is null/unreachable")
            return null
        }
        val provider = getFanProvider(controller) ?: run {
            Log.w(TAG, "acquireOnce(): FanProvider came back null from SettingsController")
            return null
        }
        // Cache + watch for death so we drop the stale provider promptly.
        val recipient = IBinder.DeathRecipient {
            Log.w(TAG, "FanProvider died — dropping cache")
            dropProvider()
            availableCache = null
        }
        runCatching { provider.linkToDeath(recipient, 0) }
            .onFailure { Log.w(TAG, "linkToDeath failed: ${it.message}") }
        fanProvider = provider
        deathRecipient = recipient
        if (BuildConfig.DEBUG) Log.i(TAG, "acquireOnce(): FanProvider acquired: $provider")
        return provider
    }

    /** Drop the cached provider (unlink death recipient). Safe when nothing cached. */
    private fun dropProvider() {
        val recipient = deathRecipient
        val provider = fanProvider
        if (recipient != null && provider != null) {
            runCatching { provider.unlinkToDeath(recipient, 0) }
        }
        fanProvider = null
        deathRecipient = null
    }

    /**
     * Hidden `android.os.ServiceManager.getService("SettingsController")` via
     * reflection — the SAME technique PServerWriter/AyaneoBinderClient use for
     * hidden-API access. Returns null on any failure.
     */
    private fun getSettingsController(): IBinder? = try {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    } catch (t: Throwable) {
        Log.w(TAG, "getSettingsController(): ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * txn 1 on SettingsController: getProvider("FanProvider") -> IBinder. Returns the
     * FanProvider binder, or null on any failure / null reply binder.
     */
    private fun getFanProvider(settingsController: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(EXTERNAL_CONTROL_DESCRIPTOR)
            data.writeString(PROVIDER_NAME)
            settingsController.transact(TXN_GET_PROVIDER, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } catch (t: Throwable) {
            Log.w(TAG, "getFanProvider(): ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        /** ServiceManager service name registered by the RP6 vendor daemon. */
        const val SERVICE_NAME = "SettingsController"

        /** Descriptor enforced by SettingsController on txn 1 (getProvider). */
        const val EXTERNAL_CONTROL_DESCRIPTOR = "com.ro.settings.IExternalControlManager"

        /** Descriptor enforced by FanProvider on its txns. */
        const val FAN_PROVIDER_DESCRIPTOR = "com.ro.settings.IFanControlProvider"

        /** The provider name passed to SettingsController.transact(1). */
        const val PROVIDER_NAME = "FanProvider"

        // ── Transaction codes (decompiled, exact) ───────────────────────────────
        /** SettingsController.getProvider(String) -> IBinder. */
        const val TXN_GET_PROVIDER = 1
        /** FanProvider.b() -> Int — read current fan value (readback-verify). */
        const val TXN_READ_FAN = 2
        /** FanProvider.c(int mode) — set fan mode. */
        const val TXN_SET_MODE = 5
        /** FanProvider.j(boolean) — enable/toggle. */
        const val TXN_SET_ENABLED = 6
        /** FanProvider.r(int speed) — set custom fan speed. */
        const val TXN_SET_SPEED = 7
    }
}
