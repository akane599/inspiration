package com.akane.inspiration.designsystem.chart.scrub

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.chart.model.labelRes
import com.akane.inspiration.designsystem.component.LegendItem
import com.akane.inspiration.designsystem.component.LegendRow
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.designsystem.theme.withTabularFigures
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId
import java.util.Locale

/**
 * Synchronized scrubbing timeline: battery % line, temperature line, Gantt lanes and a shared time axis.
 *
 * - Long press then drag scrubs a cursor ([onScrub] reports the time, null on release).
 * - A horizontal drag brushes a range, committed through [onSelectionChange] on release; a tap clears it.
 * - Standard buttons below the plot create, adjust or clear the same range without a pointer gesture.
 * - [cursorMs] draws an externally driven cursor (for example the hypnogram's) while no local scrub is active.
 *
 * Cursor and brush positions are draw-phase state: a drag frame redraws, it does not recompose the chart.
 */
@Composable
fun SynchronizedScrubbingChart(
    data: ScrubChartData,
    selection: TimeRange?,
    onSelectionChange: (TimeRange?) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
    cursorMs: () -> Long? = { null },
    minSelectionMs: Long = 5 * 60_000L,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    SynchronizedScrubbingChart(
        data = data,
        selection = selection,
        onSelectionChange = onSelectionChange,
        fixedCursorMs = null,
        modifier = modifier,
        onScrub = onScrub,
        cursorMs = cursorMs,
        minSelectionMs = minSelectionMs,
        zone = zone,
    )
}

/** Same chart with a fixed cursor, used by the screenshot previews. Not public API. */
@Composable
internal fun SynchronizedScrubbingChart(
    data: ScrubChartData,
    selection: TimeRange?,
    onSelectionChange: (TimeRange?) -> Unit,
    fixedCursorMs: Long?,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
    cursorMs: () -> Long? = { null },
    minSelectionMs: Long = 5 * 60_000L,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val interaction = remember { ScrubInteractionState(initialDim = if (selection != null) 1f else 0f) }
    val selectionState = rememberUpdatedState(selection)
    val externalCursor = rememberUpdatedState(cursorMs)
    val fixedCursor = rememberUpdatedState(fixedCursorMs)
    val isEmpty = data.series.all { it.points.isEmpty() } && data.lanes.all { it.segments.isEmpty() }

    DimFadeEffect(interaction, hasSelection = selection != null)
    PreviewClearEffect(interaction)

    val readoutCursor =
        remember(interaction) {
            {
                val local = interaction.cursorMs.longValue
                if (local != NO_TIME) local else fixedCursor.value ?: externalCursor.value()
            }
        }
    val readoutSelection =
        remember(interaction) {
            {
                val start = interaction.previewStartMs.longValue
                if (start != NO_TIME) TimeRange.of(start, interaction.previewEndMs.longValue) else selectionState.value
            }
        }

    Column(modifier) {
        ScrubReadout(data = data, cursorMs = readoutCursor, activeSelection = readoutSelection, zone = zone)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        ScrubPlot(
            data = data,
            selection = selection,
            onSelectionChange = onSelectionChange,
            onScrub = onScrub,
            minSelectionMs = minSelectionMs,
            zone = zone,
            isEmpty = isEmpty,
            interaction = interaction,
            drawInputs = remember { ScrubDrawInputs(selectionState, externalCursor, fixedCursor) },
        )
        SliceControls(
            window = data.window,
            selection = selection,
            onSelectionChange = onSelectionChange,
            minSelectionMs = minSelectionMs,
            hasData = !isEmpty,
            modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        ScrubLegend()
    }
}

@Composable
private fun ScrubPlot(
    data: ScrubChartData,
    selection: TimeRange?,
    onSelectionChange: (TimeRange?) -> Unit,
    onScrub: (Long?) -> Unit,
    minSelectionMs: Long,
    zone: ZoneId,
    isEmpty: Boolean,
    interaction: ScrubInteractionState,
    drawInputs: ScrubDrawInputs,
) {
    val spacing = MaterialTheme.spacing
    val telemetry = MaterialTheme.telemetryColors
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val locale = LocalLocale.current.platformLocale
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.withTabularFigures()
    val celsiusUnit = rememberTelemetryFormat().units.celsius

    val palette =
        remember(telemetry, scheme.onSurface, scheme.primary, scheme.surfaceContainer) {
            ScrubPalette(
                batteryLine = telemetry.batteryLine,
                temperatureLine = telemetry.temperatureLine,
                critical = telemetry.critical,
                grid = telemetry.chartGrid,
                axisLabel = telemetry.chartAxisLabel,
                screenOn = telemetry.screenOn,
                deepSleep = telemetry.deepSleep,
                awake = telemetry.awake,
                charging = telemetry.charging,
                cursor = scheme.onSurface,
                accent = scheme.primary,
                surface = scheme.surfaceContainer,
            )
        }
    val temperatureAxis =
        remember(data) {
            val points =
                data.series
                    .firstOrNull { it.kind == SeriesKind.TEMPERATURE_C }
                    ?.points
                    .orEmpty()
            if (points.isEmpty()) null else temperatureAxis(points.minOf { it.value }, points.maxOf { it.value })
        }
    // Value-axis labels depend on data and style only, so they are measured once here: their width sets the gutters.
    val labels =
        remember(temperatureAxis, labelStyle, textMeasurer, celsiusUnit) {
            measureValueAxisLabels(textMeasurer, labelStyle, temperatureAxis, celsiusUnit)
        }
    val gutters =
        remember(labels, density, spacing) {
            val gap = with(density) { spacing.sm.toPx() }
            Gutters(leftPx = labels.maxLeftWidthPx + gap, rightPx = labels.maxRightWidthPx + gap)
        }
    val config =
        remember(data, palette, labels, temperatureAxis, gutters, spacing, labelStyle, textMeasurer, zone, locale) {
            ScrubRenderConfig(
                data = data,
                palette = palette,
                labels = labels,
                temperatureAxis = temperatureAxis,
                gutters = gutters,
                gutterGap = spacing.sm,
                laneGap = spacing.xs,
                tickGap = spacing.sm,
                tickLabelStyle = labelStyle,
                textMeasurer = textMeasurer,
                zone = zone,
                locale = locale,
            )
        }
    val chartHeight =
        remember(density, labels, spacing) {
            scrubChartHeight(spacing.xs, with(density) { labels.labelHeightPx.toDp() })
        }

    // Each rememberUpdatedState keeps one State instance for the chart's lifetime, so the gesture loop
    // (never restarted by a data reload) always reads the latest window, callbacks and selection through it.
    val windowState = rememberUpdatedState(data.window)
    val currentOnSelectionChange = rememberUpdatedState(onSelectionChange)
    val minSelectionState = rememberUpdatedState(minSelectionMs)
    val guttersState = rememberUpdatedState(gutters)
    val onScrubState = rememberUpdatedState(onScrub)
    val hapticState = rememberUpdatedState(LocalHapticFeedback.current)
    val gestureInputs =
        remember {
            ScrubGestureInputs(
                window = windowState,
                selection = drawInputs.selection,
                minSelectionMs = minSelectionState,
                gutters = guttersState,
                onSelectionChange = currentOnSelectionChange,
                onScrub = onScrubState,
                haptic = hapticState,
            )
        }

    val windowDescription =
        stringResource(
            R.string.chart_a11y_window,
            formatClock(data.window.startMs, zone, data.window.spansDays(), locale),
            formatClock(data.window.endMs, zone, data.window.spansDays(), locale),
        )
    val selectionDescription =
        if (selection != null) {
            stringResource(
                R.string.chart_a11y_selection,
                formatClock(selection.startMs, zone, data.window.spansDays(), locale),
                formatClock(selection.endMs, zone, data.window.spansDays(), locale),
                durationText(selection.durationMs),
            )
        } else {
            stringResource(R.string.chart_a11y_no_selection)
        }
    val clearLabel = stringResource(R.string.chart_clear_selection)
    val exclusionHeightPx =
        with(density) {
            minOf(ScrubChartMetrics.PlotHeight, ScrubChartMetrics.GestureExclusionMax).toPx()
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .semantics {
                    contentDescription = windowDescription
                    stateDescription = selectionDescription
                    if (selection != null) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(clearLabel) {
                                    currentOnSelectionChange.value(null)
                                    true
                                },
                            )
                    }
                }.systemGestureExclusion { coordinates ->
                    Rect(0f, 0f, coordinates.size.width.toFloat(), exclusionHeightPx)
                }.then(if (isEmpty) Modifier else Modifier.scrubGestures(interaction, gestureInputs))
                .drawWithCache {
                    val renderer = buildScrubRenderer(config)
                    // The data layer is recorded once per cache build into its own offscreen layer, so a drag frame
                    // re-records and re-rasterizes only the cursor/selection overlay, not every path and label.
                    val dataLayer =
                        obtainGraphicsLayer().apply {
                            compositingStrategy = CompositingStrategy.Offscreen
                            record { renderer.drawStatic(this) }
                        }
                    onDrawBehind {
                        drawLayer(dataLayer)
                        renderer.drawOverlay(this, interaction, drawInputs)
                    }
                },
    ) {
        if (isEmpty) {
            Text(
                text = stringResource(R.string.chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ScrubLegend() {
    val telemetry = MaterialTheme.telemetryColors
    val series =
        listOf(
            LegendItem(telemetry.batteryLine, stringResource(R.string.chart_series_battery)),
            LegendItem(telemetry.temperatureLine, stringResource(R.string.chart_series_temperature)),
        )
    val lanes =
        LaneKind.entries.map { kind ->
            val color =
                when (kind) {
                    LaneKind.SCREEN_ON -> telemetry.screenOn
                    LaneKind.DEEP_SLEEP -> telemetry.deepSleep
                    LaneKind.AWAKE -> telemetry.awake
                    LaneKind.CHARGING -> telemetry.charging
                }
            LegendItem(color, stringResource(kind.labelRes))
        }
    LegendRow(items = (series + lanes).toImmutableList())
}

/** Fade the committed selection's scrim in with `tween(150)`, or snap when animations are off. */
@Composable
private fun DimFadeEffect(
    interaction: ScrubInteractionState,
    hasSelection: Boolean,
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) { animatorDurationScale(context) != 0f }
    LaunchedEffect(hasSelection, animationsEnabled) {
        val dim = interaction.dimProgress
        when {
            !hasSelection -> {
                dim.floatValue = 0f
            }

            dim.floatValue >= 1f -> {
                Unit
            }

            !animationsEnabled -> {
                dim.floatValue = 1f
            }

            else -> {
                animate(dim.floatValue, 1f, animationSpec = tween(ScrubChartMetrics.DIM_FADE_MS)) { value, _ ->
                    dim.floatValue = value
                }
            }
        }
    }
}

/** After a commit, keep the brush preview until the caller's new selection has drawn, then drop it. */
@Composable
private fun PreviewClearEffect(interaction: ScrubInteractionState) {
    LaunchedEffect(interaction) {
        snapshotFlow { interaction.commitCount.intValue }.collect { count ->
            if (count > 0) {
                withFrameNanos { }
                withFrameNanos { }
                if (!interaction.gestureActive) interaction.clearPreview()
            }
        }
    }
}

/** Read once per composition; the safe default is 1 (animations on) when the setting is unreadable. */
private fun animatorDurationScale(context: Context): Float =
    try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: RuntimeException) {
        1f
    }

private fun scrubChartHeight(
    laneGap: Dp,
    labelHeight: Dp,
): Dp =
    ScrubChartMetrics.PlotHeight +
        (laneGap + ScrubChartMetrics.LaneHeight) * LaneKind.entries.size +
        laneGap + ScrubChartMetrics.TickLength + laneGap + labelHeight
