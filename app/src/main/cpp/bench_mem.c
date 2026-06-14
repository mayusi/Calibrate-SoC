#include "bench_mem.h"

#include <stdlib.h>
#include <time.h>

int64_t calibratesoc_bench_mem_triad(int32_t array_mb, int32_t iters) {
    if (array_mb < 1) array_mb = 1;
    /* Clamp to 8 GiB so a corrupt caller cannot trigger a multi-gigabyte
     * malloc that OOM-kills the process. Real benchmark configs use ≤256 MB. */
    if (array_mb > 8192) array_mb = 8192;
    if (iters < 1) iters = 1;

    /* N elements per array. 8 bytes per double.
     * Use (size_t) casts for both multipliers so the entire multiplication
     * is performed in size_t arithmetic — avoids 32-bit overflow on ILP32
     * targets where 1024u * 1024u = 1048576 fits, but the product with a
     * large array_mb could wrap before the (size_t) cast was applied. */
    const size_t bytes = (size_t)array_mb * (size_t)1024 * (size_t)1024;
    const size_t n = bytes / sizeof(double);

    double *a = (double *)malloc(bytes);
    double *b = (double *)malloc(bytes);
    double *c = (double *)malloc(bytes);
    if (!a || !b || !c) {
        free(a); free(b); free(c);
        return 0;
    }

    /* Initialise with non-zero values so the optimizer can't elide. */
    for (size_t i = 0; i < n; ++i) {
        a[i] = 1.0;
        b[i] = 2.0;
        c[i] = 3.0;
    }

    const double scalar = 3.1415926;
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int32_t iter = 0; iter < iters; ++iter) {
        for (size_t i = 0; i < n; ++i) {
            a[i] = b[i] + scalar * c[i];
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &end);

    int64_t elapsed_ns =
        (int64_t)(end.tv_sec - start.tv_sec) * 1000000000LL +
        (int64_t)(end.tv_nsec - start.tv_nsec);
    if (elapsed_ns <= 0) elapsed_ns = 1;

    /* Triad reads b and c, writes a — 3 * bytes per iter. */
    double total_bytes = (double)bytes * 3.0 * (double)iters;
    double seconds = (double)elapsed_ns / 1e9;
    double mbps = (total_bytes / (1024.0 * 1024.0)) / seconds;

    /* Use checksum so the optimizer can't drop the work. */
    double sum = 0.0;
    for (size_t i = 0; i < n; i += 4096) sum += a[i];
    if (sum < 0) mbps = -mbps; /* never true; just defeats DCE */

    free(a); free(b); free(c);

    return (int64_t)(mbps * 1000.0);
}
