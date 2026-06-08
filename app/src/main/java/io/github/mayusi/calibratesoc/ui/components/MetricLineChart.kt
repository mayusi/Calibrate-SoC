package io.github.mayusi.calibratesoc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

/**
 * Shared single-series Vico line chart. Extracted from the previously
 * duplicated private charts in BenchmarkScreen (ThrottleChart) and
 * StabilityScreen (FpsChart / ThermalChart). Renders only when there are
 * >=2 points; otherwise an empty Box of the same height (placeholder).
 */
@Composable
fun MetricLineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    heightDp: Int = 160,
) {
    if (points.size < 2) {
        Box(
            modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(MaterialTheme.colorScheme.surface),
        )
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        producer.runTransaction {
            lineSeries { series(points) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    )
}

/**
 * Shared two-series overlay line chart (replaces the private OverlayChart
 * in BenchmarkScreen). Each non-trivial series is drawn in one lineSeries
 * block. Renders only when at least one series has >=2 points.
 */
@Composable
fun MetricLineChartOverlay(
    seriesA: List<Float>,
    seriesB: List<Float>,
    modifier: Modifier = Modifier,
    heightDp: Int = 160,
) {
    if (seriesA.size < 2 && seriesB.size < 2) {
        Box(
            modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(MaterialTheme.colorScheme.surface),
        )
        return
    }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(seriesA, seriesB) {
        producer.runTransaction {
            lineSeries {
                if (seriesA.size >= 2) series(seriesA)
                if (seriesB.size >= 2) series(seriesB)
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(),
            bottomAxis = HorizontalAxis.rememberBottom(),
        ),
        modelProducer = producer,
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    )
}

/** Convenience wrapper: a titled SectionCard with a caption + single-series
 *  chart. Keeps the honesty caption directly beside the curve. */
@Composable
fun MetricLineChartCard(
    title: String,
    caption: String,
    points: List<Float>,
    modifier: Modifier = Modifier,
) {
    SectionCard(title, modifier) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricLineChart(points)
    }
}
