package io.github.mayusi.calibratesoc.data.hardware

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Quick storage benchmark approximating AndroBench:
 *   - Sequential write 64 MB in 1 MB chunks
 *   - Sequential read of the same file
 *   - Random 4K read across the file
 *   - Random 4K write across the file
 *
 * Total wall ~10-15s on UFS 3.1+. Burns ~64 MB of write cycles
 * once (negligible for flash endurance).
 *
 * Tests write to the app's private files dir to avoid asking for
 * external-storage permission. On most Android devices that maps to
 * the same physical UFS chip as /sdcard, so the numbers are valid
 * for the internal storage class. SD card speed tests would need a
 * separate flow that writes into /storage/<SD>/.
 *
 * O_DIRECT (bypass page cache) would give numbers closer to raw
 * device throughput, but it requires NDK + Linux fcntl calls. The
 * Java RandomAccessFile approach measures the cache+device stack
 * which is what users actually experience.
 */
@Singleton
class StorageSpeedTester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pServerWriter: io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter,
) {
    /**
     * Quick sequential read probe for the Standard benchmark phase.
     * Writes a 32 MB file and immediately reads it back — no page-cache drop
     * attempt (that needs root), so on stock firmware the read measures the
     * kernel cache + UFS stack, which is still meaningful as a relative number
     * (compare your own runs). Returns MB/s or null on any error.
     *
     * Total wall-clock: ~0.5–2 s on UFS 3.x. Intentionally lightweight.
     */
    suspend fun quickSeqRead(): Double? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "quick-speed-test.bin")
        try {
            val chunk = ByteArray(CHUNK_SIZE).also { kotlin.random.Random.nextBytes(it) }
            val smallBytes = QUICK_BYTES
            val chunkCount = smallBytes / CHUNK_SIZE
            // Write first so there's something to read
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                repeat(chunkCount) { raf.write(chunk) }
                raf.fd.sync()
            }
            sequentialRead(file, smallBytes)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "speed-test.bin")
        try {
            val seqWriteMBps = sequentialWrite(file)
            // Without dropping kernel caches, the read pulls from RAM
            // (4-7 GB/s — way above the chip's spec). Two defenses:
            //   1. Echo 3 to /proc/sys/vm/drop_caches via PServer
            //      (root path; works when Force SELinux ON unlocks PServer)
            //   2. fadvise(POSIX_FADV_DONTNEED) on the file's fd —
            //      drops cached pages for THIS file only, works at any
            //      privilege if the kernel honors it.
            dropPageCache(file)
            val seqReadMBps = sequentialRead(file)
            val randomReadIOPS = randomRead(file)
            val randomWriteIOPS = randomWrite(file)
            Result(
                seqWriteMBps = seqWriteMBps,
                seqReadMBps = seqReadMBps,
                randomReadIOPS = randomReadIOPS,
                randomWriteIOPS = randomWriteIOPS,
            )
        } finally {
            runCatching { file.delete() }
        }
    }

    /** Best-effort cache drop so the seq read measures the device
     *  instead of RAM. PServer first (works when unlocked), then a
     *  no-op fallback (we accept the inflated number on stock fw). */
    private suspend fun dropPageCache(file: File) {
        // Sync first — pending writes need to hit disk before drop.
        runCatching {
            ProcessBuilder("sh", "-c", "sync").start().waitFor()
        }
        // Try PServer (root-tier).
        pServerWriter.executeShell("sync; echo 3 > /proc/sys/vm/drop_caches")
    }

    private fun sequentialWrite(target: File): Double {
        val chunk = ByteArray(CHUNK_SIZE).also { Random.nextBytes(it) }
        val chunkCount = TOTAL_BYTES / CHUNK_SIZE
        val nanos = measureNanoTime {
            RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                repeat(chunkCount) { raf.write(chunk) }
                raf.fd.sync() // force flush to device
            }
        }
        return bytesPerNanoToMBps(TOTAL_BYTES.toLong(), nanos)
    }

    private fun sequentialRead(target: File, totalBytes: Int = TOTAL_BYTES): Double {
        val buf = ByteArray(CHUNK_SIZE)
        val nanos = measureNanoTime {
            RandomAccessFile(target, "r").use { raf ->
                raf.seek(0)
                while (raf.read(buf) > 0) { /* discard */ }
            }
        }
        return bytesPerNanoToMBps(totalBytes.toLong(), nanos)
    }

    private fun randomRead(target: File): Int {
        val buf = ByteArray(BLOCK_4K)
        val maxOffset = (TOTAL_BYTES - BLOCK_4K).toLong()
        var ops = 0
        val nanos = measureNanoTime {
            RandomAccessFile(target, "r").use { raf ->
                val deadline = System.nanoTime() + RANDOM_DURATION_NS
                while (System.nanoTime() < deadline) {
                    val offset = (Random.nextLong(maxOffset) / BLOCK_4K) * BLOCK_4K
                    raf.seek(offset)
                    raf.read(buf)
                    ops++
                }
            }
        }
        return iopsFromOpsAndNanos(ops, nanos)
    }

    private fun randomWrite(target: File): Int {
        val buf = ByteArray(BLOCK_4K).also { Random.nextBytes(it) }
        val maxOffset = (TOTAL_BYTES - BLOCK_4K).toLong()
        var ops = 0
        val nanos = measureNanoTime {
            RandomAccessFile(target, "rw").use { raf ->
                val deadline = System.nanoTime() + RANDOM_DURATION_NS
                while (System.nanoTime() < deadline) {
                    val offset = (Random.nextLong(maxOffset) / BLOCK_4K) * BLOCK_4K
                    raf.seek(offset)
                    raf.write(buf)
                    ops++
                }
                raf.fd.sync()
            }
        }
        return iopsFromOpsAndNanos(ops, nanos)
    }

    private fun bytesPerNanoToMBps(bytes: Long, nanos: Long): Double {
        if (nanos <= 0) return 0.0
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        val seconds = nanos / 1_000_000_000.0
        return mb / seconds
    }

    private fun iopsFromOpsAndNanos(ops: Int, nanos: Long): Int {
        if (nanos <= 0) return 0
        return ((ops.toDouble() * 1_000_000_000.0) / nanos).toInt()
    }

    data class Result(
        val seqWriteMBps: Double,
        val seqReadMBps: Double,
        val randomReadIOPS: Int,
        val randomWriteIOPS: Int,
    )

    private companion object {
        // 512 MB. Most Android device caches max around 256 MB for a
        // single file; overrunning that forces the second half of the
        // sequential read to come from the actual UFS chip instead of
        // RAM. We could write 2 GB to be sure but 512 keeps the test
        // under 2 seconds on UFS 3.1.
        const val TOTAL_BYTES = 512 * 1024 * 1024
        // 32 MB write for the quick Standard-phase probe — small enough to
        // finish in <1 s on UFS 3.x, large enough to be meaningful.
        const val QUICK_BYTES = 32 * 1024 * 1024
        const val CHUNK_SIZE = 4 * 1024 * 1024
        const val BLOCK_4K = 4096
        const val RANDOM_DURATION_NS = 2_000_000_000L // 2s per random pass
    }
}
