package io.github.mayusi.calibratesoc.data.scorelog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ExternalScoreEntity] — pure data class, no Android or DB context.
 *
 * These tests validate the data model constraints and that the entity fields
 * can hold expected score ranges for popular benchmark apps.
 */
class ExternalScoreEntityTest {

    private fun makeScore(
        benchmarkName: String = "3DMark",
        packageName: String? = "com.futuremark.dmandroid.application",
        scoreValue: Double = 12345.0,
        scoreLabel: String = "Wild Life score",
        deviceName: String? = "Odin 2 Max",
        notedAtMs: Long = 1_700_000_000_000L,
        note: String? = null,
    ) = ExternalScoreEntity(
        benchmarkName = benchmarkName,
        packageName = packageName,
        scoreValue = scoreValue,
        scoreLabel = scoreLabel,
        deviceName = deviceName,
        notedAtMs = notedAtMs,
        note = note,
    )

    @Test
    fun `entity has auto-generated zero id by default`() {
        val entity = makeScore()
        assertThat(entity.id).isEqualTo(0L)
    }

    @Test
    fun `benchmark name is stored correctly`() {
        val entity = makeScore(benchmarkName = "AnTuTu Benchmark")
        assertThat(entity.benchmarkName).isEqualTo("AnTuTu Benchmark")
    }

    @Test
    fun `score value can hold large AnTuTu-range number`() {
        // AnTuTu scores can exceed 1 million
        val entity = makeScore(scoreValue = 1_500_000.0)
        assertThat(entity.scoreValue).isEqualTo(1_500_000.0)
    }

    @Test
    fun `score value can hold fractional fps value`() {
        val entity = makeScore(scoreValue = 59.7)
        assertThat(entity.scoreValue).isWithin(0.01).of(59.7)
    }

    @Test
    fun `package name is nullable`() {
        val entity = makeScore(packageName = null)
        assertThat(entity.packageName).isNull()
    }

    @Test
    fun `device name is nullable`() {
        val entity = makeScore(deviceName = null)
        assertThat(entity.deviceName).isNull()
    }

    @Test
    fun `note is nullable`() {
        val entity = makeScore(note = null)
        assertThat(entity.note).isNull()
    }

    @Test
    fun `note is stored correctly when provided`() {
        val entity = makeScore(note = "After tuning governor to schedutil")
        assertThat(entity.note).isEqualTo("After tuning governor to schedutil")
    }

    @Test
    fun `timestamp is stored correctly`() {
        val ts = System.currentTimeMillis()
        val entity = makeScore(notedAtMs = ts)
        assertThat(entity.notedAtMs).isEqualTo(ts)
    }

    @Test
    fun `score label is stored correctly`() {
        val entity = makeScore(scoreLabel = "Single-core score")
        assertThat(entity.scoreLabel).isEqualTo("Single-core score")
    }

    @Test
    fun `equality works on data class`() {
        val a = makeScore()
        val b = makeScore()
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `two scores with different values are not equal`() {
        val a = makeScore(scoreValue = 1000.0)
        val b = makeScore(scoreValue = 2000.0)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `score value zero is valid (edge case)`() {
        // Shouldn't normally happen but the entity shouldn't reject it
        val entity = makeScore(scoreValue = 0.0)
        assertThat(entity.scoreValue).isEqualTo(0.0)
    }

    @Test
    fun `Geekbench multi-core score range is representable`() {
        // Geekbench 6 multi-core scores on flagship chips are ~3000-7000
        val entity = makeScore(
            benchmarkName = "Geekbench 6",
            scoreValue = 5200.0,
            scoreLabel = "Multi-core score",
        )
        assertThat(entity.scoreValue).isWithin(1.0).of(5200.0)
    }

    @Test
    fun `custom free-text benchmark name is supported (no registry binding)`() {
        val entity = makeScore(
            benchmarkName = "Speedometer 3",
            packageName = null, // not in registry
            scoreValue = 18.5,
            scoreLabel = "runs/min",
        )
        assertThat(entity.benchmarkName).isEqualTo("Speedometer 3")
        assertThat(entity.packageName).isNull()
    }
}
