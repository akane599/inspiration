package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnplugSummaryCalculatorTest {
    @Test
    fun `plain unplug splits screen on and off rates and deep sleep`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 100, plugged = true, trigger = CaptureTrigger.POWER_CONNECTED),
                statusSnapshot(minutes(5), level = 100, plugged = true, trigger = CaptureTrigger.SCREEN_ON),
                statusSnapshot(minutes(10), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                statusSnapshot(
                    minutes(40), uptimeMs = minutes(40), level = 94, trigger = CaptureTrigger.SCREEN_OFF,
                ),
                statusSnapshot(minutes(100), uptimeMs = minutes(55), level = 93),
            ),
            statusSnapshot(minutes(160), uptimeMs = minutes(70), level = 92),
        )

        assertEquals(
            UnplugSummary.Discharging(
                levelNow = 92,
                dropPct = 8,
                elapsedMs = minutes(150),
                screenOnPctPerHour = 12.0,
                screenOffPctPerHour = 1.0,
                deepSleepRatio = 0.75,
                anchorIsUnplug = true,
                anchorWallMs = minutes(10),
            ),
            summary,
        )
    }

    @Test
    fun `plugged now is charging`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(statusSnapshot(minutes(0), level = 50)),
            statusSnapshot(minutes(30), level = 60, plugged = true),
        )

        assertEquals(UnplugSummary.Charging(60), summary)
    }

    @Test
    fun `never plugged anchors at the earliest snapshot`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 90, trigger = CaptureTrigger.SCREEN_ON),
                statusSnapshot(minutes(30), level = 88),
            ),
            statusSnapshot(minutes(60), level = 85),
        ) as UnplugSummary.Discharging

        assertEquals(false, summary.anchorIsUnplug)
        assertEquals(0L, summary.anchorWallMs)
        assertEquals(minutes(60), summary.elapsedMs)
        assertEquals(5, summary.dropPct)
        assertEquals(5.0, summary.screenOnPctPerHour!!, 1e-9)
        assertNull(summary.screenOffPctPerHour)
        assertNull(summary.deepSleepRatio)
    }

    @Test
    fun `missed disconnect anchors at the first unplugged snapshot and keeps the screen state`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 100, plugged = true, trigger = CaptureTrigger.SCREEN_OFF),
                statusSnapshot(minutes(30), level = 100, trigger = CaptureTrigger.APP_OPEN),
            ),
            statusSnapshot(minutes(90), level = 97),
        ) as UnplugSummary.Discharging

        assertEquals(true, summary.anchorIsUnplug)
        assertEquals(minutes(30), summary.anchorWallMs)
        assertEquals(minutes(60), summary.elapsedMs)
        assertEquals(3, summary.dropPct)
        assertEquals(3.0, summary.screenOffPctPerHour!!, 1e-9)
        assertNull(summary.screenOnPctPerHour)
    }

    @Test
    fun `reboot mid interval counts wall time but gives no suspend credit across the boot`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), elapsedMs = 0L, uptimeMs = 0L, level = 90, trigger = CaptureTrigger.SCREEN_OFF),
                statusSnapshot(minutes(60), elapsedMs = minutes(60), uptimeMs = minutes(20), level = 88),
                statusSnapshot(
                    minutes(120), elapsedMs = minutes(5), uptimeMs = minutes(5), bootCount = 2, level = 87,
                    trigger = CaptureTrigger.BOOT,
                ),
            ),
            statusSnapshot(minutes(180), elapsedMs = minutes(65), uptimeMs = minutes(25), bootCount = 2, level = 85),
        ) as UnplugSummary.Discharging

        assertEquals(5, summary.dropPct)
        assertEquals(5 / 3.0, summary.screenOffPctPerHour!!, 1e-9)
        assertEquals(80.0 / 180.0, summary.deepSleepRatio!!, 1e-9)
    }

    @Test
    fun `rates and deep sleep are null under fifteen minutes of state time`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 90, plugged = true),
                statusSnapshot(minutes(1), level = 90, trigger = CaptureTrigger.SCREEN_ON),
            ),
            statusSnapshot(minutes(11), level = 89),
        ) as UnplugSummary.Discharging

        assertEquals(1, summary.dropPct)
        assertEquals(minutes(10), summary.elapsedMs)
        assertNull(summary.screenOnPctPerHour)
        assertNull(summary.screenOffPctPerHour)
        assertNull(summary.deepSleepRatio)
    }

    @Test
    fun `unknown screen state at the anchor counts toward elapsed and drop but not the rates`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 90, trigger = CaptureTrigger.APP_OPEN),
                statusSnapshot(minutes(30), level = 88, trigger = CaptureTrigger.SCREEN_ON),
            ),
            statusSnapshot(minutes(90), level = 85),
        ) as UnplugSummary.Discharging

        assertEquals(minutes(90), summary.elapsedMs)
        assertEquals(5, summary.dropPct)
        assertEquals(3.0, summary.screenOnPctPerHour!!, 1e-9)
        assertNull(summary.screenOffPctPerHour)
    }

    @Test
    fun `null level on either end of a segment contributes no drop`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(
                statusSnapshot(minutes(0), level = 90, trigger = CaptureTrigger.SCREEN_ON),
                statusSnapshot(minutes(30), level = null),
            ),
            statusSnapshot(minutes(60), level = 80),
        ) as UnplugSummary.Discharging

        assertEquals(80, summary.levelNow)
        assertEquals(0, summary.dropPct)
        assertEquals(0.0, summary.screenOnPctPerHour!!, 1e-9)
    }

    @Test
    fun `a level that rose in a segment is not a negative drop`() {
        val summary = UnplugSummaryCalculator.compute(
            listOf(statusSnapshot(minutes(0), level = 80, trigger = CaptureTrigger.SCREEN_OFF)),
            statusSnapshot(minutes(60), level = 85),
        ) as UnplugSummary.Discharging

        assertEquals(0, summary.dropPct)
    }

    @Test
    fun `empty history is a zero length interval at now`() {
        val summary = UnplugSummaryCalculator.compute(emptyList(), statusSnapshot(minutes(30), level = 70))

        assertEquals(
            UnplugSummary.Discharging(
                levelNow = 70,
                dropPct = 0,
                elapsedMs = 0L,
                screenOnPctPerHour = null,
                screenOffPctPerHour = null,
                deepSleepRatio = null,
                anchorIsUnplug = false,
                anchorWallMs = minutes(30),
            ),
            summary,
        )
    }

    private fun minutes(n: Long) = n * 60_000L
}
