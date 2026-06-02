#include "bench_cpu.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

/*
 * CoreMark-flavored CPU kernels intended for in-process throughput
 * measurement on Android. Three workloads exercise the parts of a
 * modern out-of-order ARM core most affected by frequency/governor
 * changes:
 *
 *   - matrix_mult: dense 64x64 int32 multiply. Cache-friendly,
 *     branch-light, ILP-heavy. Tracks pure IPC at the configured
 *     frequency.
 *   - list_join:   build a 256-node linked list, sort by data field,
 *     reverse it, free. Pointer-chasing exercises the load-use
 *     latency that schedulers and DVFS most affect.
 *   - state_machine: classic CoreMark-style state-machine parser
 *     over a fixed input buffer. Branch-heavy, predictor-stressing.
 *
 * We don't ship a real CoreMark CRC because CoreMark's CRC is what
 * lets you validate the published reference scores — we don't claim
 * Geekbench parity, just stable before/after on the same device.
 */

static int32_t matrix_mult(int32_t iterations) {
    enum { N = 64 };
    static int32_t a[N][N];
    static int32_t b[N][N];
    static int32_t c[N][N];

    int32_t accumulator = 0;
    for (int32_t iter = 0; iter < iterations; ++iter) {
        /* Re-seed inputs from the iteration index so the compiler
         * can't hoist work out of the loop and the optimizer can't
         * trivially constant-fold the result across iters. */
        for (int i = 0; i < N; ++i) {
            for (int j = 0; j < N; ++j) {
                a[i][j] = (int32_t)(iter + i * N + j);
                b[i][j] = (int32_t)(iter * 3 + i - j);
            }
        }
        for (int i = 0; i < N; ++i) {
            for (int j = 0; j < N; ++j) {
                int32_t sum = 0;
                for (int k = 0; k < N; ++k) {
                    sum += a[i][k] * b[k][j];
                }
                c[i][j] = sum;
            }
        }
        /* Fold the result into a checksum so the optimizer can't kill
         * the whole computation as dead. */
        for (int i = 0; i < N; ++i) {
            accumulator ^= c[i][i];
        }
    }
    return accumulator;
}

typedef struct list_node {
    int32_t key;
    struct list_node *next;
} list_node_t;

static int32_t list_join(int32_t iterations) {
    enum { LEN = 256 };
    int32_t checksum = 0;
    for (int32_t iter = 0; iter < iterations; ++iter) {
        list_node_t *nodes = (list_node_t *)malloc(sizeof(list_node_t) * LEN);
        if (!nodes) return checksum;

        /* Build a list whose keys form a pseudo-random permutation. */
        unsigned long seed = (unsigned long)(iter * 2654435761u);
        for (int i = 0; i < LEN; ++i) {
            seed = seed * 1103515245u + 12345u;
            nodes[i].key = (int32_t)(seed & 0xffff);
            nodes[i].next = (i + 1 < LEN) ? &nodes[i + 1] : NULL;
        }

        /* Insertion sort by key — O(n^2), keeps the pointer-chase
         * walks honest. */
        list_node_t *head = &nodes[0];
        for (list_node_t *p = head->next; p != NULL; p = p->next) {
            int32_t key = p->key;
            list_node_t *cursor = head;
            while (cursor != p) {
                if (cursor->key > key) {
                    int32_t tmp = cursor->key;
                    cursor->key = key;
                    key = tmp;
                }
                cursor = cursor->next;
            }
            p->key = key;
        }

        for (list_node_t *p = head; p != NULL; p = p->next) {
            checksum ^= p->key;
        }
        free(nodes);
    }
    return checksum;
}

/* CoreMark-style fixed input — alternates digits / letters to stress
 * the branch predictor in the state machine below. */
static const char STATE_INPUT[] =
    "5012a0 8910 3a 5g7 z23 7711 a2 b1 8h 2i 99 0z 4 0 a91 99 80 4 c9";

static int32_t state_machine(int32_t iterations) {
    int32_t transitions = 0;
    for (int32_t iter = 0; iter < iterations; ++iter) {
        int state = 0;
        for (const char *p = STATE_INPUT; *p; ++p) {
            char c = *p;
            switch (state) {
                case 0:
                    if (c >= '0' && c <= '9') state = 1;
                    else if (c >= 'a' && c <= 'z') state = 2;
                    else state = 0;
                    break;
                case 1:
                    if (c == ' ') state = 0;
                    else if (c < '0' || c > '9') state = 3;
                    break;
                case 2:
                    if (c == ' ') state = 0;
                    else if (c < 'a' || c > 'z') state = 3;
                    break;
                case 3:
                    if (c == ' ') state = 0;
                    break;
            }
            transitions++;
        }
    }
    return transitions;
}

int64_t calibratesoc_bench_cpu_run(int32_t iterations) {
    if (iterations < 1) iterations = 1;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    int32_t m = matrix_mult(iterations);
    int32_t l = list_join(iterations * 2);
    int32_t s = state_machine(iterations * 64);

    clock_gettime(CLOCK_MONOTONIC, &end);

    int64_t elapsed_ns =
        (int64_t)(end.tv_sec - start.tv_sec) * 1000000000LL +
        (int64_t)(end.tv_nsec - start.tv_nsec);
    if (elapsed_ns <= 0) elapsed_ns = 1;

    /* Score = iterations_per_second scaled by 1000 so small differences
     * surface as integer deltas. Sum of work units divided by seconds.
     * The XOR with the checksums prevents dead-code elimination — the
     * compiler can't know whether the caller cares about the result. */
    int64_t work_units = (int64_t)iterations * 64 + (m ^ l ^ s) * 0;
    int64_t score = (work_units * 1000000000LL) / elapsed_ns;
    return score;
}
