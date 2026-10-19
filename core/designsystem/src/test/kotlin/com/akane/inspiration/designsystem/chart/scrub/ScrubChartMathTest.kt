package com.akane.inspiration.designsystem.chart.scrub

import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubChartMathTest {

    private fun p(t: Long, v: Float) = TimePoint(t, v)

    @Test
    fun `splitAtThreshold returns no runs for empty input`() {
        assertTrue(splitAtThreshold(emptyList(), 40f).isEmpty())
    }

    @Test
    fun `splitAtThreshold keeps a series below the threshold as one normal run`() {
        val runs = splitAtThreshold(listOf(p(0, 30f), p(10, 35f), p(20, 39.9f)), 40f)
        assertEquals(1, runs.size)
        assertFalse(runs[0].critical)
        assertEquals(3, runs[0].points.size)
    }

    @Test
    fun `splitAtThreshold treats exactly the threshold as critical`() {
        val runs = splitAtThreshold(listOf(p(0, 40f), p(10, 41f)), 40f)
        assertEquals(1, runs.size)
        assertTrue(runs[0].critical)
    }

    @Test
    fun `splitAtThreshold splits an excursion into normal, critical, normal runs joined at the crossings`() {
        val runs = splitAtThreshold(listOf(p(0, 30f), p(100, 50f), p(200, 50f), p(300, 30f)), 40f)
        assertEquals(listOf(false, true, false), runs.map { it.critical })
        // Rising crossing at the midpoint of 30 -> 50, falling crossing at the midpoint of 50 -> 30.
        val rising = TimePoint(50, 40f)
        val falling = TimePoint(250, 40f)
        assertEquals(listOf(p(0, 30f), rising), runs[0].points)
        assertEquals(listOf(rising, p(100, 50f), p(200, 50f), falling), runs[1].points)
        assertEquals(listOf(falling, p(300, 30f)), runs[2].points)
    }

    @Test
    fun `splitAtThreshold with a single point yields one single-point run`() {
        val runs = splitAtThreshold(listOf(p(5, 45f)), 40f)
        assertEquals(1, runs.size)
        assertTrue(runs[0].critical)
        assertEquals(1, runs[0].points.size)
    }

    @Test
    fun `temperatureAxis labels are multiples of 5 and cover the data`() {
        val axis = temperatureAxis(28f, 44f)
        assertEquals(25f, axis.min, 0f)
        assertEquals(45f, axis.max, 0f)
        assertEquals(35f, axis.mid, 0f)
    }

    @Test
    fun `temperatureAxis enforces a span of at least 10 for flat data`() {
        val axis = temperatureAxis(31f, 31f)
        assertEquals(30f, axis.min, 0f)
        assertEquals(40f, axis.max, 0f)
    }

    @Test
    fun `temperatureAxis widens in steps when the data spread needs it`() {
        val axis = temperatureAxis(20f, 61f)
        assertEquals(20f, axis.min, 0f)
        assertTrue(axis.max >= 61f)
        assertEquals(0f, (axis.max - axis.min) % 10f, 0f)
    }

    @Test
    fun `interpolateAt is NaN outside the span and on empty arrays`() {
        val times = longArrayOf(10, 20)
        val values = floatArrayOf(1f, 3f)
        assertTrue(interpolateAt(times, values, 9).isNaN())
        assertTrue(interpolateAt(times, values, 21).isNaN())
        assertTrue(interpolateAt(LongArray(0), FloatArray(0), 5).isNaN())
    }

    @Test
    fun `interpolateAt returns exact values at points and linear values between`() {
        val times = longArrayOf(0, 100, 200)
        val values = floatArrayOf(10f, 20f, 0f)
        assertEquals(10f, interpolateAt(times, values, 0), 0f)
        assertEquals(20f, interpolateAt(times, values, 100), 0f)
        assertEquals(15f, interpolateAt(times, values, 50), 1e-4f)
        assertEquals(10f, interpolateAt(times, values, 150), 1e-4f)
        assertEquals(0f, interpolateAt(times, values, 200), 0f)
    }

    @Test
    fun `window flags flip at one and two days`() {
        val day = 24 * 3_600_000L
        assertFalse(TimeRange.of(0, day - 1).spansDays())
        assertTrue("an exact 24 h window shows dates", TimeRange.of(0, day).spansDays())
        assertTrue(TimeRange.of(0, day + 1).spansDays())
        assertFalse(TimeRange.of(0, 2 * day).usesDateTicks())
        assertTrue(TimeRange.of(0, 2 * day + 1).usesDateTicks())
    }

    @Test
    fun `formatClock uses the zone and optional date`() {
        // 2026-10-16T21:30:00Z
        val t = 1_792_186_200_000L
        assertEquals("21:30", formatClock(t, ZoneId.of("UTC"), withDate = false, locale = Locale.ENGLISH))
        assertEquals("00:30", formatClock(t, ZoneId.of("Europe/Istanbul"), withDate = false, locale = Locale.ENGLISH))
        // No-break spaces between the date parts and the time, so the readout title never wraps inside a date-time.
        assertEquals("16 Oct 21:30", formatClock(t, ZoneId.of("UTC"), withDate = true, locale = Locale.ENGLISH))
    }

    @Test
    fun `formatTickLabel shows clock time for short windows and the date for long ones`() {
        val t = 1_792_186_200_000L
        val zone = ZoneId.of("UTC")
        val oneDay = TimeRange.of(t, t + 24 * 3_600_000L)
        val week = TimeRange.of(t, t + 7 * 24 * 3_600_000L)
        assertEquals("21:30", formatTickLabel(t, zone, oneDay, Locale.ENGLISH))
        assertEquals("16\u00a0Oct", formatTickLabel(t, zone, week, Locale.ENGLISH))
    }

    @Test
    fun `clock and tick labels follow each locale and stay the same on repeated calls`() {
        val t = 1_792_186_200_000L
        val zone = ZoneId.of("UTC")
        val tr = Locale.forLanguageTag("tr-TR")
        val week = TimeRange.of(t, t + 7 * 24 * 3_600_000L)
        repeat(3) {
            assertEquals("16\u00a0Eki\u00a021:30", formatClock(t, zone, withDate = true, locale = tr))
            assertEquals("16\u00a0Oct\u00a021:30", formatClock(t, zone, withDate = true, locale = Locale.ENGLISH))
            assertEquals("21:30", formatClock(t, zone, withDate = false, locale = tr))
            assertEquals("16\u00a0Eki", formatTickLabel(t, zone, week, tr))
            assertEquals("16\u00a0Oct", formatTickLabel(t, zone, week, Locale.ENGLISH))
        }
    }
}
