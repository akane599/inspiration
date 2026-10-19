package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.diagnostics.analysis.SegmentKind.AWAKE
import com.akane.inspiration.diagnostics.analysis.SegmentKind.CHARGING
import com.akane.inspiration.diagnostics.analysis.SegmentKind.DEEP_IDLE
import com.akane.inspiration.diagnostics.analysis.SegmentKind.LIGHT_IDLE
import com.akane.inspiration.diagnostics.analysis.SegmentKind.MAINTENANCE
import com.akane.inspiration.diagnostics.analysis.SegmentKind.SCREEN_ON
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.store.HISTORY_HORIZON_GRACE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBuilderTest {
    private val min = 60_000L
    private val sec = 1_000L
    private val base = 10_000_000L

    private fun at(
        offsetMs: Long,
        kind: Int,
        level: Int = 50,
        deciC: Int = 250,
    ) = HistoryEvent(offsetMs, base + offsetMs, kind, level, -1, deciC, 0, 0)

    private fun seg(
        kind: SegmentKind,
        startMs: Long,
        endMs: Long,
    ) = TimelineSegment(kind, base + startMs, base + endMs)

    private fun build(
        events: List<HistoryEvent>,
        from: Long = 0,
        to: Long = 120 * min,
    ) = TimelineBuilder.build(events, base + from, base + to)

    private fun Timeline.of(kind: SegmentKind) = segments.filter { it.kind == kind }

    private fun Timeline.pulses() = wakePulsesMs.map { it - base }

    /** Deep idle from 1 min to 10 min, then a gap of [gapMs], then deep idle again. */
    private fun deepGap(
        gapMs: Long,
        extra: List<HistoryEvent> = emptyList(),
    ): List<HistoryEvent> =
        extra +
            listOf(
                at(1 * min, HistoryKind.DEEP_IDLE_ON),
                at(10 * min, HistoryKind.DEEP_IDLE_OFF),
                at(10 * min + gapMs, HistoryKind.DEEP_IDLE_ON),
            )

    @Test
    fun `start and reset close all lanes without inventing a new screen or idle state`() {
        for (boundary in listOf(HistoryKind.START, HistoryKind.RESET)) {
            for (initial in listOf(HistoryKind.SCREEN_ON, HistoryKind.DEEP_IDLE_ON, HistoryKind.LIGHT_IDLE_ON)) {
                val timeline = build(listOf(at(min, initial), at(2 * min, boundary)), to = 4 * min)
                assertTrue(timeline.segments.isNotEmpty())
                assertTrue(timeline.segments.all { it.endMs <= base + 2 * min })
            }
            val gap =
                build(
                    listOf(
                        at(min, HistoryKind.DEEP_IDLE_ON),
                        at(2 * min, HistoryKind.DEEP_IDLE_OFF),
                        at(3 * min, boundary),
                    ),
                    to = 5 * min,
                )
            assertTrue(gap.segments.all { it.endMs <= base + 3 * min })
            for (plug in listOf(0, 1)) {
                val timeline =
                    build(
                        listOf(
                            at(min, HistoryKind.PLUGGED),
                            at(2 * min, boundary).copy(plugType = plug),
                        ),
                        to = 4 * min,
                    )
                assertEquals(
                    if (plug == 0) {
                        listOf(seg(CHARGING, min, 2 * min))
                    } else {
                        listOf(seg(CHARGING, min, 2 * min), seg(CHARGING, 2 * min, 4 * min))
                    },
                    timeline.of(CHARGING),
                )
            }
        }
    }

    @Test
    fun `a gap of exactly 10 minutes is maintenance and one millisecond more is awake`() {
        val exact = build(deepGap(10 * min))
        assertEquals(listOf(seg(MAINTENANCE, 10 * min, 20 * min)), exact.of(MAINTENANCE))
        assertTrue(exact.of(AWAKE).isEmpty())

        val over = build(deepGap(10 * min + 1))
        assertTrue(over.of(MAINTENANCE).isEmpty())
        assertEquals(listOf(seg(AWAKE, 10 * min, 20 * min + 1)), over.of(AWAKE))
    }

    @Test
    fun `deep and light idle states become their own segments`() {
        val timeline =
            build(
                listOf(
                    at(5 * min, HistoryKind.LIGHT_IDLE_ON),
                    at(20 * min, HistoryKind.LIGHT_IDLE_OFF),
                    at(20 * min + 30 * sec, HistoryKind.DEEP_IDLE_ON),
                    at(60 * min, HistoryKind.DEEP_IDLE_OFF),
                ),
            )

        assertEquals(listOf(seg(LIGHT_IDLE, 5 * min, 20 * min)), timeline.of(LIGHT_IDLE))
        assertEquals(listOf(seg(DEEP_IDLE, 20 * min + 30 * sec, 60 * min)), timeline.of(DEEP_IDLE))
        // The 30 s gap is maintenance; the open gap runs to `to` and is observed for 60 min, so awake.
        assertEquals(listOf(seg(MAINTENANCE, 20 * min, 20 * min + 30 * sec)), timeline.of(MAINTENANCE))
        assertEquals(listOf(seg(AWAKE, 60 * min, 120 * min)), timeline.of(AWAKE))
    }

    @Test
    fun `light to deep and deep to light switch at one timestamp without a gap or a wake pulse`() {
        val timeline =
            build(
                listOf(
                    at(1 * min, HistoryKind.LIGHT_IDLE_ON),
                    at(10 * min, HistoryKind.LIGHT_IDLE_OFF),
                    at(10 * min, HistoryKind.DEEP_IDLE_ON),
                    at(30 * min, HistoryKind.DEEP_IDLE_OFF),
                    at(30 * min, HistoryKind.LIGHT_IDLE_ON),
                    at(40 * min, HistoryKind.LIGHT_IDLE_OFF),
                    at(40 * min + 5 * sec, HistoryKind.LIGHT_IDLE_ON),
                ),
                to = 50 * min,
            )

        assertEquals(listOf(seg(DEEP_IDLE, 10 * min, 30 * min)), timeline.of(DEEP_IDLE))
        assertEquals(
            listOf(seg(LIGHT_IDLE, 1 * min, 10 * min), seg(LIGHT_IDLE, 30 * min, 40 * min), seg(LIGHT_IDLE, 40 * min + 5 * sec, 50 * min)),
            timeline.of(LIGHT_IDLE),
        )
        // Only the 5 s light-idle blip is a gap; the same-timestamp switches leave none.
        assertEquals(listOf(seg(MAINTENANCE, 40 * min, 40 * min + 5 * sec)), timeline.of(MAINTENANCE))
        assertTrue(timeline.of(AWAKE).isEmpty())
        assertTrue(timeline.wakePulsesMs.isEmpty())
    }

    @Test
    fun `a deep idle exit re-entered in exactly 60 seconds is not a wake pulse and 60 s plus 1 ms is`() {
        // Plugged in, so the gap is never maintenance and the pulse is not excluded by it.
        val plugged = listOf(at(0, HistoryKind.PLUGGED))

        val exact = build(deepGap(60 * sec, plugged))
        assertTrue(exact.wakePulsesMs.isEmpty())

        val over = build(deepGap(60 * sec + 1, plugged))
        assertEquals(listOf(10 * min), over.pulses())
    }

    @Test
    fun `a pulse inside a maintenance gap is dropped, the same pulse in an awake gap is kept`() {
        // Screen off, unplugged, 5 min gap: the DEEP_IDLE_OFF opens the maintenance gap.
        val maintenance = build(deepGap(5 * min))
        assertEquals(listOf(seg(MAINTENANCE, 10 * min, 15 * min)), maintenance.of(MAINTENANCE))
        assertTrue(maintenance.wakePulsesMs.isEmpty())

        // 11 min gap: awake, so the exit is a wake pulse.
        val awake = build(deepGap(11 * min))
        assertEquals(listOf(10 * min), awake.pulses())
    }

    @Test
    fun `maintenance while charging is awake not maintenance`() {
        val before = build(deepGap(5 * min, listOf(at(0, HistoryKind.PLUGGED))))
        assertTrue(before.of(MAINTENANCE).isEmpty())
        assertEquals(listOf(seg(AWAKE, 10 * min, 15 * min)), before.of(AWAKE))
        assertEquals(listOf(seg(CHARGING, 0, 120 * min)), before.of(CHARGING))

        // Plugging in halfway through the gap taints it too.
        val during = build(deepGap(5 * min, listOf(at(12 * min, HistoryKind.PLUGGED))))
        assertTrue(during.of(MAINTENANCE).isEmpty())
        assertEquals(listOf(seg(AWAKE, 10 * min, 15 * min)), during.of(AWAKE))

        // Unplugged again before the gap starts: back to maintenance.
        val unplugged =
            build(
                deepGap(5 * min, listOf(at(0, HistoryKind.PLUGGED), at(5 * min, HistoryKind.UNPLUGGED))),
            )
        assertEquals(listOf(seg(MAINTENANCE, 10 * min, 15 * min)), unplugged.of(MAINTENANCE))
        assertEquals(listOf(seg(CHARGING, 0, 5 * min)), unplugged.of(CHARGING))
    }

    @Test
    fun `a screen-on overlapping the gap makes it awake and overlapping segments are kept as they are`() {
        val timeline =
            build(
                deepGap(
                    5 * min,
                    listOf(
                        at(12 * min, HistoryKind.SCREEN_ON),
                        at(12 * min + 10 * sec, HistoryKind.SCREEN_ON), // duplicate while already on
                        at(20 * min, HistoryKind.SCREEN_OFF),
                    ),
                ),
            )

        assertEquals(listOf(seg(SCREEN_ON, 12 * min, 20 * min)), timeline.of(SCREEN_ON))
        assertEquals(listOf(seg(AWAKE, 10 * min, 15 * min)), timeline.of(AWAKE))
        assertTrue(timeline.of(MAINTENANCE).isEmpty())
        // The deep-idle segment that starts inside the screen-on span is not trimmed.
        assertEquals(
            listOf(seg(DEEP_IDLE, 1 * min, 10 * min), seg(DEEP_IDLE, 15 * min, 120 * min)),
            timeline.of(DEEP_IDLE),
        )
        // Every SCREEN_ON is a pulse, and the deep exit is excluded only if inside maintenance (it is not).
        assertEquals(listOf(10 * min, 12 * min, 12 * min + 10 * sec), timeline.pulses())
    }

    @Test
    fun `a screen-on that only touches the gap end leaves it maintenance`() {
        val timeline = build(deepGap(5 * min, listOf(at(15 * min, HistoryKind.SCREEN_ON), at(16 * min, HistoryKind.SCREEN_OFF))))

        assertEquals(listOf(seg(MAINTENANCE, 10 * min, 15 * min)), timeline.of(MAINTENANCE))
        // The screen-on pulse sits at the exclusive gap end, so it is kept.
        assertEquals(listOf(15 * min), timeline.pulses())
    }

    @Test
    fun `a screen-on and a deep idle exit at one instant make one pulse`() {
        val timeline =
            build(
                listOf(
                    at(1 * min, HistoryKind.DEEP_IDLE_ON),
                    at(10 * min, HistoryKind.DEEP_IDLE_OFF),
                    at(10 * min, HistoryKind.SCREEN_ON),
                ),
            )

        assertEquals(listOf(10 * min), timeline.pulses())
    }

    @Test
    fun `state open at from comes from earlier events and everything is clamped to the window`() {
        val timeline =
            build(
                listOf(
                    at(0, HistoryKind.SCREEN_ON),
                    at(10 * min, HistoryKind.DEEP_IDLE_ON),
                    at(60 * min, HistoryKind.SCREEN_OFF),
                    at(90 * min, HistoryKind.DEEP_IDLE_OFF),
                    at(200 * min, HistoryKind.DEEP_IDLE_ON), // after `to`: ignored
                ),
                from = 30 * min,
                to = 100 * min,
            )

        assertEquals(listOf(seg(SCREEN_ON, 30 * min, 60 * min)), timeline.of(SCREEN_ON))
        assertEquals(listOf(seg(DEEP_IDLE, 30 * min, 90 * min)), timeline.of(DEEP_IDLE))
        // The gap runs to `to` (10 min exactly, screen off).
        assertEquals(listOf(seg(MAINTENANCE, 90 * min, 100 * min)), timeline.of(MAINTENANCE))
        assertTrue(timeline.segments.all { it.startMs >= base + 30 * min && it.endMs <= base + 100 * min })
        assertEquals(base + 30 * min, timeline.from)
        assertEquals(base + 100 * min, timeline.to)
    }

    @Test
    fun `a gap that began before from keeps its true length for the maintenance test`() {
        val events =
            listOf(
                at(1 * min, HistoryKind.DEEP_IDLE_ON),
                at(10 * min, HistoryKind.DEEP_IDLE_OFF),
                at(23 * min, HistoryKind.DEEP_IDLE_ON),
            )

        // 13 min in total: awake, although only 8 min of it is inside the window.
        val timeline = build(events, from = 15 * min, to = 60 * min)

        assertEquals(listOf(seg(AWAKE, 15 * min, 23 * min)), timeline.of(AWAKE))
        assertTrue(timeline.of(MAINTENANCE).isEmpty())
        // The exit itself is before `from`, so it is not a pulse of this window.
        assertTrue(timeline.wakePulsesMs.isEmpty())
    }

    @Test
    fun `shutdown preserves an already observed awake gap`() {
        val timeline =
            build(
                listOf(
                    at(0, HistoryKind.DEEP_IDLE_ON),
                    at(1 * min, HistoryKind.DEEP_IDLE_OFF),
                    at(20 * min, HistoryKind.SHUTDOWN),
                ),
            )
        assertEquals(listOf(seg(AWAKE, 1 * min, 20 * min)), timeline.of(AWAKE))
    }

    @Test
    fun `a shutdown ends open states and starts no gap`() {
        val timeline =
            build(
                listOf(
                    at(1 * min, HistoryKind.SCREEN_ON),
                    at(2 * min, HistoryKind.PLUGGED),
                    at(3 * min, HistoryKind.DEEP_IDLE_ON),
                    at(10 * min, HistoryKind.SHUTDOWN),
                    at(50 * min, HistoryKind.START),
                ),
            )

        assertEquals(listOf(seg(SCREEN_ON, 1 * min, 10 * min)), timeline.of(SCREEN_ON))
        assertEquals(listOf(seg(CHARGING, 2 * min, 10 * min)), timeline.of(CHARGING))
        assertEquals(listOf(seg(DEEP_IDLE, 3 * min, 10 * min)), timeline.of(DEEP_IDLE))
        assertTrue(timeline.of(MAINTENANCE).isEmpty() && timeline.of(AWAKE).isEmpty())
    }

    @Test
    fun `level and temperature points come from LEVEL events inside the window only`() {
        val timeline =
            build(
                listOf(
                    at(0, HistoryKind.LEVEL, level = 80, deciC = 300),
                    at(10 * min, HistoryKind.LEVEL, level = 79, deciC = 305),
                    at(10 * min, HistoryKind.SCREEN_ON, level = 79, deciC = 305),
                    at(20 * min, HistoryKind.LEVEL, level = 78, deciC = 312),
                    HistoryEvent(0, -1L, HistoryKind.LEVEL, 99, -1, 999, 0, 0), // unknown wall time
                    at(500 * min, HistoryKind.LEVEL, level = 1, deciC = 100), // after `to`
                ),
                from = 5 * min,
                to = 60 * min,
            )

        assertEquals(
            listOf(TimelinePoint(base + 10 * min, 79f), TimelinePoint(base + 20 * min, 78f)),
            timeline.levelPoints,
        )
        assertEquals(
            listOf(TimelinePoint(base + 10 * min, 30.5f), TimelinePoint(base + 20 * min, 31.2f)),
            timeline.temperaturePoints,
        )
    }

    @Test
    fun `segments are sorted by start then kind and unsorted input is ordered by wall time`() {
        val shuffled =
            listOf(
                at(30 * min, HistoryKind.SCREEN_OFF),
                at(10 * min, HistoryKind.SCREEN_ON),
                at(10 * min, HistoryKind.PLUGGED),
            )

        val timeline = build(shuffled)

        assertEquals(
            listOf(seg(SCREEN_ON, 10 * min, 30 * min), seg(CHARGING, 10 * min, 120 * min)),
            timeline.segments,
        )
    }

    @Test
    fun `an empty input or a backwards window yields an empty timeline`() {
        val empty = build(emptyList())
        assertTrue(empty.segments.isEmpty() && empty.levelPoints.isEmpty() && empty.wakePulsesMs.isEmpty())

        val backwards = TimelineBuilder.build(deepGap(5 * min), base + 60 * min, base + 30 * min)
        assertTrue(backwards.segments.isEmpty() && backwards.wakePulsesMs.isEmpty())
    }

    @Test
    fun `open lanes close at the history horizon instead of the window end`() {
        val events =
            listOf(
                at(10 * min, HistoryKind.SCREEN_ON),
                at(10 * min, HistoryKind.PLUGGED),
                at(20 * min, HistoryKind.SCREEN_OFF),
                at(25 * min, HistoryKind.DEEP_IDLE_ON),
            )

        val timeline = TimelineBuilder.build(events, base, base + 120 * min, historyHorizonMs = base + 40 * min)

        assertEquals(
            listOf(seg(SCREEN_ON, 10 * min, 20 * min), seg(CHARGING, 10 * min, 40 * min), seg(DEEP_IDLE, 25 * min, 40 * min)),
            timeline.segments,
        )
        assertEquals(base + 120 * min, timeline.to)
    }

    @Test
    fun `a horizon before the newest event closes lanes at that event and one past the window changes nothing`() {
        val events = listOf(at(10 * min, HistoryKind.PLUGGED), at(30 * min, HistoryKind.LEVEL))

        assertEquals(
            listOf(seg(CHARGING, 10 * min, 30 * min)),
            TimelineBuilder.build(events, base, base + 120 * min, historyHorizonMs = Long.MIN_VALUE).segments,
        )
        assertEquals(build(events), TimelineBuilder.build(events, base, base + 120 * min, historyHorizonMs = Long.MAX_VALUE))
    }

    @Test
    fun `an open idle-exit gap ends at the horizon`() {
        val events = listOf(at(1 * min, HistoryKind.DEEP_IDLE_ON), at(10 * min, HistoryKind.DEEP_IDLE_OFF))

        val timeline = TimelineBuilder.build(events, base, base + 120 * min, historyHorizonMs = base + 60 * min)

        assertEquals(listOf(seg(AWAKE, 10 * min, 60 * min)), timeline.of(AWAKE))
    }

    @Test
    fun `lanes stay open to the window end within the horizon grace and close at the horizon past it`() {
        val events = listOf(at(10 * min, HistoryKind.PLUGGED))
        val horizon = base + 40 * min

        val within = TimelineBuilder.build(events, base, horizon + HISTORY_HORIZON_GRACE_MS, historyHorizonMs = horizon)
        assertEquals(listOf(TimelineSegment(CHARGING, base + 10 * min, horizon + HISTORY_HORIZON_GRACE_MS)), within.segments)

        val past = TimelineBuilder.build(events, base, horizon + 10 * min, historyHorizonMs = horizon)
        assertEquals(listOf(seg(CHARGING, 10 * min, 40 * min)), past.segments)
    }

    @Test
    fun `the thresholds are the documented constants`() {
        assertEquals(10 * min, TimelineBuilder.MAINTENANCE_MAX_GAP_MS)
        assertEquals(60 * sec, TimelineBuilder.WAKE_PULSE_REENTRY_MS)
    }
}
