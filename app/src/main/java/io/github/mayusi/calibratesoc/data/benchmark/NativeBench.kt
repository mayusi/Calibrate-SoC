package io.github.mayusi.calibratesoc.data.benchmark

/**
 * JNI binding to the in-process CPU benchmark in libcalibratesoc_bench.so.
 *
 * Loads the native library on first access. If load fails (corrupt APK,
 * stripped ABI on an unsupported device) callers see an
 * UnsatisfiedLinkError on the next runCpu() call — the benchmark
 * runner catches that and surfaces a "native lib unavailable" UI error.
 *
 * The kernel intentionally runs on the calling thread; pin the caller
 * to a single CPU policy via Linux sched_setaffinity if you want
 * per-cluster numbers. The default is "whatever the scheduler picks",
 * which is what most reference scores assume.
 */
object NativeBench {
    init {
        System.loadLibrary("calibratesoc_bench")
    }

    /**
     * Run the bundled CoreMark-flavored CPU benchmark.
     *
     * @param iterations  Iteration count for the matrix kernel. Caller
     *                    auto-tunes this to target ~10s wall-clock.
     * @return  Iterations-per-second-style score, higher = better.
     *          Two scores from the same device are comparable; cross-
     *          device comparison is meaningless because we don't run
     *          the official CoreMark CRC validation.
     */
    @JvmStatic
    external fun runCpu(iterations: Int): Long

    /**
     * STREAM-style triad memory bandwidth.
     *
     * @param arrayMb  size of each of the three working arrays in MB.
     *                 Pick 64 to spill out of typical mobile L2/L3.
     * @param iters    how many triad passes; 10 is enough for a
     *                 stable measurement at 64 MB.
     * @return  bandwidth in MB/s, scaled by 1000 (so 12 345 = 12.345 MB/s).
     */
    @JvmStatic
    external fun runMemTriad(arrayMb: Int, iters: Int): Long

    /**
     * Single-precision float kernel (Mandelbrot escape). Higher =
     * better float throughput. Sensitive to FPU + branch predictor.
     */
    @JvmStatic
    external fun runFloat(iterations: Int): Long

    /**
     * AES-128 round throughput over a 64KB buffer. Exercises ARMv8
     * FEAT_AES under -O3. Higher = better. Numbers will be far
     * higher on devices with hardware AES vs. those without.
     */
    @JvmStatic
    external fun runAes(iterations: Int): Long
}
