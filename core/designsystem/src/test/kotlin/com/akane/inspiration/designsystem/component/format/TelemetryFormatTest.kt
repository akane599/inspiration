package com.akane.inspiration.designsystem.component.format

import java.time.Instant
import java.time.ZoneId
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
        assertEquals("312\u00A0mA", en.milliamps(312).text)
        assertEquals("4.52\u00A0W", en.watts(4.516).text)
        assertEquals("4123\u00A0mV", en.millivolts(4123).text)
        assertEquals("3.2\u00A0%/h", en.percentPerHour(3.24).text)
        assertEquals("118\u00A0mAh/h", en.milliampHoursPerHour(117.6).text)
    }

    @Test
    fun celsiusRendersWithSpaceBeforeUnit() {
        assertEquals("45\u00A0°C", en.celsius(45.0).text)
        assertEquals("45", en.celsius(45.0).value)
        assertEquals("°C", en.celsius(45.0).unit)
        assertEquals("36.4\u00A0°C", en.celsius(36.4, decimals = 1).text)
    }

    @Test
    fun trUsesDecimalCommaAndTurkishUnits() {
        assertEquals("36,4\u00A0°C", tr.celsius(36.4, decimals = 1).text)
        assertEquals("4,52\u00A0W", tr.watts(4.516).text)
        assertEquals("3,2\u00A0%/sa", tr.percentPerHour(3.24).text)
        assertEquals("118\u00A0mAh/sa", tr.milliampHoursPerHour(117.6).text)
    }

    @Test
    fun negativesUseTypographicMinusNotHyphen() {
        assertEquals("−312\u00A0mA", en.milliamps(-312).text)
        assertEquals("−3,5", tr.number(-3.5, decimals = 1))
        assertEquals("−3.2\u00A0%/h", en.percentPerHour(-3.2).text)
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
        assertEquals("—\u00A0mA", en.milliamps(null).text)
        assertEquals("—\u00A0°C", en.celsius(null).text)
        assertEquals("—", en.number(Double.NaN))
        assertEquals("—", en.number(Double.POSITIVE_INFINITY, decimals = 2))
    }

    @Test
    fun durationPadsMinutesToTwoDigits() {
        assertEquals("1\u00A0h\u00A005\u00A0min", en.duration(minutes(65)))
        assertEquals("1\u00A0sa\u00A005\u00A0dk", tr.duration(minutes(65)))
        assertEquals("12\u00A0h\u00A030\u00A0min", en.duration(minutes(12 * 60 + 30)))
    }

    @Test
    fun durationUnderAnHourIsMinutesOnly() {
        assertEquals("45\u00A0min", en.duration(minutes(45)))
        assertEquals("5\u00A0dk", tr.duration(minutes(5)))
        assertEquals("0\u00A0min", en.duration(0L))
    }

    @Test
    fun durationWholeHoursKeepZeroMinutes() {
        assertEquals("2\u00A0h\u00A000\u00A0min", en.duration(minutes(120)))
    }

    @Test
    fun durationTruncatesToWholeMinutes() {
        assertEquals("1\u00A0h\u00A005\u00A0min", en.duration(minutes(65) + 59_999L))
    }

    @Test
    fun numberAndUnitNeverWrapApart() {
        val values = listOf(en.milliamps(-312), en.celsius(36.4, 1), en.millivolts(4410), en.watts(1.65))
        values.forEach { assertFalse("${it.text} has an ordinary space", it.text.contains(' ')) }
        assertFalse(en.duration(minutes(11 * 60 + 4)).contains(' '))
        assertFalse(tr.duration(minutes(11 * 60 + 4)).contains(' '))
    }

    private val utc = ZoneId.of("UTC")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun dateTimeIsDayMonthTimeJoinedByNoBreakSpaces() {
        assertEquals("17 Oct 00:00", en.dateTime(at("2026-10-17T00:00:00Z"), utc))
        assertEquals("16 Oct 13:30", en.dateTime(at("2026-10-16T13:30:00Z"), utc))
        assertFalse(en.dateTime(at("2026-10-16T13:30:00Z"), utc).contains(' '))
    }

    @Test
    fun dateTimeUsesTheLocalesMonthNames() {
        assertEquals("16 Eki 13:30", tr.dateTime(at("2026-10-16T13:30:00Z"), utc))
        assertEquals("2 Mar", tr.date(at("2026-03-02T08:00:00Z"), utc))
    }

    @Test
    fun dateTimeFollowsTheGivenZone() {
        val istanbul = ZoneId.of("Europe/Istanbul")
        assertEquals("16 Oct 16:30", en.dateTime(at("2026-10-16T13:30:00Z"), istanbul))
        assertEquals("17 Oct 00:30", en.dateTime(at("2026-10-16T21:30:00Z"), istanbul))
    }

    @Test
    fun rangeOnOneDayCollapsesTheRepeatedDate() {
        val range = en.range(at("2026-03-02T08:00:00Z"), at("2026-03-02T14:00:00Z"), utc)
        assertEquals("2 Mar 08:00–14:00", range)
        assertEquals("2 Mar 08:00–14:00", tr.range(at("2026-03-02T08:00:00Z"), at("2026-03-02T14:00:00Z"), utc))
    }

    @Test
    fun rangeAcrossDaysKeepsBothDates() {
        val range = en.range(at("2026-03-02T23:00:00Z"), at("2026-03-03T02:00:00Z"), utc)
        assertEquals("2 Mar 23:00 – 3 Mar 02:00", range)
    }

    @Test
    fun rangeInTheSameTimeOfDayOnDifferentDaysIsNotCollapsed() {
        val range = en.range(at("2026-03-02T08:00:00Z"), at("2026-04-02T14:00:00Z"), utc)
        assertEquals("2 Mar 08:00 – 2 Apr 14:00", range)
    }

    @Test
    fun rangeAcceptsEndsInEitherOrderAndEqualEnds() {
        val a = at("2026-03-02T08:00:00Z")
        val b = at("2026-03-02T14:00:00Z")
        assertEquals(en.range(a, b, utc), en.range(b, a, utc))
        assertEquals("2 Mar 08:00", en.range(a, a, utc))
    }

    @Test
    fun rangeCrossingMidnightInTheGivenZoneKeepsBothDates() {
        // 22:00-23:30Z is 01:00-02:30 the next day in Istanbul (UTC+3): same day there, so it collapses.
        val istanbul = ZoneId.of("Europe/Istanbul")
        assertEquals(
            "17 Oct 01:00–02:30",
            en.range(at("2026-10-16T22:00:00Z"), at("2026-10-16T23:30:00Z"), istanbul),
        )
        assertEquals(
            "16 Oct 23:30 – 17 Oct 00:30",
            en.range(at("2026-10-16T20:30:00Z"), at("2026-10-16T21:30:00Z"), istanbul),
        )
    }

    @Test
    fun negativeDurationGetsTypographicMinus() {
        assertEquals("−1\u00A0h\u00A005\u00A0min", en.duration(-minutes(65)))
        assertEquals("0\u00A0min", en.duration(-30_000L))
    }
}
