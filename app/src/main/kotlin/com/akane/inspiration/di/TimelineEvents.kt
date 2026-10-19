package com.akane.inspiration.di

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind

/**
 * Builds the event list [com.akane.inspiration.diagnostics.analysis.TimelineBuilder] needs for a window
 * starting at `from`. The builder treats every state as off before its first event, so a window that
 * opens while the screen is on, the device is plugged in or Doze is active would otherwise lose those
 * spans. Its KDoc asks the caller to include the last event before `from`; one event only seeds one
 * state, so this keeps the last transitions of each state track (screen, plug, idle, shutdown). The seeds are
 * older than the window, so the builder clamps everything it emits to [from, to].
 */
object TimelineEvents {
    private val screen = setOf(HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF)
    private val plug = setOf(HistoryKind.PLUGGED, HistoryKind.UNPLUGGED)
    private val idle =
        setOf(
            HistoryKind.DEEP_IDLE_ON,
            HistoryKind.DEEP_IDLE_OFF,
            HistoryKind.LIGHT_IDLE_ON,
            HistoryKind.LIGHT_IDLE_OFF,
        )

    /**
     * [before] are events older than the window (any order, but events at one wall time in history order, as the store
     * returns them); [inWindow] are the window's own events.
     * The last two idle events are kept: an `*_IDLE_OFF` only opens a maintenance gap when the matching
     * `*_IDLE_ON` came first.
     * Screen and plug tracks keep the last event of each kind: their preceding closed span may
     * overlap that idle-exit gap, making it awake rather than maintenance even before the window.
     */
    fun withSeeds(
        before: List<HistoryEvent>,
        inWindow: List<HistoryEvent>,
    ): List<HistoryEvent> {
        val ordered = before.sortedBy { it.wallMs }
        val tracks = screen + plug + setOf(HistoryKind.SHUTDOWN, HistoryKind.RESET, HistoryKind.START)
        val seeds =
            tracks.mapNotNull { kind -> ordered.indexOfLast { it.kind == kind }.takeIf { it >= 0 } } +
                ordered.indices.filter { ordered[it].kind in idle }.takeLast(IDLE_SEEDS)
        // Seeds keep the input order at equal wall times: a RESET's screen and doze state follow it at its instant.
        return seeds.sorted().map(ordered::get) + inWindow
    }

    private const val IDLE_SEEDS = 2
}
