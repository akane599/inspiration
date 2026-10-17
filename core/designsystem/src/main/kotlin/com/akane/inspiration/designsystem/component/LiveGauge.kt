package com.akane.inspiration.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.akane.inspiration.designsystem.R
import com.akane.inspiration.designsystem.component.format.FormattedValue
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.telemetryColors

// Widest plausible reading per slot. Tabular figures make every digit equally wide, so the template reserves
// the width of the longest value and the unit stays put while digits change.
private const val CURRENT_TEMPLATE = "8888"
private const val POWER_TEMPLATE = "88.88"
private const val VOLTAGE_TEMPLATE = "8888"
private const val TEMPERATURE_TEMPLATE = "88.8"

/**
 * The live battery reading: current as the headline, then power, voltage and temperature, then an optional
 * [remaining] line ("3 h 20 min left", already worded by the caller). Null readings show a dash. Numbers go
 * through `TelemetryFormat`, and every value sits in a slot at least as wide as its widest plausible reading, so
 * the gauge does not jump as digits change. Pass [currentMa] negative while discharging.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveGauge(
    currentMa: Int?,
    watts: Double?,
    voltageMv: Int?,
    temperatureC: Double?,
    remaining: String?,
    isCharging: Boolean,
    modifier: Modifier = Modifier,
) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    InspirationCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SemanticDot(
                color = if (isCharging) MaterialTheme.telemetryColors.charging else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (isCharging) R.string.gauge_charging else R.string.gauge_discharging),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        SlotValue(
            reading = format.milliamps(currentMa),
            template = CURRENT_TEMPLATE,
            style = MaterialTheme.numericTypography.display,
            modifier = Modifier.padding(top = spacing.xs),
        )
        FlowRow(
            modifier = Modifier.padding(top = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            LabeledSlot(R.string.gauge_label_power, format.watts(watts), POWER_TEMPLATE)
            LabeledSlot(R.string.gauge_label_voltage, format.millivolts(voltageMv), VOLTAGE_TEMPLATE)
            LabeledSlot(R.string.gauge_label_temperature, format.celsius(temperatureC, decimals = 1), TEMPERATURE_TEMPLATE)
        }
        // Always one line tall, so the card keeps its height whether or not an estimate exists.
        Text(
            text = remaining?.takeIf { it.isNotEmpty() } ?: NO_BREAK_SPACE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.md),
        )
    }
}

private const val NO_BREAK_SPACE = " "

@Composable
private fun LabeledSlot(labelRes: Int, reading: FormattedValue, template: String) {
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlotValue(reading = reading, template = template, style = MaterialTheme.numericTypography.body)
    }
}

/** A value left-aligned in a slot at least as wide as [template] in [style], followed by its unit. */
@Composable
private fun SlotValue(
    reading: FormattedValue,
    template: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotWidth = remember(template, style, density, measurer) {
        with(density) { measurer.measure(template, style, maxLines = 1, softWrap = false).size.width.toDp() }
    }
    Row(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = reading.value,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            maxLines = 1,
            softWrap = false,
            // A minimum, not a fixed width: an unexpectedly long reading grows the slot instead of clipping.
            modifier = Modifier.widthIn(min = slotWidth).alignByBaseline(),
        )
        if (reading.unit.isNotEmpty()) {
            Text(
                text = reading.unit,
                style = MaterialTheme.numericTypography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline().padding(start = MaterialTheme.spacing.xs),
            )
        }
    }
}
