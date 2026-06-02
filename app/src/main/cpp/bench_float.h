#ifndef CALIBRATESOC_BENCH_FLOAT_H
#define CALIBRATESOC_BENCH_FLOAT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Single-precision float kernel. Compute a Mandelbrot iteration count
 * across a fixed grid. Heavy on FMUL/FADD; sensitive to FPU throughput
 * and FMA presence. Bigger score = better float performance.
 *
 * @param iters  number of full grid passes
 * @return       iterations-per-second style score
 */
int64_t calibratesoc_bench_float_run(int32_t iters);

#ifdef __cplusplus
}
#endif

#endif
