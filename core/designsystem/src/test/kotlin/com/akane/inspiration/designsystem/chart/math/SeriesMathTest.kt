package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimePoint
import com.akane.inspiration.designsystem.chart.model.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMathTest {
    private val window = TimeRange.of(0L, 1_000_000L)
    private val scale = TimeScale(window, 100f)

    private fun points(vararg tv: Pair<Long, Float>) = tv.map { TimePoint(it.first, it.second) }

    @Test
    fun timeScaleRoundTripsAndHandlesDegenerateWindows() {
        assertEquals(0f, scale.toX(0L), 0f)
        assertEquals(50f, scale.toX(500_000L), 1e-3f)
        assertEquals(100f, scale.toX(1_000_000L), 1e-3f)
        assertEquals(250_000L, scale.toTime(25f))
        assertEquals(0f, TimeScale(TimeRange.of(5L, 5L), 100f).toX(5L), 0f)
        assertEquals(0L, TimeScale(window, 0f).toTime(10f))
    }

    @Test
    fun valueScaleFlipsYAndCentersFlatRange() {
        val v = ValueScale(0f, 100f, 200f)
        assertEquals(200f, v.toY(0f), 1e-3f)
        assertEquals(0f, v.toY(100f), 1e-3f)
        assertEquals(100f, v.toY(50f), 1e-3f)
        assertEquals(100f, ValueScale(5f, 5f, 200f).toY(5f), 1e-3f)
    }

    @Test
    fun decimationBoundsOutputAndKeepsFirstAndLast() {
        val input = (0 until 10_000).map { TimePoint(it * 100L, ((it * 37) % 101).toFloat()) }
        val out = decimateMinMax(input, scale, buckets = 100)
        assertTrue("size ${out.size}", out.size <= 2 * 100 + 2)
        assertEquals(input.first(), out.first())
        assertEquals(input.last(), out.last())
        assertTrue(out.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
        assertEquals(input.maxOf { it.value }, out.maxOf { it.value }, 0f)
        assertEquals(input.minOf { it.value }, out.minOf { it.value }, 0f)
    }

    @Test
    fun decimationKeepsSingleSpikeAndDipInsideABucket() {
        val input = (0 until 1_000).map { i ->
            TimePoint(i * 1_000L, when (i) { 503 -> 99f; 507 -> -40f; else -> 50f })
        }
        val out = decimateMinMax(input, scale, buckets = 10)
        assertTrue(out.any { it.value == 99f })
        assertTrue(out.any { it.value == -40f })
        assertTrue(out.size <= 22)
    }

    @Test
    fun decimationReturnsSmallInputAndGuardsBadArguments() {
        val small = points(0L to 1f, 1_000L to 2f, 2_000L to 3f)
        assertSame(small, decimateMinMax(small, scale, buckets = 100))
        val many = (0 until 500).map { TimePoint(it * 1_000L, it.toFloat()) }
        assertSame(many, decimateMinMax(many, scale, buckets = 0))
        assertSame(many, decimateMinMax(many, TimeScale(window, 0f), buckets = 10))
        assertTrue(decimateMinMax(emptyList(), scale, buckets = 10).isEmpty())
    }

    @Test
    fun decimationCollapsesPointsOutsideTheWindowIntoEdgeBuckets() {
        val input = (0 until 400).map { TimePoint(-500_000L + it * 5_000L, (it % 7).toFloat()) }
        val out = decimateMinMax(input, scale, buckets = 10)
        assertTrue(out.size <= 22)
        assertEquals(input.first(), out.first())
        assertEquals(input.last(), out.last())
    }

    @Test
    fun nearestIndexEdges() {
        val p = points(100L to 1f, 200L to 2f, 400L to 3f)
        assertEquals(-1, nearestIndex(emptyList(), 5L))
        assertEquals(0, nearestIndex(p, -50L))
        assertEquals(2, nearestIndex(p, 10_000L))
        assertEquals(1, nearestIndex(p, 200L))
        assertEquals(0, nearestIndex(p, 149L))
        assertEquals(1, nearestIndex(p, 151L))
        assertEquals(1, nearestIndex(p, 300L)) // tie goes to the earlier point
        assertEquals(2, nearestIndex(p, 301L))
        assertEquals(0, nearestIndex(points(7L to 1f), 999L))
    }

    @Test
    fun valueAtEdges() {
        val p = points(100L to 10f, 200L to 20f, 400L to 60f)
        assertNull(valueAt(emptyList(), 5L))
        assertNull(valueAt(p, 99L))
        assertNull(valueAt(p, 401L))
        assertEquals(10f, valueAt(p, 100L)!!, 0f)
        assertEquals(20f, valueAt(p, 200L)!!, 0f)
        assertEquals(60f, valueAt(p, 400L)!!, 0f)
        assertEquals(15f, valueAt(p, 150L)!!, 1e-4f)
        assertEquals(40f, valueAt(p, 300L)!!, 1e-4f)
        assertEquals(5f, valueAt(points(50L to 5f), 50L)!!, 0f)
    }

    @Test
    fun normalizeSelectionOrdersClampsAndEnforcesMinimum() {
        val w = TimeRange.of(1_000L, 11_000L)
        assertEquals(TimeRange.of(2_000L, 8_000L), normalizeSelection(8_000L, 2_000L, w, 1_000L))
        assertEquals(TimeRange.of(1_000L, 11_000L), normalizeSelection(-5_000L, 99_000L, w, 1_000L))
        // Too short: widened to the minimum, staying inside the window.
        assertEquals(TimeRange.of(5_000L, 6_000L), normalizeSelection(5_000L, 5_200L, w, 1_000L))
        assertEquals(TimeRange.of(10_000L, 11_000L), normalizeSelection(10_900L, 99_000L, w, 1_000L))
        assertEquals(TimeRange.of(1_000L, 2_000L), normalizeSelection(-9_000L, -8_000L, w, 1_000L))
        // Window shorter than the minimum.
        assertNull(normalizeSelection(1_000L, 2_000L, TimeRange.of(0L, 500L), 1_000L))
        assertNotNull(normalizeSelection(0L, 500L, TimeRange.of(0L, 500L), 500L))
    }
}
