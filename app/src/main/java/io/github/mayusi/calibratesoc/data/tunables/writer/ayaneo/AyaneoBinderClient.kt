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
 * for `com.ayaneo.gamewindow`'s `AyaAidlService` instead of AYN's PServer.
 *
 * ## What the perf binder is (decompiled from the AYANEO vendor apps)
 *
 * The actuating service is `com.ayaneo.gamewindow/.utils.aidl.AyaAidlService`. Its
 * `onBind()` returns an `AyaAidlInterface`-style stub; transaction 1 reads a string and
 * dispatches it to `PerformanceManager`, which (running as uid=system) actuates the
 * privileged CPU/GPU/fan sysfs write. The wire protocol (the `com_set_performance_*`
 * token family in [AyaneoCommands]) is unchanged across the firmwares we have inspected.
 *
 * ## The firmware-version problem this client must survive (THE root cause we fixed)
 *
 * Whether a NON-system app (us — debug-signed, our own uid) can bind that service depends
 * entirely on whether the firmware **declares it as an exported `<service>` in the
 * gamewindow manifest**:
 *  - The `AyaAidlService` is bindable zero-setup by explicit ComponentName from a normal
 *    (non-system) app. LIVE-VERIFIED on a Pocket DS running SG8275 / Android 13: the bind
 *    succeeds (`manifest resolution = Resolvable`, `BIND OK`) AND actuates — a GPU-set via
 *    the binder physically moved the kgsl ceiling 680→550→680 MHz (restored to stock).
 *  - CAVEAT on `dumpsys package`: the Service Resolver Table only lists services with an
 *    intent-filter (Notification/WindowKeyEvent here). `AyaAidlService` is exported WITHOUT
 *    a filter, so it is absent from that table yet still bindable by explicit component —
 *    do NOT treat "not in the resolver table" as "not bindable". We bind by component and
 *    let PackageManager resolution + the real bind result be the source of truth.
 *  - Firmware variance is still possible (a build could un-export or rename the service),
 *    which is why this client walks a candidate list + probes honestly rather than assuming.
 *
 * Before this fix the bind failure was SILENT (`isAvailable` logged nothing and just
 * returned false), so a device could not tell WHY AYANEO fell to the read-only tier. This
 * client now:
 *  1. tries a small CANDIDATE LIST of `(pkg, cls)` perf-service components (old name + any
 *     known variants) so it works across Pocket DS firmware versions without a code change;
 *  2. RESOLVES each candidate against PackageManager first, so a "not declared / not
 *     exported in the manifest" firmware is reported as exactly that — not a mystery;
 *  3. LOGS every outcome (package-absent / not-resolvable / not-exported / bind-returned-
 *     false / null-binding / timeout / dead-binder) at WARN/INFO so on-device debugging is
 *     possible on release builds.
 *
 * HONESTY: when no candidate binds, [isAvailable] returns false and the app falls back to
 * its other tiers (Shizuku / root). We NEVER report a live binder we cannot actually drive.
 *
 * ## Wire protocol
 *  - Bind: Intent component {pkg, cls} from a [CANDIDATES] entry, BIND_AUTO_CREATE.
 *  - On the IBinder: `transact(1, data, reply, 0)` where data is
 *    `writeInterfaceToken([IFACE_DESCRIPTOR])` then `writeString(payload)`; then
 *    `reply.readException()` (throws if the server wrote an exception).
 *  - payload = the full `<clientId>:<tag>:<command>` string from [AyaneoCommands].
 *
 * ## Lifecycle
 *  - The connection is CACHED across calls (re-bind only when the binder is null/dead),
 *    so the steady-state cost per command is a single `transact`, not a bind round-trip.
 *    The component that actually bound is remembered so a rebind goes straight to it.
 *  - A [Mutex] serialises bind/rebind so concurrent callers never race two binds.
 *  - [isAvailable] probes ONCE (a candidate resolves AND a real bind succeeds) and caches
 *    the result, mirroring [PServerWriter.isTransactable]'s honest-probe pattern. A mere
 *    "package installed" check is NOT sufficient — we require a real bind.
 *
 * ## Safety
 *  - Every failure mode (package absent, unresolvable, bind denied, bind timeout, dead
 *    binder, SecurityException, server exception) degrades to `false`/no-op — this NEVER
 *    throws and NEVER crashes the app.
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

    /**
     * The candidate component that actually bound (cached so a rebind goes straight to the
     * known-good component instead of re-walking the whole list). Cleared on a dead binder /
     * disconnect alongside [binder] so the next bind re-probes from the top.
     */
    @Volatile private var boundComponent: ComponentName? = null

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
     * True when at least one [CANDIDATES] perf-service component is installed AND we could
     * bind it at least once. Probed lazily and cached for the session.
     *
     * HONESTY: this performs a REAL bind (not just a package-presence check) — the service
     * could be present-but-unbindable on a firmware that un-exported it, and we must not
     * report a live path we can't actually drive. Mirrors [PServerWriter.isTransactable]'s
     * real-transact-then-cache discipline.
     *
     * Cheap short-circuit: if none of the candidate packages is installed we return false
     * with no IPC. The distinct candidate packages are checked once each.
     */
    suspend fun isAvailable(): Boolean {
        availableCache?.let { return it }

        // Cheap short-circuit: if NONE of the candidate packages is even installed there is
        // no AYANEO perf surface here — return false with no IPC. (Logged so a non-AYANEO
        // device's false is distinguishable from a bind failure on a real AYANEO unit.)
        // Check DISTINCT packages once each (several candidate components can share a package).
        val candidatePackages = CANDIDATES.map { it.first }.distinct()
        val installedPackages = candidatePackages.filter { isPackageInstalled(it) }
        if (installedPackages.isEmpty()) {
            Log.i(TAG, "isAvailable() → false: no AYANEO perf-service package installed " +
                "(checked $candidatePackages)")
            availableCache = false
            return false
        }

        // A successful ensureBinder() means a candidate RESOLVED in the manifest AND
        // bindService returned true AND onServiceConnected delivered a non-null binder
        // within the timeout. bindAnyCandidate() logs the per-candidate outcome itself.
        val ok = ensureBinder() != null
        if (ok) {
            Log.i(TAG, "isAvailable() → true: bound ${boundComponent?.flattenToShortString()}")
        } else {
            // HONESTY: every installed candidate failed to bind. On the newer Pocket DS
            // firmware this is expected — AyaAidlService is no longer manifest-declared, so a
            // non-system app cannot bind it. The app correctly falls back to its other tiers.
            Log.w(TAG, "isAvailable() → false: AYANEO package(s) $installedPackages present but " +
                "NO perf service was bindable (component not declared/exported in this firmware, " +
                "or bind denied). Falling back to non-binder tiers. See WARN lines above for the " +
                "exact per-candidate reason.")
        }
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
        Log.i(TAG, "invalidateAvailabilityCache(): cleared — next isAvailable() will re-probe")
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
            bindAnyCandidate()
        }
    }

    /**
     * Walk the [CANDIDATES] list and bind the FIRST one that is installed and actually hands
     * back a binder. Returns the live binder or null when every candidate fails. Logs the
     * exact reason per candidate so a firmware where the perf service was un-exported is
     * diagnosable from logcat. Must be called under [bindMutex].
     *
     * IMPORTANT — the manifest-resolution check is ADVISORY ONLY (a logged diagnostic), NOT a
     * gate: AYANEO's perf `<service>` is declared `exported="true"` with NO `<intent-filter>`,
     * so it is invisible to `queryIntentServices` and absent from the `dumpsys` Service
     * Resolver Table, yet it IS bindable by EXPLICIT component (verified live — our own
     * non-system app uid binds it). [getServiceInfo] is a component-level query and normally
     * returns such a service, but to be safe against any visibility quirk we ALWAYS attempt
     * the explicit bind when the package is installed, and only use the resolution result to
     * annotate the log. The bind itself (or its failure) is the source of truth.
     *
     * Cache hint: if a previous bind succeeded on [boundComponent], try it first so a rebind
     * goes straight to the known-good component.
     */
    private suspend fun bindAnyCandidate(): IBinder? {
        // Prefer the last-known-good component (fast rebind), then the rest of the list.
        // CANDIDATES are (pkg, cls) String pairs; the Android ComponentName is built lazily
        // per iteration below (on-device — never at class-load, so unit tests stay JVM-safe).
        val ordered = boundComponent?.let { last ->
            val lastPair = last.packageName to last.className
            listOf(lastPair) + CANDIDATES.filterNot { it == lastPair }
        } ?: CANDIDATES

        for ((pkg, cls) in ordered) {
            val component = ComponentName(pkg, cls)
            if (!isPackageInstalled(component.packageName)) {
                // Quiet at INFO — a candidate package simply not present on this device is
                // normal (we ship one client for several possible firmwares).
                Log.i(TAG, "candidate ${component.flattenToShortString()}: package not installed — skip")
                continue
            }
            // Advisory diagnostic only — does NOT gate the bind (see KDoc: filter-less
            // exported services are bindable-by-component but may not "resolve" by intent).
            val resolution = resolveService(component)
            Log.i(TAG, "candidate ${component.flattenToShortString()}: manifest resolution = " +
                "$resolution — attempting explicit-component bind regardless")

            val live = bindOnce(component)
            if (live != null) {
                Log.i(TAG, "candidate ${component.flattenToShortString()}: BIND OK")
                boundComponent = component
                return live
            }
            Log.w(TAG, "candidate ${component.flattenToShortString()}: bind did NOT deliver a " +
                "binder (manifest resolution was $resolution; see preceding WARN for the " +
                "false/null/timeout reason). Trying next candidate.")
        }
        return null
    }

    /**
     * Perform a single bind to [component] and suspend until onServiceConnected delivers the
     * binder or the timeout elapses. Caches the connection + binder on success. Never throws.
     */
    private suspend fun bindOnce(component: ComponentName): IBinder? {
        val appContext = context.applicationContext
        var localConn: ServiceConnection? = null
        var bound = false
        return try {
            withTimeout(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine<IBinder?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            Log.i(TAG, "onServiceConnected: ${name?.flattenToShortString()}")
                            cont.resumeIfActive(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            Log.w(TAG, "onServiceDisconnected: ${name?.flattenToShortString()}")
                            // HIGH-2: the binding is dead — UNBIND the old connection (not
                            // just null the binder) so the next ensureBinder() doesn't bind a
                            // fresh ServiceConnection on top of a leaked one. dropConnection()
                            // unbinds + nulls connection, binder, and boundComponent.
                            dropConnection()
                            // MEDIUM-3: the binder we probed as available is gone, so the
                            // memoised availability is now potentially stale. Clear it so the
                            // next isAvailable() re-probes (a re-bind) rather than returning a
                            // `true` that no longer holds — availability can't diverge from
                            // binder-liveness.
                            availableCache = null
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            Log.w(TAG, "onNullBinding: ${name?.flattenToShortString()} — " +
                                "service returned a null binder")
                            cont.resumeIfActive(null)
                        }
                    }
                    localConn = conn

                    val intent = Intent().apply { this.component = component }
                    bound = try {
                        appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "bindService SecurityException for " +
                            "${component.flattenToShortString()}: ${e.message}")
                        false
                    } catch (t: Throwable) {
                        Log.w(TAG, "bindService threw for ${component.flattenToShortString()}: " +
                            "${t.javaClass.simpleName}: ${t.message}")
                        false
                    }
                    if (!bound) {
                        Log.w(TAG, "bindService returned false for " +
                            "${component.flattenToShortString()} — framework refused the bind " +
                            "(service not exported to us / not declared on this firmware)")
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
            Log.w(TAG, "bindOnce(${component.flattenToShortString()}): bind timed out after " +
                "${BIND_TIMEOUT_MS}ms")
            if (bound) localConn?.let { runCatching { appContext.unbindService(it) } }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "bindOnce(${component.flattenToShortString()}): unexpected " +
                "${t.javaClass.simpleName}: ${t.message}")
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
        boundComponent = null
    }

    // ── Service resolution ─────────────────────────────────────────────────────

    private enum class ServiceResolution {
        /** A declared, exported service component visible to us — a bind can be attempted. */
        Resolvable,

        /** PackageManager has no such service component (not declared, or not visible to us). */
        NotDeclared,

        /** The component resolves but is not exported (a non-system app cannot bind it). */
        NotExported,
    }

    /**
     * Ask PackageManager whether [component] is a service we could actually bind: it must
     * resolve (be declared in the target's manifest AND visible to us via the `<queries>`
     * entry) and be exported. This converts the otherwise-SILENT `bindService → false` on a
     * firmware that un-declared the perf service into an explicit, logged reason.
     *
     * Visibility note: the AYANEO packages are declared in our AndroidManifest `<queries>`
     * block, so PackageManager exposes their components to us when they ARE declared+exported.
     * A genuinely un-exported / undeclared service resolves to NotDeclared/NotExported here
     * exactly as it would refuse the bind — so this pre-check is a faithful predictor, never
     * a false gate.
     */
    private fun resolveService(component: ComponentName): ServiceResolution = try {
        val info = context.packageManager.getServiceInfo(component, 0)
        if (info.exported) ServiceResolution.Resolvable else ServiceResolution.NotExported
    } catch (_: PackageManager.NameNotFoundException) {
        ServiceResolution.NotDeclared
    } catch (t: Throwable) {
        // Any unexpected PM failure: treat as not-declared so we skip the bind safely.
        Log.w(TAG, "resolveService(${component.flattenToShortString()}) threw " +
            "${t.javaClass.simpleName}: ${t.message} — treating as not-declared")
        ServiceResolution.NotDeclared
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

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
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
        /**
         * Primary (historical) package + service class of the AYANEO perf AIDL endpoint.
         * On firmwares that still declare+export it, this is the component we bind. Kept as
         * named constants because tests and docs reference the canonical component.
         */
        const val TARGET_PKG = "com.ayaneo.gamewindow"
        const val TARGET_CLS = "com.ayaneo.gamewindow.utils.aidl.AyaAidlService"

        /**
         * Candidate perf-service components, tried in order. The first one that is installed,
         * resolves as a declared+exported `<service>`, and hands back a binder wins.
         *
         * Why a list: the externally-bindable perf service has moved/been un-exported across
         * Pocket DS firmware revisions. Trying a small set of known component names makes this
         * client robust to the firmware version WITHOUT a code change — and the per-candidate
         * resolve+log makes it honest about which (if any) actually bound.
         *
         * All candidates speak the SAME wire protocol ([IFACE_DESCRIPTOR] + the
         * `com_set_performance_*` token family in [AyaneoCommands]) — decompiling the current
         * gamewindow build confirmed `AidlConstants` still defines the identical tokens. If a
         * future firmware genuinely changes the protocol, that is a separate change; here we
         * only vary WHICH component hosts the unchanged protocol.
         *
         * NOTE (live-verified on SG8275 / Android 13 Pocket DS): candidate #1
         * `com.ayaneo.gamewindow/.utils.aidl.AyaAidlService` resolves + binds cleanly from
         * our own uid and ACTUATES (GPU ceiling moved 680→550→680 MHz via the binder). It is
         * exported without an intent-filter, so it's absent from the `dumpsys` Service
         * Resolver Table but bindable by explicit component. The other candidates are
         * firmware-variance fallbacks; the honest per-candidate probe picks whichever binds.
         */
        // Stored as (pkg, cls) STRING pairs, NOT android.content.ComponentName objects.
        // ComponentName is an Android framework type; constructing it at class-load makes
        // this companion unusable under plain JVM unit tests (with returnDefaultValues the
        // stubbed getters return null → NPE in isAvailable). We build the ComponentName
        // lazily, at bind time, on-device — exactly where the old single-component code did.
        val CANDIDATES: List<Pair<String, String>> = listOf(
            // 1) The canonical historical component (worked zero-setup on older Pocket DS FW).
            TARGET_PKG to TARGET_CLS,
            // 2) Same service, addressed without the inner `utils.aidl` segment — some builds
            //    flatten the class path. Harmless if absent (resolve → NotDeclared → skipped).
            TARGET_PKG to "com.ayaneo.gamewindow.AyaAidlService",
            // 3) The settings app embeds the same AyaAidlInterface; if a firmware ever hosts
            //    the service there as an exported endpoint, this picks it up.
            "com.ayaneo.settings" to "com.ayaneo.gamewindow.utils.aidl.AyaAidlService",
        )

        /** Binder interface descriptor the server enforces on transaction 1. */
        const val IFACE_DESCRIPTOR = "com.ayaneo.gamewindow.AyaAidlInterface"

        /** send(String) is transaction code 1 (IBinder.FIRST_CALL_TRANSACTION). */
        const val TXN_SEND = IBinder.FIRST_CALL_TRANSACTION // == 1

        /** How long we wait for onServiceConnected before giving up. */
        const val BIND_TIMEOUT_MS = 3_000L
    }
}
