package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnplugSummaryCalculatorTest {
    @Test
    fun `latest explicit disconnect supersedes an older inferred session when connect was missed`() {
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), level = 100, plugged = true),
                    statusSnapshot(minutes(10), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                    statusSnapshot(minutes(40), level = 90),
                    statusSnapshot(minutes(70), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                ),
                statusSnapshot(minutes(100), level = 95),
            ) as UnplugSummary.Discharging
        assertEquals(minutes(70), summary.anchorWallMs)
        assertEquals(minutes(30), summary.elapsedMs)
        assertEquals(5, summary.dropPct)
    }

    @Test
    fun `plain unplug splits screen on and off rates and deep sleep`() {
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), level = 100, plugged = true, trigger = CaptureTrigger.POWER_CONNECTED),
                    statusSnapshot(minutes(5), level = 100, plugged = true, trigger = CaptureTrigger.SCREEN_ON),
                    statusSnapshot(minutes(10), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                    statusSnapshot(
                        minutes(40),
                        uptimeMs = minutes(40),
                        level = 94,
                        trigger = CaptureTrigger.SCREEN_OFF,
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
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(statusSnapshot(minutes(0), level = 50)),
                statusSnapshot(minutes(30), level = 60, plugged = true),
            )

        assertEquals(UnplugSummary.Charging(60), summary)
    }

    @Test
    fun `never plugged anchors at the earliest snapshot`() {
        val summary =
            UnplugSummaryCalculator.compute(
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
    fun `a disconnect capture with no plugged snapshot stored anchors as the unplug`() {
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), level = 95, trigger = CaptureTrigger.SCREEN_ON),
                    statusSnapshot(minutes(10), level = 94, trigger = CaptureTrigger.POWER_DISCONNECTED),
                    statusSnapshot(minutes(40), level = 90),
                ),
                statusSnapshot(minutes(70), level = 88),
            ) as UnplugSummary.Discharging

        assertEquals(true, summary.anchorIsUnplug)
        assertEquals(minutes(10), summary.anchorWallMs)
        assertEquals(minutes(60), summary.elapsedMs)
        assertEquals(6, summary.dropPct)
        assertEquals(6.0, summary.screenOnPctPerHour!!, 1e-9)
    }

    @Test
    fun `missed disconnect anchors at the first unplugged snapshot and keeps the screen state`() {
        val summary =
            UnplugSummaryCalculator.compute(
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
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), elapsedMs = 0L, uptimeMs = 0L, level = 90, trigger = CaptureTrigger.SCREEN_OFF),
                    statusSnapshot(minutes(60), elapsedMs = minutes(60), uptimeMs = minutes(20), level = 88),
                    statusSnapshot(
                        minutes(120),
                        elapsedMs = minutes(5),
                        uptimeMs = minutes(5),
                        bootCount = 2,
                        level = 87,
                        trigger = CaptureTrigger.BOOT,
                    ),
                ),
                statusSnapshot(minutes(180), elapsedMs = minutes(65), uptimeMs = minutes(25), bootCount = 2, level = 85),
            ) as UnplugSummary.Discharging

        assertEquals(5, summary.dropPct)
        assertEquals(2.0, summary.screenOffPctPerHour!!, 1e-9)
        assertEquals(40.0 / 60.0, summary.deepSleepRatio!!, 1e-9)
    }

    @Test
    fun `boot invalidates screen inference until a new screen event`() {
        for (trigger in listOf(CaptureTrigger.BOOT, CaptureTrigger.APP_OPEN)) {
            val history =
                listOf(
                    statusSnapshot(minutes(0), level = 90, trigger = CaptureTrigger.SCREEN_OFF),
                    statusSnapshot(minutes(1), level = 89, bootCount = 2, trigger = trigger),
                )
            val now = statusSnapshot(minutes(61), level = 80, bootCount = 2)
            val unknown = UnplugSummaryCalculator.compute(history, now) as UnplugSummary.Discharging
            assertEquals(10, unknown.dropPct)
            assertEquals(minutes(61), unknown.elapsedMs)
            assertNull(unknown.screenOffPctPerHour)
            assertNull(unknown.screenOnPctPerHour)
            assertNull(unknown.deepSleepRatio)
            val known =
                UnplugSummaryCalculator.compute(
                    history + statusSnapshot(minutes(31), level = 85, bootCount = 2, trigger = CaptureTrigger.SCREEN_ON),
                    now,
                ) as UnplugSummary.Discharging
            assertEquals(10.0, known.screenOnPctPerHour!!, 1e-9)
            assertNull(known.screenOffPctPerHour)
        }
    }

    @Test
    fun `a reboot with an unreadable boot count still forgets the screen state`() {
        // BOOT_COUNT unreadable: 0 on both sides of the reboot, which only elapsedRealtime running backwards shows.
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), elapsedMs = minutes(60), bootCount = 0, level = 90, trigger = CaptureTrigger.SCREEN_OFF),
                    statusSnapshot(minutes(61), elapsedMs = minutes(1), uptimeMs = minutes(1), bootCount = 0, level = 89),
                ),
                statusSnapshot(minutes(121), elapsedMs = minutes(61), uptimeMs = minutes(61), bootCount = 0, level = 80),
            ) as UnplugSummary.Discharging

        assertEquals(10, summary.dropPct)
        assertEquals(minutes(121), summary.elapsedMs)
        assertNull(summary.screenOffPctPerHour)
        assertNull(summary.screenOnPctPerHour)
        assertNull(summary.deepSleepRatio)
    }

    @Test
    fun `a capture in the same boot keeps the screen state whatever its trigger`() {
        // An app update is no reboot: the screen stayed off. 0.2.3 stored such an update as BOOT.
        for (trigger in listOf(CaptureTrigger.PACKAGE_REPLACED, CaptureTrigger.BOOT)) {
            val summary =
                UnplugSummaryCalculator.compute(
                    listOf(
                        statusSnapshot(minutes(0), level = 90, trigger = CaptureTrigger.SCREEN_OFF),
                        statusSnapshot(minutes(60), level = 89, trigger = trigger),
                    ),
                    statusSnapshot(minutes(120), level = 84),
                ) as UnplugSummary.Discharging

            assertEquals("$trigger", 6, summary.dropPct)
            assertEquals("$trigger", 3.0, summary.screenOffPctPerHour!!, 1e-9)
            assertNull("$trigger", summary.screenOnPctPerHour)
        }
    }

    @Test
    fun `rates and deep sleep are null under fifteen minutes of state time`() {
        val summary =
            UnplugSummaryCalculator.compute(
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
        val summary =
            UnplugSummaryCalculator.compute(
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
        val summary =
            UnplugSummaryCalculator.compute(
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
        val summary =
            UnplugSummaryCalculator.compute(
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

    @Test
    fun `a wall clock change during screen on does not stretch the screen on time`() {
        val summary =
            UnplugSummaryCalculator.compute(
                listOf(
                    statusSnapshot(minutes(0), level = 100, trigger = CaptureTrigger.POWER_DISCONNECTED),
                    statusSnapshot(minutes(10), level = 100, trigger = CaptureTrigger.SCREEN_ON),
                    // 30 minutes of screen-on later the wall clock has been set a day ahead.
                    statusSnapshot(minutes(40) + DAY_MS, elapsedMs = minutes(40), level = 94, trigger = CaptureTrigger.SCREEN_OFF),
                ),
                statusSnapshot(minutes(100) + DAY_MS, elapsedMs = minutes(100), level = 93),
            ) as UnplugSummary.Discharging

        assertEquals("6 % over 30 min of elapsedRealtime", 12.0, summary.screenOnPctPerHour!!, 1e-9)
        assertEquals(1.0, summary.screenOffPctPerHour!!, 1e-9)
    }

    private fun minutes(n: Long) = n * 60_000L

    private companion object {
        const val DAY_MS = 24L * 60L * 60_000L
    }
}
