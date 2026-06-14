package io.github.mayusi.calibratesoc.data.benchmarkhub

/**
 * Registry of well-known third-party benchmark apps.
 *
 * LEGAL NOTICE: Calibrate SoC does NOT run, embed, or scrape any of these
 * benchmarks. It only:
 *   1. Detects whether an app is installed via PackageManager.getLaunchIntentForPackage.
 *   2. Launches the app via a standard start-activity intent — a legal, documented
 *      Android interaction.
 *   3. Stores scores that the USER manually types in.
 *
 * It does NOT read result files, use accessibility services to read their UIs,
 * auto-import scores, or make any equivalence claims between their numbers and
 * ours. The HONESTY_NOTE constant is shown prominently in every hub UI surface.
 */
data class KnownBenchmarkApp(
    val displayName: String,
    val packageName: String,
    /** market:// URI for the Google Play Store page. */
    val playStoreUri: String,
    /** Short description of what this benchmark tests. */
    val description: String,
    /** Common score label shown when the user logs a score (e.g. "Overall", "Wild Life"). */
    val defaultScoreLabel: String = "Overall",
)

object BenchmarkAppRegistry {

    const val HONESTY_NOTE =
        "Calibrate SoC doesn't run or verify these benchmarks — it just opens them. " +
            "Any scores shown below are what YOU entered manually."

    /** Canonical list of known benchmark apps. */
    val ALL: List<KnownBenchmarkApp> = listOf(
        KnownBenchmarkApp(
            displayName = "3DMark",
            packageName = "com.futuremark.dmandroid.application",
            playStoreUri = "market://details?id=com.futuremark.dmandroid.application",
            description = "GPU + CPU benchmark suite. Wild Life and Solar Bay are the popular mobile tests.",
            defaultScoreLabel = "Wild Life score",
        ),
        KnownBenchmarkApp(
            displayName = "AnTuTu Benchmark",
            packageName = "com.antutu.ABenchMark",
            playStoreUri = "market://details?id=com.antutu.ABenchMark",
            description = "All-round benchmark: CPU, GPU, memory, UX. Popular for overall SoC comparison.",
            defaultScoreLabel = "Total score",
        ),
        KnownBenchmarkApp(
            displayName = "Geekbench 6",
            packageName = "com.primatelabs.geekbench6",
            playStoreUri = "market://details?id=com.primatelabs.geekbench6",
            description = "CPU single- and multi-core + GPU compute (Metal/OpenCL/Vulkan).",
            defaultScoreLabel = "Single-core score",
        ),
        KnownBenchmarkApp(
            displayName = "GFXBench",
            packageName = "com.glbenchmark.glbenchmark27",
            playStoreUri = "market://details?id=com.glbenchmark.glbenchmark27",
            description = "OpenGL ES / Metal GPU benchmark focused on graphics fidelity.",
            defaultScoreLabel = "Manhattan fps",
        ),
        KnownBenchmarkApp(
            displayName = "CPU Throttling Test",
            packageName = "skynet.cputhrottlingtest",
            playStoreUri = "market://details?id=skynet.cputhrottlingtest",
            description = "Measures CPU sustained performance stability (throttling behaviour).",
            defaultScoreLabel = "Stability %",
        ),
        KnownBenchmarkApp(
            displayName = "PCMark for Android",
            packageName = "com.futuremark.pcmark.android.benchmark",
            playStoreUri = "market://details?id=com.futuremark.pcmark.android.benchmark",
            description = "Productivity workloads: web, video, photo, storage.",
            defaultScoreLabel = "Work score",
        ),
        KnownBenchmarkApp(
            displayName = "Speedometer (browser)",
            packageName = "com.android.chrome",
            playStoreUri = "market://details?id=com.android.chrome",
            description = "Run Speedometer 3 in Chrome at speedometer.ethz.ch.",
            defaultScoreLabel = "Runs/min",
        ),
    )

    /** Quick lookup by package name. */
    val byPackage: Map<String, KnownBenchmarkApp> = ALL.associateBy { it.packageName }

    /** Display names for the score-log picker (includes a free-text option at the end). */
    val displayNames: List<String> = ALL.map { it.displayName }
}
