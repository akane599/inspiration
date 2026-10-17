package com.akane.inspiration.designsystem.chart.hypnogram

import com.akane.inspiration.designsystem.chart.model.HypnogramData
import com.akane.inspiration.designsystem.chart.model.HypnogramStage
import com.akane.inspiration.designsystem.chart.model.StageSegment
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HypnogramMathTest {
    private val window = TimeRange.of(0L, 1_000L)

    private fun data(vararg segments: StageSegment) = HypnogramData.of(window, segments.toList(), emptyList())

    private fun seg(start: Long, end: Long, stage: HypnogramStage) = StageSegment(start, end, stage)

    @Test
    fun noSegmentsMakesTheWholeWindowOneGap() {
        assertEquals(listOf(window), hypnogramGaps(data()))
    }

    @Test
    fun touchingSegmentsLeaveNoGap() {
        val gaps = hypnogramGaps(
            data(seg(0, 400, HypnogramStage.LIGHT_IDLE), seg(400, 1_000, HypnogramStage.DEEP_IDLE)),
        )
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun leadingMiddleAndTrailingGapsAreFound() {
        val gaps = hypnogramGaps(
            data(seg(100, 300, HypnogramStage.LIGHT_IDLE), seg(500, 800, HypnogramStage.DEEP_IDLE)),
        )
        assertEquals(listOf(TimeRange.of(0, 100), TimeRange.of(300, 500), TimeRange.of(800, 1_000)), gaps)
    }

    @Test
    fun overlapAcrossStagesDoesNotCreateOrHideGaps() {
        val gaps = hypnogramGaps(
            data(
                seg(0, 600, HypnogramStage.LIGHT_IDLE),
                seg(200, 500, HypnogramStage.MAINTENANCE),
                seg(700, 1_000, HypnogramStage.DEEP_IDLE),
            ),
        )
        assertEquals(listOf(TimeRange.of(600, 700)), gaps)
    }

    @Test
    fun segmentsOutsideTheWindowAreClipped() {
        val gaps = hypnogramGaps(
            data(seg(-500, 200, HypnogramStage.LIGHT_IDLE), seg(900, 5_000, HypnogramStage.DEEP_IDLE)),
        )
        assertEquals(listOf(TimeRange.of(200, 900)), gaps)
    }

    @Test
    fun zeroLengthWindowHasNoGapsAndNoDurations() {
        val empty = HypnogramData.of(TimeRange.of(5, 5), emptyList(), emptyList())
        assertTrue(hypnogramGaps(empty).isEmpty())
        assertTrue(stageDurationsMs(empty).all { it == 0L })
    }

    @Test
    fun stageDurationsSumPerStageAndClipToTheWindow() {
        val totals = stageDurationsMs(
            data(
                seg(-100, 100, HypnogramStage.AWAKE),
                seg(100, 400, HypnogramStage.LIGHT_IDLE),
                seg(400, 500, HypnogramStage.MAINTENANCE),
                seg(500, 700, HypnogramStage.LIGHT_IDLE),
                seg(700, 1_200, HypnogramStage.DEEP_IDLE),
            ),
        )
        assertEquals(100L, totals[HypnogramStage.AWAKE.ordinal])
        assertEquals(100L, totals[HypnogramStage.MAINTENANCE.ordinal])
        assertEquals(500L, totals[HypnogramStage.LIGHT_IDLE.ordinal])
        assertEquals(300L, totals[HypnogramStage.DEEP_IDLE.ordinal])
    }

    @Test
    fun overlappingSegmentsOfOneStageCountOnce() {
        val totals = stageDurationsMs(
            data(seg(0, 600, HypnogramStage.DEEP_IDLE), seg(400, 800, HypnogramStage.DEEP_IDLE)),
        )
        assertEquals(800L, totals[HypnogramStage.DEEP_IDLE.ordinal])
    }
}
