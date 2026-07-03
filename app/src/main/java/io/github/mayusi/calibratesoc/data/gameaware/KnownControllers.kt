package io.github.mayusi.calibratesoc.data.gameaware

/**
 * Known-CONTROLLER classifier — the mirror image of [KnownGames].
 *
 * Where [KnownGames] answers "is this foreground package a game I could tune?",
 * this answers "is this foreground package another app that is ALREADY driving
 * the SoC's performance, so CalibrateSoC must stand down and stop fighting it?"
 *
 * ## Why this exists (non-interference back-off, signal (a))
 *
 * A handheld user runs several tools that write CPU/GPU clocks or governors:
 *
 *  - **Nova / GameNative** (`app.gamenative*`, `com.gamenative`) — the user's own
 *    containerless PC-game runtime. It pins the CPU governor to `performance` and
 *    manages its own frame pacing. If AutoTDP also caps clocks underneath it, the
 *    two fight: one pins, the other caps, and the revert journals corrupt each
 *    other. AutoTDP must FULLY SUSPEND and revert to stock while Nova is foreground.
 *  - **Winlator** (`com.winlator*`) — Windows x86 translation layer; same story.
 *  - **Third-party root-perf managers / tuners** (Kraken/scene kernel managers,
 *    other TDP tools) — anything whose whole job is to own the clocks.
 *
 * The moment one of these is in the foreground, [InterferenceGuard] ORs this
 * signal into its suspend decision and AutoTDP drives its target to STOCK through
 * the normal per-tick apply path — auto-resuming the instant the controller leaves.
 *
 * ## Relationship to [KnownGames]
 *
 * [KnownGames] classifies emulators/launchers we might TUNE. Some packages appear
 * in BOTH tables intentionally: `com.gamenative` / `com.winlator` are heavy
 * translation layers KnownGames anchors as a workload (so the wrapper-aware
 * classifier works), AND controllers this table flags so AutoTDP defers to them.
 * That is not a contradiction — the game-aware auto-config only ever SUGGESTS a
 * bundle (advisory), whereas the back-off guard's job is to make sure that when
 * one of these actually owns the SoC live, we do not fight it.
 *
 * ## Honesty: pure lookup, no side effects
 *
 * Exactly like [KnownGames] this file is a lookup table. It touches no sysfs node,
 * reaches for no Android context, and applies nothing. The service layer makes ALL
 * decisions about whether and how to act on the classification.
 *
 * The list is deliberately EXTENSIBLE — new controller packages are a one-line
 * addition to [EXACT] or [PREFIXES].
 *
 * ## Complemented by a GENERIC identity heuristic (this list is the FAST PATH)
 *
 * As of 0.3.9 this name list is no longer the ONLY way signal (a) fires. It is the FAST
 * PATH — an instant, allocation-free lookup for controllers we already recognise, with no
 * Binder call. [io.github.mayusi.calibratesoc.data.autotdp.InterferenceGuard.decide] also
 * ORs in a caller-resolved GENERIC heuristic (`genericController`): a LIST-UNKNOWN foreground
 * app that holds `WRITE_SECURE_SETTINGS` AND is not a game AND is not user-tuned AND is not
 * us is treated as an external controller too (surfaced honestly as EXTERNAL_CONTROLLER).
 * So ANY app that is actually controlling performance backs AutoTDP off — not just the ones
 * named here. Keeping a package IN this list is still worthwhile: it fires INSTANTLY without
 * the PackageManager permission call, and it also catches a controller that happens NOT to
 * hold WSS (e.g. a root tuner that writes sysfs directly). The two mechanisms are additive.
 */
object KnownControllers {

    /**
     * True when [pkg] is a known external performance controller that CalibrateSoC
     * should defer to (suspend + revert to stock). Null / blank ⇒ false (no
     * foreground package known ⇒ nothing to defer to).
     *
     * Matching is exact-first, then longest-safe prefix — the SAME shape as
     * [KnownGames.defaultHintFor] so both classifiers behave identically on the
     * multi-APK families (e.g. `app.gamenative.iic`).
     */
    fun isController(pkg: String?): Boolean {
        val p = pkg?.takeIf { it.isNotBlank() } ?: return false
        if (p in EXACT) return true
        return prefixMatch(p) != null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal tables
    // ─────────────────────────────────────────────────────────────────────────

    /** Exact package names of known external controllers. */
    private val EXACT: Set<String> = buildSet {
        // ── The user's own game runtime (Nova / GameNative) ──────────────────
        entry("com.gamenative")             // GameNative / Nova (stable id)
        // ── Windows translation layer ─────────────────────────────────────────
        entry("com.winlator")               // Winlator (base package)
        // ── Extensible: common root-perf-manager / tuner examples ─────────────
        // These OWN the clocks/governor when running; AutoTDP defers.
        entry("com.franco.kernel")          // Franco Kernel Manager
        entry("flar2.exkernelmanager")      // EX Kernel Manager
    }

    /**
     * Prefix table for controller families that ship multiple APK variants —
     * mirrors [KnownGames.PREFIX_TABLE]. A foreground package that STARTS WITH one
     * of these is treated as a controller.
     */
    private val PREFIXES: List<String> = listOf(
        // Nova / GameNative wrapper family. The foreground package while a PC game
        // runs is the wrapper itself (e.g. "app.gamenative", "app.gamenative.iic"),
        // never the wrapped game — anchor the whole family. This is the SAME family
        // KnownGames anchors for the wrapper-aware workload classifier.
        "app.gamenative",
        // Any Winlator variant / fork (com.winlator.*).
        "com.winlator",
    )

    private fun prefixMatch(pkg: String): String? =
        PREFIXES.firstOrNull { prefix -> pkg.startsWith(prefix) }

    /** Helper to keep the [EXACT] buildSet block terse (mirrors KnownGames.entry). */
    private fun MutableSet<String>.entry(pkg: String) {
        add(pkg)
    }
}
