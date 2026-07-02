package io.github.mayusi.calibratesoc.data.autotdp

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoCommands
import io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AyaneoStockReconciler"

/**
 * 0.3.6 — SHIP-BLOCKER FIX: self-heals a AYANEO device left PARKED at a non-stock vendor
 * performance mode after AutoTDP's owning process died without an orderly revert.
 *
 * ## The hazard this closes
 *
 * On AYANEO, AutoTDP's durable CPU-side lever is the vendor `com_set_performance_mode`
 * ordinal ([TdpCaps.supportsPerfMode]) — see [io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo.AyaneoVendorWriter].
 * [AutoTdpRevert.revertNow] now unconditionally re-asserts stock on every ORDERLY stop
 * (stopDaemon / onDestroy / onTaskRemoved / the runDaemon `finally`) — but none of those
 * run when the app process is FORCE-KILLED or CRASHES outright: there is no in-process
 * code left alive to do the write. A device tightened to mode 0 (614 MHz CPU / 220 MHz
 * GPU) with the app dead stays there until *something* re-asserts stock.
 *
 * This class is that "something" — the RECOVERY PATH that survives a force-kill, because
 * it runs on the NEXT process start rather than depending on the dying process cleaning up
 * after itself. Call [reconcile] from a process-start / app-resume hook (see call sites).
 *
 * ## Honesty / safety contract
 *
 *  - Only acts on AYANEO devices with the vendor binder confirmed live AND no full-kernel
 *    write path — the exact [TdpCaps.supportsPerfMode] gate AutoTDP itself uses to decide
 *    whether it owns the mode lever at all. Odin/RP6/root/non-AYANEO: always a no-op.
 *  - Only acts when AutoTDP is NOT currently running ([isAutoTdpServiceRunning]). If the
 *    daemon is live it is the legitimate owner of a non-stock mode (mid-tighten) — firing
 *    here would fight it and undo an intentional, in-progress tune.
 *  - Reads the live mode DIRECTLY via [AyaneoVendorWriter.readCurrentAyaneoMode] — the
 *    app-readable sysfs TUPLE (policy3 + policy7 `scaling_max_freq` + GPU `devfreq/max_freq`)
 *    that [AyaneoVendorWriter]'s own write-verify uses (same shared implementation, no
 *    bespoke binder call, no rebuilt writer) — BEFORE issuing any write. The vendor conf
 *    file this used to read is NOT app-readable (confirmed live: EACCES for our uid), so it
 *    always returned null and this reconciler ran blind. When the tuple reads stock (1)
 *    this is a pure no-op — no AIDL command is ever sent on a healthy device, so a normal
 *    resume/launch never spams the vendor overlay.
 *
 *    NOTE: [AyaneoVendorWriter.read] for this tunable deliberately returns the constant
 *    stock value (it seeds the AutoTDP revert-journal baseline, NOT a live probe — see its
 *    doc comment), so it cannot be reused for this "is the device currently parked"
 *    read-first check. This class reads the live tuple itself instead, exactly as
 *    [AyaneoVendorWriter.writePerformanceMode]'s own readback-verify does.
 *
 *  - A null/unrecognized tuple (mid-transition, unmapped mode) is treated CONSERVATIVELY:
 *    skip this reconcile pass rather than reassert. A genuinely parked device (e.g. mode 0)
 *    reads a clean, recognized tuple — null only happens transiently or on an unmapped
 *    state, and reasserting into that is more likely to fight an in-flight transition than
 *    to fix a real hazard.
 */
@Singleton
class AyaneoStockReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tunableWriter: TunableWriter,
    private val fs: FileSystem,
) {

    /**
     * Reconcile the AYANEO vendor performance mode back to stock (1) IF all of:
     *  - [report] shows [TdpCaps.supportsPerfMode] true (AYANEO binder live, no
     *    full-kernel write path — AutoTDP's own gate for owning the mode lever), AND
     *  - AutoTDP's daemon is NOT currently running (never fights an active session), AND
     *  - the live vendor `currentMode` is NOT already stock (idempotent: a healthy device
     *    resuming/launching emits NO AIDL command at all — this is a true read-first check,
     *    not just an idempotent write).
     *
     * Best-effort: any failure is logged and swallowed. Never throws.
     */
    suspend fun reconcile(report: CapabilityReport) {
        runCatching {
            val caps = TdpCaps.from(report)
            if (!caps.supportsPerfMode) return@runCatching
            if (isAutoTdpServiceRunning()) {
                Log.d(TAG, "reconcile(): AutoTDP daemon is running — leaving the active mode alone")
                return@runCatching
            }

            // READ-FIRST: read the live sysfs-tuple mode WITHOUT writing anything. This is
            // what makes "already stock" a genuine no-op — the common healthy-device path
            // (resume/launch with nothing parked) never emits a single AIDL command.
            // A null/unrecognized tuple (mid-transition, unmapped mode) is treated
            // CONSERVATIVELY: skip this pass rather than reassert on a transient — a
            // genuinely parked device (e.g. mode 0) reads a clean, recognized tuple.
            val id = Tunables.ayaPerformanceMode()
            val current = withContext(Dispatchers.IO) {
                AyaneoVendorWriter.readCurrentAyaneoMode(fs)
            }
            if (current == null) {
                Log.d(TAG, "reconcile(): sysfs tuple unreadable/unrecognized — skipping this pass (conservative)")
                return@runCatching
            }
            if (current == AyaneoCommands.PERF_MODE_STOCK) {
                Log.d(TAG, "reconcile(): currentMode already stock (${AyaneoCommands.PERF_MODE_STOCK}) — no-op")
                return@runCatching
            }

            // currentMode is non-stock AND AutoTDP is not the owner. This is the
            // force-kill/crash recovery: re-assert stock now.
            Log.w(
                TAG,
                "reconcile(): AYANEO currentMode=$current is NOT stock and AutoTDP is not " +
                    "running — re-asserting stock mode ${AyaneoCommands.PERF_MODE_STOCK} " +
                    "(self-heal after a kill/crash left the device parked).",
            )
            val result = tunableWriter.write(
                id = id,
                value = AyaneoCommands.PERF_MODE_STOCK.toString(),
                report = report,
                reason = "AutoTDP reconcile: restore stock after unclean exit",
            )
            Log.i(TAG, "reconcile(): restore result=$result")
        }.onFailure {
            Log.w(TAG, "reconcile() threw ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * Best-effort check for whether [AutoTdpService] is currently alive in this process.
     * Mirrors [io.github.mayusi.calibratesoc.ui.overlay.HudQuickTileService]'s
     * `isServiceRunning` pattern exactly — including the raw String-key
     * `getSystemService(Context.ACTIVITY_SERVICE)` call (rather than the KTX reified
     * `getSystemService<T>()` extension, whose `ContextCompat` dispatch is awkward to stub
     * deterministically in a pure-JVM unit test) — `getRunningServices` is deprecated for
     * CROSS-app use but remains reliable for in-app introspection (checking our own service).
     */
    @Suppress("DEPRECATION")
    private fun isAutoTdpServiceRunning(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val targetName = AutoTdpService::class.java.name
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE).any { it.service.className == targetName }
        }.getOrDefault(false)
    }
}
