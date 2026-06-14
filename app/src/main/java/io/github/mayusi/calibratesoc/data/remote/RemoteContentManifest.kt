package io.github.mayusi.calibratesoc.data.remote

import kotlinx.serialization.Serializable

/**
 * Tiny index file fetched first from `content/manifest.json`.
 * If its [version] matches the cached version we skip re-fetching the
 * data files, keeping the launch-refresh a no-op when nothing has changed.
 */
@Serializable
data class RemoteContentManifest(
    /** Monotonically-increasing integer bumped whenever any content file changes. */
    val version: Int,
    /** ISO-8601 timestamp for human readability in the repo. Not parsed by the app. */
    val updatedAt: String = "",
)
