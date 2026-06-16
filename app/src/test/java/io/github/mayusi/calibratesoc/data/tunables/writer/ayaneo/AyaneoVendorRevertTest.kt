package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityProbe
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.capability.CpuPolicyProbe
import io.github.mayusi.calibratesoc.data.capability.DeviceIdentity
import io.github.mayusi.calibratesoc.data.capability.FreqRange
import io.github.mayusi.calibratesoc.data.capability.GpuFamily
import io.github.mayusi.calibratesoc.data.capability.PrivilegeTier
import io.github.mayusi.calibratesoc.data.capability.RootKind
import io.github.mayusi.calibratesoc.data.capability.ShizukuStatus
import io.github.mayusi.calibratesoc.data.capability.SoCIdentity
import io.github.mayusi.calibratesoc.data.capability.VendorAppPresence
import io.github.mayusi.calibratesoc.data.tunables.Tunables
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Test

/**
 * REVERT correctness for the AYANEO vendor writer.
 *
 * AutoTDP's revert ([io.github.mayusi.calibratesoc.data.tunables.TunableWriter.revertAll])
 * re-sends each journaled `previousValue` (the STOCK value captured before the first write)
 * through the SAME writer. For AYANEO that means re-sending the stock cpufreq/gpu values as
 * AIDL commands, then reading the node back to confirm the restore landed. This test proves
 * the writer issues the stock-restoring command and verifies it.
 */
class AyaneoVendorRevertTest {

    private val policies = listOf(
        CpuPolicyProbe(
            policyId = 7,
            onlineCores = listOf(7),
            availableFreqsKhz = listOf(844_800, 3_187_200),
            availableGovernors = listOf("schedutil", "performance", "powersave"),
            currentMinKhz = 844_800,
            currentMaxKhz = 3_187_200,
            currentGovernor = "schedutil",
            hardwareLimitsKhz = FreqRange(844_800, 3_187_200),
        ),
    )

    private fun report() = CapabilityReport(
        device = DeviceIdentity(
            "AYANEO", "AYANEO", "Pocket DS", "PocketDS", "kalama", "13", 33, "ayaneo_pocket_ds",
        ),
        soc = SoCIdentity("QTI", "SG8275", GpuFamily.ADRENO),
        privilege = PrivilegeTier.NONE,
        rootKind = RootKind.NONE,
        shizuku = ShizukuStatus(false, false, false, null),
        cpuPolicies = policies,
        gpu = null,
        thermalZones = emptyList(),
        fan = null,
        vendorApps = VendorAppPresence(false, false, ayaSpace = true),
        ayaneoBinderLive = true,
    )

    private fun writer(binder: AyaneoBinderClient, fs: FakeFileSystem): AyaneoVendorWriter {
        val probe = mockk<CapabilityProbe>()
        every { probe.report } returns MutableStateFlow(report())
        return AyaneoVendorWriter(binder, probe, fs)
    }

    private fun seed(fs: FakeFileSystem, path: String, value: String) {
        val p = path.toPath()
        p.parent?.let { fs.createDirectories(it) }
        fs.write(p) { writeUtf8(value) }
    }

    @Test
    fun `revert re-sends the journaled stock GPU max as a gpu command and verifies`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val gpuId = Tunables.gpuMaxFreq("/sys/class/kgsl/kgsl-3d0")

        val binder = mockk<AyaneoBinderClient>()
        val s = slot<String>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(capture(s)) } coAnswers {
            // Simulate the overlay actuating the node to whatever value we asked for.
            seed(fs, gpuId.target, s.captured.substringAfterLast(":"))
            sent.add(s.captured)
            true
        }
        val w = writer(binder, fs)

        // Tune down to 585 MHz, then "revert" by writing the stock 680 MHz back (what
        // TunableWriter.revertAll does with the journaled previousValue).
        w.write(gpuId, "585000000")
        val revert = w.write(gpuId, "680000000")

        // The revert issued the stock-restoring GPU command and verified the node moved back.
        assertThat(sent.last()).isEqualTo(
            "calibrate:msg_type_performance:com_set_performance_gpu:680000000"
        )
        assertThat(revert).isInstanceOf(WriteResult.Success::class.java)
        assertThat((revert as WriteResult.Success).newValue).isEqualTo("680000000")
        // The actuated node is back at the stock ceiling.
        assertThat(fs.read(gpuId.target.toPath()) { readUtf8() }).isEqualTo("680000000")
    }

    @Test
    fun `revert re-sends the journaled stock CPU cap as a cpu command (kHz to Hz)`() = runTest {
        val sent = mutableListOf<String>()
        val fs = FakeFileSystem()
        val capId = Tunables.cpuMaxFreq(7)
        seed(fs, capId.target, "3187200") // stock prime ceiling readable

        val binder = mockk<AyaneoBinderClient>()
        val s = slot<String>()
        coEvery { binder.isAvailable() } returns true
        coEvery { binder.sendCommand(capture(s)) } coAnswers {
            // Actuate: the cpu command carries Hz as <repCpu>_<Hz>; reflect kHz into the node.
            val hz = s.captured.substringAfterLast("_").toLong()
            seed(fs, capId.target, (hz / 1000L).toString())
            sent.add(s.captured); true
        }
        val w = writer(binder, fs)

        // Cap to 2419200 kHz, then revert to the stock 3187200 kHz.
        w.write(capId, "2419200")
        val revert = w.write(capId, "3187200")

        assertThat(sent.last()).isEqualTo(
            "calibrate:msg_type_performance:com_set_performance_cpu:7_3187200000"
        )
        assertThat(revert).isInstanceOf(WriteResult.Success::class.java)
        assertThat(fs.read(capId.target.toPath()) { readUtf8() }).isEqualTo("3187200")
    }
}
