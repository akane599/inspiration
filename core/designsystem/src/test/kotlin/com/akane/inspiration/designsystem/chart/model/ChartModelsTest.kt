package com.akane.inspiration.designsystem.chart.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartModelsTest {
    @Test
    fun timeRangeOrdersEndsAndReportsDuration() {
        val r = TimeRange.of(9_000L, 4_000L)
        assertEquals(4_000L, r.startMs)
        assertEquals(9_000L, r.endMs)
        assertEquals(5_000L, r.durationMs)
        assertEquals(TimeRange.of(4_000L, 9_000L), r)
    }

    @Test
    fun lineSeriesDropsNonFiniteAndSortsByTime() {
        val s = LineSeries.of(
            SeriesKind.BATTERY_PERCENT,
            listOf(
                TimePoint(300L, 30f),
                TimePoint(100L, Float.NaN),
                TimePoint(200L, 20f),
                TimePoint(50L, Float.POSITIVE_INFINITY),
                TimePoint(10L, Float.NEGATIVE_INFINITY),
                TimePoint(100L, 10f),
            ),
        )
        assertEquals(listOf(100L, 200L, 300L), s.points.map { it.tMs })
        assertEquals(listOf(10f, 20f, 30f), s.points.map { it.value })
        assertEquals(SeriesKind.BATTERY_PERCENT, s.kind)
    }

    @Test
    fun lineSeriesAcceptsEmptyInput() {
        assertTrue(LineSeries.of(SeriesKind.TEMPERATURE_C, emptyList()).points.isEmpty())
    }

    @Test
    fun laneDropsBackwardsSegmentsAndSorts() {
        val lane = Lane.of(
            LaneKind.SCREEN_ON,
            listOf(LaneSegment(500L, 600L), LaneSegment(300L, 100L), LaneSegment(100L, 200L), LaneSegment(400L, 400L)),
        )
        assertEquals(listOf(100L, 400L, 500L), lane.segments.map { it.startMs })
        assertEquals(LaneKind.SCREEN_ON, lane.kind)
    }

    @Test
    fun hypnogramDropsBackwardsSegmentsAndSortsSegmentsAndPulses() {
        val window = TimeRange.of(1_000L, 0L)
        val data = HypnogramData.of(
            window,
            listOf(
                StageSegment(500L, 700L, HypnogramStage.DEEP_IDLE),
                StageSegment(900L, 800L, HypnogramStage.AWAKE),
                StageSegment(100L, 200L, HypnogramStage.LIGHT_IDLE),
            ),
            listOf(700L, 150L, 400L),
        )
        assertEquals(TimeRange.of(0L, 1_000L), data.window)
        assertEquals(listOf(HypnogramStage.LIGHT_IDLE, HypnogramStage.DEEP_IDLE), data.segments.map { it.stage })
        assertEquals(listOf(150L, 400L, 700L), data.wakePulsesMs)
    }

    @Test
    fun factoriesNeverThrowOnHostileInput() {
        val range = TimeRange.of(Long.MAX_VALUE, Long.MIN_VALUE)
        assertNotEquals(range.startMs, range.endMs)
        Lane.of(LaneKind.CHARGING, listOf(LaneSegment(Long.MAX_VALUE, Long.MIN_VALUE)))
        HypnogramData.of(range, emptyList(), emptyList())
    }
}
