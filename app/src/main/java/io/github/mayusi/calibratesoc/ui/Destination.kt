package io.github.mayusi.calibratesoc.ui

/**
 * Navigation destinations. String routes so they compose cleanly with
 * Compose Navigation's existing argument-handling. Kept as a sealed
 * hierarchy so adding a new screen is a single-source change (the nav
 * graph picks it up via exhaustive `when`).
 */
sealed class Destination(val route: String, val label: String) {
    data object Dashboard : Destination("dashboard", "Dashboard")
    data object Tune : Destination("tune", "Tune")
    data object Profiles : Destination("profiles", "Profiles")
    data object Benchmark : Destination("benchmark", "Benchmark")
    data object Hardware : Destination("hardware", "Hardware")
    data object Settings : Destination("settings", "Settings")
    data object DeviceInfo : Destination("device_info", "Device")
    data object TuneHistory : Destination("tune_history", "History")
    /** Sessions list — sub-screen reachable from the Dashboard "Sessions" card.
     *  Not in the bottom bar (7 tabs is too many on a handheld). */
    data object Sessions : Destination("sessions", "Sessions")
    /** Session detail / timeline — sub-screen of Sessions. */
    data object SessionDetail : Destination("session_detail/{sessionId}", "Session") {
        fun route(id: Long) = "session_detail/$id"
    }
    /** Advanced Tuning — sub-screen reached from the Tune tab. Not in
     *  the bottom bar; accessed via the "Advanced tuning →" button. */
    data object AdvancedTuning : Destination("advanced_tuning", "Advanced Tuning")

    companion object {
        /** Six-tab bottom bar. Device Info is reachable as a deep-link
         *  from the Settings screen — it's a power-user diagnostic and
         *  doesn't belong in the primary nav.
         *
         *  This MUST be a `get()` rather than a `val =` initializer:
         *  Kotlin 2.1's `data object` members are not guaranteed to be
         *  initialized at the moment a companion-object val expression
         *  evaluates them, so a `val list = listOf(Dashboard, ...)`
         *  can produce a list of nulls and crash the first Compose
         *  frame with `Attempt to invoke ... on a null object reference`.
         *  A property getter re-resolves the objects on each call,
         *  after class initialization has settled. */
        val bottomBar: List<Destination>
            get() = listOf(Dashboard, Tune, Profiles, Benchmark, Hardware, Settings)
    }
}
