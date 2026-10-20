package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import com.akane.inspiration.designsystem.chart.draw.AxisStyle
import com.akane.inspiration.designsystem.chart.draw.SELECTION_DIM_ALPHA
import com.akane.inspiration.designsystem.chart.draw.TimeTick
import com.akane.inspiration.designsystem.chart.draw.drawTimeAxis
import com.akane.inspiration.designsystem.chart.math.TimeScale
import com.akane.inspiration.designsystem.chart.math.ValueScale
import com.akane.inspiration.designsystem.chart.math.contains
import com.akane.inspiration.designsystem.chart.math.decimateMinMax
import com.akane.inspiration.designsystem.chart.math.niceTimeTickSet
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.ZoneId
import java.util.Locale

/** Colors the chart draws with, resolved from the theme tokens once per composition. */
@Immutable
internal data class ScrubPalette(
    val batteryLine: Color,
    val temperatureLine: Color,
    val critical: Color,
    val grid: Color,
    val axisLabel: Color,
    val screenOn: Color,
    val deepSleep: Color,
    val awake: Color,
    val charging: Color,
    val cursor: Color,
    val accent: Color,
    val surface: Color,
) {
    fun laneColor(kind: LaneKind): Color =
        when (kind) {
            LaneKind.SCREEN_ON -> screenOn
            LaneKind.DEEP_SLEEP -> deepSleep
            LaneKind.AWAKE -> awake
            LaneKind.CHARGING -> charging
        }
}

/** Value-axis labels, measured once per data/style (their width sets the gutters). */
internal class ValueAxisLabels(
    val battery: List<TextLayoutResult>,
    val temperature: List<TextLayoutResult>,
    val maxLeftWidthPx: Int,
    val maxRightWidthPx: Int,
    val labelHeightPx: Int,
)

internal const val BATTERY_LABEL_COUNT = 3

/** The battery value axis, bottom to top: one label per gridline. */
internal val BATTERY_AXIS_PERCENTS: List<Int> = listOf(0, 50, 100)

/**
 * Measures the already formatted value-axis texts: [battery] for [BATTERY_AXIS_PERCENTS] and [temperature] for the
 * temperature axis (empty without one), both bottom to top. The caller formats them like the readouts.
 */
internal fun measureValueAxisLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    battery: List<String>,
    temperature: List<String>,
): ValueAxisLabels {
    fun measure(text: String) = measurer.measure(text, style, maxLines = 1, softWrap = false)
    val batteryLayouts = battery.map(::measure)
    val temp = temperature.map(::measure)
    return ValueAxisLabels(
        battery = batteryLayouts,
        temperature = temp,
        maxLeftWidthPx = batteryLayouts.maxOf { it.size.width },
        maxRightWidthPx = temp.maxOfOrNull { it.size.width } ?: 0,
        labelHeightPx = batteryLayouts.maxOf { it.size.height },
    )
}

/**
 * Inputs of the cached draw layer. Instances are `remember`ed on their parts, so an unchanged chart
 * keeps the same instance and the `drawWithCache` block is not rebuilt on parent recomposition.
 */
internal class ScrubRenderConfig(
    val data: ScrubChartData,
    val palette: ScrubPalette,
    val labels: ValueAxisLabels,
    val temperatureAxis: ValueAxis?,
    val gutters: Gutters,
    val gutterGap: Dp,
    val laneGap: Dp,
    val tickGap: Dp,
    val tickLabelStyle: TextStyle,
    val textMeasurer: TextMeasurer,
    val zone: ZoneId,
    val locale: Locale,
)

/** The state the draw lambda reads per frame, besides [ScrubInteractionState]. */
internal class ScrubDrawInputs(
    val selection: State<TimeRange?>,
    val externalCursor: State<() -> Long?>,
    val fixedCursor: State<Long?>,
)

/** Builds every path, decimated series, tick layout and primitive array. Runs in `drawWithCache`, never per frame. */
internal fun CacheDrawScope.buildScrubRenderer(c: ScrubRenderConfig): ScrubRenderer {
    val plotHeight = ScrubChartMetrics.PlotHeight.toPx()
    val laneHeight = ScrubChartMetrics.LaneHeight.toPx()
    val laneGap = c.laneGap.toPx()
    // The top inset also holds the selection handle (centered on it), so it must clear the handle's ring.
    val inset = (maxOf(ScrubChartMetrics.MarkerRadius, ScrubChartMetrics.HandleRadius) + ScrubChartMetrics.MarkerRing).toPx()
    val innerHeight = plotHeight - 2f * inset
    val plotWidth = (size.width - c.gutters.leftPx - c.gutters.rightPx).coerceAtLeast(1f)
    val scale = TimeScale(c.data.window, plotWidth)
    val batteryScale = ValueScale(0f, 100f, innerHeight)
    val temperatureScale = c.temperatureAxis?.let { ValueScale(it.min, it.max, innerHeight) }
    val buckets = (plotWidth / 2f).toInt().coerceAtLeast(1)

    val battery =
        c.data.series
            .firstOrNull { it.kind == SeriesKind.BATTERY_PERCENT }
            ?.points
            .orEmpty()
    val temperature =
        c.data.series
            .firstOrNull { it.kind == SeriesKind.TEMPERATURE_C }
            ?.points
            .orEmpty()

    // A series with one point is stroked as a dot this wide (the line stroke's width), so it stays visible.
    val singlePointDotRadius = ScrubChartMetrics.LineWidth.toPx()
    val temperatureNormal = Path()
    val temperatureCritical = Path()
    if (temperatureScale != null) {
        val runs = splitAtThreshold(decimateMinMax(temperature, scale, buckets), CRITICAL_TEMPERATURE_C)
        for (run in runs) {
            val target = if (run.critical) temperatureCritical else temperatureNormal
            appendLine(target, run.points, scale, temperatureScale, inset, singlePointDotRadius)
        }
    }
    val batteryPath =
        Path().also {
            appendLine(it, decimateMinMax(battery, scale, buckets), scale, batteryScale, inset, singlePointDotRadius)
        }

    val laneKinds = LaneKind.entries
    val laneTop = FloatArray(laneKinds.size) { plotHeight + laneGap * (it + 1) + laneHeight * it }
    val lanesBottom = plotHeight + (laneGap + laneHeight) * laneKinds.size
    val radius = ScrubChartMetrics.LaneCornerRadius.toPx()
    val minLaneWidth = ScrubChartMetrics.LaneMinWidth.toPx()
    val tracks = Path()
    val lanePaths = Array(laneKinds.size) { Path() }
    for (i in laneKinds.indices) {
        tracks.addRoundRect(RoundRect(0f, laneTop[i], plotWidth, laneTop[i] + laneHeight, CornerRadius(radius)))
    }
    for (lane in c.data.lanes) {
        val i = lane.kind.ordinal
        for (segment in lane.segments) {
            if (!laneSegmentInWindow(segment, c.data.window)) continue
            val x0 =scale.toX(segment.startMs).coerceIn(0f, plotWidth)
            val x1 = maxOf(scale.toX(segment.endMs).coerceIn(0f, plotWidth), x0 + minLaneWidth).coerceAtMost(plotWidth)
            if (x1 <= x0) continue
            val corner = minOf(radius, (x1 - x0) / 2f)
            lanePaths[i].addRoundRect(RoundRect(x0, laneTop[i], x1, laneTop[i] + laneHeight, CornerRadius(corner)))
        }
    }

    val sampleLabel = if (c.data.window.usesDateTicks()) "00 May" else "00:00"
    val sampleWidth =
        c.textMeasurer
            .measure(sampleLabel, c.tickLabelStyle, maxLines = 1, softWrap = false)
            .size.width
    val maxTicks = (plotWidth / (sampleWidth + c.tickGap.toPx())).toInt().coerceAtLeast(2)
    val tickSet = niceTimeTickSet(c.data.window, maxTicks, c.zone)
    val ticks =
        tickSet.ticksMs.map { tMs ->
            TimeTick(
                tMs,
                c.textMeasurer.measure(
                    formatTickLabel(tMs, c.zone, c.data.window, tickSet.dayAligned, c.locale),
                    c.tickLabelStyle,
                    maxLines = 1,
                    softWrap = false,
                ),
            )
        }

    return ScrubRenderer(
        palette = c.palette,
        labels = c.labels,
        scale = scale,
        plotLeft = c.gutters.leftPx,
        plotWidth = plotWidth,
        plotHeight = plotHeight,
        lanesBottom = lanesBottom,
        axisTop = lanesBottom + laneGap,
        inset = inset,
        gutterGap = c.gutterGap.toPx(),
        gridY = FloatArray(BATTERY_LABEL_COUNT) { inset + innerHeight * (1f - it / (BATTERY_LABEL_COUNT - 1f)) },
        batteryPath = batteryPath,
        temperatureNormal = temperatureNormal,
        temperatureCritical = temperatureCritical,
        tracks = tracks,
        lanePaths = lanePaths,
        laneColors = Array(laneKinds.size) { c.palette.laneColor(laneKinds[it]) },
        ticks = ticks,
        axisStyle =
            AxisStyle(
                tickColor = c.palette.axisLabel.copy(alpha = TICK_ALPHA),
                labelColor = c.palette.axisLabel,
                tickLengthPx = ScrubChartMetrics.TickLength.toPx(),
                strokePx = ScrubChartMetrics.HairlineWidth.toPx(),
                labelGapPx = c.laneGap.toPx(),
            ),
        lineStroke = Stroke(ScrubChartMetrics.LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        hairline = ScrubChartMetrics.HairlineWidth.toPx(),
        edgeWidth = ScrubChartMetrics.EdgeWidth.toPx(),
        cursorWidth = ScrubChartMetrics.CursorWidth.toPx(),
        handleRadius = ScrubChartMetrics.HandleRadius.toPx(),
        markerRadius = ScrubChartMetrics.MarkerRadius.toPx(),
        markerRing = ScrubChartMetrics.MarkerRing.toPx(),
        batteryScale = batteryScale,
        temperatureScale = temperatureScale,
        batteryTimes = LongArray(battery.size) { battery[it].tMs },
        batteryValues = FloatArray(battery.size) { battery[it].value },
        temperatureTimes = LongArray(temperature.size) { temperature[it].tMs },
        temperatureValues = FloatArray(temperature.size) { temperature[it].value },
    )
}

private const val TICK_ALPHA = 0.5f

private fun appendLine(
    path: Path,
    points: List<TimePoint>,
    scale: TimeScale,
    values: ValueScale,
    inset: Float,
    dotRadius: Float,
) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        // A lone point has no segment to stroke; add a tiny circle so the stroke paints a solid dot
        // (radius = stroke width, the same as the stroke's own reach either side of the circle).
        val center = Offset(scale.toX(points[0].tMs), inset + values.toY(points[0].value))
        path.addOval(Rect(center, dotRadius / 2f))
        return
    }
    path.moveTo(scale.toX(points[0].tMs), inset + values.toY(points[0].value))
    for (i in 1 until points.size) {
        path.lineTo(scale.toX(points[i].tMs), inset + values.toY(points[i].value))
    }
}

/**
 * Everything precomputed for drawing. The `draw*` members allocate nothing: they only replay cached
 * [Path]s, text layouts and primitive arrays, and read the few primitive snapshot states of
 * [ScrubInteractionState] / [ScrubDrawInputs].
 */
internal class ScrubRenderer(
    private val palette: ScrubPalette,
    private val labels: ValueAxisLabels,
    private val scale: TimeScale,
    private val plotLeft: Float,
    private val plotWidth: Float,
    private val plotHeight: Float,
    private val lanesBottom: Float,
    private val axisTop: Float,
    private val inset: Float,
    private val gutterGap: Float,
    private val gridY: FloatArray,
    private val batteryPath: Path,
    private val temperatureNormal: Path,
    private val temperatureCritical: Path,
    private val tracks: Path,
    private val lanePaths: Array<Path>,
    private val laneColors: Array<Color>,
    private val ticks: List<TimeTick>,
    private val axisStyle: AxisStyle,
    private val lineStroke: Stroke,
    private val hairline: Float,
    private val edgeWidth: Float,
    private val cursorWidth: Float,
    private val handleRadius: Float,
    private val markerRadius: Float,
    private val markerRing: Float,
    private val batteryScale: ValueScale,
    private val temperatureScale: ValueScale?,
    private val batteryTimes: LongArray,
    private val batteryValues: FloatArray,
    private val temperatureTimes: LongArray,
    private val temperatureValues: FloatArray,
) {
    /** The data-only part (labels, grid, series, lanes, axis). It reads no snapshot state. */
    fun drawStatic(scope: DrawScope) {
        scope.drawValueLabels()
        scope.translate(left = plotLeft, top = 0f) { drawPlot() }
    }

    /** The per-frame part (selection and cursor), drawn over [drawStatic]. */
    fun drawOverlay(
        scope: DrawScope,
        state: ScrubInteractionState,
        inputs: ScrubDrawInputs,
    ) {
        scope.translate(left = plotLeft, top = 0f) {
            drawSelection(state, inputs)
            drawCursor(state, inputs)
        }
    }

    private fun DrawScope.drawValueLabels() {
        val maxTop = plotHeight - labels.labelHeightPx
        for (i in 0 until BATTERY_LABEL_COUNT) {
            val layout = labels.battery[i]
            val y = (gridY[i] - layout.size.height / 2f).coerceIn(0f, maxTop)
            drawText(layout, palette.axisLabel, Offset(plotLeft - gutterGap - layout.size.width, y))
        }
        for (i in labels.temperature.indices) {
            val layout = labels.temperature[i]
            val y = (gridY[i] - layout.size.height / 2f).coerceIn(0f, maxTop)
            drawText(layout, palette.axisLabel, Offset(plotLeft + plotWidth + gutterGap, y))
        }
    }

    private fun DrawScope.drawPlot() {
        for (i in 0 until BATTERY_LABEL_COUNT) {
            drawLine(palette.grid, Offset(0f, gridY[i]), Offset(plotWidth, gridY[i]), hairline)
        }
        clipRect(0f, 0f, plotWidth, plotHeight) {
            drawPath(temperatureNormal, palette.temperatureLine, style = lineStroke)
            drawPath(temperatureCritical, palette.critical, style = lineStroke)
            drawPath(batteryPath, palette.batteryLine, style = lineStroke)
        }
        drawPath(tracks, palette.grid)
        for (i in lanePaths.indices) drawPath(lanePaths[i], laneColors[i])
        translate(left = 0f, top = axisTop) { drawTimeAxis(ticks, scale, axisStyle) }
    }

    private fun DrawScope.drawSelection(
        state: ScrubInteractionState,
        inputs: ScrubDrawInputs,
    ) {
        val previewStart = state.previewStartMs.longValue
        val selection = inputs.selection.value
        val startMs: Long
        val endMs: Long
        val progress: Float
        if (previewStart != NO_TIME) {
            startMs = previewStart
            endMs = state.previewEndMs.longValue
            progress = 1f
        } else if (selection != null) {
            startMs = selection.startMs
            endMs = selection.endMs
            progress = state.dimProgress.floatValue
        } else {
            return
        }
        val window = scale.window
        // A selection the window has slid past (a day-old slice) has nothing here to dim or grab.
        if (endMs <= window.startMs || startMs >= window.endMs) return
        val xs = scale.toX(startMs).coerceIn(0f, plotWidth)
        val xe = scale.toX(endMs).coerceIn(0f, plotWidth)
        if (progress > 0f) {
            val scrim = palette.surface.copy(alpha = SELECTION_DIM_ALPHA * progress)
            drawRect(scrim, Offset.Zero, Size(xs, lanesBottom))
            drawRect(scrim, Offset(xe, 0f), Size(plotWidth - xe, lanesBottom))
        }
        // Only an edge inside the window gets a handle: a clamped one would sit where the gesture reducer cannot grab it.
        if (startMs in window) drawEdge(xs)
        if (endMs in window) drawEdge(xe)
    }

    private fun DrawScope.drawEdge(x: Float) {
        // The handle caps the top end of the edge line, in the plot's top inset, clear of the series lines.
        drawLine(palette.accent, Offset(x, inset), Offset(x, lanesBottom), edgeWidth)
        val center = Offset(x, inset)
        drawCircle(palette.surface, handleRadius + markerRing, center)
        drawCircle(palette.accent, handleRadius, center)
    }

    private fun DrawScope.drawCursor(
        state: ScrubInteractionState,
        inputs: ScrubDrawInputs,
    ) {
        val local = state.cursorMs.longValue
        val cursor: Long =
            if (local != NO_TIME) {
                local
            } else {
                // The external lambda's Long? is boxed by its signature; it is only invoked while a caller drives the cursor.
                inputs.fixedCursor.value ?: inputs.externalCursor.value() ?: return
            }
        val window = scale.window
        if (cursor < window.startMs || cursor > window.endMs) return
        val x = scale.toX(cursor)
        drawLine(palette.cursor, Offset(x, 0f), Offset(x, lanesBottom), cursorWidth)
        val battery = interpolateAt(batteryTimes, batteryValues, cursor)
        if (!battery.isNaN()) drawMarker(x, inset + batteryScale.toY(battery), palette.batteryLine)
        val temperatureScale = temperatureScale
        if (temperatureScale != null) {
            val temperature = interpolateAt(temperatureTimes, temperatureValues, cursor)
            if (!temperature.isNaN()) {
                val color = if (temperature >= CRITICAL_TEMPERATURE_C) palette.critical else palette.temperatureLine
                drawMarker(x, inset + temperatureScale.toY(temperature), color)
            }
        }
    }

    private fun DrawScope.drawMarker(
        x: Float,
        y: Float,
        color: Color,
    ) {
        val center = Offset(x, y)
        drawCircle(palette.surface, markerRadius + markerRing, center)
        drawCircle(color, markerRadius, center)
    }
}
