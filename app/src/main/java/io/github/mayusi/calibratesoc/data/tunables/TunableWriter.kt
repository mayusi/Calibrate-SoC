package io.github.mayusi.calibratesoc.data.tunables

import android.util.Log
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapter
import io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.RootWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.WriteProtocol
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunableWriter"

/**
 * The ONLY entry point for performing a tunable write in this app. Every
 * other module routes through here so the snapshot-then-write invariant
 * holds.
 *
 * Flow per write:
 *   1. Resolve the appropriate writer for the privilege tier.
 *   2. Read the current value via that writer (so the snapshot reflects
 *      what was visible to *us* — not a stale read from a different
 *      privilege tier).
 *   3. Append a snapshot record to [TunableSnapshotStore]. We do this
 *      BEFORE the write so a crash between snapshot and write leaves us
 *      with a harmless extra journal entry, not a missing one.
 *   4. Perform the write.
 *   5. Return the [WriteResult] to the caller.
 *
 * Steps 3 and 4 are deliberately not atomic — there's no kernel
 * primitive that makes them so. The invariant we maintain is "any value
 * we ever changed is in the journal." That's stronger than "the journal
 * exactly matches reality at any instant" and it's what boot-revert
 * needs.
 */
@Singleton
class TunableWriter @Inject constructor(
    private val registry: WriterRegistry,
    private val snapshotStore: TunableSnapshotStore,
    private val deviceAdapterRegistry: DeviceAdapterRegistry,
    private val rootWriter: RootWriter,
    private val pServerWriter: PServerWriter,
) {

    suspend fun write(
        id: TunableId,
        value: String,
        report: CapabilityReport,
        reason: String,
    ): WriteResult {
        val writer = registry.writerFor(id, report)
        val previous = writer.read(id)
        snapshotStore.append(
            TunableSnapshot(
                id = id,
                previousValue = previous,
                writtenAtMs = System.currentTimeMillis(),
                reason = reason,
            ),
        )

        // On Root or PServer tier, give the per-device adapter a chance to
        // dictate the write protocol (stop daemons, chmod-lock). Adapters that
        // don't declare any are no-ops and we fall through to the generic path.
        // This is what makes AYN Odin 3 underclocks sticky against
        // perfd / vendor.perf-hal-* races.
        //
        // BUG FIX (BUG 3): previously only rootWriter received the protocol;
        // PServerWriter also performs chmod+write and the vendor daemons race
        // against it on PServer-LIVE tier just as they do on Root tier.
        // We now dispatch protocol.pre/post via PServer's executeShell for the
        // PServer case; the chmod sandwich is already baked into writeSysfs so
        // we only need the daemon stop/start hooks from the protocol.
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        val protocol = adapter?.let { writeProtocolFor(it, id) } ?: WriteProtocol.NONE
        return when {
            writer === rootWriter && protocol != WriteProtocol.NONE ->
                rootWriter.writeWithProtocol(id, value, protocol)
            writer === pServerWriter && protocol != WriteProtocol.NONE -> {
                // Run pre-hooks (daemon stop) through PServer before the write.
                for (cmd in protocol.pre) {
                    val result = pServerWriter.executeShell(cmd)
                    if (result == null) {
                        Log.w(TAG, "write(): PServer pre-hook '$cmd' returned null (circuit-breaker or binder gone)")
                    }
                }
                val writeResult = writer.write(id, value)
                // Run post-hooks (daemon start) regardless of write result.
                for (cmd in protocol.post) {
                    val result = pServerWriter.executeShell(cmd)
                    if (result == null) {
                        Log.w(TAG, "write(): PServer post-hook '$cmd' returned null")
                    }
                }
                writeResult
            }
            else -> writer.write(id, value)
        }
    }

    /**
     * Build the per-device write protocol for a tunable, if the adapter
     * has anything to say. Today only SYSFS cpufreq writes get the
     * full stop-daemons-and-chmod-lock treatment — GPU + settings keys
     * are left alone.
     */
    private fun writeProtocolFor(adapter: DeviceAdapter, id: TunableId): WriteProtocol {
        if (id.kind != TunableKind.SYSFS) return WriteProtocol.NONE
        val isCpuFreqWrite = id.target.contains("/cpufreq/") &&
            id.target.endsWith("_freq")
        if (!isCpuFreqWrite) return WriteProtocol.NONE
        if (adapter.perfDaemonsToStopOnWrite.isEmpty() && !adapter.chmodLockCpuFreqWrites) {
            return WriteProtocol.NONE
        }
        return WriteProtocol(
            pre = adapter.perfDaemonsToStopOnWrite.map { "stop $it" },
            post = adapter.perfDaemonsToStopOnWrite.map { "start $it" },
            relaxModeBeforeWrite = adapter.chmodLockCpuFreqWrites,
            sealModeAfterWriteOctal = if (adapter.chmodLockCpuFreqWrites) {
                WriteProtocol.MODE_READ_ONLY
            } else {
                WriteProtocol.MODE_LEAVE_ALONE
            },
        )
    }

    /**
     * Walk the journal in reverse-application order and write each
     * previous value back. Returns the count of successfully reverted
     * entries. Caller clears the journal only on full success.
     */
    suspend fun revertAll(report: CapabilityReport): RevertSummary {
        val journal = snapshotStore.read()
        var ok = 0
        var failed = 0
        for (entry in journal.entries.reversed()) {
            val writer = registry.writerFor(entry.id, report)
            val prev = entry.previousValue
            if (prev == null) {
                // Nothing meaningful to write back; treat as success.
                ok++
                continue
            }
            when (writer.write(entry.id, prev)) {
                is WriteResult.Success -> ok++
                // CapabilityDenied means the current privilege tier cannot write
                // this tunable at all (e.g. rebooted from ROOT → VENDOR_SETTINGS
                // tier). The sysfs value is unchanged — nothing was reverted but
                // nothing was made worse either. Treat as a skip (counts toward
                // ok) so the journal can still be cleared and the revert loop
                // terminates rather than looping forever on every boot.
                is WriteResult.CapabilityDenied -> ok++
                else -> failed++
            }
        }
        if (failed == 0) snapshotStore.clear()
        return RevertSummary(ok = ok, failed = failed, totalEntries = journal.entries.size)
    }

    data class RevertSummary(val ok: Int, val failed: Int, val totalEntries: Int)
}
