package io.github.mayusi.calibratesoc.data.gameaware

import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile

/**
 * Known-game classifier.
 *
 * Maps common emulator / game launcher package names to a sensible default
 * [GamePlan] hint.  These hints are STARTING SUGGESTIONS — the user can
 * override any of them by saving a [PerGameRecord] in the per-game store.
 * They are never applied as authoritative settings; the [GameProfileResolver]
 * marks them with [GamePlanSource.KNOWN_GAME_HINT] and [GamePlan.isLearnedGood] = false.
 *
 * ## Classification philosophy
 *
 * The classification is deliberately coarse — we categorise emulators by the
 * workload they typically impose, not by their exact performance requirements:
 *
 *  - **Heavy 3D** (PS2/GCN/Wii/PS3/Switch/Xbox): needs high clocks + good
 *    thermals.  → [AutoTdpProfile.BALANCED] + optional moderate FPS cap to
 *    reduce wasted cycles when frame-rate would exceed the display refresh.
 *
 *  - **2D / handheld** (GBA/DS/3DS/PSP/PS1 2D): CPU-light, GPU-light.
 *    → [AutoTdpProfile.EFFICIENCY] so the big cores park and battery lasts.
 *
 *  - **Native Android / general** (RetroArch, GameNative/Winlator):
 *    workload varies wildly by core/game, so we default to BALANCED.
 *
 * ALL hints have null profileId and null fpsCapHz — we don't know which
 * user profile exists on the device, and a fixed FPS cap that's right for
 * one user's display is wrong for another's.  The user's own record is the
 * right place for those.
 *
 * ## Honesty: no silent overrides
 *
 * This file is a lookup table only.  It does not write any sysfs node, it
 * does not reach for any Android context, and it does not apply anything.
 * The service layer makes ALL decisions about whether and how to act on
 * the returned hint.
 */
object KnownGames {

    /**
     * Return a default hint [GamePlan] for [packageName], or null when the
     * package is not in the known-games table.
     *
     * The returned [GamePlan] has:
     *  - [GamePlan.source] == [GamePlanSource.KNOWN_GAME_HINT]
     *  - [GamePlan.isLearnedGood] == false
     *  - [GamePlan.profileId] == null (we cannot guess which profile the user saved)
     *  - [GamePlan.fpsCapHz] == null (display-rate-dependent; user must set)
     *
     * Callers must treat this as advisory.
     */
    fun defaultHintFor(packageName: String): GamePlan? {
        val entry = TABLE[packageName]
            ?: prefixMatch(packageName)
            ?: return null
        return GamePlan(
            packageName = packageName,
            profileId = null,
            autoTdpProfile = entry.autoTdpProfile,
            fpsCapHz = null,
            isLearnedGood = false,
            source = GamePlanSource.KNOWN_GAME_HINT,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal table
    // ─────────────────────────────────────────────────────────────────────────

    /** Lightweight hint entry — only what the classifier decides. */
    private data class Hint(
        val label: String,             // human-readable name (for debug/logging)
        val autoTdpProfile: AutoTdpProfile,
        val tier: EmulationTier,
    )

    /** Coarse workload tier for documentation / future use. */
    enum class EmulationTier {
        /** Heavy 3D emulation: PS2, GCN, Wii, PS3, Switch, Xbox, Cemu. */
        HEAVY_3D,
        /** 2D / handheld: GBA, GBC, SNES, DS, 3DS, PSP, PS1, Saturn. */
        HANDHELD_2D,
        /** Native Android games or general game launchers. */
        NATIVE_OR_GENERAL,
        /** Translation / compatibility layer (Winlator, GameNative). */
        TRANSLATION_LAYER,
    }

    // ── PPSSPP — PSP (2D/3D mix but PSP is relatively light) ─────────────────
    private val TABLE: Map<String, Hint> = buildMap {

        // PSP — moderate workload; EFFICIENCY works for most games.
        entry("org.ppsspp.ppsspp",              "PPSSPP",           AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)
        entry("org.ppsspp.ppssppgold",          "PPSSPP Gold",      AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)

        // GameCube / Wii — heavy 3D; needs headroom.
        entry("org.dolphinemu.dolphinemu",       "Dolphin",          AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        // Dolphin MMJR / MMJR2 forks used on handhelds:
        entry("com.dolphin.mmjr",               "Dolphin MMJR",     AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("com.dolphin.mmjr2",              "Dolphin MMJR2",    AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // PS2 (AetherSX2 + its successors)
        entry("xyz.aethersx2.android",          "AetherSX2",        AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        // NetherSX2 (AetherSX2 community fork):
        entry("xyz.nethersx2.android",          "NetherSX2",        AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // 3DS — Citra (archived) and Azahar (active fork)
        entry("org.citra.citra_emu",            "Citra",            AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)
        entry("org.citra.citra_canary",         "Citra Canary",     AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)
        entry("com.azahar.emulator",            "Azahar (Citra fork)", AutoTdpProfile.EFFICIENCY, EmulationTier.HANDHELD_2D)

        // RetroArch — covers many cores; workload is core-dependent.
        // Default BALANCED; the user should fine-tune per-core if needed.
        entry("com.retroarch",                  "RetroArch",        AutoTdpProfile.BALANCED,    EmulationTier.NATIVE_OR_GENERAL)
        entry("com.retroarch.ra32",             "RetroArch (32-bit)", AutoTdpProfile.BALANCED,  EmulationTier.NATIVE_OR_GENERAL)

        // Winlator — Windows x86 translation layer; very heavy.
        entry("com.winlator",                   "Winlator",         AutoTdpProfile.BALANCED,    EmulationTier.TRANSLATION_LAYER)
        // GameNative (formerly Winlator fork by Termux-team)
        entry("com.gamenative",                 "GameNative",       AutoTdpProfile.BALANCED,    EmulationTier.TRANSLATION_LAYER)
        // ExaGear / ExaGear RPG (older x86 layers still in use)
        entry("com.eltechs.exagear",            "ExaGear",          AutoTdpProfile.BALANCED,    EmulationTier.TRANSLATION_LAYER)

        // Switch — Yuzu / Eden (Yuzu successor)
        entry("org.yuzu.yuzu_emu",              "Yuzu",             AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("org.yuzu.yuzu_emu.ea",           "Yuzu EA",          AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("dev.eden.eden_emu",              "Eden",             AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("com.sudachi.sudachi_emu",        "Sudachi",          AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("com.sudachi.sudachi_emu.preview","Sudachi Preview",  AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        // Citron (another Yuzu fork):
        entry("io.github.citron_emu.citron",   "Citron",           AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // DS — melonDS
        entry("me.magnum.melonds",              "melonDS",          AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)

        // PS1 — DuckStation
        entry("com.github.stenzek.duckstation","DuckStation",      AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)

        // Dreamcast / NAOMI — Flycast
        entry("com.flycast.emulator",           "Flycast",          AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        entry("com.flycast.emulator.gles",      "Flycast GLES",     AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // Wii U — Cemu
        entry("info.cemu.Cemu",                 "Cemu",             AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // PS3 — RPCS3 (Android alpha; extremely heavy)
        entry("net.rpcs3.rpcs3",                "RPCS3",            AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
        // aps3e (PS3 emulator by AetherSX2 dev)
        entry("xyz.aps3e.android",              "aps3e",            AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)

        // MAME / FBNeo via standalone apps
        entry("com.fbalpha4android",            "FB Alpha 4 Android", AutoTdpProfile.EFFICIENCY, EmulationTier.HANDHELD_2D)
        entry("com.mahbox.mame4all",            "MAME4ALL",         AutoTdpProfile.EFFICIENCY,  EmulationTier.HANDHELD_2D)

        // N64 — M64Plus (mupen64plus Android frontend)
        entry("org.mupen64plusae.bonuspack.gliden64plus", "M64Plus FZ", AutoTdpProfile.EFFICIENCY, EmulationTier.HANDHELD_2D)
        entry("paulscode.android.mupen64plusae", "Mupen64Plus AE", AutoTdpProfile.EFFICIENCY,   EmulationTier.HANDHELD_2D)

        // Xbox (OG / 360) — Xemu Android / Xenia (not yet on Android, placeholder)
        // Xemu has an unofficial Android port circulating:
        entry("org.xemu.app",                  "Xemu",             AutoTdpProfile.BALANCED,    EmulationTier.HEAVY_3D)
    }

    // ── Prefix table for emulator families that ship multiple APK variants ────
    // Used when exact package match fails.
    private val PREFIX_TABLE: List<Pair<String, Hint>> = listOf(
        // Any Dolphin variant not in the exact table:
        "org.dolphinemu" to Hint("Dolphin (variant)", AutoTdpProfile.BALANCED, EmulationTier.HEAVY_3D),
        // Any PPSSPP variant:
        "org.ppsspp"     to Hint("PPSSPP (variant)",  AutoTdpProfile.EFFICIENCY, EmulationTier.HANDHELD_2D),
        // Any Yuzu/Sudachi/Eden variant:
        "org.yuzu"       to Hint("Yuzu (variant)",    AutoTdpProfile.BALANCED, EmulationTier.HEAVY_3D),
        "com.sudachi"    to Hint("Sudachi (variant)", AutoTdpProfile.BALANCED, EmulationTier.HEAVY_3D),
        // Any Citra/Azahar variant:
        "org.citra"      to Hint("Citra (variant)",   AutoTdpProfile.EFFICIENCY, EmulationTier.HANDHELD_2D),
        // ── Windows translation-layer wrappers (GUARDRAIL 4) ──────────────────────
        // GameNative/IIC runs the game in a Wine wrapper whose FOREGROUND package is
        // the wrapper itself (e.g. "app.gamenative.iic"), NOT the wrapped game. Without
        // a prefix entry the classifier could not anchor it to a game, AUTO fell to
        // BATTERY_SAVER on a low/null-GPU tick, and the cap collapsed (DEFECT A trigger
        // #1). Both the new "app.gamenative" family and ANY "com.winlator" variant are
        // heavy translation layers — anchor them so the foreground/paused-game guard works.
        "app.gamenative" to Hint("GameNative (wrapper)", AutoTdpProfile.BALANCED, EmulationTier.TRANSLATION_LAYER),
        "com.winlator"   to Hint("Winlator (variant)",   AutoTdpProfile.BALANCED, EmulationTier.TRANSLATION_LAYER),
    )

    private fun prefixMatch(packageName: String): Hint? =
        PREFIX_TABLE.firstOrNull { (prefix, _) -> packageName.startsWith(prefix) }?.second

    // Helper to make the buildMap block less verbose:
    private fun MutableMap<String, Hint>.entry(
        pkg: String,
        label: String,
        profile: AutoTdpProfile,
        tier: EmulationTier,
    ) {
        put(pkg, Hint(label, profile, tier))
    }
}
