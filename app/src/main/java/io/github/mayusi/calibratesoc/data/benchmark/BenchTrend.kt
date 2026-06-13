package io.github.mayusi.calibratesoc.data.benchmark

/**
 * Pure domain logic for benchmark trend analysis.
 *
 * Given a list of [BenchRun]s, produces time-ordered trend series for each
 * metric so the UI can plot how scores evolve across runs of the same flavor.
 *
 * Design choices:
 * - Only COMPLETED runs are included — aborted runs have no meaningful scores.
 * - Scores are NEVER compared across flavors: Quick only has CPU-single;
 *   Standard and Full have the full suite. The caller must pick a single
 *   [BenchFlavor] before calling [compute], and only runs of that flavor
 *   are plotted.
 * - The series is sorted oldest→newest so the chart reads left-to-right.
 * - Null scores for a metric are dropped from that metric's series only.
 */
object BenchTrend {

    /** A single time-stamped data point in a trend series. */
    data class Point(
        val runIndex: Int,          // 0-based index in chronological order (for chart x-axis)
        val startedAtMs: Long,      // wall-clock time (for date labels)
        val score: Long,
        val runName: String,
    )

    /** Summary delta statistics for a metric series. */
    data class DeltaSummary(
        /** Score of the oldest run in this series. */
        val first: Long,
        /** Score of the most recent run in this series. */
        val latest: Long,
        /** The single highest score ever recorded in this series. */
        val best: Long,
        /** Percentage change from first to latest, positive = improvement. */
        val changePercent: Double,
    ) {
        val improved: Boolean get() = changePercent > 0
    }

    /**
     * Trend series for all four metrics, plus flavor metadata.
     *
     * Each series list is independently filtered: a run that scored GPU but
     * not CPU still contributes to [gpuSeries] while absent from [cpuSeries].
     * All series are sorted oldest→newest.
     *
     * [delta] is non-null iff the series has ≥2 points.
     */
    data class TrendResult(
        val flavor: BenchFlavor,
        val overallSeries: List<Point>,
        val cpuSeries: List<Point>,
        val gpuSeries: List<Point>,
        val memorySeries: List<Point>,
        /** Delta for [overallSeries]; null when <2 points. */
        val overallDelta: DeltaSummary?,
        /** Delta for [cpuSeries]; null when <2 points. */
        val cpuDelta: DeltaSummary?,
        /** Delta for [gpuSeries]; null when <2 points. */
        val gpuDelta: DeltaSummary?,
        /** Delta for [memorySeries]; null when <2 points. */
        val memoryDelta: DeltaSummary?,
        /** Total number of completed runs of this flavor (even those missing scores). */
        val totalRuns: Int,
    )

    /**
     * Compute trend series for [flavor] from [runs].
     *
     * @param runs All benchmark runs from the repository (any order is fine;
     *   this function sorts them internally).
     * @param flavor The flavor to filter to. Runs of other flavors are ignored.
     * @return [TrendResult] with all series. Individual series may be empty
     *   if no runs contributed a non-null score for that metric.
     */
    fun compute(runs: List<BenchRun>, flavor: BenchFlavor): TrendResult {
        // Only COMPLETED runs of the requested flavor, sorted oldest→newest.
        val completed = runs
            .filter { it.flavor == flavor && it.outcome == BenchOutcome.COMPLETED }
            .sortedBy { it.startedAtMs }

        val overallSeries = buildSeries(completed) { it.overallScore }
        val cpuSeries     = buildSeries(completed) { BenchScores.from(it).cpu }
        val gpuSeries     = buildSeries(completed) { BenchScores.from(it).gpu }
        val memorySeries  = buildSeries(completed) { BenchScores.from(it).memory }

        return TrendResult(
            flavor         = flavor,
            overallSeries  = overallSeries,
            cpuSeries      = cpuSeries,
            gpuSeries      = gpuSeries,
            memorySeries   = memorySeries,
            overallDelta   = delta(overallSeries),
            cpuDelta       = delta(cpuSeries),
            gpuDelta       = delta(gpuSeries),
            memoryDelta    = delta(memorySeries),
            totalRuns      = completed.size,
        )
    }

    /**
     * Choose the default flavor to show in the trend view: the one with the
     * most completed runs. Tie-breaks in favor of STANDARD, then FULL, then QUICK.
     *
     * Returns null when there are no completed runs at all.
     */
    fun defaultFlavor(runs: List<BenchRun>): BenchFlavor? {
        val priority = listOf(BenchFlavor.STANDARD, BenchFlavor.FULL, BenchFlavor.QUICK)
        return BenchFlavor.values()
            .filter { flavor ->
                runs.any { it.flavor == flavor && it.outcome == BenchOutcome.COMPLETED }
            }
            .maxWithOrNull(
                compareBy<BenchFlavor> { flavor ->
                    runs.count { it.flavor == flavor && it.outcome == BenchOutcome.COMPLETED }
                }.thenBy { priority.indexOf(it).let { idx -> if (idx < 0) Int.MAX_VALUE else -idx } }
            )
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Build a time-ordered [Point] list from [runs] using [scoreOf] to extract
     * the metric. Runs where [scoreOf] returns null are skipped for this series.
     */
    private fun buildSeries(
        runs: List<BenchRun>,
        scoreOf: (BenchRun) -> Long?,
    ): List<Point> {
        var index = 0
        return runs.mapNotNull { run ->
            val score = scoreOf(run) ?: return@mapNotNull null
            Point(
                runIndex    = index++,
                startedAtMs = run.startedAtMs,
                score       = score,
                runName     = run.name,
            )
        }
    }

    /** Returns a [DeltaSummary] if [series] has ≥2 points, else null. */
    private fun delta(series: List<Point>): DeltaSummary? {
        if (series.size < 2) return null
        val first  = series.first().score
        val latest = series.last().score
        val best   = series.maxOf { it.score }
        val changePct = if (first > 0) ((latest - first) * 100.0 / first) else 0.0
        return DeltaSummary(first = first, latest = latest, best = best, changePercent = changePct)
    }
}
