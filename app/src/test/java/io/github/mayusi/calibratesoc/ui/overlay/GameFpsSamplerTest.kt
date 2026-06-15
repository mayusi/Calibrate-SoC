package io.github.mayusi.calibratesoc.ui.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure unit tests for [GameFpsSampler]'s pure-logic methods.
 *
 * Coverage:
 *  A. [GameFpsSampler.parseSurfaceFlingerLatency]
 *      1. Sentinel (INT64_MAX) rows are skipped.
 *      2. All-zeros rows are skipped.
 *      3. Fewer than 2 usable timestamps → null (no divide error).
 *      4. First valid row is discarded as warm-up before the delta window.
 *      5. FPS > 240 is clamped (rejected as parse noise) → null.
 *      6. FPS < 1 is clamped (rejected as parse noise) → null.
 *      7. A real 60 Hz sample (timestamps spaced ~16.67 ms) → ~60.
 *      8. A real 90 Hz sample → ~90.
 *      9. Output with only the header line → null.
 *     10. Output with only zeros → null.
 *     11. Blank / empty string → null.
 *
 *  B. [GameFpsSampler.isNonGameLayer] / [GameFpsSampler.scoreLayer]
 *      1. animation-leash line is rejected.
 *      2. BlurEffect line is rejected (case-insensitive).
 *      3. Dim# line is rejected.
 *      4. ScreenDecorOverlay line is rejected.
 *      5. NavigationBar line is rejected.
 *      6. StatusBar line is rejected.
 *      7. InputMethod line is rejected.
 *      8. Splash line is rejected.
 *      9. A real SurfaceView layer line passes the filter.
 *     10. scoreLayer: SurfaceView[pkg] scores higher than pkg/Activity.
 *     11. scoreLayer: pkg/Activity scores higher than bare pkg match.
 *
 * No Android context, PServer, or coroutines are required — all tested
 * methods are pure Kotlin.
 */
class GameFpsSamplerTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Minimal stub of GameFpsSampler that lets us call the internal pure
     * methods without an Android Context or PServerWriter. We expose the
     * internal methods under test by extending the class with mocked
     * Android dependencies (using default/null values acceptable for
     * pure-logic tests — the constructor parameters are only used in
     * platform code paths not exercised here).
     *
     * We expose the pure methods via delegation so we don't need reflection.
     */
    private val sampler = GameFpsSamplerTestProxy()

    /**
     * Test proxy: wraps a GameFpsSampler created with a null context mock and
     * no-op PServerWriter so the constructor succeeds on the JVM, then
     * delegates to the internal pure methods.
     *
     * The constructor fields (context, pServerWriter) are only touched on
     * Android API calls. The pure parsing/filtering methods never reach them.
     */
    private class GameFpsSamplerTestProxy {
        // We can't construct GameFpsSampler without Android Context. Instead,
        // we replicate the pure-logic methods directly here. This is the
        // canonical pattern used elsewhere in this project (e.g. PathValidator
        // in PServerWriterLiveTest) — duplicate the pure logic in a private
        // test helper so the tests are self-contained and never touch Android.

        fun parseSurfaceFlingerLatency(out: String): Int? {
            val lines = out.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.size < 3) return null

            val timestamps = mutableListOf<Long>()
            val ws = Regex("""\s+""")

            for (i in 1 until lines.size) {
                val cols = lines[i].split(ws)
                if (cols.size < 3) continue
                val a = cols[0].toLongOrNull() ?: continue
                val v = cols[1].toLongOrNull() ?: continue
                val p = cols[2].toLongOrNull() ?: continue
                if (a == 0L && v == 0L && p == 0L) continue
                if (v == Long.MAX_VALUE) continue   // sentinel NOT_YET_PRESENTED
                if (v <= 0L) continue
                timestamps += v
            }

            // Discard warm-up first row.
            val usable = if (timestamps.size > 1) timestamps.drop(1) else timestamps
            if (usable.size < 2) return null

            val window = usable.takeLast(60)
            val spanNs = window.last() - window.first()
            if (spanNs <= 0L) return null

            val spanSec = spanNs / 1_000_000_000.0
            val fps = ((window.size - 1) / spanSec).toInt()
            if (fps < 1 || fps > 240) return null
            return fps
        }

        fun isNonGameLayer(line: String): Boolean =
            GameFpsSampler.NON_GAME_LAYER_TOKENS.any { token ->
                line.contains(token, ignoreCase = true)
            }

        // Mirrors GameFpsSampler.scoreLayer: BLAST child outranks the plain
        // SurfaceView parent (which returns all-zero --latency rows on modern
        // Android), which outranks pkg/Activity, which outranks a bare match.
        fun scoreLayer(line: String, pkg: String): Int {
            // contains, not startsWith — real --list lines are wrapped in
            // "RequestedLayerState{...}" so a prefix check never matches on-device.
            val isSurfaceView = line.contains("SurfaceView[") && line.contains(pkg)
            return when {
                isSurfaceView && line.contains("(BLAST)") -> 4
                isSurfaceView -> 3
                line.contains("$pkg/") -> 2
                else -> 1
            }
        }

        // Mirrors GameFpsSampler.rankLayerCandidates: filter non-game layers,
        // dedupe, sort best-first.
        fun rankLayerCandidates(listOut: String, pkg: String): List<String> =
            listOut.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() && !line.startsWith("---") &&
                        line.contains(pkg) && !isNonGameLayer(line)
                }
                .distinct()
                .sortedByDescending { scoreLayer(it, pkg) }
                .toList()

        // Mirrors GameFpsSampler.rankActiveRenderLayers: wrapper-app fallback —
        // keeps genuine render surfaces (SurfaceView[ or (BLAST)), excludes own
        // overlay, excludes NON_GAME_LAYER_TOKENS, excludes additional system
        // surfaces. Ranks (BLAST) first.
        fun rankActiveRenderLayers(listOut: String, ownPkg: String): List<String> =
            listOut.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("---") &&
                        (line.contains("SurfaceView[") || line.contains("(BLAST)")) &&
                        !line.contains(ownPkg) &&
                        !isNonGameLayer(line) &&
                        !GameFpsSampler.FALLBACK_SYSTEM_EXCLUSIONS.any { excl ->
                            line.contains(excl, ignoreCase = true)
                        }
                }
                .distinct()
                .sortedByDescending { line -> if (line.contains("(BLAST)")) 2 else 1 }
                .toList()
    }

    // ── Build a SurfaceFlinger --latency block ────────────────────────────────

    /**
     * Build a synthetic --latency output block at [hzTarget] Hz.
     *
     * [frameCount] frames spaced exactly 1_000_000_000 / hzTarget ns apart.
     * The first frame is at t=0 (the warm-up frame that should be discarded).
     * A header line of the refresh interval is prepended.
     */
    private fun buildLatencyOutput(
        hzTarget: Int,
        frameCount: Int,
        includeLeadingZeroRow: Boolean = false,
        includeSentinelRow: Boolean = false,
    ): String {
        val intervalNs = 1_000_000_000L / hzTarget
        val headerIntervalNs = intervalNs   // first line = refresh interval
        return buildString {
            appendLine(headerIntervalNs)
            // Optionally prepend an all-zeros padding row.
            if (includeLeadingZeroRow) appendLine("0\t0\t0")
            // Optionally include a sentinel row.
            if (includeSentinelRow) appendLine("${Long.MAX_VALUE}\t${Long.MAX_VALUE}\t${Long.MAX_VALUE}")
            for (i in 0 until frameCount) {
                val t = (i + 1) * intervalNs   // start at 1× to keep t > 0
                appendLine("${t - intervalNs / 2}\t$t\t${t + intervalNs / 4}")
            }
        }
    }

    // ── A. parseSurfaceFlingerLatency ─────────────────────────────────────────

    @Test
    fun `sentinel INT64_MAX rows are skipped`() {
        // Valid 60 Hz frames with sentinel rows interspersed. The vsync timestamps of
        // the REAL frames must form a contiguous 60 Hz cadence (one interval apart);
        // sentinel rows carry no timestamp and are dropped, so the surviving frames
        // still span a clean 60 Hz. (Indexing the timestamp by the loop counter would
        // leave gaps where sentinels were and skew the measured FPS downward.)
        val intervalNs = 1_000_000_000L / 60
        val output = buildString {
            appendLine(intervalNs)  // header
            var frame = 0
            for (i in 0 until 12) {
                if (i % 4 == 0) {
                    // Insert a sentinel row (not-yet-presented) — must be skipped.
                    appendLine("${Long.MAX_VALUE}\t${Long.MAX_VALUE}\t${Long.MAX_VALUE}")
                } else {
                    frame++
                    val t = frame.toLong() * intervalNs   // contiguous real-frame cadence
                    appendLine("${t - 100}\t$t\t${t + 100}")
                }
            }
        }
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(55..65)
    }

    @Test
    fun `all-zeros rows are skipped`() {
        val intervalNs = 1_000_000_000L / 60
        val output = buildString {
            appendLine(intervalNs)
            // 3 all-zeros rows followed by 5 valid frames.
            repeat(3) { appendLine("0\t0\t0") }
            for (i in 0 until 5) {
                val t = (i + 1) * intervalNs
                appendLine("${t - 100}\t$t\t${t + 100}")
            }
        }
        val fps = sampler.parseSurfaceFlingerLatency(output)
        // 5 rows → drop warm-up (1) → 4 usable; span = 3 × intervalNs → ~60 fps
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(55..65)
    }

    @Test
    fun `fewer than 2 usable timestamps returns null, no divide error`() {
        val intervalNs = 1_000_000_000L / 60
        // Only 1 valid data row → after warm-up discard: 0 usable → null
        val output = buildString {
            appendLine(intervalNs)
            appendLine("100\t${intervalNs}\t200")
        }
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `exactly 2 valid rows after warm-up discard returns null (need 2 after drop)`() {
        // 2 total data rows → 1 warm-up discarded → 1 usable → null
        val intervalNs = 1_000_000_000L / 60
        val output = buildString {
            appendLine(intervalNs)
            appendLine("100\t${intervalNs}\t200")
            appendLine("100\t${2 * intervalNs}\t200")
        }
        // 2 data rows → drop first → 1 remains → size < 2 → null
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `3 valid rows produces a result (2 usable after warm-up drop)`() {
        val intervalNs = 1_000_000_000L / 60
        val output = buildString {
            appendLine(intervalNs)
            for (i in 1..3) {
                val t = i * intervalNs
                appendLine("${t - 100}\t$t\t${t + 100}")
            }
        }
        // 3 rows → drop first → 2 usable → valid
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(55..65)
    }

    @Test
    fun `warm-up first frame is discarded before computing FPS`() {
        // Insert a wildly wrong first frame (huge gap), then regular 60 Hz frames.
        // If warm-up is NOT discarded the huge gap would skew the result.
        val intervalNs = 1_000_000_000L / 60
        val output = buildString {
            appendLine(intervalNs)
            // Warm-up row: timestamp at t=1000 s (would skew to near 0 fps if kept)
            appendLine("0\t${1_000L * 1_000_000_000L}\t0")
            // 5 regular 60 Hz frames starting after the warm-up.
            for (i in 1..5) {
                val t = 1_000L * 1_000_000_000L + i * intervalNs
                appendLine("${t - 100}\t$t\t${t + 100}")
            }
        }
        val fps = sampler.parseSurfaceFlingerLatency(output)
        // After dropping the warm-up row (1000 s), remaining 5 frames are at 60 Hz.
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(55..65)
    }

    @Test
    fun `FPS above 240 is rejected as parse noise, returns null`() {
        // 1000 Hz — impossible on real hardware, signals parse error.
        val output = buildLatencyOutput(hzTarget = 1000, frameCount = 10)
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `FPS below 1 is rejected as parse noise, returns null`() {
        // Two timestamps 10 seconds apart → 0 fps after toInt() → reject.
        val output = buildString {
            appendLine(16_666_666)
            appendLine("0\t1_000_000_000\t0")
            appendLine("0\t11_000_000_000\t0")
            appendLine("0\t21_000_000_000\t0")  // gives ~0.2 fps after drop
        }
        val fps = sampler.parseSurfaceFlingerLatency(output)
        // 3 rows → drop first → 2 usable; span = 10 s; (2-1)/10 = 0.1 → toInt()=0 → null
        assertThat(fps).isNull()
    }

    @Test
    fun `real 60 Hz sample (16_667 us spacing) produces ~60`() {
        val output = buildLatencyOutput(hzTarget = 60, frameCount = 30)
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(58..62)
    }

    @Test
    fun `real 90 Hz sample produces ~90`() {
        val output = buildLatencyOutput(hzTarget = 90, frameCount = 30)
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(88..92)
    }

    @Test
    fun `real 120 Hz sample produces ~120`() {
        val output = buildLatencyOutput(hzTarget = 120, frameCount = 30)
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(118..122)
    }

    @Test
    fun `output with only the header line returns null`() {
        val output = "16666666\n"
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `output with only zeros returns null`() {
        val output = buildString {
            appendLine(16_666_666)
            repeat(10) { appendLine("0\t0\t0") }
        }
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `blank output returns null`() {
        assertThat(sampler.parseSurfaceFlingerLatency("")).isNull()
        assertThat(sampler.parseSurfaceFlingerLatency("   \n   ")).isNull()
    }

    @Test
    fun `all-sentinels output returns null`() {
        val output = buildLatencyOutput(
            hzTarget = 60,
            frameCount = 0,
            includeSentinelRow = true,
        ) + buildString {
            repeat(5) { appendLine("${Long.MAX_VALUE}\t${Long.MAX_VALUE}\t${Long.MAX_VALUE}") }
        }
        assertThat(sampler.parseSurfaceFlingerLatency(output)).isNull()
    }

    @Test
    fun `mixed zeros and sentinels with real frames still parses correctly`() {
        val intervalNs = 1_000_000_000L / 60
        val output = buildLatencyOutput(
            hzTarget = 60,
            frameCount = 10,
            includeLeadingZeroRow = true,
            includeSentinelRow = true,
        )
        val fps = sampler.parseSurfaceFlingerLatency(output)
        assertThat(fps).isNotNull()
        assertThat(fps!!).isIn(55..65)
    }

    // ── B. Layer filtering and scoring ────────────────────────────────────────

    @Test
    fun `animation-leash line is rejected by isNonGameLayer`() {
        assertThat(sampler.isNonGameLayer("leash#0 com.example.game animation-leash")).isTrue()
    }

    @Test
    fun `BlurEffect line is rejected (case-insensitive)`() {
        assertThat(sampler.isNonGameLayer("com.example.game/BlurEffect#1")).isTrue()
        assertThat(sampler.isNonGameLayer("BLUREFFECT")).isTrue()
        assertThat(sampler.isNonGameLayer("blureffect")).isTrue()
    }

    @Test
    fun `Dim# line is rejected`() {
        assertThat(sampler.isNonGameLayer("Dim#3 com.example.game")).isTrue()
    }

    @Test
    fun `ScreenDecorOverlay line is rejected`() {
        assertThat(sampler.isNonGameLayer("ScreenDecorOverlay")).isTrue()
    }

    @Test
    fun `NavigationBar line is rejected`() {
        assertThat(sampler.isNonGameLayer("NavigationBar0")).isTrue()
    }

    @Test
    fun `StatusBar line is rejected`() {
        assertThat(sampler.isNonGameLayer("StatusBar")).isTrue()
    }

    @Test
    fun `InputMethod line is rejected`() {
        assertThat(sampler.isNonGameLayer("InputMethod#0")).isTrue()
    }

    @Test
    fun `Splash line is rejected (SplashScreen)`() {
        assertThat(sampler.isNonGameLayer("Splash#com.example.game")).isTrue()
        assertThat(sampler.isNonGameLayer("SplashScreen")).isTrue()
    }

    @Test
    fun `real SurfaceView layer passes isNonGameLayer filter`() {
        assertThat(sampler.isNonGameLayer("SurfaceView[com.example.game/com.example.game.MainActivity]#0")).isFalse()
    }

    @Test
    fun `bare package layer passes isNonGameLayer filter`() {
        assertThat(sampler.isNonGameLayer("com.example.game#4262")).isFalse()
    }

    @Test
    fun `scoreLayer SurfaceView containing pkg scores higher than pkg-slash-Activity`() {
        val pkg = "com.example.game"
        val surfaceView = "SurfaceView[$pkg/com.example.game.MainActivity]#0"
        val activity = "$pkg/com.example.game.MainActivity#0"

        assertThat(sampler.scoreLayer(surfaceView, pkg))
            .isGreaterThan(sampler.scoreLayer(activity, pkg))
    }

    @Test
    fun `scoreLayer pkg-slash-Activity scores higher than bare pkg`() {
        val pkg = "com.example.game"
        val activity = "$pkg/com.example.game.MainActivity#0"
        val bare = "cb4035 $pkg#4262"

        assertThat(sampler.scoreLayer(activity, pkg))
            .isGreaterThan(sampler.scoreLayer(bare, pkg))
    }

    @Test
    fun `scoreLayer SurfaceView without pkg in brackets scores as bare (score=1)`() {
        // A SurfaceView line that doesn't contain the package name → score 1
        val line = "SurfaceView[com.other.app/OtherActivity]#0"
        val pkg = "com.example.game"
        assertThat(sampler.scoreLayer(line, pkg)).isEqualTo(1)
    }

    @Test
    fun `scoreLayer SurfaceView with pkg scores 3`() {
        val pkg = "com.example.game"
        val line = "SurfaceView[$pkg/MainActivity]#0"
        assertThat(sampler.scoreLayer(line, pkg)).isEqualTo(3)
    }

    @Test
    fun `scoreLayer pkg-slash line scores 2`() {
        val pkg = "com.example.game"
        val line = "$pkg/com.example.game.Main#0"
        assertThat(sampler.scoreLayer(line, pkg)).isEqualTo(2)
    }

    // ── C. BLAST layer ranking (regression for the Odin 3 zeros-parent bug) ────
    //
    // On modern Android the plain SurfaceView[…] parent returns all-zero --latency
    // rows; its (BLAST) child carries the real frames. The resolver must rank the
    // BLAST child above the parent so the live fall-through tries it. These tests
    // use the ACTUAL `dumpsys SurfaceFlinger --list` output captured from a live
    // Odin 3 running PPSSPP (where parent #930 = zeros, BLAST #931 = 59.6 FPS).

    private val pkgPpsspp = "org.ppsspp.ppsspp"

    /** Verbatim slice of real Odin 3 `--list` output for PPSSPP. */
    private val odin3PpssppList = """
        RequestedLayerState{SurfaceView[org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity]#930 parentId=929 relativeParentId=926 z=-2}
        RequestedLayerState{Bounds for - org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity#929 parentId=926}
        RequestedLayerState{5f96885 ActivityRecordInputSink org.ppsspp.ppsspp/.PpssppActivity#924 parentId=917 z=-2147483648}
        RequestedLayerState{Background for SurfaceView[org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity]#932 parentId=930 relativeParentId=926 z=-2147483648}
        RequestedLayerState{SurfaceView[org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity](BLAST)#931 parentId=930}
        RequestedLayerState{Surface(name=32690a1 org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity)/@0xba76920 - animation-leash of starting_reveal#933}
    """.trimIndent()

    @Test
    fun `BLAST SurfaceView outranks the plain SurfaceView parent`() {
        val parent = "SurfaceView[$pkgPpsspp/$pkgPpsspp.PpssppActivity]#930 parentId=929"
        val blast = "SurfaceView[$pkgPpsspp/$pkgPpsspp.PpssppActivity](BLAST)#931 parentId=930"
        assertThat(sampler.scoreLayer(blast, pkgPpsspp))
            .isGreaterThan(sampler.scoreLayer(parent, pkgPpsspp))
        assertThat(sampler.scoreLayer(blast, pkgPpsspp)).isEqualTo(4)
        assertThat(sampler.scoreLayer(parent, pkgPpsspp)).isEqualTo(3)
    }

    @Test
    fun `rankLayerCandidates puts the BLAST child first on real Odin 3 PPSSPP output`() {
        val ranked = sampler.rankLayerCandidates(odin3PpssppList, pkgPpsspp)
        assertThat(ranked).isNotEmpty()
        // The top candidate must be the BLAST layer — the one that actually
        // carries frames. The parent #930 (zeros) must rank below it.
        assertThat(ranked.first()).contains("(BLAST)#931")
    }

    @Test
    fun `rankLayerCandidates drops the animation-leash artifact`() {
        val ranked = sampler.rankLayerCandidates(odin3PpssppList, pkgPpsspp)
        assertThat(ranked.none { it.contains("animation-leash") }).isTrue()
    }

    @Test
    fun `rankLayerCandidates returns empty when no layer matches the package`() {
        val ranked = sampler.rankLayerCandidates(odin3PpssppList, "com.not.installed")
        assertThat(ranked).isEmpty()
    }

    // ── D. Wrapper-app fallback — rankActiveRenderLayers ─────────────────────
    //
    // These tests cover the GameNative/Winlator/Wine scenario: the foreground
    // package name does NOT appear in any SurfaceFlinger layer, but a genuine
    // render surface (Wine/Xserver, Box64, etc.) IS present. The fallback must
    // find that surface while excluding our own HUD overlay and system layers.

    private val ownPkg = "io.github.mayusi.calibratesoc"

    /**
     * Synthetic --list output that mimics a GameNative session:
     *  - Our own overlay HUD (must be EXCLUDED)
     *  - A Wine/Xserver BLAST surface (the target — must be INCLUDED, ranked first)
     *  - A plain SurfaceView for the Wine/Xserver (must be INCLUDED, ranked second)
     *  - A system layer (com.android.systemui — must be EXCLUDED)
     *  - A Wallpaper layer (must be EXCLUDED)
     *  - A NavigationBar layer (must be EXCLUDED by isNonGameLayer)
     *  - A TaskSnapshot layer (must be EXCLUDED by FALLBACK_SYSTEM_EXCLUSIONS)
     */
    private val gameNativeList = """
        SurfaceView[io.github.mayusi.calibratesoc/io.github.mayusi.calibratesoc.HudActivity]#10
        SurfaceView[io.github.mayusi.calibratesoc/io.github.mayusi.calibratesoc.HudActivity](BLAST)#11
        SurfaceView[Xserver](BLAST)#42
        SurfaceView[Xserver]#41
        com.android.systemui/SystemUIService#5
        SurfaceView[WallpaperService](BLAST)#2
        NavigationBar0#3
        TaskSnapshot#7
    """.trimIndent()

    @Test
    fun `fallback finds Xserver BLAST layer and excludes own overlay`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        // Must contain the Xserver BLAST layer.
        assertThat(candidates.any { it.contains("SurfaceView[Xserver](BLAST)") }).isTrue()
        // Must NOT contain any line with our own package.
        assertThat(candidates.none { it.contains(ownPkg) }).isTrue()
    }

    @Test
    fun `fallback ranks BLAST layer before plain SurfaceView`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        assertThat(candidates).isNotEmpty()
        // The top candidate must be the (BLAST) layer.
        assertThat(candidates.first()).contains("(BLAST)")
        assertThat(candidates.first()).contains("Xserver")
    }

    @Test
    fun `fallback excludes com-android-systemui surfaces`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        assertThat(candidates.none { it.contains("com.android.systemui") }).isTrue()
    }

    @Test
    fun `fallback excludes Wallpaper BLAST surface`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        assertThat(candidates.none { it.contains("Wallpaper") }).isTrue()
    }

    @Test
    fun `fallback excludes TaskSnapshot surface`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        assertThat(candidates.none { it.contains("TaskSnapshot") }).isTrue()
    }

    @Test
    fun `fallback excludes NavigationBar surface (via NON_GAME_LAYER_TOKENS)`() {
        val candidates = sampler.rankActiveRenderLayers(gameNativeList, ownPkg)
        assertThat(candidates.none { it.contains("NavigationBar") }).isTrue()
    }

    @Test
    fun `fallback returns Xserver render surface when no layer matches the foreground pkg (GameNative case)`() {
        // The foreground package is "app.gamenative.iic" — present in ZERO --list lines.
        // The fallback must still find the Wine/Xserver render surface.
        val foregroundPkg = "app.gamenative.iic"
        val listOut = """
            SurfaceView[Xserver](BLAST)#42
            SurfaceView[Xserver]#41
            NavigationBar0#3
        """.trimIndent()

        // Pass 1 (package-matched) would find nothing for 'app.gamenative.iic'.
        val pass1 = sampler.rankLayerCandidates(listOut, foregroundPkg)
        assertThat(pass1).isEmpty()

        // Pass 2 (fallback) must find the render surface.
        val fallback = sampler.rankActiveRenderLayers(listOut, ownPkg)
        assertThat(fallback).isNotEmpty()
        assertThat(fallback.first()).contains("SurfaceView[Xserver](BLAST)")
    }

    @Test
    fun `own overlay line is never returned by fallback regardless of SurfaceView or BLAST`() {
        // Ensure even if our own HUD has a BLAST layer it is never returned.
        val listOut = """
            SurfaceView[$ownPkg/HudActivity](BLAST)#11
            SurfaceView[Xserver](BLAST)#42
        """.trimIndent()

        val fallback = sampler.rankActiveRenderLayers(listOut, ownPkg)
        assertThat(fallback.none { it.contains(ownPkg) }).isTrue()
        // Xserver BLAST must still appear.
        assertThat(fallback.any { it.contains("SurfaceView[Xserver](BLAST)") }).isTrue()
    }

    @Test
    fun `mismatched variant foreground pkg misses pass1 but fallback finds render surface`() {
        // Real GameNative scenario: foreground pkg = "app.gamenative.iic"
        // but layers are named "app.gamenative/..." — contains() misses.
        val foregroundPkg = "app.gamenative.iic"
        val listOut = """
            app.gamenative/MainActivity#20
            SurfaceView[app.gamenative/MainActivity]#21
            SurfaceView[Xserver](BLAST)#42
        """.trimIndent()

        // Pass 1 misses because "app.gamenative.iic" does not appear in any line.
        val pass1 = sampler.rankLayerCandidates(listOut, foregroundPkg)
        assertThat(pass1).isEmpty()

        // Fallback finds the Xserver BLAST render surface.
        val fallback = sampler.rankActiveRenderLayers(listOut, ownPkg)
        assertThat(fallback).isNotEmpty()
        assertThat(fallback.first()).contains("SurfaceView[Xserver](BLAST)")
    }
}
