package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.diagnostics.sensor.SensorCatalog
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.CompletableDeferred

internal fun ruleEngine() = DiagnosticRuleEngine(SensorCatalog { SensorKind.OTHER })

internal fun lightSnapshot(
    wallMs: Long,
    elapsedMs: Long = wallMs,
    level: Int? = null,
    trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
) = BatterySnapshot(
    id = wallMs,
    kind = SnapshotKind.LIGHT,
    trigger = trigger,
    clock = SnapshotClock(wallMs, elapsedMs, elapsedMs / 2, bootCount = 1, statsStartMs = null, statsDurationMs = null),
    battery = BatteryState(level, null, null, null, null, null, null, null),
    system = null,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    wakeupReasons = emptyList(),
    cpuFreq = emptyList(),
    availability = emptyMap(),
    truncatedUids = 0,
)

internal fun historyEvent(wallMs: Long, kind: Int, level: Int = -1) =
    HistoryEvent(0, wallMs, kind, level, -1, 0, 0, 0)

/** In-memory store covering what [com.akane.inspiration.telemetry.diff.ReferenceResolver] and the analyzer read. */
internal class FakeSnapshotStore(
    var snapshots: List<BatterySnapshot> = emptyList(),
    var history: List<HistoryEvent> = emptyList(),
) : SnapshotStore {
    var historyReads = 0
    var failure: Exception? = null

    /** While set, reads suspend until it completes, so a test can observe in-flight states. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    override suspend fun insert(snapshot: BatterySnapshot): Long = error("unused")
    override suspend fun byId(id: Long): BatterySnapshot? = snapshots.firstOrNull { it.id == id }
    override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = snapshots.maxByOrNull { it.clock.wallMs }
    override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?) =
        snapshots.filter { it.clock.wallMs >= wallMs && (kind == null || it.kind == kind) }.minByOrNull { it.clock.wallMs }

    override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? {
        gate?.await()
        failure?.let { throw it }
        return snapshots.filter { it.clock.wallMs <= wallMs && (kind == null || it.kind == kind) }.maxByOrNull { it.clock.wallMs }
    }

    override suspend fun latestByTrigger(trigger: CaptureTrigger) =
        snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }

    override suspend fun snapshotsBetween(fromWallMs: Long, toWallMs: Long): List<BatterySnapshot> =
        snapshots.filter { it.clock.wallMs in fromWallMs..toWallMs }.sortedBy { it.clock.wallMs }

    override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) = Unit
    override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> {
        historyReads++
        return history.filter { it.wallMs in fromWallMs..toWallMs }
    }

    override suspend fun historyCursor(): Pair<Long, Long>? = null
    override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) = Unit
    override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = error("unused")
    override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null
    override suspend fun insertSamples(samples: List<LiveReading>) = Unit
    override suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict) = Unit
    override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()
    override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
    override suspend fun prune(nowWallMs: Long) = Unit
    override suspend fun markers(): List<Marker> = emptyList()
    override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> = emptyList()
    override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()
    override suspend fun healthSamples(): List<HealthSample> = emptyList()
    override suspend fun insertHealthSample(sample: HealthSample) = Unit
    override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = error("unused")
    override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit
    override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()
    override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()
}
