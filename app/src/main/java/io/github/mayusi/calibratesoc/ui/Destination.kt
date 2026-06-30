package io.github.mayusi.calibratesoc.ui

/**
 * Navigation destinations. String routes so they compose cleanly with
 * Compose Navigation's existing argument-handling. Kept as a sealed
 * hierarchy so adding a new screen is a single-source change (the nav
 * graph picks it up via exhaustive `when`).
 *
 * ── Information Architecture (Direction C, v2) ──────────────────────
 *
 * Bottom bar (5 items):
 *   Dashboard   — live telemetry home
 *   Tune        — hub: [Presets | Advanced | AutoTDP | Profiles]
 *   Performance — hub: [Benchmark | Stability | Sessions]
 *   Hardware    — SoC/RAM/storage identify + speed-test
 *   Settings    — privilege badge + grant flows + About
 *
 * AutoTDP and Advanced Tuning are FIRST-CLASS surfaces inside the Tune
 * hub — they get their own dedicated tab in the Arsenal segmented
 * control, not just a button buried below the fold.
 *
 * Off-nav deep-links (reachable from within hub screens):
 *   DeviceInfo     — from Settings → power-user diagnostic
 *   TuneHistory    — from Tune hub Presets tab
 *   SessionDetail  — from Performance hub Sessions tab
 *   AppStats       — from Performance hub Sessions tab
 */
sealed class Destination(val route: String, val label: String) {
    data object Dashboard   : Destination("dashboard",    "Dashboard")
    data object Tune        : Destination("tune",         "Tune")
    data object Performance : Destination("performance",  "Performance")
    data object Hardware    : Destination("hardware",     "Hardware")
    data object Settings    : Destination("settings",     "Settings")

    // ── Tune hub sub-screens (also top-level routes so deep-links work) ──
    data object Profiles        : Destination("profiles",        "Profiles")
    data object AdvancedTuning  : Destination("advanced_tuning", "Advanced Tuning")
    data object AutoTdp         : Destination("autotdp",         "AutoTDP")
    data object TuneHistory     : Destination("tune_history",    "History")

    // ── Performance hub sub-screens ──────────────────────────────────
    data object Benchmark  : Destination("benchmark",  "Benchmark")
    data object Stability  : Destination("stability",  "Stability")
    data object Sessions   : Destination("sessions",   "Sessions")
    data object Insights   : Destination("insights",   "Insights")
    data object GameAware  : Destination("game_aware", "Game-Aware")

    // ── Off-nav deep-links ───────────────────────────────────────────
    data object DeviceInfo : Destination("device_info", "Device")
    // NOTE: There is intentionally no GameTunes destination. The per-game tune
    // share/import/community hub is opened inline from ProfilesScreen via local
    // state (gameTuneApp = pkg), never through the nav graph. The former
    // Destination.GameTunes + its composable had zero navigate() call sites and
    // were removed as dead routing.
    /** Session detail / timeline — sub-screen of Sessions. */
    data object SessionDetail : Destination("session_detail/{sessionId}", "Session") {
        fun route(id: Long) = "session_detail/$id"
    }
    /** Per-App Performance Dashboard — sub-screen of Sessions. */
    data object AppStats : Destination("app_stats", "App Stats")

    companion object {
        /**
         * Five-tab bottom bar (Direction C v2).
         *
         * MUST be a `get()` rather than a `val =` initializer:
         * Kotlin 2.1's `data object` members are not guaranteed to be
         * initialized at the moment a companion-object val expression
         * evaluates them, so a `val list = listOf(Dashboard, ...)`
         * can produce a list of nulls and crash the first Compose frame.
         * A property getter re-resolves the objects on each call, after
         * class initialization has settled.
         */
        val bottomBar: List<Destination>
            get() = listOf(Dashboard, Tune, Performance, Hardware, Settings)

        /** Routes that belong to the Tune hub (any tab shows the Tune bottom item as selected). */
        val tuneRoutes: Set<String>
            get() = setOf(
                Tune.route,
                AdvancedTuning.route,
                AutoTdp.route,
                Profiles.route,
                TuneHistory.route,
                // The Game Tunes screen has no nav Destination (it's reached via
                // Profiles' local state), but it lives under the Tune section, so its
                // route pattern must still highlight the Tune tab in the bottom bar.
                "game_tunes/{packageName}",
            )

        /** Routes that belong to the Performance hub. */
        val performanceRoutes: Set<String>
            get() = setOf(
                Performance.route,
                Benchmark.route,
                Stability.route,
                Sessions.route,
                Insights.route,
                GameAware.route,
                SessionDetail.route,
                AppStats.route,
            )

        /**
         * Returns true when the bottom navigation bar should be visible for [route].
         *
         * Uses prefix matching (startsWith) so deep-link variants with query
         * parameters — e.g. "tune?tab=2" or "performance?tab=1" — are correctly
         * treated as bar-visible, regardless of which query params are appended.
         *
         * The five primary bar routes are:
         *   dashboard, tune (+ ?tab=*), performance (+ ?tab=*), hardware, settings
         */
        fun isBottomBarVisible(route: String): Boolean =
            route == Dashboard.route ||
            route.startsWith(Tune.route + "?") || route == Tune.route ||
            route.startsWith(Performance.route + "?") || route == Performance.route ||
            route == Hardware.route ||
            route == Settings.route

        /**
         * Resolves [route] to the bottom-bar [Destination] that should appear active.
         *
         * Resolution order:
         *  1. If route equals or starts-with "tune?" → [Tune]
         *  2. If route is in [tuneRoutes]             → [Tune]
         *  3. If route equals or starts-with "performance?" → [Performance]
         *  4. If route is in [performanceRoutes]      → [Performance]
         *  5. Exact match against a bottom-bar item   → that item
         *  6. Fallback                                → [Dashboard]
         *
         * This is a pure function with no Compose dependency, so it is trivially
         * unit-testable.
         */
        fun activeDestFor(route: String): Destination = when {
            route == Tune.route || route.startsWith(Tune.route + "?") -> Destination.Tune
            route in tuneRoutes -> Destination.Tune
            route == Performance.route || route.startsWith(Performance.route + "?") -> Destination.Performance
            route in performanceRoutes -> Destination.Performance
            else -> bottomBar.firstOrNull { it.route == route } ?: Destination.Dashboard
        }
    }
}
