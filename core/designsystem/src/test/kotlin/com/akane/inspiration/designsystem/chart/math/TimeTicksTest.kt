package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeTicksTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val istanbul: ZoneId = ZoneId.of("Europe/Istanbul")

    private fun ms(local: String, zone: ZoneId): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun local(tMs: Long, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(tMs).atZone(zone).toLocalDateTime()

    private fun assertStrictlyIncreasingInside(ticks: List<Long>, window: TimeRange) {
        assertTrue(ticks.all { it in window.startMs..window.endMs })
        assertTrue(ticks.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun thirtyMinuteWindowTicksAlignToFiveMinutes() {
        val window = TimeRange.of(ms("2025-06-01T10:07:00", utc), ms("2025-06-01T10:37:00", utc))
        val ticks = niceTimeTicks(window, maxTicks = 6, zone = utc)
        assertEquals(6, ticks.size)
        assertTrue(ticks.all { local(it, utc).minute % 5 == 0 && local(it, utc).second == 0 })
        assertEquals(local(ticks.first(), utc), LocalDateTime.parse("2025-06-01T10:10:00"))
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun sixHourWindowUsesHourlyTicks() {
        val window = TimeRange.of(ms("2025-06-01T02:00:00", istanbul), ms("2025-06-01T08:00:00", istanbul))
        val ticks = niceTimeTicks(window, maxTicks = 7, zone = istanbul)
        assertEquals(7, ticks.size)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 3_600_000L })
        assertEquals(window.startMs, ticks.first())
    }

    @Test
    fun twentyFourHourWindowInIstanbulHasNoDstAndUsesThreeHourSteps() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", istanbul), ms("2025-03-31T00:00:00", istanbul))
        val ticks = niceTimeTicks(window, maxTicks = 9, zone = istanbul)
        assertEquals(9, ticks.size)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 3 * 3_600_000L })
        assertEquals(window.startMs, ticks.first())
        assertEquals(window.endMs, ticks.last())
    }

    @Test
    fun berlinSpringForwardDayStaysOnWallClockBoundaries() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", berlin), ms("2025-03-31T00:00:00", berlin))
        val ticks = niceTimeTicks(window, maxTicks = 9, zone = berlin)
        assertEquals(9, ticks.size)
        assertTrue(ticks.all { local(it, berlin).hour % 3 == 0 && local(it, berlin).minute == 0 })
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21, 0), ticks.map { local(it, berlin).hour })
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinFallBackDayStaysOnWallClockBoundaries() {
        val window = TimeRange.of(ms("2025-10-26T00:00:00", berlin), ms("2025-10-27T00:00:00", berlin))
        val ticks = niceTimeTicks(window, maxTicks = 9, zone = berlin)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21, 0), ticks.map { local(it, berlin).hour })
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinDstGapNeverDuplicatesOrReorders() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", berlin), ms("2025-03-30T12:00:00", berlin))
        val ticks = niceTimeTicks(window, maxTicks = 8, zone = berlin)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.size <= 8)
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun sevenDayWindowUsesDailyTicksWhenBudgetAllows() {
        val window = TimeRange.of(ms("2025-03-03T00:00:00", istanbul), ms("2025-03-10T00:00:00", istanbul))
        val ticks = niceTimeTicks(window, maxTicks = 8, zone = istanbul)
        assertEquals(8, ticks.size)
        assertTrue(ticks.all { local(it, istanbul).toLocalTime().toSecondOfDay() == 0 })
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 24 * 3_600_000L })
    }

    @Test
    fun sevenDayWindowFallsBackToCoarserStepsToStayInBudget() {
        val window = TimeRange.of(ms("2025-03-03T00:00:00", istanbul), ms("2025-03-10T00:00:00", istanbul))
        val four = niceTimeTicks(window, maxTicks = 4, zone = istanbul)
        assertTrue(four.isNotEmpty() && four.size <= 4)
        assertStrictlyIncreasingInside(four, window)
        val three = niceTimeTicks(window, maxTicks = 3, zone = istanbul)
        assertTrue(three.isNotEmpty() && three.size <= 3)
        assertStrictlyIncreasingInside(three, window)
    }

    @Test
    fun degenerateInputsGiveNoTicks() {
        val window = TimeRange.of(0L, 3_600_000L)
        assertTrue(niceTimeTicks(window, maxTicks = 0, zone = utc).isEmpty())
        assertTrue(niceTimeTicks(TimeRange.of(5L, 5L), maxTicks = 6, zone = utc).isEmpty())
    }
}
