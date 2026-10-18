package com.akane.inspiration.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.ImmutableList

/** Layout constants of the Health screen that are not spacing tokens. */
internal object HealthMetrics {
    /** From this content width the screen is two columns. */
    val TwoColumnMinWidth = 600.dp

    /** Height of one small chart of the charge curve. */
    val CurveRowHeight = 72.dp

    /** The curve's last row also carries the level axis labels. */
    val CurveRowHeightWithAxis = 92.dp

    val WearChartHeight = 168.dp
    val LineWidth = 2.dp
    val HairlineWidth = 1.dp
    val MarkerRadius = 5.dp
    val MarkerRing = 2.dp
    val KeySize = 10.dp
}

/**
 * Where an axis label of [labelSize] starts: [desired], kept within [min]..[end]. A label wider than that room
 * (a narrow window at a large font scale) starts at [min] and overflows [end], instead of throwing.
 */
internal fun labelStart(desired: Float, min: Float, end: Float, labelSize: Float): Float =
    desired.coerceIn(min, maxOf(min, end - labelSize))

/** A tick on an axis: where it sits and what it says. The caller formats the text (units, locale). */
@Immutable
internal data class AxisLabel(val value: Float, val text: String)

/**
 * A single-series line chart: [points] in data space, drawn inside [xRange] x [yRange], with hairline grid lines at
 * [yLabels] and optional [xLabels] under the plot. Diamonds mark [markers] (thermal throttling). Pure drawing, no
 * state; [description] is the whole chart for screen readers, since the canvas has no readable parts.
 */
@Composable
internal fun LineChart(
    points: ImmutableList<ChartPoint>,
    markers: ImmutableList<ChartPoint>,
    xRange: AxisRange,
    yRange: AxisRange,
    xLabels: ImmutableList<AxisLabel>,
    yLabels: ImmutableList<AxisLabel>,
    lineColor: Color,
    height: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val yLayouts = remember(yLabels, labelStyle, measurer) {
        yLabels.map { measurer.measure(it.text, labelStyle, maxLines = 1, softWrap = false) }
    }
    val xLayouts = remember(xLabels, labelStyle, measurer) {
        xLabels.map { measurer.measure(it.text, labelStyle, maxLines = 1, softWrap = false) }
    }
    val telemetry = MaterialTheme.telemetryColors
    val ringColor = MaterialTheme.colorScheme.surfaceContainer
    val gap = MaterialTheme.spacing.sm

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val markerRadius = HealthMetrics.MarkerRadius.toPx()
        val ring = HealthMetrics.MarkerRing.toPx()
        val inset = markerRadius + ring
        val leftGutter = (yLayouts.maxOfOrNull { it.size.width } ?: 0) + gap.toPx()
        val axisHeight = xLayouts.maxOfOrNull { it.size.height } ?: 0
        val bottomGutter = if (xLayouts.isEmpty()) 0f else axisHeight + gap.toPx()
        val plotLeft = leftGutter
        val plotTop = inset
        val plotWidth = (size.width - plotLeft - inset).coerceAtLeast(1f)
        val plotHeight = (size.height - bottomGutter - 2f * inset).coerceAtLeast(1f)
        val xSpan = (xRange.max - xRange.min).takeIf { it > 0f } ?: 1f
        val ySpan = (yRange.max - yRange.min).takeIf { it > 0f } ?: 1f

        fun toX(x: Float) = plotLeft + (x - xRange.min) / xSpan * plotWidth
        fun toY(y: Float) = plotTop + plotHeight - (y - yRange.min) / ySpan * plotHeight

        val hairline = HealthMetrics.HairlineWidth.toPx()
        yLabels.forEachIndexed { i, label ->
            val y = toY(label.value)
            drawLine(telemetry.chartGrid, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), hairline)
            val layout = yLayouts[i]
            val top = labelStart(y - layout.size.height / 2f, 0f, size.height, layout.size.height.toFloat())
            drawText(layout, telemetry.chartAxisLabel, Offset(plotLeft - gap.toPx() - layout.size.width, top))
        }
        xLabels.forEachIndexed { i, label ->
            val layout = xLayouts[i]
            val left = labelStart(toX(label.value) - layout.size.width / 2f, plotLeft, size.width, layout.size.width.toFloat())
            drawText(layout, telemetry.chartAxisLabel, Offset(left, size.height - axisHeight))
        }

        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(toX(p.x), toY(p.y)) else path.lineTo(toX(p.x), toY(p.y))
            }
            drawPath(
                path,
                lineColor,
                style = Stroke(HealthMetrics.LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        for (marker in markers) {
            drawDiamond(Offset(toX(marker.x), toY(marker.y)), markerRadius, ring, telemetry.critical, ringColor)
        }
        points.lastOrNull()?.let { last ->
            val center = Offset(toX(last.x), toY(last.y))
            drawCircle(ringColor, markerRadius * END_DOT_SCALE + ring, center)
            drawCircle(lineColor, markerRadius * END_DOT_SCALE, center)
        }
    }
}

/** The throttle marker's shape (a diamond), so colour is never the only thing that says "throttled". */
private fun DrawScope.drawDiamond(center: Offset, radius: Float, ring: Float, color: Color, ringColor: Color) {
    fun diamond(r: Float) = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r, center.y)
        lineTo(center.x, center.y + r)
        lineTo(center.x - r, center.y)
        close()
    }
    drawPath(diamond(radius + ring), ringColor)
    drawPath(diamond(radius), color)
}

/** The legend key for [LineChart] markers: the same diamond, at legend size. */
@Composable
internal fun ThrottleKey(modifier: Modifier = Modifier) {
    val color = MaterialTheme.telemetryColors.critical
    Canvas(modifier = modifier.size(HealthMetrics.KeySize)) {
        drawDiamond(center, size.minDimension / 2f, 0f, color, color)
    }
}

private const val END_DOT_SCALE = 0.7f
