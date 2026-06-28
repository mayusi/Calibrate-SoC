package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsDao
import io.github.mayusi.calibratesoc.data.insights.db.LearnedGameParamsEntity
import io.github.mayusi.calibratesoc.data.thermal.CapFloor
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * UNIT 1 — per-game learning model tests.
 *
 * Pure JVM: a hand-rolled in-memory [LearnedGameParamsDao] fake (no Room runtime, no
 * Android). Covers cold-start, the warm seed path, the EWMA ratchet convergence, the
 * poison-resistance / floor-clamp safety invariant, and the persistence round-trip.
 */
class LearnedGameModelTest {

    /** In-memory DAO fake: one row per pkg, REPLACE semantics like the real upsert. */
    private class FakeDao : LearnedGameParamsDao {
        val rows = mutableMapOf<String, LearnedGameParamsEntity>()
        override suspend fun getByPkg(pkg: String): LearnedGameParamsEntity? = rows[pkg]
        override suspend fun upsert(entity: LearnedGameParamsEntity) {
            rows[entity.pkg] = entity
        }
        override suspend fun getAll(): List<LearnedGameParamsEntity> =
            rows.values.sortedByDescending { it.lastUpdatedMs }
    }

    // SD8Gen2-style big-cluster OPP table (ascending). Top = 2_803_000 kHz.
    private val opp = listOf(
        499_000, 844_000, 1_171_000, 1_536_000,
        1_920_000, 2_323_000, 2_707_000, 2_803_000,
    )
    private val topOpp = opp.last()
    private val hardFloor = CapFloor.hardFloorKhz(opp)!! // 40% of top → snapped OPP

    private val PKG = "com.example.game"

    private fun model(dao: LearnedGameParamsDao = FakeDao()) = LearnedGameModel(dao)

    private fun cleanOutcome(cap: Int?) = SessionOutcome(
        preemptFiredInSteadyState = false,
        avgFpsHeldTarget = true,
        convergedCapKhz = cap,
        observedOnsetSec = null,
        observedBandCenterPct = 74,
        oppStepsKhz = opp,
    )

    private fun throttledOutcome(cap: Int?, onsetSec: Int?) = SessionOutcome(
        preemptFiredInSteadyState = true,
        avgFpsHeldTarget = false,
        convergedCapKhz = cap,
        observedOnsetSec = onsetSec,
        observedBandCenterPct = 70,
        oppStepsKhz = opp,
    )

    // ── Cold start ───────────────────────────────────────────────────────────────

    @Test
    fun `cold start - no row returns null seed`() = runBlocking {
        val m = model()
        assertThat(m.seedFor(PKG, opp)).isNull()
    }

    @Test
    fun `cold start - null package returns null seed`() = runBlocking {
        val dao = FakeDao()
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, 1_920_000, 600, 74, sessionCount = 9)
        val m = model(dao)
        assertThat(m.seedFor(null, opp)).isNull()
        assertThat(m.seedFor("  ", opp)).isNull()
    }

    @Test
    fun `cold start - sessionCount below 2 returns null seed`() = runBlocking {
        val dao = FakeDao()
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, 1_920_000, 600, 74, sessionCount = 1)
        val m = model(dao)
        assertThat(m.seedFor(PKG, opp)).isNull()
    }

    @Test
    fun `cold start - sessionCount exactly 2 surfaces the seed`() = runBlocking {
        val dao = FakeDao()
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, 1_920_000, 600, 74, sessionCount = 2)
        val m = model(dao)
        val seed = m.seedFor(PKG, opp)
        assertThat(seed).isNotNull()
        assertThat(seed!!.sessionCount).isEqualTo(2)
        assertThat(seed.safeSustainedCapKhz).isEqualTo(1_920_000)
    }

    @Test
    fun `cold start - null stored cap returns null seed`() = runBlocking {
        val dao = FakeDao()
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, safeSustainedCapKhz = null, sessionCount = 9)
        val m = model(dao)
        assertThat(m.seedFor(PKG, opp)).isNull()
    }

    // ── Warm seed (snap + floor clamp on read) ─────────────────────────────────────

    @Test
    fun `seed snaps stored cap to a real OPP`() = runBlocking {
        val dao = FakeDao()
        // An off-table value (between two OPPs) must snap DOWN to a real OPP.
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, 2_000_000, 600, 74, sessionCount = 5)
        val m = model(dao)
        val seed = m.seedFor(PKG, opp)!!
        assertThat(seed.safeSustainedCapKhz).isIn(opp)
        assertThat(seed.safeSustainedCapKhz).isEqualTo(1_920_000) // largest OPP ≤ 2_000_000
    }

    @Test
    fun `seed re-clamps a below-floor stored cap UP to the 40 percent floor`() = runBlocking {
        val dao = FakeDao()
        // A stale row from an older build that stored a below-floor cap (499 MHz).
        dao.rows[PKG] = LearnedGameParamsEntity(PKG, 499_000, 600, 70, sessionCount = 5)
        val m = model(dao)
        val seed = m.seedFor(PKG, opp)!!
        assertThat(seed.safeSustainedCapKhz!!).isAtLeast(hardFloor)
        assertThat(seed.safeSustainedCapKhz).isEqualTo(hardFloor)
    }

    // ── EWMA ratchet ───────────────────────────────────────────────────────────────

    @Test
    fun `first clean session seeds the EWMA directly with the converged cap`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        m.updateAfterSession(PKG, cleanOutcome(1_920_000), nowMs = 1_000)
        val row = dao.rows[PKG]!!
        assertThat(row.sessionCount).isEqualTo(1)
        assertThat(row.safeSustainedCapKhz).isEqualTo(1_920_000)
        assertThat(row.lastUpdatedMs).isEqualTo(1_000)
    }

    @Test
    fun `EWMA ratchet over 5 clean sessions converges upward toward the sustainable cap`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        // Session 1 establishes a low cap; sessions 2-5 prove a higher cap is sustainable.
        m.updateAfterSession(PKG, cleanOutcome(1_171_000), nowMs = 1)
        repeat(5) { m.updateAfterSession(PKG, cleanOutcome(2_323_000), nowMs = (it + 2).toLong()) }
        val row = dao.rows[PKG]!!
        assertThat(row.sessionCount).isEqualTo(6)
        // A clean session ratchets UP cautiously (candidate = max(converged, prev)), so the
        // stored cap climbs toward 2_323_000 and never below the first session's 1_171_000.
        assertThat(row.safeSustainedCapKhz!!).isAtLeast(1_171_000)
        assertThat(row.safeSustainedCapKhz!!).isAtMost(2_323_000)
        // After 5 EWMA folds at α=0.3 it should be well above the midpoint, near the top.
        assertThat(row.safeSustainedCapKhz!!).isAtLeast(1_920_000)
    }

    @Test
    fun `clean session never lowers the learned cap`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        m.updateAfterSession(PKG, cleanOutcome(2_323_000), nowMs = 1)
        m.updateAfterSession(PKG, cleanOutcome(2_323_000), nowMs = 2)
        val prevCap = dao.rows[PKG]!!.safeSustainedCapKhz!!
        // A clean session that converged LOWER must not drag the learned cap down.
        m.updateAfterSession(PKG, cleanOutcome(1_171_000), nowMs = 3)
        assertThat(dao.rows[PKG]!!.safeSustainedCapKhz!!).isAtLeast(prevCap)
    }

    // ── Poison resistance + floor clamp (SAFETY) ────────────────────────────────────

    @Test
    fun `one bad hot session cannot collapse the stored cap below the 40 percent floor`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        // Build a confident high learned cap over clean sessions.
        repeat(6) { m.updateAfterSession(PKG, cleanOutcome(2_707_000), nowMs = it.toLong()) }
        val before = dao.rows[PKG]!!.safeSustainedCapKhz!!
        // A single hot session that converged at the very bottom OPP.
        m.updateAfterSession(PKG, throttledOutcome(cap = 499_000, onsetSec = 120), nowMs = 99)
        val after = dao.rows[PKG]!!.safeSustainedCapKhz!!
        // EWMA damps the single sample: the cap drops only ~30% of the way, never collapses.
        assertThat(after).isAtLeast(hardFloor)
        assertThat(after).isLessThan(before)      // it did move down (responsive)
        assertThat(after).isGreaterThan(499_000)  // but nowhere near the bad sample
    }

    @Test
    fun `stored cap is always clamped to the 40 percent floor on write`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        // First (seeding) session converges below the floor — must be clamped UP on store.
        m.updateAfterSession(PKG, throttledOutcome(cap = 499_000, onsetSec = 60), nowMs = 1)
        assertThat(dao.rows[PKG]!!.safeSustainedCapKhz!!).isAtLeast(hardFloor)
    }

    @Test
    fun `throttle onset EWMA folds only when a preempt fired`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        // Throttled session records onset.
        m.updateAfterSession(PKG, throttledOutcome(cap = 1_920_000, onsetSec = 300), nowMs = 1)
        assertThat(dao.rows[PKG]!!.throttleOnsetSec).isEqualTo(300)
        // A subsequent CLEAN session (no preempt) must NOT erase or move the onset.
        m.updateAfterSession(PKG, cleanOutcome(2_323_000), nowMs = 2)
        assertThat(dao.rows[PKG]!!.throttleOnsetSec).isEqualTo(300)
        // Another throttled session EWMA-folds the new onset toward 200.
        m.updateAfterSession(PKG, throttledOutcome(cap = 1_920_000, onsetSec = 200), nowMs = 3)
        val onset = dao.rows[PKG]!!.throttleOnsetSec!!
        assertThat(onset).isLessThan(300)
        assertThat(onset).isGreaterThan(200)
    }

    @Test
    fun `update is a no-op for a null or blank package`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        m.updateAfterSession(null, cleanOutcome(1_920_000), nowMs = 1)
        m.updateAfterSession("   ", cleanOutcome(1_920_000), nowMs = 1)
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun `null converged cap keeps the prior learned cap unchanged`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        m.updateAfterSession(PKG, cleanOutcome(1_920_000), nowMs = 1)
        val before = dao.rows[PKG]!!.safeSustainedCapKhz
        // A session that never capped (converged == null) just bumps the count.
        m.updateAfterSession(PKG, cleanOutcome(null), nowMs = 2)
        assertThat(dao.rows[PKG]!!.safeSustainedCapKhz).isEqualTo(before)
        assertThat(dao.rows[PKG]!!.sessionCount).isEqualTo(2)
    }

    @Test
    fun `persisted row round-trips through seedFor after enough sessions`() = runBlocking {
        val dao = FakeDao()
        val m = model(dao)
        m.updateAfterSession(PKG, throttledOutcome(cap = 1_920_000, onsetSec = 480), nowMs = 1)
        m.updateAfterSession(PKG, throttledOutcome(cap = 1_920_000, onsetSec = 480), nowMs = 2)
        val seed = m.seedFor(PKG, opp)!!
        assertThat(seed.sessionCount).isEqualTo(2)
        assertThat(seed.throttleOnsetSec).isEqualTo(480)
        assertThat(seed.safeSustainedCapKhz).isIn(opp)
    }

    @Test
    fun `SessionOutcome isClean requires no preempt AND fps held`() {
        assertThat(cleanOutcome(1_920_000).isClean).isTrue()
        assertThat(throttledOutcome(1_920_000, 60).isClean).isFalse()
        // FPS not held → not clean even without a preempt.
        assertThat(
            SessionOutcome(false, avgFpsHeldTarget = false, 1_920_000, null, 74, opp).isClean
        ).isFalse()
    }
}
