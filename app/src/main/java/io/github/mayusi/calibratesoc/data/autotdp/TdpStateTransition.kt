package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.Tunables

/**
 * PURE: computes the minimal set of [TunableId] → value writes needed to
 * move from [from] to [to], given the device's GPU root path and big-policy id.
 *
 * Extracted from [AutoTdpService] so it can be unit-tested without any Android
 * runtime. The service calls [delta] and then writes each [WriteOp] via
 * TunableWriter — every write is journaled and will be reverted on stop.
 *
 * Safety invariant: cpu0 MUST NOT appear in [to.parkedPrimeCores].
 * [AutoTdpEngine] guarantees this; we assert it here as defence-in-depth.
 */
object TdpStateTransition {

    data class WriteOp(
        val id: TunableId,
        val value: String,
        val description: String,
    )

    /**
     * Returns the minimal ordered list of writes to transition from [from] to [to].
     *
     * Ordering is deliberate:
     *  1. Park new cores (offline first — before freq cap)
     *  2. Apply big-cluster freq cap
     *  3. Apply GPU floor level (max_pwrlevel)
     *  4. Apply governor overrides
     *  5. Unpark cores — LAST so they come back at the new lower cap, not at full speed
     *
     * @param from        Previously applied state (the daemon's current write target)
     * @param to          New desired state from [AutoTdpEngine.decide]
     * @param bigPolicyId Policy ID for the big cluster's scaling_max_freq
     * @param gpuRootPath Adreno root path (e.g. "/sys/class/kgsl/kgsl-3d0"), or null
     */
    fun delta(
        from: TdpState,
        to: TdpState,
        bigPolicyId: Int,
        gpuRootPath: String?,
    ): List<WriteOp> {
        val ops = mutableListOf<WriteOp>()

        // ── 1. Park cores newly added to parkedPrimeCores ─────────────────────
        val toAddParked = to.parkedPrimeCores - from.parkedPrimeCores
        // Highest index first — mirrors engine's park order
        for (core in toAddParked.sortedDescending()) {
            // Defence-in-depth: NEVER park cpu0, ever.
            check(core != 0) { "BUG: cpu0 must never be offlined (core=$core)" }
            ops += WriteOp(
                id = Tunables.cpuOnline(core),
                value = "0",
                description = "park cpu$core (offline)",
            )
        }

        // ── 2. Big-cluster freq cap ────────────────────────────────────────────
        if (to.bigClusterCapKhz != from.bigClusterCapKhz) {
            val capKhz = to.bigClusterCapKhz
            if (capKhz != null) {
                ops += WriteOp(
                    id = Tunables.cpuMaxFreq(bigPolicyId),
                    value = capKhz.toString(),
                    description = "big cap policy$bigPolicyId → ${capKhz / 1000} MHz",
                )
            }
            // capKhz == null means "remove cap": the TunableWriter snapshot
            // recorded the stock value on the first write, so revertAll() on stop
            // will restore it. Nothing explicit needed here.
        }

        // ── 3. GPU floor level (Adreno max_pwrlevel) ──────────────────────────
        if (gpuRootPath != null && to.gpuFloorLevel != from.gpuFloorLevel) {
            val level = to.gpuFloorLevel
            if (level != null) {
                // Adreno: lower index = higher performance.
                // GPU-bound → keep this low (permissive) to prioritise the GPU.
                ops += WriteOp(
                    id = Tunables.adrenoMaxPowerLevel(gpuRootPath),
                    value = level.toString(),
                    description = "GPU max_pwrlevel → $level",
                )
            }
        }

        // ── 4. Governor overrides ──────────────────────────────────────────────
        for ((policyId, governor) in to.governorOverrides) {
            if (from.governorOverrides[policyId] != governor) {
                ops += WriteOp(
                    id = Tunables.cpuGovernor(policyId),
                    value = governor,
                    description = "governor policy$policyId → $governor",
                )
            }
        }

        // ── 5. Unpark cores — AFTER applying the new lower cap ─────────────────
        val toRemoveParked = from.parkedPrimeCores - to.parkedPrimeCores
        for (core in toRemoveParked.sorted()) {
            ops += WriteOp(
                id = Tunables.cpuOnline(core),
                value = "1",
                description = "unpark cpu$core (online)",
            )
        }

        return ops
    }
}
