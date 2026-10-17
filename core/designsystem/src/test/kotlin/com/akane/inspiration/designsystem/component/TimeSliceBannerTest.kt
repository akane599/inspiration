package com.akane.inspiration.designsystem.component

import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSliceBannerTest {
    private val minute = 60_000L
    private fun range(fromMin: Long, toMin: Long) = TimeRange.of(fromMin * minute, toMin * minute)

    @Test
    fun fullCoverageHasNoGap() {
        assertTrue(sliceGaps(range(0, 120), range(0, 120)).isEmpty())
    }

    @Test
    fun missingStartAndEndAreSeparateGaps() {
        val gaps = sliceGaps(range(0, 120), range(25, 100))
        assertEquals(listOf(range(0, 25), range(100, 120)), gaps)
    }

    @Test
    fun missingStartOnlyIsOneGapWithItsPosition() {
        assertEquals(listOf(range(0, 25)), sliceGaps(range(0, 120), range(25, 120)))
    }

    @Test
    fun noCoverageLeavesTheWholeRequestAsOneGap() {
        assertEquals(listOf(range(0, 120)), sliceGaps(range(0, 120), null))
    }

    @Test
    fun coverageOutsideTheRequestIsTheWholeRequestNotTwoOverlappingGaps() {
        assertEquals(listOf(range(0, 60)), sliceGaps(range(0, 60), range(90, 120)))
        assertEquals(listOf(range(100, 160)), sliceGaps(range(100, 160), range(0, 50)))
    }

    @Test
    fun coverageBeyondTheRequestIsNotAGap() {
        assertTrue(sliceGaps(range(30, 90), range(0, 120)).isEmpty())
    }

    @Test
    fun gapsUnderAMinuteAreIgnored() {
        val request = TimeRange.of(0, 120 * minute)
        val covered = TimeRange.of(59_000L, 120 * minute)
        assertTrue(sliceGaps(request, covered).isEmpty())
    }
}
