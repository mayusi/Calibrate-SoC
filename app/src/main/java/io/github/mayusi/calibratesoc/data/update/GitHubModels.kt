package io.github.mayusi.calibratesoc.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal subset of the GitHub Releases API response.
 * https://docs.github.com/en/rest/releases/releases#get-the-latest-release
 *
 * [ignoreUnknownKeys] is set on the injected [Json] instance (AppModule),
 * so any fields not listed here are silently dropped.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name")  val tag_name: String,
    @SerialName("name")      val name: String? = null,
    @SerialName("body")      val body: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft")     val draft: Boolean = false,
    @SerialName("assets")    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("name")                 val name: String,
    @SerialName("browser_download_url") val browser_download_url: String,
    @SerialName("size")                 val size: Long = 0,
)

/**
 * Processed update information ready for the UI.
 *
 * @param versionName  Tag with the leading "v" stripped (e.g. "0.1.5-alpha").
 * @param tag          Raw tag string (e.g. "v0.1.5-alpha").
 * @param notes        Release body markdown — rendered by [ChangelogText].
 * @param apkUrl       Direct browser_download_url of the .apk asset, or null
 *                     if the release has no APK (rare; triggers GitHub fallback).
 * @param apkSize      Byte size of the APK asset (0 when apkUrl is null).
 * @param isNewer      True when [versionName] is newer than the installed build.
 */
data class UpdateInfo(
    val versionName: String,
    val tag: String,
    val notes: String,
    val apkUrl: String?,
    val apkSize: Long,
    val isNewer: Boolean,
)
