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

/** A wall-clock window `(fromWallMs, toWallMs]` timed by the stored battery history alone, `fromWallMs < toWallMs`. */
data class HistoryWindow(
    val fromWallMs: Long,
    val toWallMs: Long,
)

class ReferenceResolver(
    private val store: SnapshotStore,
) {
    /**
     * Reads only the history events the boundary needs (audit R-15): the newest event of each kind (index seeks per
     * kind in the Room store) and, for [ReferenceKind.SinceScreenOff], the events of the newest screen-off period.
     */
    suspend fun resolve(
        kind: ReferenceKind,
        nowWallMs: Long,
    ): ResolvedReference? = resolve(kind, nowWallMs, StoredAnchors(nowWallMs))

    /**
     * Resolves against [events], the stored history with `0 <= wallMs <= nowWallMs` oldest first (wall time, then
     * stored order); the same result as [resolve] reading the store.
     */
    suspend fun resolve(
        kind: ReferenceKind,
        nowWallMs: Long,
        events: List<HistoryEvent>,
    ): ResolvedReference? = resolve(kind, nowWallMs, ListedAnchors(events))

    /**
     * The newest completed screen-off period as the stored history times it, with no snapshot at either end: what
     * [ReferenceKind.SinceScreenOff] can still measure from history alone when [resolve] finds no snapshot pair for
     * it (no capture within [CAPTURE_SLACK_MS] of its SCREEN_ON). Null when the history holds no such period up to
     * [nowWallMs], or when a captured period newer than it is the one [ReferenceKind.SinceScreenOff] stands for.
     */
    suspend fun historyScreenOff(nowWallMs: Long): HistoryWindow? {
        val history = StoredAnchors(nowWallMs).completedScreenOff() ?: return null
        val captured = completedScreenOffFromSnapshots(nowWallMs)
        if (captured != null && captured.fromWallMs > history.toWallMs) return null
        return if (history.fromWallMs < history.toWallMs) HistoryWindow(history.fromWallMs, history.toWallMs) else null
    }

    private suspend fun resolve(
        kind: ReferenceKind,
        nowWallMs: Long,
        anchors: HistoryAnchors,
    ): ResolvedReference? {
        val boundary = boundaryFor(kind, nowWallMs, anchors) ?: return null
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

    /** What a boundary reads from the stored history up to now. */
    private interface HistoryAnchors {
        /** The wall time of the newest event of [kind]. */
        suspend fun newestWallMs(kind: Int): Long?

        /** The newest completed screen-off period, see [completedScreenOff]. */
        suspend fun completedScreenOff(): Boundary?
    }

    /** Anchors from a caller's list, as [resolve] with events documents it. */
    private inner class ListedAnchors(
        private val events: List<HistoryEvent>,
    ) : HistoryAnchors {
        override suspend fun newestWallMs(kind: Int): Long? = events.lastOrNull { it.kind == kind }?.wallMs

        override suspend fun completedScreenOff(): Boundary? = completedScreenOff(events)
    }

    /**
     * Anchors read from the store with targeted queries: the newest event of each kind up to [nowWallMs] (read
     * once), and for the screen-off period the newest SCREEN_OFF before the newest SCREEN_ON (a second newest-of-kind
     * read, only when the newest SCREEN_OFF is not already before it) plus the events from there to that SCREEN_ON.
     */
    private inner class StoredAnchors(
        private val nowWallMs: Long,
    ) : HistoryAnchors {
        private var newest: List<HistoryEvent>? = null

        private suspend fun newest(): List<HistoryEvent> =
            newest ?: store.lastHistoryOfEachKindBefore(nowWallMs.inclusiveBound()).also { newest = it }

        override suspend fun newestWallMs(kind: Int): Long? = newest().lastOrNull { it.kind == kind }?.wallMs

        /**
         * No SCREEN_ON follows the newest one, so the newest period ends at or before it, and it starts at a
         * SCREEN_OFF no older than the newest one before that SCREEN_ON: every event from there to the newest
         * SCREEN_ON (all of each end's wall time included) decides it as the whole history would. With no
         * SCREEN_OFF before it, only one at the same wall time stored ahead of it can pair with it.
         */
        override suspend fun completedScreenOff(): Boundary? {
            val onWallMs = newestWallMs(HistoryKind.SCREEN_ON) ?: return null
            val offWallMs = newestWallMs(HistoryKind.SCREEN_OFF) ?: return null
            val fromWallMs =
                if (offWallMs < onWallMs) {
                    offWallMs
                } else {
                    store.lastHistoryOfEachKindBefore(onWallMs).lastOrNull { it.kind == HistoryKind.SCREEN_OFF }?.wallMs ?: onWallMs
                }
            val events = store.historyBetween(fromWallMs, onWallMs).filter { it.wallMs in 0..nowWallMs }.sortedBy(HistoryEvent::wallMs)
            return completedScreenOff(events)
        }
    }

    private suspend fun boundaryFor(
        kind: ReferenceKind,
        nowWallMs: Long,
        anchors: HistoryAnchors,
    ): Boundary? =
        when (kind) {
            ReferenceKind.SinceUnplugged -> {
                sinceLatest(
                    historyWallMs = anchors.newestWallMs(HistoryKind.UNPLUGGED),
                    trigger = CaptureTrigger.POWER_DISCONNECTED,
                    nowWallMs = nowWallMs,
                    reverts = { it.battery.plugged == true || it.trigger == CaptureTrigger.POWER_CONNECTED },
                )
            }

            ReferenceKind.SinceScreenOff -> {
                sinceScreenOff(anchors, nowWallMs)
            }

            ReferenceKind.ScreenOnSession -> {
                // A RESET with the screen on restates it as a SCREEN_ON at the reset (see HistoryKind.RESET), so a
                // session that was already on then starts at the reset; battery-stats deltas cannot cross a reset anyway.
                sinceLatest(
                    historyWallMs = anchors.newestWallMs(HistoryKind.SCREEN_ON),
                    trigger = CaptureTrigger.SCREEN_ON,
                    nowWallMs = nowWallMs,
                    reverts = { it.trigger == CaptureTrigger.SCREEN_OFF },
                )
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
     * [CAPTURE_SLACK_MS] for how the two relate). A capture within the slack after that event, or before it, is
     * that event's or an older one's, and the history time starts the window. A later capture starts it when the
     * history is provably stale: a snapshot between the two [reverts] the event (plugged in again after an unplug,
     * the screen off again after a screen-on), so the capture is a newer event. Without that evidence the capture
     * may be the history event's own, deferred to the next activation; it then starts the window only when it is
     * more than the slack past the history horizon too. No ingest ran after it, so the history cannot hold its
     * event (no FULL read since, or Shizuku down), whereas a deferred capture is delivered at the activation whose
     * ingest moves the horizon.
     */
    private suspend fun sinceLatest(
        historyWallMs: Long?,
        trigger: CaptureTrigger,
        nowWallMs: Long,
        reverts: (BatterySnapshot) -> Boolean,
    ): Boundary? {
        val captureWallMs = store.latestClockByTrigger(trigger)?.wallMs?.takeIf { it <= nowWallMs }
        val start =
            when {
                historyWallMs == null -> captureWallMs
                captureWallMs == null || captureWallMs <= historyWallMs + CAPTURE_SLACK_MS -> historyWallMs
                anyStrictlyBetween(historyWallMs + CAPTURE_SLACK_MS, captureWallMs, reverts) -> captureWallMs
                pastHistoryHorizon(captureWallMs) -> captureWallMs
                else -> historyWallMs
            }
        return start?.let { Boundary(it, nowWallMs) }
    }

    private suspend fun pastHistoryHorizon(captureWallMs: Long): Boolean =
        store.historyHorizonWallMs()?.let { horizon -> captureWallMs > horizon + CAPTURE_SLACK_MS } ?: true

    /** True when a snapshot with `fromWallMs < wallMs < toWallMs` matches [predicate]; reads bare rows only. */
    private suspend fun anyStrictlyBetween(
        fromWallMs: Long,
        toWallMs: Long,
        predicate: (BatterySnapshot) -> Boolean,
    ): Boolean = toWallMs - fromWallMs > 1 && store.snapshotsBetween(fromWallMs + 1, toWallMs - 1).any(predicate)

    /**
     * The newest completed screen-off period. A captured period that starts after the history's newest completed
     * one ended is newer than anything the history holds, and wins. Otherwise the history times the period, and a
     * snapshot within [CAPTURE_SLACK_MS] of the history's SCREEN_ON must close it: the first one at or after it
     * (normally that broadcast's own capture, which lands after it) becomes the end, so it is inside the window,
     * else the newest one before it. With neither, no snapshot measures the period's end, and an earlier one would
     * stand for a sliver of it, so the result is null ([historyScreenOff] then gives the history's own timing).
     */
    private suspend fun sinceScreenOff(
        anchors: HistoryAnchors,
        nowWallMs: Long,
    ): Boundary? {
        val history = anchors.completedScreenOff() ?: return completedScreenOffFromSnapshots(nowWallMs)
        val captured = completedScreenOffFromSnapshots(nowWallMs)
        if (captured != null && captured.fromWallMs > history.toWallMs) return captured
        val near =
            store.snapshotsBetween(
                history.toWallMs - CAPTURE_SLACK_MS,
                minOf(history.toWallMs + CAPTURE_SLACK_MS, nowWallMs),
            )
        val anchorWallMs = near.filter { it.clock.wallMs >= history.toWallMs }.minOfOrNull { it.clock.wallMs }
        return when {
            anchorWallMs != null -> history.copy(toWallMs = anchorWallMs)
            near.any { it.clock.wallMs < history.toWallMs } -> history
            else -> null
        }
    }

    /**
     * The newest SCREEN_OFF to SCREEN_ON pair in [events]; of consecutive SCREEN_OFF events the last one starts the
     * period. The screen state a RESET restates at its instant counts as a transition, so a period that spans a reset
     * is cut at it: it starts at a reset made with the screen off, or ends at one made with the screen on. That is
     * deliberate: battery stats restart at a reset, and [SnapshotDiffer] invalidates every battery-stats delta whose
     * endpoints straddle one, so no per-UID window can reach across it (and at an unplug reset the time before it was
     * spent charging).
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

    /**
     * The newest SCREEN_OFF capture and the newest SCREEN_ON capture after it, as one screen-off period. From
     * API 34 both broadcasts share one delivery group, so a cached app receives only the newest pending one: the
     * two captures can then bound several periods with screen-on time between them. An app open or a marker
     * between them proves that (the screen was on), and the pair is rejected. An on-period in which the app was
     * neither opened nor marked leaves no capture and goes undetected.
     */
    private suspend fun completedScreenOffFromSnapshots(nowWallMs: Long): Boundary? {
        val screenOff = store.latestClockByTrigger(CaptureTrigger.SCREEN_OFF) ?: return null
        val screenOn = store.latestClockByTrigger(CaptureTrigger.SCREEN_ON) ?: return null
        if (screenOff.wallMs >= screenOn.wallMs || screenOn.wallMs > nowWallMs) return null
        if (anyStrictlyBetween(screenOff.wallMs, screenOn.wallMs) { it.trigger in SCREEN_ON_PROOF }) return null
        return Boundary(screenOff.wallMs, screenOn.wallMs)
    }

    private data class Boundary(
        val fromWallMs: Long,
        val toWallMs: Long,
        val markerSnapshot: BatterySnapshot? = null,
    )

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1_000L

        /** The exclusive bound that takes in this wall time too. */
        private fun Long.inclusiveBound(): Long = if (this == Long.MAX_VALUE) this else this + 1

        /**
         * How far past a battery-history event a capture may land and still be taken for that event. The framework
         * notes the event in the history first and posts its broadcast afterwards; the capture reads its clock
         * once the broadcast is delivered, milliseconds later for an active app. From API 34 the screen and power
         * broadcasts to a cached app wait until it is active again (DEFERRAL_POLICY_UNTIL_ACTIVE), so a capture can
         * also land at the next activation. The history time is the precise one; a capture is the anchor snapshot
         * of an event the history holds, or the only trace of one it has not ingested yet. Ten seconds (a
         * receiver's time limit) covers an undeferred delivery and bounds how much of the following state a
         * window that ends at an anchor takes in: a screen-off period ends at most this long into the screen-on
         * that closed it, which the rules' screen-off-only gate tolerates (audit R-03).
         */
        const val CAPTURE_SLACK_MS = 10_000L

        /**
         * Captures a user in front of a lit screen makes: opening the app, setting a marker. A failed refresh
         * retried when Shizuku becomes ready can store an APP_OPEN with the screen off; a pair rejected for it
         * errs on the safe side.
         */
        private val SCREEN_ON_PROOF = setOf(CaptureTrigger.APP_OPEN, CaptureTrigger.MARKER)
    }
}
