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
}
