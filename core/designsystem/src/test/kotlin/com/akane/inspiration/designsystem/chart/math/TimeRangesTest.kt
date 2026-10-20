package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeRangesTest {
    private val window = TimeRange.of(100L, 200L)

    @Test
    fun containsIncludesBothEnds() {
        assertTrue(100L in window)
        assertTrue(150L in window)
        assertTrue(200L in window)
        assertFalse(99L in window)
        assertFalse(201L in window)
    }

    @Test
    fun overlapsNeedsMoreThanATouchingEnd() {
        assertTrue(TimeRange.of(50L, 101L).overlaps(window))
        assertTrue(TimeRange.of(199L, 300L).overlaps(window))
        assertTrue(TimeRange.of(0L, 300L).overlaps(window))
        assertTrue(TimeRange.of(150L, 150L).overlaps(window))
        assertFalse(TimeRange.of(0L, 100L).overlaps(window))
        assertFalse(TimeRange.of(200L, 300L).overlaps(window))
        assertFalse(TimeRange.of(0L, 50L).overlaps(window))
        assertFalse(TimeRange.of(250L, 300L).overlaps(window))
    }

    @Test
    fun `dates show from 24 hours or when the ends fall on different local dates`() {
        val utc = ZoneId.of("UTC")
        val hour = 3_600_000L
        val morning = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
        assertFalse("within one local day", TimeRange.of(morning, morning + 23 * hour).showsDates(utc))
        assertTrue("an exact 24 h window", TimeRange.of(morning + hour, morning + 25 * hour).showsDates(utc))
        assertTrue("a 26 h window", TimeRange.of(morning + hour, morning + 27 * hour).showsDates(utc))
        // 22:00 to 01:00 is three hours, but says which night only with the dates.
        val lateEvening = Instant.parse("2026-10-16T22:00:00Z").toEpochMilli()
        assertTrue("crosses midnight", TimeRange.of(lateEvening, lateEvening + 3 * hour).showsDates(utc))
        // 19:00 to 21:00 UTC is 22:00 to 00:00 in Istanbul (UTC+3): one date in UTC, two there.
        val crossesInIstanbul = TimeRange.of(lateEvening - 3 * hour, lateEvening - hour)
        assertFalse(crossesInIstanbul.showsDates(utc))
        assertTrue(crossesInIstanbul.showsDates(ZoneId.of("Europe/Istanbul")))
    }
}
