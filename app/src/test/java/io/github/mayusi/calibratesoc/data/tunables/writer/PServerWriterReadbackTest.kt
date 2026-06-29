package io.github.mayusi.calibratesoc.data.tunables.writer

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the BUG 10 honesty fix in [PServerWriter.classifyReadback].
 *
 * The bug: on a null readback (the node could not be cat-back to confirm the write
 * landed), the old code returned `Success(verified=true)` — JOURNALING an unconfirmed
 * write as confirmed. AutoTDP then believed the cap landed, and with a null previousValue
 * boot-revert became a no-op, so the device could be left capped (384 MHz-collapse class).
 *
 * The fix: a null readback returns `Success(verified=FALSE)`. [TunableWriter.revertAll]
 * already treats an unverified critical-node revert as not-fully-confirmed and KEEPS the
 * journal so BootRevertReceiver stays armed.
 *
 * These tests call the extracted pure [PServerWriter.classifyReadback] directly — no real
 * binder/IPC, matching [PServerWriterLiveTest]'s "all IPC mocked" convention.
 */
class PServerWriterReadbackTest {

    private val cpuCap = TunableId(
        TunableKind.SYSFS,
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
    )

    private fun writer(): PServerWriter = PServerWriter(mockk(relaxed = true))

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    // ── THE BUG 10 FIX ────────────────────────────────────────────────────────

    @Test
    fun `null readback returns Success but verified=false (never a fake-confirmed write)`() {
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = null,     // could NOT confirm
            previous = null,     // pre-read also failed (the worst case)
            status = 0,          // shell exit 0 — the lie the old code believed
        )
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val success = result as WriteResult.Success
        // The core invariant: an unconfirmed write is NOT claimed as verified.
        assertThat(success.verified).isFalse()
        // newValue reflects the intended (we asked for it) but verified=false is the honesty.
        assertThat(success.newValue).isEqualTo("1800000")
    }

    @Test
    fun `confirmed exact readback is verified=true`() {
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = "1800000",
            previous = "3187200",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val success = result as WriteResult.Success
        assertThat(success.verified).isTrue()
        assertThat(success.previousValue).isEqualTo("3187200")
    }

    @Test
    fun `OPP-snapped numeric readback within tolerance is verified=true`() {
        // Kernel snapped 1800000 kHz to the nearest OPP step 1804800 kHz (< 100 MHz away).
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = "1804800",
            previous = "3187200",
            status = 0,
        )
        val success = result as WriteResult.Success
        assertThat(success.verified).isTrue()
        // newValue carries the ACTUAL kernel value, not our request.
        assertThat(success.newValue).isEqualTo("1804800")
    }

    @Test
    fun `readback mismatch is Rejected, never Success`() {
        // Kernel ignored the write and the node still reads the old hard-capped min.
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = "384000",   // the 384 MHz collapse value, far outside tolerance
            previous = "3187200",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
        val rejected = result as WriteResult.Rejected
        assertThat(rejected.message).contains("384000")
    }

    @Test
    fun `small control knob mismatch (GPU pwrlevel) is Rejected — tolerance does not mask it`() {
        // GPU power levels are 0-7; a 100 MHz tolerance must NOT apply or any value passes.
        val pwrLevel = TunableId(TunableKind.SYSFS, "/sys/class/kgsl/kgsl-3d0/min_pwrlevel")
        val result = writer().classifyReadback(
            id = pwrLevel,
            intended = "3",
            readback = "0",
            previous = "5",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
    }

    @Test
    fun `mismatch Rejected carries the parsed readback and previous values (Finding 1)`() {
        // The apply loop needs the numeric readback + prior to decide converge vs honest-fail.
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = "2400000",   // kernel clamped to a DIFFERENT valid OPP (> tolerance)
            previous = "3187200",
            status = 0,
        )
        val rejected = result as WriteResult.Rejected
        assertThat(rejected.readbackValue).isEqualTo(2_400_000L)
        assertThat(rejected.previousValue).isEqualTo(3_187_200L)
    }

    // ── FINDING 2: GPU devfreq nodes are Hz-valued, not kHz ─────────────────────

    private val gpuMaxFreqHz = TunableId(
        TunableKind.SYSFS,
        "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq",
    )
    private val gpuMinFreqHz = TunableId(
        TunableKind.SYSFS,
        "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
    )

    @Test
    fun `GPU devfreq Hz readback within a real OPP is accepted (Hz-unit tolerance)`() {
        // 1_100_000_000 Hz (1.1 GHz) intended; the kgsl devfreq core echoes back the
        // OPP-normalised 1_099_200_000 Hz (800 kHz away = 0.8 MHz, well inside the 100 MHz
        // snap window). With the OLD kHz tolerance (100_000 = 0.1 MHz) this FALSELY rejected;
        // the Hz-aware tolerance accepts it as the verified, landed value.
        val result = writer().classifyReadback(
            id = gpuMaxFreqHz,
            intended = "1100000000",
            readback = "1099200000",
            previous = "800000000",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val success = result as WriteResult.Success
        assertThat(success.verified).isTrue()
        assertThat(success.newValue).isEqualTo("1099200000")
    }

    @Test
    fun `GPU devfreq Hz exact readback is accepted`() {
        val result = writer().classifyReadback(
            id = gpuMinFreqHz,
            intended = "525000000",
            readback = "525000000",
            previous = "220000000",
            status = 0,
        )
        val success = result as WriteResult.Success
        assertThat(success.verified).isTrue()
    }

    @Test
    fun `genuinely wrong GPU devfreq Hz readback is still Rejected honestly`() {
        // Kernel ignored the write and the node still reads the old floor — ~880 MHz away,
        // far outside the 100 MHz Hz-window. Must NOT be masked into a fake success.
        val result = writer().classifyReadback(
            id = gpuMaxFreqHz,
            intended = "1100000000",
            readback = "220000000",   // unchanged stock floor — the write had no effect
            previous = "220000000",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
        val rejected = result as WriteResult.Rejected
        // Carries the parsed Hz values so the apply loop classifies it as a no-effect honest fail.
        assertThat(rejected.readbackValue).isEqualTo(220_000_000L)
        assertThat(rejected.previousValue).isEqualTo(220_000_000L)
    }

    @Test
    fun `Hz-scale snap delta on a kHz cpufreq node is correctly Rejected (unit isolation)`() {
        // Guard against over-widening: a 100 MHz-in-Hz window (100_000_000) must NOT leak onto
        // a kHz cpufreq node. Here intended 1_800_000 kHz vs readback 2_400_000 kHz is 600 MHz
        // apart in real terms — far outside the kHz 100 MHz window — and must stay Rejected.
        val result = writer().classifyReadback(
            id = cpuCap,
            intended = "1800000",
            readback = "2400000",
            previous = "3187200",
            status = 0,
        )
        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
    }
}
