package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeTicksTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val istanbul: ZoneId = ZoneId.of("Europe/Istanbul")

    private fun ms(
        local: String,
        zone: ZoneId,
    ): Long =
        LocalDateTime
            .parse(local)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun local(
        tMs: Long,
        zone: ZoneId,
    ): LocalDateTime = Instant.ofEpochMilli(tMs).atZone(zone).toLocalDateTime()

    /** The tick instants alone, for the cases that don't check [NiceTimeTicks.dayAligned]. */
    private fun tickTimes(
        window: TimeRange,
        maxTicks: Int,
        zone: ZoneId,
    ): List<Long> = niceTimeTickSet(window, maxTicks, zone).ticksMs

    private fun assertStrictlyIncreasingInside(
        ticks: List<Long>,
        window: TimeRange,
    ) {
        assertTrue(ticks.all { it in window.startMs..window.endMs })
        assertTrue(ticks.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun fortyEightHourWindowCanFitSubDayStepAndReportsIt() {
        val window = TimeRange.of(ms("2026-10-16T18:00:00", utc), ms("2026-10-18T18:00:00", utc))
        val set = niceTimeTickSet(window, maxTicks = 4, zone = utc)
        assertEquals(false, set.dayAligned)
        assertEquals(
            listOf("2026-10-17T00:00", "2026-10-17T12:00", "2026-10-18T00:00", "2026-10-18T12:00"),
            set.ticksMs.map { local(it, utc).toString() },
        )
        val days = niceTimeTickSet(window, maxTicks = 2, zone = utc)
        assertEquals(true, days.dayAligned)
        assertEquals(listOf("2026-10-17T00:00", "2026-10-18T00:00"), days.ticksMs.map { local(it, utc).toString() })
    }

    @Test
    fun startOfDayFollowsTheZone() {
        assertTrue(isStartOfDay(ms("2026-10-17T00:00:00", istanbul), istanbul))
        assertEquals(false, isStartOfDay(ms("2026-10-17T00:00:00", istanbul), utc))
        assertEquals(false, isStartOfDay(ms("2026-10-17T12:00:00", utc), utc))
    }

    @Test
    fun thirtyMinuteWindowTicksAlignToFiveMinutes() {
        val window = TimeRange.of(ms("2025-06-01T10:07:00", utc), ms("2025-06-01T10:37:00", utc))
        val ticks = tickTimes(window, maxTicks = 6, zone = utc)
        assertEquals(6, ticks.size)
        assertTrue(ticks.all { local(it, utc).minute % 5 == 0 && local(it, utc).second == 0 })
        assertEquals(local(ticks.first(), utc), LocalDateTime.parse("2025-06-01T10:10:00"))
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun sixHourWindowUsesHourlyTicks() {
        val window = TimeRange.of(ms("2025-06-01T02:00:00", istanbul), ms("2025-06-01T08:00:00", istanbul))
        val ticks = tickTimes(window, maxTicks = 7, zone = istanbul)
        assertEquals(7, ticks.size)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 3_600_000L })
        assertEquals(window.startMs, ticks.first())
    }

    @Test
    fun twentyFourHourWindowInIstanbulHasNoDstAndUsesThreeHourSteps() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", istanbul), ms("2025-03-31T00:00:00", istanbul))
        val ticks = tickTimes(window, maxTicks = 9, zone = istanbul)
        assertEquals(9, ticks.size)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 3 * 3_600_000L })
        assertEquals(window.startMs, ticks.first())
        assertEquals(window.endMs, ticks.last())
    }

    @Test
    fun berlinSpringForwardDayStaysOnWallClockBoundaries() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", berlin), ms("2025-03-31T00:00:00", berlin))
        val ticks = tickTimes(window, maxTicks = 9, zone = berlin)
        assertEquals(9, ticks.size)
        assertTrue(ticks.all { local(it, berlin).hour % 3 == 0 && local(it, berlin).minute == 0 })
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21, 0), ticks.map { local(it, berlin).hour })
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinFallBackDayStaysOnWallClockBoundaries() {
        val window = TimeRange.of(ms("2025-10-26T00:00:00", berlin), ms("2025-10-27T00:00:00", berlin))
        val ticks = tickTimes(window, maxTicks = 9, zone = berlin)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21, 0), ticks.map { local(it, berlin).hour })
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinSecondRepeatedHourHasFiveTenMinuteTicksIncludingEndpoints() {
        val start = Instant.parse("2025-10-26T01:10:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 40 * 60_000L)
        val set = niceTimeTickSet(window, maxTicks = 5, zone = berlin)

        assertEquals(false, set.dayAligned)
        assertEquals((0..4).map { start + it * 10 * 60_000L }, set.ticksMs)
        assertStrictlyIncreasingInside(set.ticksMs, window)
    }

    @Test
    fun berlinBothRepeatedHoursHaveTwentyOneOrderedFiveMinuteInstants() {
        val start = Instant.parse("2025-10-26T00:10:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 100 * 60_000L)
        val set = niceTimeTickSet(window, maxTicks = 21, zone = berlin)

        assertEquals(false, set.dayAligned)
        assertEquals((0..20).map { start + it * 5 * 60_000L }, set.ticksMs)
        assertEquals(2, set.ticksMs.count { local(it, berlin) == LocalDateTime.parse("2025-10-26T02:10") })
        assertEquals(2, set.ticksMs.count { local(it, berlin) == LocalDateTime.parse("2025-10-26T02:50") })
        assertStrictlyIncreasingInside(set.ticksMs, window)
    }

    @Test
    fun berlinOverlapWindowWithReversedLocalEndpointsKeepsBothOccurrences() {
        val start = Instant.parse("2025-10-26T00:50:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 20 * 60_000L)
        val ticks = tickTimes(window, maxTicks = 5, zone = berlin)

        assertEquals((0..4).map { start + it * 5 * 60_000L }, ticks)
        assertEquals(listOf(50, 55, 0, 5, 10), ticks.map { local(it, berlin).minute })
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinOverlapCountsBothOccurrencesAgainstBudget() {
        val start = Instant.parse("2025-10-26T00:10:00Z").toEpochMilli()
        val window = TimeRange.of(start, start + 100 * 60_000L)
        val set = niceTimeTickSet(window, maxTicks = 11, zone = berlin)

        assertEquals(false, set.dayAligned)
        assertEquals((0..10).map { start + it * 10 * 60_000L }, set.ticksMs)
        for (budget in listOf(0, 1, 5, 11, 21)) {
            val ticks = tickTimes(window, maxTicks = budget, zone = berlin)
            assertTrue(ticks.size <= budget)
            assertStrictlyIncreasingInside(ticks, window)
        }
    }

    @Test
    fun berlinRepeatedHourExcludesTicksJustOutsideWindow() {
        val start = Instant.parse("2025-10-26T01:10:00Z").toEpochMilli()
        val window = TimeRange.of(start + 1L, start + 40 * 60_000L - 1L)
        val ticks = tickTimes(window, maxTicks = 5, zone = berlin)

        assertEquals((1..3).map { start + it * 10 * 60_000L }, ticks)
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun berlinSpringGapSkipsNonexistentTwoHourSlotRatherThanShiftingIt() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", berlin), ms("2025-03-30T06:00:00", berlin))
        val set = niceTimeTickSet(window, maxTicks = 4, zone = berlin)

        assertEquals(false, set.dayAligned)
        assertEquals(
            listOf("2025-03-29T23:00:00Z", "2025-03-30T02:00:00Z", "2025-03-30T04:00:00Z")
                .map { Instant.parse(it).toEpochMilli() },
            set.ticksMs,
        )
        assertEquals(listOf(0, 4, 6), set.ticksMs.map { local(it, berlin).hour })
        assertStrictlyIncreasingInside(set.ticksMs, window)
    }

    @Test
    fun berlinDstGapNeverDuplicatesOrReorders() {
        val window = TimeRange.of(ms("2025-03-30T00:00:00", berlin), ms("2025-03-30T12:00:00", berlin))
        val ticks = tickTimes(window, maxTicks = 8, zone = berlin)
        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.size <= 8)
        assertStrictlyIncreasingInside(ticks, window)
    }

    @Test
    fun sevenDayWindowUsesDailyTicksWhenBudgetAllows() {
        val window = TimeRange.of(ms("2025-03-03T00:00:00", istanbul), ms("2025-03-10T00:00:00", istanbul))
        val ticks = tickTimes(window, maxTicks = 8, zone = istanbul)
        assertEquals(8, ticks.size)
        assertTrue(ticks.all { local(it, istanbul).toLocalTime().toSecondOfDay() == 0 })
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 24 * 3_600_000L })
    }

    @Test
    fun sevenDayWindowFallsBackToCoarserStepsToStayInBudget() {
        val window = TimeRange.of(ms("2025-03-03T00:00:00", istanbul), ms("2025-03-10T00:00:00", istanbul))
        val four = tickTimes(window, maxTicks = 4, zone = istanbul)
        assertTrue(four.isNotEmpty() && four.size <= 4)
        assertStrictlyIncreasingInside(four, window)
        val three = tickTimes(window, maxTicks = 3, zone = istanbul)
        assertTrue(three.isNotEmpty() && three.size <= 3)
        assertStrictlyIncreasingInside(three, window)
    }

    @Test
    fun degenerateInputsGiveNoTicks() {
        val window = TimeRange.of(0L, 3_600_000L)
        assertTrue(tickTimes(window, maxTicks = 0, zone = utc).isEmpty())
        assertTrue(tickTimes(TimeRange.of(5L, 5L), maxTicks = 6, zone = utc).isEmpty())
    }
}
