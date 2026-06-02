package io.github.mayusi.calibratesoc.data.benchmark

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import kotlinx.serialization.Serializable

/**
 * Frozen device state at the moment a benchmark run started. Persisted
 * with the run record so a later "compare two runs" view can show what
 * was actually different between them (preset, governors, freq caps).
 *
 * We capture only the fields that meaningfully affect performance —
 * board name + kernel version + privilege tier + per-policy caps and
 * governors + GPU freq cap. Anything else (thermal-zone names,
 * vendor app presence) is irrelevant to the comparison.
 */
@Serializable
data class SystemSnapshot(
    val capturedAtMs: Long,
    val deviceModel: String,
    val socModel: String,
    val androidVersion: String,
    val privilegeTier: String,
    val cpuPolicies: List<PolicySnapshot>,
    val gpuMinHz: Long?,
    val gpuMaxHz: Long?,
    val gpuGovernor: String?,
    val appVersion: String,
) {
    @Serializable
    data class PolicySnapshot(
        val policyId: Int,
        val minKhz: Int,
        val maxKhz: Int,
        val governor: String,
    )

    companion object {
        fun fromReport(report: CapabilityReport, appVersion: String): SystemSnapshot =
            SystemSnapshot(
                capturedAtMs = System.currentTimeMillis(),
                deviceModel = "${report.device.manufacturer} ${report.device.model}".trim(),
                socModel = "${report.soc.socManufacturer} ${report.soc.socModel}".trim(),
                androidVersion = "${report.device.androidVersion} (API ${report.device.sdkInt})",
                privilegeTier = report.privilege.name,
                cpuPolicies = report.cpuPolicies.map { p ->
                    PolicySnapshot(
                        policyId = p.policyId,
                        minKhz = p.currentMinKhz,
                        maxKhz = p.currentMaxKhz,
                        governor = p.currentGovernor,
                    )
                },
                gpuMinHz = report.gpu?.currentMinHz,
                gpuMaxHz = report.gpu?.currentMaxHz,
                gpuGovernor = report.gpu?.currentGovernor,
                appVersion = appVersion,
            )
    }
}
