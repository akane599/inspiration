package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelinePoint
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.snapshot.SleepStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitMappersTest {

    // --- Timeline -> ScrubChartData ---

    private val timeline = Timeline(
        segments = listOf(
            TimelineSegment(SegmentKind.SCREEN_ON, 10, 20),
            TimelineSegment(SegmentKind.DEEP_IDLE, 30, 90),
            TimelineSegment(SegmentKind.LIGHT_IDLE, 20, 30),
            TimelineSegment(SegmentKind.MAINTENANCE, 90, 95),
            TimelineSegment(SegmentKind.AWAKE, 95, 99),
            TimelineSegment(SegmentKind.CHARGING, 0, 5),
        ),
        levelPoints = listOf(TimelinePoint(50, 70f), TimelinePoint(0, 80f), TimelinePoint(100, Float.NaN)),
        temperaturePoints = listOf(TimelinePoint(0, 31.5f), TimelinePoint(60, 33f)),
        wakePulsesMs = listOf(10L),
        from = 0,
        to = 100,
    )

    @Test
    fun `timeline window and both series map across, sorted and without non finite values`() {
        val data = scrubChartData(timeline)

        assertEquals(0L, data.window.startMs)
        assertEquals(100L, data.window.endMs)
        val battery = data.series.single { it.kind == SeriesKind.BATTERY_PERCENT }
        assertEquals(listOf(0L, 50L), battery.points.map { it.tMs })
        assertEquals(listOf(80f, 70f), battery.points.map { it.value })
        val temperature = data.series.single { it.kind == SeriesKind.TEMPERATURE_C }
        assertEquals(listOf(31.5f, 33f), temperature.points.map { it.value })
    }

    @Test
    fun `deep idle is the deep doze lane and light idle and maintenance stay off the lanes`() {
        val data = scrubChartData(timeline)

        fun spans(kind: LaneKind) = data.lanes.single { it.kind == kind }.segments.map { it.startMs to it.endMs }
        assertEquals(listOf(30L to 90L), spans(LaneKind.DEEP_SLEEP))
        assertEquals(listOf(10L to 20L), spans(LaneKind.SCREEN_ON))
        assertEquals(listOf(95L to 99L), spans(LaneKind.AWAKE))
        assertEquals(listOf(0L to 5L), spans(LaneKind.CHARGING))
        assertEquals("one lane per kind, in the chart's order", LaneKind.entries, data.lanes.map { it.kind })
    }

    @Test
    fun `an empty timeline maps to an empty chart over the same window`() {
        val data = scrubChartData(emptyTimeline(from = 5, to = 15))

        assertEquals(5L, data.window.startMs)
        assertEquals(15L, data.window.endMs)
        assertTrue(data.series.all { it.points.isEmpty() })
        assertTrue(data.lanes.all { it.segments.isEmpty() })
    }

    // --- IntervalAnalysis -> MetricsUi ---

    @Test
    fun `a complete interval maps every figure`() {
        val metrics = metricsUi(intervalAnalysis())

        assertEquals(8.2, metrics.activePctPerHour!!, 1e-9)
        assertEquals(0.5, metrics.idlePctPerHour!!, 1e-9)
        // screen-on mAh = 1000 - 200 over 2 h; screen-off 200 mAh over 8 h
        assertEquals(400.0, metrics.activeMahPerHour!!, 1e-9)
        assertEquals(25.0, metrics.idleMahPerHour!!, 1e-9)
        assertEquals(0.7, metrics.deepSleepRatio!!, 1e-9)
        assertEquals(7 * HOUR_MS, metrics.deepSleepMs)
        assertEquals(2 * HOUR_MS, metrics.screenOnMs)
        assertEquals(40 * MIN_MS, metrics.screenOffAwakeMs)
    }

    @Test
    fun `missing sources stay null instead of becoming zero`() {
        val analysis = intervalAnalysis(
            diff = snapshotDiff(dischargeMah = null, dischargeScreenOffMah = null, sleep = null),
            metrics = intervalMetrics(screenOnPctPerHour = null, screenOffPctPerHour = null),
        )

        val metrics = metricsUi(analysis)

        assertNull(metrics.activePctPerHour)
        assertNull(metrics.idlePctPerHour)
        assertNull(metrics.activeMahPerHour)
        assertNull(metrics.idleMahPerHour)
        assertNull(metrics.deepSleepRatio)
        assertNull(metrics.deepSleepMs)
        assertNull(metrics.screenOffAwakeMs)
        assertEquals("screen-on time still comes from the history metrics", 2 * HOUR_MS, metrics.screenOnMs)
    }

    @Test
    fun `an mAh per hour figure needs fifteen minutes in its state`() {
        val analysis = intervalAnalysis(metrics = intervalMetrics(screenOnMs = 14 * MIN_MS, screenOffMs = 15 * MIN_MS))

        val metrics = metricsUi(analysis)

        assertNull(metrics.activeMahPerHour)
        assertEquals(800.0, metrics.idleMahPerHour!!, 1e-9)
    }

    @Test
    fun `counters that disagree give no screen-on mAh`() {
        val analysis = intervalAnalysis(diff = snapshotDiff(dischargeMah = 100.0, dischargeScreenOffMah = 150.0))

        val metrics = metricsUi(analysis)

        assertNull(metrics.activeMahPerHour)
        assertEquals(150.0 / 8, metrics.idleMahPerHour!!, 1e-9)
    }

    @Test
    fun `deep sleep without a valid ratio shows neither ratio nor duration`() {
        val sleep = SleepStats(
            deepSleepMs = 0, screenOffDeepSleepMs = null, screenOffAwakeMs = null, screenOffMs = null,
            screenOnMs = null, deepSleepRatio = null,
        )

        val metrics = metricsUi(intervalAnalysis(diff = snapshotDiff(sleep = sleep)))

        assertNull(metrics.deepSleepRatio)
        assertNull(metrics.deepSleepMs)
        assertEquals("no stats screen-on time falls back to the history metrics", 2 * HOUR_MS, metrics.screenOnMs)
    }

    // --- Time left ---

    @Test
    fun `time left is charge counter over current while discharging`() {
        // 3,000,000 uAh at 300 mA is 10 h
        val reading = liveReading(currentMa = -300, chargeCounterUah = 3_000_000)

        assertEquals(10 * HOUR_MS, remainingMs(reading, systemEstimateMs = 99L))
    }

    @Test
    fun `time left falls back to the system estimate without a counter or a negative current`() {
        assertEquals(7L, remainingMs(liveReading(currentMa = -300, chargeCounterUah = null), 7L))
        assertEquals(7L, remainingMs(liveReading(currentMa = null, chargeCounterUah = 3_000_000), 7L))
        assertEquals(7L, remainingMs(liveReading(currentMa = 0, chargeCounterUah = 3_000_000), 7L))
        assertNull(remainingMs(liveReading(currentMa = -300, chargeCounterUah = null), null))
    }

    @Test
    fun `an extrapolation beyond a week is not trusted`() {
        // 3,000,000 uAh at 1 mA would be 125 days
        val reading = liveReading(currentMa = -1, chargeCounterUah = 3_000_000)

        assertEquals(42L, remainingMs(reading, 42L))
        assertNull(remainingMs(reading, null))
    }

    @Test
    fun `there is no time to empty while plugged in`() {
        val reading = liveReading(currentMa = 900, plugged = true, chargeCounterUah = 3_000_000)

        assertNull(remainingMs(reading, 5L))
        assertTrue(liveUi(reading, 5L).charging)
    }

    @Test
    fun `live reading converts temperature and keeps missing values null`() {
        val live = liveUi(liveReading(currentMa = null, plugged = null, temperatureDeciC = 315), null)

        assertEquals(31.5, live.temperatureC!!, 1e-9)
        assertNull(live.currentMa)
        assertNull(live.remainingMs)
        assertFalse("unknown plug state and no current is not charging", live.charging)
        assertNull(liveUi(liveReading(temperatureDeciC = null), null).temperatureC)
        assertTrue("positive current without a plug flag reads as charging", liveUi(liveReading(currentMa = 500, plugged = null), null).charging)
    }

    // --- Slice banner ---

    @Test
    fun `no slice outside time slice mode`() {
        assertNull(sliceUi(ReferenceKind.Last24h, AnalysisState.Empty))
        assertNotNull(sliceUi(ReferenceKind.TimeSlice(1, 2), AnalysisState.Loading))
    }
}
