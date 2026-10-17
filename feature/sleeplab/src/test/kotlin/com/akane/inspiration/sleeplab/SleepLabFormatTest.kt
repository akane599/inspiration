package com.akane.inspiration.sleeplab

import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.TelemetryUnits
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class SleepLabFormatTest {
    private val en = TelemetryFormat(Locale.US, TelemetryUnits.English)
    private val tr = TelemetryFormat(Locale.forLanguageTag("tr"), TelemetryUnits.English.copy(hour = "sa", minute = "dk"))

    @Test
    fun `a wakelock under a minute is shown in seconds, not as zero minutes`() {
        assertEquals("12 s", formatSpan(en, 12_400, "s"))
        assertEquals("0 s", formatSpan(en, 0, "s"))
        assertEquals("59 s", formatSpan(en, 59_999, "s"))
        assertEquals("a negative span never shows a sign", "0 s", formatSpan(en, -5_000, "s"))
    }

    @Test
    fun `a wakelock of a minute or more uses the shared duration`() {
        assertEquals("1 min", formatSpan(en, 60_000, "s"))
        assertEquals("1 h 05 min", formatSpan(en, 65 * 60_000L, "s"))
        assertEquals("1 sa 05 dk", formatSpan(tr, 65 * 60_000L, "sn"))
        assertEquals("30 sn", formatSpan(tr, 30_000, "sn"))
    }

    @Test
    fun `frequencies are megahertz below a gigahertz and gigahertz above, with the locale's decimals`() {
        assertEquals("600 MHz", formatFrequency(en, 600_000, "MHz", "GHz"))
        assertEquals("708 MHz", formatFrequency(en, 708_000, "MHz", "GHz"))
        assertEquals("999 MHz", formatFrequency(en, 999_000, "MHz", "GHz"))
        assertEquals("1.00 GHz", formatFrequency(en, 1_000_000, "MHz", "GHz"))
        assertEquals("2.84 GHz", formatFrequency(en, 2_841_600, "MHz", "GHz"))
        assertEquals("2,84 GHz", formatFrequency(tr, 2_841_600, "MHz", "GHz"))
    }

    @Test
    fun `a share is a whole number percentage figure`() {
        assertEquals("42", formatShare(en, 0.42f))
        assertEquals("100", formatShare(en, 1f))
        assertEquals("0", formatShare(en, 0f))
    }

    @Test
    fun `counts saturate instead of overflowing a plural quantity`() {
        assertEquals(7, 7L.asQuantity())
        assertEquals(0, (-3L).asQuantity())
        assertEquals(Int.MAX_VALUE, Long.MAX_VALUE.asQuantity())
    }

    @Test
    fun `a range over one day shows the end as a bare time and one over midnight repeats the date`() {
        val zone = ZoneId.of("UTC")
        val sameDay = formatRange(1_760_000_000_000, 1_760_000_000_000 + 3_600_000, Locale.US, zone)
        val overMidnight = formatRange(1_760_000_000_000, 1_760_000_000_000 + 24 * 3_600_000L, Locale.US, zone)

        // "10/9/25, 7:33 AM – 8:33 AM": one date, then two times.
        assertEquals(1, Regex("""\d+/\d+/\d+""").findAll(sameDay).count())
        assertEquals(2, Regex("""\d+/\d+/\d+""").findAll(overMidnight).count())
    }
}
