package io.github.mayusi.calibratesoc.data.tunables.writer

import android.util.Log
import com.topjohnwu.superuser.Shell
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalibrateSoC-RootWriter"

/**
 * Writer backed by topjohnwu/libsu. Executes `cat` / `echo > path` via a
 * persistent root shell session — much cheaper than spawning `su -c "..."`
 * per call because the shell stays alive across writes.
 *
 * libsu lazily acquires the root grant on the first command — the UI
 * surfaces a Magisk/KernelSU prompt at that moment, not at app launch.
 *
 * Only handles SYSFS tunables — Settings-System writes go through
 * [SettingsKeyWriter] regardless of privilege tier.
 *
 * **WriteProtocol support:** the basic write path is `printf %s value > path`.
 * Some kernels (notably AYN Snapdragon-Elite kernels) need a more elaborate
 * recipe: stop daemons, chmod the file, write, chmod back. Callers attach
 * an optional [WriteProtocol] via the *Protocol overloads — see
 * [io.github.mayusi.calibratesoc.data.tunables.writer.WriteProtocol] for
 * the full discussion.
 */
@Singleton
class RootWriter @Inject constructor() : SysfsWriter {

    override suspend fun read(id: TunableId): String? {
        if (id.kind != TunableKind.SYSFS) return null
        return withContext(Dispatchers.IO) {
            val result = Shell.cmd("cat ${id.target.shellQuote()}").exec()
            if (result.isSuccess) result.out.joinToString("\n").trim().ifBlank { null } else null
        }
    }

    override suspend fun canWrite(id: TunableId): Boolean {
        if (id.kind != TunableKind.SYSFS) return false
        return withContext(Dispatchers.IO) {
            // Cheap rights check: -w on the path tells us SELinux + DAC
            // both allow a root write without actually writing.
            Shell.cmd("test -w ${id.target.shellQuote()}").exec().isSuccess
        }
    }

    override suspend fun write(id: TunableId, value: String): WriteResult =
        writeWithProtocol(id, value, WriteProtocol.NONE)

    /**
     * Write with a per-device recipe. Honours `pre`/`post` shell hooks
     * and the optional chmod-relax / chmod-seal sandwich.
     *
     * BUG 11 fix: previously EVERY hook + chmod exit code was silently discarded
     * (`Shell.cmd(cmd).exec()` with the result dropped on the floor). The failure modes
     * that hid behind that:
     *   - A pre-hook `stop perfd` that FAILS leaves the perf daemon running, which then
     *     races back and overwrites our cpufreq cap milliseconds later — the tune silently
     *     does not stick (a 384 MHz-collapse contributor).
     *   - A `chmod 666` relax that FAILS leaves the 0444 node read-only, so the write below
     *     ENOENTs/EACCESes — yet we'd still report Success off the (separate) write result.
     *   - A `chmod 444` seal that FAILS leaves the sysfs node world-writable (security).
     *   - A post-hook `start <daemon>` that FAILS leaves the device with a perf daemon down.
     *
     * Now every exit code is inspected. The two LOAD-BEARING pre-write steps (the daemon
     * stop pre-hooks and the relax chmod) ABORT the write with [WriteResult.Failed] if they
     * fail, because proceeding would either fight a live daemon or write to a read-only node
     * — i.e. we'd be lying about a tune that cannot land. Post-write steps (seal chmod,
     * daemon restart) are logged HARD on failure but do not retroactively fail an already-
     * landed write; the caller/HUD sees the warning in logcat and the value did take.
     */
    suspend fun writeWithProtocol(
        id: TunableId,
        value: String,
        protocol: WriteProtocol,
    ): WriteResult {
        if (id.kind != TunableKind.SYSFS) {
            return WriteResult.CapabilityDenied(id, "RootWriter handles SYSFS only.")
        }
        return withContext(Dispatchers.IO) {
            // All shell calls funnel through `run`. The real runner executes via libsu;
            // tests inject a fake to drive the BUG 11 abort/log decisions deterministically.
            executeProtocol(id, value, protocol, ::runShell)
        }
    }

    /**
     * Pure-of-libsu core of [writeWithProtocol]: takes a [run] function (so libsu's static
     * [Shell] is injectable in tests) and applies the BUG 11 exit-code discipline. Every
     * hook/chmod result is now inspected:
     *   - pre-hooks + relax chmod failing → ABORT with [WriteResult.Failed] (proceeding
     *     would fight a live daemon or write to a read-only node — a tune that can't land).
     *   - seal chmod + post-hooks failing → logged HARD, but an already-landed write is not
     *     retroactively failed.
     */
    internal fun executeProtocol(
        id: TunableId,
        value: String,
        protocol: WriteProtocol,
        run: (String) -> ShellExecResult,
    ): WriteResult {
        val qpath = id.target.shellQuote()
        val previous = run("cat $qpath").let { if (it.isSuccess) it.stdout.trim().ifBlank { null } else null }

        // ── Pre-hooks (e.g. `stop perfd`) — a failure here means a perf daemon may still
        //    be alive to overwrite our write, so we abort rather than write a value that
        //    won't stick. ─────────────────────────────────────────────────────────────
        for (cmd in protocol.pre) {
            val r = run(cmd)
            if (!r.isSuccess) {
                val err = r.errorText()
                Log.w(TAG, "writeWithProtocol(${id.target}): pre-hook '$cmd' FAILED ($err) — aborting write")
                return WriteResult.Failed(
                    id,
                    IllegalStateException(
                        "Pre-write hook '$cmd' failed ($err); aborting to avoid a tune that " +
                            "a still-running daemon would overwrite.",
                    ),
                )
            }
        }

        // ── Relax chmod — load-bearing: without it the 0444 node rejects the write. ──
        if (protocol.relaxModeBeforeWrite) {
            val r = run("chmod 666 $qpath")
            if (!r.isSuccess) {
                val err = r.errorText()
                Log.w(TAG, "writeWithProtocol(${id.target}): relax 'chmod 666' FAILED ($err) — aborting write")
                return WriteResult.Failed(
                    id,
                    IllegalStateException(
                        "chmod 666 on '${id.target}' failed ($err); the node is read-only so " +
                            "the write cannot land — refusing to claim success.",
                    ),
                )
            }
        }

        // `printf %s ...` rather than `echo`: echo appends a newline
        // that some kernel parsers reject with EINVAL.
        val writeResult = run("printf %s ${value.shellQuote()} > $qpath")

        // ── Seal chmod — post-write; failing leaves the node world-writable (security).
        //    Log HARD but do not unwind an already-landed write. ───────────────────────
        if (protocol.sealModeAfterWriteOctal != 0) {
            val octal = Integer.toOctalString(protocol.sealModeAfterWriteOctal)
            val r = run("chmod $octal $qpath")
            if (!r.isSuccess) {
                Log.e(TAG, "writeWithProtocol(${id.target}): seal 'chmod $octal' FAILED " +
                    "(${r.errorText()}) — node may be left writable")
            }
        }

        // ── Post-hooks (e.g. `start perfd`) — failing leaves a perf daemon down. ──────
        for (cmd in protocol.post) {
            val r = run(cmd)
            if (!r.isSuccess) {
                Log.e(TAG, "writeWithProtocol(${id.target}): post-hook '$cmd' FAILED " +
                    "(${r.errorText()}) — partial state, daemon may be down")
            }
        }

        return if (writeResult.isSuccess) {
            WriteResult.Success(id, previous, value)
        } else {
            WriteResult.Rejected(id = id, errno = writeResult.code, message = writeResult.errorText())
        }
    }

    /** Libsu-backed shell runner. The single point where [Shell] is touched in the write path. */
    private fun runShell(command: String): ShellExecResult {
        val r = Shell.cmd(command).exec()
        return ShellExecResult(
            isSuccess = r.isSuccess,
            code = r.code,
            stdout = r.out.joinToString("\n"),
            stderr = r.err.joinToString("\n"),
        )
    }

    private fun readBlocking(id: TunableId): String? {
        val r = Shell.cmd("cat ${id.target.shellQuote()}").exec()
        return if (r.isSuccess) r.out.joinToString("\n").trim().ifBlank { null } else null
    }

    /**
     * libsu-independent shell result, so [executeProtocol] (and its BUG 11 exit-code
     * discipline) is unit-testable without mocking libsu's final [Shell] chain.
     */
    internal data class ShellExecResult(
        val isSuccess: Boolean,
        val code: Int,
        val stdout: String = "",
        val stderr: String = "",
    ) {
        fun errorText(): String = stderr.ifBlank { "exit $code" }
    }

    /** Defensive quoting for shell arguments. Tunable targets are kernel
     *  paths today, but we still don't want a future path containing a
     *  space or `$` to inject. */
    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
}
