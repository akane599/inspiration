package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId

private object BannerMetrics {
    val BorderWidth = 1.dp
}

/** Gaps shorter than this are rounding noise at the edges of a slice, not missing data worth a warning. */
private const val MIN_GAP_MS = 60_000L

/**
 * The parts of [requested] that [covered] does not reach: before it starts and after it ends, ignoring gaps under a
 * minute. A null [covered] (no data at all) leaves the whole request as one gap.
 */
internal fun sliceGaps(requested: TimeRange, covered: TimeRange?): List<TimeRange> {
    val disjoint = covered == null || covered.endMs <= requested.startMs || covered.startMs >= requested.endMs
    val gaps = if (covered == null || disjoint) {
        listOf(requested)
    } else {
        listOf(
            TimeRange.of(requested.startMs, covered.startMs.coerceAtLeast(requested.startMs)),
            TimeRange.of(covered.endMs.coerceAtMost(requested.endMs), requested.endMs),
        )
    }
    return gaps.filter { it.durationMs >= MIN_GAP_MS }
}

/**
 * The one wording for missing data: "No data for 25 min at 2 Mar 08:00–08:25" (Turkish: "2 Mar 08:00–08:25
 * arasında 25 dk veri yok"). Screens with their own gap data use this instead of phrasing it themselves.
 */
@Composable
fun sliceGapText(gap: TimeRange, zone: ZoneId = ZoneId.systemDefault()): String {
    val format = rememberTelemetryFormat()
    return stringResource(
        R.string.slice_gap,
        format.duration(gap.durationMs),
        format.range(gap.startMs, gap.endMs, zone),
    )
}

/**
 * Tells the user that the figures below describe a slice of time: the [requested] range and the range [covered] by
 * data (null when there is none). The ranges and a "No data for 25 min at …" line per gap are formatted here, so
 * every screen words them the same way. [onClear] removes the slice.
 */
@Composable
fun TimeSliceBanner(
    requested: TimeRange,
    covered: TimeRange?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val format = rememberTelemetryFormat()
    val gaps = sliceGaps(requested, covered).map { sliceGapText(it, zone) }
    TimeSliceBannerContent(
        requested = format.range(requested.startMs, requested.endMs, zone),
        covered = covered?.let { format.range(it.startMs, it.endMs, zone) } ?: NO_VALUE,
        gapTexts = gaps.toImmutableList(),
        onClear = onClear,
        modifier = modifier,
    )
}

/**
 * Same banner for ranges the caller has already formatted: the [requested] range, the range [covered] by data, and
 * a [gapText] when the two differ (null when the data covers the whole request). Prefer the [TimeRange] overload,
 * which formats the ranges and words the gap consistently.
 */
@Composable
fun TimeSliceBanner(
    requested: String,
    covered: String,
    gapText: String?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TimeSliceBannerContent(
        requested = requested,
        covered = covered,
        gapTexts = listOfNotNull(gapText).toImmutableList(),
        onClear = onClear,
        modifier = modifier,
    )
}

@Composable
private fun TimeSliceBannerContent(
    requested: String,
    covered: String,
    gapTexts: ImmutableList<String>,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val spacing = MaterialTheme.spacing
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(BannerMetrics.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.md, top = spacing.sm, end = spacing.sm, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                val requestedLabel = stringResource(R.string.slice_requested)
                val coveredLabel = stringResource(R.string.slice_covered)
                val labelWidth = widestLabel(requestedLabel, coveredLabel)
                RangeLine(requestedLabel, labelWidth, requested)
                RangeLine(coveredLabel, labelWidth, covered)
                gapTexts.forEach { gapText ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SemanticDot(color = MaterialTheme.statusColors.warning)
                        Text(
                            text = gapText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.slice_clear))
            }
        }
    }
}

/**
 * One "label  value" line. The label sits in a column of fixed [labelWidth] (the wider of the banner's labels), so
 * the values of every line start at the same x, and the two texts share a baseline. A long value wraps inside its
 * own column rather than under the label.
 */
@Composable
private fun RangeLine(label: String, labelWidth: Dp, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth).alignByBaseline(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/** The width of the widest of [labels] in the range-line label style, so the lines can share one label column. */
@Composable
private fun widestLabel(vararg labels: String): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelMedium
    return remember(labels.toList(), style, density, measurer) {
        with(density) { labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }.toDp() }
    }
}
