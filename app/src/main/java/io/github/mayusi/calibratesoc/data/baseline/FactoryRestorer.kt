package io.github.mayusi.calibratesoc.data.baseline

import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.github.mayusi.calibratesoc.data.tunables.writer.WriterRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replays a [FactoryBaseline] back to the device. Each tunable in the
 * baseline gets written through the appropriate writer for the
 * current privilege tier — so a baseline restore on a NONE-tier
 * device still attempts the Settings.System keys (which may succeed
 * if WRITE_SECURE_SETTINGS is granted) while skipping the sysfs ones
 * with a CapabilityDenied result the user can read in the report.
 *
 * Returns a summary the UI can render — "30 OK, 6 skipped (need root)"
 * lets the user judge whether the restore actually achieved what they
 * wanted.
 */
@Singleton
class FactoryRestorer @Inject constructor(
    private val writerRegistry: WriterRegistry,
) {
    suspend fun restore(baseline: FactoryBaseline, report: CapabilityReport): RestoreSummary {
        var ok = 0
        var denied = 0
        var failed = 0
        val errors = mutableListOf<String>()
        for (entry in baseline.tunables) {
            val previous = entry.previousValue ?: continue
            val writer = writerRegistry.writerFor(entry.id, report)
            when (val res = writer.write(entry.id, previous)) {
                is WriteResult.Success -> ok++
                is WriteResult.CapabilityDenied -> denied++
                is WriteResult.Rejected -> {
                    failed++
                    errors += "${entry.id.target}: ${res.message}"
                }
                is WriteResult.Failed -> {
                    failed++
                    errors += "${entry.id.target}: ${res.error.message ?: "failed"}"
                }
            }
        }
        return RestoreSummary(
            total = baseline.tunables.size,
            ok = ok,
            denied = denied,
            failed = failed,
            errors = errors,
        )
    }

    data class RestoreSummary(
        val total: Int,
        val ok: Int,
        val denied: Int,
        val failed: Int,
        val errors: List<String>,
    )
}
