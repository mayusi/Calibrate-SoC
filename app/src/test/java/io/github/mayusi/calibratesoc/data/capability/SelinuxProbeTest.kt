package io.github.mayusi.calibratesoc.data.capability

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Before
import org.junit.Test

/**
 * Hermetic unit tests for [SelinuxProbe] — no Android, no device.
 *
 * Covers the three honest outcomes (Enforcing / Permissive / unknown) across both
 * read paths:
 *   - the world-readable `/sys/fs/selinux/enforce` file (via [FakeFileSystem]), and
 *   - the authoritative root `getenforce` read (via a fake runner lambda) which is
 *     trusted when PServer is live.
 */
class SelinuxProbeTest {

    private lateinit var fs: FakeFileSystem
    private lateinit var probe: SelinuxProbe

    private val enforcePath = "/sys/fs/selinux/enforce".toPath()

    @Before
    fun setUp() {
        fs = FakeFileSystem()
        probe = SelinuxProbe(fs)
    }

    private fun writeEnforce(content: String) {
        fs.createDirectories(enforcePath.parent!!)
        fs.write(enforcePath) { writeUtf8(content) }
    }

    /** A getenforce runner that always fails (no PServer / IPC error). */
    private val noGetenforce: suspend (String) -> Pair<Int, String>? = { null }

    // ── File read path (pserverLive = false) ──────────────────────────────────

    @Test
    fun `file read 1 means enforcing`() = runTest {
        writeEnforce("1")
        assertThat(probe.probe(pserverLive = false, runGetenforce = noGetenforce)).isTrue()
    }

    @Test
    fun `file read 0 means permissive`() = runTest {
        writeEnforce("0")
        assertThat(probe.probe(pserverLive = false, runGetenforce = noGetenforce)).isFalse()
    }

    @Test
    fun `file read with trailing newline still parses`() = runTest {
        writeEnforce("1\n")
        assertThat(probe.probe(pserverLive = false, runGetenforce = noGetenforce)).isTrue()
    }

    @Test
    fun `missing file is unknown`() = runTest {
        // No file written → read fails → null.
        assertThat(probe.probe(pserverLive = false, runGetenforce = noGetenforce)).isNull()
    }

    @Test
    fun `garbage file content is unknown`() = runTest {
        writeEnforce("banana")
        assertThat(probe.probe(pserverLive = false, runGetenforce = noGetenforce)).isNull()
    }

    @Test
    fun `getenforce is NOT consulted when pserver is not live`() = runTest {
        writeEnforce("0")
        var called = false
        val runner: suspend (String) -> Pair<Int, String>? = { called = true; 0 to "Enforcing" }
        // pserverLive=false → file says permissive → false, and the root runner is never called.
        assertThat(probe.probe(pserverLive = false, runGetenforce = runner)).isFalse()
        assertThat(called).isFalse()
    }

    // ── Authoritative root read path (pserverLive = true) ─────────────────────

    @Test
    fun `getenforce Enforcing wins when pserver live`() = runTest {
        // File would say permissive, but the authoritative root read says Enforcing.
        writeEnforce("0")
        val runner: suspend (String) -> Pair<Int, String>? = { 0 to "Enforcing" }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isTrue()
    }

    @Test
    fun `getenforce Permissive wins when pserver live`() = runTest {
        writeEnforce("1")
        val runner: suspend (String) -> Pair<Int, String>? = { 0 to "Permissive" }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isFalse()
    }

    @Test
    fun `getenforce is case-insensitive and trims`() = runTest {
        val runner: suspend (String) -> Pair<Int, String>? = { 0 to "  enforcing  " }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isTrue()
    }

    @Test
    fun `getenforce non-zero status falls back to file`() = runTest {
        writeEnforce("1")
        // Root read failed (non-zero status) → fall back to the file, which is Enforcing.
        val runner: suspend (String) -> Pair<Int, String>? = { 1 to "" }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isTrue()
    }

    @Test
    fun `getenforce null result falls back to file`() = runTest {
        writeEnforce("0")
        val runner: suspend (String) -> Pair<Int, String>? = { null }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isFalse()
    }

    @Test
    fun `getenforce garbage falls back to file`() = runTest {
        writeEnforce("1")
        val runner: suspend (String) -> Pair<Int, String>? = { 0 to "what" }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isTrue()
    }

    @Test
    fun `both reads fail is unknown`() = runTest {
        // No file AND root read fails → null (never guessed).
        val runner: suspend (String) -> Pair<Int, String>? = { null }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isNull()
    }

    @Test
    fun `getenforce that throws is swallowed and falls back to file`() = runTest {
        writeEnforce("0")
        val runner: suspend (String) -> Pair<Int, String>? = { throw RuntimeException("binder died") }
        assertThat(probe.probe(pserverLive = true, runGetenforce = runner)).isFalse()
    }
}
