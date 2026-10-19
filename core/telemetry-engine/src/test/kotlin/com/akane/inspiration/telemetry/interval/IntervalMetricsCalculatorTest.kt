package com.akane.inspiration.telemetry.interval

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.store.HISTORY_HORIZON_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalMetricsCalculatorTest {
    @Test
    fun `span before the first screen event counts as unknown`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.START, 80),
                    event(10, HistoryKind.SCREEN_ON, 80),
                    event(40, HistoryKind.LEVEL, 78),
                ),
                fromWallMs = minutes(5),
                toWallMs = minutes(60),
            )

        assertEquals(minutes(5), metrics.unknownMs)
        assertEquals(minutes(50), metrics.screenOnMs)
        assertEquals(0L, metrics.screenOffMs)
        assertEquals(2, metrics.levelDropScreenOn)
        assertTrue(metrics.discontinuities.isEmpty())
    }

    @Test
    fun `screen on and off time and drops are split and later events are ignored`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_OFF, 100),
                    event(60, HistoryKind.SCREEN_ON, 100),
                    event(90, HistoryKind.LEVEL, 95),
                    event(120, HistoryKind.SCREEN_OFF, 95),
                    event(150, HistoryKind.LEVEL, 94),
                    event(300, HistoryKind.LEVEL, 10),
                ),
                fromWallMs = 0,
                toWallMs = minutes(240),
            )

        assertEquals(minutes(60), metrics.screenOnMs)
        assertEquals(minutes(180), metrics.screenOffMs)
        assertEquals(0L, metrics.unknownMs)
        assertEquals(5, metrics.levelDropScreenOn)
        assertEquals(1, metrics.levelDropScreenOff)
        assertEquals(6, metrics.levelUsedPct)
        assertEquals(5.0, metrics.screenOnPctPerHour!!, 1e-9)
        assertEquals(1.0 / 3.0, metrics.screenOffPctPerHour!!, 1e-9)
        assertEquals(100, metrics.startLevel)
        assertEquals(94, metrics.endLevel)
    }

    @Test
    fun `levels are seeded from non-LEVEL events at each bound`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_ON, 70),
                    event(10, HistoryKind.DEEP_IDLE_ON, 70),
                    event(50, HistoryKind.LEVEL, 69),
                    event(70, HistoryKind.DEEP_IDLE_OFF, 69),
                ),
                fromWallMs = minutes(20),
                toWallMs = minutes(80),
            )

        assertEquals(70, metrics.startLevel)
        assertEquals(69, metrics.endLevel)
        assertEquals(1, metrics.levelDropScreenOn)
        assertEquals(minutes(60), metrics.screenOnMs)
    }

    @Test
    fun `screen transition is applied before a LEVEL at the same timestamp`() {
        listOf(
            listOf(event(60, HistoryKind.SCREEN_OFF, 89), event(60, HistoryKind.LEVEL, 89)),
            listOf(event(60, HistoryKind.LEVEL, 89), event(60, HistoryKind.SCREEN_OFF, 89)),
        ).forEach { sameTimestamp ->
            val metrics =
                IntervalMetricsCalculator.compute(
                    listOf(event(0, HistoryKind.SCREEN_ON, 90)) + sameTimestamp,
                    fromWallMs = 0,
                    toWallMs = minutes(120),
                )

            assertEquals(0, metrics.levelDropScreenOn)
            assertEquals(1, metrics.levelDropScreenOff)
            assertEquals(minutes(60), metrics.screenOnMs)
            assertEquals(minutes(60), metrics.screenOffMs)
        }
    }

    @Test
    fun `RESET reseeds the level without a false drop and is a discontinuity`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_ON, 80),
                    event(10, HistoryKind.LEVEL, 79),
                    event(20, HistoryKind.RESET, 100),
                    event(30, HistoryKind.LEVEL, 99),
                ),
                fromWallMs = 0,
                toWallMs = minutes(60),
            )

        assertEquals(2, metrics.levelUsedPct)
        assertEquals(listOf(minutes(20)), metrics.discontinuities)
        assertEquals(80, metrics.startLevel)
        assertEquals(99, metrics.endLevel)
    }

    @Test
    fun `a RESET reseeds even when the level then falls below the old baseline`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_OFF, 50),
                    event(10, HistoryKind.RESET, 30),
                    event(20, HistoryKind.LEVEL, 29),
                ),
                fromWallMs = 0,
                toWallMs = minutes(30),
            )

        assertEquals(1, metrics.levelDropScreenOff)
        assertEquals(1, metrics.levelUsedPct)
    }

    @Test
    fun `charging time and drops are excluded and levelUsed is the sum of drops`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_OFF, 50),
                    event(30, HistoryKind.LEVEL, 49),
                    event(60, HistoryKind.PLUGGED, 49),
                    event(90, HistoryKind.LEVEL, 60),
                    event(100, HistoryKind.LEVEL, 59),
                    event(120, HistoryKind.UNPLUGGED, 59),
                    event(150, HistoryKind.LEVEL, 58),
                ),
                fromWallMs = 0,
                toWallMs = minutes(180),
            )

        assertEquals(minutes(60), metrics.chargingMs)
        assertEquals(minutes(120), metrics.screenOffMs)
        assertEquals(0L, metrics.screenOnMs)
        assertEquals(2, metrics.levelDropScreenOff)
        assertEquals(2, metrics.levelUsedPct)
        assertEquals(50, metrics.startLevel)
        assertEquals(58, metrics.endLevel)
        assertTrue(metrics.endLevel!! > metrics.startLevel!!)
        assertEquals(1.0, metrics.screenOffPctPerHour!!, 1e-9)
    }

    @Test
    fun `rate is null under fifteen minutes and defined from fifteen minutes`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(
                    event(0, HistoryKind.SCREEN_ON, 90),
                    event(5, HistoryKind.LEVEL, 89),
                    event(14, HistoryKind.SCREEN_OFF, 89),
                ),
                fromWallMs = 0,
                toWallMs = minutes(114),
            )
        assertEquals(minutes(14), metrics.screenOnMs)
        assertEquals(1, metrics.levelDropScreenOn)
        assertNull(metrics.screenOnPctPerHour)
        assertNotNull(metrics.screenOffPctPerHour)

        val boundary =
            IntervalMetricsCalculator.compute(
                listOf(event(0, HistoryKind.SCREEN_ON, 90), event(5, HistoryKind.LEVEL, 89)),
                fromWallMs = 0,
                toWallMs = minutes(15),
            )
        assertEquals(4.0, boundary.screenOnPctPerHour!!, 1e-9)
    }

    @Test
    fun `empty list and no events before the end give zeros and null levels`() {
        listOf(
            emptyList(),
            listOf(event(500, HistoryKind.SCREEN_ON, 80), event(600, HistoryKind.LEVEL, 79)),
        ).forEach { events ->
            val metrics = IntervalMetricsCalculator.compute(events, minutes(10), minutes(100))

            assertEquals(
                IntervalMetrics(
                    fromWallMs = minutes(10),
                    toWallMs = minutes(100),
                    screenOnMs = 0,
                    screenOffMs = 0,
                    unknownMs = 0,
                    chargingMs = 0,
                    startLevel = null,
                    endLevel = null,
                    levelUsedPct = 0,
                    levelDropScreenOn = 0,
                    levelDropScreenOff = 0,
                    screenOnPctPerHour = null,
                    screenOffPctPerHour = null,
                    discontinuities = emptyList(),
                ),
                metrics,
            )
        }
    }

    @Test
    fun `shutdown clears charging and screen state and startup seeds the plug state`() {
        for (startupPlug in listOf(0, 1)) {
            val metrics =
                IntervalMetricsCalculator.compute(
                    listOf(
                        event(0, HistoryKind.PLUGGED, 80),
                        event(0, HistoryKind.SCREEN_ON, 80),
                        event(10, HistoryKind.SHUTDOWN, 80),
                        event(20, HistoryKind.START, 79).copy(plugType = startupPlug),
                        event(25, HistoryKind.SCREEN_OFF, 79),
                        event(30, HistoryKind.LEVEL, 78),
                    ),
                    fromWallMs = 0,
                    toWallMs = minutes(40),
                )

            assertEquals(minutes(if (startupPlug == 0) 10 else 30), metrics.chargingMs)
            assertEquals(minutes(if (startupPlug == 0) 15 else 10), metrics.unknownMs)
            assertEquals(0L, metrics.screenOnMs)
            assertEquals(if (startupPlug == 0) 1 else 0, metrics.levelDropScreenOff)
            assertEquals(listOf(minutes(10), minutes(20)), metrics.discontinuities)
        }
    }

    /** Screen off from 0, 2% gone by 2 h; history was last ingested at 2 h, the interval ends 6 h later. */
    private val staleTail =
        listOf(
            event(0, HistoryKind.UNPLUGGED, 90),
            event(0, HistoryKind.SCREEN_OFF, 90),
            event(60, HistoryKind.LEVEL, 89),
            event(100, HistoryKind.LEVEL, 88),
        )

    @Test
    fun `time after the history horizon is unknown and does not dilute the rate`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                staleTail,
                fromWallMs = 0,
                toWallMs = minutes(8 * 60),
                historyHorizonWallMs = minutes(120),
            )

        assertEquals(minutes(120), metrics.screenOffMs)
        assertEquals(minutes(6 * 60), metrics.unknownMs)
        assertEquals(0L, metrics.chargingMs)
        assertEquals(2, metrics.levelDropScreenOff)
        // 2% over the 2 known hours, not over all 8.
        assertEquals(1.0, metrics.screenOffPctPerHour!!, 1e-9)
    }

    @Test
    fun `a horizon before the newest event still carries state to that event`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                staleTail,
                fromWallMs = 0,
                toWallMs = minutes(8 * 60),
                historyHorizonWallMs = minutes(30),
            )

        assertEquals(minutes(100), metrics.screenOffMs)
        assertEquals(minutes(8 * 60 - 100), metrics.unknownMs)
    }

    @Test
    fun `a plugged tail past the horizon is unknown, not charging`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                listOf(event(0, HistoryKind.SCREEN_OFF, 50), event(10, HistoryKind.PLUGGED, 50)),
                fromWallMs = 0,
                toWallMs = minutes(60),
                historyHorizonWallMs = minutes(20),
            )

        assertEquals(minutes(10), metrics.screenOffMs)
        assertEquals(minutes(10), metrics.chargingMs)
        assertEquals(minutes(40), metrics.unknownMs)
    }

    @Test
    fun `a horizon at or after the interval end changes nothing`() {
        val default = IntervalMetricsCalculator.compute(staleTail, fromWallMs = 0, toWallMs = minutes(8 * 60))
        for (horizon in listOf(minutes(8 * 60), minutes(9 * 60), Long.MAX_VALUE)) {
            assertEquals(
                default,
                IntervalMetricsCalculator.compute(staleTail, 0, minutes(8 * 60), historyHorizonWallMs = horizon),
            )
        }
        assertEquals(0L, default.unknownMs)
    }

    @Test
    fun `a horizon before the interval start makes the whole interval unknown`() {
        val metrics =
            IntervalMetricsCalculator.compute(
                staleTail,
                fromWallMs = minutes(200),
                toWallMs = minutes(300),
                historyHorizonWallMs = Long.MIN_VALUE,
            )

        assertEquals(minutes(100), metrics.unknownMs)
        assertEquals(0L, metrics.screenOffMs)
        assertNull(metrics.screenOffPctPerHour)
    }

    @Test
    fun `a gap within the grace carries the last state and a longer one is unknown from the horizon`() {
        val horizon = minutes(120)
        val withinGrace =
            IntervalMetricsCalculator.compute(staleTail, 0, horizon + HISTORY_HORIZON_GRACE_MS, historyHorizonWallMs = horizon)
        assertEquals(0L, withinGrace.unknownMs)
        assertEquals(horizon + HISTORY_HORIZON_GRACE_MS, withinGrace.screenOffMs)

        val pastGrace =
            IntervalMetricsCalculator.compute(staleTail, 0, horizon + HISTORY_HORIZON_GRACE_MS + 1, historyHorizonWallMs = horizon)
        assertEquals(HISTORY_HORIZON_GRACE_MS + 1, pastGrace.unknownMs)
        assertEquals(horizon, pastGrace.screenOffMs)
        assertEquals(2 * 60_000L, HISTORY_HORIZON_GRACE_MS)
    }

    private fun minutes(value: Long): Long = value * 60_000L

    private fun event(
        minute: Long,
        kind: Int,
        levelPct: Int,
    ) = HistoryEvent(
        historyTimeMs = minutes(minute),
        wallMs = minutes(minute),
        kind = kind,
        levelPct = levelPct,
        chargeUah = -1L,
        temperatureDeciC = 250,
        voltageMv = 4000,
        plugType = 0,
    )
}
