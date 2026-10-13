package com.fakeglucosense.companion.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.fakeglucosense.companion.data.settings.DisplayUnit
import com.fakeglucosense.companion.domain.GlucoseBand
import com.fakeglucosense.companion.domain.bandOf
import com.fakeglucosense.companion.ui.format.GlucoseFormat
import com.fakeglucosense.companion.ui.theme.bandColor
import com.fakeglucosense.core.analysis.TrendCalculator
import com.fakeglucosense.core.model.AlertThresholds
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.TimeRange

/**
 * Glucose over time with the target band shaded and threshold lines dashed. A gap longer than the
 * trend window breaks the line, so missing data is not drawn as a smooth transition.
 */
@Composable
fun GlucoseChart(
    points: List<GlucoseReading>,
    thresholds: AlertThresholds,
    range: TimeRange,
    nowMillis: Long,
    unit: DisplayUnit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val (yMin, yMax) = remember(points, thresholds) { ChartGeometry.yDomain(points, thresholds) }
    val windowStart = nowMillis - range.millis

    Canvas(modifier = modifier.fillMaxWidth().height(230.dp)) {
        val geometry = ChartGeometry(windowStart, nowMillis, yMin, yMax)
        val axisWidth = 44.dp.toPx()
        val footer = 18.dp.toPx()
        val plotLeft = axisWidth
        val plotWidth = size.width - axisWidth - 6.dp.toPx()
        val plotHeight = size.height - footer

        fun px(fraction: Float) = plotLeft + fraction * plotWidth
        fun py(mgdl: Double) = geometry.fractionY(mgdl) * plotHeight

        // Target band between the low and high thresholds.
        val bandTop = py(thresholds.highMgdl.toDouble())
        val bandBottom = py(thresholds.lowMgdl.toDouble())
        drawRect(
            color = bandColor(GlucoseBand.IN_RANGE).copy(alpha = 0.12f),
            topLeft = Offset(plotLeft, bandTop),
            size = Size(plotWidth, bandBottom - bandTop),
        )

        // Dashed threshold lines with value labels on the axis.
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        for (value in listOf(thresholds.lowMgdl, thresholds.highMgdl)) {
            val y = py(value.toDouble())
            drawLine(
                color = labelColor.copy(alpha = 0.6f),
                start = Offset(plotLeft, y),
                end = Offset(plotLeft + plotWidth, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
            drawText(
                textMeasurer = textMeasurer,
                text = GlucoseFormat.number(value.toDouble(), unit),
                topLeft = Offset(0f, y - 7.dp.toPx()),
                style = labelStyle.copy(color = labelColor),
            )
        }

        // Clipped to the window, in time order. A gap longer than the trend window starts a new segment.
        val inWindow = points.filter { it.epochMillis >= windowStart && it.epochMillis <= nowMillis }
        val path = Path()
        var previous: GlucoseReading? = null
        for (reading in inWindow) {
            val x = px(geometry.fractionX(reading.epochMillis))
            val y = py(reading.mgdl.toDouble())
            val gap = previous?.let { reading.epochMillis - it.epochMillis > TrendCalculator.WINDOW_MILLIS } ?: true
            if (gap) path.moveTo(x, y) else path.lineTo(x, y)
            previous = reading
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // Points coloured by band. The most recent one is larger.
        for (reading in inWindow) {
            drawCircle(
                color = bandColor(thresholds.bandOf(reading.mgdl)),
                radius = 2.5.dp.toPx(),
                center = Offset(px(geometry.fractionX(reading.epochMillis)), py(reading.mgdl.toDouble())),
            )
        }
        inWindow.lastOrNull()?.let { latest ->
            drawCircle(
                color = bandColor(thresholds.bandOf(latest.mgdl)),
                radius = 5.dp.toPx(),
                center = Offset(px(geometry.fractionX(latest.epochMillis)), py(latest.mgdl.toDouble())),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // Time labels at the start, middle, and end of the window.
        val footerY = plotHeight + 3.dp.toPx()
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val at = windowStart + ((nowMillis - windowStart) * fraction).toLong()
            val label = GlucoseFormat.clock(at)
            val measured = textMeasurer.measure(label, labelStyle.copy(color = labelColor))
            val x = (px(fraction) - measured.size.width / 2f).coerceIn(plotLeft, size.width - measured.size.width)
            drawText(measured, topLeft = Offset(x, footerY))
        }
    }
}
