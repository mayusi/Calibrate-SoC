package io.github.mayusi.calibratesoc.data.tunables.writer

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the BUG 11 fix in [RootWriter.executeProtocol].
 *
 * The bug: every pre/post hook + chmod exit code was silently discarded (the libsu shell
 * result was dropped). A failing `stop perfd` left a daemon racing the write (tune doesn't
 * stick); a failing `chmod 666` left the node read-only (write can't land yet we'd claim
 * success); a failing `chmod 444` seal left the node writable (security); a failing
 * `start perfd` left a daemon down.
 *
 * The fix routes every shell call through an injectable runner and inspects each result:
 *   - pre-hook OR relax-chmod failure -> abort with [WriteResult.Failed].
 *   - seal-chmod / post-hook failure -> logged HARD, write result preserved.
 *
 * Tests inject a fake runner (no libsu, no root, no device) and assert the control flow.
 */
class RootWriterProtocolHookTest {

    private val cpuCap = TunableId(
        TunableKind.SYSFS,
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq",
    )

    private val writer = RootWriter()

    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    private fun ok(stdout: String = "") = RootWriter.ShellExecResult(isSuccess = true, code = 0, stdout = stdout)
    private fun fail(code: Int = 1, stderr: String = "") =
        RootWriter.ShellExecResult(isSuccess = false, code = code, stderr = stderr)

    /** A recording runner driven by a per-command result function. */
    private class FakeRunner(val resultFor: (String) -> RootWriter.ShellExecResult) :
        (String) -> RootWriter.ShellExecResult {
        val calls = mutableListOf<String>()
        override fun invoke(command: String): RootWriter.ShellExecResult {
            calls.add(command)
            return resultFor(command)
        }
    }

    // -- THE BUG 11 FIX: pre-hook failure aborts the write --

    @Test
    fun `pre-hook failure aborts with Failed and never reaches the write`() {
        val protocol = WriteProtocol(
            pre = listOf("stop perfd"),
            post = listOf("start perfd"),
            relaxModeBeforeWrite = true,
            sealModeAfterWriteOctal = WriteProtocol.MODE_READ_ONLY,
        )
        val runner = FakeRunner { cmd ->
            when {
                cmd.startsWith("cat ") -> ok("3187200")
                cmd == "stop perfd" -> fail(stderr = "service not found")
                else -> ok()
            }
        }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Failed::class.java)
        assertThat(runner.calls.any { it.startsWith("printf") }).isFalse()
        assertThat(runner.calls.any { it.startsWith("chmod 666") }).isFalse()
    }

    // -- relax-chmod failure aborts the write --

    @Test
    fun `relax chmod failure aborts with Failed and never writes`() {
        val protocol = WriteProtocol(relaxModeBeforeWrite = true)
        val runner = FakeRunner { cmd ->
            when {
                cmd.startsWith("cat ") -> ok("3187200")
                cmd.startsWith("chmod 666") -> fail(stderr = "Operation not permitted")
                else -> ok()
            }
        }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Failed::class.java)
        assertThat(runner.calls.any { it.startsWith("printf") }).isFalse()
    }

    // -- seal-chmod failure is logged but the write still succeeds --

    @Test
    fun `seal chmod failure does not unwind an already-landed write`() {
        val protocol = WriteProtocol(
            relaxModeBeforeWrite = true,
            sealModeAfterWriteOctal = WriteProtocol.MODE_READ_ONLY,
        )
        val runner = FakeRunner { cmd ->
            when {
                cmd.startsWith("cat ") -> ok("3187200")
                cmd.startsWith("chmod 444") -> fail(stderr = "read-only fs")
                else -> ok()
            }
        }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat(runner.calls.any { it.startsWith("printf") }).isTrue()
    }

    // -- post-hook failure is logged but the write still succeeds --

    @Test
    fun `post-hook failure does not fail an already-landed write`() {
        val protocol = WriteProtocol(
            pre = listOf("stop perfd"),
            post = listOf("start perfd"),
        )
        val runner = FakeRunner { cmd ->
            when {
                cmd.startsWith("cat ") -> ok("3187200")
                cmd == "start perfd" -> fail(stderr = "already running")
                else -> ok()
            }
        }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        assertThat(runner.calls).contains("start perfd")
    }

    // -- happy path: all hooks succeed, full sequence runs in order --

    @Test
    fun `all hooks succeed - full sequence executes in the right order`() {
        val protocol = WriteProtocol(
            pre = listOf("stop perfd"),
            post = listOf("start perfd"),
            relaxModeBeforeWrite = true,
            sealModeAfterWriteOctal = WriteProtocol.MODE_READ_ONLY,
        )
        val runner = FakeRunner { cmd -> if (cmd.startsWith("cat ")) ok("3187200") else ok() }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val success = result as WriteResult.Success
        assertThat(success.previousValue).isEqualTo("3187200")
        assertThat(success.newValue).isEqualTo("1800000")
        val order = runner.calls.filterNot { it.startsWith("cat ") }
        assertThat(order[0]).isEqualTo("stop perfd")
        assertThat(order[1]).startsWith("chmod 666")
        assertThat(order[2]).startsWith("printf")
        assertThat(order[3]).startsWith("chmod 444")
        assertThat(order[4]).isEqualTo("start perfd")
    }

    // -- the underlying write itself failing -> Rejected (unchanged behaviour) --

    @Test
    fun `write printf failure yields Rejected`() {
        val protocol = WriteProtocol.NONE
        val runner = FakeRunner { cmd ->
            when {
                cmd.startsWith("cat ") -> ok("3187200")
                cmd.startsWith("printf") -> fail(code = 1, stderr = "EINVAL")
                else -> ok()
            }
        }

        val result = writer.executeProtocol(cpuCap, "1800000", protocol, runner)

        assertThat(result).isInstanceOf(WriteResult.Rejected::class.java)
        val rejected = result as WriteResult.Rejected
        assertThat(rejected.errno).isEqualTo(1)
    }
}
