package io.github.mayusi.calibratesoc.data.tunables.writer

import com.topjohnwu.superuser.Shell
import io.github.mayusi.calibratesoc.data.tunables.TunableId
import io.github.mayusi.calibratesoc.data.tunables.TunableKind
import io.github.mayusi.calibratesoc.data.tunables.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
     * and the optional chmod-relax / chmod-seal sandwich. Failures in
     * pre/post hooks are logged via the shell exit code embedded in the
     * Rejected result; the write itself is still attempted because the
     * common case (a daemon that's already dead) shouldn't block tuning.
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
            val previous = readBlocking(id)

            for (cmd in protocol.pre) {
                Shell.cmd(cmd).exec() // result ignored — daemons may already be down
            }
            if (protocol.relaxModeBeforeWrite) {
                Shell.cmd("chmod 666 ${id.target.shellQuote()}").exec()
            }

            // `printf %s ...` rather than `echo`: echo appends a newline
            // that some kernel parsers reject with EINVAL.
            val writeCmd = "printf %s ${value.shellQuote()} > ${id.target.shellQuote()}"
            val writeResult = Shell.cmd(writeCmd).exec()

            if (protocol.sealModeAfterWriteOctal != 0) {
                val octal = Integer.toOctalString(protocol.sealModeAfterWriteOctal)
                Shell.cmd("chmod $octal ${id.target.shellQuote()}").exec()
            }
            for (cmd in protocol.post) {
                Shell.cmd(cmd).exec()
            }

            if (writeResult.isSuccess) {
                WriteResult.Success(id, previous, value)
            } else {
                val err = writeResult.err.joinToString("\n").ifBlank { "Shell exit ${writeResult.code}" }
                WriteResult.Rejected(id = id, errno = writeResult.code, message = err)
            }
        }
    }

    private fun readBlocking(id: TunableId): String? {
        val r = Shell.cmd("cat ${id.target.shellQuote()}").exec()
        return if (r.isSuccess) r.out.joinToString("\n").trim().ifBlank { null } else null
    }

    /** Defensive quoting for shell arguments. Tunable targets are kernel
     *  paths today, but we still don't want a future path containing a
     *  space or `$` to inject. */
    private fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
}
