package com.akane.inspiration.designsystem.chart.scrub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.math.showsDates
import com.akane.inspiration.designsystem.chart.math.valueAt
import com.akane.inspiration.designsystem.chart.model.ScrubChartData
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one composed element that shows live values. It reads the cursor and the live selection through
 * lambdas, so only this child recomposes while the user scrubs or brushes. The title always reserves two lines
 * (a date range wraps at narrow widths or large fonts) above one values line, so the chart below never shifts.
 */
@Composable
internal fun ScrubReadout(
    data: ScrubChartData,
    cursorMs: () -> Long?,
    activeSelection: () -> TimeRange?,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val cursor = cursorMs()
    val selection = activeSelection()
    val battery =
        data.series
            .firstOrNull { it.kind == SeriesKind.BATTERY_PERCENT }
            ?.points
            .orEmpty()
    val temperature =
        data.series
            .firstOrNull { it.kind == SeriesKind.TEMPERATURE_C }
            ?.points
            .orEmpty()
    val withDate = data.window.showsDates(zone)
    val title: String
    val values: List<String>
    when {
        cursor != null -> {
            title = formatClock(cursor, zone, withDate, locale)
            values = cursorValues(battery, temperature, cursor)
        }

        selection != null -> {
            title = rangeText(selection, zone, withDate, locale)
            values = selectionValues(battery, selection)
        }

        else -> {
            title = rangeText(data.window, zone, withDate, locale)
            values = listOf(durationText(data.window.durationMs))
        }
    }
    val line2 = values.ifEmpty { listOf(durationText((selection ?: data.window).durationMs)) }
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            text = title,
            style = MaterialTheme.numericTypography.title,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
            line2.forEach { value ->
                Text(
                    text = value,
                    style = MaterialTheme.numericTypography.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun cursorValues(
    battery: List<TimePoint>,
    temperature: List<TimePoint>,
    cursor: Long,
): List<String> {
    val out = ArrayList<String>(2)
    valueAt(battery, cursor)?.let { out += stringResource(R.string.chart_readout_battery, it.roundToInt()) }
    // Through TelemetryFormat like the axis and the gauge: locale digits, U+2212 for sub-zero, the unit resource.
    val format = rememberTelemetryFormat()
    valueAt(temperature, cursor)?.let {
        out += stringResource(R.string.chart_readout_temperature, format.celsius(it.toDouble(), decimals = 1).text)
    }
    return out
}

@Composable
private fun selectionValues(
    battery: List<TimePoint>,
    selection: TimeRange,
): List<String> {
    val out = ArrayList<String>(2)
    out += durationText(selection.durationMs)
    val start = valueAt(battery, selection.startMs)
    val end = valueAt(battery, selection.endMs)
    if (start != null && end != null) {
        val delta = (start - end).roundToInt()
        out +=
            if (delta >= 0) {
                stringResource(R.string.chart_readout_selection_drop, delta)
            } else {
                stringResource(R.string.chart_readout_selection_gain, abs(delta))
            }
    }
    return out
}

@Composable
internal fun rangeText(
    range: TimeRange,
    zone: ZoneId,
    withDate: Boolean,
    locale: Locale,
): String =
    stringResource(
        R.string.chart_readout_time_range,
        formatClock(range.startMs, zone, withDate, locale),
        formatClock(range.endMs, zone, withDate, locale),
    )

@Composable
internal fun durationText(durationMs: Long): String = rememberTelemetryFormat().duration(durationMs)
