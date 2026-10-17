package com.akane.inspiration.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.akane.inspiration.designsystem.component.CardTitle
import com.akane.inspiration.designsystem.component.EmptyState
import com.akane.inspiration.designsystem.component.InspirationCard
import com.akane.inspiration.designsystem.component.MetricBar
import com.akane.inspiration.designsystem.component.MetricValue
import com.akane.inspiration.designsystem.component.SemanticDot
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A whole percentage with the locale's placement of the sign ("87%" / "%87"). */
@Composable
private fun percentText(format: TelemetryFormat, value: Number?, decimals: Int = 0): String =
    if (value == null) NO_VALUE else stringResource(R.string.health_percent_value, format.number(value.toDouble(), decimals))

/** The loading placeholder: the screen's shape is not known yet, so one honest card, never a bare spinner. */
@Composable
internal fun LoadingCard(modifier: Modifier = Modifier) {
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.health_card_title))
        Row(
            modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            // The spinner animates forever, so previews and screenshot runs skip it.
            if (!LocalInspectionMode.current) {
                CircularProgressIndicator(modifier = Modifier.size(MaterialTheme.spacing.lg))
            }
            Text(
                text = stringResource(R.string.health_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- charge curve -------------------------------------------------------------------------------

private fun metricTitle(metric: CurveMetric): Int = when (metric) {
    CurveMetric.Watts -> R.string.health_metric_power
    CurveMetric.Current -> R.string.health_metric_current
    CurveMetric.Voltage -> R.string.health_metric_voltage
    CurveMetric.Temperature -> R.string.health_metric_temperature
}

private fun TelemetryFormat.reading(metric: CurveMetric, value: Float) = when (metric) {
    CurveMetric.Watts -> watts(value.toDouble())
    CurveMetric.Current -> milliamps(value.roundToInt())
    CurveMetric.Voltage -> millivolts(value.roundToInt())
    CurveMetric.Temperature -> celsius(value.toDouble(), decimals = 1)
}

/** Each metric keeps its own colour in its own chart; none of them reuses a sleep-stage or status hue. */
@Composable
private fun metricColor(metric: CurveMetric): Color = when (metric) {
    CurveMetric.Watts -> MaterialTheme.telemetryColors.charging
    CurveMetric.Current -> MaterialTheme.colorScheme.primary
    CurveMetric.Voltage -> MaterialTheme.colorScheme.tertiary
    CurveMetric.Temperature -> MaterialTheme.telemetryColors.temperatureLine
}

/** The live or recorded charge curve as four small charts over battery level, or the designed "no charge" state. */
@Composable
internal fun CurveCard(curve: CurveUi, charging: Boolean, modifier: Modifier = Modifier) {
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.health_curve_title), Modifier.weight(1f))
            if (curve is CurveUi.Ready && curve.source == CurveSource.Live && charging) LiveBadge()
        }
        when (curve) {
            CurveUi.None -> EmptyState(
                title = stringResource(R.string.health_curve_none_title),
                body = stringResource(R.string.health_curve_none_body),
                action = null,
            )
            is CurveUi.Ready -> CurveBody(curve)
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SemanticDot(color = MaterialTheme.statusColors.ok)
        Text(
            text = stringResource(R.string.health_curve_live),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
        )
    }
}

@Composable
private fun CurveBody(curve: CurveUi.Ready) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    val levels = remember { persistentListOf(0f, 50f, 100f) }
    val levelRange = remember { AxisRange(0f, 100f) }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val levelLabels = levels.map { AxisLabel(it, percentText(format, it)) }.toImmutableList()

    curve.series.forEachIndexed { index, series ->
        val last = index == curve.series.lastIndex
        val range = remember(series) { axisRange(series.min, series.max, minAxisSpan(series.metric)) }
        val yLabels = remember(series, range, format) {
            niceTicks(range)
                .map { AxisLabel(it, format.number(it.toDouble(), axisDecimals(series.metric))) }
                .toImmutableList()
        }
        val latest = format.reading(series.metric, series.latest)
        val name = stringResource(metricTitle(series.metric))
        val description = stringResource(
            R.string.health_chart_desc,
            name,
            stringResource(
                R.string.health_chart_range,
                format.reading(series.metric, series.min).text,
                format.reading(series.metric, series.max).text,
            ),
            stringResource(
                R.string.health_chart_range,
                percentText(format, curve.startLevelPct),
                percentText(format, curve.endLevelPct),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            MetricValue(value = latest.value, unit = latest.unit)
        }
        LineChart(
            points = series.points,
            markers = series.throttled,
            xRange = levelRange,
            yRange = range,
            xLabels = if (last) levelLabels else persistentListOf(),
            yLabels = yLabels,
            lineColor = metricColor(series.metric),
            height = (if (last) HealthMetrics.CurveRowHeightWithAxis else HealthMetrics.CurveRowHeight) * fontScale,
            description = description,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
    Text(
        text = stringResource(R.string.health_curve_axis),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
    )
    ThrottleSummary(curve)
    Caption(text = curveCaption(curve, format), modifier = Modifier.padding(top = spacing.sm))
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ThrottleSummary(curve: CurveUi.Ready) {
    if (!curve.throttleKnown) return
    val format = rememberTelemetryFormat()
    val throttle = curve.throttle
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (throttle != null) {
            ThrottleKey()
            Text(
                text = stringResource(R.string.health_throttle_key),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when {
                throttle == null -> stringResource(R.string.health_throttle_none)
                throttle.fromLevelPct == throttle.toLevelPct ->
                    stringResource(R.string.health_throttle_at, percentText(format, throttle.fromLevelPct))
                else -> stringResource(
                    R.string.health_throttle_range,
                    percentText(format, throttle.fromLevelPct),
                    percentText(format, throttle.toLevelPct),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Says where the curve came from and what it lacks, so a thin curve is never mistaken for a full one (H9). */
@Composable
private fun curveCaption(curve: CurveUi.Ready, format: TelemetryFormat): String = when {
    curve.detailFromLevelPct != null ->
        stringResource(R.string.health_curve_caption_partial, percentText(format, curve.detailFromLevelPct))
    curve.source == CurveSource.Live -> stringResource(R.string.health_curve_caption_live)
    curve.source == CurveSource.Recorded -> stringResource(R.string.health_curve_caption_recorded)
    else -> stringResource(R.string.health_curve_caption_history)
}

// ---- health card --------------------------------------------------------------------------------

@Composable
private fun missingText(reason: MissingReason?): String = when (reason) {
    MissingReason.NoKernelRead -> stringResource(R.string.health_missing_no_kernel)
    MissingReason.NotReported -> stringResource(R.string.health_missing_not_reported)
    MissingReason.NotMeasuredYet -> stringResource(R.string.health_missing_not_measured)
    null -> ""
}

/** Design capacity against the kernel's `charge_full` against the app's own measurement, and the cycle count. */
@Composable
internal fun HealthCard(card: HealthCardUi, modifier: Modifier = Modifier) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    val stacked = LocalDensity.current.fontScale >= StackFontScale
    val mah = stringResource(R.string.health_unit_mah)

    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.health_card_title))
        if (stacked) {
            Column(
                modifier = Modifier.padding(top = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                CapacityStat(R.string.health_design_title, card.design, format, mah, Modifier.fillMaxWidth())
                CapacityStat(R.string.health_reported_title, card.reported, format, mah, Modifier.fillMaxWidth())
                CapacityStat(R.string.health_measured_title, card.measured, format, mah, Modifier.fillMaxWidth())
                CycleStat(card, format, Modifier.fillMaxWidth())
            }
        } else {
            Column(
                modifier = Modifier.padding(top = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    CapacityStat(R.string.health_design_title, card.design, format, mah, Modifier.weight(1f))
                    CapacityStat(R.string.health_reported_title, card.reported, format, mah, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                    CapacityStat(R.string.health_measured_title, card.measured, format, mah, Modifier.weight(1f))
                    CycleStat(card, format, Modifier.weight(1f))
                }
            }
        }
        Caption(text = stringResource(R.string.health_card_note), modifier = Modifier.padding(top = spacing.md))
    }
}

/** A reading that is also a progress bar when it is a share of design capacity. Missing shows a dash and why. */
@Composable
private fun Stat(
    titleRes: Int,
    value: String,
    unit: String,
    note: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricValue(
            value = value,
            unit = unit,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            style = MaterialTheme.numericTypography.headline,
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            )
        }
        if (progress != null) {
            MetricBar(progress = progress, modifier = Modifier.padding(top = MaterialTheme.spacing.sm))
        }
    }
}

@Composable
private fun CapacityStat(
    titleRes: Int,
    capacity: CapacityUi,
    format: TelemetryFormat,
    mahUnit: String,
    modifier: Modifier,
) {
    val pct = capacity.pctOfDesign
    Stat(
        titleRes = titleRes,
        value = format.number(capacity.mah, 0),
        unit = if (capacity.mah != null) mahUnit else "",
        note = when {
            capacity.mah == null -> missingText(capacity.missing)
            pct != null -> stringResource(R.string.health_of_design, percentText(format, pct, decimals = 1))
            else -> null
        },
        progress = pct?.let { (it / 100.0).toFloat() },
        modifier = modifier,
    )
}

@Composable
private fun CycleStat(card: HealthCardUi, format: TelemetryFormat, modifier: Modifier) {
    Stat(
        titleRes = R.string.health_cycles_title,
        value = format.number(card.cycles?.toDouble(), 0),
        unit = "",
        note = if (card.cycles == null) missingText(card.cyclesMissing) else null,
        progress = null,
        modifier = modifier,
    )
}

/** From this font scale the health figures stack in one column so their numbers keep their lines. */
private const val StackFontScale = 1.3f

// ---- wear chart ---------------------------------------------------------------------------------

@Composable
internal fun WearCard(wear: WearUi, zone: ZoneId, modifier: Modifier = Modifier) {
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.health_wear_title))
        Caption(text = stringResource(R.string.health_wear_subtitle), modifier = Modifier.padding(top = MaterialTheme.spacing.xs))
        when (wear) {
            is WearUi.NotEnough -> EmptyState(
                title = stringResource(R.string.health_wear_empty_title),
                body = stringResource(R.string.health_wear_empty_body),
                action = null,
            )
            is WearUi.Ready -> WearBody(wear, zone)
        }
    }
}

@Composable
private fun WearBody(wear: WearUi.Ready, zone: ZoneId) {
    val format = rememberTelemetryFormat()
    val spanMs = wear.endMs - wear.startMs
    val xRange = remember(wear) { AxisRange(0f, spanMs.toFloat()) }
    val yRange = remember(wear) { axisRange(wear.minPct, wear.maxPct, WEAR_MIN_SPAN_PCT) }
    val xLabels = remember(wear, format, zone) {
        persistentListOf(
            AxisLabel(0f, formatStamp(wear.startMs, spanMs, format, zone)),
            AxisLabel(spanMs.toFloat(), formatStamp(wear.endMs, spanMs, format, zone)),
        )
    }
    val yLabels = niceTicks(yRange)
        .map { AxisLabel(it, percentText(format, it)) }
        .toImmutableList()
    val summary = stringResource(
        R.string.health_wear_summary,
        percentText(format, wear.latestPct, decimals = 1),
        percentText(format, wear.firstPct, decimals = 1),
    )
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = MaterialTheme.spacing.md),
    )
    LineChart(
        points = wear.points,
        markers = persistentListOf(),
        xRange = xRange,
        yRange = yRange,
        xLabels = xLabels,
        yLabels = yLabels,
        lineColor = MaterialTheme.colorScheme.primary,
        height = HealthMetrics.WearChartHeight * LocalDensity.current.fontScale.coerceAtLeast(1f),
        description = stringResource(
            R.string.health_wear_desc,
            percentText(format, wear.firstPct, decimals = 1),
            percentText(format, wear.latestPct, decimals = 1),
        ),
        modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
    )
}

/** Capacity shifts of a point or two are real but small; the axis never zooms tighter than this. */
private const val WEAR_MIN_SPAN_PCT = 4f

// ---- heat and voltage analysis ------------------------------------------------------------------

@Composable
internal fun AnalysisCard(analysis: WearAnalysisUi?, modifier: Modifier = Modifier) {
    InspirationCard(modifier = modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.health_analysis_title))
        if (analysis == null) {
            EmptyState(
                title = stringResource(R.string.health_analysis_none_title),
                body = stringResource(R.string.health_analysis_none_body),
                action = null,
            )
        } else {
            AnalysisBody(analysis)
        }
    }
}

@Composable
private fun AnalysisBody(analysis: WearAnalysisUi) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    val hotLimit = format.celsius(HOT_TEMPERATURE_C).text
    val voltageLimit = format.millivolts(HIGH_VOLTAGE_MV).text

    Text(
        text = stringResource(R.string.health_analysis_coverage, format.duration(analysis.chargeMs)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.sm),
    )
    Finding(
        hot = analysis.hotMs != null && analysis.hotMs > 0,
        text = when {
            analysis.hotMs == null -> stringResource(R.string.health_hot_unknown)
            analysis.hotMs == 0L -> stringResource(
                R.string.health_hot_none,
                hotLimit,
                format.duration(analysis.hotObservedMs),
            )
            else -> stringResource(
                R.string.health_hot_some,
                format.duration(analysis.hotMs),
                hotLimit,
                format.celsius(analysis.maxTemperatureC, decimals = 1).text,
                format.duration(analysis.hotObservedMs),
            )
        },
    )
    Finding(
        hot = analysis.highVoltageMs != null && analysis.highVoltageMs > 0,
        text = when {
            analysis.highVoltageMs == null -> stringResource(R.string.health_voltage_unknown)
            analysis.highVoltageMs == 0L -> stringResource(
                R.string.health_voltage_none,
                voltageLimit,
                format.duration(analysis.voltageObservedMs),
            )
            else -> stringResource(
                R.string.health_voltage_some,
                format.duration(analysis.highVoltageMs),
                voltageLimit,
                format.millivolts(analysis.maxVoltageMv).text,
                format.duration(analysis.voltageObservedMs),
            )
        },
    )
    if (analysis.sparse) {
        Caption(text = stringResource(R.string.health_analysis_sparse), modifier = Modifier.padding(top = spacing.md))
    }
    Caption(
        text = stringResource(R.string.health_analysis_basis, hotLimit, voltageLimit),
        modifier = Modifier.padding(top = spacing.sm),
    )
}

/** One plain-language finding. The dot is critical only when there is something to act on; the text says it too. */
@Composable
private fun Finding(hot: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        SemanticDot(
            color = if (hot) MaterialTheme.statusColors.warning else MaterialTheme.statusColors.neutral,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
