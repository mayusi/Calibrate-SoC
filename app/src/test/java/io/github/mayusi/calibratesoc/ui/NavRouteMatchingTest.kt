package io.github.mayusi.calibratesoc.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [Destination.isBottomBarVisible] and [Destination.activeDestFor].
 *
 * These helpers are pure functions, so no Android framework is needed.
 *
 * Covers:
 *  - Base routes for all five bottom-bar items
 *  - Deep-link query-param variants ("tune?tab=2", "performance?tab=3")
 *  - Tune hub sub-routes (advanced_tuning, autotdp, profiles, tune_history)
 *  - Performance hub sub-routes (benchmark, stability, sessions, insights, game_aware)
 *  - Off-nav routes that must HIDE the bar (session_detail/{id}, app_stats, device_info)
 *  - Unknown / empty routes
 */
class NavRouteMatchingTest {

    // ─── isBottomBarVisible ──────────────────────────────────────────────────────

    @Test
    fun `dashboard base route shows bar`() {
        assertThat(Destination.isBottomBarVisible("dashboard")).isTrue()
    }

    @Test
    fun `tune base route shows bar`() {
        assertThat(Destination.isBottomBarVisible("tune")).isTrue()
    }

    @Test
    fun `tune deep-link with tab=0 shows bar`() {
        assertThat(Destination.isBottomBarVisible("tune?tab=0")).isTrue()
    }

    @Test
    fun `tune deep-link with tab=2 (AutoTDP) shows bar`() {
        assertThat(Destination.isBottomBarVisible("tune?tab=2")).isTrue()
    }

    @Test
    fun `tune deep-link with tab=3 shows bar`() {
        assertThat(Destination.isBottomBarVisible("tune?tab=3")).isTrue()
    }

    @Test
    fun `performance base route shows bar`() {
        assertThat(Destination.isBottomBarVisible("performance")).isTrue()
    }

    @Test
    fun `performance deep-link with tab=1 shows bar`() {
        assertThat(Destination.isBottomBarVisible("performance?tab=1")).isTrue()
    }

    @Test
    fun `performance deep-link with tab=3 shows bar`() {
        assertThat(Destination.isBottomBarVisible("performance?tab=3")).isTrue()
    }

    @Test
    fun `hardware route shows bar`() {
        assertThat(Destination.isBottomBarVisible("hardware")).isTrue()
    }

    @Test
    fun `settings route shows bar`() {
        assertThat(Destination.isBottomBarVisible("settings")).isTrue()
    }

    @Test
    fun `off-nav session_detail hides bar`() {
        assertThat(Destination.isBottomBarVisible("session_detail/42")).isFalse()
    }

    @Test
    fun `off-nav app_stats hides bar`() {
        assertThat(Destination.isBottomBarVisible("app_stats")).isFalse()
    }

    @Test
    fun `off-nav device_info hides bar`() {
        assertThat(Destination.isBottomBarVisible("device_info")).isFalse()
    }

    @Test
    fun `off-nav tune_history hides bar`() {
        // tune_history is a Tune hub sub-screen pushed onto the back stack;
        // the bar should be hidden while it is active (it has its own back button).
        assertThat(Destination.isBottomBarVisible("tune_history")).isFalse()
    }

    @Test
    fun `empty route hides bar`() {
        assertThat(Destination.isBottomBarVisible("")).isFalse()
    }

    @Test
    fun `unknown route hides bar`() {
        assertThat(Destination.isBottomBarVisible("some_unknown_screen")).isFalse()
    }

    // ─── activeDestFor ───────────────────────────────────────────────────────────

    @Test
    fun `tune base route resolves to Tune`() {
        assertThat(Destination.activeDestFor("tune")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `tune?tab=0 resolves to Tune`() {
        assertThat(Destination.activeDestFor("tune?tab=0")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `tune?tab=2 (AutoTDP deep-link) resolves to Tune`() {
        assertThat(Destination.activeDestFor("tune?tab=2")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `tune?tab=3 resolves to Tune`() {
        assertThat(Destination.activeDestFor("tune?tab=3")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `advanced_tuning sub-route resolves to Tune`() {
        assertThat(Destination.activeDestFor("advanced_tuning")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `autotdp sub-route resolves to Tune`() {
        assertThat(Destination.activeDestFor("autotdp")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `profiles sub-route resolves to Tune`() {
        assertThat(Destination.activeDestFor("profiles")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `tune_history sub-route resolves to Tune`() {
        assertThat(Destination.activeDestFor("tune_history")).isEqualTo(Destination.Tune)
    }

    @Test
    fun `performance base route resolves to Performance`() {
        assertThat(Destination.activeDestFor("performance")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `performance?tab=3 resolves to Performance`() {
        assertThat(Destination.activeDestFor("performance?tab=3")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `stability sub-route resolves to Performance`() {
        assertThat(Destination.activeDestFor("stability")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `sessions sub-route resolves to Performance`() {
        assertThat(Destination.activeDestFor("sessions")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `benchmark sub-route resolves to Performance`() {
        assertThat(Destination.activeDestFor("benchmark")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `insights sub-route resolves to Performance`() {
        assertThat(Destination.activeDestFor("insights")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `game_aware sub-route resolves to Performance`() {
        assertThat(Destination.activeDestFor("game_aware")).isEqualTo(Destination.Performance)
    }

    @Test
    fun `dashboard route resolves to Dashboard`() {
        assertThat(Destination.activeDestFor("dashboard")).isEqualTo(Destination.Dashboard)
    }

    @Test
    fun `hardware route resolves to Hardware`() {
        assertThat(Destination.activeDestFor("hardware")).isEqualTo(Destination.Hardware)
    }

    @Test
    fun `settings route resolves to Settings`() {
        assertThat(Destination.activeDestFor("settings")).isEqualTo(Destination.Settings)
    }

    @Test
    fun `unknown route falls back to Dashboard`() {
        assertThat(Destination.activeDestFor("some_unknown")).isEqualTo(Destination.Dashboard)
    }

    @Test
    fun `empty route falls back to Dashboard`() {
        assertThat(Destination.activeDestFor("")).isEqualTo(Destination.Dashboard)
    }
}
