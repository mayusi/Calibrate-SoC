package io.github.mayusi.calibratesoc.data.tunables.writer.ayaneo

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
     * A Context whose PackageManager reports `com.ayaneo.gamewindow` ABSENT, so
     * [AyaneoBinderClient.isAvailable] short-circuits to false with NO bind/IPC — a clean,
     * deterministic probe we can count via the getPackageInfo invocation.
     */
    private fun contextWithGamewindowAbsent(): Pair<Context, PackageManager> {
        val pm = mockk<PackageManager>()
        every {
            pm.getPackageInfo(AyaneoBinderClient.TARGET_PKG, 0)
        } throws PackageManager.NameNotFoundException()
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
