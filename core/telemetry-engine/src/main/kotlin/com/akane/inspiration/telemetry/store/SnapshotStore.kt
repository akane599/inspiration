package com.akane.inspiration.telemetry.store

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind

interface SnapshotStore {
    suspend fun insert(snapshot: BatterySnapshot): Long

    suspend fun byId(id: Long): BatterySnapshot?

    suspend fun latest(kind: SnapshotKind? = null): BatterySnapshot?

    suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot?

    suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot?

    suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot?

    suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long)

    suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent>

    suspend fun historyCursor(): Pair<Long, Long>?

    suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long)

    suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long

    suspend fun marker(id: Long): Pair<Long, BatterySnapshot>?

    suspend fun insertSamples(samples: List<LiveReading>)

    suspend fun upsertChargeSession(
        statsStartMs: Long,
        segment: ChargeSegment,
        verdict: SegmentVerdict,
    )

    suspend fun acceptedEstimatesChronological(limit: Int): List<Double>

    suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String)

    suspend fun prune(nowWallMs: Long)
}
