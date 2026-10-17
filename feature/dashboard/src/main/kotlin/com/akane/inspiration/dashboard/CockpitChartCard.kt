package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.chart.scrub.SynchronizedScrubbingChart
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.TimeSliceBanner
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId

/**
 * The synchronized scrub chart. A released brush becomes the app-wide time slice; a tap on it clears the slice.
 * The card title names no period: the chart's own readout states the range once, for the whole day or the slice.
 */
@Composable
internal fun ChartCard(
    chart: ChartUi,
    slice: SliceUi?,
    onIntent: (CockpitIntent) -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    InspirationCard(modifier = modifier) {
        CardTitle(text = stringResource(R.string.cockpit_chart_title))
        when (chart) {
            ChartUi.Loading -> ChartMessage(R.string.cockpit_chart_loading, loading = true)
            ChartUi.Failed -> ChartMessage(R.string.cockpit_chart_failed, loading = false)
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
private fun ChartMessage(textRes: Int, loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) LoadingIndicator()
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The spinner of a loading state. It spins forever, which a screenshot run cannot settle on, so previews draw it
 * as a still partial arc instead.
 */
@Composable
internal fun LoadingIndicator(modifier: Modifier = Modifier) {
    val size = Modifier.size(MaterialTheme.spacing.lg)
    if (LocalInspectionMode.current) {
        CircularProgressIndicator(progress = { PREVIEW_ARC }, modifier = modifier.then(size))
    } else {
        CircularProgressIndicator(modifier = modifier.then(size))
    }
}

private const val PREVIEW_ARC = 0.3f

/** The banner that tells the user every figure below describes a slice, with how well the data covers it. */
@Composable
internal fun SliceBanner(
    slice: SliceUi,
    onClear: () -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    TimeSliceBanner(
        requested = slice.requested,
        covered = slice.covered,
        onClear = onClear,
        modifier = modifier.fillMaxWidth(),
        zone = zone,
    )
}
