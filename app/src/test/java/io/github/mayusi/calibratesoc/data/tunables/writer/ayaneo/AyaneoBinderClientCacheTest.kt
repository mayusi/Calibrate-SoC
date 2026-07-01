package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * CRITICAL-1 / MEDIUM-3 contract test for [AyaneoBinderClient]'s availability cache.
 *
 * The full bind/transact path is Android-framework-bound (bindService / Parcel) and is
 * verified off-device by source reasoning + the live Pocket DS run. What we CAN unit-test
 * here — and what the audit bug is actually about — is the MEMOISATION + INVALIDATION
 * contract that gates LIVE:
 *
 *   - [AyaneoBinderClient.isAvailable] caches its result (the package-presence short-circuit
 *     gives a deterministic, IPC-free probe we can count).
 *   - [AyaneoBinderClient.invalidateAvailabilityCache] busts that cache so the NEXT
 *     isAvailable() RE-PROBES rather than returning the stale value.
 *
 * This is the exact contract [AutoTdpService.runDaemon] and [MainActivity.onResume] now
 * rely on (they call invalidateAvailabilityCache() before each capabilityProbe.refresh()),
 * mirroring [PServerWriter.invalidateTransactableCache]. Without invalidation a stale `true`
 * could survive a gamewindow force-stop/restart and make the LIVE gate claim live on a
 * binder we can no longer drive — the same "claim-live, silently-fail" class as the 384 MHz
 * bug.
 */
class AyaneoBinderClientCacheTest {

    /**
     * android.util.Log is not available in the pure-JVM unit-test runtime. [AyaneoBinderClient]
     * now logs its probe outcome unconditionally (always-on diagnostics, not DEBUG-gated), so
     * the static Log calls must be stubbed or they NPE. Mirrors the sibling
     * AyaneoVendorWriterTest / AppReaperTest setup.
     */
    @Before
    fun stubLog() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
    }

    /**
     * A Context whose PackageManager reports EVERY AYANEO candidate package ABSENT, so
     * [AyaneoBinderClient.isAvailable] short-circuits to false with NO bind/IPC — a clean,
     * deterministic probe we can count via the getPackageInfo invocation.
     *
     * isAvailable() checks each DISTINCT candidate package for presence; with all absent the
     * probe returns false before any bind. We count re-probes via the canonical TARGET_PKG
     * lookup, which runs exactly once per probe pass.
     */
    private fun contextWithGamewindowAbsent(): Pair<Context, PackageManager> {
        val pm = mockk<PackageManager>()
        // Stub presence checks for ALL distinct candidate packages as absent (the candidate
        // list now spans more than one package). Each distinct package is queried once/probe.
        AyaneoBinderClient.CANDIDATES.map { it.first }.distinct().forEach { pkg ->
            every { pm.getPackageInfo(pkg, 0) } throws PackageManager.NameNotFoundException()
        }
        val ctx = mockk<Context>()
        every { ctx.packageManager } returns pm
        every { ctx.applicationContext } returns ctx
        return ctx to pm
    }

    @Test
    fun `isAvailable memoises the probe — the second call does NOT re-probe`() = runTest {
        val (ctx, pm) = contextWithGamewindowAbsent()
        val client = AyaneoBinderClient(ctx)

        assertThat(client.isAvailable()).isFalse()
        assertThat(client.isAvailable()).isFalse()

        // Cached: the package-presence probe ran exactly ONCE despite two isAvailable() calls.
        verify(exactly = 1) { pm.getPackageInfo(AyaneoBinderClient.TARGET_PKG, 0) }
    }

    @Test
    fun `invalidateAvailabilityCache forces the next isAvailable to RE-PROBE (not stale)`() = runTest {
        val (ctx, pm) = contextWithGamewindowAbsent()
        val client = AyaneoBinderClient(ctx)

        // Prime the cache.
        assertThat(client.isAvailable()).isFalse()
        verify(exactly = 1) { pm.getPackageInfo(AyaneoBinderClient.TARGET_PKG, 0) }

        // CRITICAL-1: bust the cache exactly as AutoTdpService/MainActivity do before each
        // capabilityProbe.refresh(). The next isAvailable() MUST re-probe the binder rather
        // than echo the stale cached value.
        client.invalidateAvailabilityCache()
        assertThat(client.isAvailable()).isFalse()

        // The re-probe ran a SECOND package-presence check — the cache was genuinely busted,
        // not returned stale. This is the re-probe-before-refresh guarantee.
        verify(exactly = 2) { pm.getPackageInfo(AyaneoBinderClient.TARGET_PKG, 0) }
    }

    @Test
    fun `repeated invalidation re-probes each time — never serves a stale result`() = runTest {
        val (ctx, pm) = contextWithGamewindowAbsent()
        val client = AyaneoBinderClient(ctx)

        // Three (invalidate → probe) cycles, as would happen across three daemon starts /
        // app resumes after gamewindow restarts. Each cycle must re-probe.
        repeat(3) {
            client.invalidateAvailabilityCache()
            client.isAvailable()
        }

        verify(exactly = 3) { pm.getPackageInfo(AyaneoBinderClient.TARGET_PKG, 0) }
    }
}
