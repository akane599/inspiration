package com.akane.inspiration.designsystem.component.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelemetryFormatTest {
    private val en = TelemetryFormat(Locale.US, TelemetryUnits.English)
    private val tr = TelemetryFormat(
        Locale.forLanguageTag("tr-TR"),
        TelemetryUnits(
            milliamps = "mA",
            watts = "W",
            millivolts = "mV",
            celsius = "°C",
            percentPerHour = "%/sa",
            milliampHoursPerHour = "mAh/sa",
            hour = "sa",
            minute = "dk",
        ),
    )

    private fun minutes(m: Long) = m * 60_000L

    @Test
    fun unitsRenderWithTheirUnitStringEn() {
        assertEquals("312 mA", en.milliamps(312).text)
        assertEquals("4.52 W", en.watts(4.516).text)
        assertEquals("4123 mV", en.millivolts(4123).text)
        assertEquals("3.2 %/h", en.percentPerHour(3.24).text)
        assertEquals("118 mAh/h", en.milliampHoursPerHour(117.6).text)
    }

    @Test
    fun celsiusRendersWithSpaceBeforeUnit() {
        assertEquals("45 °C", en.celsius(45.0).text)
        assertEquals("45", en.celsius(45.0).value)
        assertEquals("°C", en.celsius(45.0).unit)
        assertEquals("36.4 °C", en.celsius(36.4, decimals = 1).text)
    }

    @Test
    fun trUsesDecimalCommaAndTurkishUnits() {
        assertEquals("36,4 °C", tr.celsius(36.4, decimals = 1).text)
        assertEquals("4,52 W", tr.watts(4.516).text)
        assertEquals("3,2 %/sa", tr.percentPerHour(3.24).text)
        assertEquals("118 mAh/sa", tr.milliampHoursPerHour(117.6).text)
    }

    @Test
    fun negativesUseTypographicMinusNotHyphen() {
        assertEquals("−312 mA", en.milliamps(-312).text)
        assertEquals("−3,5", tr.number(-3.5, decimals = 1))
        assertEquals("−3.2 %/h", en.percentPerHour(-3.2).text)
        assertFalse(en.milliamps(-312).text.contains('-'))
    }

    @Test
    fun valueRoundingToZeroHasNoSign() {
        assertEquals("0.0", en.number(-0.04, decimals = 1))
        assertEquals("0", en.number(-0.4))
        assertEquals("0", en.number(-0.0))
    }

    @Test
    fun missingOrNonFiniteReadingKeepsItsUnit() {
        assertEquals("— mA", en.milliamps(null).text)
        assertEquals("— °C", en.celsius(null).text)
        assertEquals("—", en.number(Double.NaN))
        assertEquals("—", en.number(Double.POSITIVE_INFINITY, decimals = 2))
    }

    @Test
    fun durationPadsMinutesToTwoDigits() {
        assertEquals("1 h 05 min", en.duration(minutes(65)))
        assertEquals("1 sa 05 dk", tr.duration(minutes(65)))
        assertEquals("12 h 30 min", en.duration(minutes(12 * 60 + 30)))
    }

    @Test
    fun durationUnderAnHourIsMinutesOnly() {
        assertEquals("45 min", en.duration(minutes(45)))
        assertEquals("5 dk", tr.duration(minutes(5)))
        assertEquals("0 min", en.duration(0L))
    }

    @Test
    fun durationWholeHoursKeepZeroMinutes() {
        assertEquals("2 h 00 min", en.duration(minutes(120)))
    }

    @Test
    fun durationTruncatesToWholeMinutes() {
        assertEquals("1 h 05 min", en.duration(minutes(65) + 59_999L))
    }

    @Test
    fun negativeDurationGetsTypographicMinus() {
        assertEquals("−1 h 05 min", en.duration(-minutes(65)))
        assertEquals("0 min", en.duration(-30_000L))
    }
}
