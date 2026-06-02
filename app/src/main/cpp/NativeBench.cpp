#include <jni.h>
#include "bench_cpu.h"
#include "bench_mem.h"
#include "bench_float.h"
#include "bench_aes.h"

/*
 * JNI shim. Single static method on the Kotlin side:
 *   NativeBench.runCpu(iterations: Int): Long
 *
 * Function naming follows the canonical JNI mangling
 * Java_<package>_<class>_<method> with the package's dots replaced by
 * underscores. Underscores in identifiers themselves become "_1" but
 * we don't have any.
 */

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mayusi_calibratesoc_data_benchmark_NativeBench_runCpu(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint iterations
) {
    return static_cast<jlong>(calibratesoc_bench_cpu_run(iterations));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mayusi_calibratesoc_data_benchmark_NativeBench_runMemTriad(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint array_mb,
    jint iters
) {
    return static_cast<jlong>(calibratesoc_bench_mem_triad(array_mb, iters));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mayusi_calibratesoc_data_benchmark_NativeBench_runFloat(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint iterations
) {
    return static_cast<jlong>(calibratesoc_bench_float_run(iterations));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mayusi_calibratesoc_data_benchmark_NativeBench_runAes(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint iterations
) {
    return static_cast<jlong>(calibratesoc_bench_aes_run(iterations));
}
