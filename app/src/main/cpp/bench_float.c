#include "bench_float.h"

#include <time.h>

/*
 * Mandelbrot escape-count kernel. Fixed 256x256 grid, 1000 max iters
 * per pixel. Total work per pass ~16M FMA-equivalent ops. Picked over
 * SGEMM because Mandelbrot has natural branch divergence (some pixels
 * exit early) which exercises the predictor + FPU together; SGEMM
 * would just measure peak FLOPS, which is less representative of
 * real game workloads.
 */
int64_t calibratesoc_bench_float_run(int32_t iters) {
    if (iters < 1) iters = 1;

    static const int W = 256;
    static const int H = 256;
    static const int MAX_ITER = 1000;
    static const float X_MIN = -2.0f;
    static const float X_MAX = 1.0f;
    static const float Y_MIN = -1.5f;
    static const float Y_MAX = 1.5f;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    long long checksum = 0;
    for (int iter = 0; iter < iters; ++iter) {
        for (int py = 0; py < H; ++py) {
            float y0 = Y_MIN + ((float)py / H) * (Y_MAX - Y_MIN);
            for (int px = 0; px < W; ++px) {
                float x0 = X_MIN + ((float)px / W) * (X_MAX - X_MIN);
                float x = 0.0f, y = 0.0f;
                int it = 0;
                while (x * x + y * y <= 4.0f && it < MAX_ITER) {
                    float xt = x * x - y * y + x0;
                    y = 2.0f * x * y + y0;
                    x = xt;
                    ++it;
                }
                checksum += it;
            }
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    int64_t elapsed_ns =
        (int64_t)(end.tv_sec - start.tv_sec) * 1000000000LL +
        (int64_t)(end.tv_nsec - start.tv_nsec);
    if (elapsed_ns <= 0) elapsed_ns = 1;

    /* Score is total work units per second; baseline scaling makes
     * small deltas readable as integers. Tiny dependency on checksum
     * so DCE can't kill the loop. */
    int64_t work = (int64_t)iters * W * H;
    int64_t score = (work * 1000000000LL) / elapsed_ns;
    if (checksum == 0) score = -1; /* never hits but defeats DCE */
    return score;
}
