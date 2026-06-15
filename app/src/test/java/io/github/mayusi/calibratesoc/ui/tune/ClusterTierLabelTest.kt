package io.github.mayusi.calibratesoc.ui.tune

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [clusterTierLabel].
 *
 * The helper is a pure function: given a policy's max-freq and the sorted
 * distinct list of all policies' max-freqs, it returns the tier label.
 * These tests cover the three topologies we encounter in practice:
 *   - 1 cluster (edge case: single-policy SoC or read failure)
 *   - 2 clusters (big.LITTLE): efficiency + big
 *   - 3+ clusters (big.LITTLE.prime): efficiency + big + prime
 */
class ClusterTierLabelTest {

    // ─── 1-cluster (degenerate) ─────────────────────────────────────────────────

    @Test
    fun `single policy returns efficiency`() {
        val allMax = listOf(1_800_000)
        assertThat(clusterTierLabel(1_800_000, allMax)).isEqualTo("efficiency")
    }

    // ─── 2-cluster (big.LITTLE) ─────────────────────────────────────────────────

    @Test
    fun `two clusters - lowest is efficiency`() {
        val allMax = listOf(1_800_000, 2_400_000)
        assertThat(clusterTierLabel(1_800_000, allMax)).isEqualTo("efficiency")
    }

    @Test
    fun `two clusters - highest is big (not prime when only two tiers)`() {
        val allMax = listOf(1_800_000, 2_400_000)
        assertThat(clusterTierLabel(2_400_000, allMax)).isEqualTo("big")
    }

    // ─── 3-cluster (big.LITTLE.prime) ───────────────────────────────────────────

    @Test
    fun `three clusters - lowest is efficiency`() {
        val allMax = listOf(1_209_600, 2_150_400, 3_187_200)
        assertThat(clusterTierLabel(1_209_600, allMax)).isEqualTo("efficiency")
    }

    @Test
    fun `three clusters - middle is big`() {
        val allMax = listOf(1_209_600, 2_150_400, 3_187_200)
        assertThat(clusterTierLabel(2_150_400, allMax)).isEqualTo("big")
    }

    @Test
    fun `three clusters - highest is prime`() {
        val allMax = listOf(1_209_600, 2_150_400, 3_187_200)
        assertThat(clusterTierLabel(3_187_200, allMax)).isEqualTo("prime")
    }

    // ─── 4-cluster (efficiency + big + big2 + prime) ────────────────────────────

    @Test
    fun `four clusters - lowest is efficiency`() {
        val allMax = listOf(1_000_000, 1_800_000, 2_400_000, 3_200_000)
        assertThat(clusterTierLabel(1_000_000, allMax)).isEqualTo("efficiency")
    }

    @Test
    fun `four clusters - middle tiers are big`() {
        val allMax = listOf(1_000_000, 1_800_000, 2_400_000, 3_200_000)
        assertThat(clusterTierLabel(1_800_000, allMax)).isEqualTo("big")
        assertThat(clusterTierLabel(2_400_000, allMax)).isEqualTo("big")
    }

    @Test
    fun `four clusters - highest is prime`() {
        val allMax = listOf(1_000_000, 1_800_000, 2_400_000, 3_200_000)
        assertThat(clusterTierLabel(3_200_000, allMax)).isEqualTo("prime")
    }
}
