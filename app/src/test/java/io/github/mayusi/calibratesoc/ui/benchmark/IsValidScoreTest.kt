package io.github.mayusi.calibratesoc.ui.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [isValidScore] — the score-input guard added for FIX 5.
 *
 * Pure JVM — no Android dependency.
 */
class IsValidScoreTest {

    @Test
    fun `null is invalid`() {
        assertThat(isValidScore(null)).isFalse()
    }

    @Test
    fun `positive infinity is invalid`() {
        assertThat(isValidScore(Double.POSITIVE_INFINITY)).isFalse()
    }

    @Test
    fun `negative infinity is invalid`() {
        assertThat(isValidScore(Double.NEGATIVE_INFINITY)).isFalse()
    }

    @Test
    fun `NaN is invalid`() {
        assertThat(isValidScore(Double.NaN)).isFalse()
    }

    @Test
    fun `zero is invalid`() {
        assertThat(isValidScore(0.0)).isFalse()
    }

    @Test
    fun `negative value is invalid`() {
        assertThat(isValidScore(-1.0)).isFalse()
    }

    @Test
    fun `value at upper bound 1e10 is invalid`() {
        assertThat(isValidScore(1e10)).isFalse()
    }

    @Test
    fun `value above upper bound is invalid`() {
        assertThat(isValidScore(1e11)).isFalse()
    }

    @Test
    fun `absurd large value is invalid`() {
        assertThat(isValidScore(1e308)).isFalse()
    }

    @Test
    fun `typical score is valid`() {
        assertThat(isValidScore(12345.0)).isTrue()
    }

    @Test
    fun `minimum positive value above zero is valid`() {
        assertThat(isValidScore(0.001)).isTrue()
    }

    @Test
    fun `value just below upper bound is valid`() {
        assertThat(isValidScore(9_999_999_999.0)).isTrue()
    }

    @Test
    fun `large but in-range score is valid`() {
        assertThat(isValidScore(9_000_000.0)).isTrue()
    }
}
