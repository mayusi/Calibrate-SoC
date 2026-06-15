package io.github.mayusi.calibratesoc.data.shizuku

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ShizukuNodeCache] — the per-node probe result cache.
 *
 * The Shizuku binder is faked via a mock [ShizukuServiceConnection] so these
 * tests are pure JVM / no Android framework.
 */
class ShizukuNodeCacheTest {

    private lateinit var fakeService: ISysfsUserService
    private lateinit var fakeConnection: ShizukuServiceConnection
    private lateinit var cache: ShizukuNodeCache

    @Before
    fun setUp() {
        // ShizukuNodeCache logs via android.util.Log, unavailable in the pure-JVM
        // unit-test runtime. Stub it to a no-op (same pattern as GitHubCertPinsTest).
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        fakeService = mockk()
        fakeConnection = mockk()
        // Wire the mock connection to return our fake service stub.
        every { fakeConnection.service } returns fakeService
        every { fakeConnection.serviceFlow } returns MutableStateFlow(fakeService)
        cache = ShizukuNodeCache(fakeConnection)
    }

    // ── Happy path: probe succeeds → cached as writable ───────────────────────

    @Test
    fun `probeAndCache returns true when service probeWritable returns 0`() = runTest {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        every { fakeService.probeWritable(path) } returns 0

        val result = cache.probeAndCache(path)

        assertThat(result).isTrue()
        assertThat(cache.isCachedWritable(path)).isTrue()
        assertThat(cache.getCached(path)).isEqualTo(true)
    }

    // ── SELinux denial → cached as non-writable ───────────────────────────────

    @Test
    fun `probeAndCache returns false when service returns EACCES`() = runTest {
        val path = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"
        every { fakeService.probeWritable(path) } returns 13 // EACCES

        val result = cache.probeAndCache(path)

        assertThat(result).isFalse()
        assertThat(cache.isCachedWritable(path)).isFalse()
    }

    // ── Cache hit: second probe does not call the binder again ────────────────

    @Test
    fun `probeAndCache uses cached result on second call for same path`() = runTest {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"
        every { fakeService.probeWritable(path) } returns 0

        cache.probeAndCache(path) // first call — hits binder
        cache.probeAndCache(path) // second call — should use cache

        // probeWritable should only have been called once.
        coVerify(exactly = 1) { fakeService.probeWritable(path) }
        assertThat(cache.isCachedWritable(path)).isTrue()
    }

    // ── Service not connected → returns null, no cache entry ──────────────────

    @Test
    fun `probeAndCache returns null when service is not connected`() = runTest {
        every { fakeConnection.service } returns null
        val path = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"

        val result = cache.probeAndCache(path)

        assertThat(result).isNull()
        // Should NOT have a cached entry (null = unknown, not false = denied).
        assertThat(cache.getCached(path)).isNull()
        assertThat(cache.isCachedWritable(path)).isFalse() // isCachedWritable treats null as false
    }

    // ── Binder exception → null result, no cache entry ───────────────────────

    @Test
    fun `probeAndCache returns null on binder exception`() = runTest {
        val path = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
        every { fakeService.probeWritable(path) } throws android.os.RemoteException("binder died")

        val result = cache.probeAndCache(path)

        assertThat(result).isNull()
        assertThat(cache.getCached(path)).isNull()
    }

    // ── clearCache evicts all entries ─────────────────────────────────────────

    @Test
    fun `clearCache removes all cached entries`() = runTest {
        val pathA = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        val pathB = "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"
        every { fakeService.probeWritable(any()) } returns 0

        cache.probeAndCache(pathA)
        cache.probeAndCache(pathB)
        assertThat(cache.isCachedWritable(pathA)).isTrue()
        assertThat(cache.isCachedWritable(pathB)).isTrue()

        cache.clearCache()

        assertThat(cache.getCached(pathA)).isNull()
        assertThat(cache.getCached(pathB)).isNull()
        assertThat(cache.isCachedWritable(pathA)).isFalse()
    }

    // ── Mixed: some paths pass, some denied ───────────────────────────────────

    @Test
    fun `probeAll populates cache with mixed results`() = runTest {
        val gpuPath = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
        val cpuPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        every { fakeService.probeWritable(gpuPath) } returns 13 // denied
        every { fakeService.probeWritable(cpuPath) } returns 0  // allowed

        cache.probeAll(listOf(gpuPath, cpuPath))

        assertThat(cache.isCachedWritable(gpuPath)).isFalse()
        assertThat(cache.isCachedWritable(cpuPath)).isTrue()
    }

    // ── snapshot returns copy of cache ────────────────────────────────────────

    @Test
    fun `snapshot returns consistent map of cached results`() = runTest {
        val path = "/sys/devices/system/cpu/cpu1/online"
        every { fakeService.probeWritable(path) } returns 0

        cache.probeAndCache(path)
        val snap = cache.snapshot()

        assertThat(snap).containsExactly(path, true)
    }

    // ── After clearCache, probe retries the binder ────────────────────────────

    @Test
    fun `after clearCache probeAndCache calls binder again`() = runTest {
        val path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"
        every { fakeService.probeWritable(path) } returns 0

        cache.probeAndCache(path)  // first — populates cache
        cache.clearCache()
        cache.probeAndCache(path)  // second — should re-call binder

        // probeWritable should have been called twice (not just once).
        coVerify(exactly = 2) { fakeService.probeWritable(path) }
    }
}
