package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.SnapshotStore

sealed interface ReferenceKind {
    data object SinceUnplugged : ReferenceKind

    data object SinceScreenOff : ReferenceKind

    data object ScreenOnSession : ReferenceKind

    data object Last24h : ReferenceKind

    data class CustomMarker(
        val markerId: Long,
    ) : ReferenceKind

    data class TimeSlice(
        val fromWallMs: Long,
        val toWallMs: Long,
    ) : ReferenceKind
}

data class ResolvedReference(
    val ref: BatterySnapshot,
    val now: BatterySnapshot,
    val boundaryFromWallMs: Long,
    val boundaryToWallMs: Long,
    val coveredFromWallMs: Long,
    val coveredToWallMs: Long,
    val gapMs: Long,
    val perUidAvailable: Boolean,
)

class ReferenceResolver(
    private val store: SnapshotStore,
) {
    suspend fun resolve(
        kind: ReferenceKind,
        nowWallMs: Long,
    ): ResolvedReference? = resolve(kind, nowWallMs, historyUpTo(nowWallMs))

    /**
     * Resolves against [events], the stored history up to [nowWallMs] oldest first as [historyUpTo] returns it,
     * so a caller that needs that history too loads it once.
     */
    suspend fun resolve(
        kind: ReferenceKind,
        nowWallMs: Long,
        events: List<HistoryEvent>,
    ): ResolvedReference? {
        val boundary = boundaryFor(kind, nowWallMs, events) ?: return null
        if (boundary.fromWallMs > boundary.toWallMs) return null

        val end = store.lastAtOrBefore(boundary.toWallMs, null) ?: return null
        // Resolution needs a snapshot at or before now. `end` is one unless it lies past now (only a boundary that
        // ends after now allows that), so a boundary ending at now loads that snapshot once, not twice.
        if (end.clock.wallMs > nowWallMs && store.lastAtOrBefore(nowWallMs, null) == null) return null
        val ref = boundary.markerSnapshot ?: closestReference(boundary.fromWallMs, end) ?: return null
        // A marker's own snapshot must precede `end` like a closestReference result: right after "Mark now" it is
        // still the newest snapshot, and pairing it with itself is a zero-length interval, not a measurement.
        // Across a reboot elapsed time is not comparable, so that pair stays and the differ flags it.
        if (ref.clock.bootCount == end.clock.bootCount && !ref.precedes(end)) return null
        if (ref.clock.wallMs > end.clock.wallMs) return null

        val coveredFrom = ref.clock.wallMs
        val coveredTo = end.clock.wallMs
        return ResolvedReference(
            ref = ref,
            now = end,
            boundaryFromWallMs = boundary.fromWallMs,
            boundaryToWallMs = boundary.toWallMs,
            coveredFromWallMs = coveredFrom,
            coveredToWallMs = coveredTo,
            gapMs =
                (coveredFrom - boundary.fromWallMs).coerceAtLeast(0) +
                    (boundary.toWallMs - coveredTo).coerceAtLeast(0),
            perUidAvailable = ref.kind == SnapshotKind.FULL && end.kind == SnapshotKind.FULL,
        )
    }

    /** The stored history with `0 <= wallMs <= nowWallMs`, oldest first. */
    suspend fun historyUpTo(nowWallMs: Long): List<HistoryEvent> =
        store
            .historyBetween(0, nowWallMs)
            .filter { it.wallMs in 0..nowWallMs }
            .sortedBy(HistoryEvent::wallMs)

    private suspend fun boundaryFor(
        kind: ReferenceKind,
        nowWallMs: Long,
        events: List<HistoryEvent>,
    ): Boundary? =
        when (kind) {
            ReferenceKind.SinceUnplugged -> {
                sinceLatest(events.lastOrNull { it.kind == HistoryKind.UNPLUGGED }?.wallMs, CaptureTrigger.POWER_DISCONNECTED, nowWallMs)
            }

            ReferenceKind.SinceScreenOff -> {
                sinceScreenOff(events, nowWallMs)
            }

            ReferenceKind.ScreenOnSession -> {
                // A RESET with the screen on restates it as a SCREEN_ON at the reset (see HistoryKind.RESET), so a
                // session that was already on then starts at the reset; battery-stats deltas cannot cross a reset anyway.
                sinceLatest(events.lastOrNull { it.kind == HistoryKind.SCREEN_ON }?.wallMs, CaptureTrigger.SCREEN_ON, nowWallMs)
            }

            ReferenceKind.Last24h -> {
                Boundary(nowWallMs - DAY_MS, nowWallMs)
            }

            is ReferenceKind.CustomMarker -> {
                store.marker(kind.markerId)?.let { (wallMs, snapshot) ->
                    if (wallMs <= nowWallMs) Boundary(wallMs, nowWallMs, snapshot) else null
                }
            }

            is ReferenceKind.TimeSlice -> {
                Boundary(kind.fromWallMs, kind.toWallMs)
            }
        }

    /**
     * The earliest FULL snapshot after the boundary in [end]'s session, else the earliest snapshot of any kind
     * (per-UID data then unavailable). Never [end] itself: when it is the only snapshot after the boundary the
     * interval cannot be measured and the result is null, never a zero-length self-pair.
     */
    private suspend fun closestReference(
        boundaryWallMs: Long,
        end: BatterySnapshot,
    ): BatterySnapshot? =
        store
            .firstAtOrAfter(
                wallMs = boundaryWallMs,
                kind = SnapshotKind.FULL,
                statsStartMs = end.clock.statsStartMs,
                bootCount = end.clock.bootCount,
            )?.takeIf { it.precedes(end) } ?: store
            .firstAtOrAfter(
                wallMs = boundaryWallMs,
                kind = null,
                statsStartMs = null,
                bootCount = end.clock.bootCount,
            )?.takeIf { it.precedes(end) }

    /**
     * Strictly earlier than [end] in the same boot; [end] itself, or a row captured at the same instant, is not.
     * Every same-boot reference must satisfy it, a marker's snapshot included.
     */
    private fun BatterySnapshot.precedes(end: BatterySnapshot): Boolean =
        clock.wallMs <= end.clock.wallMs &&
            clock.bootCount == end.clock.bootCount &&
            clock.elapsedRealtimeMs < end.clock.elapsedRealtimeMs

    /**
     * From the newest history event ([historyWallMs]) or the newest [trigger] capture to now (see
     * [CAPTURE_SLACK_MS] for how the two relate). The capture starts the window only when it is more than the slack
     * past both that event and the history horizon: no ingest ran after it, so it is a newer event the history
     * lacks (no FULL read since, or Shizuku down). A capture within the slack, or an earlier one, belongs to an
     * event the history already holds (a deferred capture is delivered at the activation whose ingest moves the
     * horizon), and the history time starts the window.
     */
    private suspend fun sinceLatest(
        historyWallMs: Long?,
        trigger: CaptureTrigger,
        nowWallMs: Long,
    ): Boundary? {
        val captureWallMs = store.latestClockByTrigger(trigger)?.wallMs?.takeIf { it <= nowWallMs }
        val start =
            when {
                historyWallMs == null -> captureWallMs
                captureWallMs == null -> historyWallMs
                captureWallMs > historyWallMs + CAPTURE_SLACK_MS && pastHistoryHorizon(captureWallMs) -> captureWallMs
                else -> historyWallMs
            }
        return start?.let { Boundary(it, nowWallMs) }
    }

    private suspend fun pastHistoryHorizon(captureWallMs: Long): Boolean =
        store.historyHorizonWallMs()?.let { horizon -> captureWallMs > horizon + CAPTURE_SLACK_MS } ?: true

    /**
     * The newest completed screen-off period. A captured period that starts after the history's newest completed
     * one ended is newer than anything the history holds, and wins. Otherwise the history times the period, and
     * it ends at the first snapshot within [CAPTURE_SLACK_MS] after the history's SCREEN_ON (normally that
     * broadcast's own capture), so the snapshot that closes the period is inside the window, not just past it.
     */
    private suspend fun sinceScreenOff(
        events: List<HistoryEvent>,
        nowWallMs: Long,
    ): Boundary? {
        val history = completedScreenOff(events) ?: return completedScreenOffFromSnapshots(nowWallMs)
        val captured = completedScreenOffFromSnapshots(nowWallMs)
        if (captured != null && captured.fromWallMs > history.toWallMs) return captured
        val anchorWallMs =
            store
                .snapshotsBetween(history.toWallMs, minOf(history.toWallMs + CAPTURE_SLACK_MS, nowWallMs))
                .minOfOrNull { it.clock.wallMs }
        return anchorWallMs?.let { history.copy(toWallMs = it) } ?: history
    }

    /**
     * The newest SCREEN_OFF to SCREEN_ON pair in [events]. The screen state a RESET restates at its instant counts as
     * a transition, so a period that spans a reset is cut at it: it starts at a reset made with the screen off, or ends
     * at one made with the screen on. That is deliberate: battery stats restart at a reset, and [SnapshotDiffer]
     * invalidates every battery-stats delta whose endpoints straddle one, so no per-UID window can reach across it.
     */
    private fun completedScreenOff(events: List<HistoryEvent>): Boundary? {
        var candidate: Boundary? = null
        var screenOff: Long? = null
        for (event in events) {
            when (event.kind) {
                HistoryKind.SCREEN_OFF -> {
                    screenOff = event.wallMs
                }

                HistoryKind.SCREEN_ON -> {
                    screenOff?.let { candidate = Boundary(it, event.wallMs) }
                    screenOff = null
                }
            }
        }
        return candidate
    }

    private suspend fun completedScreenOffFromSnapshots(nowWallMs: Long): Boundary? {
        val screenOff = store.latestClockByTrigger(CaptureTrigger.SCREEN_OFF) ?: return null
        val screenOn = store.latestClockByTrigger(CaptureTrigger.SCREEN_ON) ?: return null
        return if (screenOff.wallMs < screenOn.wallMs && screenOn.wallMs <= nowWallMs) {
            Boundary(screenOff.wallMs, screenOn.wallMs)
        } else {
            null
        }
    }

    private data class Boundary(
        val fromWallMs: Long,
        val toWallMs: Long,
        val markerSnapshot: BatterySnapshot? = null,
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1_000L

        /**
         * How far past a battery-history event a capture may land and still be taken for that event. The framework
         * notes the event in the history first and posts its broadcast afterwards; the capture reads its clock
         * once the broadcast is delivered, milliseconds later for an active app. From API 34 the screen and power
         * broadcasts to a cached app wait until it is active again (DEFERRAL_POLICY_UNTIL_ACTIVE), so a capture can
         * also land at the next activation. The history time is the precise one; a capture is the anchor snapshot
         * of an event the history holds, or the only trace of one it has not ingested yet. Ten seconds (a
         * receiver's time limit) covers an undeferred delivery and bounds how much of the following state a
         * window that ends at an anchor takes in.
         */
        const val CAPTURE_SLACK_MS = 10_000L
    }
}
