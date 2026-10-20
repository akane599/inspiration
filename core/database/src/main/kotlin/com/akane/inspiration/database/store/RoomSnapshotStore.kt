package com.akane.inspiration.database.store

import androidx.room.withTransaction
import com.akane.inspiration.database.CaptureMissEntity
import com.akane.inspiration.database.ChargeSessionEntity
import com.akane.inspiration.database.HealthSampleEntity
import com.akane.inspiration.database.HistoryCursorEntity
import com.akane.inspiration.database.HistoryDao
import com.akane.inspiration.database.HistoryEventEntity
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.MarkerEntity
import com.akane.inspiration.database.SnapshotAlarmEntity
import com.akane.inspiration.database.SnapshotAvailabilityEntity
import com.akane.inspiration.database.SnapshotComponentEntity
import com.akane.inspiration.database.SnapshotCpuFreqEntity
import com.akane.inspiration.database.SnapshotEntity
import com.akane.inspiration.database.SnapshotSystemEntity
import com.akane.inspiration.database.SnapshotUidEntity
import com.akane.inspiration.database.SnapshotUidSensorEntity
import com.akane.inspiration.database.SnapshotWakelockEntity
import com.akane.inspiration.database.TelemetrySampleEntity
import com.akane.inspiration.database.ToolkitActionEntity
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.diff.BatteryStatsSession
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.status.UnplugSummaryCalculator
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SAMPLES_BETWEEN_LIMIT
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.store.UnplugWindow

class RoomSnapshotStore(
    private val database: InspirationDatabase,
    private val currentBootCount: () -> Int? = { null },
) : SnapshotStore {
    /** The reads of [block] in one transaction; Room nests the store's own transactions inside it. */
    override suspend fun <R> readConsistently(block: suspend () -> R): R = database.withTransaction { block() }

    override suspend fun insert(snapshot: BatterySnapshot): Long =
        database.withTransaction {
            val snapshotId = database.snapshotDao().insert(snapshot.toEntity())
            val snapshotDao = database.snapshotDao()

            snapshot.system?.let { snapshotDao.insertSystem(it.toEntity(snapshotId)) }
            snapshotDao.insertAvailability(
                snapshot.availability.map { (source, availability) ->
                    SnapshotAvailabilityEntity(
                        snapshotId = snapshotId,
                        source = source,
                        available = availability.available,
                        reason = availability.reason,
                    )
                },
            )
            snapshotDao.insertUids(snapshot.uids.map { it.toEntity(snapshotId) })
            snapshotDao.insertComponents(snapshot.componentEntities(snapshotId))
            snapshotDao.mergeWakelocks(snapshot.wakelockEntities(snapshotId))
            snapshotDao.insertAlarms(snapshot.alarmEntities(snapshotId))
            snapshotDao.insertUidSensors(snapshot.sensorEntities(snapshotId))
            snapshotDao.insertCpuFrequencies(
                snapshot.cpuFreq.map {
                    SnapshotCpuFreqEntity(
                        snapshotId = snapshotId,
                        policy = it.policy,
                        freqKhz = it.freqKhz,
                        timeMs = it.timeMs,
                    )
                },
            )
            snapshotId
        }

    override suspend fun byId(id: Long): BatterySnapshot? =
        database.withTransaction {
            database.snapshotDao().byId(id)?.let { readSnapshot(it) }
        }

    override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? =
        database.withTransaction {
            database.snapshotDao().latest(kind?.name)?.let { readSnapshot(it) }
        }

    override suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot? =
        database.withTransaction {
            database
                .snapshotDao()
                .firstAtOrAfter(
                    wallMs = wallMs,
                    kind = kind?.name,
                    statsStartMs = statsStartMs,
                    bootCount = bootCount,
                    statsStartToleranceMs = BatteryStatsSession.START_TOLERANCE_MS,
                )?.let { readSnapshot(it) }
        }

    override suspend fun lastAtOrBefore(
        wallMs: Long,
        kind: SnapshotKind?,
    ): BatterySnapshot? =
        database.withTransaction {
            database.snapshotDao().lastAtOrBefore(wallMs, kind?.name)?.let { readSnapshot(it) }
        }

    override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
        database.withTransaction {
            latestTriggerRow(trigger)?.let { readSnapshot(it) }
        }

    override suspend fun latestClockByTrigger(trigger: CaptureTrigger): SnapshotClock? = latestTriggerRow(trigger)?.toClock()

    private suspend fun latestTriggerRow(trigger: CaptureTrigger): SnapshotEntity? =
        database.snapshotDao().latestByTrigger(trigger.name, currentBootCount()?.takeIf { it > 0 })

    override suspend fun snapshotsBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<BatterySnapshot> = database.snapshotDao().between(fromWallMs, toWallMs).map { it.toBareSnapshot() }

    override suspend fun unplugWindowSnapshots(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<BatterySnapshot> = database.snapshotDao().unplugWindow(fromWallMs, toWallMs).map { it.toBareSnapshot() }

    override suspend fun unplugWindow(
        fromWallMs: Long,
        toWallMs: Long,
    ): UnplugWindow =
        database.withTransaction {
            val snapshotDao = database.snapshotDao()
            val historyDao = database.historyDao()
            var rows = snapshotDao.unplugWindow(fromWallMs, toWallMs)
            val plugWallMs = historyDao.newestWallMs(fromWallMs, toWallMs - 1, HistoryKind.PLUGGED, HistoryKind.UNPLUGGED)
            if (plugWallMs != null && plugWallMs < (rows.firstOrNull()?.wallMs ?: Long.MAX_VALUE)) {
                rows = snapshotDao.between(snapshotDao.unplugWindowStartAtOrBefore(fromWallMs, plugWallMs), toWallMs)
            }
            val firstWallMs = rows.firstOrNull()?.wallMs ?: plugWallMs ?: toWallMs
            val historyFromWallMs =
                historyDao.newestWallMs(fromWallMs, firstWallMs, HistoryKind.SCREEN_ON, HistoryKind.SCREEN_OFF) ?: firstWallMs
            val kinds = UnplugSummaryCalculator.HISTORY_KINDS.map { it.toString() }
            UnplugWindow(
                snapshots = rows.map { it.toBareSnapshot() },
                history = historyDao.ofKindsBetween(kinds, historyFromWallMs, toWallMs).map { it.toDomain() },
            )
        }

    /** The newest wall time of an event of any of [kinds] in `fromWallMs..atWallMs`: one index seek per kind. */
    private suspend fun HistoryDao.newestWallMs(
        fromWallMs: Long,
        atWallMs: Long,
        vararg kinds: Int,
    ): Long? = kinds.asList().mapNotNull { newestWallMs(it.toString(), fromWallMs, atWallMs) }.maxOrNull()

    override suspend fun insertHistory(
        events: List<HistoryEvent>,
        statsStartMs: Long,
    ) {
        database.withTransaction {
            database.historyDao().insertIgnore(events.map { it.toEntity(statsStartMs) })
        }
    }

    override suspend fun historyBetween(
        fromWallMs: Long,
        toWallMs: Long,
    ): List<HistoryEvent> = database.historyDao().between(fromWallMs, toWallMs).map { it.toDomain() }

    override suspend fun historySessions(): List<Pair<Long, List<HistoryEvent>>> {
        // The autoincrement id is the (serialized) ingest order, which no wall-clock step can reorder; the label
        // only splits runs. Grouping by label would order runs by a value the platform shifts and merge a label
        // that recurs (audit N-13).
        val runs = ArrayList<Pair<Long, MutableList<HistoryEvent>>>()
        for (row in database.historyDao().between(0L, Long.MAX_VALUE).sortedBy { it.id }) {
            val run = runs.lastOrNull()?.takeIf { it.first == row.statsStartMs }
            if (run != null) run.second += row.toDomain() else runs += row.statsStartMs to mutableListOf(row.toDomain())
        }
        return runs
    }

    // Of a tie, the first stored row: what the scanning default (and the pre-index query) returned.
    override suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
        database.historyDao().newestOfEachKindBefore(wallMs).distinctBy { it.kind }.map { it.toDomain() }

    override suspend fun newestHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
        database.historyDao().newestOfEachKindBefore(wallMs).map { it.toDomain() }

    override suspend fun historyHorizonWallMs(): Long? =
        database.snapshotDao().newestWallMsWithCompleteSource(SnapshotKind.FULL.name, HISTORY_SOURCE)

    /** Only the history ingest reads it; the first read of a database an earlier build filled rewinds it once. */
    override suspend fun historyCursor(): Pair<Long, Long>? =
        database.historyDao().resumeCursor()?.let {
            it.statsStartMs to it.lastHistoryTimeMs
        }

    override suspend fun setHistoryCursor(
        statsStartMs: Long,
        lastHistoryTimeMs: Long,
    ) {
        database.historyDao().advanceCursor(
            HistoryCursorEntity(
                statsStartMs = statsStartMs,
                lastHistoryTimeMs = lastHistoryTimeMs,
            ),
        )
    }

    override suspend fun addMarker(
        snapshotId: Long,
        label: String,
        wallMs: Long,
    ): Long =
        database.withTransaction {
            database.markerDao().insert(MarkerEntity(snapshotId = snapshotId, label = label, wallMs = wallMs))
        }

    override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? =
        database.withTransaction {
            database.markerDao().byId(id)?.let { marker ->
                database.snapshotDao().byId(marker.snapshotId)?.let { marker.wallMs to readSnapshot(it) }
            }
        }

    override suspend fun markers(): List<Marker> = database.markerDao().all().map { Marker(it.id, it.snapshotId, it.label, it.wallMs) }

    override suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample> = database.telemetryDao().between(from, to, SAMPLES_BETWEEN_LIMIT).map { it.toDomain() }

    override suspend fun chargeSessions(): List<ChargeSessionRecord> = database.chargeSessionDao().all().map { it.toDomain() }

    override suspend fun healthSamples(): List<HealthSample> =
        database.healthDao().all().map { HealthSample(it.wallMs, it.fccMah, it.designMah, it.cycleCount, it.measuredMah) }

    override suspend fun insertHealthSample(sample: HealthSample) {
        database.healthDao().insert(
            HealthSampleEntity(sample.wallMs, sample.fccMah, sample.designMah, sample.cycleCount, sample.measuredMah),
        )
    }

    override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long =
        database.toolkitActionDao().insertAndPrune(record.toEntity().copy(id = 0))

    override suspend fun updateToolkitAction(record: ToolkitActionRecord) {
        database.toolkitActionDao().update(record.toEntity())
    }

    override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> =
        database.toolkitActionDao().newest(limit).mapNotNull { it.toDomain() }

    override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> =
        database.toolkitActionDao().withStatus(ToolkitActionStatus.PENDING.code).mapNotNull { it.toDomain() }

    override suspend fun deleteToolkitActions(ids: List<Long>) {
        if (ids.isNotEmpty()) database.toolkitActionDao().deleteSettled(ids)
    }

    override suspend fun insertSamples(samples: List<LiveReading>) {
        if (samples.isEmpty()) return
        database.withTransaction {
            database.telemetryDao().insertSamples(samples.map { it.toEntity() })
        }
    }

    override suspend fun upsertChargeSession(
        statsStartMs: Long,
        segment: ChargeSegment,
        verdict: SegmentVerdict,
    ) {
        database.withTransaction {
            database.chargeSessionDao().upsert(
                ChargeSessionEntity(
                    statsStartMs = statsStartMs,
                    startHistoryTimeMs = segment.startHistoryTimeMs,
                    startWallMs = segment.startWallMs,
                    endWallMs = segment.endWallMs,
                    startPct = segment.startPct,
                    endPct = segment.endPct,
                    deltaChargeUah = segment.deltaChargeUah,
                    integratedMah = segment.integratedMah,
                    maxTemperatureDeciC = segment.maxTemperatureDeciC,
                    estimateMah = verdict.estimateMah,
                    independent = verdict.independent,
                    accepted = verdict.accepted,
                    rejectReason = verdict.rejectReason,
                ),
            )
        }
    }

    override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> =
        database.chargeSessionDao().acceptedEstimatesChronological(limit)

    override suspend fun recordMiss(
        wallMs: Long,
        trigger: CaptureTrigger,
        reason: String,
    ) {
        database.withTransaction {
            database.captureMissDao().insert(
                CaptureMissEntity(
                    wallMs = wallMs,
                    trigger = trigger.name,
                    reason = reason,
                ),
            )
        }
    }

    override suspend fun minClockOffsetMs(bootCount: Int): Long? = database.maintenanceDao().minClockOffsetMs(bootCount)

    override suspend fun prune(nowWallMs: Long) {
        database.maintenanceDao().prune(nowWallMs)
    }

    private suspend fun readSnapshot(snapshot: SnapshotEntity): BatterySnapshot {
        val snapshotDao = database.snapshotDao()
        val identityScope = snapshot.identityScope()
        val components = snapshotDao.componentsForSnapshot(snapshot.id)
        val wakelocks = snapshotDao.wakelocksForSnapshot(snapshot.id)
        val alarms = snapshotDao.alarmsForSnapshot(snapshot.id)
        val sensors = snapshotDao.uidSensorsForSnapshot(snapshot.id)
        // One pass per child table, then a map lookup per UID: filtering each table per UID is O(UIDs x rows).
        val componentsByUid = components.groupBy { it.uid }
        val partialWakelocksByUid = wakelocks.filter { it.kind == PARTIAL_WAKELOCK }.groupBy { it.uid }
        val alarmsByUid = alarms.groupBy { it.uid }
        val sensorsByUid = sensors.groupBy { it.uid }
        return BatterySnapshot(
            id = snapshot.id,
            kind = SnapshotKind.valueOf(snapshot.kind),
            trigger = CaptureTrigger.valueOf(snapshot.trigger),
            clock =
                SnapshotClock(
                    wallMs = snapshot.wallMs,
                    elapsedRealtimeMs = snapshot.elapsedRealtimeMs,
                    uptimeMs = snapshot.uptimeMs,
                    bootCount = snapshot.bootCount,
                    statsStartMs = snapshot.statsStartMs,
                    statsDurationMs = snapshot.statsDurationMs,
                ),
            battery =
                BatteryState(
                    levelPct = snapshot.levelPct,
                    plugged = snapshot.plugged,
                    status = snapshot.statusText,
                    temperatureDeciC = snapshot.temperatureDeciC,
                    voltageMv = snapshot.voltageMv,
                    currentMa = snapshot.currentMa,
                    chargeCounterUah = snapshot.chargeCounterUah,
                    dischargedMah = snapshot.dischargedMah,
                ),
            system = snapshotDao.systemForSnapshot(snapshot.id)?.toDomain(),
            uids =
                snapshotDao.uidsForSnapshot(snapshot.id).map { uid ->
                    uid.toDomain(
                        components = componentsByUid[uid.uid].orEmpty(),
                        wakelocks = partialWakelocksByUid[uid.uid].orEmpty(),
                        alarms = alarmsByUid[uid.uid].orEmpty(),
                        sensors = sensorsByUid[uid.uid].orEmpty(),
                        identityScope = identityScope,
                    )
                },
            deviceComponentsMah = componentsByUid[DEVICE_UID].orEmpty().associate { it.component to it.mah },
            kernelWakelocks =
                wakelocks
                    .filter { it.kind == KERNEL_WAKELOCK }
                    .map { KernelWakelock(it.name, it.durationMs, it.count) },
            wakeupReasons =
                wakelocks
                    .filter { it.kind == WAKEUP_REASON }
                    .map { KernelWakelock(it.name, it.durationMs, it.count) },
            cpuFreq =
                snapshotDao.cpuFrequenciesForSnapshot(snapshot.id).map {
                    CpuFreqTime(policy = it.policy, freqKhz = it.freqKhz, timeMs = it.timeMs)
                },
            availability =
                snapshotDao.availabilityForSnapshot(snapshot.id).associate {
                    it.source to SourceAvailability(it.available, it.reason)
                },
            truncatedUids = snapshot.truncatedUids,
            uidIdentityScope = identityScope,
        )
    }

    /** The snapshot and battery columns only; system timers and every per-UID or kernel list stay empty. */
    private fun SnapshotEntity.toBareSnapshot() =
        BatterySnapshot(
            id = id,
            kind = SnapshotKind.valueOf(kind),
            trigger = CaptureTrigger.valueOf(trigger),
            clock = toClock(),
            battery =
                BatteryState(
                    levelPct = levelPct,
                    plugged = plugged,
                    status = statusText,
                    temperatureDeciC = temperatureDeciC,
                    voltageMv = voltageMv,
                    currentMa = currentMa,
                    chargeCounterUah = chargeCounterUah,
                    dischargedMah = dischargedMah,
                ),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = emptyMap(),
            truncatedUids = truncatedUids,
        )

    private fun SnapshotEntity.toClock() = SnapshotClock(wallMs, elapsedRealtimeMs, uptimeMs, bootCount, statsStartMs, statsDurationMs)

    private fun SnapshotEntity.identityScope(): UidIdentityScope? {
        val userId = uidIdentityUserId ?: return null
        val serial = uidIdentityUserSerial ?: return null
        val epoch = uidIdentityReaderEpoch ?: return null
        val sequence = uidIdentityPackageChangeSequence ?: return null
        return UidIdentityScope(userId, serial, epoch, sequence).takeIf { it.isKnown() }
    }

    private fun BatterySnapshot.toEntity() =
        SnapshotEntity(
            kind = kind.name,
            trigger = trigger.name,
            wallMs = clock.wallMs,
            elapsedRealtimeMs = clock.elapsedRealtimeMs,
            uptimeMs = clock.uptimeMs,
            bootCount = clock.bootCount,
            statsStartMs = clock.statsStartMs,
            statsDurationMs = clock.statsDurationMs,
            levelPct = battery.levelPct,
            plugged = battery.plugged,
            statusText = battery.status,
            temperatureDeciC = battery.temperatureDeciC,
            voltageMv = battery.voltageMv,
            currentMa = battery.currentMa,
            chargeCounterUah = battery.chargeCounterUah,
            dischargedMah = battery.dischargedMah,
            truncatedUids = truncatedUids,
            uidIdentityUserId = uidIdentityScope?.takeIf { it.isKnown() }?.userId,
            uidIdentityUserSerial = uidIdentityScope?.takeIf { it.isKnown() }?.userSerial,
            uidIdentityReaderEpoch = uidIdentityScope?.takeIf { it.isKnown() }?.readerEpoch,
            uidIdentityPackageChangeSequence = uidIdentityScope?.takeIf { it.isKnown() }?.packageChangeSequence,
        )

    private fun SystemTimers.toEntity(snapshotId: Long) =
        SnapshotSystemEntity(
            snapshotId = snapshotId,
            startClockTimeMs = startClockTimeMs,
            totalRealtimeMs = totalRealtimeMs,
            totalUptimeMs = totalUptimeMs,
            batteryRealtimeMs = batteryRealtimeMs,
            batteryUptimeMs = batteryUptimeMs,
            screenOffRealtimeMs = screenOffRealtimeMs,
            screenOffUptimeMs = screenOffUptimeMs,
            screenDozeMs = screenDozeMs,
            screenOnMs = screenOnMs,
            interactiveMs = interactiveMs,
            deepDozeMs = deepDozeMs,
            deepDozeCount = deepDozeCount,
            lightDozeMs = lightDozeMs,
            dischargeTotalMah = dischargeTotalMah,
            dischargeScreenOffMah = dischargeScreenOffMah,
            chargeTimeRemainingMs = chargeTimeRemainingMs,
            dischargeTimeRemainingMs = dischargeTimeRemainingMs,
            signalBin0Ms = signalStrengthMs.getOrNull(0),
            signalBin1Ms = signalStrengthMs.getOrNull(1),
            signalBin2Ms = signalStrengthMs.getOrNull(2),
            signalBin3Ms = signalStrengthMs.getOrNull(3),
            signalBin4Ms = signalStrengthMs.getOrNull(4),
            signalScanningMs = signalScanningMs,
            mobileRadioActiveMs = mobileRadioActiveMs,
        )

    private fun SnapshotSystemEntity.toDomain() =
        SystemTimers(
            startClockTimeMs = startClockTimeMs,
            totalRealtimeMs = totalRealtimeMs,
            totalUptimeMs = totalUptimeMs,
            batteryRealtimeMs = batteryRealtimeMs,
            batteryUptimeMs = batteryUptimeMs,
            screenOffRealtimeMs = screenOffRealtimeMs,
            screenOffUptimeMs = screenOffUptimeMs,
            screenDozeMs = screenDozeMs,
            screenOnMs = screenOnMs,
            interactiveMs = interactiveMs,
            deepDozeMs = deepDozeMs,
            deepDozeCount = deepDozeCount,
            lightDozeMs = lightDozeMs,
            dischargeTotalMah = dischargeTotalMah,
            dischargeScreenOffMah = dischargeScreenOffMah,
            chargeTimeRemainingMs = chargeTimeRemainingMs,
            dischargeTimeRemainingMs = dischargeTimeRemainingMs,
            signalStrengthMs = signalBins(),
            signalScanningMs = signalScanningMs,
            mobileRadioActiveMs = mobileRadioActiveMs,
        )

    private fun SnapshotSystemEntity.signalBins(): List<Long> {
        val bins = listOf(signalBin0Ms, signalBin1Ms, signalBin2Ms, signalBin3Ms, signalBin4Ms)
        return if (bins.all { it == null }) emptyList() else bins.map { it ?: 0L }
    }

    private fun UidSnapshot.toEntity(snapshotId: Long) =
        SnapshotUidEntity(
            snapshotId = snapshotId,
            uid = uid,
            packageName = packageName,
            totalMah = totalMah,
            fgMah = fgMah,
            bgMah = bgMah,
            fgsMah = fgsMah,
            cachedMah = cachedMah,
            cpuUserMs = cpuUserMs,
            cpuSystemMs = cpuSystemMs,
            fgActivityMs = fgActivityMs,
            fgServiceMs = fgServiceMs,
            ownerInstallFingerprint = UidOwnerFingerprint.known(ownerInstallFingerprint),
        )

    private fun SnapshotUidEntity.toDomain(
        components: List<SnapshotComponentEntity>,
        wakelocks: List<SnapshotWakelockEntity>,
        alarms: List<SnapshotAlarmEntity>,
        sensors: List<SnapshotUidSensorEntity>,
        identityScope: UidIdentityScope?,
    ) = UidSnapshot(
        uid = uid,
        packageName = packageName,
        totalMah = totalMah,
        fgMah = fgMah,
        bgMah = bgMah,
        fgsMah = fgsMah,
        cachedMah = cachedMah,
        componentsMah = components.associate { it.component to it.mah },
        cpuUserMs = cpuUserMs,
        cpuSystemMs = cpuSystemMs,
        fgActivityMs = fgActivityMs,
        fgServiceMs = fgServiceMs,
        partialWakelocks = wakelocks.map { UidWakelock(it.name, it.durationMs, it.bgDurationMs, it.count) },
        // A row with no batterystats count but an alarm-dump count was reported by the dump only.
        wakeupAlarms = alarms.filterNot { it.count == 0L && it.alarmDumpWakeups > 0L }.map { UidAlarm(it.tag, it.count) },
        sensors = sensors.map { UidSensor(it.handle, it.totalMs, it.bgMs, it.count) },
        alarmDumpWakeups = alarms.filter { it.alarmDumpWakeups > 0L }.map { UidAlarm(it.tag, it.alarmDumpWakeups) },
        ownerInstallFingerprint = if (identityScope?.covers(uid) == true) UidOwnerFingerprint.known(ownerInstallFingerprint) else null,
    )

    private fun BatterySnapshot.componentEntities(snapshotId: Long): List<SnapshotComponentEntity> =
        buildList {
            deviceComponentsMah.forEach { (component, mah) ->
                add(SnapshotComponentEntity(snapshotId, DEVICE_UID, component, mah))
            }
            uids.forEach { uid ->
                uid.componentsMah.forEach { (component, mah) ->
                    add(SnapshotComponentEntity(snapshotId, uid.uid, component, mah))
                }
            }
        }

    /** One row per (kind, uid, name) after blank names become [UNNAMED_WAKELOCK], durations and counts summed. */
    private fun BatterySnapshot.wakelockEntities(snapshotId: Long): List<SnapshotWakelockEntity> =
        buildList {
            uids.forEach { uid ->
                uid.partialWakelocks.forEach { wakelock ->
                    add(
                        SnapshotWakelockEntity(
                            snapshotId = snapshotId,
                            kind = PARTIAL_WAKELOCK,
                            uid = uid.uid,
                            name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                            durationMs = wakelock.durationMs,
                            bgDurationMs = wakelock.bgDurationMs,
                            count = wakelock.count,
                        ),
                    )
                }
            }
            kernelWakelocks.forEach { wakelock ->
                add(
                    SnapshotWakelockEntity(
                        snapshotId = snapshotId,
                        kind = KERNEL_WAKELOCK,
                        uid = DEVICE_UID,
                        name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                        durationMs = wakelock.durationMs,
                        bgDurationMs = 0L,
                        count = wakelock.count,
                    ),
                )
            }
            wakeupReasons.forEach { wakelock ->
                add(
                    SnapshotWakelockEntity(
                        snapshotId = snapshotId,
                        kind = WAKEUP_REASON,
                        uid = DEVICE_UID,
                        name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                        durationMs = wakelock.durationMs,
                        bgDurationMs = 0L,
                        count = wakelock.count,
                    ),
                )
            }
        }.groupBy { Triple(it.kind, it.uid, it.name) }.values.map { rows ->
            rows.reduce { merged, row ->
                merged.copy(
                    durationMs = merged.durationMs + row.durationMs,
                    bgDurationMs = merged.bgDurationMs + row.bgDurationMs,
                    count = merged.count + row.count,
                )
            }
        }

    private fun BatterySnapshot.alarmEntities(snapshotId: Long): List<SnapshotAlarmEntity> {
        val counts =
            uids
                .flatMap { uid -> uid.wakeupAlarms.map { alarm -> (uid.uid to alarm.tag) to alarm.count } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
        val dumpWakeups =
            uids
                .flatMap { uid -> uid.alarmDumpWakeups.map { alarm -> (uid.uid to alarm.tag) to alarm.count } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
        return (counts.keys + dumpWakeups.keys).map { key ->
            SnapshotAlarmEntity(snapshotId, key.first, key.second, counts[key] ?: 0L, dumpWakeups[key] ?: 0L)
        }
    }

    private fun BatterySnapshot.sensorEntities(snapshotId: Long): List<SnapshotUidSensorEntity> =
        uids.flatMap { uid ->
            uid.sensors.map { SnapshotUidSensorEntity(snapshotId, uid.uid, it.handle, it.totalMs, it.bgMs, it.count) }
        }

    private fun HistoryEvent.toEntity(statsStartMs: Long) =
        HistoryEventEntity(
            statsStartMs = statsStartMs,
            historyTimeMs = historyTimeMs,
            wallMs = wallMs,
            kind = kind.toString(),
            levelPct = levelPct,
            chargeUah = chargeUah,
            temperatureDeciC = temperatureDeciC,
            voltageMv = voltageMv,
            plugType = plugType.toString(),
        )

    private fun HistoryEventEntity.toDomain() =
        HistoryEvent(
            historyTimeMs = historyTimeMs,
            wallMs = wallMs,
            kind = kind.toInt(),
            levelPct = levelPct ?: 0,
            chargeUah = chargeUah ?: -1L,
            temperatureDeciC = temperatureDeciC ?: 0,
            voltageMv = voltageMv ?: 0,
            plugType = plugType?.toInt() ?: 0,
        )

    private fun LiveReading.toEntity() =
        TelemetrySampleEntity(
            wallMs = wallMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            bootCount = bootCount,
            currentMa = currentMa,
            voltageMv = voltageMv,
            watts = watts,
            temperatureDeciC = temperatureDeciC,
            levelPct = levelPct,
            plugged = plugged,
            thermalStatus = thermalStatus,
        )

    private fun TelemetrySampleEntity.toDomain() =
        TelemetrySample(
            wallMs = wallMs,
            elapsedRealtimeMs = elapsedRealtimeMs,
            bootCount = bootCount,
            currentMa = currentMa,
            voltageMv = voltageMv,
            watts = watts,
            temperatureDeciC = temperatureDeciC,
            levelPct = levelPct,
            plugged = plugged,
            thermalStatus = thermalStatus,
        )

    private fun ChargeSessionEntity.toDomain() =
        ChargeSessionRecord(
            statsStartMs = statsStartMs,
            startHistoryTimeMs = startHistoryTimeMs,
            startWallMs = startWallMs,
            endWallMs = endWallMs,
            startPct = startPct,
            endPct = endPct,
            deltaChargeUah = deltaChargeUah,
            integratedMah = integratedMah,
            maxTemperatureDeciC = maxTemperatureDeciC,
            estimateMah = estimateMah,
            independent = independent,
            accepted = accepted,
            rejectReason = rejectReason,
        )

    private fun ToolkitActionRecord.toEntity() =
        ToolkitActionEntity(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            kind = kind,
            pkg = pkg,
            op = op,
            param = param,
            status = status.code,
            code = code,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            restoresId = restoresId,
        )

    /** Null when the stored status code is unknown, so a row written by a newer build is skipped, not mislabelled. */
    private fun ToolkitActionEntity.toDomain(): ToolkitActionRecord? {
        val parsed = ToolkitActionStatus.fromCode(status) ?: return null
        return ToolkitActionRecord(
            id = id,
            startedAt = startedAt,
            endedAt = endedAt,
            kind = kind,
            pkg = pkg,
            op = op,
            param = param,
            status = parsed,
            code = code,
            beforeRaw = beforeRaw,
            afterRaw = afterRaw,
            restoresId = restoresId,
        )
    }

    private companion object {
        const val DEVICE_UID = -1
        const val PARTIAL_WAKELOCK = "PARTIAL"
        const val KERNEL_WAKELOCK = "KERNEL"
        const val WAKEUP_REASON = "WAKEUP_REASON"
        const val UNNAMED_WAKELOCK = "<unnamed>"
    }
}
