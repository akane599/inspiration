package com.akane.inspiration.ipc.client

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.ipc.model.Reasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHistoryTest {
    private fun record(
        time: Long,
        cmd: Int = HistoryBits.CMD_UPDATE,
        currentTime: Long = 0L,
        screenOn: Boolean = false,
        plugged: Boolean = false,
        deepIdle: Boolean = false,
        lightIdle: Boolean = false,
        level: Int = 50,
        chargeUah: Int = 3_000_000,
        plugType: Int = 0,
    ) = HistoryRecord(
        time = time,
        currentTime = currentTime,
        cmd = cmd,
        states =
            (if (screenOn) HistoryBits.STATE_SCREEN_ON_FLAG else 0) or
                (if (plugged) HistoryBits.STATE_BATTERY_PLUGGED_FLAG else 0),
        states2 =
            when {
                deepIdle -> HistoryBits.DEVICE_IDLE_MODE_DEEP shl HistoryBits.STATE2_DEVICE_IDLE_SHIFT
                lightIdle -> HistoryBits.DEVICE_IDLE_MODE_LIGHT shl HistoryBits.STATE2_DEVICE_IDLE_SHIFT
                else -> 0
            },
        batteryLevel = level,
        plugType = plugType,
        chargeUah = chargeUah,
        temperatureDeciC = 250 + level,
        voltageMv = 3_800 + level,
    )

    private fun read(
        history: FakeBatteryHistory,
        statsStartMs: Long = history.statsStartMs,
        after: Long = 0L,
        maxEvents: Int = 4096,
        sdkInt: Int = 35,
    ): BatteryHistorySnapshot =
        BatteryStatsClient({ fakeBinder }, sdkInt, { 0 }) { FakeBatteryStatsCalls(history = history) }
            .readHistory(statsStartMs, after, maxEvents)

    private fun BatteryHistorySnapshot.kinds() = events.map { it.kind }

    /** Level steps 1 ms apart: one LEVEL event per record after the baseline at t=1. */
    private fun levelSteps(count: Int) =
        listOf(record(time = 1, level = 100)) + (1..count).map { record(time = 1L + it, level = 100 - it % 100) }

    @Test
    fun `wall time is rebuilt from CURRENT_TIME and RESET anchors`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 10, level = 60),
                        record(time = 20, level = 59),
                        record(time = 100, cmd = HistoryBits.CMD_RESET, currentTime = 1_000_000L, level = 59),
                        record(time = 200, screenOn = true, level = 59),
                        record(time = 300, cmd = HistoryBits.CMD_CURRENT_TIME, currentTime = 5_000_000L, screenOn = true, level = 59),
                        record(time = 500, screenOn = false, level = 59),
                    ),
            )

        val snapshot = read(history)

        assertEquals(
            listOf(HistoryKind.LEVEL, HistoryKind.RESET, HistoryKind.SCREEN_OFF, HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF),
            snapshot.kinds(),
        )
        // Before any anchor the wall time is unknown.
        assertEquals(-1L, snapshot.events[0].wallMs)
        // The RESET anchors itself and the screen state it restates.
        assertEquals(1_000_000L, snapshot.events[1].wallMs)
        assertEquals(1_000_000L, snapshot.events[2].wallMs)
        assertEquals(1_000_100L, snapshot.events[3].wallMs)
        // A later CURRENT_TIME re-anchors (wall clock jumped).
        assertEquals(5_000_200L, snapshot.events[4].wallMs)
        assertEquals(500L, snapshot.events[4].historyTimeMs)
    }

    @Test
    fun `an unplug RESET restates its live screen state, then the plugged flag clears into UNPLUGGED`() {
        // AOSP writes the unplug RESET whole from the live state: screen on, plugged flag still set, plugType 0.
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 10, screenOn = false, plugged = true, plugType = 2, level = 99),
                        record(time = 20, screenOn = true, plugged = true, plugType = 2, level = 100),
                        record(
                            time = 100,
                            cmd = HistoryBits.CMD_RESET,
                            currentTime = 1_000_000L,
                            screenOn = true,
                            plugged = true,
                            level = 100,
                        ),
                        record(time = 100, screenOn = true, plugged = false, level = 100),
                        record(time = 400, screenOn = false, level = 100),
                    ),
            )

        val events = read(history).events

        assertEquals(
            listOf(
                20L to HistoryKind.SCREEN_ON,
                20L to HistoryKind.LEVEL,
                // The reset restates the screen state: without it the screen-on stretch from 100 has no event.
                100L to HistoryKind.RESET,
                100L to HistoryKind.SCREEN_ON,
                100L to HistoryKind.UNPLUGGED,
                400L to HistoryKind.SCREEN_OFF,
            ),
            events.map { it.historyTimeMs to it.kind },
        )
        assertEquals(0, events.first { it.kind == HistoryKind.RESET }.plugType)
        assertEquals(listOf(1_000_000L), events.filter { it.historyTimeMs == 100L }.map { it.wallMs }.distinct())
    }

    @Test
    fun `the redroid RESET with the screen on yields a SCREEN_ON at the reset (device evidence)`() {
        // run-ax4ji1gb redroid-w4-1-db: RESET +screen +plugged plugType 0, then a screen off and on 13 h later.
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(
                            time = 115_928_047L,
                            cmd = HistoryBits.CMD_RESET,
                            currentTime = 1_792_364_576_106L,
                            screenOn = true,
                            plugged = true,
                            level = 0,
                        ),
                        record(time = 162_371_969L, screenOn = false, plugged = true, level = 0),
                        record(time = 162_411_419L, screenOn = true, plugged = true, level = 0),
                    ),
            )

        assertEquals(
            listOf(
                1_792_364_576_106L to HistoryKind.RESET,
                1_792_364_576_106L to HistoryKind.SCREEN_ON,
                1_792_411_020_028L to HistoryKind.SCREEN_OFF,
                1_792_411_059_478L to HistoryKind.SCREEN_ON,
            ),
            read(history).events.map { it.wallMs to it.kind },
        )
    }

    @Test
    fun `a RESET restates screen off and the doze mode it was written in`() {
        for ((deep, entry, exit) in listOf(
            Triple(true, HistoryKind.DEEP_IDLE_ON, HistoryKind.DEEP_IDLE_OFF),
            Triple(false, HistoryKind.LIGHT_IDLE_ON, HistoryKind.LIGHT_IDLE_OFF),
        )) {
            val history =
                FakeBatteryHistory(
                    records =
                        listOf(
                            record(time = 1, cmd = HistoryBits.CMD_RESET, currentTime = 10_000L, deepIdle = deep, lightIdle = !deep),
                            record(time = 2),
                        ),
                )

            assertEquals(
                listOf(1L to HistoryKind.RESET, 1L to HistoryKind.SCREEN_OFF, 1L to entry, 2L to exit),
                read(history).events.map { it.historyTimeMs to it.kind },
            )
        }
    }

    @Test
    fun `a RESET and the state it restates land on one page`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1, level = 100),
                        record(time = 2, level = 99),
                        record(time = 3, cmd = HistoryBits.CMD_RESET, currentTime = 10_000L, screenOn = true, level = 99),
                    ),
            )

        val first = read(history, maxEvents = 2)

        assertTrue(first.truncated)
        assertEquals(listOf(HistoryKind.LEVEL), first.kinds())
        val next = read(history, after = first.events.last().historyTimeMs, maxEvents = 2)
        assertFalse(next.truncated)
        assertEquals(listOf(HistoryKind.RESET, HistoryKind.SCREEN_ON), next.kinds())
    }

    @Test
    fun `LEVEL is emitted only when batteryLevel changes and carries the battery readings`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1, level = 50),
                        record(time = 2, level = 50),
                        record(time = 3, level = 49, chargeUah = 2_900_000),
                        record(time = 4, level = 49),
                        record(time = 5, level = 49, plugged = true, plugType = 2),
                        record(time = 6, level = 48, plugged = true, plugType = 2, chargeUah = 0),
                    ),
            )

        val snapshot = read(history)

        assertEquals(listOf(HistoryKind.LEVEL, HistoryKind.PLUGGED, HistoryKind.LEVEL), snapshot.kinds())
        val first = snapshot.events[0]
        assertEquals(3L, first.historyTimeMs)
        assertEquals(49, first.levelPct)
        assertEquals(2_900_000L, first.chargeUah)
        assertEquals(299, first.temperatureDeciC)
        assertEquals(3_849, first.voltageMv)
        assertEquals(2, snapshot.events[1].plugType)
        // A non-positive charge reading is reported as unknown.
        assertEquals(-1L, snapshot.events[2].chargeUah)
    }

    @Test
    fun `session commands and deep idle transitions map to their kinds`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1, cmd = HistoryBits.CMD_RESET, currentTime = 10_000L),
                        record(time = 2, deepIdle = true),
                        record(time = 3, deepIdle = false),
                        record(time = 4, cmd = HistoryBits.CMD_SHUTDOWN),
                        // START is a silent baseline (AOSP writes it with zeroed states), unlike a RESET.
                        record(time = 5, cmd = HistoryBits.CMD_START, screenOn = true),
                        record(time = 6, screenOn = true),
                        record(time = 7, cmd = HistoryBits.CMD_OVERFLOW),
                        // After an overflow the next item is a baseline, not a transition.
                        record(time = 8, screenOn = false, level = 10),
                    ),
            )

        assertEquals(
            listOf(
                HistoryKind.RESET,
                HistoryKind.SCREEN_OFF,
                HistoryKind.DEEP_IDLE_ON,
                HistoryKind.DEEP_IDLE_OFF,
                HistoryKind.SHUTDOWN,
                HistoryKind.START,
            ),
            read(history).kinds(),
        )
    }

    @Test
    fun `every device-idle transition emits the exit of the old state then the entry of the new one`() {
        // off -> light -> deep -> light -> off -> deep -> off, one record each, level constant.
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1),
                        record(time = 2, lightIdle = true),
                        record(time = 3, deepIdle = true),
                        record(time = 4, lightIdle = true),
                        record(time = 5),
                        record(time = 6, deepIdle = true),
                        record(time = 7),
                    ),
            )

        val events = read(history).events

        assertEquals(
            listOf(
                2L to HistoryKind.LIGHT_IDLE_ON, // off -> light
                3L to HistoryKind.LIGHT_IDLE_OFF, // light -> deep: exit first ...
                3L to HistoryKind.DEEP_IDLE_ON, // ... then entry, same timestamp
                4L to HistoryKind.DEEP_IDLE_OFF, // deep -> light: reverse
                4L to HistoryKind.LIGHT_IDLE_ON,
                5L to HistoryKind.LIGHT_IDLE_OFF, // light -> off
                6L to HistoryKind.DEEP_IDLE_ON, // off -> deep
                7L to HistoryKind.DEEP_IDLE_OFF, // deep -> off
            ),
            events.map { it.historyTimeMs to it.kind },
        )
        assertEquals(11, HistoryKind.LIGHT_IDLE_ON)
        assertEquals(12, HistoryKind.LIGHT_IDLE_OFF)
        assertEquals(9, HistoryKind.DEEP_IDLE_ON)
        assertEquals(10, HistoryKind.DEEP_IDLE_OFF)
    }

    @Test
    fun `an overflow makes the next device-idle state a baseline, not a transition`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1),
                        record(time = 2, cmd = HistoryBits.CMD_OVERFLOW),
                        record(time = 3, lightIdle = true),
                        record(time = 4),
                    ),
            )

        assertEquals(listOf(HistoryKind.LIGHT_IDLE_OFF), read(history).kinds())
    }

    @Test
    fun `keep-oldest cap returns the first events, sets truncated and stops the walk`() {
        val history = FakeBatteryHistory(records = levelSteps(10))

        val snapshot = read(history, maxEvents = 3)

        assertTrue(snapshot.truncated)
        assertEquals(listOf(2L, 3L, 4L), snapshot.events.map { it.historyTimeMs })
        assertTrue(history.consumed < 11)
    }

    @Test
    fun `exactly maxEvents events are not truncated`() {
        val snapshot = read(FakeBatteryHistory(records = levelSteps(10)), maxEvents = 10)

        assertFalse(snapshot.truncated)
        assertEquals(10, snapshot.events.size)
    }

    @Test
    fun `maxEvents is capped at 4096`() {
        val snapshot = read(FakeBatteryHistory(records = levelSteps(5_000)), maxEvents = 10_000)

        assertTrue(snapshot.truncated)
        assertEquals(4096, snapshot.events.size)
        assertEquals(2L, snapshot.events.first().historyTimeMs)
    }

    @Test
    fun `a page never ends inside one history timestamp`() {
        val history =
            FakeBatteryHistory(
                records =
                    listOf(
                        record(time = 1, level = 50),
                        record(time = 2, level = 49),
                        record(time = 3, level = 48),
                        // Same millisecond as the next item: both must land on the same page.
                        record(time = 4, level = 47),
                        record(time = 4, level = 47, screenOn = true),
                    ),
            )

        val first = read(history, maxEvents = 3)

        assertTrue(first.truncated)
        assertEquals(listOf(2L, 3L), first.events.map { it.historyTimeMs })
        val next = read(history, after = first.events.last().historyTimeMs, maxEvents = 3)
        assertFalse(next.truncated)
        assertEquals(listOf(HistoryKind.LEVEL, HistoryKind.SCREEN_ON), next.kinds())
    }

    @Test
    fun `cursor skips old events but keeps state from before it`() {
        val history =
            FakeBatteryHistory(
                statsStartMs = 7_000L,
                records =
                    listOf(
                        record(time = 1, level = 50),
                        record(time = 2, level = 49),
                        record(time = 3, level = 48),
                        // The baseline at t=3 (48) makes this a change even though it is past the cursor.
                        record(time = 4, level = 47),
                    ),
            )

        val snapshot = read(history, statsStartMs = 7_000L, after = 3L)

        assertFalse(snapshot.sessionChanged)
        assertEquals(7_000L, snapshot.statsStartMs)
        assertEquals(listOf(4L), snapshot.events.map { it.historyTimeMs })
        assertEquals(47, snapshot.events.single().levelPct)
    }

    @Test
    fun `a different session restarts the cursor at 0 and reports sessionChanged`() {
        val history =
            FakeBatteryHistory(
                statsStartMs = 9_000L,
                records = listOf(record(time = 1, level = 50), record(time = 2, level = 49), record(time = 3, level = 48)),
            )

        val snapshot = read(history, statsStartMs = 7_000L, after = 2L)

        assertTrue(snapshot.sessionChanged)
        assertEquals(9_000L, snapshot.statsStartMs)
        assertEquals(listOf(2L, 3L), snapshot.events.map { it.historyTimeMs })
    }

    @Test
    fun `LinkageError during the walk maps to no_such_method and still closes`() {
        val history =
            FakeBatteryHistory(
                records = levelSteps(3),
                failure = NoSuchFieldError("batteryChargeUah"),
                failAfter = 2,
            )

        val snapshot = read(history)

        assertUnavailable(Reasons.NO_SUCH_METHOD, snapshot.available, snapshot.reason)
        assertEquals(1, history.closed)
    }

    @Test
    fun `LinkageError from the query maps to no_such_method`() {
        val calls = FakeBatteryStatsCalls(failure = NoSuchMethodError("includeBatteryHistory"))

        val snapshot = BatteryStatsClient({ fakeBinder }, 35, { 0 }) { calls }.readHistory(0L, 0L, 10)

        assertUnavailable(Reasons.NO_SUCH_METHOD, snapshot.available, snapshot.reason)
    }

    @Test
    fun `below api 34 the history read is refused before any batterystats query`() {
        // The history iterator is an Iterator only from API 34; the walk below it could only fail after the query.
        for (sdkInt in 31..33) {
            val calls = FakeBatteryStatsCalls()

            val snapshot = BatteryStatsClient({ fakeBinder }, sdkInt, { 0 }) { calls }.readHistory(0L, 0L, 10)

            assertUnavailable(Reasons.API, snapshot.available, snapshot.reason)
            assertEquals("api $sdkInt sends no history query", emptyList<String>(), calls.builder.calls)
        }
        assertTrue(read(FakeBatteryHistory(records = levelSteps(2)), sdkInt = 34).available)
    }

    @Test
    fun `successful read closes the history exactly once`() {
        val history = FakeBatteryHistory(records = levelSteps(2))

        val snapshot = read(history)

        assertTrue(snapshot.available)
        assertEquals(1, history.closed)
    }

    @Test
    fun `missing stats maps to remote`() {
        val snapshot =
            BatteryStatsClient({ fakeBinder }, 35, { 0 }) { FakeBatteryStatsCalls(history = null) }
                .readHistory(0L, 0L, 10)

        assertUnavailable(Reasons.REMOTE, snapshot.available, snapshot.reason)
    }

    @Test
    fun `history query asks for the longest preferred duration only on api 36+`() {
        fun queryFor(sdkInt: Int): List<String> {
            val calls = FakeBatteryStatsCalls()
            BatteryStatsClient({ fakeBinder }, sdkInt, { 0 }) { calls }.readHistory(0L, 0L, 10)
            return calls.builder.calls
        }

        assertEquals(listOf("includeBatteryHistory"), queryFor(35))
        val expected = listOf("includeBatteryHistory", "setPreferredHistoryDurationMs($PREFERRED_HISTORY_DURATION_MS)")
        assertEquals(expected, queryFor(36))
        assertEquals(expected, queryFor(37))
    }
}
