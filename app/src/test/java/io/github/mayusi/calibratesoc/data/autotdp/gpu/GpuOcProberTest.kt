package io.github.mayusi.calibratesoc.data.autotdp.gpu

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.autotdp.TdpCaps
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UNIT 3 (ADAPTIVE MODE) — proof tests for the beyond-stock GPU-OC accept-probe.
 *
 * Two layers:
 *   1. [GpuOcProber.classifyProbe] — the PURE decision function. No I/O, exhaustively
 *      pinned: no-headroom → Unsupported, clamp → Rejected, accepted-but-clock-never-rises
 *      → Ineffective, clock-exceeds-stock → Accepted.
 *   2. [GpuOcProber.probe] — the I/O orchestration, driven by fake reader/writer/loadGen.
 *      The SAFETY invariants are asserted on EVERY path: stock max_freq is always restored
 *      (the last write to max_freq is the stock ceiling) and the load gen is ALWAYS stopped
 *      — including when a child throws.
 *
 * Pure JVM — no Robolectric, no Android (the prober's I/O is fully behind injected fakes).
 */
class GpuOcProberTest {

    private val ceilHz = 1_100_000_000L           // stock ceiling
    private val ocHz = 1_300_000_000L             // a real beyond-stock OPP
    private val gpuRoot = "/sys/class/kgsl/kgsl-3d0"
    private val maxFreqPath = "$gpuRoot/devfreq/max_freq"
    private val curFreqPath = "$gpuRoot/devfreq/cur_freq"
    private val availPath = "$gpuRoot/devfreq/available_frequencies"

    private fun caps(
        ceil: Long? = ceilHz,
        steps: List<Long> = listOf(160_000_000L, 550_000_000L, ceilHz, ocHz),
        root: String? = gpuRoot,
    ) = TdpCaps(
        primeCoreIndices = listOf(7),
        bigPolicyId = 4,
        bigClusterOppStepsKhz = listOf(1_000_000, 2_000_000),
        gpuMinLevel = 0,
        gpuMaxLevel = 6,
        minOnlineCores = 4,
        totalOnlineCores = 8,
        gpuDevfreqFloorHz = 160_000_000L,
        gpuDevfreqCeilHz = ceil,
        gpuDevfreqStepsHz = steps,
        gpuRootPath = root,
        uclampAvailable = true,
        fanModeAvailable = true,
    )

    // ════════════════════════════════════════════════════════════════════════════
    //  classifyProbe — the pure decision logic
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    fun classify_noHeadroom_isUnsupported() {
        // written == ceil → no real beyond-stock target.
        val v = GpuOcProber.classifyProbe(
            written = ceilHz, readback = ceilHz, ceilHz = ceilHz, maxObservedHz = ceilHz,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Unsupported)
    }

    @Test
    fun classify_writtenBelowCeil_isUnsupported() {
        val v = GpuOcProber.classifyProbe(
            written = ceilHz - 1, readback = ceilHz - 1, ceilHz = ceilHz, maxObservedHz = 0L,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Unsupported)
    }

    @Test
    fun classify_readbackLessThanWritten_isRejectedWithClampValue() {
        // Kernel clamped the cap back to stock.
        val v = GpuOcProber.classifyProbe(
            written = ocHz, readback = ceilHz, ceilHz = ceilHz, maxObservedHz = 0L,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Rejected(clampedTo = ceilHz))
    }

    @Test
    fun classify_acceptedCapButClockNeverExceedsStock_isIneffective() {
        // Readback matched the write, but under load the clock never rose above stock.
        val v = GpuOcProber.classifyProbe(
            written = ocHz, readback = ocHz, ceilHz = ceilHz, maxObservedHz = ceilHz,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Ineffective)
    }

    @Test
    fun classify_clockExactlyAtCeil_isIneffective_notAccepted() {
        // Boundary: "exceeds" is STRICT. Clock == ceil is NOT an overclock.
        val v = GpuOcProber.classifyProbe(
            written = ocHz, readback = ocHz, ceilHz = ceilHz, maxObservedHz = ceilHz,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Ineffective)
    }

    @Test
    fun classify_clockExceedsStock_isAcceptedWithReachedHz() {
        val v = GpuOcProber.classifyProbe(
            written = ocHz, readback = ocHz, ceilHz = ceilHz, maxObservedHz = ocHz,
        )
        assertThat(v).isEqualTo(GpuOcVerdict.Accepted(reachedHz = ocHz))
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  probe — I/O orchestration with fakes; SAFETY invariants on every path
    // ════════════════════════════════════════════════════════════════════════════

    /** Records every (path,value) written, in order, so we can assert the LAST max_freq write. */
    private class FakeWriter(val accept: Boolean = true) : GpuOcProber.SysfsWriter {
        val writes = mutableListOf<Pair<String, Long>>()
        override suspend fun write(path: String, valueHz: Long): Boolean {
            writes.add(path to valueHz)
            return accept
        }
        fun lastWriteTo(path: String): Long? = writes.lastOrNull { it.first == path }?.second
    }

    /** Reader whose max_freq readback reflects what FakeWriter last wrote (honest mirror),
     *  with a configurable clamp. cur_freq models a REAL live node: it returns the steady
     *  clock-under-load [loadClockHz], so whichever coroutine samples it (the concurrent
     *  poller or the authoritative post-load read) sees the same true value — no consume-once
     *  index race. This mirrors a real device where cur_freq is the current clock, not a queue. */
    private inner class FakeReader(
        val writer: FakeWriter,
        val available: String? = "160000000 550000000 $ceilHz $ocHz",
        val loadClockHz: Long = ocHz,                  // clock observed under load (live value)
        val clampMaxFreqTo: Long? = null,              // non-null = kernel clamps readback
    ) : GpuOcProber.SysfsReader {
        val curFreqReads = AtomicInteger(0)            // how many times cur_freq was sampled
        override suspend fun read(path: String): String? = when (path) {
            availPath -> available
            maxFreqPath -> {
                val written = writer.lastWriteTo(maxFreqPath)
                when {
                    written == null -> null
                    clampMaxFreqTo != null && written > clampMaxFreqTo -> clampMaxFreqTo.toString()
                    else -> written.toString()
                }
            }
            curFreqPath -> {
                curFreqReads.incrementAndGet()
                loadClockHz.toString()
            }
            else -> null
        }
    }

    private class FakeLoadGen(val throwInRun: Boolean = false) : GpuOcProber.LoadGen {
        val stopped = AtomicBoolean(false)
        var runs = 0
        override suspend fun run(durationMs: Long) {
            runs++
            if (throwInRun) throw IllegalStateException("EGL exploded mid-load")
        }
        override fun stop() { stopped.set(true) }
    }

    @Test
    fun probe_noHeadroom_returnsUnsupported_andNeverWrites() = runTest {
        // OPP table tops out AT the stock ceiling → no beyond-stock target.
        val writer = FakeWriter()
        val reader = FakeReader(writer, available = "160000000 550000000 $ceilHz")
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(steps = listOf(160_000_000L, ceilHz)), reader, writer, load)

        assertThat(v).isEqualTo(GpuOcVerdict.Unsupported)
        // No headroom ⇒ no write attempted ⇒ nothing to restore, no load run.
        assertThat(writer.writes).isEmpty()
        assertThat(load.runs).isEqualTo(0)
    }

    @Test
    fun probe_missingCeilOrRoot_returnsUnsupported() = runTest {
        val writer = FakeWriter()
        val reader = FakeReader(writer)
        assertThat(GpuOcProber().probe(caps(ceil = null), reader, writer, FakeLoadGen()))
            .isEqualTo(GpuOcVerdict.Unsupported)
        assertThat(GpuOcProber().probe(caps(root = null), reader, writer, FakeLoadGen()))
            .isEqualTo(GpuOcVerdict.Unsupported)
    }

    @Test
    fun probe_kernelClamps_returnsRejected_andRestoresStock_andStopsLoad() = runTest {
        val writer = FakeWriter()
        // Kernel clamps any beyond-stock write back to the stock ceiling.
        val reader = FakeReader(writer, clampMaxFreqTo = ceilHz)
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(), reader, writer, load)

        assertThat(v).isEqualTo(GpuOcVerdict.Rejected(clampedTo = ceilHz))
        // SAFETY: last max_freq write is the stock ceiling (no beyond-stock left stuck).
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz)
        // SAFETY: load always stopped.
        assertThat(load.stopped.get()).isTrue()
    }

    @Test
    fun probe_acceptedCapButClockFlat_returnsIneffective_andRestoresStock() = runTest {
        val writer = FakeWriter()
        // Cap accepted (no clamp) but the clock never rises above stock under load.
        val reader = FakeReader(writer, loadClockHz = ceilHz)
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(), reader, writer, load)

        assertThat(v).isEqualTo(GpuOcVerdict.Ineffective)
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz)
        assertThat(load.stopped.get()).isTrue()
        assertThat(load.runs).isEqualTo(1)
    }

    @Test
    fun probe_clockExceedsStock_returnsAccepted_butStillRestoresStock() = runTest {
        val writer = FakeWriter()
        // Clock reaches into the new headroom under load (1.2 GHz > stock) → genuine OC.
        val reader = FakeReader(writer, loadClockHz = 1_200_000_000L)
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(), reader, writer, load)

        assertThat(v).isInstanceOf(GpuOcVerdict.Accepted::class.java)
        assertThat((v as GpuOcVerdict.Accepted).reachedHz).isAtLeast(1_200_000_000L)
        // SAFETY (LAW): even on Accepted the probe restores the stock ceiling — the
        // coordinator re-applies the beyond-stock cap deliberately, never the probe.
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz)
        assertThat(load.stopped.get()).isTrue()
    }

    @Test
    fun probe_loadGenThrows_stillRestoresStock_andStopsLoad() = runTest {
        val writer = FakeWriter()
        val reader = FakeReader(writer)
        val load = FakeLoadGen(throwInRun = true)

        // The exception from the load propagates, but the finally block MUST still run.
        var threw = false
        try {
            GpuOcProber().probe(caps(), reader, writer, load)
        } catch (_: Throwable) {
            threw = true
        }

        assertThat(threw).isTrue()
        // SAFETY on the exception path: stock restored + load stopped, no orphan.
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz)
        assertThat(load.stopped.get()).isTrue()
    }

    @Test
    fun probe_writerRejectsRaise_returnsRejected_andRestoresStock() = runTest {
        // The privilege layer refuses the raise outright (write returns false).
        val writer = FakeWriter(accept = false)
        val reader = FakeReader(writer)
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(), reader, writer, load)

        assertThat(v).isInstanceOf(GpuOcVerdict.Rejected::class.java)
        // Still restores stock + stops load (no beyond-stock stuck, no orphan).
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz)
        assertThat(load.stopped.get()).isTrue()
    }

    @Test
    fun probe_usesCapsStepsWhenAvailableFreqsNodeUnreadable() = runTest {
        val writer = FakeWriter()
        // available_frequencies node returns null → falls back to caps.gpuDevfreqStepsHz.
        val reader = FakeReader(writer, available = null, loadClockHz = ocHz)
        val load = FakeLoadGen()

        val v = GpuOcProber().probe(caps(), reader, writer, load)

        // ocTarget resolved from caps steps (max = ocHz) → write happened → Accepted.
        assertThat(v).isInstanceOf(GpuOcVerdict.Accepted::class.java)
        assertThat(writer.lastWriteTo(maxFreqPath)).isEqualTo(ceilHz) // restored
    }
}
