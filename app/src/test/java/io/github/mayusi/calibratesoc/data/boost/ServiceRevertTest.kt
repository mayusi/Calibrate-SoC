package io.github.mayusi.calibratesoc.data.boost

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * HIGH-1 / CRITICAL-1 — the SHARED [ServiceRevert] (used by GameBoostService and
 * ThrottleGuardService) must SURVIVE coroutine cancellation, exactly like AutoTDP's
 * AutoTdpRevert.
 *
 * DEFECT B's siblings: both services wired revertAll into the daemon `finally`, which every
 * stop path reaches via `loopJob.cancel()`; the inner PServer `withContext(Dispatchers.IO)`
 * threw CancellationException before the writes ran → the cap / ceiling pin stayed pinned
 * until reboot. [ServiceRevert.revertNow] wraps the revert in `NonCancellable + Dispatchers.IO`
 * so it lands even from an ALREADY-cancelled job, and latches so the four exit paths revert
 * at most once.
 */
class ServiceRevertTest {

    private val report: CapabilityReport = CapabilityReport(
        device = DeviceIdentity(
            manufacturer = "AYN", brand = "AYN", model = "Odin3",
            device = "odin3", hardware = "pineapple",
            androidVersion = "14", sdkInt = 34, knownHandheldKey = "ayn_odin3",
        ),
        soc = SoCIdentity(
            socManufacturer = "qualcomm", socModel = "sm8650", gpuFamily = GpuFamily.ADRENO,
        ),
        privilege = PrivilegeTier.ROOT,
        rootKind = RootKind.MAGISK,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = emptyList(),
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, false),
    )

    private fun revertOver(calls: AtomicInteger, started: CompletableDeferred<Unit>? = null): ServiceRevert {
        val writer = mockk<TunableWriter>()
        coEvery { writer.revertAll(any()) } coAnswers {
            started?.complete(Unit)
            delay(1) // cancellation tripwire: a cancelled job throws here without NonCancellable
            calls.incrementAndGet()
            TunableWriter.RevertSummary(ok = 1, failed = 0, totalEntries = 1)
        }
        return ServiceRevert(writer)
    }

    @Test
    fun `revertNow invokes revertAll even when the calling job is cancelled`() = runBlocking {
        val calls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val revert = revertOver(calls, started)

        val job: Job = launch(Dispatchers.Default) {
            revert.revertNow(report)
        }
        withTimeout(2_000) { started.await() }
        job.cancel()
        job.join()

        // Without NonCancellable, the in-flight delay() throws and calls stays 0.
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `revertNow is idempotent — a second call does not revert again`() = runBlocking {
        val calls = AtomicInteger(0)
        val revert = revertOver(calls)

        val first = revert.revertNow(report)
        val second = revert.revertNow(report)

        assertThat(first).isNotNull()
        assertThat(second).isNull()
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent exit paths revert at most once`() = runBlocking {
        val calls = AtomicInteger(0)
        val revert = revertOver(calls)

        // stopDaemon + onDestroy + onTaskRemoved + the finally all racing to revert.
        val jobs = (1..5).map {
            launch(Dispatchers.Default) { revert.revertNow(report) }
        }
        jobs.forEach { it.join() }

        assertThat(calls.get()).isEqualTo(1)
    }
}
