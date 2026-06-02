#ifndef CALIBRATESOC_BENCH_CPU_H
#define CALIBRATESOC_BENCH_CPU_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Run the in-process CPU benchmark and return a score. Score scales
 * roughly with achievable throughput on the calling thread — pinned
 * single-thread CoreMark-style work. Higher = better.
 *
 * @param iterations  How many iterations of each kernel to run. Use
 *                    higher counts for more stable scores at the cost
 *                    of longer wall-clock. The runner targets ~10s
 *                    per call by auto-tuning this number.
 * @return  A unit-free score representing iterations-per-second of the
 *          combined kernels. The interesting comparison is two scores
 *          from the same device under different tune settings.
 */
int64_t calibratesoc_bench_cpu_run(int32_t iterations);

#ifdef __cplusplus
}
#endif

#endif // CALIBRATESOC_BENCH_CPU_H
