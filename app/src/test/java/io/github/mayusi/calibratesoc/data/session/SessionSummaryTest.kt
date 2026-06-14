package io.github.mayusi.calibratesoc.data.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure JVM tests for [computeSessionSummary]. No Android dependencies —
 * all inputs are Kotlin data classes; all assertions use Truth.
 */
class SessionSummaryTest {

    // --- helpers -------------------------------------------------------

    private fun sample(
        elapsedMs: Long = 0L,
        fps: Float? = null,
        cpuMaxMhz: Int = 3000,
        gpuMhz: Int? = 800,
        cpuTempC: Float? = 60f,
        gpuTempC: Float? = 55f,
        batteryW: Double? = 10.0,
        cpuLoadPct: Int = 50,
    ) = SessionSample(
        elapsedMs = elapsedMs,
        fps = fps,
        cpuMaxMhz = cpuMaxMhz,
        gpuMhz = gpuMhz,
        cpuTempC = cpuTempC,
        gpuTempC = gpuTempC,
        batteryW = batteryW,
        cpuLoadPct = cpuLoadPct,
    )

    // --- empty samples -------------------------------------------------

    @Test
    fun `empty samples returns null for all nullable fields, zero events`() {
        val s = computeSessionSummary(emptyList())
        assertThat(s.avgFps).isNull()
        assertThat(s.p1LowFps).isNull()
        assertThat(s.minFps).isNull()
        assertThat(s.peakCpuTempC).isNull()
        assertThat(s.peakGpuTempC).isNull()
        assertThat(s.avgWatts).isNull()
        assertThat(s.fpsDipEvents).isEqualTo(0)
    }

    // --- avgFps --------------------------------------------------------

    @Test
    fun `avgFps ignores null fps samples`() {
        val samples = listOf(
            sample(fps = null),
            sample(fps = 60f),
            sample(fps = null),
            sample(fps = 30f),
        )
        val s = computeSessionSummary(samples)
        // (60 + 30) / 2 = 45
        assertThat(s.avgFps).isWithin(0.1f).of(45f)
    }

    @Test
    fun `avgFps is null when all samples have null fps`() {
        val samples = listOf(sample(fps = null), sample(fps = null))
        val s = computeSessionSummary(samples)
        assertThat(s.avgFps).isNull()
    }

    @Test
    fun `avgFps correct for uniform fps`() {
        val samples = (1..10).map { sample(fps = 60f) }
        val s = computeSessionSummary(samples)
        assertThat(s.avgFps).isWithin(0.01f).of(60f)
    }

    // --- minFps --------------------------------------------------------

    @Test
    fun `minFps returns the lowest fps in the samples`() {
        val samples = listOf(
            sample(fps = 60f),
            sample(fps = 20f),
            sample(fps = 45f),
        )
        assertThat(computeSessionSummary(samples).minFps).isWithin(0.1f).of(20f)
    }

    @Test
    fun `minFps is null when no fps samples exist`() {
        assertThat(computeSessionSummary(listOf(sample(fps = null))).minFps).isNull()
    }

    // --- p1LowFps ------------------------------------------------------

    @Test
    fun `p1LowFps is null when fewer than 100 fps samples`() {
        val samples = (1..50).map { sample(fps = 60f) }
        val s = computeSessionSummary(samples)
        assertThat(s.p1LowFps).isNull()
    }

    @Test
    fun `p1LowFps computed for 100 samples with one obvious low outlier`() {
        // 99 samples at 60 fps + 1 at 1 fps = 100 total. 1% of 100 = 1 sample.
        val samples = listOf(sample(fps = 1f)) + (1..99).map { sample(fps = 60f) }
        val s = computeSessionSummary(samples)
        // p1 = avg of bottom 1 % (= 1 sample) = 1 fps
        assertThat(s.p1LowFps).isWithin(0.5f).of(1f)
    }

    // --- peakCpuTempC / peakGpuTempC -----------------------------------

    @Test
    fun `peakCpuTempC picks the hottest sample`() {
        val samples = listOf(
            sample(cpuTempC = 60f),
            sample(cpuTempC = 85f),
            sample(cpuTempC = 72f),
        )
        assertThat(computeSessionSummary(samples).peakCpuTempC).isWithin(0.1f).of(85f)
    }

    @Test
    fun `peakCpuTempC is null when all cpuTempC are null`() {
        val samples = listOf(sample(cpuTempC = null), sample(cpuTempC = null))
        assertThat(computeSessionSummary(samples).peakCpuTempC).isNull()
    }

    @Test
    fun `peakGpuTempC picks the hottest gpu sample`() {
        val samples = listOf(
            sample(gpuTempC = 55f),
            sample(gpuTempC = 90f),
            sample(gpuTempC = 70f),
        )
        assertThat(computeSessionSummary(samples).peakGpuTempC).isWithin(0.1f).of(90f)
    }

    // --- avgWatts ------------------------------------------------------

    @Test
    fun `avgWatts is average of non-null batteryW samples`() {
        val samples = listOf(
            sample(batteryW = 10.0),
            sample(batteryW = null),
            sample(batteryW = 20.0),
        )
        val s = computeSessionSummary(samples)
        // (10 + 20) / 2 = 15
        assertThat(s.avgWatts!!).isWithin(0.01).of(15.0)
    }

    @Test
    fun `avgWatts is null when all batteryW are null`() {
        val samples = listOf(sample(batteryW = null))
        assertThat(computeSessionSummary(samples).avgWatts).isNull()
    }

    // --- fpsDipEvents --------------------------------------------------

    @Test
    fun `fpsDipEvents zero when fps is stable`() {
        val samples = (1..20).map { sample(fps = 60f) }
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(0)
    }

    @Test
    fun `fpsDipEvents counts one event for a sustained dip then recovery`() {
        // avg = 60. 80% of 60 = 48. Anything below 48 is a dip.
        // Three consecutive dip samples → 1 event.
        val samples = listOf(
            sample(fps = 60f), sample(fps = 60f),
            sample(fps = 10f), sample(fps = 10f), sample(fps = 10f), // 1 event
            sample(fps = 60f), sample(fps = 60f),
        )
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(1)
    }

    @Test
    fun `fpsDipEvents counts multiple distinct dip events`() {
        val samples = listOf(
            sample(fps = 60f),
            sample(fps = 10f), // event 1
            sample(fps = 60f),
            sample(fps = 10f), // event 2
            sample(fps = 60f),
        )
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(2)
    }

    @Test
    fun `fpsDipEvents is zero when all fps are null`() {
        val samples = listOf(sample(fps = null), sample(fps = null))
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(0)
    }

    @Test
    fun `fpsDipEvents fires on OR condition - below avg threshold but above absolute floor`() {
        // avg = 100 fps. dipThreshold = 80 fps (100 * 0.80). absoluteFloor = 40.
        // A sample at 70 fps is < 80 (dipThreshold) but > 40 (absoluteFloor).
        // With the old AND/minOf logic this would NOT count as a dip because
        // minOf(80, 40) = 40 and 70 > 40. With the fixed OR logic it DOES count.
        val samples = listOf(
            sample(fps = 100f), sample(fps = 100f), sample(fps = 100f), sample(fps = 100f),
            sample(fps = 70f),  // dip: < 80 (avg-threshold) but > 40 (floor) → event 1
            sample(fps = 100f), sample(fps = 100f),
        )
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(1)
    }

    @Test
    fun `fpsDipEvents fires on OR condition - below absolute floor but above avg threshold`() {
        // avg = 30 fps. dipThreshold = 24 fps (30 * 0.80). absoluteFloor = 40.
        // A sample at 35 fps is > 24 (dipThreshold) but < 40 (absoluteFloor).
        // With the old AND/minOf logic this would NOT count (minOf(24,40)=24; 35>24).
        // With the fixed OR logic it counts because 35 < 40.
        val samples = listOf(
            sample(fps = 30f), sample(fps = 30f), sample(fps = 30f),
            sample(fps = 35f),  // dip: < 40 (absolute floor) → event 1
            sample(fps = 30f),
        )
        assertThat(computeSessionSummary(samples).fpsDipEvents).isEqualTo(1)
    }

    // --- p1LowFps rounding (FIX 4) -------------------------------------

    @Test
    fun `p1LowFps uses rounded count not floored - boundary at 150 samples`() {
        // 150 * 0.01 = 1.5 → floor gives 1, round gives 2.
        // Bottom 2 samples (fps 1 + fps 2) average = 1.5 fps with rounding.
        // Bottom 1 sample average = 1.0 fps with floor.
        val sorted = listOf(1f, 2f) + (1..148).map { 60f }
        val samples = sorted.map { sample(fps = it) }
        val s = computeSessionSummary(samples)
        // With roundToInt: p1Count=2, p1Low = avg(1,2) = 1.5
        assertThat(s.p1LowFps!!).isWithin(0.1f).of(1.5f)
    }

    // --- prune-to-10 logic (pure) --------------------------------------
    //
    // The actual pruning runs in Room (SessionDao.pruneToTen) and is
    // tested at the DAO level; here we verify the contract indirectly:
    // that a list of more than 10 sessions can be correctly pruned
    // to the 10 newest by startedAtMs.

    @Test
    fun `selecting newest 10 from 15 sessions leaves the 10 most recent`() {
        val sessions = (1..15).map { i ->
            GameSession(
                id = i.toLong(),
                startedAtMs = i.toLong() * 1_000L,
                durationMs = 60_000L,
                appLabel = null,
                profileName = null,
                samples = emptyList(),
                summary = computeSessionSummary(emptyList()),
                fpsAvailableDuringSampling = false,
            )
        }
        val pruned = sessions.sortedByDescending { it.startedAtMs }.take(10)
        assertThat(pruned).hasSize(10)
        // The 10 newest: sessions 6..15 (startedAtMs 6000..15000)
        assertThat(pruned.map { it.id }).containsExactlyElementsIn(6L..15L)
    }
}
