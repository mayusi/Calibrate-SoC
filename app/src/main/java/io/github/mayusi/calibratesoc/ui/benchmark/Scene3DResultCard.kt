package io.github.mayusi.calibratesoc.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.calibratesoc.data.benchmark.GpuSceneResult
import io.github.mayusi.calibratesoc.ui.components.KvRow
import io.github.mayusi.calibratesoc.ui.components.MetricLineChart
import io.github.mayusi.calibratesoc.ui.components.SectionCard
import io.github.mayusi.calibratesoc.ui.theme.Spacing

/**
 * Full result card for a SCENE_3D benchmark run.
 *
 * Displays all fields from [GpuSceneResult]:
 *   - Headline: avg FPS + stability% tier badge
 *   - Render config: tier/resolution, API, triangle count
 *   - Frame pacing: median FPS, 1% low, p99 frame time, consistency%
 *   - Sustained performance: per-loop FPS curve + stability%
 *   - Frame-time curve (downsampled)
 *   - Temps: peak CPU + GPU if available
 *   - Honesty caption (always visible — this is OUR benchmark)
 *
 * LEGAL: The HONESTY_CAPTION is always shown so the user clearly understands
 * this is Calibrate SoC's own test, not comparable to 3DMark / AnTuTu.
 */
@Composable
fun Scene3DResultCard(
    result: GpuSceneResult,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "GPU 3D — heavy scene result",
        icon = Icons.Outlined.Videocam,
        modifier = modifier,
    ) {
        // ── Headline ─────────────────────────────────────────────
        HeadlineFpsCard(result)

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // ── Render config ────────────────────────────────────────
        Text(
            "Render configuration",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KvRow(
            label = "Tier / resolution",
            value = "${result.tier.label}  (${result.renderWidthPx}×${result.renderHeightPx})",
            explainer = "Offscreen FBO — no display dependency.",
        )
        KvRow(
            label = "API",
            value = result.apiLabel,
            explainer = "GLES 3.0 preferred; 2.0 fallback if driver rejects 3.0.",
        )
        KvRow(
            label = "Triangles / frame",
            value = "%,d".format(result.trianglesPerFrame),
            explainer = "Approximate rendered triangle count each frame.",
        )
        KvRow(
            label = "Render passes",
            value = result.passCount.toString(),
            explainer = "Depth prepass + main lit pass (2) or forward-only (1).",
        )
        KvRow(
            label = "Duration",
            value = "%.1f s".format(result.totalDurationMs / 1000.0),
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // ── Frame pacing ─────────────────────────────────────────
        Text(
            "Frame pacing",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KvRow(
            label = "Avg FPS",
            value = "%.1f FPS".format(result.avgFps),
            explainer = "Mean over all post-warmup frames.",
        )
        KvRow(
            label = "Median FPS",
            value = "%.1f FPS".format(result.p50Fps),
            explainer = "Typical frame speed — less skewed by outliers than the mean.",
        )
        KvRow(
            label = "1% low FPS",
            value = "%.1f FPS".format(result.p1LowFps),
            explainer = "FPS you sustain 99% of the time — based on p99 slowest frame.",
        )
        KvRow(
            label = "p99 frame time",
            value = "%.2f ms".format(result.p99FrameMs),
            explainer = "The slowest 1% of frames — high = visible stutter.",
        )
        Column {
            KvRow(
                label = "Consistency",
                value = "%.0f%%".format(result.consistencyPct),
                explainer = "100% = perfectly even frame pacing.",
            )
            LinearProgressIndicator(
                progress = { (result.consistencyPct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
            )
        }

        // ── Sustained stability ──────────────────────────────────
        result.stabilityPct?.let { pct ->
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "Sustained stability",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                KvRow(
                    label = "Stability %",
                    value = "$pct%",
                    explainer = "Avg FPS of last 25% loops ÷ peak loop FPS. " +
                        "100% = no thermal throttle across loops.",
                )
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                )
            }
        }

        if (result.loopResults.size >= 2) {
            Text(
                "Per-loop avg FPS — flat = sustained, falling = throttling.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(
                points = result.loopResults.sortedBy { it.loopIndex }
                    .map { it.avgFps.toFloat() },
                heightDp = 130,
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // ── Frame-time curve ─────────────────────────────────────
        if (result.frameTimesMsDownsampled.size >= 2) {
            Text(
                "Per-frame time (ms) — flat = smooth, spikes = stutter.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLineChart(
                points = result.frameTimesMsDownsampled,
                heightDp = 130,
            )
        }

        // ── Temperatures ─────────────────────────────────────────
        if (result.peakCpuTempC != null || result.peakGpuTempC != null) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                "Temperatures",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.peakCpuTempC?.let {
                KvRow(
                    label = "Peak CPU temp",
                    value = "%.1f°C".format(it),
                    explainer = "Hottest CPU reading from concurrent telemetry.",
                )
            }
            result.peakGpuTempC?.let {
                KvRow(
                    label = "Peak GPU temp",
                    value = "%.1f°C".format(it),
                    explainer = "Hottest GPU reading (null if sensor unavailable).",
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        // ── Honesty caption — always shown ───────────────────────
        Text(
            GpuSceneResult.HONESTY_CAPTION,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─── Headline FPS badge ───────────────────────────────────────────────

@Composable
private fun HeadlineFpsCard(result: GpuSceneResult) {
    val stabilityColor = result.stabilityPct?.let { pct ->
        when {
            pct >= 90 -> Color(0xFF34D399) // green
            pct >= 70 -> Color(0xFFFBBF24) // amber
            else      -> MaterialTheme.colorScheme.error
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.group),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "%.1f".format(result.avgFps),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFA78BFA), // GPU purple
                    )
                    Text(
                        "avg FPS · ${result.tier.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.stabilityPct?.let { pct ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "$pct%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = stabilityColor ?: MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "stability",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "GPU 3D scene — our own benchmark. Compare your own runs only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
