package com.akane.inspiration.designsystem.chart.hypnogram

import com.akane.inspiration.designsystem.chart.math.niceTimeTickSet
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SleepHypnogramClockTest {
    private val t = Instant.parse("2026-10-16T21:30:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private val night = TimeRange.of(t, t + 8 * 3_600_000L)
    private val week = TimeRange.of(t, t + 7 * 24 * 3_600_000L)
    private val tr = Locale.forLanguageTag("tr-TR")

    private fun label(
        window: TimeRange,
        locale: Locale,
    ) = clockFormatter(window, locale).format(Instant.ofEpochMilli(t).atZone(utc))

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
        withDefaultLocale(Locale.US) { assertEquals("16 Eki 21:30", label(week, tr)) }
        withDefaultLocale(tr) { assertEquals("16 Oct 21:30", label(week, Locale.ENGLISH)) }
    }

    @Test
    fun `short windows show the 24-hour time in every locale`() {
        assertEquals("21:30", label(night, Locale.ENGLISH))
        assertEquals("21:30", label(night, tr))
    }

    @Test
    fun `the a11y date switch happens at exactly two days and keeps the time of day`() {
        val twoDays = 2 * 24 * 3_600_000L
        assertEquals("21:30", label(TimeRange.of(t, t + twoDays - 1), Locale.ENGLISH))
        assertEquals("16 Oct 21:30", label(TimeRange.of(t, t + twoDays), Locale.ENGLISH))
    }

    @Test
    fun `a 48 h window with 12 h ticks labels them with times and dates at midnight`() {
        val start = Instant.parse("2026-10-16T18:00:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 48 * 3_600_000L)
        val set = niceTimeTickSet(window, maxTicks = 4, zone = utc)
        assertFalse(set.dayAligned)
        val labels = set.ticksMs.map { tickLabel(it, utc, window, set.dayAligned, Locale.ENGLISH) }
        assertEquals(listOf("17 Oct", "12:00", "18 Oct", "12:00"), labels)
    }

    @Test
    fun `a 48 h window with day ticks labels them with dates`() {
        val start = Instant.parse("2026-10-16T18:00:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 48 * 3_600_000L)
        val set = niceTimeTickSet(window, maxTicks = 2, zone = utc)
        assertTrue(set.dayAligned)
        assertEquals(
            listOf("17 Eki", "18 Eki"),
            set.ticksMs.map { tickLabel(it, utc, window, set.dayAligned, tr) },
        )
    }

    @Test
    fun `a short window keeps clock-time ticks even at midnight`() {
        val midnight = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        assertEquals("00:00", tickLabel(midnight, utc, night, dayAligned = false, locale = Locale.ENGLISH))
    }
}
