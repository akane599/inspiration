package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.akane.inspiration.designsystem.component.format.rememberSystemZoneId
import com.akane.inspiration.designsystem.theme.spacing
import java.time.ZoneId

/**
 * The Cockpit, stateless: the whole screen is a function of [state] and reports through [onIntent]. Under
 * [CockpitMetrics.TwoColumnMinWidth] it is one column in the spec's order; above it the story and gauge sit beside
 * the metric grid and the chart spans the full width below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CockpitScreen(
    state: CockpitState,
    onIntent: (CockpitIntent) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = rememberSystemZoneId(),
) {
    val spacing = MaterialTheme.spacing
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onIntent(CockpitIntent.Refresh) },
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= CockpitMetrics.TwoColumnMinWidth
            val metrics = (state.analysis as? AnalysisUi.Ready)?.metrics ?: NoMetrics
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                CockpitHeader(
                    reference = state.reference,
                    shizuku = state.shizuku,
                    // The empty story card already carries a "Grant access" button.
                    showGrant = state.analysis != AnalysisUi.Empty,
                    onIntent = onIntent,
                )
                state.slice?.let { slice ->
                    SliceBanner(slice = slice, onClear = { onIntent(CockpitIntent.ClearSlice) }, zone = zone)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                            StoryCard(analysis = state.analysis, shizuku = state.shizuku, onIntent = onIntent)
                            LiveCard(live = state.live)
                        }
                        MetricGrid(metrics = metrics, modifier = Modifier.weight(1f))
                    }
                } else {
                    StoryCard(analysis = state.analysis, shizuku = state.shizuku, onIntent = onIntent)
                    LiveCard(live = state.live)
                    MetricGrid(metrics = metrics)
                }
                ChartCard(chart = state.chart, slice = state.slice, onIntent = onIntent, zone = zone)
            }
        }
    }
    state.markerSheet?.let { sheet -> MarkerSheet(sheet = sheet, onIntent = onIntent, zone = zone) }
}
