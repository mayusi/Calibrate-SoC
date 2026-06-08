package io.github.mayusi.calibratesoc.data.benchmark

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offscreen GLES 2.0 fragment-shader-heavy benchmark. Baseline [run] and
 * [runDetailed] render at 800×800 with ~80 shader iterations per pixel.
 * Stress variant [runStress] can render at higher resolution (e.g. 1440×1440)
 * with more iterations (e.g. 256) to achieve sustained peak GPU load for
 * thermal stability testing.
 *
 * Why this and not Vulkan compute: Vulkan needs vkSDK + glslang to
 * pre-compile shaders, more setup; GLES 2.0 is universal and the
 * fragment-shader workload meaningfully exercises the same GPU
 * pipeline games do. The trig+sqrt loop in the fragment shader is
 * the same pattern Cemu / yuzu / RPCS3 hammer.
 *
 * Runs entirely on a background thread with its own EGL context;
 * no SurfaceView, no Activity lifecycle entanglement. Failure modes
 * (no GLES available, EGL create fails) return null FPS and the
 * runner reports "GPU bench skipped — no offscreen GLES".
 */
@Singleton
class GpuTriangleStorm @Inject constructor() {

    suspend fun run(durationMs: Long = 5_000L): Double? =        // keep: stability uses this
        runDetailed(durationMs)?.avgFps

    /**
     * Rich variant: captures per-frame wall-clock deltas (glFinish makes
     * them honest, not pipelined) so the runner can compute percentiles,
     * 1% low, and frame-pacing consistency. The bare [run] above delegates
     * here and just keeps the avgFps.
     */
    suspend fun runDetailed(durationMs: Long = 8_000L): GpuFrameResult? =
        withEglContext { renderLoopFrames(durationMs) }

    /**
     * Heavier stress variant: renders at a higher resolution (surfacePx×surfacePx)
     * with more fragment shader iterations (shaderIterations) to achieve sustained
     * peak GPU load. Used by the stability test when CPU is already pinned to create
     * a truly combined CPU+GPU thermal saturation test.
     * Returns avg FPS or null if GLES setup fails.
     */
    suspend fun runStress(
        durationMs: Long,
        surfacePx: Int = 1440,
        shaderIterations: Int = 256,
    ): Double? = withEglContext(surfaceWidth = surfacePx, surfaceHeight = surfacePx) { renderLoopFramesStress(durationMs, surfacePx, shaderIterations) }?.avgFps

    /**
     * Draw-call ceiling: count how many trivial draw calls the CPU can
     * submit per second when the fragment shader does nothing. The pixel
     * pipeline is starved by the geometry pipeline / driver call overhead
     * — this gives the CPU's draw-call budget. Compare against [run]'s
     * fragment-heavy gpuFps to see whether a real workload would be
     * GPU-bound or CPU-bound.
     */
    suspend fun runDrawCallCeiling(durationMs: Long = 2_000L): Double? =
        withEglContext { renderLoopDrawCallCeiling(durationMs) }

    private suspend fun <T> withEglContext(
        surfaceWidth: Int = WIDTH,
        surfaceHeight: Int = HEIGHT,
        block: () -> T?
    ): T? = withContext(Dispatchers.Default) {
        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return@withContext null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return@withContext null

            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return@withContext null
            }

            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return@withContext null

            // Clamp pbuffer dimensions to a safe range. Some GLES drivers have lower limits.
            val clampedWidth = surfaceWidth.coerceIn(800, 2048)
            val clampedHeight = surfaceHeight.coerceIn(800, 2048)

            var surfAttrs = intArrayOf(EGL14.EGL_WIDTH, clampedWidth, EGL14.EGL_HEIGHT, clampedHeight, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttrs, 0)

            // Fallback to 800×800 if the requested size fails (driver limitation).
            if (surface == EGL14.EGL_NO_SURFACE && (clampedWidth != WIDTH || clampedHeight != HEIGHT)) {
                android.util.Log.w("GpuTriangleStorm", "pbuffer at ${clampedWidth}×${clampedHeight} failed, falling back to ${WIDTH}×${HEIGHT}")
                surfAttrs = intArrayOf(EGL14.EGL_WIDTH, WIDTH, EGL14.EGL_HEIGHT, HEIGHT, EGL14.EGL_NONE)
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttrs, 0)
            }
            if (surface == EGL14.EGL_NO_SURFACE) return@withContext null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return@withContext null

            block()
        } finally {
            try {
                if (display != null) {
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) { /* clean-up best-effort */ }
        }
    }

    private fun renderLoopDrawCallCeiling(durationMs: Long): Double {
        // Tiny scissor box (1x1 pixel) keeps fragment work near zero so
        // the bottleneck is "how fast can we issue glDrawArrays + flush".
        GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, 0, 1, 1)
        val program = buildProgram()
        GLES20.glUseProgram(program)

        val vertexData = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf = ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(vertexData).position(0)

        val posLoc = GLES20.glGetAttribLocation(program, "a_pos")
        val timeLoc = GLES20.glGetUniformLocation(program, "u_time")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, buf)

        val start = System.nanoTime()
        val deadline = start + durationMs * 1_000_000L
        var calls = 0L
        var t = 0f
        while (System.nanoTime() < deadline) {
            // Batch 256 draw calls then flush. Mirrors the way a real game
            // engine bursts submissions per frame, and avoids the per-call
            // glFinish stall that would make this a pure GPU latency test.
            for (i in 0 until 256) {
                GLES20.glUniform1f(timeLoc, t)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
                t += 0.0001f
            }
            GLES20.glFinish()
            calls += 256
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
        return calls / elapsedSec
    }

    private fun renderLoopFrames(durationMs: Long): GpuFrameResult {
        GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        val program = buildProgram()
        GLES20.glUseProgram(program)

        // Full-screen triangle (3 verts cover the whole NDC area without
        // a second triangle — saves a vertex).
        val vertexData = floatArrayOf(
            -1f, -1f,
             3f, -1f,
            -1f,  3f,
        )
        val buf = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(vertexData).position(0)

        val posLoc = GLES20.glGetAttribLocation(program, "a_pos")
        val timeLoc = GLES20.glGetUniformLocation(program, "u_time")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, buf)

        val frameTimes = ArrayList<Float>(4096)
        var prev = System.nanoTime()
        val start = prev
        val deadline = start + durationMs * 1_000_000L
        var frames = 0
        while (System.nanoTime() < deadline) {
            val t = (System.nanoTime() - start) / 1_000_000_000.0f
            GLES20.glUniform1f(timeLoc, t)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            // Force the GPU to actually complete so we don't just queue
            // commands without rendering — vendors batch aggressively.
            // This also makes the per-frame delta an honest wall-clock
            // frame time, so percentiles/1%-low are meaningful.
            GLES20.glFinish()
            val now = System.nanoTime()
            frameTimes.add(((now - prev) / 1_000_000.0).toFloat())  // ms
            prev = now
            frames++
        }
        val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
        // Drop the first frame delta — shader warm-up / first-draw compile
        // spike would skew the slow-tail percentiles.
        val deltas = if (frameTimes.size > 1) frameTimes.drop(1) else frameTimes
        return GpuFrameResult(
            avgFps = if (elapsedSec > 0) frames / elapsedSec else 0.0,
            frameTimesMs = deltas.toFloatArray(),
        )
    }

    private fun renderLoopFramesStress(
        durationMs: Long,
        surfacePx: Int,
        shaderIterations: Int,
    ): GpuFrameResult {
        // Stress variant: EGL pbuffer surface is already created at surfacePx×surfacePx
        // by withEglContext. Set the viewport to match so the fragment shader actually
        // operates on the higher-resolution buffer. Combined with increased shader
        // iterations, this achieves genuine sustained GPU thermal load.
        // Clamp surfacePx the same way as withEglContext does, to handle fallback.
        val surfaceDim = surfacePx.coerceIn(800, 2048)
        GLES20.glViewport(0, 0, surfaceDim, surfaceDim)
        val program = buildProgramStress(shaderIterations)
        GLES20.glUseProgram(program)

        val vertexData = floatArrayOf(
            -1f, -1f,
             3f, -1f,
            -1f,  3f,
        )
        val buf = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(vertexData).position(0)

        val posLoc = GLES20.glGetAttribLocation(program, "a_pos")
        val timeLoc = GLES20.glGetUniformLocation(program, "u_time")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, buf)

        val frameTimes = ArrayList<Float>(4096)
        var prev = System.nanoTime()
        val start = prev
        val deadline = start + durationMs * 1_000_000L
        var frames = 0
        while (System.nanoTime() < deadline) {
            val t = (System.nanoTime() - start) / 1_000_000_000.0f
            GLES20.glUniform1f(timeLoc, t)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            GLES20.glFinish()
            val now = System.nanoTime()
            frameTimes.add(((now - prev) / 1_000_000.0).toFloat())  // ms
            prev = now
            frames++
        }
        val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
        val deltas = if (frameTimes.size > 1) frameTimes.drop(1) else frameTimes
        return GpuFrameResult(
            avgFps = if (elapsedSec > 0) frames / elapsedSec else 0.0,
            frameTimesMs = deltas.toFloatArray(),
        )
    }

    private fun buildProgram(): Int = buildProgramWithIterations(80)

    private fun buildProgramStress(iterations: Int): Int = buildProgramWithIterations(iterations)

    private fun buildProgramWithIterations(iterations: Int): Int {
        val vsh = """
            attribute vec2 a_pos;
            varying vec2 v_uv;
            void main() {
                v_uv = a_pos * 0.5 + 0.5;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """.trimIndent()
        val fsh = """
            precision mediump float;
            varying vec2 v_uv;
            uniform float u_time;
            void main() {
                vec2 p = v_uv * 2.0 - 1.0;
                float a = 0.0;
                // Fragment-heavy: ~$iterations trig+sqrt ops per pixel.
                for (int i = 0; i < $iterations; i++) {
                    float fi = float(i);
                    a += sin(p.x * (fi + 1.0) + u_time) *
                         cos(p.y * (fi + 1.0) - u_time);
                    p = vec2(p.x + sqrt(abs(a) + 0.1), p.y - 0.013 * a);
                }
                gl_FragColor = vec4(0.5 + 0.5 * sin(a), 0.5 + 0.5 * cos(a), 0.5, 1.0);
            }
        """.trimIndent()
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsh)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsh)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        return s
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 800
    }
}
