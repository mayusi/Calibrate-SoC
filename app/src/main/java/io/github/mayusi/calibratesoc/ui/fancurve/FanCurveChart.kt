package io.github.mayusi.calibratesoc.ui.fancurve

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import io.github.mayusi.calibratesoc.data.fancurve.FanCurve

/**
 * A line chart of a [FanCurve]: temperature (°C) on the x-axis, fan duty (%) on
 * the y-axis. The INT_MAX sentinel point is NOT plotted at its literal x (that
 * would crush the visible range) — instead its duty is extended as a flat tail
 * a few degrees past the last real point so the "ceiling" behaviour is visible.
 *
 * Reuses the Vico stack the rest of the app uses ([CartesianChartModelProducer],
 * line layer, start/bottom axes) for visual consistency with the metric charts.
 */
@Composable
fun FanCurveChart(
    curve: FanCurve,
    modifier: Modifier = Modifier,
    heightDp: Int = 180,
) {
    val real = curve.points.filterNot { it.isSentinel }
    if (real.size < 2) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Add at least two points to preview the curve.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // x = temps, y = duties. Append a flat ceiling tail to visualize the catch-all.
    val sentinelDuty = curve.points.last().dutyPct
    val lastTemp = real.last().tempC
    val xs = real.map { it.tempC.toFloat() } + (lastTemp + CEILING_TAIL_C).toFloat()
    val ys = real.map { it.dutyPct.toFloat() } + sentinelDuty.toFloat()

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(curve) {
        producer.runTransaction {
            lineSeries { series(x = xs, y = ys) }
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

/** How many degrees past the last real point to extend the ceiling tail. */
private const val CEILING_TAIL_C = 15
