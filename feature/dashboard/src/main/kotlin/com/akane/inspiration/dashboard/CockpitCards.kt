package com.akane.inspiration.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.EmptyStateAction
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.LiveGauge
import com.akane.inspiration.designsystem.component.MetricTile
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.diagnostics.story.AndroidStoryWriter
import com.akane.inspiration.diagnostics.story.BatteryStory
import java.util.Locale

/** The Battery Story card, or the designed loading, empty and error state that stands in for it. */
@Composable
internal fun StoryCard(
    analysis: AnalysisUi,
    shizuku: ShizukuUi,
    onIntent: (CockpitIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        when (analysis) {
            AnalysisUi.Loading -> StoryLoading()
            AnalysisUi.Empty -> {
                val blocked = shizuku.reason != ShizukuReason.Ready
                EmptyState(
                    title = stringResource(R.string.cockpit_empty_title),
                    body = stringResource(if (blocked) R.string.cockpit_empty_blocked_body else R.string.cockpit_empty_body),
                    action = if (shizuku.canGrant) {
                        EmptyStateAction(stringResource(R.string.cockpit_shizuku_grant)) { onIntent(CockpitIntent.GrantShizuku) }
                    } else {
                        EmptyStateAction(stringResource(R.string.cockpit_empty_refresh)) { onIntent(CockpitIntent.Refresh) }
                    },
                )
            }
            AnalysisUi.Error -> EmptyState(
                title = stringResource(R.string.cockpit_error_title),
                body = stringResource(R.string.cockpit_error_body),
                action = EmptyStateAction(stringResource(R.string.cockpit_error_retry)) { onIntent(CockpitIntent.Refresh) },
            )
            is AnalysisUi.Ready -> StoryText(analysis.story)
        }
    }
}

@Composable
private fun StoryTitle() {
    CardTitle(text = stringResource(R.string.cockpit_story_title))
}

@Composable
private fun StoryLoading() {
    StoryTitle()
    Row(
        modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        LoadingIndicator()
        Text(
            text = stringResource(R.string.cockpit_story_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The diagnostics module words the story in the current locale; this only shows it. */
@Composable
private fun StoryText(story: BatteryStory) {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val text = remember(story, locale, context) { AndroidStoryWriter(context).render(story, locale) }
    StoryTitle()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
    )
}

/**
 * The live gauge with its time-to-empty line. A missing reading shows dashes, never zeros, and with no reading at
 * all the gauge neither claims a charging state nor a time left.
 */
@Composable
internal fun LiveCard(live: LiveUi?, modifier: Modifier = Modifier) {
    val format = rememberTelemetryFormat()
    val remainingMs = live?.remainingMs
    val remaining = when {
        live == null -> null
        remainingMs != null -> stringResource(R.string.cockpit_remaining, format.duration(remainingMs))
        else -> stringResource(R.string.cockpit_remaining_unknown)
    }
    LiveGauge(
        currentMa = live?.currentMa,
        watts = live?.watts,
        voltageMv = live?.voltageMv,
        temperatureC = live?.temperatureC,
        remaining = remaining,
        isCharging = live?.charging,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Nothing measured: every figure renders as a dash. */
internal val NoMetrics = MetricsUi(
    activePctPerHour = null,
    activeMahPerHour = null,
    idlePctPerHour = null,
    idleMahPerHour = null,
    deepSleepRatio = null,
    deepSleepMs = null,
    screenOnMs = null,
    screenOffAwakeMs = null,
)

/**
 * The 2x2 grid: active drain, idle drain, deep sleep (CPU suspend, not doze) and screen time. At large font
 * scales two tiles no longer fit side by side without breaking their figures, so the grid becomes one column.
 */
@Composable
internal fun MetricGrid(metrics: MetricsUi, modifier: Modifier = Modifier) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    val stacked = LocalDensity.current.fontScale >= CockpitMetrics.StackTilesFontScale
    val tiles = listOf<@Composable (Modifier) -> Unit>(
        { tile -> ActiveTile(metrics, format, tile) },
        { tile -> IdleTile(metrics, format, tile) },
        { tile -> DeepSleepTile(metrics, format, tile) },
        { tile -> ScreenTile(metrics, format, tile) },
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (stacked) {
            tiles.forEach { tile -> tile(Modifier.fillMaxWidth()) }
        } else {
            tiles.chunked(2).forEach { pair ->
                // Two tiles of equal height: the row is as tall as the taller tile.
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    pair.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun ActiveTile(metrics: MetricsUi, format: TelemetryFormat, modifier: Modifier) {
    val rate = format.percentPerHour(metrics.activePctPerHour)
    MetricTile(
        title = stringResource(R.string.cockpit_metric_active),
        primary = rate.value,
        primaryUnit = rate.unit,
        secondary = stringResource(
            R.string.cockpit_metric_active_detail,
            format.milliampHoursPerHour(metrics.activeMahPerHour).text,
        ),
        progress = null,
        modifier = modifier,
    )
}

@Composable
private fun IdleTile(metrics: MetricsUi, format: TelemetryFormat, modifier: Modifier) {
    val rate = format.percentPerHour(metrics.idlePctPerHour)
    MetricTile(
        title = stringResource(R.string.cockpit_metric_idle),
        primary = rate.value,
        primaryUnit = rate.unit,
        secondary = stringResource(
            R.string.cockpit_metric_idle_detail,
            format.milliampHoursPerHour(metrics.idleMahPerHour).text,
        ),
        progress = null,
        modifier = modifier,
    )
}

@Composable
private fun DeepSleepTile(metrics: MetricsUi, format: TelemetryFormat, modifier: Modifier) {
    val suspended = metrics.deepSleepMs?.let(format::duration) ?: NO_VALUE
    MetricTile(
        title = stringResource(R.string.cockpit_metric_deep_sleep),
        primary = format.number(metrics.deepSleepRatio?.let { it * PERCENT }, decimals = 1),
        primaryUnit = stringResource(R.string.cockpit_unit_percent),
        secondary = stringResource(R.string.cockpit_metric_deep_sleep_detail, suspended),
        progress = metrics.deepSleepRatio?.toFloat(),
        modifier = modifier,
    )
}

@Composable
private fun ScreenTile(metrics: MetricsUi, format: TelemetryFormat, modifier: Modifier) {
    val awake = metrics.screenOffAwakeMs?.let(format::duration) ?: NO_VALUE
    MetricTile(
        title = stringResource(R.string.cockpit_metric_screen),
        primary = metrics.screenOnMs?.let(format::duration) ?: NO_VALUE,
        secondary = stringResource(R.string.cockpit_metric_screen_detail, awake),
        progress = null,
        modifier = modifier,
    )
}

private const val PERCENT = 100.0
