package io.github.mayusi.calibratesoc.data.hardware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureNanoTime

/**
 * Network speed test using Cloudflare's public anycast endpoints.
 *
 *   https://speed.cloudflare.com/__down?bytes=N  — download N bytes
 *   https://speed.cloudflare.com/__up            — upload, body length = throughput
 *
 * These endpoints are designed for in-browser speed tests, are
 * unauthenticated, anycasted (so latency is to the nearest PoP),
 * and have no rate limiting at the volumes we use.
 *
 * Total wall ~15s: 50 MB download + 25 MB upload + two ping passes
 * to cloudflare.com and google.com. Reasonable on a 5G phone, takes
 * longer on a metered slow connection — the user opts in.
 *
 * We don't ship a CDN-agnostic version because every "neutral"
 * provider eventually rate-limits or rebrands; Cloudflare's speed
 * endpoint has been stable for >5 years and has no terms-of-service
 * issue for non-commercial in-app use.
 */
@Singleton
class NetworkSpeedTester @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun run(): NetworkTestResult = withContext(Dispatchers.IO) {
        val download = runCatching { downloadMbps(50 * 1024 * 1024) }.getOrNull()
        val upload = runCatching { uploadMbps(25 * 1024 * 1024) }.getOrNull()
        val pingCf = runCatching { pingMs("https://speed.cloudflare.com/__down?bytes=1") }.getOrNull()
        val pingGoogle = runCatching { pingMs("https://www.google.com/generate_204") }.getOrNull()
        NetworkTestResult(
            downloadMbps = download,
            uploadMbps = upload,
            latencyCloudflareMs = pingCf,
            latencyGoogleMs = pingGoogle,
            measuredAtMs = System.currentTimeMillis(),
        )
    }

    private fun downloadMbps(bytes: Int): Double {
        val req = Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=$bytes")
            .header("Cache-Control", "no-cache")
            .build()
        val buf = ByteArray(64 * 1024)
        var read = 0L
        val nanos = measureNanoTime {
            client.newCall(req).execute().use { resp ->
                val body = resp.body ?: throw IOException("no body")
                body.byteStream().use { stream ->
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        read += n
                    }
                }
            }
        }
        return bytesPerNanoToMbps(read, nanos)
    }

    private fun uploadMbps(bytes: Int): Double {
        // Upload by streaming a request body of `bytes` random data.
        val data = ByteArray(bytes).also { kotlin.random.Random.nextBytes(it) }
        val req = Request.Builder()
            .url("https://speed.cloudflare.com/__up")
            .post(okhttp3.RequestBody.create(null, data))
            .build()
        val nanos = measureNanoTime {
            client.newCall(req).execute().use { resp ->
                resp.body?.close()
            }
        }
        return bytesPerNanoToMbps(bytes.toLong(), nanos)
    }

    private fun pingMs(url: String): Long {
        val req = Request.Builder().url(url).head().build()
        val nanos = measureNanoTime {
            client.newCall(req).execute().use { it.body?.close() }
        }
        return nanos / 1_000_000L
    }

    /** Convert bytes-over-nanoseconds to Mbps (megabits per second,
     *  not megabytes — speed-test convention). */
    private fun bytesPerNanoToMbps(bytes: Long, nanos: Long): Double {
        if (nanos <= 0) return 0.0
        val bits = bytes.toDouble() * 8.0
        val seconds = nanos / 1_000_000_000.0
        return (bits / 1_000_000.0) / seconds
    }
}
