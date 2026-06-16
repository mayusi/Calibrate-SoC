package io.github.mayusi.calibratesoc.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import kotlinx.coroutines.runBlocking
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
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dragHandler: DragHandler? = null

    private val frameRateSampler = HudFrameRateSampler()

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
                val view = composeView ?: return@collect
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
        if (sessionRecorder.isRecording.value) {
            runBlocking(Dispatchers.IO) {
                runCatching { sessionRecorder.stop("hud_stop") }
                    .onFailure { e ->
                        hudEventLog.add(
                            HudEventLog.Level.WARN,
                            "sessionRecorder.stop failed: ${e.message}",
                        )
                    }
            }
        }
        frameRateSampler.stop()
        gameFpsSampler.stop()
        // Detach the FPS bridge — the overlay-layer sampler is going away.
        autoTdpSampler.realFpsSupplier =
            io.github.mayusi.calibratesoc.data.autotdp.RealFpsSupplier.NONE
        dragHandler?.stop()
        dragHandler = null
        runCatching { composeView?.let { windowManager?.removeView(it) } }
        composeView = null
        _viewModelStore.clear()
        // Fire setRunning(false) before cancel so the launched coroutine isn't dropped.
        serviceScope.launch { hudPrefs.setRunning(false) }
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

        // Read last-saved position synchronously (one-time warm DataStore read,
        // < 1 ms) to place the overlay without any visible jump on open.
        val (savedXDp, savedYDp) = runBlocking { hudPrefs.xDp.first() to hudPrefs.yDp.first() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedXDp * density).roundToInt()
            y = (savedYDp * density).roundToInt()
        }
        layoutParams = params

        val view = ComposeView(this).apply {
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
        composeView = view

        val handler = DragHandler(params, wm, view, density) { xDp, yDp ->
            lifecycleScope.launch { hudPrefs.setPosition(xDp, yDp) }
        }
        dragHandler = handler
        view.setOnTouchListener(handler)
        wm.addView(view, params)
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeProfile() {
        lifecycleScope.launch {
            hudPrefs.profile.collect { assembler.feedProfile(it) }
        }
        lifecycleScope.launch {
            val density = resources.displayMetrics.density
            combine(hudPrefs.xDp, hudPrefs.yDp) { x, y -> x to y }
                .collect { (xDp, yDp) ->
                    val p = layoutParams ?: return@collect
                    val view = composeView ?: return@collect
                    p.x = (xDp * density).roundToInt()
                    p.y = (yDp * density).roundToInt()
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
    val ramUsedPct: Int? = null,
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
