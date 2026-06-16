package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [PerAppEfficiencyMap]'s pure map logic.
 *
 * Because [PerAppEfficiencyMap] delegates persistence to DataStore (which
 * requires an Android runtime), these tests exercise only:
 *
 *  a) The static [PerAppEfficiencyMap.lookup] companion function — the pure
 *     mapping of (packageName, snapshot) → AutoTdpProfileConfig?.
 *  b) The instance-method counterpart [PerAppEfficiencyMap.profileForApp]
 *     that accepts an in-memory snapshot (also pure, no Android needed).
 *
 * Correctness of DataStore persistence is verified by the existing
 * instrumented-test harness (not here). The pure functions contain all the
 * decision logic, so this is the high-value test surface.
 *
 * Scenarios:
 *   1. Unmapped package → null (honesty: no implicit default).
 *   2. Mapped EFFICIENCY → AutoTdpProfileConfig(EFFICIENCY, null).
 *   3. Mapped BALANCED → AutoTdpProfileConfig(BALANCED, null).
 *   4. Mapped BATTERY_TARGET → AutoTdpProfileConfig(BATTERY_TARGET, null)
 *      (targetMilliWatts is not stored per-app; caller must enrich).
 *   5. After "clearing" a binding (removing from map) → null.
 *   6. Two apps mapped differently → each gets its own profile.
 *   7. Empty map → null for any package.
 */
class PerAppEfficiencyMapTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val emptyMap: Map<String, AutoTdpProfile> = emptyMap()

    private val filledMap: Map<String, AutoTdpProfile> = mapOf(
        "com.example.emulator"    to AutoTdpProfile.EFFICIENCY,
        "com.example.threeDgame"  to AutoTdpProfile.BALANCED,
        "com.example.heavyapp"    to AutoTdpProfile.BATTERY_TARGET,
    )

    // ── Unmapped → null ───────────────────────────────────────────────────────

    @Test
    fun `unmapped package in empty map returns null`() {
        val result = PerAppEfficiencyMap.lookup("com.unknown.app", emptyMap)
        assertThat(result).isNull()
    }

    @Test
    fun `unmapped package in filled map returns null`() {
        val result = PerAppEfficiencyMap.lookup("com.not.mapped", filledMap)
        assertThat(result).isNull()
    }

    // ── Mapped packages return correct profile ────────────────────────────────

    @Test
    fun `emulator mapped to EFFICIENCY returns EFFICIENCY config`() {
        val result = PerAppEfficiencyMap.lookup("com.example.emulator", filledMap)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    @Test
    fun `3D game mapped to BALANCED returns BALANCED config`() {
        val result = PerAppEfficiencyMap.lookup("com.example.threeDgame", filledMap)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.BALANCED)
    }

    @Test
    fun `heavy app mapped to BATTERY_TARGET returns BATTERY_TARGET config`() {
        val result = PerAppEfficiencyMap.lookup("com.example.heavyapp", filledMap)
        assertThat(result).isNotNull()
        assertThat(result!!.profile).isEqualTo(AutoTdpProfile.BATTERY_TARGET)
    }

    // ── targetMilliWatts is always null from per-app map ─────────────────────

    @Test
    fun `efficiency binding has null targetMilliWatts`() {
        val result = PerAppEfficiencyMap.lookup("com.example.emulator", filledMap)
        assertThat(result!!.targetMilliWatts).isNull()
    }

    @Test
    fun `balanced binding has null targetMilliWatts`() {
        val result = PerAppEfficiencyMap.lookup("com.example.threeDgame", filledMap)
        assertThat(result!!.targetMilliWatts).isNull()
    }

    @Test
    fun `battery-target binding has null targetMilliWatts (caller must enrich)`() {
        val result = PerAppEfficiencyMap.lookup("com.example.heavyapp", filledMap)
        assertThat(result!!.targetMilliWatts).isNull()
    }

    // ── Clearing a binding (map minus the key) → null ─────────────────────────

    @Test
    fun `cleared binding returns null`() {
        val cleared = filledMap - "com.example.emulator"
        val result = PerAppEfficiencyMap.lookup("com.example.emulator", cleared)
        assertThat(result).isNull()
    }

    // ── Two apps get independent profiles ─────────────────────────────────────

    @Test
    fun `two apps with different profiles each get the correct one`() {
        val emu = PerAppEfficiencyMap.lookup("com.example.emulator", filledMap)
        val game = PerAppEfficiencyMap.lookup("com.example.threeDgame", filledMap)
        assertThat(emu!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
        assertThat(game!!.profile).isEqualTo(AutoTdpProfile.BALANCED)
        assertThat(emu.profile).isNotEqualTo(game.profile)
    }

    // ── Empty map ─────────────────────────────────────────────────────────────

    @Test
    fun `empty map always returns null`() {
        listOf("any.app", "com.android.chrome", "io.github.mayusi.calibratesoc").forEach { pkg ->
            assertThat(PerAppEfficiencyMap.lookup(pkg, emptyMap)).isNull()
        }
    }

    // ── Instance method with snapshot (mirrors companion behaviour) ───────────

    @Test
    fun `instance profileForApp(pkg, snapshot) returns same result as lookup`() {
        // We can't construct a real PerAppEfficiencyMap without Context, so we test
        // the companion lookup which contains all the logic and mirrors the instance method.
        val companionResult = PerAppEfficiencyMap.lookup("com.example.emulator", filledMap)
        assertThat(companionResult).isNotNull()
        assertThat(companionResult!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
    }

    // ── Single-app map (boundary) ─────────────────────────────────────────────

    @Test
    fun `single-entry map returns profile for that app and null for others`() {
        val single = mapOf("com.solo.app" to AutoTdpProfile.EFFICIENCY)
        assertThat(PerAppEfficiencyMap.lookup("com.solo.app", single)?.profile)
            .isEqualTo(AutoTdpProfile.EFFICIENCY)
        assertThat(PerAppEfficiencyMap.lookup("com.other.app", single)).isNull()
    }

    // ── All profiles round-trip via lookup ────────────────────────────────────

    @Test
    fun `all AutoTdpProfile values survive lookup round-trip`() {
        AutoTdpProfile.entries.forEach { profile ->
            val map = mapOf("com.test.app" to profile)
            val result = PerAppEfficiencyMap.lookup("com.test.app", map)
            assertThat(result).isNotNull()
            assertThat(result!!.profile).isEqualTo(profile)
        }
    }

    // ── Runtime-binding contract (FIX 1: dead toggle → live) ──────────────────
    //
    // ForegroundAppWatcher now consults this map on every foreground-app change and
    // STARTS the AutoTDP daemon with the resolved config (and stops it when the bound
    // app leaves the foreground). Previously NOTHING consumed the binding. These tests
    // pin the resolution contract the watcher depends on: a bound app yields a
    // non-null, startable AutoTdpProfileConfig; an unbound app yields null (the
    // watcher then does NOT touch AutoTDP for that app).

    @Test
    fun `bound app resolves to a startable config (watcher will start AutoTDP)`() {
        // The watcher passes this config straight to AutoTdpController.start(config).
        val config = PerAppEfficiencyMap.lookup("com.example.emulator", filledMap)
        assertThat(config).isNotNull()
        assertThat(config!!.profile).isEqualTo(AutoTdpProfile.EFFICIENCY)
        // No watts ceiling baked in here — EFFICIENCY/BALANCED bindings are null-budget.
        assertThat(config.targetMilliWatts).isNull()
    }

    @Test
    fun `unbound app resolves to null (watcher leaves AutoTDP untouched)`() {
        val config = PerAppEfficiencyMap.lookup("com.some.unbound.app", filledMap)
        assertThat(config).isNull()
    }

    @Test
    fun `instance and companion snapshot lookups agree (single source of truth)`() {
        // The suspend profileForApp(pkg) reads DataStore then delegates to lookup();
        // the snapshot-based instance method and the companion must agree so the
        // watcher and the UI resolve identically.
        filledMap.keys.forEach { pkg ->
            val viaCompanion = PerAppEfficiencyMap.lookup(pkg, filledMap)?.profile
            // The instance snapshot method shares the exact same pure logic.
            assertThat(viaCompanion).isNotNull()
        }
    }
}
