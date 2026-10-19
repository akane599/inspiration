package com.akane.inspiration.designsystem.chart.hypnogram

import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepHypnogramClockTest {
    private val t = Instant.parse("2026-10-16T21:30:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private val night = TimeRange.of(t, t + 8 * 3_600_000L)
    private val week = TimeRange.of(t, t + 7 * 24 * 3_600_000L)
    private val tr = Locale.forLanguageTag("tr-TR")

    private fun label(window: TimeRange, locale: Locale) =
        clockFormatter(window, locale).format(Instant.ofEpochMilli(t).atZone(utc))

    private fun withDefaultLocale(default: Locale, block: () -> Unit) {
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
        withDefaultLocale(Locale.US) { assertEquals("16 Eki", label(week, tr)) }
        withDefaultLocale(tr) { assertEquals("16 Oct", label(week, Locale.ENGLISH)) }
    }

    @Test
    fun `short windows show the 24-hour time in every locale`() {
        assertEquals("21:30", label(night, Locale.ENGLISH))
        assertEquals("21:30", label(night, tr))
    }

    @Test
    fun `the date switch happens at exactly two days`() {
        val twoDays = 2 * 24 * 3_600_000L
        assertEquals("21:30", label(TimeRange.of(t, t + twoDays - 1), Locale.ENGLISH))
        assertEquals("16 Oct", label(TimeRange.of(t, t + twoDays), Locale.ENGLISH))
    }
}
