package com.akane.inspiration.sleeplab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.chart.hypnogram.SleepHypnogram
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.LegendItem
import com.akane.inspiration.designsystem.component.LegendRow
import com.akane.inspiration.designsystem.component.ScreenHeader
import com.akane.inspiration.designsystem.component.TimeSliceBanner
import com.akane.inspiration.designsystem.component.format.rememberSystemZoneId
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors
import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import kotlinx.collections.immutable.persistentListOf
import java.time.ZoneId

/**
 * Sleep Lab, stateless: the whole screen is a function of [state] and reports through [onIntent]. [explain]
 * words a dictionary explanation for a name in the current locale. Until the analysis is ready one status card
 * stands in for everything. Once it is, the culprit card comes first (when the awake check fired or could not run),
 * then the hypnogram, then the tabs and the CPU card: stacked under
 * [SleepLabMetrics.TwoColumnMinWidth], side by side above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepLabScreen(
    state: SleepLabState,
    onIntent: (SleepLabIntent) -> Unit,
    explain: (EntryKind, String) -> Explanation?,
    modifier: Modifier = Modifier,
    zone: ZoneId = rememberSystemZoneId(),
) {
    val spacing = MaterialTheme.spacing
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onIntent(SleepLabIntent.Refresh) },
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= SleepLabMetrics.TwoColumnMinWidth
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                ScreenHeader(title = stringResource(R.string.sleeplab_title))
                state.slice?.let { slice -> SliceBanner(slice = slice, onClear = { onIntent(SleepLabIntent.ClearSlice) }, zone = zone) }
                when (val analysis = state.analysis) {
                    AnalysisUi.Loading, AnalysisUi.Empty, AnalysisUi.Error -> {
                        StatusCard(analysis, onIntent)
                    }

                    is AnalysisUi.Ready -> {
                        // The diagnosis comes first; the hypnogram and the tabs below are its evidence.
                        CulpritCard(analysis.awake, explain)
                        HypnogramCard(hypnogram = state.hypnogram, zone = zone, onIntent = onIntent)
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                                DiagnosticsCard(analysis, state.tab, onIntent, explain, Modifier.weight(1f))
                                CpuCard(analysis.cpu, analysis.deepSleep, Modifier.weight(1f))
                            }
                        } else {
                            DiagnosticsCard(analysis, state.tab, onIntent, explain)
                            CpuCard(analysis.cpu, analysis.deepSleep)
                        }
                    }
                }
            }
        }
    }
}

/** The banner that tells the user every figure below describes a slice, with how well the data covers it. */
@Composable
private fun SliceBanner(
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

/** The designed loading, empty and error state that stands in for the screen while there is nothing to show. */
@Composable
private fun StatusCard(
    analysis: AnalysisUi,
    onIntent: (SleepLabIntent) -> Unit,
) {
    InspirationCard(modifier = Modifier.fillMaxWidth()) {
        when (analysis) {
            AnalysisUi.Empty -> {
                EmptyState(
                    title = stringResource(R.string.sleeplab_empty_title),
                    body = stringResource(R.string.sleeplab_empty_body),
                    action = EmptyStateAction(stringResource(R.string.sleeplab_empty_refresh)) { onIntent(SleepLabIntent.Refresh) },
                )
            }

            AnalysisUi.Error -> {
                EmptyState(
                    title = stringResource(R.string.sleeplab_error_title),
                    body = stringResource(R.string.sleeplab_error_body),
                    action = EmptyStateAction(stringResource(R.string.sleeplab_error_retry)) { onIntent(SleepLabIntent.Refresh) },
                )
            }

            else -> {
                LoadingRow(stringResource(R.string.sleeplab_loading))
            }
        }
    }
}

@Composable
internal fun LoadingRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        // The spinner animates forever, so previews and screenshot runs skip it.
        if (!LocalInspectionMode.current) {
            CircularProgressIndicator(modifier = Modifier.size(MaterialTheme.spacing.lg))
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card heading, in the shared card-title style, with the one line that says what the card shows. */
@Composable
internal fun CardHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        CardTitle(text = title)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The screen-off hypnogram with its wake-up legend, or the designed loading, failed and empty state. */
@Composable
private fun HypnogramCard(
    hypnogram: HypnogramUi,
    zone: ZoneId,
    onIntent: (SleepLabIntent) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    InspirationCard(modifier = Modifier.fillMaxWidth()) {
        CardHeader(
            title = stringResource(R.string.sleeplab_hypnogram_title),
            subtitle = stringResource(R.string.sleeplab_hypnogram_subtitle),
        )
        when (hypnogram) {
            HypnogramUi.Loading -> {
                LoadingRow(stringResource(R.string.sleeplab_hypnogram_loading))
            }

            HypnogramUi.Failed -> {
                EmptyState(
                    title = stringResource(R.string.sleeplab_hypnogram_failed_title),
                    body = stringResource(R.string.sleeplab_hypnogram_failed_body),
                    action = EmptyStateAction(stringResource(R.string.sleeplab_error_retry)) { onIntent(SleepLabIntent.Refresh) },
                )
            }

            is HypnogramUi.Ready -> {
                if (hypnogram.data.segments.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.sleeplab_hypnogram_empty_title),
                        body = stringResource(R.string.sleeplab_hypnogram_empty_body),
                        action = null,
                    )
                } else {
                    val format = rememberTelemetryFormat()
                    val wakeUps = hypnogram.data.wakePulsesMs.size
                    val hatched = remember(hypnogram.data) { hasHatchedGaps(hypnogram.data) }
                    SleepHypnogram(data = hypnogram.data, zone = zone, modifier = Modifier.padding(top = spacing.sm))
                    LegendRow(
                        items =
                            persistentListOf(
                                LegendItem(
                                    color = MaterialTheme.telemetryColors.critical,
                                    label = pluralStringResource(R.plurals.sleeplab_wakeups, wakeUps, format.number(wakeUps.toDouble())),
                                ),
                            ),
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                    Text(
                        text = stringResource(if (hatched) R.string.sleeplab_hypnogram_note else R.string.sleeplab_hypnogram_note_wakeups),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.sm),
                    )
                }
            }
        }
    }
}
