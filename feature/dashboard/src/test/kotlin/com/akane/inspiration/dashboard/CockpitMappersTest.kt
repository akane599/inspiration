package com.akane.inspiration.dashboard

import com.akane.inspiration.designsystem.chart.model.LaneKind
import com.akane.inspiration.designsystem.chart.model.SeriesKind
import com.akane.inspiration.designsystem.component.GaugeChargeState
import com.akane.inspiration.designsystem.component.PulseStatus
import com.akane.inspiration.diagnostics.analysis.AnalysisState
import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.Timeline
import com.akane.inspiration.diagnostics.analysis.TimelinePoint
import com.akane.inspiration.diagnostics.analysis.TimelineSegment
import com.akane.inspiration.ipc.ShizukuState
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

    private val timeline =
        Timeline(
            segments =
                listOf(
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

        fun spans(kind: LaneKind) =
            data.lanes
                .single { it.kind == kind }
                .segments
                .map { it.startMs to it.endMs }
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
        val analysis =
            intervalAnalysis(
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
        val sleep =
            SleepStats(
                deepSleepMs = 0,
                screenOffDeepSleepMs = null,
                screenOffAwakeMs = null,
                screenOffMs = null,
                screenOnMs = null,
                deepSleepRatio = null,
            )

        val metrics = metricsUi(intervalAnalysis(diff = snapshotDiff(sleep = sleep)))

        assertNull(metrics.deepSleepRatio)
        assertNull(metrics.deepSleepMs)
        assertEquals("no stats screen-on time falls back to the history metrics", 2 * HOUR_MS, metrics.screenOnMs)
    }

    @Test
    fun `screen time is not taken from history metrics that cover only part of the interval`() {
        val noStats =
            SleepStats(
                deepSleepMs = 0,
                screenOffDeepSleepMs = null,
                screenOffAwakeMs = null,
                screenOffMs = null,
                screenOnMs = null,
                deepSleepRatio = null,
            )

        val unknown =
            metricsUi(
                intervalAnalysis(diff = snapshotDiff(sleep = noStats), metrics = intervalMetrics(unknownMs = 30 * MIN_MS)),
            )
        val reset =
            metricsUi(
                intervalAnalysis(
                    diff = snapshotDiff(sleep = noStats),
                    metrics = intervalMetrics(discontinuities = listOf(NOW_MS - 5 * HOUR_MS)),
                ),
            )
        val withStats =
            metricsUi(
                intervalAnalysis(metrics = intervalMetrics(unknownMs = 30 * MIN_MS, discontinuities = listOf(NOW_MS - 5 * HOUR_MS))),
            )

        assertNull("unknown history time would undercount screen-on time", unknown.screenOnMs)
        assertNull("a history reset leaves part of the interval unattributed", reset.screenOnMs)
        assertEquals("the stats' own screen-on time does not depend on history", 2 * HOUR_MS, withStats.screenOnMs)
    }

    @Test
    fun `a deep sleep share needs fifteen minutes of screen off`() {
        // Screen-off spent awake: a measured 0% that only means something over a long enough window.
        fun awakeScreenOff(screenOffMs: Long) =
            SleepStats(
                deepSleepMs = 0,
                screenOffDeepSleepMs = 0,
                screenOffAwakeMs = screenOffMs,
                screenOffMs = screenOffMs,
                screenOnMs = 2 * HOUR_MS,
                deepSleepRatio = 0.0,
            )

        val short = metricsUi(intervalAnalysis(diff = snapshotDiff(sleep = awakeScreenOff(15 * MIN_MS - 1))))
        val enough = metricsUi(intervalAnalysis(diff = snapshotDiff(sleep = awakeScreenOff(15 * MIN_MS))))

        assertNull("under the floor the tile shows a dash, not 0.0%", short.deepSleepRatio)
        assertNull(short.deepSleepMs)
        assertEquals(0.0, enough.deepSleepRatio!!, 0.0)
        assertEquals(0L, enough.deepSleepMs)
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
        assertEquals(GaugeChargeState.Charging, liveUi(reading, 5L).chargeState)
        assertEquals(
            GaugeChargeState.Charging,
            liveUi(liveReading(currentMa = 900, plugged = true, status = "charging"), 5L).chargeState,
        )
    }

    @Test
    fun `a plugged battery losing to the load is not charging and keeps its time left`() {
        // A weak charger: the battery still drains 300 mA, whatever status it reports. 3,000,000 uAh / 300 mA = 10 h.
        for (status in listOf("discharging", "charging", "not_charging", null)) {
            val reading = liveReading(currentMa = -300, plugged = true, chargeCounterUah = 3_000_000, status = status)

            val live = liveUi(reading, systemEstimateMs = 99L)

            assertEquals("status $status", GaugeChargeState.PluggedNotCharging, live.chargeState)
            assertEquals("status $status", 10 * HOUR_MS, live.remainingMs)
        }
    }

    @Test
    fun `a battery held at a charge limit or full is plugged in, not charging, with no time to empty`() {
        // 3,000,000 uAh at 20 mA would read 6 d 6 h, at 49 mA 2 d 13 h: a held battery's noise, not a drain.
        for (status in listOf("not_charging", "full", "discharging")) {
            for (currentMa in listOf(0, 3, -2, -18, -20, -49)) {
                val reading = liveReading(currentMa = currentMa, plugged = true, chargeCounterUah = 3_000_000, status = status)

                val live = liveUi(reading, systemEstimateMs = 42L)

                assertEquals("$status at $currentMa mA", GaugeChargeState.PluggedNotCharging, live.chargeState)
                assertNull("$status at $currentMa mA: no time to empty on a charger", live.remainingMs)
            }
        }
    }

    @Test
    fun `a drain past the deadband on a charger has a time to empty`() {
        // 3,000,000 uAh / 50 mA = 60 h
        val reading = liveReading(currentMa = -50, plugged = true, chargeCounterUah = 3_000_000, status = "full")

        assertEquals(60 * HOUR_MS, remainingMs(reading, systemEstimateMs = 42L))
    }

    @Test
    fun `without a status the plug decides until the current clearly crosses the deadband`() {
        for (currentMa in listOf(3, 0, -2, -20, -49)) {
            val live = liveUi(liveReading(currentMa = currentMa, plugged = true, chargeCounterUah = 3_000_000, status = null), 42L)

            assertEquals("plugged at $currentMa mA", GaugeChargeState.Charging, live.chargeState)
            assertNull("plugged at $currentMa mA", live.remainingMs)
        }
        assertEquals(
            GaugeChargeState.PluggedNotCharging,
            chargeState(liveReading(currentMa = -50, plugged = true, status = null)),
        )
        // With the plug unknown as well, only a clear charge current reads as charging.
        assertEquals(GaugeChargeState.Discharging, chargeState(liveReading(currentMa = 20, plugged = null, status = null)))
        assertEquals(GaugeChargeState.Charging, chargeState(liveReading(currentMa = 50, plugged = null, status = null)))
    }

    @Test
    fun `a label the deadband decided holds until the current is 10 mA back inside it`() {
        fun plugged(currentMa: Int) = liveReading(currentMa = currentMa, plugged = true, status = null)
        val pnc = GaugeChargeState.PluggedNotCharging

        assertEquals(pnc, chargeState(plugged(-45), previous = pnc))
        assertEquals(pnc, chargeState(plugged(-40), previous = pnc))
        assertEquals(GaugeChargeState.Charging, chargeState(plugged(-39), previous = pnc))
        assertEquals(
            "entering still takes the full deadband",
            GaugeChargeState.Charging,
            chargeState(plugged(-45), GaugeChargeState.Charging),
        )

        // With the plug unknown as well, a charge current keeps its label down to 40 mA.
        fun unknownPlug(currentMa: Int) = liveReading(currentMa = currentMa, plugged = null, status = null)
        assertEquals(GaugeChargeState.Charging, chargeState(unknownPlug(40), previous = GaugeChargeState.Charging))
        assertEquals(GaugeChargeState.Discharging, chargeState(unknownPlug(39), previous = GaugeChargeState.Charging))
        assertEquals(GaugeChargeState.Discharging, chargeState(unknownPlug(45), previous = GaugeChargeState.Discharging))
    }

    @Test
    fun `a time to empty on a charger holds until the drain is 10 mA back inside the deadband`() {
        fun full(currentMa: Int) = liveReading(currentMa = currentMa, plugged = true, chargeCounterUah = 3_000_000, status = "full")
        val counted = liveUi(full(-50), 42L)

        // 3,000,000 uAh / 40 mA = 75 h
        assertEquals(75 * HOUR_MS, remainingMs(full(-40), 42L, previous = counted))
        assertNull(remainingMs(full(-39), 42L, previous = counted))
        assertNull("entering still takes the full deadband", remainingMs(full(-45), 42L, previous = liveUi(full(-30), 42L)))
    }

    @Test
    fun `a charging battery near zero keeps its charging status`() {
        // The end of a taper: the charger regulates around zero, so the label must not flicker.
        val reading = liveReading(currentMa = -20, plugged = true, chargeCounterUah = 3_000_000, status = "charging")

        assertEquals(GaugeChargeState.Charging, liveUi(reading, 42L).chargeState)
        assertNull(remainingMs(reading, 42L))
    }

    @Test
    fun `an unplugged battery is discharging whatever its status says`() {
        val reading = liveReading(currentMa = -300, plugged = false, chargeCounterUah = 3_000_000, status = "charging")

        assertEquals(GaugeChargeState.Discharging, liveUi(reading, null).chargeState)
        assertEquals(10 * HOUR_MS, remainingMs(reading, null))
    }

    @Test
    fun `live reading converts temperature and keeps missing values null`() {
        val live = liveUi(liveReading(currentMa = null, plugged = null, temperatureDeciC = 315), null)

        assertEquals(31.5, live.temperatureC!!, 1e-9)
        assertNull(live.currentMa)
        assertNull(live.remainingMs)
        assertEquals("unknown plug state and no current is not charging", GaugeChargeState.Discharging, live.chargeState)
        assertNull(liveUi(liveReading(temperatureDeciC = null), null).temperatureC)
        assertEquals(
            "positive current without a plug flag reads as charging",
            GaugeChargeState.Charging,
            liveUi(liveReading(currentMa = 500, plugged = null), null).chargeState,
        )
        assertEquals(
            "a plug with neither status nor current falls back to charging",
            GaugeChargeState.Charging,
            liveUi(liveReading(currentMa = null, plugged = true), null).chargeState,
        )
    }

    // --- Shizuku status ---

    @Test
    fun `an outdated Shizuku server is blocked and offers no grant`() {
        val ui = shizukuUi(ShizukuState.UnsupportedServer(apiVersion = 12))

        assertEquals(PulseStatus.BLOCKED, ui.status)
        assertEquals(ShizukuReason.UnsupportedServer, ui.reason)
        assertNull(ui.uid)
        assertFalse("permission cannot help a server that is too old", ui.canGrant)
    }

    // --- Slice banner ---

    @Test
    fun `no slice outside time slice mode`() {
        assertNull(sliceUi(ReferenceKind.Last24h, AnalysisState.Empty))
        assertNotNull(sliceUi(ReferenceKind.TimeSlice(1, 2), AnalysisState.Loading))
    }
}
