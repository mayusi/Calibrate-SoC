#ifndef CALIBRATESOC_BENCH_MEM_H
#define CALIBRATESOC_BENCH_MEM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * STREAM-style triad memory bandwidth benchmark.
 *
 * Performs `iters` passes of `a[i] = b[i] + scalar * c[i]` over
 * three double arrays of `array_mb` megabytes each. Returns the
 * measured bandwidth in MB/s scaled by 1000 (so 12.345 GB/s →
 * 12_345_000), zero on allocation failure.
 *
 * Sizing matters: pick array_mb large enough to spill out of the
 * L2/L3 cache so we measure DRAM rather than cache, but small
 * enough that we don't trip the OOM killer on low-RAM phones.
 * 64 MB total (3 × 64 MB = 192 MB allocated) works on every
 * device with >= 3 GB of RAM.
 */
int64_t calibratesoc_bench_mem_triad(int32_t array_mb, int32_t iters);

#ifdef __cplusplus
}
#endif

#endif /* CALIBRATESOC_BENCH_MEM_H */
