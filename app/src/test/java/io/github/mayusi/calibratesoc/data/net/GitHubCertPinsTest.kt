package io.github.mayusi.calibratesoc.data.net

import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Pure-JVM tests for [GitHubCertPins].
 *
 * Covers:
 *  1. [GitHubCertPins.executeWithPinFallback] — three scenarios:
 *       a) pinnedAttempt succeeds → its result is returned, unpinned NOT called.
 *       b) pinnedAttempt throws [SSLPeerUnverifiedException] → unpinned result returned.
 *       c) pinnedAttempt throws any other exception → it propagates.
 *  2. [GitHubCertPins.pinnedClient] — returns a client with a non-empty [CertificatePinner].
 *
 * No Android runtime is required: [GitHubCertPins] is a pure-Kotlin object.
 * The OkHttp + javax.net.ssl classes are on the JVM classpath via the
 * testImplementation dependencies (okhttp is already a transitive dep of the
 * production code, so it is available to the unit-test runner).
 */
class GitHubCertPinsTest {

    @Before
    fun stubAndroidLog() {
        // executeWithPinFallback logs via android.util.Log.w on the fallback path,
        // which is not available in the pure-JVM unit-test runtime. Stub it to a no-op.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    // ── executeWithPinFallback ────────────────────────────────────────────────

    @Test
    fun `executeWithPinFallback returns pinned result when pinned attempt succeeds`() {
        var unpinnedCalled = false

        val result = GitHubCertPins.executeWithPinFallback(
            tag = "test",
            pinnedAttempt = { "pinned-ok" },
            unpinnedAttempt = {
                unpinnedCalled = true
                "unpinned-ok"
            },
        )

        assertThat(result).isEqualTo("pinned-ok")
        assertThat(unpinnedCalled).isFalse()
    }

    @Test
    fun `executeWithPinFallback returns unpinned result on SSLPeerUnverifiedException`() {
        val result = GitHubCertPins.executeWithPinFallback(
            tag = "test-pin-mismatch",
            pinnedAttempt = {
                throw SSLPeerUnverifiedException("Certificate pinning failure!")
            },
            unpinnedAttempt = { "unpinned-fallback" },
        )

        assertThat(result).isEqualTo("unpinned-fallback")
    }

    @Test
    fun `executeWithPinFallback propagates non-SSL exceptions`() {
        val boom = RuntimeException("network timeout or other real failure")

        val thrown = runCatching {
            GitHubCertPins.executeWithPinFallback<String>(
                tag = "test-other-exception",
                pinnedAttempt = { throw boom },
                unpinnedAttempt = { "should-not-reach" },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(boom)
    }

    @Test
    fun `executeWithPinFallback propagates IOException that is not SSLPeerUnverifiedException`() {
        val ioEx = java.io.IOException("connection reset")

        val thrown = runCatching {
            GitHubCertPins.executeWithPinFallback<String>(
                tag = "test-io-exception",
                pinnedAttempt = { throw ioEx },
                unpinnedAttempt = { "should-not-reach" },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(ioEx)
    }

    @Test
    fun `executeWithPinFallback works with Unit return type`() {
        // Verify that the generic helper works for Unit (common for fire-and-forget calls).
        var pinnedRan = false
        GitHubCertPins.executeWithPinFallback<Unit>(
            tag = "unit-test",
            pinnedAttempt = { pinnedRan = true },
            unpinnedAttempt = { /* should not reach */ },
        )
        assertThat(pinnedRan).isTrue()
    }

    // ── pinnedClient ─────────────────────────────────────────────────────────

    @Test
    fun `pinnedClient returns a client with non-empty CertificatePinner`() {
        val client = GitHubCertPins.pinnedClient()
        // OkHttpClient exposes the pinner; we verify it has at least one pin
        // configured (i.e. it is not the empty no-op pinner).
        val pinner = client.certificatePinner
        // CertificatePinner.pins is an internal field — inspect via toString
        // which includes the pin patterns, or use the public API: check that
        // at least one pin is returned for a known host.
        //
        // The public API only exposes finding pins for a hostname. We check
        // that a known pinned host returns at least one pin entry.
        val pinsForApiGitHub = pinner.findMatchingPins("api.github.com")
        assertThat(pinsForApiGitHub).isNotEmpty()
    }

    @Test
    fun `pinnedClient with custom builder preserves the builder's settings`() {
        // Verify that timeout settings on the builder are preserved by
        // pinnedClient (it must not silently discard builder state).
        val client = GitHubCertPins.pinnedClient(
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(42, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(99, java.util.concurrent.TimeUnit.SECONDS),
        )
        assertThat(client.connectTimeoutMillis).isEqualTo(42_000)
        assertThat(client.readTimeoutMillis).isEqualTo(99_000)
        // And the pinner is still attached.
        assertThat(client.certificatePinner.findMatchingPins("github.com")).isNotEmpty()
    }

    @Test
    fun `certificatePinner has pins for all expected GitHub hosts`() {
        val pinner = GitHubCertPins.certificatePinner
        val hosts = listOf(
            "api.github.com",
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "codeload.github.com",
        )
        for (host in hosts) {
            val pins = pinner.findMatchingPins(host)
            assertWithMessage("pins for $host")
                .that(pins)
                .isNotEmpty()
        }
    }
}
