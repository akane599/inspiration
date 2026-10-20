package com.akane.inspiration.designsystem.chart.hypnogram

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.draw.AxisStyle
import com.akane.inspiration.designsystem.chart.draw.SELECTION_DIM_ALPHA
import com.akane.inspiration.designsystem.chart.draw.TimeTick
import com.akane.inspiration.designsystem.chart.draw.drawTimeAxis
import com.akane.inspiration.designsystem.chart.math.TimeScale
import com.akane.inspiration.designsystem.chart.math.formatClock
import com.akane.inspiration.designsystem.chart.math.formatTickLabel
import com.akane.inspiration.designsystem.chart.math.niceTimeTickSet
import com.akane.inspiration.designsystem.chart.math.overlaps
import com.akane.inspiration.designsystem.chart.math.showsDates
import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.chart.model.labelRes
import com.akane.inspiration.designsystem.component.format.rememberSystemZoneId
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.designsystem.theme.withTabularFigures
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

private const val MAINTENANCE_ALPHA_DARK = 0.7f
private const val MAINTENANCE_ALPHA_LIGHT = 0.85f
private const val GAP_HATCH_ALPHA = 0.45f
private const val CONNECTOR_ALPHA = 0.5f

private val PulseZone = 12.dp
private val PulseWidth = 2.dp
private val PulseHeight = 8.dp
private val PulseGap = 1.dp
private val BarRadius = 4.dp
private val BarMaxHeight = 22.dp
private val MinRowHeight = 24.dp
private val ColumnGap = 8.dp

/** Space between the plot's right edge and the totals column, so a wide total never touches a bar. */
private val TotalsGutter = 16.dp
private val TickLength = 4.dp
private val TickLabelGap = 2.dp
private val MinTickSpacing = 16.dp
private val HatchSpacing = 6.dp
private val HairStroke = 1.dp
private val CursorStroke = 1.5.dp
private val EdgeStroke = 2.dp
private const val BAR_FRACTION = 0.62f
private const val LABEL_COLUMN_FRACTION = 0.32f
private const val DURATION_COLUMN_FRACTION = 0.28f

/**
 * Lane labels, totals and tick labels follow the system font scale only up to this factor. Past it the label
 * columns would take the plot's width (at 360 dp and 1.5x or 2x they otherwise clip or crowd the bars); the
 * screen around the chart still scales fully.
 */
private const val MAX_CHART_FONT_SCALE = 1.2f
private const val MIN_VISIBLE_PX = 2f

/**
 * Sleep-stage hypnogram: one row per [HypnogramStage] (Awake on top, Deep idle at the bottom), segments
 * as rounded bars joined by thin vertical step connectors, wake pulses as ticks above the Awake row,
 * and a per-stage total at the end of each row. Time with no segment is drawn as a hatched "no data"
 * band, never interpolated. Display only: [selection] dims everything outside it and [cursorMs] draws a
 * cursor line, both read in the draw phase so a scrubbing parent never recomposes or rebuilds this chart.
 * Assumes left-to-right layout.
 */
@Composable
fun SleepHypnogram(
    data: HypnogramData,
    modifier: Modifier = Modifier,
    selection: TimeRange? = null,
    cursorMs: () -> Long? = { null },
    zone: ZoneId = rememberSystemZoneId(),
) {
    val density = LocalDensity.current
    val textDensity = remember(density) { Density(density.density, minOf(density.fontScale, MAX_CHART_FONT_SCALE)) }
    val labelStyle = MaterialTheme.typography.labelSmall.withTabularFigures()
    val minHeight =
        with(textDensity) {
            val lineHeight = if (labelStyle.lineHeight.isSp) labelStyle.lineHeight.toDp() else 16.dp
            val rowHeight = maxOf(MinRowHeight, lineHeight + ColumnGap)
            PulseZone + rowHeight * HypnogramStage.entries.size + TickLength + TickLabelGap + lineHeight
        }

    if (data.segments.isEmpty() || data.window.durationMs <= 0L) {
        Box(modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.chart_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val resources = LocalResources.current
    val telemetry = MaterialTheme.telemetryColors
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val palette =
        remember(telemetry, scheme.background, scheme.surfaceContainer, scheme.onSurface, scheme.primary) {
            val maintenanceAlpha = if (scheme.background.luminance() < 0.5f) MAINTENANCE_ALPHA_DARK else MAINTENANCE_ALPHA_LIGHT
            HypnogramPalette(
                stage =
                    arrayOf(
                        telemetry.awake,
                        telemetry.awake.copy(alpha = maintenanceAlpha),
                        telemetry.lightSleep,
                        telemetry.deepSleep,
                    ),
                pulse = telemetry.critical,
                grid = telemetry.chartGrid,
                connector = telemetry.chartAxisLabel.copy(alpha = CONNECTOR_ALPHA),
                gapHatch = telemetry.chartAxisLabel.copy(alpha = GAP_HATCH_ALPHA),
                label = telemetry.chartAxisLabel,
                dim = scheme.surfaceContainer.copy(alpha = SELECTION_DIM_ALPHA),
                cursor = scheme.onSurface,
                edge = scheme.primary,
            )
        }
    val stageNames = remember(resources) { HypnogramStage.entries.map { resources.getString(it.labelRes) } }
    val durationsMs = remember(data) { stageDurationsMs(data) }
    val format = rememberTelemetryFormat()
    // The composition's locale, the one rememberTelemetryFormat uses, so axis and a11y dates match the rest of the UI.
    val locale = LocalLocale.current.platformLocale
    val durationTexts = remember(durationsMs, format) { durationsMs.map(format::duration) }
    val description =
        remember(data, zone, locale, stageNames, durationTexts, resources) {
            describe(resources, data.window, zone, locale, stageNames, durationTexts)
        }
    val selectionDescription =
        remember(selection, data.window, zone, locale, resources, format) {
            selection?.let {
                val withDate = data.window.showsDates(zone)
                resources.getString(
                    R.string.chart_a11y_selection,
                    formatClock(it.startMs, zone, withDate, locale),
                    formatClock(it.endMs, zone, withDate, locale),
                    format.duration(it.durationMs),
                )
            }
        }

    // Selection and cursor reach the draw lambda through State, so a change redraws only and neither
    // rebuilds the cache below nor, for the cursor, recomposes anything.
    val selectionState = rememberUpdatedState(selection)
    val cursorState = rememberUpdatedState(cursorMs)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .semantics {
                contentDescription = description
                selectionDescription?.let { stateDescription = it }
            }.drawWithCache {
                val layer =
                    buildLayer(
                        density = this,
                        textDensity = textDensity,
                        layoutDirection = layoutDirection,
                        size = size,
                        data = data,
                        zone = zone,
                        locale = locale,
                        palette = palette,
                        stageNames = stageNames,
                        durationTexts = durationTexts,
                        textStyle = labelStyle,
                        measurer = textMeasurer,
                    )
                // Static data in its own offscreen layer: a cursor frame redraws only the overlay.
                val dataLayer =
                    obtainGraphicsLayer().apply {
                        compositingStrategy = CompositingStrategy.Offscreen
                        record { drawHypnogramData(layer) }
                    }
                onDrawBehind {
                    drawLayer(dataLayer)
                    drawHypnogramOverlay(layer, selectionState, cursorState)
                }
            },
    )
}

private class HypnogramPalette(
    val stage: Array<Color>,
    val pulse: Color,
    val grid: Color,
    val connector: Color,
    val gapHatch: Color,
    val label: Color,
    val dim: Color,
    val cursor: Color,
    val edge: Color,
)

/** Everything the draw lambda needs, built once per data/size/colors/density change. */
private class HypnogramLayer(
    val palette: HypnogramPalette,
    val plotLeft: Float,
    val plotWidth: Float,
    val rowsBottom: Float,
    val scale: TimeScale,
    val stagePaths: Array<Path>,
    val gridPath: Path,
    val gapFillPath: Path,
    val gapHatchPath: Path,
    val connectorPath: Path,
    val pulsePath: Path,
    val hairStroke: Stroke,
    val cursorStrokePx: Float,
    val edgeStrokePx: Float,
    val textLayouts: Array<TextLayoutResult>,
    val textOffsets: Array<Offset>,
    val ticks: List<TimeTick>,
    val axisStyle: AxisStyle,
)

@Suppress("LongParameterList", "LongMethod")
private fun buildLayer(
    density: Density,
    textDensity: Density,
    layoutDirection: LayoutDirection,
    size: Size,
    data: HypnogramData,
    zone: ZoneId,
    locale: Locale,
    palette: HypnogramPalette,
    stageNames: List<String>,
    durationTexts: List<String>,
    textStyle: TextStyle,
    measurer: TextMeasurer,
): HypnogramLayer =
    with(density) {
        fun measure(
            text: String,
            maxWidthPx: Int = Constraints.Infinity,
        ) = measurer.measure(
            text = text,
            style = textStyle,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidthPx),
            layoutDirection = layoutDirection,
            density = textDensity,
        )

        val stages = HypnogramStage.entries
        val nameLayouts = stages.map { measure(stageNames[it.ordinal], (size.width * LABEL_COLUMN_FRACTION).toInt()) }
        val durationLayouts = stages.map { measure(durationTexts[it.ordinal], (size.width * DURATION_COLUMN_FRACTION).toInt()) }
        val plotLeft = nameLayouts.maxOf { it.size.width } + ColumnGap.toPx()
        val plotRight = size.width - durationLayouts.maxOf { it.size.width } - TotalsGutter.toPx()
        val plotWidth = maxOf(1f, plotRight - plotLeft)
        val scale = TimeScale(data.window, plotWidth)

        // Time axis: ticks and labels measured here, once.
        val sample = measure("00:00")
        val maxTicks = maxOf(2, (plotWidth / (sample.size.width + MinTickSpacing.toPx())).toInt())
        val tickSet = niceTimeTickSet(data.window, maxTicks, zone)
        val ticks = tickSet.ticksMs.map { TimeTick(it, measure(formatTickLabel(it, zone, data.window, tickSet.dayAligned, locale))) }
        val tickLength = TickLength.toPx()
        val axisHeight = tickLength + TickLabelGap.toPx() + sample.size.height

        val rowsTop = PulseZone.toPx()
        val rowsBottom = maxOf(rowsTop, size.height - axisHeight)
        val rowHeight = (rowsBottom - rowsTop) / stages.size
        val barHeight = minOf(rowHeight * BAR_FRACTION, BarMaxHeight.toPx())
        val connectorInset = maxOf(0f, barHeight / 2f - BarRadius.toPx())

        fun centerY(stage: HypnogramStage) = rowsTop + rowHeight * (stage.ordinal + 0.5f)

        fun xOf(tMs: Long) = plotLeft + scale.toX(tMs).coerceIn(0f, plotWidth)

        val stagePaths = Array(stages.size) { Path() }
        val connectorPath = Path()
        val gridPath = Path()
        for (stage in stages) {
            gridPath.moveTo(plotLeft, centerY(stage))
            gridPath.lineTo(plotLeft + plotWidth, centerY(stage))
        }

        // Segments clip to the window; connectors join only segments that touch, so a gap breaks the step line.
        var previousStage: HypnogramStage? = null
        var previousEndX = 0f
        for (segment in data.segments) {
            if (segment.endMs <= data.window.startMs || segment.startMs >= data.window.endMs) continue
            val startX = xOf(segment.startMs)
            val endX = maxOf(xOf(segment.endMs), minOf(startX + MIN_VISIBLE_PX, plotLeft + plotWidth))
            val top = centerY(segment.stage) - barHeight / 2f
            val radius = minOf(BarRadius.toPx(), (endX - startX) / 2f, barHeight / 2f)
            stagePaths[segment.stage.ordinal].addRoundRect(
                RoundRect(startX, top, endX, top + barHeight, CornerRadius(radius)),
            )
            val before = previousStage
            if (before != null && before != segment.stage && abs(startX - previousEndX) <= MIN_VISIBLE_PX) {
                // Stop short of the bar centers so a translucent bar (maintenance) never shows the line through it.
                val direction = if (segment.stage.ordinal > before.ordinal) 1f else -1f
                connectorPath.moveTo(startX, centerY(before) + direction * connectorInset)
                connectorPath.lineTo(startX, centerY(segment.stage) - direction * connectorInset)
            }
            previousStage = segment.stage
            previousEndX = endX
        }

        val gapFillPath = Path()
        val gapHatchPath = Path()
        val hatchSpacing = HatchSpacing.toPx()
        val rowsHeight = rowsBottom - rowsTop
        for (gap in hypnogramGaps(data)) {
            val left = xOf(gap.startMs)
            val right = xOf(gap.endMs)
            if (right - left < MIN_VISIBLE_PX) continue
            gapFillPath.addRect(Rect(left, rowsTop, right, rowsBottom))
            // 45 degree lines, analytically clipped to the band so no clip is needed per frame.
            var x = left - rowsHeight
            while (x < right) {
                val lo = maxOf(x, left)
                val hi = minOf(x + rowsHeight, right)
                if (hi > lo) {
                    gapHatchPath.moveTo(lo, rowsBottom - (lo - x))
                    gapHatchPath.lineTo(hi, rowsBottom - (hi - x))
                }
                x += hatchSpacing
            }
        }

        val pulsePath = Path()
        val pulseHalf = PulseWidth.toPx() / 2f
        val pulseBottom = rowsTop - PulseGap.toPx()
        val pulseTop = pulseBottom - PulseHeight.toPx()
        for (t in data.wakePulsesMs) {
            if (t < data.window.startMs || t > data.window.endMs) continue
            val x = xOf(t)
            pulsePath.addRect(Rect(x - pulseHalf, pulseTop, x + pulseHalf, pulseBottom))
        }

        // Row labels at the start, totals right-aligned at the end, both centered on the row.
        val textLayouts = Array(stages.size * 2) { if (it < stages.size) nameLayouts[it] else durationLayouts[it - stages.size] }
        val textOffsets =
            Array(stages.size * 2) {
                val layout = textLayouts[it]
                val y = centerY(stages[it % stages.size]) - layout.size.height / 2f
                if (it < stages.size) Offset(0f, y) else Offset(size.width - layout.size.width, y)
            }

        HypnogramLayer(
            palette = palette,
            plotLeft = plotLeft,
            plotWidth = plotWidth,
            rowsBottom = rowsBottom,
            scale = scale,
            stagePaths = stagePaths,
            gridPath = gridPath,
            gapFillPath = gapFillPath,
            gapHatchPath = gapHatchPath,
            connectorPath = connectorPath,
            pulsePath = pulsePath,
            hairStroke = Stroke(width = HairStroke.toPx()),
            cursorStrokePx = CursorStroke.toPx(),
            edgeStrokePx = EdgeStroke.toPx(),
            textLayouts = textLayouts,
            textOffsets = textOffsets,
            ticks = ticks,
            axisStyle =
                AxisStyle(
                    tickColor = palette.grid,
                    labelColor = palette.label,
                    tickLengthPx = tickLength,
                    strokePx = HairStroke.toPx(),
                    labelGapPx = TickLabelGap.toPx(),
                ),
        )
    }

/** The data-only part, recorded once per cache build into its own layer. It reads no snapshot state. */
private fun DrawScope.drawHypnogramData(layer: HypnogramLayer) {
    val palette = layer.palette
    drawPath(layer.gridPath, palette.grid, style = layer.hairStroke)
    drawPath(layer.gapFillPath, palette.grid)
    drawPath(layer.gapHatchPath, palette.gapHatch, style = layer.hairStroke)
    drawPath(layer.connectorPath, palette.connector, style = layer.hairStroke)
    for (i in layer.stagePaths.indices) drawPath(layer.stagePaths[i], palette.stage[i])
    drawPath(layer.pulsePath, palette.pulse)
    for (i in layer.textLayouts.indices) {
        drawText(layer.textLayouts[i], color = palette.label, topLeft = layer.textOffsets[i])
    }
    translate(left = layer.plotLeft, top = layer.rowsBottom) {
        drawTimeAxis(layer.ticks, layer.scale, layer.axisStyle)
    }
}

/** The per-frame path: only State reads and primitive math happen here. */
private fun DrawScope.drawHypnogramOverlay(
    layer: HypnogramLayer,
    selectionState: State<TimeRange?>,
    cursorState: State<() -> Long?>,
) {
    val palette = layer.palette
    val selection = selectionState.value
    // A selection outside the window has nothing here to dim: drawing it would wash out the whole chart.
    if (selection != null && selection.overlaps(layer.scale.window)) {
        val plotRight = layer.plotLeft + layer.plotWidth
        val start = layer.plotLeft + layer.scale.toX(selection.startMs).coerceIn(0f, layer.plotWidth)
        val end = layer.plotLeft + layer.scale.toX(selection.endMs).coerceIn(0f, layer.plotWidth)
        drawRect(palette.dim, Offset(layer.plotLeft, 0f), Size(start - layer.plotLeft, layer.rowsBottom))
        drawRect(palette.dim, Offset(end, 0f), Size(plotRight - end, layer.rowsBottom))
        // Edge lines only where the range ends inside the window; a clamped edge would just trace the plot's border.
        val window = layer.scale.window
        if (selection.startMs > window.startMs && selection.startMs < window.endMs) {
            drawLine(palette.edge, Offset(start, 0f), Offset(start, layer.rowsBottom), strokeWidth = layer.edgeStrokePx)
        }
        if (selection.endMs > window.startMs && selection.endMs < window.endMs) {
            drawLine(palette.edge, Offset(end, 0f), Offset(end, layer.rowsBottom), strokeWidth = layer.edgeStrokePx)
        }
    }

    val cursor = cursorState.value()
    if (cursor != null && cursor >= layer.scale.window.startMs && cursor <= layer.scale.window.endMs) {
        val x = layer.plotLeft + layer.scale.toX(cursor)
        drawLine(palette.cursor, Offset(x, 0f), Offset(x, layer.rowsBottom), strokeWidth = layer.cursorStrokePx)
    }
}

/** A11y text in [locale]: the clock times name their dates when the window [showsDates] in [zone] (the scrub chart's rule). */
private fun describe(
    resources: Resources,
    window: TimeRange,
    zone: ZoneId,
    locale: Locale,
    stageNames: List<String>,
    durationTexts: List<String>,
): String {
    val withDate = window.showsDates(zone)
    val span =
        resources.getString(
            R.string.chart_a11y_window,
            formatClock(window.startMs, zone, withDate, locale),
            formatClock(window.endMs, zone, withDate, locale),
        )
    val totals =
        stageNames.indices.joinToString(", ") {
            resources.getString(R.string.chart_stage_total, stageNames[it], durationTexts[it])
        }
    return "$span. $totals"
}
