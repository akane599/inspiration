package com.akane.inspiration.debug.gallery

import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class GalleryDataTest {
    private val data = GalleryDataGenerator.generate()

    @Test
    fun `each series has exactly 8640 sorted finite points at a 10 s cadence`() {
        assertEquals(listOf(SeriesKind.BATTERY_PERCENT, SeriesKind.TEMPERATURE_C), data.chart.series.map { it.kind })
        for (series in data.chart.series) {
            assertEquals(8_640, series.points.size)
            assertTrue(series.points.all { it.value.isFinite() })
            assertEquals(
                List(8_639) { GalleryDataGenerator.STEP_MS },
                series.points.zipWithNext { a, b -> b.tMs - a.tMs },
            )
            assertEquals(GalleryDataGenerator.WINDOW_START_MS, series.points.first().tMs)
            assertTrue(series.points.last().tMs < data.chart.window.endMs)
        }
    }

    @Test
    fun `window starts at midday UTC`() {
        val start = Instant.ofEpochMilli(GalleryDataGenerator.WINDOW_START_MS).atZone(ZoneOffset.UTC)
        assertEquals(12, start.hour)
        assertEquals(0, start.minute)
    }

    @Test
    fun `battery stays within percent bounds`() {
        val battery = data.chart.series.first { it.kind == SeriesKind.BATTERY_PERCENT }
        assertTrue(battery.points.all { it.value in 0f..100f })
    }

    @Test
    fun `window is 24 h and all four lanes toggle inside it`() {
        assertEquals(24 * 3_600_000L, data.chart.window.durationMs)
        assertEquals(LaneKind.entries, data.chart.lanes.map { it.kind })
        for (lane in data.chart.lanes) {
            assertTrue("${lane.kind} has segments", lane.segments.isNotEmpty())
            assertTrue(lane.segments.all { it.startMs >= data.chart.window.startMs && it.endMs <= data.chart.window.endMs })
        }
    }

    @Test
    fun `hypnogram covers the night with dense stages and 25 wake pulses`() {
        val night = GalleryDataGenerator.night
        assertEquals(night, data.hypnogram.window)
        assertEquals(GalleryDataGenerator.WAKE_PULSE_COUNT, data.hypnogram.wakePulsesMs.size)
        assertTrue(data.hypnogram.segments.size in 30..60)
        assertEquals(night.startMs, data.hypnogram.segments.first().startMs)
        assertEquals(night.endMs, data.hypnogram.segments.last().endMs)
        assertTrue(data.hypnogram.segments.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
    }

    @Test
    fun `same seed yields identical data`() {
        assertEquals(data, GalleryDataGenerator.generate(GalleryDataGenerator.SEED))
    }

    @Test
    fun `counter drain returns the tally and restarts from zero`() {
        val counter = RecompositionCounter()
        repeat(3) { counter.increment() }
        assertEquals(3, counter.drain())
        assertEquals(0, counter.drain())
    }

    @Test
    fun `gesture end line is the pinned json shape`() {
        assertEquals(
            """{"event":"gesture_end","parentRecompositions":2,"chartRecompositions":5}""",
            gestureEndLine(parentRecompositions = 2, chartRecompositions = 5),
        )
    }
}
