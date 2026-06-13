package io.github.mayusi.calibratesoc.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

// ── URL allowlist ─────────────────────────────────────────────────────────────

/**
 * Pure-JVM tests for [ApkDownloader.isAllowedUrl].
 *
 * Security contract:
 *  - Only `https://` scheme is accepted.
 *  - Host must end with `.github.com` or `.githubusercontent.com`.
 *  - Anything else — http, file, content, arbitrary host — is rejected.
 *
 * [isAllowedUrl] lives in the companion object so it can be tested here
 * without an Android runtime context.
 */
class ApkDownloaderUrlTest {

    // ── Accepted URLs ─────────────────────────────────────────────────────────

    @Test
    fun `github release asset URL is accepted`() {
        val url = "https://objects.githubusercontent.com/github-production-release-asset-2e65be/" +
            "123456/CalibrateSoC-0.1.5-alpha.apk?X-Amz-Signature=abc123"
        assertThat(ApkDownloader.isAllowedUrl(url)).isTrue()
    }

    @Test
    fun `github com download URL is accepted`() {
        val url = "https://github.com/mayusi/Calibrate-SoC/releases/download/" +
            "v0.1.5-alpha/CalibrateSoC-0.1.5-alpha.apk"
        assertThat(ApkDownloader.isAllowedUrl(url)).isTrue()
    }

    @Test
    fun `api github com URL is accepted`() {
        // GitHub API redirects sometimes originate from api.github.com
        assertThat(ApkDownloader.isAllowedUrl("https://api.github.com/some/path")).isTrue()
    }

    @Test
    fun `raw githubusercontent com URL is accepted`() {
        assertThat(
            ApkDownloader.isAllowedUrl("https://raw.githubusercontent.com/user/repo/main/file.apk")
        ).isTrue()
    }

    // ── Rejected: wrong scheme ────────────────────────────────────────────────

    @Test
    fun `http github URL is rejected`() {
        assertThat(
            ApkDownloader.isAllowedUrl("http://objects.githubusercontent.com/file.apk")
        ).isFalse()
    }

    @Test
    fun `file scheme is rejected`() {
        assertThat(
            ApkDownloader.isAllowedUrl("file:///sdcard/malicious.apk")
        ).isFalse()
    }

    @Test
    fun `content scheme is rejected`() {
        assertThat(
            ApkDownloader.isAllowedUrl("content://com.example/file.apk")
        ).isFalse()
    }

    // ── Rejected: wrong host ──────────────────────────────────────────────────

    @Test
    fun `arbitrary HTTPS host is rejected`() {
        assertThat(
            ApkDownloader.isAllowedUrl("https://evil.example.com/CalibrateSoC.apk")
        ).isFalse()
    }

    @Test
    fun `URL that ends with github com but isn't a subdomain is rejected`() {
        // e.g. "notgithub.com" does NOT end with ".github.com"
        assertThat(
            ApkDownloader.isAllowedUrl("https://notgithub.com/file.apk")
        ).isFalse()
    }

    @Test
    fun `homograph lookalike domain is rejected`() {
        // Attacker registers "github.com.evil.org" — must be rejected.
        assertThat(
            ApkDownloader.isAllowedUrl("https://github.com.evil.org/file.apk")
        ).isFalse()
    }

    @Test
    fun `githubusercontent com lookalike is rejected`() {
        // "githubusercontent.com.attacker.io" must not match the suffix check.
        assertThat(
            ApkDownloader.isAllowedUrl("https://githubusercontent.com.attacker.io/file.apk")
        ).isFalse()
    }

    // ── Rejected: malformed input ─────────────────────────────────────────────

    @Test
    fun `empty string is rejected without crashing`() {
        assertThat(ApkDownloader.isAllowedUrl("")).isFalse()
    }

    @Test
    fun `blank string is rejected without crashing`() {
        assertThat(ApkDownloader.isAllowedUrl("   ")).isFalse()
    }

    @Test
    fun `javascript URI is rejected`() {
        assertThat(ApkDownloader.isAllowedUrl("javascript:alert(1)")).isFalse()
    }
}

/**
 * Pure-JVM tests for [UpdateChecker.isNewer] and the GitHub JSON models.
 *
 * isNewer rules:
 *   - Strips leading "v" from either argument.
 *   - Compares MAJOR.MINOR.PATCH as integers.
 *   - A release WITHOUT a pre-release suffix is newer than the same numeric
 *     triple WITH a suffix (standard semver: "1.0.0" > "1.0.0-alpha").
 *   - Any malformed input returns false (no crash).
 */
class UpdateCheckerIsNewerTest {

    // ── Basic numeric ordering ────────────────────────────────────────────────

    @Test
    fun `patch increment is newer`() {
        assertThat(UpdateChecker.isNewer("0.1.5-alpha", "0.1.4-alpha")).isTrue()
    }

    @Test
    fun `equal versions are not newer`() {
        assertThat(UpdateChecker.isNewer("0.1.4-alpha", "0.1.4-alpha")).isFalse()
    }

    @Test
    fun `older patch is not newer`() {
        assertThat(UpdateChecker.isNewer("0.1.4-alpha", "0.1.5-alpha")).isFalse()
    }

    @Test
    fun `minor increment is newer`() {
        assertThat(UpdateChecker.isNewer("0.2.0-alpha", "0.1.9-alpha")).isTrue()
    }

    @Test
    fun `major increment is newer`() {
        assertThat(UpdateChecker.isNewer("1.0.0", "0.9.9")).isTrue()
    }

    @Test
    fun `major regression is not newer`() {
        assertThat(UpdateChecker.isNewer("0.9.9", "1.0.0")).isFalse()
    }

    // ── Tag-style leading "v" ─────────────────────────────────────────────────

    @Test
    fun `tag with leading v is stripped and compared correctly`() {
        assertThat(UpdateChecker.isNewer("v0.1.5-alpha", "0.1.4-alpha")).isTrue()
        assertThat(UpdateChecker.isNewer("v0.1.4-alpha", "v0.1.4-alpha")).isFalse()
        assertThat(UpdateChecker.isNewer("v0.1.3-alpha", "v0.1.4-alpha")).isFalse()
    }

    // ── Pre-release suffix (semver ordering) ──────────────────────────────────

    @Test
    fun `release without suffix beats same triple with suffix`() {
        // "1.0.0" (no suffix) > "1.0.0-alpha" (has suffix)
        assertThat(UpdateChecker.isNewer("1.0.0", "1.0.0-alpha")).isTrue()
    }

    @Test
    fun `both have same suffix at same triple — equal, not newer`() {
        assertThat(UpdateChecker.isNewer("0.1.4-alpha", "0.1.4-alpha")).isFalse()
    }

    @Test
    fun `both have suffix but remote has higher triple`() {
        assertThat(UpdateChecker.isNewer("0.1.5-alpha", "0.1.4-alpha")).isTrue()
    }

    @Test
    fun `remote lacks suffix but current has higher triple`() {
        // "0.1.3" vs "0.1.4-alpha" — numeric triple wins; 0.1.3 < 0.1.4
        assertThat(UpdateChecker.isNewer("0.1.3", "0.1.4-alpha")).isFalse()
    }

    // ── Current is release, remote is pre-release at same triple ─────────────

    @Test
    fun `pre-release at same triple is not newer than release`() {
        // installed is already "1.0.0" (no suffix); remote "1.0.0-alpha" is older
        assertThat(UpdateChecker.isNewer("1.0.0-alpha", "1.0.0")).isFalse()
    }

    // ── Edge cases / malformed inputs ─────────────────────────────────────────

    @Test
    fun `empty remote version returns false without crashing`() {
        assertThat(UpdateChecker.isNewer("", "0.1.4-alpha")).isFalse()
    }

    @Test
    fun `empty current version returns false without crashing`() {
        assertThat(UpdateChecker.isNewer("0.1.5-alpha", "")).isFalse()
    }

    @Test
    fun `both empty returns false without crashing`() {
        assertThat(UpdateChecker.isNewer("", "")).isFalse()
    }

    @Test
    fun `non-numeric segments are treated as zero`() {
        // "0.x.5" — 'x' becomes 0, so this is "0.0.5" vs "0.1.4": not newer
        assertThat(UpdateChecker.isNewer("0.x.5", "0.1.4")).isFalse()
        // "1.x.0" → "1.0.0" vs "0.9.9" → newer on major
        assertThat(UpdateChecker.isNewer("1.x.0", "0.9.9")).isTrue()
    }
}

// ── JSON model parsing ────────────────────────────────────────────────────────

class GitHubModelsJsonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `parses a minimal release response`() {
        val raw = """
            {
              "tag_name": "v0.1.5-alpha",
              "name": "0.1.5-alpha",
              "body": "## 0.1.5-alpha\n- Fixed a thing\n- Added another thing",
              "prerelease": false,
              "draft": false,
              "assets": [
                {
                  "name": "CalibrateSoC-0.1.5-alpha.apk",
                  "browser_download_url": "https://github.com/mayusi/Calibrate-SoC/releases/download/v0.1.5-alpha/CalibrateSoC-0.1.5-alpha.apk",
                  "size": 12345678
                }
              ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GitHubRelease>(raw)
        assertThat(release.tag_name).isEqualTo("v0.1.5-alpha")
        assertThat(release.name).isEqualTo("0.1.5-alpha")
        assertThat(release.prerelease).isFalse()
        assertThat(release.draft).isFalse()
        assertThat(release.body).contains("Fixed a thing")
        assertThat(release.assets).hasSize(1)
    }

    @Test
    fun `extracts the apk asset name and url`() {
        val raw = """
            {
              "tag_name": "v0.1.5-alpha",
              "assets": [
                {
                  "name": "CalibrateSoC-0.1.5-alpha.apk",
                  "browser_download_url": "https://example.com/CalibrateSoC-0.1.5-alpha.apk",
                  "size": 7654321
                },
                {
                  "name": "source.tar.gz",
                  "browser_download_url": "https://example.com/source.tar.gz",
                  "size": 100
                }
              ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GitHubRelease>(raw)
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        assertThat(apkAsset).isNotNull()
        assertThat(apkAsset!!.name).isEqualTo("CalibrateSoC-0.1.5-alpha.apk")
        assertThat(apkAsset.browser_download_url).contains("CalibrateSoC-0.1.5-alpha.apk")
        assertThat(apkAsset.size).isEqualTo(7654321L)
    }

    @Test
    fun `ignores unknown keys in response`() {
        // The real GitHub API returns many more fields; we must not crash.
        val raw = """
            {
              "url": "https://api.github.com/repos/mayusi/Calibrate-SoC/releases/1",
              "id": 12345,
              "tag_name": "v0.1.5-alpha",
              "target_commitish": "main",
              "created_at": "2025-01-01T00:00:00Z",
              "published_at": "2025-01-01T00:00:00Z",
              "html_url": "https://github.com/mayusi/Calibrate-SoC/releases/tag/v0.1.5-alpha",
              "author": { "login": "mayusi", "id": 999 },
              "assets": []
            }
        """.trimIndent()

        // Should not throw even with many unexpected fields.
        val release = runCatching { json.decodeFromString<GitHubRelease>(raw) }.getOrNull()
        assertThat(release).isNotNull()
        assertThat(release!!.tag_name).isEqualTo("v0.1.5-alpha")
    }

    @Test
    fun `release with no assets has empty asset list`() {
        val raw = """{"tag_name": "v0.2.0", "assets": []}"""
        val release = json.decodeFromString<GitHubRelease>(raw)
        assertThat(release.assets).isEmpty()
        // No APK asset found — apkUrl would be null in UpdateChecker
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        assertThat(apkAsset).isNull()
    }

    @Test
    fun `release body null is handled gracefully`() {
        val raw = """{"tag_name": "v0.2.0"}"""
        val release = json.decodeFromString<GitHubRelease>(raw)
        assertThat(release.body).isNull()
        // UpdateChecker maps null body to empty string
        val notes = release.body ?: ""
        assertThat(notes).isEmpty()
    }
}
