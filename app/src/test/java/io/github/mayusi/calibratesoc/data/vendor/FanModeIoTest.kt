package io.github.mayusi.calibratesoc.data.vendor

import android.content.ContentResolver
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.capability.CapabilityReport
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class FanModeIoTest {

    private val contentResolver: ContentResolver = mockk()
    private val tunableWriter: TunableWriter = mockk()
    private val report: CapabilityReport = mockk(relaxed = true)

    private val fanKey = "ayn_fan_mode"
    private val sampleId = TunableId(TunableKind.SETTINGS_SYSTEM, fanKey)

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    // --- readFanMode ---

    @Test
    fun `readFanMode returns value from Settings System`() {
        mockkStatic(android.provider.Settings.System::class)
        every {
            android.provider.Settings.System.getString(contentResolver, fanKey)
        } returns "performance"

        val result = readFanMode(contentResolver, fanKey)

        assertThat(result).isEqualTo("performance")
    }

    @Test
    fun `readFanMode returns null when Settings System throws`() {
        mockkStatic(android.provider.Settings.System::class)
        every {
            android.provider.Settings.System.getString(contentResolver, fanKey)
        } throws RuntimeException("settings gone")

        val result = readFanMode(contentResolver, fanKey)

        assertThat(result).isNull()
    }

    @Test
    fun `readFanMode returns null when value is null`() {
        mockkStatic(android.provider.Settings.System::class)
        every {
            android.provider.Settings.System.getString(contentResolver, fanKey)
        } returns null

        val result = readFanMode(contentResolver, fanKey)

        assertThat(result).isNull()
    }

    // --- writeFanMode ---

    @Test
    fun `writeFanMode delegates to TunableWriter with SETTINGS_SYSTEM kind`() = runTest {
        val idSlot = slot<TunableId>()
        coEvery {
            tunableWriter.write(id = capture(idSlot), value = any(), report = any(), reason = any())
        } returns WriteResult.Success(id = sampleId, previousValue = "auto", newValue = "performance")

        val result = writeFanMode(tunableWriter, fanKey, "performance", report, "test")

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat(idSlot.captured.kind).isEqualTo(TunableKind.SETTINGS_SYSTEM)
        assertThat(idSlot.captured.target).isEqualTo(fanKey)
    }

    @Test
    fun `writeFanMode passes value and reason to TunableWriter`() = runTest {
        val valueSlot = slot<String>()
        val reasonSlot = slot<String>()
        coEvery {
            tunableWriter.write(id = any(), value = capture(valueSlot), report = any(), reason = capture(reasonSlot))
        } returns WriteResult.Success(id = sampleId, previousValue = "auto", newValue = "performance")

        writeFanMode(tunableWriter, fanKey, "performance", report, "fan-curve-apply")

        assertThat(valueSlot.captured).isEqualTo("performance")
        assertThat(reasonSlot.captured).isEqualTo("fan-curve-apply")
    }

    @Test
    fun `writeFanMode returns CapabilityDenied when writer cannot handle the key`() = runTest {
        coEvery {
            tunableWriter.write(id = any(), value = any(), report = any(), reason = any())
        } returns WriteResult.CapabilityDenied(id = sampleId, reason = "no tier for SETTINGS_SYSTEM")

        val result = writeFanMode(tunableWriter, fanKey, "performance", report, "test")

        assertThat(result).isInstanceOf(WriteResult.CapabilityDenied::class.java)
    }

    @Test
    fun `writeFanMode returns Rejected when kernel rejects the write`() = runTest {
        coEvery {
            tunableWriter.write(id = any(), value = any(), report = any(), reason = any())
        } returns WriteResult.Rejected(id = sampleId, errno = null, message = "settings provider error")

        val result = writeFanMode(tunableWriter, fanKey, "performance", report, "test")

        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
    }

    @Test
    fun `writeFanMode returns Failed on unexpected error`() = runTest {
        coEvery {
            tunableWriter.write(id = any(), value = any(), report = any(), reason = any())
        } returns WriteResult.Failed(id = sampleId, error = RuntimeException("binder died"))

        val result = writeFanMode(tunableWriter, fanKey, "performance", report, "test")

        assertThat(result).isInstanceOf(WriteResult.Failed::class.java)
    }
}
