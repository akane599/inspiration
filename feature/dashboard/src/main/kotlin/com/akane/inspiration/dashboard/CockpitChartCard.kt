package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.chart.scrub.SynchronizedScrubbingChart
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.TimeSliceBanner
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId
import java.util.Locale

/** The synchronized scrub chart. A released brush becomes the app-wide time slice; a tap on it clears the slice. */
@Composable
internal fun ChartCard(
    chart: ChartUi,
    slice: SliceUi?,
    onIntent: (CockpitIntent) -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    InspirationCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.cockpit_chart_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (chart) {
            ChartUi.Loading -> ChartMessage(R.string.cockpit_chart_loading)
            ChartUi.Failed -> ChartMessage(R.string.cockpit_chart_failed)
            is ChartUi.Ready -> {
                val onSelectionChange = remember(onIntent) {
                    { range: TimeRange? ->
                        onIntent(
                            if (range == null) CockpitIntent.ClearSlice else CockpitIntent.CommitBrush(range.startMs, range.endMs),
                        )
                    }
                }
                SynchronizedScrubbingChart(
                    data = chart.data,
                    selection = slice?.requested,
                    onSelectionChange = onSelectionChange,
                    zone = zone,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                )
                Text(
                    text = stringResource(R.string.cockpit_chart_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun ChartMessage(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.lg),
    )
}

/** The banner that tells the user every figure below describes a slice, with how well the data covers it. */
@Composable
internal fun SliceBanner(
    slice: SliceUi,
    onClear: () -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val format = rememberTelemetryFormat()
    val gap = slice.gapMs
    TimeSliceBanner(
        requested = formatRange(slice.requested.startMs, slice.requested.endMs, locale, zone),
        covered = slice.covered?.let { formatRange(it.startMs, it.endMs, locale, zone) } ?: NO_VALUE,
        gapText = gap?.let { stringResource(R.string.cockpit_slice_gap, format.duration(it)) },
        onClear = onClear,
        modifier = modifier.fillMaxWidth(),
    )
}
