package io.github.mayusi.calibratesoc.data.hardware

import io.github.mayusi.calibratesoc.data.benchmark.NativeBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [NativeBench.runMemTriad] in a coroutine-friendly suspend
 * function so the Hardware screen can `viewModelScope.launch { ... }`
 * without juggling threads.
 *
 * Returns MB/s as a Double. Falls back to 0.0 if the native lib
 * couldn't allocate the triad arrays (low-RAM device).
 */
@Singleton
class MemoryBandwidthTester @Inject constructor() {
    suspend fun run(arrayMb: Int = DEFAULT_ARRAY_MB, iters: Int = DEFAULT_ITERS): Double =
        withContext(Dispatchers.Default) {
            runCatching { NativeBench.runMemTriad(arrayMb, iters) }.getOrDefault(0L) / 1000.0
        }

    private companion object {
        const val DEFAULT_ARRAY_MB = 64
        const val DEFAULT_ITERS = 10
    }
}
