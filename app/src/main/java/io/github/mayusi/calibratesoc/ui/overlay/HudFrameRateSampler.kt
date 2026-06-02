package io.github.mayusi.calibratesoc.ui.overlay

import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rolling frame-rate sampler. Subscribes to [Choreographer] and reports
 * the average frame rate over the last [WINDOW] callbacks.
 *
 * This is the HUD's own draw cadence — NOT the game's FPS. Android has
 * no public API for hooking another app's swap chain without root. We
 * label this honestly as "HUD Hz" in the UI so users don't take it for
 * something it isn't. In practice when the device is GPU-bound the HUD
 * Hz also dips because the GPU pipeline is shared, so it's a useful
 * (if noisy) signal of "the system is currently under load".
 */
class HudFrameRateSampler {
    private val _hz = MutableStateFlow(0)
    val hz: StateFlow<Int> = _hz

    private val timestamps = LongArray(WINDOW)
    private var idx = 0
    private var filled = 0
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            timestamps[idx] = frameTimeNanos
            idx = (idx + 1) % WINDOW
            if (filled < WINDOW) filled++

            if (filled >= 2) {
                val oldest = timestamps[(idx - filled + WINDOW) % WINDOW]
                val newest = timestamps[(idx - 1 + WINDOW) % WINDOW]
                val spanSec = (newest - oldest) / 1_000_000_000.0
                if (spanSec > 0) {
                    _hz.value = ((filled - 1) / spanSec).toInt()
                }
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        idx = 0
        filled = 0
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    private companion object {
        const val WINDOW = 60
    }
}
