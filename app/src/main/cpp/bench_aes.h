#ifndef CALIBRATESOC_BENCH_AES_H
#define CALIBRATESOC_BENCH_AES_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * AES-128 encryption rounds over a fixed buffer. ARMv8-A has
 * dedicated AES instructions (FEAT_AES) — this kernel exercises
 * those on aarch64. Generic implementation; the compiler will
 * vectorise it where possible.
 *
 * @param iters  passes over the buffer
 * @return       iterations-per-second score
 */
int64_t calibratesoc_bench_aes_run(int32_t iters);

#ifdef __cplusplus
}
#endif

#endif
