package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind

/**
 * Turns battery-history events into a [Timeline]. Pure: no Android types, no clock, no I/O.
 *
 * Rules (docs/plans/phase-5.md H6):
 * - **Input.** Events with an unknown wall time (`wallMs < 0`) and events after `to` are ignored. The
 *   rest are ordered by `wallMs`; ties keep input order, so a light-to-deep doze change stays
 *   OFF-then-ON. Every state (screen, plug, doze) is off before the first event, so a caller that
 *   wants the state open at `from` includes the last event before `from`; those earlier events
 *   only seed state, and everything emitted is clamped to [from, to].
 * - **Maintenance.** A *gap* runs from an `*_IDLE_OFF` to the next `*_IDLE_ON` (or to `to` when none
 *   was seen). A gap of at most [MAINTENANCE_MAX_GAP_MS] that overlaps no screen-on or charging span
 *   is MAINTENANCE; any other non-empty gap is AWAKE. The gap length is measured unclamped, so a
 *   gap that began before `from` keeps its true length. A SHUTDOWN ends every open state and starts no gap.
 * - **Wake pulses.** Every SCREEN_ON, plus every DEEP_IDLE_OFF with no `*_IDLE_ON` within
 *   [WAKE_PULSE_REENTRY_MS] (inclusive; light idle counts, so deep-to-light is not a pulse). A pulse
 *   inside a MAINTENANCE gap (start inclusive, end exclusive) is dropped. No cause is claimed.
 * - **Points.** One level point per LEVEL event in [from, to]; temperature is deci-Celsius / 10.
 */
object TimelineBuilder {
    /** Longest idle-exit gap that still counts as a maintenance window (inclusive). */
    const val MAINTENANCE_MAX_GAP_MS = 10 * 60_000L

    /** An idle re-entry this soon (inclusive) after a DEEP_IDLE_OFF means it was not a wake pulse. */
    const val WAKE_PULSE_REENTRY_MS = 60_000L

    fun build(events: List<HistoryEvent>, from: Long, to: Long): Timeline {
        if (to < from) return Timeline(emptyList(), emptyList(), emptyList(), emptyList(), from, to)
        val ordered = events.filter { it.wallMs in 0..to }.sortedBy { it.wallMs }
        val spans = Spans.scan(ordered, to)
        val maintenance = spans.gaps.filter { it.isMaintenance(spans) }
        return Timeline(
            segments = segments(spans, maintenance, from, to),
            levelPoints = points(ordered, from, to) { it.levelPct.toFloat() },
            temperaturePoints = points(ordered, from, to) { it.temperatureDeciC / 10f },
            wakePulsesMs = wakePulses(ordered, maintenance, from, to),
            from = from,
            to = to,
        )
    }

    private fun Span.isMaintenance(spans: Spans): Boolean =
        endMs - startMs <= MAINTENANCE_MAX_GAP_MS &&
            spans.screen.none { overlaps(it) } && spans.charging.none { overlaps(it) }

    private fun segments(spans: Spans, maintenance: List<Span>, from: Long, to: Long): List<TimelineSegment> {
        val all = ArrayList<TimelineSegment>()
        fun add(kind: SegmentKind, list: List<Span>) {
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

    private fun points(
        ordered: List<HistoryEvent>,
        from: Long,
        to: Long,
        value: (HistoryEvent) -> Float,
    ): List<TimelinePoint> = ordered
        .filter { it.kind == HistoryKind.LEVEL && it.wallMs in from..to }
        .map { TimelinePoint(it.wallMs, value(it)) }

    private fun wakePulses(ordered: List<HistoryEvent>, maintenance: List<Span>, from: Long, to: Long): List<Long> {
        val idleOns = ordered.filter { it.kind == HistoryKind.DEEP_IDLE_ON || it.kind == HistoryKind.LIGHT_IDLE_ON }
            .map { it.wallMs }
        var next = 0
        val pulses = ArrayList<Long>()
        for (event in ordered) {
            val t = event.wallMs
            when (event.kind) {
                HistoryKind.SCREEN_ON -> pulses += t
                HistoryKind.DEEP_IDLE_OFF -> {
                    // `ordered` is time-sorted, so the first idle entry at or after t only moves forward.
                    while (next < idleOns.size && idleOns[next] < t) next++
                    val reentered = next < idleOns.size && idleOns[next] - t <= WAKE_PULSE_REENTRY_MS
                    if (!reentered) pulses += t
                }
            }
        }
        return pulses.filter { t -> t in from..to && maintenance.none { t >= it.startMs && t < it.endMs } }
            .distinct()
    }
}

/** A half-open `[startMs, endMs)` span; unclamped, as seen in the events. */
private data class Span(val startMs: Long, val endMs: Long) {
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
        fun scan(ordered: List<HistoryEvent>, end: Long): Spans {
            val screen = StateLane()
            val plug = StateLane()
            val deep = StateLane()
            val light = StateLane()
            val gaps = StateLane()
            for (event in ordered) {
                val t = event.wallMs
                when (event.kind) {
                    HistoryKind.SCREEN_ON -> screen.open(t)
                    HistoryKind.SCREEN_OFF -> screen.close(t)
                    HistoryKind.PLUGGED -> plug.open(t)
                    HistoryKind.UNPLUGGED -> plug.close(t)
                    HistoryKind.RESET, HistoryKind.START -> {
                        // The fresh baseline carries plug state, but no screen or idle state.
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
                    HistoryKind.DEEP_IDLE_OFF -> if (deep.isOpen) {
                        deep.close(t)
                        gaps.open(t)
                    }
                    HistoryKind.LIGHT_IDLE_OFF -> if (light.isOpen) {
                        light.close(t)
                        gaps.open(t)
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
