package io.github.mayusi.calibratesoc.data.monitor

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import okio.Path.Companion.toPath
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the CPU load source fallback chain:
 *   - [CpuStatSampler.sampleFromRawText] parses root-sourced /proc/stat correctly.
 *   - [CpuLoadSourceSelector] returns ROOT_PROC_STAT when PServer is live.
 *   - [CpuLoadSourceSelector] returns DIRECT_PROC_STAT when /proc/stat yields nonzero.
 *   - [CpuLoadSourceSelector] detects frozen zeros and falls through to FREQ_PROXY.
 *   - FREQ_PROXY computes cur/max correctly from sysfs.
 *   - Delta state is preserved across samples (first tick = empty, second = real load).
 *
 * All tests are pure JVM — no Android runtime, no Robolectric.
 */
class CpuLoadSourceTest {

    // ── /proc/stat fixtures ────────────────────────────────────────────────────
    //
    // Format per line:
    //   cpuN  user  nice  system  idle  iowait  irq  softirq  steal  guest  guest_nice
    // total = sum of all fields; busy = total - idle - iowait.
    //
    // Snapshot1 (baseline):
    //   cpu0: user=500 idle=400 iowait=100 → total=1000, busy=500
    //   cpu1: user=200 idle=700 iowait=100 → total=1000, busy=200
    //   cpu2: user=900 idle=50  iowait=50  → total=1000, busy=900
    //   cpu3: user=50  idle=900 iowait=50  → total=1000, busy=50
    //
    // Snapshot2 (one tick later) — computed so deltas give the INTENDED loads:
    //   cpu0: delta_total=500,  delta_busy=250 → 50%
    //     snap2: user=750, idle=650, iowait=100 → total=1500, busy=750
    //   cpu1: delta_total=500, delta_busy=100 → 20%
    //     snap2: user=300, idle=1100, iowait=100 → total=1500, busy=300
    //   cpu2: delta_total=500, delta_busy=450 → 90%
    //     snap2: user=1350, idle=100, iowait=50 → total=1500, busy=1350
    //   cpu3: delta_total=1000, delta_busy=50 → 5%
    //     snap2: user=100, idle=1850, iowait=50 → total=2000, busy=100

    private val procStatSnapshot1 = listOf(
        "cpu  4000 0 0 2350 0 0 0 0 0 0",
        "cpu0 500 0 0 400 100 0 0 0 0 0",
        "cpu1 200 0 0 700 100 0 0 0 0 0",
        "cpu2 900 0 0 50 50 0 0 0 0 0",
        "cpu3 50 0 0 900 50 0 0 0 0 0",
    ).joinToString("\n")

    private val procStatSnapshot2 = listOf(
        "cpu  10000 0 0 6300 0 0 0 0 0 0",
        "cpu0 750 0 0 650 100 0 0 0 0 0",
        "cpu1 300 0 0 1100 100 0 0 0 0 0",
        "cpu2 1350 0 0 100 50 0 0 0 0 0",
        "cpu3 100 0 0 1850 50 0 0 0 0 0",
    ).joinToString("\n")

    /** Frozen snapshot — every per-cpu line has identical counters each tick
     *  (Android 12+ hidepid restriction returns zeros from the app UID). */
    private val procStatFrozen = listOf(
        "cpu  99999 0 0 1 0 0 0 0 0 0",
        "cpu0 0 0 0 0 0 0 0 0 0 0",
        "cpu1 0 0 0 0 0 0 0 0 0 0",
        "cpu2 0 0 0 0 0 0 0 0 0 0",
        "cpu3 0 0 0 0 0 0 0 0 0 0",
    ).joinToString("\n")

    // ── Shared helpers ─────────────────────────────────────────────────────────

    private val fakeFs = FakeFileSystem()

    @Before
    fun setup() {
        // Nothing needed at setup — writeProcStat and setupFakeCpuFreqFs create
        // directories lazily. FakeFileSystem starts empty.
    }

    /**
     * Write [content] to /proc/stat, creating parent directories if needed.
     * FakeFileSystem requires every ancestor directory to exist before a write.
     */
    private fun writeProcStat(content: String) {
        fakeFs.createDirectories("/proc".toPath())
        fakeFs.write("/proc/stat".toPath()) { writeUtf8(content) }
    }

    // ── CpuStatSampler: parseProcStat + delta tests ────────────────────────────

    @Test
    fun `parseProcStat skips aggregate cpu line, parses cpuN entries`() {
        val sampler = CpuStatSampler(fakeFs)
        val snapshots = sampler.parseProcStat(procStatSnapshot1)
        // We expect exactly 4 cores (0..3), no "cpu" aggregate entry.
        assertThat(snapshots.keys).containsExactly(0, 1, 2, 3)
        // cpu0: user=500 idle=400 iowait=100 → total=1000 busy=500
        val cpu0 = snapshots[0]!!
        assertThat(cpu0.total).isEqualTo(1000L)
        assertThat(cpu0.busy).isEqualTo(500L)
        // cpu2: user=900 idle=50 iowait=50 → total=1000 busy=900
        val cpu2 = snapshots[2]!!
        assertThat(cpu2.total).isEqualTo(1000L)
        assertThat(cpu2.busy).isEqualTo(900L)
    }

    @Test
    fun `sampleFromRawText first tick returns empty list — no baseline yet`() {
        val sampler = CpuStatSampler(fakeFs)
        val result = sampler.sampleFromRawText(procStatSnapshot1)
        assertThat(result).isEmpty()
    }

    @Test
    fun `sampleFromRawText second tick returns correct per-core load percentages`() {
        val sampler = CpuStatSampler(fakeFs)
        // Prime the baseline.
        sampler.sampleFromRawText(procStatSnapshot1)
        // Second tick — should compute deltas.
        val loads = sampler.sampleFromRawText(procStatSnapshot2)

        assertThat(loads).hasSize(4)
        // cpu0: delta_total=500, delta_busy=250 → 50%
        assertThat(loads[0]).isEqualTo(50)
        // cpu1: delta_total=500, delta_busy=100 → 20%
        assertThat(loads[1]).isEqualTo(20)
        // cpu2: delta_total=500, delta_busy=450 → 90%
        assertThat(loads[2]).isEqualTo(90)
        // cpu3: delta_total=1000, delta_busy=50 → 5%
        assertThat(loads[3]).isEqualTo(5)
    }

    @Test
    fun `sampleFromRawText uses independent delta state from direct sample()`() {
        val sampler = CpuStatSampler(fakeFs)
        // Write snapshot1 so the direct path reads it.
        writeProcStat(procStatSnapshot1)
        // Prime the DIRECT baseline.
        sampler.sample()
        // Prime the ROOT baseline with the same snapshot.
        sampler.sampleFromRawText(procStatSnapshot1)

        // Advance ONLY the root source.
        val rootLoads = sampler.sampleFromRawText(procStatSnapshot2)
        // Root should see real deltas (cpu2 = 90%).
        assertThat(rootLoads[2]).isEqualTo(90)

        // Direct path baseline was primed with snapshot1 above.
        // Overwrite /proc/stat to snapshot2 so the direct path delta computes.
        writeProcStat(procStatSnapshot2)
        val directLoads = sampler.sample()
        // Direct should independently compute cpu2 = 90% from its own baseline.
        assertThat(directLoads[2]).isEqualTo(90)
    }

    @Test
    fun `reset clears both direct and root delta state maps`() {
        val sampler = CpuStatSampler(fakeFs)
        // Prime both baselines.
        sampler.sampleFromRawText(procStatSnapshot1)
        writeProcStat(procStatSnapshot1)
        sampler.sample()

        sampler.reset()

        // After reset, both paths return empty (no baseline).
        val rootAfterReset = sampler.sampleFromRawText(procStatSnapshot2)
        assertThat(rootAfterReset).isEmpty()

        writeProcStat(procStatSnapshot2)
        val directAfterReset = sampler.sample()
        assertThat(directAfterReset).isEmpty()
    }

    @Test
    fun `sampleFromRawText clamps load to 0-100 range`() {
        val sampler = CpuStatSampler(fakeFs)
        // First tick primes baseline at 0.
        sampler.sampleFromRawText("cpu0 0 0 0 0 0 0 0 0 0 0\n")
        // Second tick: all jiffy time in user → 100%.
        val loads = sampler.sampleFromRawText("cpu0 200 0 0 0 0 0 0 0 0 0\n")
        assertThat(loads).hasSize(1)
        assertThat(loads[0]).isIn(0..100)
        assertThat(loads[0]).isEqualTo(100)
    }

    // ── Frequency proxy tests ──────────────────────────────────────────────────

    @Test
    fun `freq proxy computes cur-over-max correctly for each core`() {
        // Set up 3 cores with different cur/max ratios.
        setupFakeCpuFreqFs(
            cpuCount = 3,
            curFreqs  = listOf(1_000_000L, 2_000_000L, 4_320_000L),
            maxFreqs  = listOf(2_000_000L, 2_000_000L, 4_320_000L),
        )

        val pServerWriter = mockk<PServerWriter>(relaxed = true)
        every { pServerWriter.transactableNow() } returns false

        // Make the direct /proc/stat path return frozen zeros so the selector
        // falls through to FREQ_PROXY on the second tick.
        writeProcStat(procStatFrozen)

        val sampler = CpuStatSampler(fakeFs)
        val selector = CpuLoadSourceSelector(
            cpuStatSampler = sampler,
            pServerWriter  = pServerWriter,
            fs             = fakeFs,
        )

        runBlocking {
            // First tick: the direct /proc/stat path primes its baseline and yields
            // an empty list. The selector NEVER dead-ends on a higher rung — it falls
            // through to the FREQ_PROXY floor (scaling_cur/max_freq are always readable),
            // so even the first tick returns a usable proxy reading rather than
            // UNAVAILABLE. This is the corrected fall-through contract.
            val firstReading = selector.sample()
            assertThat(firstReading.source).isEqualTo(CpuLoadReading.Source.FREQ_PROXY)
            assertThat(firstReading.loads).hasSize(3)
            // cpu0: 1_000_000 / 2_000_000 = 50%
            assertThat(firstReading.loads[0]).isEqualTo(50)
            // cpu1: 2_000_000 / 2_000_000 = 100%
            assertThat(firstReading.loads[1]).isEqualTo(100)
            // cpu2: 4_320_000 / 4_320_000 = 100%
            assertThat(firstReading.loads[2]).isEqualTo(100)

            // Second tick: direct returns all-zero delta (frozen) while freqs are busy
            // (cpu1 and cpu2 at 100% of max → freqsLookBusy() = true) → still FREQ_PROXY.
            val secondReading = selector.sample()
            assertThat(secondReading.source).isEqualTo(CpuLoadReading.Source.FREQ_PROXY)
            assertThat(secondReading.loads).hasSize(3)
            assertThat(secondReading.loads[0]).isEqualTo(50)
            assertThat(secondReading.loads[1]).isEqualTo(100)
            assertThat(secondReading.loads[2]).isEqualTo(100)
        }
    }

    @Test
    fun `root proc stat path returns correct loads when PServer is transactable`() {
        val samplerWithBaseline = CpuStatSampler(fakeFs)
        // Prime the root-path baseline with snapshot1.
        samplerWithBaseline.sampleFromRawText(procStatSnapshot1)

        val pServerWriter = mockk<PServerWriter>(relaxed = true)
        every { pServerWriter.transactableNow() } returns true
        // PServer returns snapshot2 on every call to executeShell.
        coEvery { pServerWriter.executeShell("cat /proc/stat") } returns (0 to procStatSnapshot2)

        val selector = CpuLoadSourceSelector(
            cpuStatSampler = samplerWithBaseline,
            pServerWriter  = pServerWriter,
            fs             = fakeFs,
        )

        runBlocking {
            val reading = selector.sample()
            assertThat(reading.source).isEqualTo(CpuLoadReading.Source.ROOT_PROC_STAT)
            assertThat(reading.loads).hasSize(4)
            // cpu0 = 50%, cpu2 = 90% — verifies real delta computation via root path.
            assertThat(reading.loads[0]).isEqualTo(50)
            assertThat(reading.loads[2]).isEqualTo(90)
        }
    }

    @Test
    fun `root path returns UNAVAILABLE on first tick when no baseline exists`() {
        val pServerWriter = mockk<PServerWriter>(relaxed = true)
        every { pServerWriter.transactableNow() } returns true
        // First tick — PServer returns snapshot1 (no prior root baseline).
        coEvery { pServerWriter.executeShell("cat /proc/stat") } returns (0 to procStatSnapshot1)

        val selector = CpuLoadSourceSelector(
            cpuStatSampler = CpuStatSampler(fakeFs),
            pServerWriter  = pServerWriter,
            fs             = fakeFs,
        )

        runBlocking {
            val reading = selector.sample()
            // sampleFromRawText returns empty on first call (no baseline) → UNAVAILABLE.
            assertThat(reading.source).isEqualTo(CpuLoadReading.Source.UNAVAILABLE)
            assertThat(reading.loads).isEmpty()
        }
    }

    @Test
    fun `direct proc stat returns real loads when hidepid is not active`() {
        writeProcStat(procStatSnapshot1)

        val pServerWriter = mockk<PServerWriter>(relaxed = true)
        every { pServerWriter.transactableNow() } returns false

        val selector = CpuLoadSourceSelector(
            cpuStatSampler = CpuStatSampler(fakeFs),
            pServerWriter  = pServerWriter,
            fs             = fakeFs,
        )

        runBlocking {
            // First tick: primes baseline → UNAVAILABLE (no delta yet).
            selector.sample()

            // Overwrite with snapshot2 so the next tick yields real deltas.
            writeProcStat(procStatSnapshot2)
            val reading = selector.sample()

            assertThat(reading.source).isEqualTo(CpuLoadReading.Source.DIRECT_PROC_STAT)
            assertThat(reading.loads[0]).isEqualTo(50)
            assertThat(reading.loads[2]).isEqualTo(90)
        }
    }

    @Test
    fun `reset clears frozen detection flag — direct path re-evaluated after reset`() {
        val pServerWriter = mockk<PServerWriter>(relaxed = true)
        every { pServerWriter.transactableNow() } returns false

        // Set up 2 cores at 100% frequency so freqsLookBusy() = true.
        setupFakeCpuFreqFs(
            cpuCount = 2,
            curFreqs  = listOf(2_000_000L, 2_000_000L),
            maxFreqs  = listOf(2_000_000L, 2_000_000L),
        )
        writeProcStat(procStatFrozen)

        val selector = CpuLoadSourceSelector(
            cpuStatSampler = CpuStatSampler(fakeFs),
            pServerWriter  = pServerWriter,
            fs             = fakeFs,
        )

        runBlocking {
            selector.sample() // prime baseline
            val frozenReading = selector.sample() // all-zero delta + busy freqs → FREQ_PROXY
            assertThat(frozenReading.source).isEqualTo(CpuLoadReading.Source.FREQ_PROXY)

            // After reset, the frozen flag is cleared so the direct path is
            // re-evaluated. We prove that by feeding the direct path real (non-frozen)
            // /proc/stat data and confirming it is used again on the SECOND post-reset
            // tick. On the FIRST post-reset tick the direct baseline is still priming
            // (empty), so the selector falls through to the FREQ_PROXY floor rather
            // than dead-ending on UNAVAILABLE — the corrected fall-through contract.
            selector.reset()
            writeProcStat(procStatSnapshot1)
            val afterReset = selector.sample()
            assertThat(afterReset.source).isEqualTo(CpuLoadReading.Source.FREQ_PROXY)

            // Second tick post-reset: the direct path now has a real delta and is
            // re-evaluated (proving reset cleared the frozen latch). snapshot1→snapshot2
            // yields nonzero loads, so the direct read is NOT considered frozen here.
            writeProcStat(procStatSnapshot2)
            val secondAfterReset = selector.sample()
            assertThat(secondAfterReset.source).isEqualTo(CpuLoadReading.Source.DIRECT_PROC_STAT)
            assertThat(secondAfterReset.loads[2]).isEqualTo(90)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Populate the fake filesystem with /sys/devices/system/cpu/cpuN/cpufreq/
     * scaling_cur_freq and scaling_max_freq for [cpuCount] cores.
     *
     * FakeFileSystem requires every parent directory to be created first.
     */
    private fun setupFakeCpuFreqFs(
        cpuCount: Int,
        curFreqs: List<Long>,
        maxFreqs: List<Long>,
    ) {
        fakeFs.createDirectories("/sys/devices/system/cpu".toPath())
        for (i in 0 until cpuCount) {
            val cpuDir = "/sys/devices/system/cpu/cpu$i/cpufreq".toPath()
            fakeFs.createDirectories(cpuDir)
            fakeFs.write(cpuDir / "scaling_cur_freq") {
                writeUtf8("${curFreqs[i]}\n")
            }
            fakeFs.write(cpuDir / "scaling_max_freq") {
                writeUtf8("${maxFreqs[i]}\n")
            }
        }
    }
}
