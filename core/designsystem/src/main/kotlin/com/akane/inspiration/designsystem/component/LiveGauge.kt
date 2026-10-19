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
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.rememberTelemetryFormat
import com.akane.inspiration.designsystem.theme.numericTypography
import com.akane.inspiration.designsystem.theme.spacing
import com.akane.inspiration.designsystem.theme.statusColors
import com.akane.inspiration.designsystem.theme.telemetryColors

// Typical reading per slot (a minus sign or an extra digit just grows the slot). Tabular figures make every digit
// equally wide, so the template keeps the unit in place while digits change, and a typical value fills the slot
// so there is no gap between it and its unit ("1.65 W", not "1.65   W").
private const val CURRENT_TEMPLATE = "888"
private const val POWER_TEMPLATE = "8.88"
private const val VOLTAGE_TEMPLATE = "8888"
private const val TEMPERATURE_TEMPLATE = "88.8"

/** What the battery is doing, as the live gauge's status line words it. */
enum class GaugeChargeState {
    Charging,
    Discharging,

    /** A charger is connected but the battery is not charging: full, held at a charge limit, or losing to the load. */
    PluggedNotCharging,
}

/**
 * The live battery reading: current as the headline, then power, voltage and temperature, then an optional
 * [remaining] line ("3 h 20 min left", already worded by the caller; omitted, with no space left for it, when
 * null or empty). Numbers go through `TelemetryFormat`, and
 * a value sits in a slot at least as wide as its typical reading, so the gauge does not jump as digits change.
 * Pass [currentMa] negative while discharging.
 *
 * A null reading shows a plain dash at body size, with no slot and no unit. [chargeState] is null when the
 * charging state is unknown (no battery data at all): the status then reads "No reading", never "Discharging".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveGauge(
    currentMa: Int?,
    watts: Double?,
    voltageMv: Int?,
    temperatureC: Double?,
    remaining: String?,
    chargeState: GaugeChargeState?,
    modifier: Modifier = Modifier,
) {
    val format = rememberTelemetryFormat()
    val spacing = MaterialTheme.spacing
    InspirationCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SemanticDot(
                color =
                    when (chargeState) {
                        GaugeChargeState.Charging -> MaterialTheme.telemetryColors.charging

                        // Plugged in but not charging: the battery gains nothing, like a discharging one.
                        GaugeChargeState.Discharging,
                        GaugeChargeState.PluggedNotCharging,
                        -> MaterialTheme.colorScheme.onSurfaceVariant

                        null -> MaterialTheme.statusColors.neutral
                    },
            )
            Text(
                text =
                    stringResource(
                        when (chargeState) {
                            GaugeChargeState.Charging -> R.string.gauge_charging
                            GaugeChargeState.Discharging -> R.string.gauge_discharging
                            GaugeChargeState.PluggedNotCharging -> R.string.gauge_plugged_not_charging
                            null -> R.string.gauge_no_reading
                        },
                    ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        SlotValue(
            reading = format.milliamps(currentMa),
            template = CURRENT_TEMPLATE,
            style = MaterialTheme.numericTypography.display,
            missingStyle = MaterialTheme.numericTypography.title,
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
        // No estimate, no line: an empty reserved line would leave extra space under the values.
        remaining?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
    }
}

@Composable
private fun LabeledSlot(
    labelRes: Int,
    reading: FormattedValue,
    template: String,
) {
    Column {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlotValue(
            reading = reading,
            template = template,
            style = MaterialTheme.numericTypography.body,
            missingStyle = MaterialTheme.numericTypography.body,
        )
    }
}

/**
 * A value left-aligned in a slot at least as wide as [template] in [style], followed by its unit. A missing
 * reading is just a dash in [missingStyle]: no slot width, no unit.
 */
@Composable
private fun SlotValue(
    reading: FormattedValue,
    template: String,
    style: TextStyle,
    missingStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (reading.value == NO_VALUE) {
        Text(
            text = NO_VALUE,
            style = missingStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotWidth =
        remember(template, style, density, measurer) {
            with(density) {
                measurer
                    .measure(template, style, maxLines = 1, softWrap = false)
                    .size.width
                    .toDp()
            }
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
