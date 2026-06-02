package io.github.mayusi.calibratesoc.ui.overlay

/**
 * Which row layout the floating HUD draws.
 *
 *   COMPACT — single horizontal strip: max CPU MHz · GPU% · highest temp °C
 *             · battery W. ~70dp tall, perfect for racing it across the
 *             top of an emulator window without obscuring the game.
 *   VERBOSE — multi-row panel with per-core MHz, GPU MHz + load, every
 *             thermal zone, battery draw and live-tweak chips. Closer to
 *             a desktop RTSS overlay; use when actively tuning.
 */
enum class HudProfile { COMPACT, VERBOSE }
