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
    suspend fun resolve(kind: ReferenceKind, nowWallMs: Long): ResolvedReference? {
        val latest = store.lastAtOrBefore(nowWallMs, null) ?: return null
        val events = store.historyBetween(0, nowWallMs)
            .filter { it.wallMs in 0..nowWallMs }
            .sortedBy(HistoryEvent::wallMs)
        val boundary = boundaryFor(kind, nowWallMs, events) ?: return null
        if (boundary.fromWallMs > boundary.toWallMs) return null

        val end = store.lastAtOrBefore(boundary.toWallMs, null) ?: return null
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

    private suspend fun boundaryFor(
        kind: ReferenceKind,
        nowWallMs: Long,
        events: List<HistoryEvent>,
    ): Boundary? = when (kind) {
        ReferenceKind.SinceUnplugged -> {
            val boundary = events.lastOrNull { it.kind == HistoryKind.UNPLUGGED }?.wallMs
                ?: store.latestByTrigger(CaptureTrigger.POWER_DISCONNECTED)?.clock?.wallMs
            boundary?.let { Boundary(it, nowWallMs) }
        }

        ReferenceKind.SinceScreenOff -> completedScreenOff(events)
            ?: completedScreenOffFromSnapshots(nowWallMs)

        ReferenceKind.ScreenOnSession -> {
            val boundary = events.lastOrNull { it.kind == HistoryKind.SCREEN_ON }?.wallMs
                ?: store.latestByTrigger(CaptureTrigger.SCREEN_ON)?.clock?.wallMs
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
        ) ?: store.firstAtOrAfter(
            wallMs = boundaryWallMs,
            kind = null,
            statsStartMs = null,
            bootCount = end.clock.bootCount,
        )

    private fun completedScreenOff(events: List<HistoryEvent>): Boundary? {
        var candidate: Boundary? = null
        events.forEachIndexed { index, event ->
            if (event.kind == HistoryKind.SCREEN_OFF) {
                val screenOn = events.drop(index + 1).firstOrNull { it.kind == HistoryKind.SCREEN_ON }
                if (screenOn != null) candidate = Boundary(event.wallMs, screenOn.wallMs)
            }
        }
        return candidate
    }

    private suspend fun completedScreenOffFromSnapshots(nowWallMs: Long): Boundary? {
        val screenOff = store.latestByTrigger(CaptureTrigger.SCREEN_OFF) ?: return null
        val screenOn = store.latestByTrigger(CaptureTrigger.SCREEN_ON) ?: return null
        return if (screenOff.clock.wallMs < screenOn.clock.wallMs && screenOn.clock.wallMs <= nowWallMs) {
            Boundary(screenOff.clock.wallMs, screenOn.clock.wallMs)
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
