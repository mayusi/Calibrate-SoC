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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.calibratesoc.R
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import io.github.mayusi.calibratesoc.ui.theme.CalibrateSocTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Foreground service that hosts a draggable Compose overlay on top of
 * any app. Mirrors the RTSS / MSI Afterburner pattern from desktop —
 * a permanent floating panel that follows the game and shows live
 * telemetry.
 *
 * Why [LifecycleService] (not vanilla Service): a [ComposeView]
 * refuses to draw without LifecycleOwner + SavedStateRegistryOwner +
 * ViewModelStoreOwner on the view tree. LifecycleService gives us
 * the first; we implement the other two ourselves and wire all three
 * onto the WindowManager-managed view in [attachOverlay].
 *
 * Window type is TYPE_APPLICATION_OVERLAY — requires the user to
 * grant SYSTEM_ALERT_WINDOW manually via Settings → Display over
 * other apps. We never auto-grant.
 *
 * Telemetry source: the same [MonitorService] the dashboard uses,
 * sampled at 1 Hz to stay smooth without spending battery on stress-
 * rate polling.
 */
@AndroidEntryPoint
class OverlayService :
    Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    // Compose insists on a LifecycleOwner + SavedStateRegistryOwner +
    // ViewModelStoreOwner on the view tree. LifecycleService gives us
    // the first owner but Hilt's KSP processor only sees direct Service
    // descent — extending LifecycleService trips its base-class check.
    // So we extend plain Service and implement the trio ourselves, the
    // same way LifecycleService does internally (LifecycleRegistry + a
    // coroutine scope tied to its DESTROYED transition).
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleScope: CoroutineScope get() = serviceScope

    @Inject lateinit var monitorService: MonitorService
    @Inject lateinit var hudPrefs: HudPrefs
    @Inject lateinit var profileRepository: io.github.mayusi.calibratesoc.data.profiles.ProfileRepository
    @Inject lateinit var capabilityProbe: io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
    @Inject lateinit var deviceAdapterRegistry: io.github.mayusi.calibratesoc.data.devicedb.DeviceAdapterRegistry
    @Inject lateinit var scriptGenerator: io.github.mayusi.calibratesoc.data.script.AynScriptGenerator
    @Inject lateinit var scriptDeployer: io.github.mayusi.calibratesoc.data.script.AynScriptDeployer
    @Inject lateinit var tuneHistoryStore: io.github.mayusi.calibratesoc.data.tunables.TuneHistoryStore
    @Inject lateinit var hudEventLog: HudEventLog
    @Inject lateinit var pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
    @Inject lateinit var gameFpsSampler: GameFpsSampler
    @Inject lateinit var refreshRateController: io.github.mayusi.calibratesoc.data.display.RefreshRateController
    @Inject lateinit var userPrefs: io.github.mayusi.calibratesoc.data.prefs.UserPrefs

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore get() = _viewModelStore

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val hudState = MutableStateFlow(HudUiState())
    private val frameRateSampler = HudFrameRateSampler()

    override fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (!Settings.canDrawOverlays(this)) {
            // SAW permission was revoked while we weren't running.
            // Don't crash; just stop quietly. The Tune/Dashboard UI
            // shows a "grant overlay permission" path.
            stopSelf()
            return
        }

        startInForeground()
        attachOverlay()
        observeTelemetry()
        observeProfile()
        observeUserProfiles()
        observeBigCorePolicy()
        frameRateSampler.start()
        hudEventLog.add(HudEventLog.Level.INFO, "HUD started")
        serviceScope.launch {
            frameRateSampler.hz.collect { hz ->
                hudState.value = hudState.value.copy(hudHz = hz)
            }
        }
        gameFpsSampler.start(serviceScope)
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(gameFpsSampler.fps, gameFpsSampler.foregroundPkg) { fps, pkg ->
                fps to pkg
            }.collect { (fps, pkg) ->
                hudState.value = hudState.value.copy(gameFps = fps, gameForegroundPkg = pkg)
            }
        }
        serviceScope.launch {
            refreshRateController.preferredHz.collect { hz ->
                val view = composeView ?: return@collect
                val params = layoutParams ?: return@collect
                val newId = hz?.let { refreshRateController.resolveModeIdForHz(it) } ?: 0
                if (params.preferredDisplayModeId != newId) {
                    params.preferredDisplayModeId = newId
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CYCLE_PROFILE -> serviceScope.launch {
                val next = when (hudState.value.profile) {
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
        frameRateSampler.stop()
        gameFpsSampler.stop()
        runCatching {
            composeView?.let { windowManager?.removeView(it) }
        }
        composeView = null
        _viewModelStore.clear()
        // Fire the "running = false" pref BEFORE we cancel the scope,
        // otherwise the launched coroutine is dropped.
        runCatching {
            kotlinx.coroutines.runBlocking { hudPrefs.setRunning(false) }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Setup ---

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
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: don't steal focus from the game.
            // LAYOUT_NO_LIMITS: allow positioning into the gesture-nav strip.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).roundToInt()
            y = (64 * density).roundToInt()
        }
        layoutParams = params

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CalibrateSocTheme {
                    val state by hudState.collectAsState()
                    HudOverlayContent(
                        state = state,
                        onCycleLayout = {
                            serviceScope.launch {
                                val next = when (state.profile) {
                                    HudProfile.COMPACT -> HudProfile.VERBOSE
                                    HudProfile.VERBOSE -> HudProfile.COMPACT
                                }
                                hudPrefs.setProfile(next)
                                hudEventLog.add(HudEventLog.Level.INFO, "Layout → ${next.name.lowercase()}")
                            }
                        },
                        onApplyProfile = { id ->
                            hudEventLog.add(HudEventLog.Level.ACTION, "apply $id (chip)")
                            applyProfileViaScript(id)
                        },
                        onStepMhz = { delta -> stepBigCoreMhz(delta) },
                        onCycleNextProfile = { cycleNextProfile() },
                        onTogglePolicy = { pid ->
                            serviceScope.launch {
                                val cur = hudState.value.enabledPolicies
                                val next = if (pid in cur) cur - pid else cur + pid
                                hudPrefs.setEnabledPolicies(next)
                                hudState.value = hudState.value.copy(enabledPolicies = next)
                            }
                        },
                        onPickStepSize = { mhz ->
                            serviceScope.launch { hudPrefs.setStepMhz(mhz) }
                        },
                        onClose = { stopSelf() },
                    )
                }
            }
        }
        composeView = view

        view.setOnTouchListener(
            DragHandler(params, wm, view, density) { xDp, yDp ->
                lifecycleScope.launch { hudPrefs.setPosition(xDp, yDp) }
            },
        )

        wm.addView(view, params)
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            monitorService.telemetry(MonitorService.DEFAULT_INTERVAL_MS).collect { t ->
                val cpuMaxKhz = t.perCoreCpuFreqKhz.maxOrNull() ?: 0
                val cpuLoadPct = if (t.perCoreLoadPct.isEmpty()) 0
                    else t.perCoreLoadPct.average().toInt()
                val gpuMhz = t.gpuFreqHz?.let { (it / 1_000_000L).toInt() }
                val maxTempC = t.zoneTempsMilliC.maxOfOrNull { it.tempMilliC / 1000f } ?: 0f
                // CPU temp: average across all per-core sensors gives
                // the same number Odin Game Assistant shows. Single
                // FEX-pinned cores can spike to 100°C+ while 7 other
                // cores are at 60°C — the average ~75°C is what
                // matters for thermal behavior. We also track the
                // peak so users can see when individual cores spike.
                val cpuTemps = t.zoneTempsMilliC
                    .filter { it.label.startsWith("cpu", ignoreCase = true) }
                    .map { it.tempMilliC / 1000f }
                val cpuTempC = if (cpuTemps.isEmpty()) null
                    else (cpuTemps.average().toFloat())
                val cpuPeakTempC = cpuTemps.maxOrNull()
                val gpuTempC = t.zoneTempsMilliC
                    .filter {
                        it.label.contains("gpu", ignoreCase = true) ||
                            it.label.contains("kgsl", ignoreCase = true)
                    }
                    .maxOfOrNull { it.tempMilliC / 1000f }
                val batteryW: Double? = run {
                    val ua = t.batteryCurrentUa ?: return@run null
                    val uv = t.batteryVoltageUv ?: return@run null
                    val absUa = if (ua < 0) -ua else ua
                    val mw = (absUa * uv) / 1_000_000_000L
                    mw / 1000.0
                }

                val ramUsedPct = if (t.ramTotalKb > 0) {
                    (100.0 * (t.ramTotalKb - t.ramAvailableKb) / t.ramTotalKb).toInt()
                        .coerceIn(0, 100)
                } else null
                val batteryTempC = t.batteryTempDeciC?.let { it / 10f }
                hudState.value = hudState.value.copy(
                    cpuMaxMhz = cpuMaxKhz / 1000,
                    cpuLoadPct = cpuLoadPct,
                    perCoreMhz = t.perCoreCpuFreqKhz.map { it / 1000 },
                    perCoreLoadPct = t.perCoreLoadPct,
                    gpuMhz = gpuMhz,
                    gpuLoadPct = t.gpuLoadPct,
                    cpuTempC = cpuTempC,
                    cpuPeakTempC = cpuPeakTempC,
                    gpuTempC = gpuTempC,
                    batteryTempC = batteryTempC,
                    maxTempC = maxTempC,
                    batteryW = batteryW,
                    ramUsedPct = ramUsedPct,
                    zones = t.zoneTempsMilliC.map { it.label to it.tempMilliC / 1000f },
                )
            }
        }
    }

    private fun observeBigCorePolicy() {
        serviceScope.launch {
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            val all = report.cpuPolicies.map { it.policyId }.sorted()
            val big = report.cpuPolicies.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }
            // Live-tune is possible if EITHER:
            //  - PServer actually EXECUTES our commands (not just exists)
            //  - libsu root is active (Magisk / KernelSU)
            //  - Direct sysfs write works (rare without root)
            // We ALSO require the user to have opted into Experimental —
            // the ± steppers are powerful enough to leave the device in
            // a bad state if misused.
            //
            // IMPORTANT: we use pServerWriter.isTransactable() (a real
            // no-op transact) NOT getService()!=null. On the Odin 3 and
            // Thor the binder is registered but rejects our UID, so the
            // existence check is a false positive that made the HUD show
            // ± steppers that silently failed on every tap.
            val pserverWorks = pServerWriter.isTransactable()
            val rooted = isRootAvailable()
            // Real direct-write probe: read the first policy's max freq
            // and write it back unchanged. Succeeds only if our UID truly
            // has POSIX write to sysfs (chmod 666 already applied via the
            // unlock script + permissive SELinux). On stock Odin 3 / Thor
            // the file is 664/660 owned system:system, so this is false.
            val directWritable = report.cpuPolicies.firstOrNull()?.let { p ->
                probeSinglePolicyWritable(p.policyId)
            } ?: false
            val capable = pserverWorks || rooted || directWritable
            hudState.value = hudState.value.copy(
                bigCorePolicy = big?.policyId,
                bigCoreCurrentMhz = big?.currentMaxKhz?.div(1000),
                allPolicies = all,
                enabledPolicies = if (hudState.value.enabledPolicies.isEmpty())
                    all.toSet() else hudState.value.enabledPolicies,
            )
            // Re-evaluate canTuneLive whenever the experimental toggle
            // flips, so users can hide/show the steppers without
            // restarting the HUD service.
            userPrefs.experimentalEnabled.collect { experimental ->
                val gated = capable && experimental
                hudState.value = hudState.value.copy(canTuneLive = gated)
                hudEventLog.add(
                    HudEventLog.Level.INFO,
                    "canTuneLive=$gated (pserver=$pserverWorks, root=$rooted, direct=$directWritable, experimental=$experimental)",
                )
            }
        }
    }

    private fun observeUserProfiles() {
        lifecycleScope.launch {
            profileRepository.store.collect { store ->
                val chips = store.profiles
                    .sortedByDescending { it.createdAtMs }
                    .take(4)
                    .map { it.id to it.name }
                hudState.value = hudState.value.copy(quickProfiles = chips)
            }
        }
    }

    /**
     * Step the chosen CPU policies by ±[deltaMhz]. Honors the per-policy
     * chip toggles (defaults to all policies on). Snaps to nearest OPP-
     * table entry per policy so we never request a value the kernel
     * can't accept.
     *
     * Write strategy:
     *  - Root available  → direct libsu shell write. Instant, no user
     *    interaction. This is the path that actually feels like a HUD
     *    knob.
     *  - No root         → fall back to script-per-tap, with a clearer
     *    message explaining what the user has to do. (We deploy the
     *    script every tap; in practice the user should grant root once
     *    and forget about it — covered by the setup-script flow.)
     */
    private fun stepBigCoreMhz(deltaMhz: Int) {
        serviceScope.launch {
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            if (report.cpuPolicies.isEmpty()) {
                hudEventLog.add(HudEventLog.Level.WARN, "No CPU policy found for stepping")
                flashActionMessage("No CPU policy detected.")
                return@launch
            }
            val enabled = hudState.value.enabledPolicies.ifEmpty {
                report.cpuPolicies.map { it.policyId }.toSet()
            }
            val targets = report.cpuPolicies.filter { it.policyId in enabled }
            if (targets.isEmpty()) {
                flashActionMessage("Toggle at least one cluster chip.")
                return@launch
            }

            // Per-policy snap to nearest OPP entry.
            val perPolicyNewKhz = mutableMapOf<Int, Int>()
            val perPolicyOldKhz = mutableMapOf<Int, Int>()
            for (p in targets) {
                val targetKhz = (p.currentMaxKhz / 1000 + deltaMhz) * 1000
                val snapped = p.availableFreqsKhz.minByOrNull { kotlin.math.abs(it - targetKhz) }
                    ?: targetKhz
                perPolicyNewKhz[p.policyId] = snapped
                perPolicyOldKhz[p.policyId] = p.currentMaxKhz
            }

            val rootAvailable = isRootAvailable()
            val summary = perPolicyNewKhz.toSortedMap().entries.joinToString(" ") {
                "p${it.key}=${it.value / 1000}"
            }

            // Write strategy, in priority order:
            //   1. Direct sysfs write — works on rooted devices or when
            //      our app's UID has SELinux + POSIX write access (rare).
            //   2. PServer binder — Odin's vendor service runs in a
            //      permissive SELinux domain when Force SELinux is ON.
            //      We send shell commands and PServer executes them as
            //      root. No Odin Settings UI, no script-per-tap.
            //   3. Root via libsu (if Magisk/KernelSU active).
            //   4. Script-per-tap fallback (no PServer / no root).
            val directOk = tryDirectSysfsWrite(perPolicyNewKhz)
            val pserverOk = !directOk && tryPServerWrite(perPolicyNewKhz, report, summary)
            if (directOk) {
                hudEventLog.add(HudEventLog.Level.ACTION, "direct write OK ($summary)")
                flashActionMessage("Applied: $summary MHz")
            } else if (pserverOk) {
                hudEventLog.add(HudEventLog.Level.ACTION, "PServer write OK ($summary)")
                flashActionMessage("Applied: $summary MHz")
            } else if (rootAvailable) {
                // Direct sysfs write via libsu. Apply the same chmod
                // sandwich the generator uses so vendor perfd can't
                // clobber.
                val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
                val lock = adapter?.chmodLockCpuFreqWrites ?: true
                val cmds = mutableListOf<String>()
                adapter?.perfDaemonsToStopOnWrite?.forEach { cmds += "stop $it" }
                for ((pid, khz) in perPolicyNewKhz) {
                    val path = "/sys/devices/system/cpu/cpufreq/policy$pid/scaling_max_freq"
                    if (lock) cmds += "chmod 666 $path"
                    cmds += "printf %s '$khz' > $path"
                    if (lock) cmds += "chmod 444 $path"
                }
                val res = com.topjohnwu.superuser.Shell.cmd(*cmds.toTypedArray()).exec()
                if (res.isSuccess) {
                    hudEventLog.add(HudEventLog.Level.ACTION, "root write OK ($summary)")
                    flashActionMessage("Applied: $summary MHz")
                } else {
                    val err = res.err.joinToString("; ").ifBlank { "exit ${res.code}" }
                    hudEventLog.add(HudEventLog.Level.ERROR, "root write failed: $err")
                    flashActionMessage("Root write failed: $err")
                }
            } else {
                // No root — deploy a one-off script.
                val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
                val preset = io.github.mayusi.calibratesoc.data.presets.Preset(
                    id = "hud_step_${System.currentTimeMillis()}",
                    name = "HUD step (${if (deltaMhz >= 0) "+" else ""}$deltaMhz)",
                    description = "Stepped: $summary MHz",
                    verification = io.github.mayusi.calibratesoc.data.presets.VerificationTier.USER_CUSTOM,
                    cpuPolicyMaxKhz = perPolicyNewKhz,
                )
                val body = scriptGenerator.generate(preset, report, adapter)
                scriptDeployer.deploy(preset, body)
                hudEventLog.add(HudEventLog.Level.WARN, "no root — script written ($summary)")
                flashActionMessage("No root — need to run setup script. See Settings → Unlock HUD.")
            }

            // Update HUD's notion of current freqs.
            val bigPolicyId = targets.maxByOrNull { it.availableFreqsKhz.maxOrNull() ?: 0 }?.policyId
            val bigNewKhz = bigPolicyId?.let { perPolicyNewKhz[it] }
            hudState.value = hudState.value.copy(
                bigCorePolicy = bigPolicyId ?: hudState.value.bigCorePolicy,
                bigCoreCurrentMhz = bigNewKhz?.let { it / 1000 } ?: hudState.value.bigCoreCurrentMhz,
            )
            tuneHistoryStore.append(
                io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry(
                    appliedAtMs = System.currentTimeMillis(),
                    presetName = "HUD step ${if (deltaMhz >= 0) "+" else ""}$deltaMhz MHz",
                    presetDescription = summary,
                    pathway = when {
                        directOk -> io.github.mayusi.calibratesoc.data.tunables.ApplyPathway.DIRECT_ROOT
                        pserverOk -> io.github.mayusi.calibratesoc.data.tunables.ApplyPathway.DIRECT_ROOT
                        rootAvailable -> io.github.mayusi.calibratesoc.data.tunables.ApplyPathway.DIRECT_ROOT
                        else -> io.github.mayusi.calibratesoc.data.tunables.ApplyPathway.GENERATED_SCRIPT
                    },
                    notes = when {
                        directOk -> "HUD stepper (chmod direct)"
                        pserverOk -> "HUD stepper (PServer binder)"
                        rootAvailable -> "HUD stepper (libsu root)"
                        else -> "HUD stepper (script fallback)"
                    },
                    cpuPolicyMaxKhz = perPolicyNewKhz,
                ),
            )
        }
    }

    /**
     * Wake Odin's game-assistant out of App Freezer so PServer binder
     * calls aren't returning DEAD_OBJECT. Frozen apps reject binder
     * transactions; sending any binder intent un-freezes the target
     * for ~60 seconds. We do this once at HUD start AND right before
     * each PServer call (cheap — just `bindService` with auto-unbind).
     */
    private fun wakeOdinGameAssistant() {
        runCatching {
            val intent = Intent().apply {
                setClassName("com.odin.gameassistant", "com.odin.gameassistant.PServerService")
                action = "com.odin.gameassistant.PSERVER_WAKE"
            }
            // Best-effort: send broadcast (no permission needed for
            // implicit intents to system apps in the queries{} block).
            sendBroadcast(intent)
        }
        runCatching {
            // Backup wake: PackageManager getLaunchIntent → starting an
            // activity un-freezes the target app. We don't actually want
            // to show the activity, so we add FLAG_ACTIVITY_NO_USER_ACTION
            // + don't add NEW_TASK, so it queues but doesn't surface.
            // Most reliable cross-firmware wake.
            packageManager.getLaunchIntentForPackage("com.odin.gameassistant")?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                // Don't actually startActivity — just touching the
                // PackageManager seems to thaw it. If that's not enough
                // we can `startService` instead.
            }
        }
        hudEventLog.add(HudEventLog.Level.INFO, "Sent wake to Odin gameassistant")
    }

    /** Cheap root check — libsu's cached state, no shell spawn. */
    private fun isRootAvailable(): Boolean =
        runCatching { com.topjohnwu.superuser.Shell.getCachedShell()?.isRoot == true }
            .getOrElse { false }

    /**
     * Build one shell command that does the whole HUD step in a single
     * PServer transact: stop vendor perf daemons, chmod each policy's
     * scaling_max_freq to 666, write the new value, chmod back to 444
     * to deny perfd a chance to clamp back. Returns true on PServer
     * status 0.
     */
    private suspend fun tryPServerWrite(
        perPolicyKhz: Map<Int, Int>,
        report: io.github.mayusi.calibratesoc.data.capability.CapabilityReport,
        summary: String,
    ): Boolean {
        if (perPolicyKhz.isEmpty()) return false
        val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
        val daemons = adapter?.perfDaemonsToStopOnWrite.orEmpty()
        val lock = adapter?.chmodLockCpuFreqWrites ?: true
        val parts = mutableListOf<String>()
        daemons.forEach { parts += "stop $it 2>/dev/null" }
        for ((policyId, khz) in perPolicyKhz) {
            val path = "/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq"
            if (lock) parts += "chmod 666 $path 2>/dev/null"
            parts += "printf %s '$khz' > $path"
            if (lock) parts += "chmod 444 $path 2>/dev/null"
        }
        val command = parts.joinToString(" ; ")
        val result = pServerWriter.executeShell(command) ?: run {
            hudEventLog.add(HudEventLog.Level.WARN, "PServer binder not reachable")
            return false
        }
        val (status, stdout) = result
        return if (status == 0) {
            true
        } else {
            hudEventLog.add(HudEventLog.Level.WARN, "PServer status=$status stdout=$stdout")
            false
        }
    }

    /**
     * Try writing every per-policy max freq directly via FileWriter.
     * Returns true only if EVERY write succeeded.
     *
     * We deliberately don't pre-check with canWrite() — that method
     * returns false on sysfs files even when the mode is 666 (it
     * looks at uid/gid match, not just the world-bit). So we just
     * attempt the write and let the IOException tell us.
     */
    private fun tryDirectSysfsWrite(perPolicyKhz: Map<Int, Int>): Boolean {
        if (perPolicyKhz.isEmpty()) return false
        for ((policyId, khz) in perPolicyKhz) {
            val path = java.io.File("/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq")
            val ok = runCatching {
                path.bufferedWriter().use { it.write(khz.toString()) }
                true
            }.getOrElse { false }
            if (!ok) return false
        }
        return true
    }

    /**
     * Non-destructive write probe for one policy: read the current
     * scaling_max_freq and write the SAME value back. No kernel-state
     * change, but it tells us whether our UID can write sysfs at all.
     * Used to decide canTuneLive without arming the PServer breaker.
     */
    private fun probeSinglePolicyWritable(policyId: Int): Boolean {
        val path = java.io.File("/sys/devices/system/cpu/cpufreq/policy$policyId/scaling_max_freq")
        return runCatching {
            if (!path.exists()) return false
            val current = path.readText().trim()
            if (current.isEmpty()) return false
            path.bufferedWriter().use { it.write(current) }
            true
        }.getOrElse { false }
    }


    /**
     * Cycle to the next saved profile and apply it. Wraps around at the
     * end of the chip list so a long-running play session can just keep
     * tapping the same button to try every saved tune.
     */
    private fun cycleNextProfile() {
        val chips = hudState.value.quickProfiles
        if (chips.isEmpty()) {
            flashActionMessage("No saved profiles to cycle through.")
            hudEventLog.add(HudEventLog.Level.WARN, "Profile cycle: nothing to cycle")
            return
        }
        // Find last-applied profile by walking back through the log.
        val lastAppliedId = hudEventLog.entries.value
            .firstNotNullOfOrNull { entry ->
                Regex("^apply ([^ ]+) ").find(entry.message)?.groupValues?.getOrNull(1)
            }
        val idx = chips.indexOfFirst { it.first == lastAppliedId }
        val next = chips[(idx + 1) % chips.size]
        hudEventLog.add(HudEventLog.Level.ACTION, "apply ${next.first} (${next.second})")
        applyProfileViaScript(next.first)
    }

    private fun flashActionMessage(msg: String) {
        hudState.value = hudState.value.copy(lastActionMessage = msg)
        serviceScope.launch {
            kotlinx.coroutines.delay(6_000)
            if (hudState.value.lastActionMessage == msg) {
                hudState.value = hudState.value.copy(lastActionMessage = null)
            }
        }
    }

    private fun applyProfileViaScript(profileId: String) {
        lifecycleScope.launch {
            val profile = profileRepository.snapshot().profiles.firstOrNull { it.id == profileId }
                ?: return@launch
            val report = capabilityProbe.report.value ?: capabilityProbe.refresh()
            val adapter = deviceAdapterRegistry.lookup(report.device.knownHandheldKey)
            val body = scriptGenerator.generate(profile.toPreset(), report, adapter)
            val deployed = scriptDeployer.deploy(profile.toPreset(), body)
            tuneHistoryStore.append(
                io.github.mayusi.calibratesoc.data.tunables.TuneHistoryEntry(
                    appliedAtMs = System.currentTimeMillis(),
                    presetName = profile.name,
                    presetDescription = profile.description,
                    pathway = io.github.mayusi.calibratesoc.data.tunables.ApplyPathway.GENERATED_SCRIPT,
                    notes = "from HUD chip",
                    cpuPolicyMaxKhz = profile.cpuPolicyMaxKhz,
                    cpuPolicyMinKhz = profile.cpuPolicyMinKhz,
                    cpuPolicyGovernor = profile.cpuPolicyGovernor,
                    gpuMaxHz = profile.gpuMaxHz,
                    gpuMinHz = profile.gpuMinHz,
                    gpuGovernor = profile.gpuGovernor,
                ),
            )
            val vs = io.github.mayusi.calibratesoc.data.vendor.OdinIntents
                .vendorSettingsName(this@OverlayService)
            val msg = if (deployed.visibleToOdinPicker) {
                "Wrote ${profile.name} → /sdcard/CalibrateSoC. Open $vs → Run as Root."
            } else {
                "Wrote ${profile.name} to app-private storage. Move it to /sdcard to run it."
            }
            hudState.value = hudState.value.copy(lastActionMessage = msg)
            // Hide the toast line after a few seconds so the HUD doesn't
            // become a wall of status text.
            kotlinx.coroutines.delay(6_000)
            if (hudState.value.lastActionMessage == msg) {
                hudState.value = hudState.value.copy(lastActionMessage = null)
            }
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            hudPrefs.profile.collect { p -> hudState.value = hudState.value.copy(profile = p) }
        }
        lifecycleScope.launch {
            hudPrefs.stepMhz.collect { s -> hudState.value = hudState.value.copy(stepMhz = s) }
        }
        lifecycleScope.launch {
            hudPrefs.enabledPolicies.collect { set ->
                if (set.isNotEmpty()) {
                    hudState.value = hudState.value.copy(enabledPolicies = set)
                }
            }
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
                }
        }
    }

    /**
     * Drag-to-move that doesn't snap. The previous version waited 220 ms
     * before deciding "this is a drag", so the first chunk of finger
     * movement was discarded and the panel teleported to wherever the
     * finger had drifted by the time the latch tripped. Now: track the
     * finger from ACTION_DOWN, mark it a drag once movement exceeds the
     * standard touch slop (8 dp), and feed every subsequent MOVE
     * straight into the WindowManager. Layout updates are coalesced via
     * Choreographer so we never request more than one update per
     * displayed frame — the source of the other half of the chop.
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

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    dragging = false
                    // Don't consume DOWN — let chip clicks see it.
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - touchX
                    val dy = ev.rawY - touchY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx)) {
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
            frameChoreographer.postFrameCallback {
                pendingFrame = false
                runCatching { wm.updateViewLayout(view, params) }
            }
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
 * Snapshot of telemetry the HUD draws. Kept flat (no nested objects)
 * to make Compose recomposition tracking cheap — every field is a
 * primitive or simple list.
 */
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
    /** id → display label for chips. Empty list = no chips row. */
    val quickProfiles: List<Pair<String, String>> = emptyList(),
    /** Brief toast-style line shown right under the chip row after a tap. */
    val lastActionMessage: String? = null,
    /** HUD's own draw rate from Choreographer. Labeled honestly as Hz —
     *  not the game's FPS. When the device is GPU-bound the HUD Hz also
     *  dips because the GPU pipeline is shared. */
    val hudHz: Int = 0,
    /** Stepper state: current big-core max in MHz + +/- step size. Null
     *  until we've resolved the policy from the capability probe. */
    val bigCorePolicy: Int? = null,
    val bigCoreCurrentMhz: Int? = null,
    val stepMhz: Int = 200,
    /** True when we have an actual path to write CPU clocks at runtime
     *  (PServer reachable OR direct sysfs OR root). Drives whether the
     *  HUD shows ± buttons. */
    val canTuneLive: Boolean = false,
    /** Per-policy enable for the stepper. Empty = all-on. */
    val enabledPolicies: Set<Int> = emptySet(),
    /** All policy IDs the kernel exposes, in display order. */
    val allPolicies: List<Int> = emptyList(),
    /** Real game FPS if the DUMP permission has been granted via the
     *  one-time setup script. Null = not granted or no foreground app. */
    val gameFps: Int? = null,
    val gameForegroundPkg: String? = null,
)
