package io.github.mayusi.calibratesoc.data.autotdp

import com.google.common.truth.Truth.assertThat
import io.github.mayusi.calibratesoc.data.tunables.TunableWriter
import io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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
 * WAVE 3A — session-level perfd lifecycle. The vendor perf daemons MUST be restarted on
 * EVERY AutoTDP exit (even cancellation), or the device is left with vendor perf management
 * disabled until reboot. [PerfDaemonController.restoreForSession] mirrors [AutoTdpRevert]:
 * NonCancellable + idempotent. These tests prove:
 *   - stop-on-engage issues `stop <daemon>` for each daemon AND suppresses the per-write dance
 *   - restart-on-exit issues `start <daemon>` and clears the suppression
 *   - restore SURVIVES cancellation of the calling job (NonCancellable)
 *   - restore is idempotent across the 4 exit paths
 *   - we never `start` a daemon we didn't `stop` (no stop ran ⇒ restore issues no start)
 *   - the controller is a no-op when disabled or when there are no daemons
 */
class PerfDaemonControllerTest {

    private val daemons = listOf("perfd", "vendor.perf-hal-1-0")

    private fun controller(
        pserver: PServerWriter,
        writer: TunableWriter,
        daemonList: List<String> = daemons,
    ) = PerfDaemonController(pserver, writer, daemonList)

    /** A PServerWriter mock whose executeShell records commands and never fails. */
    private fun pserverRecording(commands: MutableList<String>): PServerWriter {
        val p = mockk<PServerWriter>()
        coEvery { p.executeShell(any()) } coAnswers {
            commands.add(firstArg())
            0 to ""
        }
        return p
    }

    private fun writerStubbingSuppression(): TunableWriter {
        val w = mockk<TunableWriter>()
        every { w.setPerfDaemonsSessionStopped(any()) } just Runs
        return w
    }

    @Test
    fun `stopForSession issues stop per daemon and suppresses the per-write dance`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)

        val did = c.stopForSession(enabled = true)

        assertThat(did).isTrue()
        assertThat(cmds).containsExactly("stop perfd", "stop vendor.perf-hal-1-0").inOrder()
        // Per-write daemon dance must be suppressed for the duration.
        verify(exactly = 1) { writer.setPerfDaemonsSessionStopped(true) }
    }

    @Test
    fun `restoreForSession issues start per daemon and clears the suppression`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)

        c.stopForSession(enabled = true)
        cmds.clear()
        val did = c.restoreForSession()

        assertThat(did).isTrue()
        assertThat(cmds).containsExactly("start perfd", "start vendor.perf-hal-1-0").inOrder()
        // Suppression cleared so the next session's per-write dance resumes.
        verify(exactly = 1) { writer.setPerfDaemonsSessionStopped(false) }
    }

    @Test
    fun `restoreForSession runs even when the calling job is cancelled`() = runBlocking {
        // executeShell signals start then suspends on delay(1) — the cancellation tripwire.
        // If restoreForSession failed to shield with NonCancellable, the cancelled job
        // would throw at delay() and the start commands would never be issued.
        val starts = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val pserver = mockk<PServerWriter>()
        coEvery { pserver.executeShell(any()) } coAnswers {
            val cmd = firstArg<String>()
            if (cmd.startsWith("start")) {
                entered.complete(Unit)
                delay(1)
                starts.incrementAndGet()
            }
            0 to ""
        }
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)
        c.stopForSession(enabled = true)

        val job: Job = launch(Dispatchers.Default) {
            c.restoreForSession()
        }
        withTimeout(2_000) { entered.await() }
        job.cancel()
        job.join()

        // Both starts must have run despite the cancellation.
        assertThat(starts.get()).isEqualTo(2)
    }

    @Test
    fun `restoreForSession is idempotent across the four exit paths`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)
        c.stopForSession(enabled = true)
        cmds.clear()

        // Simulate stopDaemon + onDestroy + onTaskRemoved + the finally all racing.
        val jobs = (1..4).map {
            launch(Dispatchers.Default) { c.restoreForSession() }
        }
        jobs.forEach { it.join() }

        // start issued exactly once per daemon despite four restore calls.
        assertThat(cmds.count { it == "start perfd" }).isEqualTo(1)
        assertThat(cmds.count { it == "start vendor.perf-hal-1-0" }).isEqualTo(1)
        // Suppression cleared exactly once.
        verify(exactly = 1) { writer.setPerfDaemonsSessionStopped(false) }
    }

    @Test
    fun `restore without a prior stop issues no start but still clears suppression`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)

        // No stopForSession call — e.g. a stop fired before capabilities resolved.
        val did = c.restoreForSession()

        assertThat(did).isTrue()
        // We NEVER start a daemon we did not stop.
        assertThat(cmds).isEmpty()
        coVerify(exactly = 0) { pserver.executeShell(match { it.startsWith("start") }) }
        // The suppression flag is still cleared defensively (it can never be left set).
        verify(exactly = 1) { writer.setPerfDaemonsSessionStopped(false) }
    }

    @Test
    fun `stopForSession is a no-op when disabled`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)

        val did = c.stopForSession(enabled = false)

        assertThat(did).isFalse()
        assertThat(cmds).isEmpty()
        verify(exactly = 0) { writer.setPerfDaemonsSessionStopped(true) }
    }

    @Test
    fun `stopForSession is a no-op when there are no daemons`() = runBlocking {
        val cmds = mutableListOf<String>()
        val pserver = pserverRecording(cmds)
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer, daemonList = emptyList())

        val did = c.stopForSession(enabled = true)

        assertThat(did).isFalse()
        assertThat(cmds).isEmpty()
        verify(exactly = 0) { writer.setPerfDaemonsSessionStopped(true) }
    }

    @Test
    fun `stopForSession runs at most once`() = runBlocking {
        val calls = AtomicInteger(0)
        val pserver = mockk<PServerWriter>()
        coEvery { pserver.executeShell(any()) } coAnswers {
            calls.incrementAndGet()
            0 to ""
        }
        val writer = writerStubbingSuppression()
        val c = controller(pserver, writer)

        val first = c.stopForSession(enabled = true)
        val second = c.stopForSession(enabled = true)

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        // Two daemons stopped on the first call only.
        assertThat(calls.get()).isEqualTo(2)
    }
}
