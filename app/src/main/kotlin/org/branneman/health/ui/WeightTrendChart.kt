package org.branneman.health.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.branneman.health.dashboard.TrendConfidence
import org.branneman.health.dashboard.TrendRange
import org.branneman.health.dashboard.WeightTrendData
import org.branneman.health.dashboard.WeightTrendPoint
import org.branneman.health.dashboard.filterToRange
import org.branneman.health.util.effectiveDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val rangeOrder = listOf(
    TrendRange.WEEK, TrendRange.MONTH, TrendRange.THREE_MONTHS,
    TrendRange.SIX_MONTHS, TrendRange.YEAR, TrendRange.ALL,
)

private val rangeLabels = mapOf(
    TrendRange.WEEK to "1W",
    TrendRange.MONTH to "1M",
    TrendRange.THREE_MONTHS to "3M",
    TrendRange.SIX_MONTHS to "6M",
    TrendRange.YEAR to "1Y",
    TrendRange.ALL to "ALL",
)

@Composable
fun WeightTrendChart(
    trend: WeightTrendData,
    selectedRange: TrendRange,
    goalWeightKg: Double?,
    onSelectRange: (TrendRange) -> Unit,
    today: LocalDate = effectiveDate(),
) {
    if (trend.confidence == TrendConfidence.NONE) {
        Text(
            text = "Log a few more weigh-ins to see your trend.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("trend-empty-message"),
        )
        return
    }

    val visiblePoints = filterToRange(trend.points, selectedRange, today)

    Column(modifier = Modifier.fillMaxWidth()) {
        WeightTrendCanvas(
            points = visiblePoints,
            dashed = trend.confidence == TrendConfidence.PARTIAL,
            goalWeightKg = goalWeightKg,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rangeOrder.filter { it in trend.availableRanges }.forEach { range ->
                val label = rangeLabels.getValue(range)
                FilterChip(
                    selected = range == selectedRange,
                    onClick = { onSelectRange(range) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("trend-range-$label"),
                )
            }
        }
    }
}

@Composable
private fun WeightTrendCanvas(
    points: List<WeightTrendPoint>,
    dashed: Boolean,
    goalWeightKg: Double?,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val goalColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .testTag("trend-chart-canvas"),
    ) {
        if (points.isEmpty()) return@Canvas

        // Honest, un-truncated axis: round the data's own min/max outward to "nice"
        // step values (standard d3-style tick rounding) so labels read as round kg
        // numbers, never the data's arbitrary decimal endpoints (math-model §3.3 /
        // dashboard UX chart conventions — never shrink the range to exaggerate movement).
        val allValues = points.flatMap { listOf(it.smoothedKg, it.rawKg) } + listOfNotNull(goalWeightKg)
        val rawMin = allValues.min()
        val rawMax = allValues.max()
        val rawRange = (rawMax - rawMin).takeIf { it > 0 } ?: 1.0
        val roughStep = rawRange / 3
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(roughStep)))
        val normalized = roughStep / magnitude
        val niceStep = magnitude * when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else              -> 10.0
        }
        val minKg = Math.floor(rawMin / niceStep) * niceStep
        val maxKg = Math.ceil(rawMax / niceStep) * niceStep
        val kgRange = (maxKg - minKg).takeIf { it > 0 } ?: 1.0
        val gridValues = generateSequence(minKg) { it + niceStep }.takeWhile { it <= maxKg + niceStep / 2 }.toList()

        // Reserve margins for axis labels so the plot itself doesn't touch the edges.
        val yAxisLabelWidth = 40.dp.toPx()
        val xAxisLabelHeight = 20.dp.toPx()
        val plotLeft = yAxisLabelWidth
        val plotRight = size.width
        val plotBottom = size.height - xAxisLabelHeight

        fun yFor(kg: Double): Float = (plotBottom * (1 - (kg - minKg) / kgRange)).toFloat()
        fun xFor(index: Int): Float =
            if (points.size == 1) (plotLeft + plotRight) / 2f
            else plotLeft + (plotRight - plotLeft) * (index.toFloat() / (points.size - 1))

        val textPaint = Paint().apply {
            color = labelColor
            textSize = 11.sp.toPx()
            isAntiAlias = true
        }

        // Y-axis: one gridline + rounded kg label per nice step.
        gridValues.forEach { kg ->
            val y = yFor(kg).coerceIn(textPaint.textSize, plotBottom)
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            textPaint.textAlign = Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(kg), 0f, y + textPaint.textSize / 3, textPaint,
            )
        }

        // X-axis: start / mid / end date labels of the visible window.
        val formatter = DateTimeFormatter.ofPattern("MMM d")
        val dateIndices = when {
            points.size <= 2 -> listOf(0, points.size - 1)
            else             -> listOf(0, points.size / 2, points.size - 1)
        }.distinct()
        textPaint.textAlign = Paint.Align.CENTER
        dateIndices.forEach { i ->
            drawContext.canvas.nativeCanvas.drawText(
                points[i].date.format(formatter), xFor(i), size.height, textPaint,
            )
        }

        goalWeightKg?.let { goal ->
            drawLine(
                color = goalColor,
                start = Offset(plotLeft, yFor(goal)),
                end = Offset(plotRight, yFor(goal)),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }

        points.forEachIndexed { i, point ->
            drawCircle(color = dotColor, radius = 4f, center = Offset(xFor(i), yFor(point.rawKg)))
        }

        val path = Path().apply {
            points.forEachIndexed { i, point ->
                val x = xFor(i)
                val y = yFor(point.smoothedKg)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 6f,
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(20f, 12f)) else null,
            ),
        )
    }
}
