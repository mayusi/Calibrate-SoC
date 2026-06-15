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
 *                           ascending. The engine steps through these when
 *                           clamping or relaxing the cap.
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
 */
data class TdpCaps(
    val primeCoreIndices: List<Int>,
    val bigPolicyId: Int,
    val bigClusterOppStepsKhz: List<Int>,
    val gpuMinLevel: Int?,
    val gpuMaxLevel: Int?,
    val minOnlineCores: Int,
    /** Total number of online CPU cores the CapabilityReport reports. Used to
     *  compute how many we can safely park before hitting [minOnlineCores]. */
    val totalOnlineCores: Int,
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
            val bigClusterOppSteps = bigPolicy?.availableFreqsKhz?.sorted() ?: emptyList()

            // ── GPU power levels ───────────────────────────────────────────────
            // Adreno: lower index = higher performance (0 = max perf).
            val adrenoExtras = report.adrenoExtras
            val gpuMinLevel = adrenoExtras?.currentMinPwrLevel
            val gpuMaxLevel = adrenoExtras?.currentMaxPwrLevel

            // ── Online core count & min-online floor ──────────────────────────
            val totalOnlineCores = policies.sumOf { it.onlineCores.size }

            // Safety floor: keep at least half the cores online, minimum 2.
            // On a 2-core device (exotic) we still keep 1 core beyond cpu0.
            val minOnlineCores = maxOf(2, totalOnlineCores / 2)

            return TdpCaps(
                primeCoreIndices = primeCoreIndices,
                bigPolicyId = bigPolicyId,
                bigClusterOppStepsKhz = bigClusterOppSteps,
                gpuMinLevel = gpuMinLevel,
                gpuMaxLevel = gpuMaxLevel,
                minOnlineCores = minOnlineCores,
                totalOnlineCores = totalOnlineCores,
            )
        }
    }
}
