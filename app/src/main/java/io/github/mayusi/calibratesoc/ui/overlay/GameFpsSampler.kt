package io.github.mayusi.calibratesoc.ui.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
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

/**
 * Display-rate FPS reader. Works for EVERY app — Vulkan, OpenGL,
 * Unity, native, emulator — because it doesn't try to peek inside the
 * game process. Instead, it asks the system compositor what refresh
 * rate it's actually presenting at right now.
 *
 * How the signal lines up with "game FPS":
 *
 *   - Modern games (and PPSSPP / AetherSX2 / Dolphin / RetroArch /
 *     Cemu / DuckStation when set to fullscreen) call
 *     `Surface.setFrameRate(N)` to ask SurfaceFlinger to lock the
 *     panel to N Hz. SurfaceFlinger obliges by switching the
 *     Display.Mode, and Display.getRefreshRate() returns N. That N
 *     IS the game's present rate.
 *
 *   - Games that don't call setFrameRate present at whatever cadence
 *     they happen to render, but the compositor still composites at
 *     the panel's max rate. In that case our number is the upper
 *     bound (panel's native Hz), which is still a useful signal —
 *     the user can compare against the game's internal counter.
 *
 *   - Emulators that target a low frame rate (PSP=60, GBA=59.7,
 *     N64=50/60) and call setFrameRate accordingly: we get the
 *     right number.
 *
 * We label it honestly as "FPS (display)" so the user knows the
 * source.
 *
 * Strictly better than `dumpsys gfxinfo` which fails silently on
 * Vulkan-direct apps. No permissions needed beyond standard ones.
 */
@Singleton
class GameFpsSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter,
) {
    private val _fps = MutableStateFlow<Int?>(null)
    val fps: StateFlow<Int?> = _fps.asStateFlow()

    private val _foregroundPkg = MutableStateFlow<String?>(null)
    val foregroundPkg: StateFlow<String?> = _foregroundPkg.asStateFlow()

    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null

    // P2: Cache the resolved SurfaceFlinger layer name per package so we avoid
    // running the expensive `dumpsys SurfaceFlinger --list | grep` every tick.
    // The cache entry is invalidated on display-mode change or after LAYER_CACHE_TTL_MS.
    private data class LayerCacheEntry(val pkg: String, val layerName: String, val resolvedAtMs: Long)
    @Volatile private var layerCache: LayerCacheEntry? = null

    fun start(scope: CoroutineScope) {
        if (displayListener != null) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return

        // Seed with the current rate.
        publishCurrentRate(dm)

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    // Display mode change may mean a different frame-rate lock;
                    // invalidate the layer cache so we re-resolve on the next tick.
                    layerCache = null
                    publishCurrentRate(dm)
                }
            }
        }
        dm.registerDisplayListener(listener, mainHandler)
        displayListener = listener

        // Fast poller as a safety net — onDisplayChanged doesn't fire
        // on every mode switch on every OEM kernel. Every 500 ms is
        // cheap enough.
        job = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(500)
                val pkg = currentForegroundPkg()
                _foregroundPkg.value = pkg
                // Real-game-FPS path: query SurfaceFlinger via PServer.
                // Falls back to display rate when PServer isn't
                // reachable or the foreground pkg has no measurable
                // layer.
                val realFps = pkg?.let { sampleRealFpsFor(it) }
                if (realFps != null && realFps in 1..240) {
                    _fps.value = realFps
                } else {
                    publishCurrentRate(dm)
                }
            }
        }
    }

    /**
     * Pull SurfaceFlinger frame timestamps for the foreground app's
     * layer via PServer. We run `dumpsys SurfaceFlinger --list` to
     * find the layer name (cached for [LAYER_CACHE_TTL_MS]), then
     * `--latency '<layer>'` to get per-frame timestamps. PServer runs
     * in a permissive domain that can read SurfaceFlinger frame data
     * even for Vulkan-direct apps like PPSSPP — something a normal app
     * UID never could.
     *
     * The expensive `--list | grep` step is cached: once we resolve the
     * layer name for a package we reuse it for [LAYER_CACHE_TTL_MS] ms.
     * The cache is invalidated on display-mode change (the set of active
     * layers may have changed) and when the foreground package changes.
     */
    private suspend fun sampleRealFpsFor(pkg: String): Int? {
        // Resolve layer name from cache or via fresh dumpsys --list.
        val now = System.currentTimeMillis()
        val cached = layerCache
        val layerName: String = if (
            cached != null &&
            cached.pkg == pkg &&
            (now - cached.resolvedAtMs) < LAYER_CACHE_TTL_MS
        ) {
            cached.layerName
        } else {
            // Cache miss or TTL expired: re-enumerate.
            val findLayer = "dumpsys SurfaceFlinger --list | grep '$pkg' | grep -v 'animation-leash' | head -1"
            val layerResult = pServerWriter.executeShell(findLayer) ?: return null
            val (_, layerOut) = layerResult
            // Lines from --list look like "SurfaceView[com.foo/.Bar]#0" or
            // "cb4035 com.foo/com.foo.BarActivity#4262" — take the first
            // non-empty line.
            val resolved = layerOut.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("---") }
                ?: return null
            layerCache = LayerCacheEntry(pkg = pkg, layerName = resolved, resolvedAtMs = now)
            resolved
        }

        // Some shells quote the layer name oddly. Strip outer quotes
        // and escape inner single quotes for the shell argument.
        val safe = layerName.trim().replace("'", "'\\''")
        val latencyCmd = "dumpsys SurfaceFlinger --latency '$safe'"
        val (_, latencyOut) = pServerWriter.executeShell(latencyCmd) ?: return null
        return parseSurfaceFlingerLatency(latencyOut)
    }

    /**
     * SurfaceFlinger --latency output: first line is the panel's refresh
     * interval in ns (16666666 for 60Hz). Following lines are per-frame
     * triples "a v p" where v is the vsync timestamp in ns. We compute
     * FPS = (count - 1) / (last - first) seconds, ignoring all-zero
     * rows that SurfaceFlinger emits when no recent frames exist.
     */
    private fun parseSurfaceFlingerLatency(out: String): Int? {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 3) return null
        val timestamps = mutableListOf<Long>()
        // Skip first line (refresh interval).
        for (i in 1 until lines.size) {
            // P3: use hoisted WHITESPACE instead of allocating a new Regex per line.
            val cols = lines[i].split(WHITESPACE)
            if (cols.size < 3) continue
            val v = cols[1].toLongOrNull() ?: continue
            if (v > 0) timestamps += v
        }
        if (timestamps.size < 2) return null
        val window = timestamps.takeLast(60)
        val spanSec = (window.last() - window.first()) / 1_000_000_000.0
        if (spanSec <= 0) return null
        return ((window.size - 1) / spanSec).toInt().coerceIn(1, 240)
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
        layerCache = null
    }

    companion object {
        /** Whitespace splitter — hoisted to avoid per-line Regex allocation in parseSurfaceFlingerLatency. */
        private val WHITESPACE = Regex("""\s+""")

        /** How long the resolved SurfaceFlinger layer name is cached per package (ms).
         *  4 seconds is long enough to avoid redundant --list calls across many 500 ms ticks
         *  while still being short enough to recover if the app restarts its surface mid-session. */
        private const val LAYER_CACHE_TTL_MS = 4_000L
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

