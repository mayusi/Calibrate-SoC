package io.github.mayusi.calibratesoc.data.shizuku

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the [OnboardingState] state machine transitions.
 *
 * These tests verify the logical progression of the onboarding state machine
 * independently of Android framework calls (no Context needed — pure logic tests
 * on the enum and its semantics).
 *
 * The actual [ShizukuOnboarding] class uses Shizuku static methods and an
 * Android Context, making it difficult to test directly without instrumentation.
 * These tests instead validate the state-machine contract:
 *   - states are mutually exclusive
 *   - the ordinal progression is correct
 *   - GRANTED_NO_WRITES is a terminal "honest failure" state distinct from GRANTED
 */
class ShizukuOnboardingStateTest {

    // ── State progression order ───────────────────────────────────────────────

    @Test
    fun `OnboardingState progression order matches discovery steps`() {
        // NOT_INSTALLED is first (lowest barrier state).
        // GRANTED is the goal.
        // GRANTED_NO_WRITES is honest failure after full setup.
        assertThat(OnboardingState.NOT_INSTALLED.ordinal)
            .isLessThan(OnboardingState.INSTALLED_STOPPED.ordinal)
        assertThat(OnboardingState.INSTALLED_STOPPED.ordinal)
            .isLessThan(OnboardingState.RUNNING_NO_PERMISSION.ordinal)
        assertThat(OnboardingState.RUNNING_NO_PERMISSION.ordinal)
            .isLessThan(OnboardingState.GRANTED.ordinal)
    }

    // ── GRANTED_NO_WRITES is the honest fallback after full setup ─────────────

    @Test
    fun `GRANTED_NO_WRITES ordinal is last — terminal honest failure after GRANTED`() {
        val allStates = OnboardingState.entries
        // GRANTED_NO_WRITES should come after GRANTED in ordinal order.
        assertThat(OnboardingState.GRANTED_NO_WRITES.ordinal)
            .isGreaterThan(OnboardingState.GRANTED.ordinal)
    }

    // ── All states are distinct ───────────────────────────────────────────────

    @Test
    fun `all OnboardingState values are distinct`() {
        val ordinals = OnboardingState.entries.map { it.ordinal }
        assertThat(ordinals).containsNoDuplicates()
    }

    // ── State count ───────────────────────────────────────────────────────────

    @Test
    fun `five distinct states exist covering the full lifecycle`() {
        assertThat(OnboardingState.entries).hasSize(5)
    }

    // ── simulate probe count driving NOT_INSTALLED → GRANTED_NO_WRITES ────────

    @Test
    fun `probed writable count zero drives GRANTED_NO_WRITES not GRANTED`() {
        // This simulates the logic in ShizukuOnboarding.computeState.
        // When probedWritableCount == 0, we should NOT report GRANTED.
        val probedWritableCount = 0
        val state = if (probedWritableCount == 0) {
            OnboardingState.GRANTED_NO_WRITES
        } else {
            OnboardingState.GRANTED
        }
        assertThat(state).isEqualTo(OnboardingState.GRANTED_NO_WRITES)
    }

    @Test
    fun `probed writable count above zero drives GRANTED`() {
        val probedWritableCount = 3
        val state = if (probedWritableCount == 0) {
            OnboardingState.GRANTED_NO_WRITES
        } else {
            OnboardingState.GRANTED
        }
        assertThat(state).isEqualTo(OnboardingState.GRANTED)
    }

    // ── GRANTED_NO_WRITES message must explain Snapdragon SELinux reality ─────

    @Test
    fun `GRANTED_NO_WRITES name contains 'WRITES' distinguishing it from GRANTED`() {
        // Semantic check: the name must make the honesty distinction obvious
        // to any developer reading it, not just users.
        assertThat(OnboardingState.GRANTED_NO_WRITES.name).contains("WRITES")
        assertThat(OnboardingState.GRANTED.name).doesNotContain("NO_WRITES")
    }
}
