package io.github.mayusi.calibratesoc.data.autotdp

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport

/**
 * The device's tunable envelope — everything [AutoTdpEngine] is allowed to
 * touch, derived once from [CapabilityReport] and held immutable for the
 * duration of the daemon session.
 *
 * [primeCoreIndices]    — CPU indices that belong to the top cluster (highest
 *                         top OPP). cpu0 is NEVER included here even if it
 *                         shares the prime policy, because cpu0 must never be
 *                         offlined (kernel / Android hard requirement).
 *
 * [bigPolicyId]         — The policy ID for the big cluster (the policy whose
 *                         top OPP is the second highest — the "gold/big"
 *                         policy). On 2-cluster devices this is the same as
 *                         the prime policy (all non-little cores share one
 *                         policy). The engine caps this policy's
 *                         scaling_max_freq.
 *
 * [bigClusterOppStepsKhz] — Available OPP steps for the big policy, sorted
 *                           ascending, CLAMPED to the effective WRITABLE ceiling
 *                           (see [bigClusterWritableMaxKhz]). The engine steps
 *                           through these when clamping or relaxing the cap; its
 *                           top is therefore the highest OPP the device will
 *                           actually ACCEPT, never a kernel OPP the vendor write
 *                           path silently rejects. Because every cap-target and
 *                           the 40% hard floor are derived from this list's top,
 *                           clamping here makes the whole engine writable-bounded
 *                           at a single point.
 *
 * [bigClusterWritableMaxKhz] — The highest big-policy OPP that is actually
 *                           writable/accepted on THIS device's effective write
 *                           path, distinct from the full kernel OPP table top.
 *                           On a fully-kernel-writable device (root / PServer /
 *                           chmod-direct) this EQUALS the kernel OPP top — the
 *                           step table is unclamped and the engine still reaches
 *                           max (Odin 3 / RP6 unregressed). On a constrained
 *                           vendor path (AYANEO vendor binder, or any tier with
 *                           no proven full-kernel write) it is the STOCK
 *                           scaling_max_freq the vendor set — the overlay clamps
 *                           / rejects anything above it, so the engine must never
 *                           target above this (targeting above is what crashed
 *                           `com.ayaneo.gamewindow` via the rejected-write storm).
 *                           Sourced honestly from the device, never hardcoded.
 *
 * [gpuMinLevel]         — Adreno max_pwrlevel lower bound (fastest level
 *                         supported). Lower index = higher GPU performance.
 *                         Null when no Adreno GPU / no power-level control.
 *
 * [gpuMaxLevel]         — Adreno max_pwrlevel upper bound (slowest level we
 *                         allow). The engine can write any level in
 *                         [gpuMinLevel .. gpuMaxLevel].
 *
 * [minOnlineCores]      — Absolute floor on total online cores. The engine
 *                         will not park a core if doing so would put total
 *                         online count below this floor. Minimum is 1 (cpu0
 *                         is always online, but we set a practical floor
 *                         based on cluster count).
 *
 * ── Wave 2 envelope ──────────────────────────────────────────────────────────
 * [gpuDevfreqFloorHz]    — Lowest GPU devfreq frequency the engine may write to
 *                          min_freq/max_freq, in Hz. Read from the probed OPP
 *                          table (NOT hardcoded). Null when no devfreq table is
 *                          discoverable — the engine then skips the GPU_DEVFREQ
 *                          lever and falls back to the pwrlevel floor.
 * [gpuDevfreqCeilHz]     — Highest GPU devfreq frequency, in Hz. The engine
 *                          never writes a min/max outside [floor, ceil].
 * [gpuDevfreqStepsHz]    — The discrete devfreq OPP steps in Hz, ascending. The
 *                          GPU_DEVFREQ lever moves between these; empty when the
 *                          table is unknown.
 * [gpuRootPath]          — The GPU sysfs root (e.g. /sys/class/kgsl/kgsl-3d0).
 *                          Null when no GPU was probed. The engine carries it so
 *                          the devfreq lever can be honestly skipped when absent.
 * [uclampAvailable]      — True when the kernel exposes /dev/cpuctl uclamp on the
 *                          top-app slice (probed). The UCLAMP lever is only ever
 *                          selected when this is true; otherwise the engine
 *                          degrades to parking (honesty: never fake a node).
 * [fanModeAvailable]     — True when a vendor fan_mode preset key is controllable
 *                          on this device. The fan governor only actuates when
 *                          this is true.
 */
data class TdpCaps(
    val primeCoreIndices: List<Int>,
    val bigPolicyId: Int,
    val bigClusterOppStepsKhz: List<Int>,
    /**
     * The highest big-policy OPP actually WRITABLE on this device's effective
     * write path (see the class KDoc). Equals the kernel OPP top on
     * fully-kernel-writable devices (root / PServer / chmod-direct); equals the
     * stock scaling_max_freq on constrained vendor paths (AYANEO binder, etc.).
     * [bigClusterOppStepsKhz] is clamped to this value. Defaulted (0 → "no
     * clamp known") so existing test fixtures that build TdpCaps directly keep
     * compiling; [from] always sets a real value.
     */
    val bigClusterWritableMaxKhz: Int = 0,
    val gpuMinLevel: Int?,
    val gpuMaxLevel: Int?,
    val minOnlineCores: Int,
    /** Total number of online CPU cores the CapabilityReport reports. Used to
     *  compute how many we can safely park before hitting [minOnlineCores]. */
    val totalOnlineCores: Int,
    // ── Wave 2 envelope (defaulted so existing test fixtures keep compiling) ────
    val gpuDevfreqFloorHz: Long? = null,
    val gpuDevfreqCeilHz: Long? = null,
    val gpuDevfreqStepsHz: List<Long> = emptyList(),
    val gpuRootPath: String? = null,
    val uclampAvailable: Boolean = false,
    val fanModeAvailable: Boolean = false,
) {
    companion object {

        /**
         * Derives [TdpCaps] from a live [CapabilityReport].
         *
         * Prime-core detection mirrors [PresetGenerator]'s cluster-tier logic exactly:
         *   prime  = policy whose top availableFreqsKhz is the largest on the chip
         *   little = policy whose top availableFreqsKhz is the smallest
         *   big/gold = everything else (or the single non-little policy on 2-cluster
         *              devices when little == prime are the same policy)
         *
         * On a 3-cluster layout (little / gold / prime), the prime policy's cores
         * are the parking targets; the gold policy is the big-cluster cap target.
         * On a 2-cluster layout, the single high-OPP policy serves both roles.
         *
         * cpu0 is unconditionally excluded from [primeCoreIndices] regardless of
         * which policy it belongs to.
         */
        fun from(report: CapabilityReport): TdpCaps {
            val policies = report.cpuPolicies

            // ── Cluster classification ─────────────────────────────────────────
            // Find the top OPP of each policy (same approach as PresetGenerator).
            val policyTopOpp = policies.associateWith { p ->
                p.availableFreqsKhz.maxOrNull() ?: 0
            }

            val maxTopOpp = policyTopOpp.values.maxOrNull() ?: 0
            val minTopOpp = policyTopOpp.values.minOrNull() ?: 0

            // Prime = the policy(ies) with the highest top OPP.
            val primePolicies = policies.filter { policyTopOpp[it] == maxTopOpp }
            // Little = the policy(ies) with the lowest top OPP.
            // (On a 1-cluster device, little == prime — handled gracefully below.)

            // Collect prime core indices, NEVER including cpu0.
            val primeCoreIndices = primePolicies
                .flatMap { it.onlineCores }
                .filter { it != 0 }
                .sorted()

            // ── Big-cluster policy selection ───────────────────────────────────
            // On 3+ cluster devices: the policy whose top OPP is second-largest
            // (gold/big) is the capping target. On 2-cluster devices: the single
            // high-OPP policy IS both prime and the cap target.
            val bigPolicy = if (policies.size <= 2) {
                // 2-cluster (or 1-cluster): cap the highest-OPP policy.
                primePolicies.firstOrNull() ?: policies.firstOrNull()
            } else {
                // 3+ clusters: sort descending by top OPP, skip prime, take next.
                policies
                    .sortedByDescending { policyTopOpp[it] ?: 0 }
                    .firstOrNull { policyTopOpp[it] != maxTopOpp }
                    ?: primePolicies.firstOrNull()
                    ?: policies.firstOrNull()
            }

            val bigPolicyId = bigPolicy?.policyId ?: 0
            val bigClusterOppFull = bigPolicy?.availableFreqsKhz?.sorted() ?: emptyList()

            // ── Effective WRITABLE ceiling (the AYANEO crash fix) ──────────────
            // The FULL kernel OPP table top is NOT necessarily writable. Some
            // vendor write paths (AYANEO's `gamewindow` overlay) clamp/reject any
            // scaling_max_freq above the STOCK ceiling the vendor set; targeting
            // above it never moves the node AND the rejected-write storm crashes
            // the vendor app. So the cap-target step table must top out at the
            // highest OPP this device will actually ACCEPT.
            //
            // A cluster's max node is fully kernel-writable — writable ceiling ==
            // kernel OPP top, so NO clamp — exactly when we hold a proven
            // full-kernel sysfs write path:
            //   • ROOT (Magisk / KernelSU), or
            //   • PServer root runner live (Odin 3 / RP6 — write anything), or
            //   • sysfs chmod-666 direct (unlock script grants app-UID writes).
            // On any of those, Odin/RP6 stay UNREGRESSED: they still reach max.
            //
            // Otherwise (AYANEO vendor binder, vendor-settings, NoopWriter, or any
            // tier without a proven full-kernel write) the honest writable ceiling
            // is the STOCK scaling_max_freq (bigPolicy.currentMaxKhz) — the vendor
            // overlay accepts nothing above it. Sourced from the device, never
            // hardcoded.
            val fullKernelWritable =
                report.privilege == io.github.mayusi.calibratesoc.data.capability.PrivilegeTier.ROOT ||
                    report.pserverSysfsLive ||
                    report.sysfsDirectlyWritable

            // ── Is the CPU-freq CAP-DOWN lever itself writable? (the SHIP-BLOCKER) ──
            // Distinct from "is there ANY live write path". The AYANEO vendor overlay
            // (com.ayaneo.gamewindow) ACCEPTS a scaling_max_freq write ONLY at the stock
            // ceiling (a no-op); ANY sub-stock value is REJECTED (readback snaps back to
            // stock — live-proven on the AYANEO Pocket DS). So on that path the CPU cap
            // can never step DOWN: it is a DEAD lever for power reduction. If we keep a
            // full sub-stock OPP step table there, `stepCapDown` always finds a lower
            // index → reports changed=true → `applyTightenLever` returns on Lever.CAP
            // FIRST every tick and NEVER falls through to the GPU lever that DOES actuate
            // on AYANEO (AyaneoVendorWriter → com_set_performance_gpu → kgsl-3d0 devfreq,
            // readable+verified). Net: nothing actuates.
            //
            // Model this HONESTLY as a capability of the cap lever, NOT as a blunt
            // `!fullKernelWritable`. They only COINCIDE today because AYANEO is the sole
            // constrained live tier and its CPU cap happens to be a no-op:
            //   • fully-kernel-writable (ROOT / PServer live / chmod-direct) → the cap
            //     writes a real sub-stock range → cap-down is a LIVE lever → KEEP the full
            //     writable table (Odin 3 / RP6 / ROOT step CPU down normally — UNREGRESSED).
            //   • AYANEO vendor-binder live with NO full-kernel path → the cap can only be
            //     written at stock → cap-down is a NO-OP → DEAD lever.
            // FUTURE: if a constrained device ever exposes a REAL writable sub-stock CPU
            // range through its vendor path, flip this to `true` for that path (e.g. gate on
            // a probed "vendor accepts sub-stock scaling_max" signal) so it keeps its cap
            // granularity. Do NOT let the AYANEO coincidence harden into an unlabeled
            // `!fullKernelWritable` — the concept is "can the CPU cap step down", not
            // "is the kernel writable".
            val cpuCapLeverWritable = when {
                fullKernelWritable -> true
                // Known dead-CPU-cap path: AYANEO vendor binder is the live tier but its
                // scaling_max_freq write is a stock-only no-op.
                report.ayaneoBinderLive -> false
                // Any other tier: default to writable (unchanged behaviour for every
                // non-AYANEO device — they keep their full step table exactly as before).
                else -> true
            }

            val kernelTopKhz = bigClusterOppFull.lastOrNull() ?: 0
            // The stock ceiling the vendor set for the big policy (scaling_max_freq).
            // Fall back to the kernel top when it's missing/absurd so we never
            // under-clamp to zero and strand the engine at the bottom OPP.
            val stockCeilingKhz = (bigPolicy?.currentMaxKhz ?: 0)
                .takeIf { it > 0 }
                ?: kernelTopKhz

            val writableMaxKhz = when {
                bigClusterOppFull.isEmpty() -> 0
                fullKernelWritable -> kernelTopKhz
                // Constrained path: clamp to the stock ceiling, but never above the
                // real kernel top (a bogus stock read can't invent OPPs).
                else -> minOf(stockCeilingKhz, kernelTopKhz)
            }

            // Clamp the CAP-TARGET step table to the writable ceiling. Every
            // downstream consumer (stepCapDown's steps.lastIndex, the 40% hard
            // floor off steps.last(), enforceInvariants, the seed, the budget
            // fraction) derives its top-of-range from THIS list, so clamping here
            // makes the entire engine writable-bounded at one point — no tick can
            // ever target above what the device accepts. Guarantee ≥1 step: if the
            // ceiling somehow lands below the lowest OPP, keep the single lowest
            // step so the controller still has a valid (degenerate) table.
            val bigClusterOppClamped = if (writableMaxKhz <= 0) {
                bigClusterOppFull
            } else {
                bigClusterOppFull.filter { it <= writableMaxKhz }
                    .ifEmpty { bigClusterOppFull.take(1) }
            }

            // ── DEAD-CPU-CAP COLLAPSE (the SHIP-BLOCKER fix) ───────────────────────
            // When CPU cap-DOWN is a NO-OP lever on this write path ([cpuCapLeverWritable]
            // == false, i.e. the AYANEO vendor-binder path today), collapse the cap-target
            // step table to a SINGLE entry at the STOCK ceiling. With one step,
            // `stepCapDown` finds no lower index → returns changed=false →
            // `applyTightenLever` does NOT return on Lever.CAP and its EXISTING loop falls
            // through SAME-TICK to the next lever (GPU_DEVFREQ etc.) that DOES actuate on
            // AYANEO. No engine or write-loop change: the lever fallthrough already does
            // the work. The single value is the STOCK ceiling — a real reported OPP and a
            // harmless no-op write — so enforceInvariants / OPP-snap / the 40% hard floor /
            // MM-1 / MM-2 all still behave (cap stays == stock = no CPU-power contribution,
            // exactly what we want; the GPU lever drives). ≥1-step guarantee preserved.
            // NOTE: fully-kernel-writable devices (ROOT / PServer / chmod) have
            // cpuCapLeverWritable == true → they KEEP the full clamped table and step CPU
            // down normally (Odin 3 / RP6 UNREGRESSED).
            val bigClusterOppSteps = if (!cpuCapLeverWritable && bigClusterOppClamped.isNotEmpty()) {
                // stockCeilingKhz is a real OPP the device reports; snap to the highest
                // retained step ≤ it (the clamp already tops out at the writable ceiling,
                // so this is normally the clamped table's own last entry) to guarantee the
                // collapsed value is a genuine member of the OPP table for MM-2/OPP-snap.
                val stockStep = bigClusterOppClamped.lastOrNull { it <= stockCeilingKhz }
                    ?: bigClusterOppClamped.last()
                listOf(stockStep)
            } else {
                bigClusterOppClamped
            }

            // ── GPU power levels ───────────────────────────────────────────────
            // Adreno: lower index = higher performance (0 = max perf).
            val adrenoExtras = report.adrenoExtras
            val gpuMinLevel = adrenoExtras?.currentMinPwrLevel
            val gpuMaxLevel = adrenoExtras?.currentMaxPwrLevel

            // ── GPU devfreq envelope (Wave 2) ──────────────────────────────────
            // The devfreq min/max lever is finer than the 0-7 pwrlevel. We read the
            // bounds from the PROBED OPP table — never hardcoded — so a device that
            // genuinely lacks devfreq simply yields nulls and the engine skips the
            // lever (honesty). On the AYN Odin 3 this is 160 MHz..1100 MHz.
            val gpu = report.gpu
            val gpuDevfreqSteps: List<Long> = gpu?.availableFreqsHz?.sorted()?.distinct().orEmpty()
            // Prefer the OPP table bounds; fall back to the probed current min/max
            // when the table is sparse (some firmwares expose only min_freq/max_freq).
            val gpuDevfreqFloor: Long? = when {
                gpuDevfreqSteps.isNotEmpty() -> gpuDevfreqSteps.first()
                gpu != null && gpu.currentMinHz > 0L -> gpu.currentMinHz
                else -> null
            }
            val gpuDevfreqCeil: Long? = when {
                gpuDevfreqSteps.isNotEmpty() -> gpuDevfreqSteps.last()
                gpu != null && gpu.currentMaxHz > 0L -> gpu.currentMaxHz
                else -> null
            }
            // Only expose a usable devfreq envelope when floor < ceil (a real range).
            val devfreqUsable = gpuDevfreqFloor != null && gpuDevfreqCeil != null &&
                gpuDevfreqFloor < gpuDevfreqCeil
            val gpuRootPath = gpu?.rootPath

            // ── uclamp availability (Wave 2) ───────────────────────────────────
            // The top-app uclamp.min perf-hint is only a real lever when the kernel
            // exposes /dev/cpuctl uclamp (probed via schedBoostInterface == UCLAMP).
            val uclampAvailable =
                report.schedBoostInterface == io.github.mayusi.calibratesoc.data.capability.SchedBoostInterface.UCLAMP

            // ── fan_mode availability (Wave 2) ─────────────────────────────────
            // The AYN/Retroid fan presets ride a Settings.System key the vendor app
            // owns. We can control it when a vendor perf companion is present OR a
            // vendor-settings fan source was probed.
            val fanModeAvailable = report.vendorApps.anyVendorPerfApp ||
                report.fan?.source == io.github.mayusi.calibratesoc.data.capability.FanSource.VENDOR_SETTINGS_KEY

            // ── Online core count & min-online floor ──────────────────────────
            val totalOnlineCores = policies.sumOf { it.onlineCores.size }

            // Safety floor: keep at least half the cores online, minimum 2.
            // On a 2-core device (exotic) we still keep 1 core beyond cpu0.
            val minOnlineCores = maxOf(2, totalOnlineCores / 2)

            // The reported writable ceiling: the top of the clamped step table on a
            // real table, else the raw writable ceiling (0 when no table at all).
            val reportedWritableMaxKhz = bigClusterOppSteps.lastOrNull() ?: writableMaxKhz

            return TdpCaps(
                primeCoreIndices = primeCoreIndices,
                bigPolicyId = bigPolicyId,
                bigClusterOppStepsKhz = bigClusterOppSteps,
                bigClusterWritableMaxKhz = reportedWritableMaxKhz,
                gpuMinLevel = gpuMinLevel,
                gpuMaxLevel = gpuMaxLevel,
                minOnlineCores = minOnlineCores,
                totalOnlineCores = totalOnlineCores,
                gpuDevfreqFloorHz = if (devfreqUsable) gpuDevfreqFloor else null,
                gpuDevfreqCeilHz = if (devfreqUsable) gpuDevfreqCeil else null,
                gpuDevfreqStepsHz = if (devfreqUsable) gpuDevfreqSteps else emptyList(),
                gpuRootPath = gpuRootPath,
                uclampAvailable = uclampAvailable,
                fanModeAvailable = fanModeAvailable,
            )
        }
    }
}
