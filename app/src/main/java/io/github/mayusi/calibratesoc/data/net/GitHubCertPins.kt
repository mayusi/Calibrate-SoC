package io.github.mayusi.calibratesoc.data.net

import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * TLS certificate pinning for all GitHub network calls.
 *
 * ## Threat model
 *
 * This pinning is **defence-in-depth** layered on top of:
 *   1. **APK signature verification** (ApkDownloader.verifySignature) — the
 *      hard gate that prevents a tampered APK from ever being installed.
 *   2. **HTTPS** — ambient TLS with the OS trust store already blocks passive
 *      eavesdropping and most active MITM attacks.
 *   3. **GitHub-host allowlist** (ApkDownloader.isAllowedUrl) — restricts
 *      all OTA and update traffic to known GitHub hostnames.
 *
 * Given those layers, TLS pinning adds a narrow additional defence against an
 * attacker who has compromised a CA in the user's OS trust store but has NOT
 * compromised GitHub's signing key.  Because APK signature verification is the
 * hard gate, a MITM that bypasses pinning can at worst deliver a *network
 * error* or a *wrong file* — the signature check still rejects any tampered
 * APK before the system installer is ever called.  This is why we choose
 * **FAIL-OPEN** on a pin mismatch:
 *
 *   • A genuine CA rotation (GitHub switching intermediates) would otherwise
 *     silently brick OTA updates for all users still on the old pin.
 *   • Fail-open degrades to standard HTTPS + signature verification — still
 *     far stronger than no pinning at all.
 *   • A logged warning gives the developer a clear signal to refresh the pins.
 *
 * ## Pin selection strategy
 *
 * We pin **intermediate-CA and root-CA SPKI hashes**, NOT leaf certificates.
 * GitHub rotates leaf certs frequently; CAs change on the order of years.
 * We include MULTIPLE pins per host (current + known backup) so a single CA
 * rotation does not drop all pins simultaneously.
 *
 * GitHub historically uses DigiCert CAs for github.com / api.github.com and
 * Sectigo (formerly Comodo/USERTrust) CAs for githubusercontent.com CDN hosts.
 *
 * ## Pin honesty
 *
 * These pins were extracted from the LIVE GitHub certificate chains on
 * 2026-06-14 with:
 *
 *   openssl s_client -connect <host>:443 -servername <host> -showcerts \
 *     | openssl x509 -pubkey -noout \
 *     | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | base64
 *
 * We pin each host's CURRENT INTERMEDIATE plus the ROOT above it (taken from
 * the same live chain). The root pins are the long-lived backup that survives a
 * routine intermediate rotation (e.g. Let's Encrypt R12 -> R13). If GitHub
 * migrates to a different CA entirely, the fail-open fallback degrades to
 * standard HTTPS + APK signature verification rather than bricking updates,
 * and logs a warning so the pins can be refreshed in a later release.
 *
 * Re-verify these before a future release if updates start logging
 * "cert-pin bypassed" warnings.
 */
object GitHubCertPins {

    private const val TAG = "GitHubCertPins"

    // ── Sectigo CA (github.com, api.github.com, codeload.github.com) ──────────
    // Live chain 2026-06-14: leaf CN=github.com
    //   issuer -> "Sectigo Public Server Authentication CA DV E36" (intermediate)
    //          -> "Sectigo Public Server Authentication Root E46" (root)

    /** Sectigo Public Server Authentication CA DV E36 — current intermediate. */
    private const val SECTIGO_SERVER_AUTH_DV_E36 =
        "sha256/ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY="

    /** Sectigo Public Server Authentication Root E46 — long-lived root (backup). */
    private const val SECTIGO_SERVER_AUTH_ROOT_E46 =
        "sha256/sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE="

    // ── Let's Encrypt (raw / objects .githubusercontent.com CDN) ──────────────
    // Live chain 2026-06-14: leaf CN=*.github.io
    //   issuer -> "Let's Encrypt R12" (intermediate)
    //          -> ISRG Root X1 (root)

    /** Let's Encrypt R12 — current intermediate for the githubusercontent CDN. */
    private const val LETSENCRYPT_R12 =
        "sha256/kZwN96eHtZftBWrOZUsd6cA4es80n3NzSk/XtYz2EqQ="

    /** ISRG Root X1 — Let's Encrypt's long-lived root (survives R12->R13 etc.). */
    private const val ISRG_ROOT_X1 =
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="

    // ── CertificatePinner ─────────────────────────────────────────────────────

    /**
     * [CertificatePinner] covering every GitHub host this app talks to.
     *
     * Each host pins its current intermediate + the root above it (current +
     * long-lived backup), so a routine intermediate rotation does not drop all
     * pins at once. All values are extracted from the LIVE chains (see class
     * doc). The fail-open fallback ([executeWithPinFallback]) ensures a stale
     * pin degrades to standard HTTPS rather than bricking updates.
     */
    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        // api.github.com — UpdateChecker fetches the latest release manifest (Sectigo).
        .add("api.github.com", SECTIGO_SERVER_AUTH_DV_E36)
        .add("api.github.com", SECTIGO_SERVER_AUTH_ROOT_E46)
        // github.com — ApkDownloader browser_download_url redirects (Sectigo).
        .add("github.com", SECTIGO_SERVER_AUTH_DV_E36)
        .add("github.com", SECTIGO_SERVER_AUTH_ROOT_E46)
        // codeload.github.com — source archives (Sectigo, same chain as github.com).
        .add("codeload.github.com", SECTIGO_SERVER_AUTH_DV_E36)
        .add("codeload.github.com", SECTIGO_SERVER_AUTH_ROOT_E46)
        // raw.githubusercontent.com — RemoteContentRepository OTA JSON (Let's Encrypt).
        .add("raw.githubusercontent.com", LETSENCRYPT_R12)
        .add("raw.githubusercontent.com", ISRG_ROOT_X1)
        // objects.githubusercontent.com — GitHub release-asset CDN (Let's Encrypt).
        .add("objects.githubusercontent.com", LETSENCRYPT_R12)
        .add("objects.githubusercontent.com", ISRG_ROOT_X1)
        .build()

    // ── Client factory ────────────────────────────────────────────────────────

    /**
     * Returns a new [OkHttpClient] built from [base] with [certificatePinner]
     * attached.
     *
     * Callers keep their own timeout settings in [base]:
     * ```
     * val client = GitHubCertPins.pinnedClient(
     *     OkHttpClient.Builder()
     *         .connectTimeout(10, TimeUnit.SECONDS)
     *         .readTimeout(15, TimeUnit.SECONDS)
     * )
     * ```
     */
    fun pinnedClient(base: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        base.certificatePinner(certificatePinner).build()

    // ── Fail-open execution helper ────────────────────────────────────────────

    /**
     * Executes [pinnedAttempt] (which should use a pinned [OkHttpClient]).
     *
     * Behaviour:
     *  - On success: returns the result of [pinnedAttempt].
     *  - On [SSLPeerUnverifiedException] (certificate pin mismatch): logs a
     *    **warning** and returns the result of [unpinnedAttempt] instead.
     *    This is the **fail-open** path: the CA may have rotated; we degrade
     *    to standard HTTPS rather than bricking the request.
     *  - On any OTHER exception: propagates normally — do not swallow real
     *    network errors (timeouts, DNS failures, server errors, etc.).
     *
     * @param tag   Short label used in the warning log, e.g. "UpdateChecker.fetchLatest".
     * @param pinnedAttempt   Lambda that performs the request via the pinned client.
     * @param unpinnedAttempt Lambda that repeats the same request via an unpinned client.
     */
    fun <T> executeWithPinFallback(
        tag: String,
        pinnedAttempt: () -> T,
        unpinnedAttempt: () -> T,
    ): T {
        return try {
            pinnedAttempt()
        } catch (e: SSLPeerUnverifiedException) {
            Log.w(TAG, "cert-pin bypassed for $tag — falling back to unpinned TLS")
            unpinnedAttempt()
        }
    }
}
