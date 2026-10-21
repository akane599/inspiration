package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.store.HISTORY_HORIZON_GRACE_MS
import com.akane.inspiration.telemetry.store.knownStateEndWallMs

/**
 * Turns battery-history events into a [Timeline]. Pure: no Android types, no clock, no I/O.
 *
 * Rules (docs/plans/phase-5.md H6):
 * - **Input.** Events with an unknown wall time (`wallMs < 0`) and events after `to` are ignored. The
 *   rest are ordered by `wallMs`; ties keep input order, so a light-to-deep doze change stays
 *   OFF-then-ON. Every state (screen, plug, doze) is off before the first event, so a caller that
 *   wants the state open at `from` includes the last event before `from`; those earlier events
 *   only seed state, and everything emitted is clamped to [from, to].
 * - **Horizon.** Open states end at the history horizon (see [build]), not at `to`, when it is earlier.
 * - **Maintenance.** A *gap* runs from an `*_IDLE_OFF` to the next `*_IDLE_ON` (or to the lane end when none
 *   was seen). A gap of at most [MAINTENANCE_MAX_GAP_MS] that overlaps no screen-on or charging span
 *   is MAINTENANCE; any other non-empty gap is AWAKE. The gap length is measured unclamped, so a
 *   gap that began before `from` keeps its true length. A SHUTDOWN ends every open state and starts no gap.
 * - **Wake pulses.** Every SCREEN_ON except the one that restates a RESET's screen state right after it, plus every
 *   DEEP_IDLE_OFF with no `*_IDLE_ON` within
 *   [WAKE_PULSE_REENTRY_MS] (inclusive; light idle counts, so deep-to-light is not a pulse). A pulse
 *   inside a MAINTENANCE gap (start inclusive, end exclusive) is dropped. No cause is claimed.
 * - **Points.** Level samples come only from LEVEL events in (from, to]; temperature samples come from every event
 *   except synthetic START records and convert deci-Celsius / 10. Equal-time samples keep the last eligible event.
 *   Both series include the value in force at `from` (from the newest event at or before it, unless it is a START)
 *   and the last value held to where the history is known complete (the lane end).
 */
object TimelineBuilder {
    /** Longest idle-exit gap that still counts as a maintenance window (inclusive). */
    const val MAINTENANCE_MAX_GAP_MS = 10 * 60_000L

    /** An idle re-entry this soon (inclusive) after a DEEP_IDLE_OFF means it was not a wake pulse. */
    const val WAKE_PULSE_REENTRY_MS = 60_000L

    /**
     * [historyHorizonMs] is the wall time up to which the stored history is known to be complete (the last
     * successful ingest). Open lanes close there, or at the newest event when that is later, and the last level and
     * temperature are held only to there, so the stale last state is not drawn on to [to]; the window past it stays
     * empty. A gap of at most [HISTORY_HORIZON_GRACE_MS]
     * before [to] still keeps lanes open to [to]. The default, [to], keeps every lane open to it.
     */
    fun build(
        events: List<HistoryEvent>,
        from: Long,
        to: Long,
        historyHorizonMs: Long = to,
    ): Timeline {
        if (to < from) return Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)
        val ordered = events.filter { it.wallMs in 0..to }.sortedBy { it.wallMs }
        val knownTo = maxOf(historyHorizonMs, ordered.lastOrNull()?.wallMs ?: Long.MIN_VALUE)
        val knownEnd = knownStateEndWallMs(knownTo, to)
        val laneEnd = knownEnd.coerceIn(from, to)
        val spans = Spans.scan(ordered, laneEnd)
        // A set, so sorting the remaining gaps into AWAKE is one lookup per gap; it keeps the gaps' time order.
        val maintenance = spans.gaps.filterTo(LinkedHashSet()) { it.isMaintenance(spans) }
        // History known only up to before the window says nothing about the level inside it.
        val carryIn = from <= knownEnd
        return Timeline(
            segments = segments(spans, maintenance, from, to),
            levelPoints = points(ordered, from, laneEnd, carryIn) { it.levelPct.toFloat() },
            temperaturePoints = points(ordered, from, laneEnd, carryIn, sample = { it.kind != HistoryKind.START }) { it.temperatureDeciC / 10f },
            wakePulsesMs = wakePulses(ordered, maintenance, from, to),
            from = from,
            to = to,
        )
    }

    private fun Span.isMaintenance(spans: Spans): Boolean =
        endMs - startMs <= MAINTENANCE_MAX_GAP_MS &&
            spans.screen.none { overlaps(it) } && spans.charging.none { overlaps(it) }

    private fun segments(
        spans: Spans,
        maintenance: Set<Span>,
        from: Long,
        to: Long,
    ): List<TimelineSegment> {
        val all = ArrayList<TimelineSegment>()

        fun add(
            kind: SegmentKind,
            list: Collection<Span>,
        ) {
            list.forEach { span ->
                val start = maxOf(span.startMs, from)
                val end = minOf(span.endMs, to)
                if (end > start) all += TimelineSegment(kind, start, end)
            }
        }
        add(SegmentKind.SCREEN_ON, spans.screen)
        add(SegmentKind.CHARGING, spans.charging)
        add(SegmentKind.DEEP_IDLE, spans.deep)
        add(SegmentKind.LIGHT_IDLE, spans.light)
        add(SegmentKind.MAINTENANCE, maintenance)
        add(SegmentKind.AWAKE, spans.gaps.filter { it !in maintenance })
        return all.sortedWith(compareBy({ it.startMs }, { it.kind.ordinal }))
    }

    /**
     * A point for each event accepted by [sample] in `(from, to]` ([ordered] ends at `to`); the last accepted event
     * wins at equal timestamps. Every event carries its record's level and temperature, so with [carryIn] the newest
     * event at or before [from] (a caller's seed) gives the value in force as the window opens, unless it is a START:
     * that record is written before the battery service reports anything, so its zeroed values are no evidence.
     * The last value is then held to [holdTo], where the history is known complete, never past it.
     */
    private fun points(
        ordered: List<HistoryEvent>,
        from: Long,
        holdTo: Long,
        carryIn: Boolean,
        sample: (HistoryEvent) -> Boolean = { it.kind == HistoryKind.LEVEL },
        value: (HistoryEvent) -> Float,
    ): List<TimelinePoint> {
        val out = ArrayList<TimelinePoint>()
        if (carryIn) {
            ordered
                .lastOrNull { it.wallMs <= from }
                ?.takeIf { it.kind != HistoryKind.START }
                ?.let { out += TimelinePoint(from, value(it)) }
        }
        ordered.asSequence()
            .filter { sample(it) && it.wallMs > from }
            .forEach { event ->
                val point = TimelinePoint(event.wallMs, value(event))
                if (out.lastOrNull()?.tMs == point.tMs) {
                    out[out.lastIndex] = point
                } else {
                    out += point
                }
            }
        out.lastOrNull()?.let { last -> if (holdTo > last.tMs) out += TimelinePoint(holdTo, last.value) }
        return out
    }

    private fun wakePulses(
        ordered: List<HistoryEvent>,
        maintenance: Set<Span>,
        from: Long,
        to: Long,
    ): List<Long> {
        val idleOns =
            ordered
                .filter { it.kind == HistoryKind.DEEP_IDLE_ON || it.kind == HistoryKind.LIGHT_IDLE_ON }
                .map { it.wallMs }
        var next = 0
        val pulses = ArrayList<Long>()
        for ((index, event) in ordered.withIndex()) {
            val t = event.wallMs
            when (event.kind) {
                HistoryKind.SCREEN_ON -> {
                    if (!ordered.restatesReset(index)) pulses += t
                }

                HistoryKind.DEEP_IDLE_OFF -> {
                    // `ordered` is time-sorted, so the first idle entry at or after t only moves forward.
                    while (next < idleOns.size && idleOns[next] < t) next++
                    val reentered = next < idleOns.size && idleOns[next] - t <= WAKE_PULSE_REENTRY_MS
                    if (!reentered) pulses += t
                }
            }
        }
        return pulses
            .filter { t -> t in from..to && maintenance.none { t >= it.startMs && t < it.endMs } }
            .distinct()
    }

    /** Whether the event at [index] is the screen state its RESET restates (same record), not a transition. */
    private fun List<HistoryEvent>.restatesReset(index: Int): Boolean {
        val previous = getOrNull(index - 1) ?: return false
        val event = get(index)
        return previous.kind == HistoryKind.RESET &&
            previous.historyTimeMs == event.historyTimeMs &&
            previous.wallMs == event.wallMs
    }
}

/** A half-open `[startMs, endMs)` span; unclamped, as seen in the events. */
private data class Span(
    val startMs: Long,
    val endMs: Long,
) {
    fun overlaps(other: Span): Boolean = startMs < other.endMs && other.startMs < endMs
}

/** One state's spans: [open] is idempotent, [close] emits the span, closing an unopened lane is a no-op. */
private class StateLane {
    private val closed = ArrayList<Span>()
    private var openAt: Long? = null
    val isOpen get() = openAt != null

    /** Closed spans with the empty ones (open and close at one instant) removed. */
    val spans: List<Span> get() = closed.filter { it.endMs > it.startMs }

    fun open(t: Long) {
        if (openAt == null) openAt = t
    }

    fun close(t: Long) {
        openAt?.let { closed += Span(it, t) }
        openAt = null
    }
}

/** Every lane's spans for one event list; open spans end at the window end. */
private class Spans(
    val screen: List<Span>,
    val charging: List<Span>,
    val deep: List<Span>,
    val light: List<Span>,
    val gaps: List<Span>,
) {
    companion object {
        fun scan(
            ordered: List<HistoryEvent>,
            end: Long,
        ): Spans {
            val screen = StateLane()
            val plug = StateLane()
            val deep = StateLane()
            val light = StateLane()
            val gaps = StateLane()
            for (event in ordered) {
                val t = event.wallMs
                when (event.kind) {
                    HistoryKind.SCREEN_ON -> {
                        screen.open(t)
                    }

                    HistoryKind.SCREEN_OFF -> {
                        screen.close(t)
                    }

                    HistoryKind.PLUGGED -> {
                        plug.open(t)
                    }

                    HistoryKind.UNPLUGGED -> {
                        plug.close(t)
                    }

                    HistoryKind.RESET, HistoryKind.START -> {
                        // A boundary: the boundary itself carries the plug state. A RESET's screen and doze state
                        // follow it at the same instant (ties keep input order) and reopen their lanes; START has none.
                        listOf(screen, plug, deep, light, gaps).forEach { it.close(t) }
                        if (event.plugType != 0) plug.open(t)
                    }

                    HistoryKind.DEEP_IDLE_ON -> {
                        light.close(t)
                        deep.open(t)
                        gaps.close(t)
                    }

                    HistoryKind.LIGHT_IDLE_ON -> {
                        deep.close(t)
                        light.open(t)
                        gaps.close(t)
                    }

                    HistoryKind.DEEP_IDLE_OFF -> {
                        if (deep.isOpen) {
                            deep.close(t)
                            gaps.open(t)
                        }
                    }

                    HistoryKind.LIGHT_IDLE_OFF -> {
                        if (light.isOpen) {
                            light.close(t)
                            gaps.open(t)
                        }
                    }

                    HistoryKind.SHUTDOWN -> {
                        listOf(screen, plug, deep, light).forEach { it.close(t) }
                        gaps.close(t) // Preserve the observed gap, without opening a new one.
                    }
                }
            }
            listOf(screen, plug, deep, light, gaps).forEach { it.close(end) }
            return Spans(screen.spans, plug.spans, deep.spans, light.spans, gaps.spans)
        }
    }
}
