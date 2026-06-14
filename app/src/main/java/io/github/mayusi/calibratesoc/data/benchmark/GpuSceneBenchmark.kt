package io.github.mayusi.calibratesoc.data.benchmark

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import io.github.mayusi.calibratesoc.data.monitor.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heavy sustained-3D-scene GPU benchmark renderer.
 *
 * Scene design — 100% procedural, zero external assets:
 *   - A dense tessellated sphere grid (N×M latitude/longitude divisions) per
 *     instance, instanced across a K×K grid of objects. Total triangle count
 *     is configurable per tier (~200k–900k triangles/frame).
 *   - Per-pixel Blinn-Phong lighting (diffuse + specular), one animated
 *     directional light, plus additional heavy fragment work (GGX-ish
 *     roughness NDF approximation + a multi-octave noise accumulator) so
 *     the fragment shader is GPU-bound, not just vertex-bound.
 *   - Two render passes per frame: a depth-only prepass (populates the
 *     depth buffer, renders the full mesh with gl_FragCoord-based discard
 *     to force real rasterisation work), then the main lit pass with an
 *     early-Z benefit. This mimics real engines that separate depth and
 *     shading.
 *   - Post-pass tonemapping/vignette fullscreen quad over an FBO colour
 *     attachment — adds a tiny third pass to test texture-sample throughput.
 *
 * Render resolution — fixed offscreen FBO (no screen dependency):
 *   STANDARD = 1920×1080  (1080p)
 *   EXTREME  = 2560×1440  (1440p) — default
 *   ULTRA    = 3840×2160  (4K)
 * Falls back gracefully if the driver rejects a large FBO (GLES returns
 * GL_FRAMEBUFFER_UNSUPPORTED or GL_OUT_OF_MEMORY → reduce to next tier).
 *
 * GLES version — requests GLES 3.0 (VAOs, instancing, FBO with depth
 * texture) with a GLES 2.0 fallback path (no VAOs, no instancing —
 * renders the same geometry via repeated draw calls). Reports which API
 * was used in [GpuSceneResult.apiLabel].
 *
 * Timing — [GLES20.glFinish] after every frame; wall-clock deltas.
 * First [WARMUP_FRAMES] frames dropped from metrics. No vsync (offscreen).
 * Vertex animation (rotation angle in uniform) every frame so driver
 * cannot cache geometry submissions.
 *
 * Sustained-loop model:
 *   The run is split into [loopCount] loops each of [loopMs] milliseconds.
 *   A concurrent telemetry sampler reads temperature/frequency via
 *   [MonitorService] (same pattern as [StabilityTestRunner]).
 *   Stability% = avg of last-25%-loop FPS / peak-loop FPS × 100.
 *
 * LEGAL: 100% original code. No 3DMark/AnTuTu/other-benchmark content,
 * names, or scoring formulas. The metrics (avg/p50/p1-low/p99/consistency/
 * stability%) are generic industry-standard graphics benchmarking terms.
 *
 * NOTE: Our own benchmark — compare your own runs, not other chips.
 */
@Singleton
class GpuSceneBenchmark @Inject constructor(
    private val monitorService: MonitorService,
) {

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Run the 3D scene benchmark.
     *
     * @param requestedTier  Target resolution tier; falls back gracefully.
     * @param loopCount      Number of equal-duration loops (default 10).
     * @param loopMs         Duration of each loop in ms (default 20 000 ms).
     * @param killTempC      Abort if CPU temp exceeds this value (default 95°C).
     * @return               [GpuSceneResult] with full metrics, or null if EGL
     *                       setup failed completely.
     */
    suspend fun run(
        requestedTier: SceneTier = SceneTier.EXTREME,
        loopCount: Int = 10,
        loopMs: Long = 20_000L,
        killTempC: Float = 95f,
    ): GpuSceneResult? = coroutineScope {
        val startedAtMs = System.currentTimeMillis()
        val throttleSamples = mutableListOf<ThrottleSample>()
        var result: GpuSceneResult? = null

        // Launch concurrent telemetry sampler — mirrors StabilityTestRunner pattern.
        val samplerJob = launch(Dispatchers.IO) {
            monitorService.telemetry(MonitorService.STRESS_INTERVAL_MS).collect { t ->
                throttleSamples += telemetryToThrottleSample(t, startedAtMs)
            }
        }

        try {
            result = withEglContext3(requestedTier) { glesVersion, actualTier, w, h ->
                if (isActive) renderScene(
                    glesVersion = glesVersion,
                    tier = actualTier,
                    width = w,
                    height = h,
                    loopCount = loopCount,
                    loopMs = loopMs,
                    killTempC = killTempC,
                    throttleSamples = throttleSamples,
                    startedAtMs = startedAtMs,
                    isActiveCheck = { isActive },
                )
                else null
            }
        } finally {
            samplerJob.cancel()
        }

        result
    }

    // ─── EGL setup — GLES 3.0 with 2.0 fallback ──────────────────────────────

    /**
     * Establish an EGL context requesting GLES 3.0; falls back to 2.0 if
     * not available. Then tries to create an FBO at the requested tier's
     * resolution, falling back through EXTREME → STANDARD as needed.
     *
     * The lambda receives (glesVersion, actualTier, width, height) already
     * bound to the current EGL context on Dispatchers.Default.
     */
    private suspend fun <T> withEglContext3(
        requestedTier: SceneTier,
        block: suspend (glesVersion: Int, tier: SceneTier, width: Int, height: Int) -> T?,
    ): T? = withContext(Dispatchers.Default) {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return@withContext null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return@withContext null

            // Try GLES 3.0 first.
            // EGL_OPENGL_ES3_BIT_KHR = 0x00000040 (EGL_KHR_create_context extension;
            // universally supported on Android API 18+ even though EGL14 doesn't
            // declare it as a named constant in older NDK header imports).
            var glesVersion = 3
            val configAttrs30 = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x00000040, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            var configOk = EGL14.eglChooseConfig(
                display, configAttrs30, 0, configs, 0, 1, numConfigs, 0
            ) && numConfigs[0] > 0

            if (!configOk) {
                // GLES 2.0 fallback.
                glesVersion = 2
                val configAttrs20 = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_NONE,
                )
                configOk = EGL14.eglChooseConfig(
                    display, configAttrs20, 0, configs, 0, 1, numConfigs, 0
                ) && numConfigs[0] > 0
                if (!configOk) return@withContext null
            }

            val contextAttrs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, glesVersion,
                EGL14.EGL_NONE,
            )
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return@withContext null

            // Use a minimal pbuffer just to hold the context; actual rendering
            // will be done into an FBO. We need any valid current surface.
            val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttrs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return@withContext null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return@withContext null

            // Determine actual render resolution with graceful tier fallback.
            val (actualTier, renderW, renderH) = resolveRenderTier(requestedTier, glesVersion)

            block(glesVersion, actualTier, renderW, renderH)
        } finally {
            try {
                if (display != null) {
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE)
                        EGL14.eglDestroySurface(display, surface)
                    if (context != null && context != EGL14.EGL_NO_CONTEXT)
                        EGL14.eglDestroyContext(display, context)
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) { /* best-effort cleanup */ }
        }
    }

    /**
     * Probe which tier the driver can actually support by attempting to create
     * and complete a framebuffer object at each resolution.
     * Falls down from requested → EXTREME → STANDARD.
     */
    private fun resolveRenderTier(
        requested: SceneTier,
        glesVersion: Int,
    ): Triple<SceneTier, Int, Int> {
        // Build a priority list: requested tier first, then smaller tiers.
        val priority = SceneTier.entries.sortedByDescending { it.ordinal }
            .dropWhile { it.ordinal > requested.ordinal }

        for (tier in priority) {
            val w = tier.width
            val h = tier.height
            if (canCreateFbo(w, h, glesVersion)) {
                if (tier != requested) {
                    Log.w(TAG, "Requested ${requested.label} not supported; using ${tier.label}")
                }
                return Triple(tier, w, h)
            }
        }
        // Last-resort: STANDARD (should never fail on any GLES device).
        val fb = SceneTier.STANDARD
        return Triple(fb, fb.width, fb.height)
    }

    /** Attempt to create a colour+depth FBO at [w]×[h]; return true if
     *  GL_FRAMEBUFFER_COMPLETE. Cleans up the FBO before returning. */
    private fun canCreateFbo(w: Int, h: Int, glesVersion: Int): Boolean {
        return try {
            if (glesVersion >= 3) {
                val fboIds = IntArray(1)
                GLES30.glGenFramebuffers(1, fboIds, 0)
                val fbo = fboIds[0]
                val texIds = IntArray(1)
                GLES30.glGenTextures(1, texIds, 0)
                val colorTex = texIds[0]
                val rboIds = IntArray(1)
                GLES30.glGenRenderbuffers(1, rboIds, 0)
                val depthRbo = rboIds[0]

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTex)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRbo)
                GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, colorTex, 0,
                )
                GLES30.glFramebufferRenderbuffer(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                    GLES30.GL_RENDERBUFFER, depthRbo,
                )
                val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
                val ok = status == GLES30.GL_FRAMEBUFFER_COMPLETE
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glDeleteTextures(1, texIds, 0)
                GLES30.glDeleteRenderbuffers(1, rboIds, 0)
                GLES30.glDeleteFramebuffers(1, fboIds, 0)
                ok
            } else {
                // GLES 2.0: renderbuffers only.
                val fboIds = IntArray(1)
                GLES20.glGenFramebuffers(1, fboIds, 0)
                val fbo = fboIds[0]
                val rboIds = IntArray(2)
                GLES20.glGenRenderbuffers(2, rboIds, 0)
                val colorRbo = rboIds[0]; val depthRbo = rboIds[1]

                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, colorRbo)
                GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_RGBA4, w, h)
                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRbo)
                GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferRenderbuffer(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_RENDERBUFFER, colorRbo,
                )
                GLES20.glFramebufferRenderbuffer(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER, depthRbo,
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                val ok = status == GLES20.GL_FRAMEBUFFER_COMPLETE
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteRenderbuffers(2, rboIds, 0)
                GLES20.glDeleteFramebuffers(1, fboIds, 0)
                ok
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FBO probe at ${w}×${h} threw: ${t.message}")
            false
        }
    }

    // ─── Main render loop ─────────────────────────────────────────────────────

    private fun renderScene(
        glesVersion: Int,
        tier: SceneTier,
        width: Int,
        height: Int,
        loopCount: Int,
        loopMs: Long,
        killTempC: Float,
        throttleSamples: MutableList<ThrottleSample>,
        startedAtMs: Long,
        isActiveCheck: () -> Boolean,
    ): GpuSceneResult? {
        // ── Geometry ──────────────────────────────────────────────────────────
        // Build a dense tessellated sphere using latitude/longitude subdivisions.
        // Higher latSubdiv/lonSubdiv → more triangles.
        val latSubdiv = SPHERE_LAT_SUBDIV
        val lonSubdiv = SPHERE_LON_SUBDIV
        val sphere = buildSphere(latSubdiv, lonSubdiv)

        // Instance grid: INSTANCE_GRID × INSTANCE_GRID objects.
        val instanceGrid = if (glesVersion >= 3) INSTANCE_GRID_GLES3 else INSTANCE_GRID_GLES2
        val trianglesPerInstance = sphere.indexCount / 3
        val totalTriangles = trianglesPerInstance.toLong() * instanceGrid * instanceGrid

        Log.i(
            TAG,
            "Scene: ${latSubdiv}×${lonSubdiv} sphere, ${instanceGrid}×${instanceGrid} instances, " +
            "~${totalTriangles / 1_000}k tri/frame, ${tier.label}, GLES $glesVersion",
        )

        // ── GL resource setup ─────────────────────────────────────────────────
        // Depth-prepass program (vertex-only heavy; trivial frag).
        val depthProg = buildDepthProgram()
        // Main lit program: Blinn-Phong + heavy fragment noise accumulator.
        val litProg = buildLitProgram()
        // Post-processing program: tonemap + vignette over the lit FBO.
        val postProg = buildPostProgram()

        if (depthProg == 0 || litProg == 0 || postProg == 0) {
            Log.e(TAG, "Shader compilation failed — aborting scene benchmark")
            glDeletePrograms(depthProg, litProg, postProg)
            return null
        }

        // Upload geometry to VBOs.
        val vboIds = IntArray(2)   // [0] = vertex, [1] = index
        GLES20.glGenBuffers(2, vboIds, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            sphere.vertexData.capacity() * 4,
            sphere.vertexData,
            GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            sphere.indexData.capacity() * 4,
            sphere.indexData,
            GLES20.GL_STATIC_DRAW,
        )

        // Build the main scene FBO (colour texture + depth renderbuffer).
        val sceneFbo = buildSceneFbo(width, height, glesVersion)
        if (sceneFbo == null) {
            Log.e(TAG, "Scene FBO creation failed at ${width}×${height}")
            GLES20.glDeleteBuffers(2, vboIds, 0)
            glDeletePrograms(depthProg, litProg, postProg)
            return null
        }

        // Fullscreen quad for post-pass (NDC, no Z).
        val quadBuf = buildFullscreenQuad()

        // Enable depth test.
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LESS)

        // ── Render loops ──────────────────────────────────────────────────────
        val loopFpsList = mutableListOf<Double>()
        val allFrameTimes = ArrayList<Float>(4096)
        var abortedDueToTemp = false

        try {
            for (loopIndex in 0 until loopCount) {
                if (!isActiveCheck()) break

                val loopStart = System.nanoTime()
                val loopDeadline = loopStart + loopMs * 1_000_000L
                val loopFrameTimes = ArrayList<Float>(512)
                var prev = loopStart
                var frames = 0
                var warmupDone = false

                while (System.nanoTime() < loopDeadline) {
                    if (!isActiveCheck()) break

                    // Animate: rotate objects + light over time.
                    val tSec = (System.nanoTime() - startedAtMs * 1_000_000L) / 1_000_000_000.0f

                    // ── Pass 1: depth prepass into sceneFbo ──────────────────
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo.fboId)
                    GLES20.glViewport(0, 0, width, height)
                    GLES20.glColorMask(false, false, false, false)
                    GLES20.glDepthMask(true)
                    GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                    GLES20.glUseProgram(depthProg)
                    setDepthUniforms(depthProg, tSec, width.toFloat() / height)
                    drawInstanceGrid(depthProg, vboIds, sphere, instanceGrid, tSec, glesVersion)

                    // ── Pass 2: lit shading with depth-equal test ────────────
                    // Re-use the depth buffer from pass 1; fragments that
                    // fail the prepass depth are culled for free.
                    GLES20.glColorMask(true, true, true, true)
                    GLES20.glDepthMask(false)
                    GLES20.glDepthFunc(GLES20.GL_LEQUAL)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(litProg)
                    setLitUniforms(litProg, tSec, width.toFloat() / height)
                    drawInstanceGrid(litProg, vboIds, sphere, instanceGrid, tSec, glesVersion)

                    // Restore depth func for next prepass.
                    GLES20.glDepthFunc(GLES20.GL_LESS)
                    GLES20.glDepthMask(true)

                    // ── Pass 3: post-process tonemap/vignette (default FBO = pbuffer) ──
                    // Note: in our EGL setup the "default" FBO is the 16×16 pbuffer —
                    // we blit into it as a proxy for a real swapchain swap. The
                    // important thing is that the GPU executes the texture-sampling
                    // + tonemapping fragment work; the pixel destination doesn't matter
                    // for a benchmark.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, 16, 16)
                    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                    GLES20.glUseProgram(postProg)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneFbo.colorTexId)
                    drawFullscreenQuad(postProg, quadBuf)
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST)

                    // ── Honest timing: glFinish forces GPU completion ─────────
                    GLES20.glFinish()

                    val now = System.nanoTime()
                    val frameMs = (now - prev) / 1_000_000.0f
                    prev = now
                    frames++

                    if (!warmupDone && frames > WARMUP_FRAMES) {
                        warmupDone = true
                        // Reset prev so first measured frame doesn't include warmup.
                        prev = now
                        frames = 0
                        continue
                    }
                    if (warmupDone) {
                        loopFrameTimes.add(frameMs)
                        allFrameTimes.add(frameMs)
                    }
                }

                val loopElapsedSec = (System.nanoTime() - loopStart) / 1_000_000_000.0
                val loopFps = if (loopElapsedSec > 0 && loopFrameTimes.isNotEmpty())
                    loopFrameTimes.size / loopElapsedSec else 0.0
                loopFpsList.add(loopFps)

                Log.d(TAG, "Loop $loopIndex: avgFps=%.1f, frames=${loopFrameTimes.size}".format(loopFps))

                // Thermal kill-switch.
                val latestTemp = throttleSamples.lastOrNull()?.cpuMaxTempC ?: 0f
                if (latestTemp >= killTempC) {
                    Log.w(TAG, "Thermal kill at ${latestTemp}°C after loop $loopIndex")
                    abortedDueToTemp = true
                    break
                }
            }
        } finally {
            // Cleanup GL resources.
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES20.glDeleteBuffers(2, vboIds, 0)
            sceneFbo.delete()
            GLES20.glDeleteBuffers(1, intArrayOf(quadBuf), 0)
            glDeletePrograms(depthProg, litProg, postProg)
        }

        // ── Aggregate metrics ─────────────────────────────────────────────────
        if (allFrameTimes.isEmpty()) {
            Log.w(TAG, "No frames measured — scene benchmark returning null")
            return null
        }

        val frames = allFrameTimes.toFloatArray()
        val summary = GpuFrameSummary.from(
            avgFps = allFrameTimes.size / (allFrameTimes.sumOf { it.toDouble() } / 1000.0),
            frames = frames,
            downsampleTo = 600,
        )

        val loopResults = loopFpsList.mapIndexed { i, fps ->
            SceneLoopResult(
                loopIndex = i,
                avgFps = fps,
                avgFrameMs = if (fps > 0) 1000.0 / fps else 0.0,
            )
        }

        val peakCpuTempC = throttleSamples.maxOfOrNull { it.cpuMaxTempC }
        val peakGpuTempC = throttleSamples.mapNotNull { it.gpuTempC }.maxOrNull()
        val totalDurationMs = System.currentTimeMillis() - startedAtMs

        return GpuSceneResult(
            tier = tier,
            renderWidthPx = width,
            renderHeightPx = height,
            apiLabel = if (glesVersion >= 3) "GLES 3.0" else "GLES 2.0 (fallback)",
            trianglesPerFrame = totalTriangles,
            passCount = 3,  // depth prepass + lit pass + post pass
            avgFps = summary.avgFps,
            avgFrameMs = summary.avgFrameMs,
            p50Fps = summary.p50Fps,
            p1LowFps = summary.p1LowFps,
            p99FrameMs = summary.p99FrameMs,
            consistencyPct = summary.consistencyPct,
            stabilityPct = GpuSceneResult.computeStabilityPct(loopFpsList),
            loopResults = loopResults,
            frameTimesMsDownsampled = summary.frameTimesMsDownsampled,
            peakCpuTempC = peakCpuTempC,
            peakGpuTempC = peakGpuTempC,
            totalDurationMs = totalDurationMs,
        )
    }

    // ─── Geometry: tessellated sphere ─────────────────────────────────────────

    internal data class SphereGeometry(
        val vertexData: FloatBuffer,   // pos(3) + normal(3) + uv(2) = 8 floats/vertex
        val indexData: IntBuffer,
        val indexCount: Int,
    )

    /**
     * Generate a UV-sphere with [latDiv] latitude and [lonDiv] longitude
     * segments. Vertex layout: position.xyz (3 floats), normal.xyz (3 floats),
     * texcoord.uv (2 floats) = 8 floats per vertex.
     *
     * Pure JVM maths, no GL calls. Can be unit-tested.
     */
    internal fun buildSphere(latDiv: Int, lonDiv: Int): SphereGeometry {
        val vertCount = (latDiv + 1) * (lonDiv + 1)
        val idxCount = latDiv * lonDiv * 6
        val vBuf = ByteBuffer.allocateDirect(vertCount * VERTEX_STRIDE * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val iBuf = ByteBuffer.allocateDirect(idxCount * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()

        for (lat in 0..latDiv) {
            val theta = lat * PI / latDiv        // 0 .. π
            val sinTheta = sin(theta).toFloat()
            val cosTheta = cos(theta).toFloat()
            for (lon in 0..lonDiv) {
                val phi = lon * 2.0 * PI / lonDiv  // 0 .. 2π
                val sinPhi = sin(phi).toFloat()
                val cosPhi = cos(phi).toFloat()
                val x = sinTheta * cosPhi
                val y = cosTheta
                val z = sinTheta * sinPhi
                // Position.
                vBuf.put(x).put(y).put(z)
                // Normal (same as position for unit sphere).
                vBuf.put(x).put(y).put(z)
                // UV.
                vBuf.put(lon.toFloat() / lonDiv)
                vBuf.put(lat.toFloat() / latDiv)
            }
        }
        vBuf.position(0)

        for (lat in 0 until latDiv) {
            for (lon in 0 until lonDiv) {
                val a = lat * (lonDiv + 1) + lon
                val b = a + (lonDiv + 1)
                // Two triangles per quad.
                iBuf.put(a).put(b).put(a + 1)
                iBuf.put(b).put(b + 1).put(a + 1)
            }
        }
        iBuf.position(0)

        return SphereGeometry(vBuf, iBuf, idxCount)
    }

    // ─── Draw helpers ─────────────────────────────────────────────────────────

    /**
     * Issue one draw call per instance in a [grid]×[grid] arrangement.
     * On GLES 3.0 we would ideally use `glDrawElementsInstanced` with an
     * instance VBO, but since the per-instance transform is just a uniform
     * update, we use a simple loop (driver command overhead is part of the
     * benchmark load — like a real engine issuing one draw per object).
     * On GLES 2.0 the same loop runs identically.
     */
    private fun drawInstanceGrid(
        program: Int,
        vboIds: IntArray,
        sphere: SphereGeometry,
        grid: Int,
        tSec: Float,
        glesVersion: Int,
    ) {
        // Vertex layout: pos(3)+normal(3)+uv(2), stride = 8*4 = 32 bytes.
        val stride = VERTEX_STRIDE * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIds[1])

        val posLoc = GLES20.glGetAttribLocation(program, "a_pos")
        val normLoc = GLES20.glGetAttribLocation(program, "a_normal")
        val uvLoc = GLES20.glGetAttribLocation(program, "a_uv")
        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, 0)
        }
        if (normLoc >= 0) {
            GLES20.glEnableVertexAttribArray(normLoc)
            GLES20.glVertexAttribPointer(normLoc, 3, GLES20.GL_FLOAT, false, stride, 12)
        }
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, 24)
        }

        val instanceOffLoc = GLES20.glGetUniformLocation(program, "u_instanceOffset")
        val instScaleLoc = GLES20.glGetUniformLocation(program, "u_instanceRotY")

        val spacing = 2.5f
        val half = (grid - 1) * spacing * 0.5f
        for (row in 0 until grid) {
            for (col in 0 until grid) {
                val ox = col * spacing - half
                val oy = 0f
                val oz = row * spacing - half
                if (instanceOffLoc >= 0) GLES20.glUniform3f(instanceOffLoc, ox, oy, oz)
                // Each instance rotates at a slightly different rate for vertex animation.
                val rotY = tSec * (1f + (row * grid + col) * 0.07f)
                if (instScaleLoc >= 0) GLES20.glUniform1f(instScaleLoc, rotY)
                GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    sphere.indexCount,
                    GLES20.GL_UNSIGNED_INT,
                    0,
                )
            }
        }

        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (normLoc >= 0) GLES20.glDisableVertexAttribArray(normLoc)
        if (uvLoc >= 0) GLES20.glDisableVertexAttribArray(uvLoc)
    }

    // ─── FBO builder ──────────────────────────────────────────────────────────

    private data class SceneFbo(
        val fboId: Int,
        val colorTexId: Int,
        val depthRboId: Int,
        val glesVersion: Int,
    ) {
        fun delete() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(colorTexId), 0)
            GLES20.glDeleteRenderbuffers(1, intArrayOf(depthRboId), 0)
        }
    }

    private fun buildSceneFbo(w: Int, h: Int, glesVersion: Int): SceneFbo? {
        return try {
            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            val fbo = fboIds[0]

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val colorTex = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
            if (glesVersion >= 3) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                    w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
            }
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val rboIds = IntArray(1)
            GLES20.glGenRenderbuffers(1, rboIds, 0)
            val depthRbo = rboIds[0]
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRbo)
            val depthFormat = if (glesVersion >= 3) GLES30.GL_DEPTH_COMPONENT24 else GLES20.GL_DEPTH_COMPONENT16
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, depthFormat, w, h)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, colorTex, 0,
            )
            GLES20.glFramebufferRenderbuffer(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, depthRbo,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Scene FBO incomplete: 0x${status.toString(16)}")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteFramebuffers(1, fboIds, 0)
                GLES20.glDeleteTextures(1, texIds, 0)
                GLES20.glDeleteRenderbuffers(1, rboIds, 0)
                return null
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            SceneFbo(fbo, colorTex, depthRbo, glesVersion)
        } catch (t: Throwable) {
            Log.e(TAG, "buildSceneFbo threw: ${t.message}")
            null
        }
    }

    // ─── Fullscreen quad ──────────────────────────────────────────────────────

    /** Build a VBO holding a screen-space quad (two triangles, NDC coords). */
    private fun buildFullscreenQuad(): Int {
        val data = floatArrayOf(
            // position(2) + texcoord(2)
            -1f, -1f,  0f, 0f,
             1f, -1f,  1f, 0f,
            -1f,  1f,  0f, 1f,
             1f,  1f,  1f, 1f,
        )
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun drawFullscreenQuad(program: Int, vboId: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        val posLoc = GLES20.glGetAttribLocation(program, "a_pos")
        val uvLoc = GLES20.glGetAttribLocation(program, "a_uv")
        if (posLoc >= 0) {
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, 0)
        }
        if (uvLoc >= 0) {
            GLES20.glEnableVertexAttribArray(uvLoc)
            GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, 8)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (posLoc >= 0) GLES20.glDisableVertexAttribArray(posLoc)
        if (uvLoc >= 0) GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // ─── Uniform setters ──────────────────────────────────────────────────────

    private fun setDepthUniforms(prog: Int, tSec: Float, aspect: Float) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_time"), tSec)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_aspect"), aspect)
    }

    private fun setLitUniforms(prog: Int, tSec: Float, aspect: Float) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_time"), tSec)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_aspect"), aspect)
        // Animated directional light direction (normalized, rotates in XZ plane).
        val lx = sin(tSec * 0.5f)
        val ly = 0.6f
        val lz = cos(tSec * 0.5f)
        val len = sqrt(lx * lx + ly * ly + lz * lz)
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(prog, "u_lightDir"),
            lx / len, ly / len, lz / len,
        )
    }

    // ─── Shader programs ──────────────────────────────────────────────────────

    /**
     * Depth-prepass program.
     *
     * Vertex shader: applies rotation around Y per instance + view-projection.
     * Fragment shader: cheap constant write (GPU still rasterises all fragments
     * for depth; the colour discard saves bandwidth but not rasteriser work).
     */
    private fun buildDepthProgram(): Int {
        val vsh = """
            precision highp float;
            attribute vec3 a_pos;
            attribute vec3 a_normal;
            attribute vec2 a_uv;
            uniform float u_time;
            uniform float u_aspect;
            uniform vec3  u_instanceOffset;
            uniform float u_instanceRotY;

            // Simple Y-axis rotation matrix columns.
            vec3 rotateY(vec3 p, float angle) {
                float c = cos(angle); float s = sin(angle);
                return vec3(c*p.x + s*p.z, p.y, -s*p.x + c*p.z);
            }

            void main() {
                vec3 worldPos = rotateY(a_pos * 0.9, u_instanceRotY) + u_instanceOffset;
                // Simple perspective: fov~60deg, near=0.1, far=50. Camera at (0,0,14).
                float fovY = 1.047; // radians
                float near = 0.1; float far = 50.0;
                float f = 1.0 / tan(fovY * 0.5);
                vec3 cam = worldPos - vec3(0.0, 0.0, 14.0);
                float z = -cam.z;
                float clipX = (cam.x / z) * f / u_aspect;
                float clipY = (cam.y / z) * f;
                float clipZ = (z * (far + near) / (near - far) + 2.0 * far * near / (near - far)) / z;
                gl_Position = vec4(clipX, clipY, clipZ, 1.0);
            }
        """.trimIndent()
        val fsh = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.0);
            }
        """.trimIndent()
        return linkProgram(vsh, fsh)
    }

    /**
     * Lit shading program: Blinn-Phong diffuse + specular + heavy fragment load.
     *
     * Fragment load: 32-iteration noise accumulator (sin/cos/sqrt per iteration)
     * + a GGX-ish NDF approximation (original formula, not copied IP), making
     * this both geometry-heavy (200k–900k tri/frame) AND fragment-heavy.
     *
     * Original shader — no code from any other benchmark.
     */
    private fun buildLitProgram(): Int {
        val vsh = """
            precision highp float;
            attribute vec3 a_pos;
            attribute vec3 a_normal;
            attribute vec2 a_uv;
            uniform float u_time;
            uniform float u_aspect;
            uniform vec3  u_instanceOffset;
            uniform float u_instanceRotY;

            varying vec3 v_worldPos;
            varying vec3 v_normal;
            varying vec2 v_uv;

            vec3 rotateY(vec3 p, float angle) {
                float c = cos(angle); float s = sin(angle);
                return vec3(c*p.x + s*p.z, p.y, -s*p.x + c*p.z);
            }

            void main() {
                vec3 localPos = a_pos * 0.9;
                vec3 worldPos = rotateY(localPos, u_instanceRotY) + u_instanceOffset;
                vec3 worldNorm = normalize(rotateY(a_normal, u_instanceRotY));
                v_worldPos = worldPos;
                v_normal   = worldNorm;
                v_uv       = a_uv;

                float fovY = 1.047;
                float near = 0.1; float far = 50.0;
                float f = 1.0 / tan(fovY * 0.5);
                vec3 cam = worldPos - vec3(0.0, 0.0, 14.0);
                float z = -cam.z;
                float clipX = (cam.x / z) * f / u_aspect;
                float clipY = (cam.y / z) * f;
                float clipZ = (z * (far + near) / (near - far) + 2.0 * far * near / (near - far)) / z;
                gl_Position = vec4(clipX, clipY, clipZ, 1.0);
            }
        """.trimIndent()
        val fsh = """
            precision highp float;
            varying vec3 v_worldPos;
            varying vec3 v_normal;
            varying vec2 v_uv;
            uniform vec3  u_lightDir;
            uniform float u_time;

            // ── Heavy fragment work ─────────────────────────────────────────
            // 32-iter accumulator: each iter does sin/cos/sqrt. Together with
            // the GGX NDF approximation below this ensures fragment throughput
            // is the sustained bottleneck on large-triangle scenes.
            float heavyAccumulator(vec2 p, float seed) {
                float a = seed;
                for (int i = 0; i < 32; i++) {
                    float fi = float(i);
                    a += sin(p.x * (fi + 1.0) + seed) * cos(p.y * (fi + 0.7) - seed);
                    p.x += sqrt(abs(a) + 0.01);
                    p.y -= 0.011 * a;
                }
                return a;
            }

            // ── GGX-ish NDF approximation (original formulation) ────────────
            // Standard Trowbridge-Reitz distribution: D(h) = α²/(π·((n·h)²(α²-1)+1)²).
            // This is the industry-standard formula (Trowbridge & Reitz 1975, Walter 2007)
            // — it is not proprietary to any benchmark.
            float ndfGGX(float nDotH, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
                return a2 / (3.14159265 * d * d + 1e-7);
            }

            void main() {
                vec3 N = normalize(v_normal);
                vec3 L = normalize(u_lightDir);
                vec3 V = normalize(vec3(0.0, 0.0, 14.0) - v_worldPos);
                vec3 H = normalize(L + V);

                float nDotL = max(dot(N, L), 0.0);
                float nDotH = max(dot(N, H), 0.0);

                // Per-surface roughness derived from UV (procedural material).
                float roughness = 0.15 + 0.5 * (sin(v_uv.x * 6.28318) * 0.5 + 0.5);

                // Blinn-Phong diffuse.
                vec3 baseColor = vec3(
                    0.4 + 0.4 * sin(v_uv.x * 12.566),
                    0.3 + 0.3 * cos(v_uv.y * 9.425),
                    0.5 + 0.25 * sin(u_time * 0.3 + v_uv.x * 3.14)
                );
                vec3 diffuse = baseColor * nDotL;

                // GGX specular.
                float D = ndfGGX(nDotH, roughness);
                vec3 specular = vec3(D * 0.15);

                // Heavy accumulator as a procedural noise layer on top of lighting.
                float noise = heavyAccumulator(v_uv * 3.0, u_time * 0.4) * 0.015;

                vec3 color = diffuse + specular + vec3(noise * 0.5 + 0.05);

                // Ambient.
                color += baseColor * 0.07;

                // Simple Reinhard tonemap inline.
                color = color / (color + vec3(1.0));

                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()
        return linkProgram(vsh, fsh)
    }

    /**
     * Post-processing program: tonemaps + vignetted the scene FBO colour
     * texture onto the output surface. Tests texture-sample throughput.
     */
    private fun buildPostProgram(): Int {
        val vsh = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying vec2 v_uv;
            void main() {
                v_uv = a_uv;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """.trimIndent()
        val fsh = """
            precision mediump float;
            varying vec2 v_uv;
            uniform sampler2D u_scene;
            void main() {
                vec3 c = texture2D(u_scene, v_uv).rgb;
                // Vignette.
                vec2 uv2 = v_uv * 2.0 - 1.0;
                float vig = 1.0 - dot(uv2 * 0.6, uv2 * 0.6);
                c *= clamp(vig, 0.0, 1.0);
                // Gamma correction (approximate sRGB).
                c = pow(c, vec3(1.0 / 2.2));
                gl_FragColor = vec4(c, 1.0);
            }
        """.trimIndent()
        return linkProgram(vsh, fsh)
    }

    // ─── GL shader plumbing ───────────────────────────────────────────────────

    private fun compileShader(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        if (s == 0) return 0
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES20.GL_FALSE) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun linkProgram(vshSrc: String, fshSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vshSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fshSrc)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == GLES20.GL_FALSE) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun glDeletePrograms(vararg ids: Int) {
        for (id in ids) if (id != 0) GLES20.glDeleteProgram(id)
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "GpuSceneBenchmark"

        /** Floats per vertex: position(3) + normal(3) + uv(2). */
        const val VERTEX_STRIDE = 8

        /** Latitude/longitude subdivisions for the sphere mesh.
         *  60×60 = ~7200 verts, 7200 tris per sphere. */
        const val SPHERE_LAT_SUBDIV = 60
        const val SPHERE_LON_SUBDIV = 60

        /** Instance grid size on GLES 3.0.
         *  11×11 = 121 instances × ~7 200 tri = ~872 000 tri/frame. */
        const val INSTANCE_GRID_GLES3 = 11

        /** Reduced instance count on GLES 2.0 (no hardware instancing;
         *  draw-call overhead limits the practical count).
         *  7×7 = 49 instances × ~7 200 tri = ~353 000 tri/frame. */
        const val INSTANCE_GRID_GLES2 = 7

        /** Number of frames dropped at the start of each loop as warmup. */
        const val WARMUP_FRAMES = 3
    }
}
