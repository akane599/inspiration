package com.akane.inspiration.telemetry.status

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UnplugSummaryCalculator] with ingested battery history timing the screen and plug transitions (SQ-25). */
class UnplugSummaryHistoryTest {
    /**
     * Plugged in with the screen on at 22:00 and unplugged at 22:30 (captured at once), screen off 23:00, on 07:00,
     * app opened 08:00. The SCREEN_OFF broadcast was dropped and the SCREEN_ON one delivered at 08:00, so the captures
     * alone read the whole night as screen-on; the history splits it.
     */
    @Test
    fun `history screen events split a night whose SCREEN_OFF was lost and SCREEN_ON came late`() {
        val unplugWallMs = minutes(30) + 2_000L
        val snapshots =
            listOf(
                statusSnapshot(0L, uptimeMs = 0L, level = 100, plugged = true, trigger = CaptureTrigger.SCREEN_ON),
                statusSnapshot(unplugWallMs, uptimeMs = unplugWallMs, level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                // 6 h of the 8 h off suspended: uptime advanced 3 h 30 min over the 9 h 30 min since the unplug.
                statusSnapshot(minutes(600), uptimeMs = minutes(240), level = 87, trigger = CaptureTrigger.SCREEN_ON),
            )
        val now = statusSnapshot(minutes(605), uptimeMs = minutes(245), level = 86)
        val history =
            listOf(
                // Its capture landed within the slack: that capture is the event's own and anchors.
                event(HistoryKind.UNPLUGGED, minutes(30), level = 100),
                event(HistoryKind.SCREEN_OFF, minutes(60), level = 99),
                event(HistoryKind.SCREEN_ON, minutes(540), level = 91),
            )

        val summary = UnplugSummaryCalculator.compute(snapshots, now, history) as UnplugSummary.Discharging

        assertEquals(unplugWallMs, summary.anchorWallMs)
        assertEquals(minutes(605) - unplugWallMs, summary.elapsedMs)
        assertEquals(14, summary.dropPct)
        // 8 % over the 8 h off; 6 % over the 1 h 35 min on (less the 2 s before the anchor).
        assertEquals(1.0, summary.screenOffPctPerHour!!, 1e-9)
        assertEquals(6 / ((minutes(95) - 2_000L) / 3_600_000.0), summary.screenOnPctPerHour!!, 1e-9)
        assertEquals(0.75, summary.deepSleepRatio!!, 1e-9)

        val capturesOnly = UnplugSummaryCalculator.compute(snapshots, now) as UnplugSummary.Discharging
        assertNull(capturesOnly.screenOffPctPerHour)
        assertNull(capturesOnly.deepSleepRatio)
    }

    @Test
    fun `a late POWER_DISCONNECTED after a history unplug keeps the anchor at the history unplug`() {
        val snapshots =
            listOf(
                statusSnapshot(0L, level = 100, plugged = true, trigger = CaptureTrigger.CHARGE_JOB),
                statusSnapshot(minutes(300), level = 90, trigger = CaptureTrigger.POWER_DISCONNECTED),
            )
        val now = statusSnapshot(minutes(310), level = 89)
        val history = listOf(event(HistoryKind.UNPLUGGED, minutes(60), level = 100))

        val summary = UnplugSummaryCalculator.compute(snapshots, now, history) as UnplugSummary.Discharging

        assertTrue(summary.anchorIsUnplug)
        assertEquals(minutes(60), summary.anchorWallMs)
        assertEquals(minutes(250), summary.elapsedMs)
        assertEquals(11, summary.dropPct)
        assertEquals(minutes(300), (UnplugSummaryCalculator.compute(snapshots, now) as UnplugSummary.Discharging).anchorWallMs)
    }

    @Test
    fun `a history unplug anchors even with no plugged snapshot stored`() {
        val snapshots =
            listOf(
                statusSnapshot(0L, level = 100, plugged = null),
                statusSnapshot(minutes(300), level = 90, trigger = CaptureTrigger.POWER_DISCONNECTED),
            )
        val now = statusSnapshot(minutes(310), level = 89)
        val history = listOf(event(HistoryKind.UNPLUGGED, minutes(60), level = 99))

        val summary = UnplugSummaryCalculator.compute(snapshots, now, history) as UnplugSummary.Discharging

        assertTrue(summary.anchorIsUnplug)
        assertEquals(minutes(60), summary.anchorWallMs)
        assertEquals(10, summary.dropPct)
    }

    @Test
    fun `a plugged snapshot after the history unplug shows a newer unplug the history has not ingested`() {
        val snapshots =
            listOf(
                statusSnapshot(0L, level = 100, plugged = true, trigger = CaptureTrigger.CHARGE_JOB),
                statusSnapshot(minutes(200), level = 95, plugged = true, trigger = CaptureTrigger.CHARGE_JOB),
                statusSnapshot(minutes(300), level = 95, trigger = CaptureTrigger.POWER_DISCONNECTED),
            )
        val now = statusSnapshot(minutes(310), level = 94)
        val history = listOf(event(HistoryKind.UNPLUGGED, minutes(60), level = 100))

        val summary = UnplugSummaryCalculator.compute(snapshots, now, history) as UnplugSummary.Discharging

        assertEquals(minutes(300), summary.anchorWallMs)
        assertEquals(1, summary.dropPct)
    }

    /** The "plain unplug" fixture of [UnplugSummaryCalculatorTest], whose expected output it repeats. */
    @Test
    fun `history whose every event has its own capture, or none of the read kinds, leaves the summary unchanged`() {
        val snapshots =
            listOf(
                statusSnapshot(minutes(0), level = 100, plugged = true, trigger = CaptureTrigger.POWER_CONNECTED),
                statusSnapshot(minutes(5), level = 100, plugged = true, trigger = CaptureTrigger.SCREEN_ON),
                statusSnapshot(minutes(10), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                statusSnapshot(minutes(40), uptimeMs = minutes(40), level = 94, trigger = CaptureTrigger.SCREEN_OFF),
                statusSnapshot(minutes(100), uptimeMs = minutes(55), level = 93),
            )
        val now = statusSnapshot(minutes(160), uptimeMs = minutes(70), level = 92)
        val expected =
            UnplugSummary.Discharging(
                levelNow = 92,
                dropPct = 8,
                elapsedMs = minutes(150),
                screenOnPctPerHour = 12.0,
                screenOffPctPerHour = 1.0,
                deepSleepRatio = 0.75,
                anchorIsUnplug = true,
                anchorWallMs = minutes(10),
            )
        val prompt =
            listOf(
                event(HistoryKind.PLUGGED, minutes(0), level = 100),
                event(HistoryKind.SCREEN_ON, minutes(5) - 3_000L, level = 100),
                event(HistoryKind.UNPLUGGED, minutes(10) - 500L, level = 100),
                event(HistoryKind.SCREEN_OFF, minutes(40) - 9_000L, level = 94),
            )
        val ignored =
            listOf(
                event(HistoryKind.LEVEL, minutes(70), level = 93),
                event(HistoryKind.RESET, minutes(80), level = 93),
                // Unknown wall time, and at or after now.
                event(HistoryKind.SCREEN_ON, -1L, level = 93),
                event(HistoryKind.UNPLUGGED, minutes(160), level = 92),
            )

        assertEquals(expected, UnplugSummaryCalculator.compute(snapshots, now))
        assertEquals(expected, UnplugSummaryCalculator.compute(snapshots, now, emptyList()))
        assertEquals(expected, UnplugSummaryCalculator.compute(snapshots, now, prompt))
        assertEquals(expected, UnplugSummaryCalculator.compute(snapshots, now, ignored))
    }

    @Test
    fun `a screen capture inside the history's span is a late delivery and sets no state`() {
        // The SCREEN_ON at 01:00 was delivered at 03:00, after the screen went off again at 01:30.
        val snapshots =
            listOf(
                statusSnapshot(0L, level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                statusSnapshot(minutes(180), level = 94, trigger = CaptureTrigger.SCREEN_ON),
            )
        val now = statusSnapshot(minutes(240), level = 93)
        val history =
            listOf(
                event(HistoryKind.SCREEN_OFF, minutes(1), level = 100),
                event(HistoryKind.SCREEN_ON, minutes(60), level = 98),
                event(HistoryKind.SCREEN_OFF, minutes(90), level = 97),
                event(HistoryKind.SCREEN_ON, minutes(230), level = 93),
            )

        val summary = UnplugSummaryCalculator.compute(snapshots, now, history) as UnplugSummary.Discharging

        // Off 1-60 (2 %) and 90-230 (4 %) despite the capture at 180; on 60-90 (1 %) and 230-240 (0 %).
        assertEquals(6.0 / (minutes(199) / 3_600_000.0), summary.screenOffPctPerHour!!, 1e-9)
        assertEquals(1.0 / (minutes(40) / 3_600_000.0), summary.screenOnPctPerHour!!, 1e-9)
        assertEquals(7, summary.dropPct)
    }

    private fun event(
        kind: Int,
        wallMs: Long,
        level: Int,
    ) = HistoryEvent(
        historyTimeMs = wallMs,
        wallMs = wallMs,
        kind = kind,
        levelPct = level,
        chargeUah = -1L,
        temperatureDeciC = 300,
        voltageMv = 3_900,
        plugType = 0,
    )

    private fun minutes(n: Long) = n * 60_000L
}
