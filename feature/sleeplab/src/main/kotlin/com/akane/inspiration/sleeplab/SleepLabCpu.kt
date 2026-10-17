package com.akane.inspiration.sleeplab

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.MetricBar
import com.akane.inspiration.designsystem.component.MetricValue
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing

private const val PERCENT = 100.0

/**
 * CPU deep sleep (suspend) and, per cluster, how long the CPU spent at each frequency. The residency needs
 * valid kernel counters; without them the card says so instead of showing zeros.
 */
@Composable
internal fun CpuCard(cpu: CpuUi, deepSleep: DeepSleepUi, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val format = rememberTelemetryFormat()
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardHeader(title = stringResource(R.string.sleeplab_cpu_title), subtitle = stringResource(R.string.sleeplab_cpu_subtitle))
        DeepSleepBlock(deepSleep, format, Modifier.padding(top = spacing.md))
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
        when (cpu) {
            CpuUi.Unavailable -> EmptyState(
                title = stringResource(R.string.sleeplab_cpu_unavailable_title),
                body = stringResource(R.string.sleeplab_cpu_unavailable_body),
                action = null,
            )
            CpuUi.NoData -> EmptyState(
                title = stringResource(R.string.sleeplab_cpu_nodata_title),
                body = stringResource(R.string.sleeplab_cpu_nodata_body),
                action = null,
            )
            is CpuUi.Ready -> Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                cpu.clusters.forEach { cluster -> ClusterBlock(cluster, format) }
            }
        }
    }
}

/** The CPU-suspend share. Not doze: the note under it says so. A missing figure is a dash, never a zero. */
@Composable
private fun DeepSleepBlock(deepSleep: DeepSleepUi, format: TelemetryFormat, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val ratio = deepSleep.ratio
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.sleeplab_deep_sleep_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricValue(
            value = format.number(ratio?.let { it * PERCENT }, decimals = 1),
            unit = stringResource(R.string.sleeplab_unit_percent),
            style = MaterialTheme.numericTypography.title,
        )
        if (ratio != null) {
            MetricBar(progress = ratio.toFloat())
        }
        if (deepSleep.suspendedMs != null) {
            Text(
                text = stringResource(R.string.sleeplab_deep_sleep_detail, format.duration(deepSleep.suspendedMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.sleeplab_deep_sleep_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClusterBlock(cluster: ClusterUi, format: TelemetryFormat) {
    val spacing = MaterialTheme.spacing
    val mhz = stringResource(R.string.sleeplab_unit_mhz)
    val ghz = stringResource(R.string.sleeplab_unit_ghz)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.sleeplab_cluster_title, stringResource(cluster.role.nameRes()), cluster.policy),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        cluster.rows.forEach { row ->
            FreqLine(
                label = formatFrequency(format, row.freqKhz, mhz, ghz),
                share = row.share,
                timeMs = row.timeMs,
                format = format,
            )
        }
        if (cluster.otherShare > 0f) {
            FreqLine(
                label = stringResource(R.string.sleeplab_freq_other),
                share = cluster.otherShare,
                timeMs = (cluster.totalMs * cluster.otherShare).toLong(),
                format = format,
            )
        }
    }
}

/** One frequency: its name and how much of the cluster's time it took, over a bar of the same share. */
@Composable
private fun FreqLine(label: String, share: Float, timeMs: Long, format: TelemetryFormat) {
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm), verticalAlignment = Alignment.Top) {
            Text(
                text = label,
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.sleeplab_freq_share, formatShare(format, share), format.duration(timeMs)),
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MetricBar(progress = share, modifier = Modifier.padding(top = MaterialTheme.spacing.xs))
    }
}

@StringRes
private fun ClusterRole.nameRes(): Int = when (this) {
    ClusterRole.Little -> R.string.sleeplab_cluster_little
    ClusterRole.Big -> R.string.sleeplab_cluster_big
    ClusterRole.Mid -> R.string.sleeplab_cluster_mid
    ClusterRole.Prime -> R.string.sleeplab_cluster_prime
    ClusterRole.Generic -> R.string.sleeplab_cluster_generic
}
