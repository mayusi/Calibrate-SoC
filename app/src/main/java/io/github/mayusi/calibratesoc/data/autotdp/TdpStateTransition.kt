package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.tunables.KernelTunables
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
     *  2. Lower the min-freq FLOOR (before the cap drops, so min never momentarily
     *     exceeds the new lower max) — Wave 2
     *  3. Apply big-cluster freq cap
     *  4. Raise the min-freq FLOOR (after the cap rises, so min ≤ max throughout) —
     *     Wave 2
     *  5. Apply GPU floor level (max_pwrlevel)
     *  6. Apply GPU devfreq min/max (Wave 2): write MIN before MAX when lowering,
     *     MAX before MIN when raising — handled by always writing min then max with
     *     the engine guaranteeing min < max
     *  7. Apply uclamp top-app hint (Wave 2)
     *  8. Apply fan_mode preset (Wave 2 — Settings.System write)
     *  9. Apply governor overrides
     * 10. Unpark cores — LAST so they come back at the new lower cap, not at full speed
     *
     * Every write below routes through [TunableWriter] which snapshots the previous
     * value before writing and reverts it on stop — the new Wave-2 actuators inherit
     * that machinery for free (no per-actuator revert code needed).
     *
     * @param from        Previously applied state (the daemon's current write target)
     * @param to          New desired state from [AutoTdpEngine.decide]
     * @param bigPolicyId Policy ID for the big cluster's scaling_max_freq
     * @param gpuRootPath Adreno root path (e.g. "/sys/class/kgsl/kgsl-3d0"), or null
     * @param fanModeKey  AYN/Retroid Settings.System key for the fan preset (e.g.
     *                    "fan_mode"), or null when no controllable fan key exists —
     *                    in which case fan_mode writes are honestly skipped.
     */
    fun delta(
        from: TdpState,
        to: TdpState,
        bigPolicyId: Int,
        gpuRootPath: String?,
        fanModeKey: String? = null,
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

        // ── 2. Lower the min-freq FLOOR BEFORE the cap drops ──────────────────
        // When the floor is decreasing we write it first so the cluster's min never
        // momentarily sits above a not-yet-lowered max (some kernels EINVAL that).
        val floorDropping = isFloorLower(to.bigClusterMinKhz, from.bigClusterMinKhz)
        if (floorDropping) {
            emitMinFloor(ops, to, from, bigPolicyId)
        }

        // ── 3. Big-cluster freq cap ────────────────────────────────────────────
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

        // ── 4. Raise the min-freq FLOOR AFTER the cap rises ───────────────────
        if (!floorDropping) {
            emitMinFloor(ops, to, from, bigPolicyId)
        }

        // ── 5. GPU floor level (Adreno max_pwrlevel) ──────────────────────────
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

        // ── 6. GPU devfreq min/max (finer than pwrlevel) ──────────────────────
        if (gpuRootPath != null) {
            if (to.gpuDevfreqMinHz != from.gpuDevfreqMinHz) {
                to.gpuDevfreqMinHz?.let { minHz ->
                    ops += WriteOp(
                        id = Tunables.gpuMinFreq(gpuRootPath),
                        value = minHz.toString(),
                        description = "GPU devfreq min → ${minHz / 1_000_000} MHz",
                    )
                }
            }
            if (to.gpuDevfreqMaxHz != from.gpuDevfreqMaxHz) {
                to.gpuDevfreqMaxHz?.let { maxHz ->
                    ops += WriteOp(
                        id = Tunables.gpuMaxFreq(gpuRootPath),
                        value = maxHz.toString(),
                        description = "GPU devfreq max → ${maxHz / 1_000_000} MHz",
                    )
                }
            }
        }

        // ── 7. uclamp top-app perf hint ───────────────────────────────────────
        if (to.uclampTopAppMin != from.uclampTopAppMin) {
            to.uclampTopAppMin?.let { hint ->
                ops += WriteOp(
                    id = KernelTunables.uclampMin(KernelTunables.Slices.TOP_APP),
                    value = hint.toString(),
                    description = "top-app uclamp.min → $hint",
                )
            }
        }

        // ── 8. fan_mode preset (Settings.System) ──────────────────────────────
        // Honesty: only emitted when a controllable fan key is known. The write
        // routes through SettingsKeyWriter / PServerWriter and is snapshotted +
        // reverted on stop exactly like a sysfs write.
        if (fanModeKey != null && to.fanMode != from.fanMode) {
            to.fanMode?.let { mode ->
                ops += WriteOp(
                    id = Tunables.settingsSystemKey(fanModeKey),
                    value = mode.toString(),
                    description = "fan_mode → $mode",
                )
            }
        }

        // ── 9. Governor overrides ──────────────────────────────────────────────
        for ((policyId, governor) in to.governorOverrides) {
            if (from.governorOverrides[policyId] != governor) {
                ops += WriteOp(
                    id = Tunables.cpuGovernor(policyId),
                    value = governor,
                    description = "governor policy$policyId → $governor",
                )
            }
        }

        // ── 10. Unpark cores — AFTER applying the new lower cap ────────────────
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

    /**
     * Emit the scaling_min_freq write when the floor changed and is non-null. A null
     * `to` floor means "remove the floor" — TunableWriter's snapshot reverts the
     * stock value on stop, so no explicit write is needed (mirrors the cap path).
     */
    private fun emitMinFloor(
        ops: MutableList<WriteOp>,
        to: TdpState,
        from: TdpState,
        bigPolicyId: Int,
    ) {
        if (to.bigClusterMinKhz == from.bigClusterMinKhz) return
        val minKhz = to.bigClusterMinKhz ?: return
        ops += WriteOp(
            id = Tunables.cpuMinFreq(bigPolicyId),
            value = minKhz.toString(),
            description = "big min floor policy$bigPolicyId → ${minKhz / 1000} MHz",
        )
    }

    /** True when the `to` floor is strictly lower than the `from` floor (null = stock
     *  = the lowest possible floor, so dropping TO null is also "lower"). */
    private fun isFloorLower(toFloor: Int?, fromFloor: Int?): Boolean = when {
        toFloor == fromFloor -> false
        fromFloor == null -> false          // was stock (lowest) → any change raises
        toFloor == null -> true             // to stock (lowest) → dropping
        else -> toFloor < fromFloor
    }
}
