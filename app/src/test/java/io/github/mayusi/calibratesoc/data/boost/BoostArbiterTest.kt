package io.github.mayusi.calibratesoc.data.boost

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.boost.BoostArbiter.ClockOwner
import org.junit.Test

/**
 * Unit tests for [BoostArbiter] — the AutoTDP ⟷ Game Boost mutual-exclusion and the
 * Throttle Guard suppression rules. Pure logic, no Android.
 */
class BoostArbiterTest {

    // ─── Mutual exclusion ────────────────────────────────────────────────────────

    @Test
    fun `acquiring from free state requires no prior stop`() {
        val a = BoostArbiter()
        val r = a.acquire(ClockOwner.AUTO_TDP)
        assertThat(r.granted).isTrue()
        assertThat(r.mustStopPreviousOwner).isEqualTo(ClockOwner.NONE)
        assertThat(a.owner.value).isEqualTo(ClockOwner.AUTO_TDP)
    }

    @Test
    fun `Game Boost acquiring while AutoTDP owns must stop AutoTDP`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.AUTO_TDP)
        val r = a.acquire(ClockOwner.GAME_BOOST)
        assertThat(r.mustStopPreviousOwner).isEqualTo(ClockOwner.AUTO_TDP)
        assertThat(a.owner.value).isEqualTo(ClockOwner.GAME_BOOST)
    }

    @Test
    fun `AutoTDP acquiring while Game Boost owns must stop Game Boost`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.GAME_BOOST)
        val r = a.acquire(ClockOwner.AUTO_TDP)
        assertThat(r.mustStopPreviousOwner).isEqualTo(ClockOwner.GAME_BOOST)
        assertThat(a.owner.value).isEqualTo(ClockOwner.AUTO_TDP)
    }

    @Test
    fun `re-acquiring the same owner requires no stop (idempotent)`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.GAME_BOOST)
        val r = a.acquire(ClockOwner.GAME_BOOST)
        assertThat(r.mustStopPreviousOwner).isEqualTo(ClockOwner.NONE)
        assertThat(a.owner.value).isEqualTo(ClockOwner.GAME_BOOST)
    }

    @Test
    fun `acquire with NONE is rejected`() {
        val a = BoostArbiter()
        try {
            a.acquire(ClockOwner.NONE)
            assertThat(false).isTrue() // should not reach
        } catch (e: IllegalArgumentException) {
            assertThat(e).isNotNull()
        }
    }

    // ─── Release semantics ───────────────────────────────────────────────────────

    @Test
    fun `release by the current owner frees the clocks`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.AUTO_TDP)
        a.release(ClockOwner.AUTO_TDP)
        assertThat(a.owner.value).isEqualTo(ClockOwner.NONE)
    }

    @Test
    fun `late release by a superseded owner is a no-op`() {
        // AutoTDP owned, then Game Boost took over. A late AutoTDP.release() must NOT
        // clear Game Boost's ownership (this prevents a stale stop from un-suppressing
        // the guard while Boost is still running).
        val a = BoostArbiter()
        a.acquire(ClockOwner.AUTO_TDP)
        a.acquire(ClockOwner.GAME_BOOST)
        a.release(ClockOwner.AUTO_TDP) // stale
        assertThat(a.owner.value).isEqualTo(ClockOwner.GAME_BOOST)
        assertThat(a.throttleGuardSuppressed.value).isTrue()
    }

    // ─── Throttle Guard suppression ──────────────────────────────────────────────

    @Test
    fun `guard is not suppressed when clocks are free`() {
        val a = BoostArbiter()
        assertThat(a.throttleGuardSuppressed.value).isFalse()
    }

    @Test
    fun `guard is suppressed while AutoTDP owns clocks`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.AUTO_TDP)
        assertThat(a.throttleGuardSuppressed.value).isTrue()
    }

    @Test
    fun `guard is suppressed while Game Boost owns clocks`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.GAME_BOOST)
        assertThat(a.throttleGuardSuppressed.value).isTrue()
    }

    @Test
    fun `guard un-suppressed after the owner releases`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.GAME_BOOST)
        a.release(ClockOwner.GAME_BOOST)
        assertThat(a.throttleGuardSuppressed.value).isFalse()
    }

    @Test
    fun `guard stays suppressed across an owner handoff`() {
        val a = BoostArbiter()
        a.acquire(ClockOwner.AUTO_TDP)
        a.acquire(ClockOwner.GAME_BOOST) // handoff
        assertThat(a.throttleGuardSuppressed.value).isTrue()
    }

    // ─── isOwnerOrFree ───────────────────────────────────────────────────────────

    @Test
    fun `isOwnerOrFree true for free and for the owner, false for the other`() {
        val a = BoostArbiter()
        assertThat(a.isOwnerOrFree(ClockOwner.AUTO_TDP)).isTrue() // free
        a.acquire(ClockOwner.AUTO_TDP)
        assertThat(a.isOwnerOrFree(ClockOwner.AUTO_TDP)).isTrue() // is owner
        assertThat(a.isOwnerOrFree(ClockOwner.GAME_BOOST)).isFalse() // other owns
    }
}
