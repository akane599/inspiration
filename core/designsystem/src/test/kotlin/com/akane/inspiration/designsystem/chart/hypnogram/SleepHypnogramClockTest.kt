package com.akane.inspiration.designsystem.chart.hypnogram

import com.akane.inspiration.designsystem.chart.math.formatClock
import com.akane.inspiration.designsystem.chart.math.formatTickLabel
import com.akane.inspiration.designsystem.chart.math.niceTimeTickSet
import com.akane.inspiration.designsystem.chart.math.showsDates
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The hypnogram's a11y clock text and tick labels, which use the scrub chart's shared clock rule. */
class SleepHypnogramClockTest {
    private val t = Instant.parse("2026-10-16T21:30:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private val hour = 3_600_000L
    private val night = TimeRange.of(t, t + 8 * hour)
    private val evening = TimeRange.of(t, t + 2 * hour)
    private val week = TimeRange.of(t, t + 7 * 24 * hour)
    private val tr = Locale.forLanguageTag("tr-TR")

    /** How the hypnogram names [t] in its window and selection descriptions. */
    private fun label(
        window: TimeRange,
        locale: Locale,
        zone: ZoneId = utc,
    ) = formatClock(t, zone, withDate = window.showsDates(zone), locale = locale)

    private fun withDefaultLocale(
        default: Locale,
        block: () -> Unit,
    ) {
        val saved = Locale.getDefault()
        Locale.setDefault(default)
        try {
            block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `long windows use the given locale's month names, not the JVM default`() {
        withDefaultLocale(Locale.US) { assertEquals("16 Eki 21:30", label(week, tr)) }
        withDefaultLocale(tr) { assertEquals("16 Oct 21:30", label(week, Locale.ENGLISH)) }
    }

    @Test
    fun `windows within one local day show the 24-hour time in every locale`() {
        assertEquals("21:30", label(evening, Locale.ENGLISH))
        assertEquals("21:30", label(evening, tr))
    }

    @Test
    fun `a window whose ends fall on different local dates names the dates`() {
        // 21:30 to 05:30 crosses midnight: "Chart from 21:30 to 05:30" would not say which night.
        assertEquals("16 Oct 21:30", label(night, Locale.ENGLISH))
        assertEquals("16 Eki 21:30", label(night, tr))
        // The finding's case: a 26 h "since unplugged" window must not read as a two-hour chart.
        assertEquals("16 Oct 21:30", label(TimeRange.of(t, t + 26 * hour), Locale.ENGLISH))
        assertEquals("16 Oct 21:30", label(TimeRange.of(t, t + 24 * hour), Locale.ENGLISH))
    }

    @Test
    fun `the date decision uses the chart's zone`() {
        val istanbul = ZoneId.of("Europe/Istanbul")
        // 19:30 to 21:30 UTC is one date in UTC, but 22:30 to 00:30 in Istanbul (UTC+3): two dates there.
        val crossesInIstanbul = TimeRange.of(t - 2 * hour, t)
        assertEquals("21:30", label(crossesInIstanbul, Locale.ENGLISH))
        assertEquals("17 Oct 00:30", label(crossesInIstanbul, Locale.ENGLISH, istanbul))
        // 21:30 to 23:30 UTC is 00:30 to 02:30 of one date in Istanbul.
        assertEquals("00:30", label(evening, Locale.ENGLISH, istanbul))
    }

    @Test
    fun `a 48 h window with 12 h ticks labels them with times and dates at midnight`() {
        val start = Instant.parse("2026-10-16T18:00:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 48 * 3_600_000L)
        val set = niceTimeTickSet(window, maxTicks = 4, zone = utc)
        assertFalse(set.dayAligned)
        val labels = set.ticksMs.map { formatTickLabel(it, utc, window, set.dayAligned, Locale.ENGLISH) }
        assertEquals(listOf("17 Oct", "12:00", "18 Oct", "12:00"), labels)
    }

    @Test
    fun `a 48 h window with day ticks labels them with dates`() {
        val start = Instant.parse("2026-10-16T18:00:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 48 * 3_600_000L)
        val set = niceTimeTickSet(window, maxTicks = 2, zone = utc)
        assertTrue(set.dayAligned)
        assertEquals(
            listOf("17 Eki", "18 Eki"),
            set.ticksMs.map { formatTickLabel(it, utc, window, set.dayAligned, tr) },
        )
    }

    @Test
    fun `a short window keeps clock-time ticks even at midnight`() {
        val midnight = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        assertEquals("00:00", formatTickLabel(midnight, utc, night, dayAligned = false, locale = Locale.ENGLISH))
    }
}
