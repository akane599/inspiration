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

    data class CustomMarker(val markerId: Long) : ReferenceKind

    data class TimeSlice(val fromWallMs: Long, val toWallMs: Long) : ReferenceKind
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
    suspend fun resolve(kind: ReferenceKind, nowWallMs: Long): ResolvedReference? =
        resolve(kind, nowWallMs, historyUpTo(nowWallMs))

    /**
     * Resolves against [events], the stored history up to [nowWallMs] oldest first as [historyUpTo] returns it,
     * so a caller that needs that history too loads it once.
     */
    suspend fun resolve(kind: ReferenceKind, nowWallMs: Long, events: List<HistoryEvent>): ResolvedReference? {
        val boundary = boundaryFor(kind, nowWallMs, events) ?: return null
        if (boundary.fromWallMs > boundary.toWallMs) return null

        val end = store.lastAtOrBefore(boundary.toWallMs, null) ?: return null
        // Resolution needs a snapshot at or before now. `end` is one unless it lies past now (only a boundary that
        // ends after now allows that), so a boundary ending at now loads that snapshot once, not twice.
        if (end.clock.wallMs > nowWallMs && store.lastAtOrBefore(nowWallMs, null) == null) return null
        val ref = boundary.markerSnapshot ?: closestReference(boundary.fromWallMs, end) ?: return null
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
            gapMs = (coveredFrom - boundary.fromWallMs).coerceAtLeast(0) +
                (boundary.toWallMs - coveredTo).coerceAtLeast(0),
            perUidAvailable = ref.kind == SnapshotKind.FULL && end.kind == SnapshotKind.FULL,
        )
    }

    /** The stored history with `0 <= wallMs <= nowWallMs`, oldest first. */
    suspend fun historyUpTo(nowWallMs: Long): List<HistoryEvent> =
        store.historyBetween(0, nowWallMs)
            .filter { it.wallMs in 0..nowWallMs }
            .sortedBy(HistoryEvent::wallMs)

    private suspend fun boundaryFor(
        kind: ReferenceKind,
        nowWallMs: Long,
        events: List<HistoryEvent>,
    ): Boundary? = when (kind) {
        ReferenceKind.SinceUnplugged -> {
            val boundary = events.lastOrNull { it.kind == HistoryKind.UNPLUGGED }?.wallMs
                ?: store.latestClockByTrigger(CaptureTrigger.POWER_DISCONNECTED)?.wallMs
            boundary?.let { Boundary(it, nowWallMs) }
        }

        ReferenceKind.SinceScreenOff -> completedScreenOff(events)
            ?: completedScreenOffFromSnapshots(nowWallMs)

        ReferenceKind.ScreenOnSession -> {
            val boundary = events.lastOrNull { it.kind == HistoryKind.SCREEN_ON }?.wallMs
                ?: store.latestClockByTrigger(CaptureTrigger.SCREEN_ON)?.wallMs
            boundary?.let { Boundary(it, nowWallMs) }
        }

        ReferenceKind.Last24h -> Boundary(nowWallMs - DAY_MS, nowWallMs)

        is ReferenceKind.CustomMarker -> store.marker(kind.markerId)?.let { (wallMs, snapshot) ->
            if (wallMs <= nowWallMs) Boundary(wallMs, nowWallMs, snapshot) else null
        }

        is ReferenceKind.TimeSlice -> Boundary(kind.fromWallMs, kind.toWallMs)
    }

    private suspend fun closestReference(
        boundaryWallMs: Long,
        end: BatterySnapshot,
    ): BatterySnapshot? =
        store.firstAtOrAfter(
            wallMs = boundaryWallMs,
            kind = SnapshotKind.FULL,
            statsStartMs = end.clock.statsStartMs,
            bootCount = end.clock.bootCount,
        )?.takeIf { it.clock.wallMs <= end.clock.wallMs } ?: store.firstAtOrAfter(
            wallMs = boundaryWallMs,
            kind = null,
            statsStartMs = null,
            bootCount = end.clock.bootCount,
        )?.takeIf { it.clock.wallMs <= end.clock.wallMs }

    private fun completedScreenOff(events: List<HistoryEvent>): Boundary? {
        var candidate: Boundary? = null
        var screenOff: Long? = null
        for (event in events) {
            when (event.kind) {
                HistoryKind.SCREEN_OFF -> screenOff = event.wallMs
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
    }
}
