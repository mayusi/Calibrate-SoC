package io.github.mayusi.calibratesoc.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.R
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpController
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile
import io.github.mayusi.calibratesoc.data.autotdp.AutoTdpStatus
import io.github.mayusi.calibratesoc.data.display.RefreshRateController
import io.github.mayusi.calibratesoc.data.session.SessionRecorder
import io.github.mayusi.calibratesoc.ui.theme.CalibrateSocTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Foreground service that hosts a draggable Compose overlay on top of any app.
 *
 * Thin host responsibilities only:
 *  - Service lifecycle: notification, foreground start/stop, Android
 *    LifecycleOwner / SavedStateRegistry / ViewModelStore wiring.
 *  - WindowManager: attach/detach the ComposeView, manage LayoutParams.
 *  - [DragHandler]: translate finger movement into WindowManager layout updates
 *    and persist position to DataStore.
 *  - Refresh-rate controller: propagate preferred Hz to WindowManager.
 *  - [HudFrameRateSampler] and [GameFpsSampler]: start/stop + feed results to
 *    [HudStateAssembler] via its feedXxx helpers.
 *  - [observeBigCorePolicy]: probe CPU topology + write-capability once,
 *    then stream canTuneLive flips whenever the experimental flag changes.
 *
 * Telemetry collection, AutoTDP integration, and all write-path logic have
 * been extracted into dedicated classes:
 *  - [HudStateAssembler]: single shared telemetry subscription (fixes the
 *    previous double-subscription bug), AutoTDP state observation, profile
 *    chip list, session recorder mirroring.
 *  - [HudTuneController]: ± MHz steppers, OPP-snap, multi-path write dispatch,
 *    profile apply, flashActionMessage with atomic token, history appends.
 *
 * Why vanilla Service (not LifecycleService): Hilt's KSP processor only
 * accepts direct Service descent. We implement LifecycleOwner +
 * SavedStateRegistryOwner + ViewModelStoreOwner manually — same way
 * LifecycleService does internally.
 */
@AndroidEntryPoint
class OverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleScope: CoroutineScope get() = serviceScope

    // ── Injected dependencies ─────────────────────────────────────────────────

    @Inject lateinit var hudPrefs: HudPrefs
    @Inject lateinit var hudEventLog: HudEventLog
    @Inject lateinit var gameFpsSampler: GameFpsSampler
    @Inject lateinit var refreshRateController: RefreshRateController
    @Inject lateinit var sessionRecorder: SessionRecorder

    /** AutoTDP controller — used for HUD in-overlay start/stop/profile switch. */
    @Inject lateinit var autoTdpController: AutoTdpController

    /**
     * AutoTDP savings probe — bridged to [gameFpsSampler] here so the probe can
     * read REAL game FPS while the HUD is active. The data layer has no FPS source
     * of its own; this is the only honest place to supply it.
     */
    @Inject lateinit var autoTdpSampler: io.github.mayusi.calibratesoc.data.autotdp.AutoTdpSampler

    /** State assembler: owns the single telemetry subscription + AutoTDP feed. */
    @Inject lateinit var assembler: HudStateAssembler

    /** Tune controller: owns all live-write paths + flashActionMessage. */
    @Inject lateinit var tuneController: HudTuneController

    // ── Compose / WindowManager state ─────────────────────────────────────────

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore get() = _viewModelStore

    private var windowManager: WindowManager? = null
    // The view actually attached to the WindowManager — an [UnboundedWidthHost]
    // FrameLayout that wraps the ComposeView and forces an UNSPECIFIED width
    // measure (see UnboundedWidthHost for the full root-cause writeup). Drag +
    // clampToScreen operate on this host, since it is the window's content view.
    private var hostView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dragHandler: DragHandler? = null

    private val frameRateSampler = HudFrameRateSampler()

    /**
     * Sticky ACTION_BATTERY_CHANGED receiver — the honest, ANR-safe source of the
     * battery % shown in the HUD. Mirrors the Dashboard's PERF-2 pattern: register
     * once with the sticky filter (the framework returns the current level
     * synchronously, subsequent changes arrive via [onReceive]), so we NEVER do a
     * per-tick binder/registerReceiver call from a composable. Battery % changes a
     * few times an hour, not every frame. Null stays null → HUD shows "—".
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            assembler.feedBatteryPct(readBatteryPct(intent))
        }
    }
    private var batteryReceiverRegistered = false

    /** Parse a 0–100 level from an ACTION_BATTERY_CHANGED intent, or null. */
    private fun readBatteryPct(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (!Settings.canDrawOverlays(this)) {
            // SAW permission revoked while we weren't running. Stop quietly.
            stopSelf()
            return
        }

        startInForeground()

        // Wire the assembler ↔ tune controller cross-references BEFORE
        // attachOverlay so the first Compose frame can already read hudState.
        tuneController.assembler = assembler
        tuneController.bind(serviceScope)

        // Kick off all telemetry + AutoTDP observation in the assembler.
        assembler.start(serviceScope)

        // Register the sticky battery receiver and seed the first level. The
        // sticky Intent is returned synchronously by registerReceiver, so the HUD
        // has a real % on the first frame without any polling. runCatching guards
        // a framework refusal (e.g. headless context) — null then renders as "—".
        runCatching {
            val sticky = registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            batteryReceiverRegistered = true
            assembler.feedBatteryPct(readBatteryPct(sticky))
        }

        attachOverlay()
        observeProfile()

        // HUD Choreographer frame rate → assembler.
        frameRateSampler.start()
        serviceScope.launch {
            frameRateSampler.hz.collect { assembler.feedHudHz(it) }
        }

        // Game FPS sampler → assembler (fps + foreground-pkg + isRealFps flag).
        gameFpsSampler.start(serviceScope)
        serviceScope.launch {
            combine(
                gameFpsSampler.fps,
                gameFpsSampler.foregroundPkg,
                gameFpsSampler.isRealFps,
            ) { fps, pkg, isReal -> Triple(fps, pkg, isReal) }
                .collect { (fps, pkg, isReal) -> assembler.feedGameFps(fps, pkg, isReal) }
        }

        // Bridge REAL game FPS into the AutoTDP savings probe. HONESTY: returns the
        // FPS ONLY when isRealFps is true (a genuine SurfaceFlinger measurement);
        // otherwise null so AutoTdpEffect.fpsDelta stays null and the UI hides it.
        autoTdpSampler.realFpsSupplier =
            io.github.mayusi.calibratesoc.data.autotdp.RealFpsSupplier {
                if (gameFpsSampler.isRealFps.value) gameFpsSampler.fps.value else null
            }

        // Refresh rate → WindowManager preferred display mode ID.
        serviceScope.launch {
            refreshRateController.preferredHz.collect { hz ->
                val view = hostView ?: return@collect
                val params = layoutParams ?: return@collect
                val newId = hz?.let { refreshRateController.resolveModeIdForHz(it) } ?: 0
                if (params.preferredDisplayModeId != newId) {
                    params.preferredDisplayModeId = newId
                    runCatching { windowManager?.updateViewLayout(view, params) }
                        .onFailure { e ->
                            hudEventLog.add(
                                HudEventLog.Level.WARN,
                                "refreshRate updateViewLayout failed: ${e.message}",
                            )
                        }
                }
            }
        }

        // Seed available refresh-rate options into state (once — modes don't change at runtime).
        val modes = refreshRateController.supportedModes()
        val hzOptions = modes.map { it.hz }.sortedBy { it }
        assembler.feedRefreshRateOptions(hzOptions)

        // Observe pinned Hz preference.
        serviceScope.launch {
            refreshRateController.preferredHz.collect { hz ->
                assembler.feedPinnedHz(hz)
            }
        }

        // Observe HUD size index + opacity preferences.
        serviceScope.launch { hudPrefs.hudSizeIndex.collect { assembler.feedHudSizeIndex(it) } }
        serviceScope.launch { hudPrefs.hudOpacity.collect { assembler.feedHudOpacity(it) } }

        hudEventLog.add(HudEventLog.Level.INFO, "HUD started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CYCLE_PROFILE -> serviceScope.launch {
                val next = when (assembler.state.value.profile) {
                    HudProfile.COMPACT -> HudProfile.VERBOSE
                    HudProfile.VERBOSE -> HudProfile.COMPACT
                }
                hudPrefs.setProfile(next)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        if (batteryReceiverRegistered) {
            runCatching { unregisterReceiver(batteryReceiver) }
            batteryReceiverRegistered = false
        }
        frameRateSampler.stop()
        gameFpsSampler.stop()
        // Detach the FPS bridge — the overlay-layer sampler is going away.
        autoTdpSampler.realFpsSupplier =
            io.github.mayusi.calibratesoc.data.autotdp.RealFpsSupplier.NONE
        dragHandler?.stop()
        dragHandler = null
        runCatching { hostView?.let { windowManager?.removeView(it) } }
        hostView = null
        _viewModelStore.clear()

        // CRITICAL (UI) ANR + C-3 FIX: the two persistence writes onDestroy must perform —
        // sessionRecorder.stop(...) and hudPrefs.setRunning(false) — were each broken:
        //   - sessionRecorder.stop ran under runBlocking(Dispatchers.IO) on the MAIN thread
        //     (an unbounded main-thread block → ANR if the recorder flush stalled).
        //   - setRunning(false) was launched on serviceScope and then serviceScope.cancel()
        //     ran immediately after, cancelling the launched coroutine BEFORE the write hit
        //     disk (it persisted only by luck of scheduling).
        // Fix: run BOTH fire-and-forget on a fresh DETACHED scope under NonCancellable, so:
        //   - they execute off the main thread (no ANR — nothing blocks onDestroy), and
        //   - serviceScope.cancel() below cannot drop them (the scope is independent and the
        //     body is NonCancellable), so the writes reliably reach disk.
        // GlobalScope-equivalent detached scope is intentional here: a fire-and-forget flush
        // that must outlive the service's own scope.
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (sessionRecorder.isRecording.value) {
                    runCatching { sessionRecorder.stop("hud_stop") }
                        .onFailure { e ->
                            hudEventLog.add(
                                HudEventLog.Level.WARN,
                                "sessionRecorder.stop failed: ${e.message}",
                            )
                        }
                }
                runCatching { hudPrefs.setRunning(false) }
                    .onFailure { e ->
                        hudEventLog.add(
                            HudEventLog.Level.WARN,
                            "setRunning(false) failed: ${e.message}",
                        )
                    }
            }
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating HUD",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the floating performance HUD is active."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Calibrate SoC HUD")
            .setContentText("Floating overlay running. Tap to open the app.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        lifecycleScope.launch { hudPrefs.setRunning(true) }
    }

    private fun attachOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density

        // CRITICAL (UI) ANR FIX: do NOT block the main thread reading DataStore. onCreate
        // runs on the main thread; the old `runBlocking { hudPrefs.xDp.first() }` could ANR
        // on a cold DataStore (first read deserializes from disk). Instead init at (0,0) and
        // asynchronously read the last-saved position, then updateViewLayout once it lands.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Spawn INSET from the top-left corner (not flush at 0,0) so the very
            // first frame — before the async saved-position read below lands —
            // already floats as an intentional HUD instead of being jammed into
            // the screen corner. Matches HudPrefs.DEFAULT_X/Y_DP (the persisted
            // first-run default), so first frame and post-read placement agree.
            x = (HudPrefs.DEFAULT_X_DP * density).roundToInt()
            y = (HudPrefs.DEFAULT_Y_DP * density).roundToInt()
        }
        layoutParams = params

        // Async one-shot read of the saved position; apply it once (the observeProfile()
        // x/y collector also keeps it in sync afterwards, so this just covers the first
        // placement without a visible jump being driven from a blocked main thread).
        serviceScope.launch {
            val savedXDp = hudPrefs.xDp.first()
            val savedYDp = hudPrefs.yDp.first()
            val view = hostView ?: return@launch
            val p = layoutParams ?: return@launch
            p.x = (savedXDp * density).roundToInt()
            p.y = (savedYDp * density).roundToInt()
            // Clamp so a position saved near (or past) the right/bottom edge can't
            // place the WRAP_CONTENT bar partly off-screen. The view may not be
            // measured yet on this first pass, so also re-clamp after layout once
            // its real width is known (see view.post below).
            windowManager?.let { wm -> clampToScreen(p, view, wm) }
            runCatching { windowManager?.updateViewLayout(view, p) }
                .onFailure { e ->
                    hudEventLog.add(
                        HudEventLog.Level.WARN,
                        "initial position updateViewLayout failed: ${e.message}",
                    )
                }
            // Re-clamp once the bar has measured its real (dynamic) width, so the
            // trailing BAT + swap + close cells are guaranteed fully visible.
            view.post {
                val wm = windowManager ?: return@post
                val before = p.x to p.y
                clampToScreen(p, view, wm)
                if (p.x to p.y != before) {
                    runCatching { wm.updateViewLayout(view, p) }
                }
            }
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CalibrateSocTheme {
                    val state by assembler.state.collectAsState()
                    HudOverlayContent(
                        state = state,
                        onCycleLayout = {
                            serviceScope.launch {
                                val next = when (state.profile) {
                                    HudProfile.COMPACT -> HudProfile.VERBOSE
                                    HudProfile.VERBOSE -> HudProfile.COMPACT
                                }
                                hudPrefs.setProfile(next)
                                hudEventLog.add(
                                    HudEventLog.Level.INFO,
                                    "Layout → ${next.name.lowercase()}",
                                )
                            }
                        },
                        onApplyProfile = { id ->
                            hudEventLog.add(HudEventLog.Level.ACTION, "apply $id (chip)")
                            tuneController.applyProfileViaScript(id)
                        },
                        onCycleNextProfile = { tuneController.cycleNextProfile() },
                        onToggleRecord = {
                            serviceScope.launch {
                                if (sessionRecorder.isRecording.value) {
                                    sessionRecorder.stop("hud_button")
                                    hudEventLog.add(HudEventLog.Level.INFO, "Session recording stopped")
                                } else {
                                    sessionRecorder.start(hudIsRunning = true)
                                    hudEventLog.add(HudEventLog.Level.INFO, "Session recording started")
                                }
                            }
                        },
                        onToggleAutoTdp = {
                            if (assembler.state.value.autoTdpRunning) {
                                autoTdpController.stop()
                                hudEventLog.add(HudEventLog.Level.INFO, "AutoTDP stopped from HUD")
                            } else {
                                val profile = assembler.state.value.autoTdpActiveProfile
                                autoTdpController.start(profile)
                                hudEventLog.add(HudEventLog.Level.INFO, "AutoTDP started (${profile.name}) from HUD")
                            }
                        },
                        onSetAutoTdpProfile = { profile ->
                            assembler.feedAutoTdpActiveProfile(profile)
                            // If already running, restart with new profile.
                            if (assembler.state.value.autoTdpRunning) {
                                autoTdpController.stop()
                                autoTdpController.start(profile)
                                hudEventLog.add(HudEventLog.Level.INFO, "AutoTDP profile → ${profile.name}")
                            }
                        },
                        onSetRefreshHz = { hz ->
                            serviceScope.launch {
                                refreshRateController.setPreferredHz(hz)
                                hudEventLog.add(HudEventLog.Level.INFO, "Refresh rate → ${hz?.toInt() ?: "auto"} Hz")
                            }
                        },
                        onCycleHudSize = {
                            serviceScope.launch {
                                val next = (assembler.state.value.hudSizeIndex + 1) % 3
                                hudPrefs.setHudSizeIndex(next)
                                assembler.feedHudSizeIndex(next)
                            }
                        },
                        onSetOpacity = { opacity ->
                            serviceScope.launch {
                                hudPrefs.setHudOpacity(opacity)
                                assembler.feedHudOpacity(opacity)
                            }
                        },
                        onClose = { stopSelf() },
                    )
                }
            }
        }
        // Wrap the ComposeView in a host that forces an UNSPECIFIED-width measure
        // so the WRAP_CONTENT overlay window sizes to the bar's FULL intrinsic
        // width (root-cause fix — see [UnboundedWidthHost]). The host is the view
        // attached to the WindowManager and the one dragged/clamped; the inner
        // ComposeView owns the composition + lifecycle wiring above.
        val host = UnboundedWidthHost(this).apply {
            // CRITICAL: the host is the view actually attached to the WindowManager,
            // and Compose resolves ViewTreeLifecycleOwner / SavedStateRegistry /
            // ViewModelStore by walking UP from the ComposeView through its parents.
            // The owners MUST be set on this host (not only the inner ComposeView),
            // otherwise AbstractComposeView throws "ViewTreeLifecycleOwner not found"
            // when it creates its recomposer on attach → crash on HUD show.
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        hostView = host

        val handler = DragHandler(params, wm, host, density) { xDp, yDp ->
            lifecycleScope.launch { hudPrefs.setPosition(xDp, yDp) }
        }
        dragHandler = handler
        host.setOnTouchListener(handler)
        wm.addView(host, params)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeProfile() {
        lifecycleScope.launch {
            hudPrefs.profile.collect { profile ->
                assembler.feedProfile(profile)
                // RE-CLAMP ON EXPAND (verbose visibility fix): COMPACT→VERBOSE grows
                // the WRAP_CONTENT window from a thin bar to a tall ~330dp card. The
                // window keeps the bar's (x,y), so a bar sitting near the bottom/right
                // edge would push the expanded panel mostly OFF-SCREEN — which read as
                // "verbose doesn't show". Re-clamp after the view re-measures to the
                // new size so the whole panel is pulled back on-screen. view.post runs
                // after the next layout pass, when view.width/height reflect the new
                // profile; clampToScreen no-ops when the size is unchanged.
                val view = hostView ?: return@collect
                view.post {
                    val wm = windowManager ?: return@post
                    val p = layoutParams ?: return@post
                    val before = p.x to p.y
                    clampToScreen(p, view, wm)
                    if (p.x to p.y != before) {
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                }
            }
        }
        lifecycleScope.launch {
            val density = resources.displayMetrics.density
            combine(hudPrefs.xDp, hudPrefs.yDp) { x, y -> x to y }
                .collect { (xDp, yDp) ->
                    val p = layoutParams ?: return@collect
                    val view = hostView ?: return@collect
                    p.x = (xDp * density).roundToInt()
                    p.y = (yDp * density).roundToInt()
                    // Keep the dynamic-width bar fully on-screen (RP6 right-edge clip).
                    windowManager?.let { wm -> clampToScreen(p, view, wm) }
                    runCatching { windowManager?.updateViewLayout(view, p) }
                        .onFailure { e ->
                            hudEventLog.add(
                                HudEventLog.Level.WARN,
                                "updateViewLayout failed: ${e.message}",
                            )
                        }
                }
        }
    }

    // ── DragHandler ───────────────────────────────────────────────────────────

    /**
     * Touch-based drag without snap. Tracks from ACTION_DOWN, latches after
     * the standard 8 dp touch slop, then feeds every MOVE directly into
     * WindowManager coalesced via Choreographer (one update per display frame).
     */
    private class DragHandler(
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager,
        private val view: View,
        private val density: Float,
        private val onPositionChanged: (xDp: Int, yDp: Int) -> Unit,
    ) : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private val touchSlopPx = (8 * density)
        private val frameChoreographer = android.view.Choreographer.getInstance()
        private var pendingFrame = false

        private val frameCallback = android.view.Choreographer.FrameCallback {
            pendingFrame = false
            runCatching { wm.updateViewLayout(view, params) }
        }

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    dragging = false
                    return false // Let chip clicks through.
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX
                    val dy = ev.rawY - touchY
                    if (!dragging &&
                        (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        clampToScreen(params, view, wm)
                        scheduleLayout()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        onPositionChanged(
                            (params.x / density).roundToInt(),
                            (params.y / density).roundToInt(),
                        )
                        dragging = false
                        return true
                    }
                }
            }
            return false
        }

        private fun scheduleLayout() {
            if (pendingFrame) return
            pendingFrame = true
            frameChoreographer.postFrameCallback(frameCallback)
        }

        fun stop() {
            frameChoreographer.removeFrameCallback(frameCallback)
            pendingFrame = false
        }
    }

    companion object {
        const val ACTION_CYCLE_PROFILE = "io.github.mayusi.calibratesoc.HUD_CYCLE_PROFILE"
        const val ACTION_STOP = "io.github.mayusi.calibratesoc.HUD_STOP"
        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "hud_overlay"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

/**
 * A [FrameLayout] host that measures its single child (the HUD [ComposeView]) at
 * its FULL INTRINSIC width.
 *
 * ── Why this exists (the compact-bar "broken layout" root cause) ──────────────
 * The HUD lives in a `WRAP_CONTENT × WRAP_CONTENT` overlay window. When Android's
 * `ViewRootImpl` measures the content of a `WRAP_CONTENT` window, it hands the
 * root view a width [View.MeasureSpec] of mode **`AT_MOST`**, bounded by the
 * available window/display width — NOT `UNSPECIFIED`. A plain `ComposeView`
 * faithfully propagates that bounded `AT_MOST` width into the Compose layout, so
 * the bar's `Row(wrapContentWidth())` is told "you may be at most N px wide". On a
 * dense handheld (RP6) the bar's true intrinsic width can exceed that bound, so
 * Compose squeezes the LAST children to fit: the BAT cell's "92%" collapses to
 * stacked vertical characters ("9"/"2"/"%") and the trailing CONTROLS cell
 * (AUTO·TDP pill + expand + close) overflows and is clipped away. That is exactly
 * the on-device symptom.
 *
 * `ComposeView`/`AbstractComposeView.onMeasure` are `final`, so we cannot subclass
 * the ComposeView itself. Instead this thin FrameLayout wraps it and re-issues the
 * width spec as **`UNSPECIFIED`** to the child whenever the incoming spec is
 * `AT_MOST` (the `WRAP_CONTENT` window case). `UNSPECIFIED` lets the Compose
 * hierarchy report the bar's full desired width with NO upper bound, so every cell
 * measures at its natural size on a single row. This host then reports that same
 * width upward, the `WRAP_CONTENT` window sizes to it, and [clampToScreen] keeps
 * the (now correctly-sized) bar fully on-screen. An `EXACTLY` spec (never used by
 * our WRAP_CONTENT window, but possible in tests/previews) is passed through
 * untouched so a fixed-size host is still honoured. Height is left as-is — only
 * width was being starved.
 *
 * This is the minimal, correct measurement fix: no `widthIn`, no `weight`, no
 * `fillMaxWidth` anywhere on the bar (all of which would re-introduce a bound).
 * The verbose panel is unaffected — it pins its own width via `widthIn(min=max=)`,
 * which under an `UNSPECIFIED` spec simply resolves to that fixed width.
 */
private class UnboundedWidthHost(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val effectiveWidthSpec = if (widthMode == View.MeasureSpec.AT_MOST) {
            // Bounded WRAP_CONTENT window → measure unbounded so the bar reports its
            // full intrinsic width and no trailing cell is width-starved.
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            // EXACTLY (fixed host) or already UNSPECIFIED → leave untouched.
            widthMeasureSpec
        }
        super.onMeasure(effectiveWidthSpec, heightMeasureSpec)
    }
}

/**
 * Clamp the overlay window position so the (WRAP_CONTENT) HUD always stays fully
 * ON-SCREEN.
 *
 * RP6 clipping fix: the overlay window is `WRAP_CONTENT` width with
 * `FLAG_LAYOUT_NO_LIMITS` (so it can be dragged freely). The compact bar grows
 * RIGHTWARD from its top-left origin as cells are added; with no clamp, a bar
 * dragged toward — or restored near — the right edge pushed its trailing cells
 * (BAT + swap + close) past the physical screen edge, where they were clipped and
 * the game bled through behind the off-screen window region. Clamping the origin
 * to `[0, screen − measuredSize]` (top-left gravity) keeps the whole bar visible
 * regardless of its dynamic width.
 *
 * Uses the view's already-measured pixel size (`view.width`/`view.height`), so it
 * must run AFTER the view has been laid out at least once — on a drag MOVE (always
 * laid out) and on a position restore (guarded by a non-zero width). When the view
 * hasn't measured yet (width == 0) we only floor the origin at 0 and leave the
 * upper bound to the next layout pass, so we never clamp against a stale 0 width.
 */
private fun clampToScreen(
    params: WindowManager.LayoutParams,
    view: View,
    wm: WindowManager,
) {
    val metrics = android.util.DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)
    val screenW = metrics.widthPixels
    val screenH = metrics.heightPixels
    val viewW = view.width
    val viewH = view.height

    // Right/bottom bound only applies once we know the measured size; otherwise
    // just keep the origin non-negative and let the next layout pass tighten it.
    val maxX = if (viewW > 0) (screenW - viewW).coerceAtLeast(0) else Int.MAX_VALUE
    val maxY = if (viewH > 0) (screenH - viewH).coerceAtLeast(0) else Int.MAX_VALUE
    params.x = params.x.coerceIn(0, maxX)
    params.y = params.y.coerceIn(0, maxY)
}

/**
 * Immutable snapshot of HUD display state.
 *
 * All fields are primitives, enums, nullable value types, or read-only
 * Kotlin collections — Compose can skip recomposition on unchanged subtrees.
 *
 * AutoTDP fields (prefix [autoTdp*]):
 *  [autoTdpStatus]       — daemon lifecycle (IDLE/RUNNING/STOPPED/…)
 *  [autoTdpRunning]      — shortcut: true only when status == RUNNING
 *  [autoTdpParkedCores]  — core indices AutoTDP has offlined
 *  [autoTdpBigCapMhz]    — big-cluster MHz cap applied, null = uncapped
 *  [autoTdpGpuLevel]     — GPU power-level floor, null = unconstrained
 *  [autoTdpReason]       — last decision string from AutoTdpEngine
 *  [autoTdpSavingsMw]        — measured delta mW (positive = saved), null until measured
 *  [autoTdpSavingsPct]       — same delta as a percentage, null until measured
 *  [autoTdpSavingsReady]     — true only when enoughData; never show savings before this
 *  [autoTdpGoal]             — concrete GoalProfile resolved this tick (Wave 4b), null when not running
 *  [autoTdpDetectedContext]  — classifier DETECTED context when AUTO goal is live (blue pill, NOT measured)
 */
@Immutable
data class HudUiState(
    val profile: HudProfile = HudProfile.COMPACT,
    val cpuMaxMhz: Int = 0,
    val cpuLoadPct: Int = 0,
    val perCoreMhz: List<Int> = emptyList(),
    val perCoreLoadPct: List<Int> = emptyList(),
    val gpuMhz: Int? = null,
    val gpuLoadPct: Int? = null,
    val cpuTempC: Float? = null,
    val cpuPeakTempC: Float? = null,
    val gpuTempC: Float? = null,
    val batteryTempC: Float? = null,
    val maxTempC: Float = 0f,
    val batteryW: Double? = null,
    /** Battery state-of-charge percent (0–100), fed from a sticky
     *  ACTION_BATTERY_CHANGED receiver. Null = sensor unavailable (render "—"). */
    val batteryPct: Int? = null,
    val ramUsedPct: Int? = null,
    /** True when [perCoreLoadPct] is a frequency-ratio PROXY (not a true
     *  /proc/stat read). Drives the "~" honesty prefix on load values so an
     *  estimate never reads as a measured utilisation. */
    val loadIsProxy: Boolean = false,
    /** Kernel cooling-device max cur_state this tick. > 0 = actively throttling
     *  NOW (honest "am I being throttled?" signal). Null = not probed on device. */
    val coolingDeviceMaxState: Int? = null,
    val zones: List<Pair<String, Float>> = emptyList(),
    /** id → display label for chips. Empty = no chips row. */
    val quickProfiles: List<Pair<String, String>> = emptyList(),
    /** Brief toast-style line shown after a tap action. Null = hidden. */
    val lastActionMessage: String? = null,
    /** HUD Choreographer draw rate — NOT the game's FPS. */
    val hudHz: Int = 0,
    val gameFps: Int? = null,
    /** True when [gameFps] is a real game frame-rate measurement (from PServer /
     *  GameFpsSampler), false when it is the panel's display refresh rate
     *  used as a fallback label. Drives the "FPS"/"REFRESH" chip label. */
    val gameFpsIsReal: Boolean = false,
    val gameForegroundPkg: String? = null,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Long = 0L,
    // AutoTDP integration
    val autoTdpStatus: AutoTdpStatus = AutoTdpStatus.IDLE,
    val autoTdpRunning: Boolean = false,
    val autoTdpParkedCores: Set<Int> = emptySet(),
    val autoTdpBigCapMhz: Int? = null,
    val autoTdpGpuLevel: Int? = null,
    val autoTdpReason: String = "",
    val autoTdpSavingsMw: Int? = null,
    val autoTdpSavingsPct: Double? = null,
    val autoTdpSavingsReady: Boolean = false,
    /** Clean machine-readable classification of the last AutoTDP decision. */
    val autoTdpHoldReason: io.github.mayusi.calibratesoc.data.autotdp.HoldReason =
        io.github.mayusi.calibratesoc.data.autotdp.HoldReason.NO_TELEMETRY,
    /** HEARTBEAT: wall-clock epoch (ms) of the last applied AutoTDP tick. Null = none yet. */
    val autoTdpLastAppliedEpochMs: Long? = null,
    /** Big-cluster cap delta vs stock ceiling, in MHz (stockCeiling − cap). Null = uncapped/unknown. */
    val autoTdpCapDeltaMhz: Int? = null,
    /** Session energy saved this session in Wh. MEASURED-only; null until probe-backed. */
    val autoTdpSessionWh: Double? = null,
    /** Rolling decision history (oldest-first, bounded). Empty before first tick. */
    val autoTdpDecisions: List<io.github.mayusi.calibratesoc.data.autotdp.DecisionRecord> =
        emptyList(),
    // AutoTDP active profile (used for the in-HUD profile picker)
    val autoTdpActiveProfile: io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile =
        io.github.mayusi.calibratesoc.data.autotdp.AutoTdpProfile.BALANCED,
    // Smart AutoTDP Wave 4b — goal + detected context for HUD display
    /** The concrete GoalProfile the daemon resolved to this tick (null when not RUNNING). */
    val autoTdpGoal: io.github.mayusi.calibratesoc.data.autotdp.GoalProfile? = null,
    /** Classifier DETECTED context (non-null when AUTO goal is RUNNING and a tick completed).
     *  Styled DETECTED (blue pill), NEVER "MEASURED" — this is a classifier belief. */
    val autoTdpDetectedContext: io.github.mayusi.calibratesoc.data.autotdp.WorkloadContext? = null,
    // Refresh-rate / FPS-cap controls
    /** Available Hz options from RefreshRateController. Empty = no cap control. */
    val availableHzOptions: List<Float> = emptyList(),
    /** Currently pinned Hz, null = system default. */
    val pinnedHz: Float? = null,
    // HUD display prefs (Direction-C layout controls)
    /** 0=small, 1=medium, 2=large — persisted via HudPrefs. */
    val hudSizeIndex: Int = 1,
    /** 0.0..1.0 overlay alpha — persisted via HudPrefs. */
    val hudOpacity: Float = 0.94f,
)
