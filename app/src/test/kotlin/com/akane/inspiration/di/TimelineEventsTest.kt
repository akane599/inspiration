package com.akane.inspiration.di

import com.akane.inspiration.diagnostics.analysis.SegmentKind
import com.akane.inspiration.diagnostics.analysis.TimelineBuilder
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEventsTest {
    private fun event(kind: Int, wallMs: Long) = HistoryEvent(
        historyTimeMs = wallMs,
        wallMs = wallMs,
        kind = kind,
        levelPct = 50,
        chargeUah = -1L,
        temperatureDeciC = 300,
        voltageMv = 3_900,
        plugType = 0,
    )

    @Test
    fun `keeps the last event of each state track before the window`() {
        val before = listOf(
            event(HistoryKind.SCREEN_ON, 100),
            event(HistoryKind.SCREEN_OFF, 200),
            event(HistoryKind.SCREEN_ON, 300),
            event(HistoryKind.PLUGGED, 150),
            event(HistoryKind.LEVEL, 350),
            event(HistoryKind.SHUTDOWN, 50),
        )
        val inWindow = listOf(event(HistoryKind.LEVEL, 1_000))

        val result = TimelineEvents.withSeeds(before, inWindow)

        assertEquals(
            listOf(HistoryKind.SHUTDOWN, HistoryKind.PLUGGED, HistoryKind.SCREEN_OFF, HistoryKind.SCREEN_ON, HistoryKind.LEVEL),
            result.map { it.kind },
        )
        assertEquals("the screen-on seed is the newest screen-on event", 300L, result[3].wallMs)
        assertEquals("the window's own events follow the seeds untouched", inWindow.single(), result.last())
    }

    @Test
    fun `keeps the last two idle events so a gap that began before the window survives`() {
        val before = listOf(
            event(HistoryKind.DEEP_IDLE_ON, 10),
            event(HistoryKind.DEEP_IDLE_OFF, 20),
            event(HistoryKind.LIGHT_IDLE_ON, 30),
            event(HistoryKind.LIGHT_IDLE_OFF, 40),
        )

        val kinds = TimelineEvents.withSeeds(before, emptyList()).map { it.kind }

        assertEquals(listOf(HistoryKind.LIGHT_IDLE_ON, HistoryKind.LIGHT_IDLE_OFF), kinds)
    }

    @Test
    fun `no history before the window adds nothing`() {
        val inWindow = listOf(event(HistoryKind.SCREEN_ON, 5))

        assertEquals(inWindow, TimelineEvents.withSeeds(emptyList(), inWindow))
    }

    @Test
    fun `the last event of each kind is enough to seed the same state as the full history`() {
        val full = listOf(
            event(HistoryKind.SCREEN_ON, 10),
            event(HistoryKind.SCREEN_OFF, 20),
            event(HistoryKind.SCREEN_ON, 30),
            event(HistoryKind.PLUGGED, 15),
            event(HistoryKind.UNPLUGGED, 25),
            event(HistoryKind.DEEP_IDLE_ON, 12),
            event(HistoryKind.DEEP_IDLE_OFF, 22),
            event(HistoryKind.DEEP_IDLE_ON, 32),
            event(HistoryKind.LIGHT_IDLE_ON, 14),
            event(HistoryKind.LIGHT_IDLE_OFF, 16),
            event(HistoryKind.LEVEL, 40),
        )
        val lastOfEachKind = full.groupBy { it.kind }.values.map { events -> events.maxBy { it.wallMs } }
        val inWindow = listOf(event(HistoryKind.LEVEL, 1_000))

        val fromFull = TimelineEvents.withSeeds(full, inWindow)
        val fromLast = TimelineEvents.withSeeds(lastOfEachKind, inWindow)

        assertEquals("same screen, plug and idle seeds", fromFull.map { it.kind to it.wallMs }, fromLast.map { it.kind to it.wallMs })
        assertEquals(
            listOf(
                HistoryKind.PLUGGED,
                HistoryKind.SCREEN_OFF,
                HistoryKind.DEEP_IDLE_OFF,
                HistoryKind.UNPLUGGED,
                HistoryKind.SCREEN_ON,
                HistoryKind.DEEP_IDLE_ON,
                HistoryKind.LEVEL,
            ),
            fromLast.map { it.kind },
        )
    }

    @Test
    fun `screen activity before the window keeps an overlapping idle exit gap awake`() {
        assertGapIsAwake(HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF)
    }

    @Test
    fun `charging before the window keeps an overlapping idle exit gap awake`() {
        assertGapIsAwake(HistoryKind.PLUGGED, HistoryKind.UNPLUGGED)
    }

    private fun assertGapIsAwake(on: Int, off: Int) {
        val before = listOf(
            event(HistoryKind.DEEP_IDLE_ON, 0),
            event(HistoryKind.DEEP_IDLE_OFF, 100_000),
            event(on, 150_000),
            event(off, 200_000),
        )
        val inWindow = listOf(event(HistoryKind.DEEP_IDLE_ON, 300_000))
        val timeline = TimelineBuilder.build(TimelineEvents.withSeeds(before, inWindow), 250_000, 350_000)

        assertTrue(timeline.segments.none { it.kind == SegmentKind.MAINTENANCE })
        assertEquals(
            listOf(250_000L to 300_000L),
            timeline.segments.filter { it.kind == SegmentKind.AWAKE }.map { it.startMs to it.endMs },
        )
    }

    @Test
    fun `a window that opens mid screen-on and mid charge still shows both lanes`() {
        val before = listOf(event(HistoryKind.PLUGGED, 100), event(HistoryKind.SCREEN_ON, 200))
        val inWindow = listOf(event(HistoryKind.SCREEN_OFF, 2_500))
        val from = 1_000L
        val to = 3_000L

        val timeline = TimelineBuilder.build(TimelineEvents.withSeeds(before, inWindow), from, to)
        val spans = timeline.segments.associate { it.kind to (it.startMs to it.endMs) }

        assertEquals(1_000L to 2_500L, spans[SegmentKind.SCREEN_ON])
        assertEquals(1_000L to 3_000L, spans[SegmentKind.CHARGING])
    }
}
