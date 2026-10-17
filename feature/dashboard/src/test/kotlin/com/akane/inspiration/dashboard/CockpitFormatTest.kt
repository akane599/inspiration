package com.akane.inspiration.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class CockpitFormatTest {
    private val utc = ZoneId.of("UTC")
    private val turkish = Locale.forLanguageTag("tr")

    private fun ms(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `a same day range names the date once and the end as a bare time`() {
        val text = formatRange(ms(2026, 10, 16, 14, 10), ms(2026, 10, 16, 16, 20), turkish, utc)

        assertEquals("16.10.2026 14:10 – 16:20", text)
    }

    @Test
    fun `a range over midnight repeats the date on the end`() {
        val text = formatRange(ms(2026, 10, 16, 23, 0), ms(2026, 10, 17, 1, 30), turkish, utc)

        assertEquals("16.10.2026 23:00 – 17.10.2026 01:30", text)
    }

    @Test
    fun `the zone decides the wall time`() {
        val istanbul = ZoneId.of("Europe/Istanbul")
        val start = ms(2026, 10, 16, 21, 30)

        val text = formatRange(start, start + 30 * MIN_MS, turkish, istanbul)

        assertTrue(text, text.startsWith("17.10.2026 00:30"))
        assertFalse(text, text.contains("21:30"))
    }

    @Test
    fun `a bare time follows the locale`() {
        assertEquals("14:10", formatTime(ms(2026, 10, 16, 14, 10), turkish, utc))
    }
}
