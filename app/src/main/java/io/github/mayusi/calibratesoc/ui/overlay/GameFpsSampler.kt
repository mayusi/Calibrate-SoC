package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.calibratesoc.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-FpsSampler"

/**
 * Per-app FPS sampler using the SurfaceFlinger latency path via PServer.
 *
 * Every 500 ms:
 *  1. Resolve the foreground package (UsageStatsManager).
 *  2. Find its SurfaceFlinger layer name via `dumpsys SurfaceFlinger --list`,
 *     filtering out known non-game layers and preferring SurfaceView layers.
 *  3. Read frame timestamps via `dumpsys SurfaceFlinger --latency '<layer>'`
 *     and parse vsync triples → FPS.
 *  4. If step 3 yields 0 frames, invalidate the layer cache (dead layer) and
 *     fall back to Display.getRefreshRate() for this tick.
 *  5. Expose [isRealFps] so the HUD labels the source honestly:
 *     `true` = measured from SurfaceFlinger frames; `false` = fallback refresh rate.
 *
 * All dumpsys/PServer calls run on [Dispatchers.IO] inside the poller coroutine
 * (launched on [Dispatchers.Default]). The main thread is never touched.
 */
@Singleton
class GameFpsSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter,
    /**
     * Root-backed foreground-package reader (Wave 3B). Used as the preferred
     * foreground-package source when PServer is transactable: `dumpsys activity
     * activities | grep mResumedActivity` is always accurate, requires no Android
     * permission on the app UID, and is not subject to the 10-second UsageStats
     * event window. Falls back to [currentForegroundPkg] (UsageStats) when null.
     */
    private val rootForegroundReader: io.github.mayusi.calibratesoc.data.monitor.RootForegroundReader,
) {
    private val _fps = MutableStateFlow<Int?>(null)
    val fps: StateFlow<Int?> = _fps.asStateFlow()

    private val _foregroundPkg = MutableStateFlow<String?>(null)
    val foregroundPkg: StateFlow<String?> = _foregroundPkg.asStateFlow()

    /**
     * True when the current [fps] value was derived from real SurfaceFlinger
     * frame timestamps. False when [fps] is the panel's refresh rate (fallback).
     *
     * The HUD should display "FPS" when this is true, "REFRESH" when false.
     * Using source-of-truth tracking rather than a value-range heuristic avoids
     * mislabeling games that genuinely run at 60 or 120 fps.
     */
    private val _isRealFps = MutableStateFlow(false)
    val isRealFps: StateFlow<Boolean> = _isRealFps.asStateFlow()

    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * Cached resolved SurfaceFlinger layer name per package.
     *
     * Cached for [LAYER_CACHE_TTL_MS] ms to avoid an expensive `dumpsys --list`
     * every 500 ms tick. Invalidated on display-mode change (onDisplayChanged)
     * or when [sampleRealFpsFor] receives 0 valid frames from the cached layer
     * (dead/rotated surface). A null cache forces re-resolution on the next tick.
     */
    private data class LayerCacheEntry(
        val pkg: String,
        val layerName: String,
        val resolvedAtMs: Long,
    )

    @Volatile private var layerCache: LayerCacheEntry? = null

    fun start(scope: CoroutineScope) {
        if (displayListener != null) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return

        // Seed with the current display rate immediately (before the first PServer tick).
        publishCurrentRate(dm)

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    // Display mode change may mean a different frame-rate lock or a
                    // different set of active surfaces. Invalidate the layer cache.
                    layerCache = null
                    publishCurrentRate(dm)
                }
            }
        }
        dm.registerDisplayListener(listener, mainHandler)
        displayListener = listener

        // 500 ms poller — cheap backup for onDisplayChanged which may not fire on
        // every mode switch on every OEM kernel.
        job = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(500)
                // Wave 3B: prefer the root dumpsys source when PServer is live — it is
                // always accurate and requires no Android permission on the app UID.
                // Falls back to the UsageStats path (which needs PACKAGE_USAGE_STATS)
                // when PServer is unavailable. Either way, null means "unknown" and the
                // HUD falls back honestly (no fabricated package name).
                val pkg = rootForegroundReader.readForegroundPkg() ?: currentForegroundPkg()
                _foregroundPkg.value = pkg

                if (pkg == null) {
                    // No foreground game / screen off — fall back and don't spin.
                    publishCurrentRate(dm)
                    _isRealFps.value = false
                    continue
                }

                // Real-game-FPS path via PServer. Falls back to display rate when
                // PServer is unreachable or the layer yields no frame data.
                val realFps = sampleRealFpsFor(pkg)
                if (realFps != null) {
                    _fps.value = realFps
                    _isRealFps.value = true
                } else {
                    publishCurrentRate(dm)
                    _isRealFps.value = false
                }
            }
        }
    }

    /**
     * Pull SurfaceFlinger frame timestamps for the foreground app's layer.
     *
     * Layer resolution uses a two-pass heuristic:
     *  Pass 1 (package-matched):
     *   a. From all layers matching the package name, exclude known non-game
     *      decorators (animation-leash, *BlurEffect*, Dim#, ScreenDecorOverlay,
     *      NavigationBar, StatusBar, InputMethod, *Splash*, etc.).
     *   b. From remaining candidates, prefer SurfaceView[…] layers, then
     *      layers containing the activity class name, then bare package matches.
     *   c. Cache the winner for [LAYER_CACHE_TTL_MS]. On a tick that yields 0
     *      valid frame rows the cache is invalidated so we re-resolve next tick
     *      instead of being stuck on a dead/rotated surface.
     *
     *  Pass 2 (wrapper-app fallback — for GameNative/Winlator/Wine):
     *   When Pass 1 finds no package-matched candidate that carries real frames
     *   (e.g. GameNative renders via a Wine/Xserver surface NOT named after the
     *   host package), a second pass resolves the best ACTIVE rendering layer
     *   regardless of package name via [resolveActiveRenderLayers]. This is
     *   required because:
     *    - At the GameNative menu there is NO SurfaceView layer at all (Activity
     *      windows only). The render surface only appears once a game launches
     *      inside the container, named after Wine/Xserver — not the host package.
     *    - The foreground pkg can be "app.gamenative.iic" while layers are named
     *      "app.gamenative/…" (mismatched variant), causing contains(pkg) to miss.
     *   The first fallback candidate that produces real frames is used and cached
     *   under [pkg] so the next tick uses the fast path.
     *
     * HONESTY: [_isRealFps] is set to true ONLY when this method returns non-null
     * (i.e. parseSurfaceFlingerLatency returned a real value from a real layer,
     * whether package-matched or fallback). The refresh-rate fallback in the
     * polling loop sets [_isRealFps] to false.
     *
     * All I/O runs on [Dispatchers.IO] (the enclosing job is [Dispatchers.Default];
     * [pServerWriter.executeShell] switches to IO internally).
     */
    private suspend fun sampleRealFpsFor(pkg: String): Int? {
        val now = System.currentTimeMillis()
        val cached = layerCache

        // Fast path: a previously-working layer is still warm — try it first.
        if (cached != null && cached.pkg == pkg && (now - cached.resolvedAtMs) < LAYER_CACHE_TTL_MS) {
            val fps = readLatencyFps(cached.layerName)
            if (fps != null) return fps
            // The cached layer went dead/rotated — fall through to a full re-resolve.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "cached layer '${cached.layerName}' for $pkg yielded no frames — re-resolving")
            }
            layerCache = null
        }

        // ── Pass 1: package-matched resolution ───────────────────────────────
        // Resolve ALL candidate layers, best-ranked first, and try each until one
        // produces real frames. This is essential on modern Android: the highest-
        // ranked plain SurfaceView[…] parent often returns all-zero --latency rows,
        // while its (BLAST) child carries the real frames. Trying only the single
        // top candidate (and waiting for the next tick) would loop on the dead
        // parent forever. VERIFIED on Odin 3: PPSSPP parent #930 = zeros, BLAST
        // child #931 = 59.6 FPS.
        val listCmd = "dumpsys SurfaceFlinger --list"
        val (_, listOut) = pServerWriter.executeShell(listCmd) ?: return null

        val ranked = rankLayerCandidates(listOut, pkg)
        for (layer in ranked) {
            val fps = readLatencyFps(layer)
            if (fps != null) {
                // Cache the layer that actually worked, not just the top-ranked one.
                layerCache = LayerCacheEntry(pkg = pkg, layerName = layer, resolvedAtMs = now)
                return fps
            }
        }

        // ── Pass 2: wrapper-app fallback (GameNative / Winlator / Wine) ──────
        // No package-matched layer produced real frames. The foreground app may be
        // a container (GameNative, Winlator) whose inner render surface is named
        // after Wine/Xserver/Box64, not the host package. Resolve the best active
        // rendering surface ignoring package name, excluding our own HUD overlay
        // and known system layers. Use the already-fetched listOut to avoid a
        // second dumpsys --list call.
        val fallbackCandidates = rankActiveRenderLayers(listOut, context.packageName)
        if (BuildConfig.DEBUG && fallbackCandidates.isNotEmpty()) {
            Log.d(TAG, "pkg-match pass yielded no frames for '$pkg' — trying ${fallbackCandidates.size} fallback render layer(s)")
        }
        for (layer in fallbackCandidates) {
            val fps = readLatencyFps(layer)
            if (fps != null) {
                // Cache under pkg so the next tick takes the fast path.
                layerCache = LayerCacheEntry(pkg = pkg, layerName = layer, resolvedAtMs = now)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "wrapper-app fallback: '$layer' → $fps fps for pkg='$pkg'")
                }
                return fps
            }
        }

        return null
    }

    /**
     * Run `--latency` for a single resolved layer and parse it to an FPS, or null
     * when PServer is unreachable or the layer yields no usable frames (e.g. an
     * all-zeros parent container). Pure-ish: the only I/O is the PServer exec.
     */
    private suspend fun readLatencyFps(layerName: String): Int? {
        val safe = layerName.trim().replace("'", "'\\''")
        val latencyCmd = "dumpsys SurfaceFlinger --latency '$safe'"
        val (_, latencyOut) = pServerWriter.executeShell(latencyCmd) ?: return null
        return parseSurfaceFlingerLatency(latencyOut)
    }

    /**
     * Pure ranking of `--list` output → candidate layer names, best-first.
     *
     * Filter strategy (applied in order):
     *  1. Must contain [pkg] anywhere in the line.
     *  2. Exclude known non-game/compositor layers via [isNonGameLayer].
     *  3. Rank remaining candidates (see [scoreLayer]) and return them
     *       best-first so the caller can try each until one yields frames:
     *       SurfaceView (BLAST) → SurfaceView parent → pkg/Activity → bare pkg.
     *
     * Pure (no I/O) so it is directly unit-testable.
     * Returns an empty list when no candidate passes the filter.
     */
    internal fun rankLayerCandidates(listOut: String, pkg: String): List<String> {
        return listOut.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("---") &&
                    line.contains(pkg) &&
                    !isNonGameLayer(line)
            }
            .distinct()
            .sortedByDescending { scoreLayer(it, pkg) }
            .toList()
    }

    /**
     * Pure ranking of `--list` output → active render surface candidates,
     * best-first, ignoring which package owns the layer.
     *
     * Keep rules:
     *  - Line MUST contain "SurfaceView[" OR "(BLAST)" (genuine render surfaces).
     *  - Exclude our own HUD overlay: any line containing [ownPkg]
     *    ("io.github.mayusi.calibratesoc"). CRITICAL: measuring our own HUD
     *    would produce our own refresh rate, not the game's FPS.
     *  - Exclude all [isNonGameLayer] tokens (system compositor layers).
     *  - Exclude additional system layer substrings not in [NON_GAME_LAYER_TOKENS]:
     *    "com.android.systemui", "Wallpaper", "TaskSnapshot", "ScreenDecor",
     *    "pointer", "cursor".
     *
     * Rank: (BLAST) first (score 2), then SurfaceView (score 1), best-first.
     *
     * [ownPkg] is passed as a parameter (not captured from Context) so this
     * method is pure and directly unit-testable without an Android Context.
     *
     * Returns an empty list when no genuine render surface survives the filters.
     */
    internal fun rankActiveRenderLayers(listOut: String, ownPkg: String): List<String> {
        return listOut.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("---") &&
                    // Only genuine render surfaces (not container/compositor layers).
                    (line.contains("SurfaceView[") || line.contains("(BLAST)")) &&
                    // Never measure our own overlay.
                    !line.contains(ownPkg) &&
                    // Exclude standard non-game tokens.
                    !isNonGameLayer(line) &&
                    // Exclude additional system-UI surfaces.
                    !FALLBACK_SYSTEM_EXCLUSIONS.any { excl -> line.contains(excl, ignoreCase = true) }
            }
            .distinct()
            .sortedByDescending { line ->
                // (BLAST) layers carry the actual frames on Android 12+; prefer them.
                if (line.contains("(BLAST)")) 2 else 1
            }
            .toList()
    }

    /**
     * True when the layer line is a known compositor artifact rather than a game surface.
     * Matching is case-insensitive on the distinguishing token.
     */
    internal fun isNonGameLayer(line: String): Boolean {
        return NON_GAME_LAYER_TOKENS.any { token -> line.contains(token, ignoreCase = true) }
    }

    /**
     * Score a candidate layer line for selection preference (higher = better).
     *  4 → SurfaceView **BLAST** layer containing the package — the real frame
     *      source on modern Android (12+/BLAST). The plain SurfaceView[…] parent
     *      is a container that returns all-zero --latency rows; its BLAST child is
     *      where buffers actually present. VERIFIED on Odin 3: parent #930 yielded
     *      all-zeros, BLAST child #931 yielded real 59.6 FPS for PPSSPP.
     *  3 → plain SurfaceView[…] layer containing the package (often the zeros
     *      parent — kept as a fallback below BLAST, ahead of bare matches).
     *  2 → layer containing pkg/… (activity name separator)
     *  1 → bare package match
     */
    internal fun scoreLayer(line: String, pkg: String): Int {
        // Match SurfaceView as a SUBSTRING, not a prefix: real `--list` lines are
        // wrapped (e.g. "RequestedLayerState{SurfaceView[pkg/...](BLAST)#931 ...}"),
        // so startsWith("SurfaceView[") would never match on-device. VERIFIED on
        // Odin 3 — the wrapped form is exactly what PPSSPP produces.
        val isSurfaceView = line.contains("SurfaceView[") && line.contains(pkg)
        return when {
            isSurfaceView && line.contains("(BLAST)") -> 4
            isSurfaceView -> 3
            line.contains("$pkg/") -> 2
            else -> 1
        }
    }

    /**
     * Parse `dumpsys SurfaceFlinger --latency` output into an FPS integer.
     *
     * Output format:
     *   Line 0  : refresh interval in ns (e.g. 16666666 for 60 Hz)
     *   Lines 1+: per-frame triples "desiredPresentTime  actualPresentTime  frameReadyTime"
     *             columns are nanosecond timestamps; column index 1 is vsync.
     *
     * Rules applied:
     *  - Skip rows where any column is 0 (SurfaceFlinger padding rows).
     *  - Skip rows where vsync == [SENTINEL_NOT_YET_PRESENTED] (frame not yet displayed).
     *  - Discard the first valid row (warm-up frame with unreliable delta).
     *  - Require at least 2 remaining timestamps; otherwise return null.
     *  - FPS = (count - 1) / span_seconds using the vsync column.
     *  - Clamp: reject result < 1 or > 240 (parse noise / stuck compositor).
     *
     * This function is pure (no I/O) and therefore directly unit-testable.
     */
    internal fun parseSurfaceFlingerLatency(out: String): Int? {
        val lines = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // Need at least the header line + 2 data lines to compute any delta.
        if (lines.size < 3) return null

        val timestamps = mutableListOf<Long>()

        // Skip index 0 (refresh-interval header). Iterate data rows.
        for (i in 1 until lines.size) {
            val cols = lines[i].split(WHITESPACE)
            if (cols.size < 3) continue

            // All-zeros row: SurfaceFlinger emits these as empty-slot padding.
            val a = cols[0].toLongOrNull() ?: continue
            val v = cols[1].toLongOrNull() ?: continue
            val p = cols[2].toLongOrNull() ?: continue
            if (a == 0L && v == 0L && p == 0L) continue

            // Sentinel: INT64_MAX means "frame not yet presented". Skip.
            if (v == SENTINEL_NOT_YET_PRESENTED) continue
            if (v <= 0L) continue

            timestamps += v
        }

        // Discard the warm-up first frame (its preceding delta is meaningless).
        val usable = if (timestamps.size > 1) timestamps.drop(1) else timestamps
        if (usable.size < 2) return null

        // Use the most recent 60 frames to be responsive to rate changes.
        val window = usable.takeLast(60)

        val spanNs = window.last() - window.first()
        if (spanNs <= 0L) return null

        val spanSec = spanNs / 1_000_000_000.0
        val fps = ((window.size - 1) / spanSec).toInt()

        // Clamp absurd values: anything outside 1–240 is parse noise.
        if (fps < 1 || fps > 240) return null
        return fps
    }

    fun stop() {
        displayListener?.let {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        job?.cancel()
        job = null
        _fps.value = null
        _foregroundPkg.value = null
        _isRealFps.value = false
        layerCache = null
    }

    /**
     * Debug self-test: run the three SurfaceFlinger diagnostic commands for [pkg]
     * and return their concatenated raw stdout. Useful for in-app debug buttons to
     * prove the PServer path works on a given device.
     *
     * Includes BOTH the package-matched candidate probes AND the wrapper-app
     * fallback (active render layer) candidates with their parsed FPS, so the
     * debug output shows exactly which path the live sampler would take.
     *
     * ONLY callable in debug builds — production callers must gate on [BuildConfig.DEBUG].
     * Do NOT call this from the hot 500 ms polling loop.
     */
    suspend fun probeFpsCommandsForDebug(pkg: String): String {
        require(BuildConfig.DEBUG) {
            "probeFpsCommandsForDebug() must only be called in debug builds"
        }
        val sb = StringBuilder()

        val (_, listOut) = pServerWriter.executeShell("dumpsys SurfaceFlinger --list")
            ?: run { sb.appendLine("=== --list: PServer unavailable ==="); return sb.toString() }
        sb.appendLine("=== dumpsys SurfaceFlinger --list (raw) ===")
        sb.appendLine(listOut)

        // ── Pass 1: package-matched candidates ───────────────────────────────
        // Probe EVERY ranked candidate's --latency so the debug output shows which
        // layer actually carries frames (the parent SurfaceView is often all-zeros;
        // its BLAST child carries real frames). This mirrors the live fall-through.
        val ranked = rankLayerCandidates(listOut, pkg)
        sb.appendLine("=== Pass 1 — pkg-matched candidates for '$pkg' (${ranked.size} found) ===")
        if (ranked.isEmpty()) {
            sb.appendLine("(none — package name not found in any non-system layer)")
        } else {
            for (layerName in ranked) {
                val safe = layerName.replace("'", "'\\''")
                val latency = pServerWriter.executeShell("dumpsys SurfaceFlinger --latency '$safe'")
                val parsedFps = latency?.second?.let { parseSurfaceFlingerLatency(it) }
                sb.appendLine("  layer='$layerName' score=${scoreLayer(layerName, pkg)} parsedFps=${parsedFps ?: "none"}")
                sb.appendLine(latency?.second ?: "  (PServer unavailable)")
            }
        }

        // ── Pass 2: wrapper-app fallback candidates ───────────────────────────
        // These are the active render layers that would be tried when Pass 1
        // yields no frames (e.g. GameNative/Winlator — Wine/Xserver surfaces).
        val fallback = rankActiveRenderLayers(listOut, context.packageName)
        sb.appendLine("=== Pass 2 — active-render fallback candidates (${fallback.size} found, own-pkg='${context.packageName}' excluded) ===")
        if (fallback.isEmpty()) {
            sb.appendLine("(none — no SurfaceView/BLAST layers outside own package + system exclusions)")
        } else {
            for (layerName in fallback) {
                val safe = layerName.replace("'", "'\\''")
                val latency = pServerWriter.executeShell("dumpsys SurfaceFlinger --latency '$safe'")
                val parsedFps = latency?.second?.let { parseSurfaceFlingerLatency(it) }
                sb.appendLine("  layer='$layerName' parsedFps=${parsedFps ?: "none"}")
                sb.appendLine(latency?.second ?: "  (PServer unavailable)")
            }
        }

        val (_, versionOut) = pServerWriter.executeShell("dumpsys SurfaceFlinger --version")
            ?: run { sb.appendLine("=== --version: PServer unavailable ==="); return sb.toString() }
        sb.appendLine("=== dumpsys SurfaceFlinger --version ===")
        sb.appendLine(versionOut)

        return sb.toString()
    }

    companion object {
        /** Whitespace splitter — hoisted to avoid per-line Regex allocation. */
        private val WHITESPACE = Regex("""\s+""")

        /**
         * INT64_MAX sentinel emitted by SurfaceFlinger when a frame slot has not
         * yet been presented. Rows with this vsync value must be skipped.
         */
        internal const val SENTINEL_NOT_YET_PRESENTED = Long.MAX_VALUE   // 9223372036854775807

        /** Layer-cache TTL per package. 4 s avoids redundant --list calls across
         *  multiple 500 ms ticks while allowing recovery if the app restarts its surface. */
        private const val LAYER_CACHE_TTL_MS = 4_000L

        /**
         * Substrings that identify compositor/system layers that never carry
         * real game frame data. Matching is case-insensitive.
         *
         * Applied to both the package-matched pass and the fallback pass.
         *
         * Sources: AOSP SurfaceFlinger layer naming conventions + on-device
         * observation on Odin 3 (Android 14, SurfaceFlinger 14.0).
         */
        internal val NON_GAME_LAYER_TOKENS = listOf(
            "animation-leash",
            "BlurEffect",
            "Dim#",
            "ScreenDecorOverlay",
            "NavigationBar",
            "StatusBar",
            "InputMethod",
            "Splash",
        )

        /**
         * Additional system-surface substrings excluded ONLY from the
         * wrapper-app fallback pass ([rankActiveRenderLayers]).
         *
         * These are not in [NON_GAME_LAYER_TOKENS] because they are unlikely
         * to appear in package-matched results and we keep that list minimal
         * to avoid false negatives on unusual game setups.
         *
         * Matching is case-insensitive.
         */
        internal val FALLBACK_SYSTEM_EXCLUSIONS = listOf(
            "com.android.systemui",
            "Wallpaper",
            "TaskSnapshot",
            "ScreenDecor",
            "pointer",
            "cursor",
        )
    }

    private fun publishCurrentRate(dm: DisplayManager) {
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val hz = display.refreshRate
        if (hz > 0) _fps.value = hz.toInt()
    }

    private fun currentForegroundPkg(): String? {
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return null
        if (context.packageManager.checkPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS,
                context.packageName,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return null
        val now = System.currentTimeMillis()
        val events = um.queryEvents(now - 10_000L, now)
        val ev = android.app.usage.UsageEvents.Event()
        var lastResumed: String? = null
        while (events.getNextEvent(ev)) {
            if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumed = ev.packageName
            }
        }
        return lastResumed?.takeIf { it != context.packageName }
    }
}
