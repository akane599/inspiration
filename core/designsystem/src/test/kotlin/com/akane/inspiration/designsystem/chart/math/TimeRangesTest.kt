package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
