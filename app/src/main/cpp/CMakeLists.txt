cmake_minimum_required(VERSION 3.22.1)
project(calibratesoc_bench LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)

# Single shared library with the JNI shim + the in-process CPU bench
# kernels. Vendoring full upstream CoreMark/CoreMark-PRO was the
# original plan but those projects expect to be the program entry point
# (own main(), own argv parsing, calls exit() at the end) which makes
# them painful to embed. We ship CoreMark-flavored kernels here —
# matrix mult, linked-list join, state machine — that give a stable
# before/after score on the same device. Not an apples-to-apples
# Geekbench number, but it answers the question the user actually
# cares about: "did my underclock cost me any throughput?"
add_library(calibratesoc_bench SHARED
    NativeBench.cpp
    bench_cpu.c
    bench_mem.c
    bench_float.c
    bench_aes.c
)

target_include_directories(calibratesoc_bench PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
target_link_libraries(calibratesoc_bench
    ${log-lib}
)
