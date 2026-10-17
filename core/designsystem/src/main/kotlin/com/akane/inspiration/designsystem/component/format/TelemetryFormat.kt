package com.akane.inspiration.designsystem.component.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.akane.inspiration.designsystem.R
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Typographic minus (U+2212): same width as the plus sign, so tabular columns stay aligned. */
const val MINUS_SIGN: Char = '−'

/** Shown instead of a number when a reading is unavailable. */
const val NO_VALUE: String = "—"

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L

/** The unit strings [TelemetryFormat] needs. Resolved from `strings_format.xml` by [rememberTelemetryFormat]. */
@Immutable
data class TelemetryUnits(
    val milliamps: String,
    val watts: String,
    val millivolts: String,
    val celsius: String,
    val percentPerHour: String,
    val milliampHoursPerHour: String,
    val hour: String,
    val minute: String,
) {
    companion object {
        /** The English units, for tests and previews that do not go through resources. */
        val English = TelemetryUnits(
            milliamps = "mA",
            watts = "W",
            millivolts = "mV",
            celsius = "°C",
            percentPerHour = "%/h",
            milliampHoursPerHour = "mAh/h",
            hour = "h",
            minute = "min",
        )
    }
}

/** A number and the unit that goes with it; [text] joins them ("45 °C"). */
@Immutable
data class FormattedValue(val value: String, val unit: String) {
    val text: String get() = if (unit.isEmpty()) value else "$value $unit"

    override fun toString(): String = text
}

/**
 * Locale-aware formatting of telemetry readings. Every function is pure: it depends only on [locale] and [units],
 * so en/tr behaviour is plain-JUnit testable. Screens never format inline; they call this.
 *
 * Negative numbers use [MINUS_SIGN], never the ASCII hyphen. A value that rounds to zero is shown without a sign,
 * and a null or non-finite reading becomes [NO_VALUE] with its unit kept so the layout does not change.
 */
@Immutable
data class TelemetryFormat(val locale: Locale, val units: TelemetryUnits) {

    /** [value] with [decimals] fraction digits in the locale's decimal separator and digits, never grouped. */
    fun number(value: Double?, decimals: Int = 0): String {
        if (value == null || !value.isFinite()) return NO_VALUE
        val body = numberFormat(decimals).format(abs(value))
        return if (value < 0 && body.hasNonZeroDigit()) MINUS_SIGN + body else body
    }

    fun milliamps(ma: Int?): FormattedValue = FormattedValue(number(ma?.toDouble()), units.milliamps)

    fun watts(watts: Double?): FormattedValue = FormattedValue(number(watts, decimals = 2), units.watts)

    fun millivolts(mv: Int?): FormattedValue = FormattedValue(number(mv?.toDouble()), units.millivolts)

    /** "45 °C" with the default whole degrees; pass [decimals] = 1 for the gauge. */
    fun celsius(celsius: Double?, decimals: Int = 0): FormattedValue =
        FormattedValue(number(celsius, decimals), units.celsius)

    fun percentPerHour(value: Double?): FormattedValue =
        FormattedValue(number(value, decimals = 1), units.percentPerHour)

    fun milliampHoursPerHour(value: Double?): FormattedValue =
        FormattedValue(number(value, decimals = 0), units.milliampHoursPerHour)

    /**
     * "1 h 05 min" (en) / "1 sa 05 dk" (tr). Whole minutes, truncated; under an hour it is just "45 min".
     * A negative duration gets [MINUS_SIGN].
     */
    fun duration(durationMs: Long): String {
        val totalMinutes = abs(durationMs) / MS_PER_MINUTE
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val body = if (hours > 0) {
            "${number(hours.toDouble())} ${units.hour} ${twoDigits(minutes)} ${units.minute}"
        } else {
            "${number(minutes.toDouble())} ${units.minute}"
        }
        return if (durationMs < 0 && totalMinutes > 0) MINUS_SIGN + body else body
    }

    private fun numberFormat(decimals: Int): NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            roundingMode = RoundingMode.HALF_UP
        }

    private fun twoDigits(value: Long): String =
        NumberFormat.getIntegerInstance(locale).apply {
            isGroupingUsed = false
            minimumIntegerDigits = 2
        }.format(value)
}

private fun String.hasNonZeroDigit(): Boolean = any { it.isDigit() && Character.digit(it, 10) != 0 }

/** A [TelemetryFormat] for the current locale, with the unit strings from `strings_format.xml`. */
@Composable
fun rememberTelemetryFormat(): TelemetryFormat {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val units = TelemetryUnits(
        milliamps = stringResource(R.string.telemetry_unit_ma),
        watts = stringResource(R.string.telemetry_unit_w),
        millivolts = stringResource(R.string.telemetry_unit_mv),
        celsius = stringResource(R.string.telemetry_unit_celsius),
        percentPerHour = stringResource(R.string.telemetry_unit_percent_per_hour),
        milliampHoursPerHour = stringResource(R.string.telemetry_unit_mah_per_hour),
        hour = stringResource(R.string.telemetry_unit_hour),
        minute = stringResource(R.string.telemetry_unit_minute),
    )
    return remember(locale, units) { TelemetryFormat(locale, units) }
}
