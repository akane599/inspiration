package com.akane.inspiration.database.store

import androidx.room.withTransaction
import com.akane.inspiration.database.CaptureMissEntity
import com.akane.inspiration.database.ChargeSessionEntity
import com.akane.inspiration.database.HistoryCursorEntity
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
import com.akane.inspiration.database.HealthSampleEntity
import com.akane.inspiration.database.TelemetrySampleEntity
import com.akane.inspiration.database.ToolkitActionEntity
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
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
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus

class RoomSnapshotStore(
    private val database: InspirationDatabase,
) : SnapshotStore {
    override suspend fun insert(snapshot: BatterySnapshot): Long = database.withTransaction {
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
        snapshot.mergeWakelocks(snapshotId, snapshotDao)
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

    override suspend fun byId(id: Long): BatterySnapshot? = database.withTransaction {
        database.snapshotDao().byId(id)?.let { readSnapshot(it) }
    }

    override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = database.withTransaction {
        database.snapshotDao().latest(kind?.name)?.let { readSnapshot(it) }
    }

    override suspend fun firstAtOrAfter(
        wallMs: Long,
        kind: SnapshotKind?,
        statsStartMs: Long?,
        bootCount: Int?,
    ): BatterySnapshot? = database.withTransaction {
        database.snapshotDao().firstAtOrAfter(
            wallMs = wallMs,
            kind = kind?.name,
            statsStartMs = statsStartMs,
            bootCount = bootCount,
        )?.let { readSnapshot(it) }
    }

    override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?): BatterySnapshot? = database.withTransaction {
        database.snapshotDao().lastAtOrBefore(wallMs, kind?.name)?.let { readSnapshot(it) }
    }

    override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = database.withTransaction {
        database.snapshotDao().latestByTrigger(trigger.name)?.let { readSnapshot(it) }
    }

    override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) {
        database.withTransaction {
            database.historyDao().insertIgnore(events.map { it.toEntity(statsStartMs) })
        }
    }

    override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long): List<HistoryEvent> =
        database.historyDao().between(fromWallMs, toWallMs).map { it.toDomain() }

    override suspend fun historyCursor(): Pair<Long, Long>? = database.historyDao().cursor()?.let {
        it.statsStartMs to it.lastHistoryTimeMs
    }

    override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) {
        database.withTransaction {
            database.historyDao().upsertCursor(
                HistoryCursorEntity(
                    statsStartMs = statsStartMs,
                    lastHistoryTimeMs = lastHistoryTimeMs,
                ),
            )
        }
    }

    override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = database.withTransaction {
        database.markerDao().insert(MarkerEntity(snapshotId = snapshotId, label = label, wallMs = wallMs))
    }

    override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = database.withTransaction {
        database.markerDao().byId(id)?.let { marker ->
            database.snapshotDao().byId(marker.snapshotId)?.let { marker.snapshotId to readSnapshot(it) }
        }
    }

    override suspend fun markers(): List<Marker> =
        database.markerDao().all().map { Marker(it.id, it.snapshotId, it.label, it.wallMs) }

    override suspend fun samplesBetween(from: Long, to: Long): List<TelemetrySample> =
        database.telemetryDao().between(from, to).map { it.toDomain() }

    override suspend fun chargeSessions(): List<ChargeSessionRecord> =
        database.chargeSessionDao().all().map { it.toDomain() }

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

    override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) {
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

    override suspend fun prune(nowWallMs: Long) {
        database.maintenanceDao().prune(nowWallMs)
    }

    private suspend fun readSnapshot(snapshot: SnapshotEntity): BatterySnapshot {
        val snapshotDao = database.snapshotDao()
        val components = snapshotDao.componentsForSnapshot(snapshot.id)
        val wakelocks = snapshotDao.wakelocksForSnapshot(snapshot.id)
        val alarms = snapshotDao.alarmsForSnapshot(snapshot.id)
        val sensors = snapshotDao.uidSensorsForSnapshot(snapshot.id)
        return BatterySnapshot(
            id = snapshot.id,
            kind = SnapshotKind.valueOf(snapshot.kind),
            trigger = CaptureTrigger.valueOf(snapshot.trigger),
            clock = SnapshotClock(
                wallMs = snapshot.wallMs,
                elapsedRealtimeMs = snapshot.elapsedRealtimeMs,
                uptimeMs = snapshot.uptimeMs,
                bootCount = snapshot.bootCount,
                statsStartMs = snapshot.statsStartMs,
                statsDurationMs = snapshot.statsDurationMs,
            ),
            battery = BatteryState(
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
            uids = snapshotDao.uidsForSnapshot(snapshot.id).map { uid ->
                uid.toDomain(
                    components = components.filter { it.uid == uid.uid },
                    wakelocks = wakelocks.filter { it.uid == uid.uid && it.kind == PARTIAL_WAKELOCK },
                    alarms = alarms.filter { it.uid == uid.uid },
                    sensors = sensors.filter { it.uid == uid.uid },
                )
            },
            deviceComponentsMah = components.filter { it.uid == DEVICE_UID }.associate { it.component to it.mah },
            kernelWakelocks = wakelocks
                .filter { it.kind == KERNEL_WAKELOCK }
                .map { KernelWakelock(it.name, it.durationMs, it.count) },
            wakeupReasons = wakelocks
                .filter { it.kind == WAKEUP_REASON }
                .map { KernelWakelock(it.name, it.durationMs, it.count) },
            cpuFreq = snapshotDao.cpuFrequenciesForSnapshot(snapshot.id).map {
                CpuFreqTime(policy = it.policy, freqKhz = it.freqKhz, timeMs = it.timeMs)
            },
            availability = snapshotDao.availabilityForSnapshot(snapshot.id).associate {
                it.source to SourceAvailability(it.available, it.reason)
            },
            truncatedUids = snapshot.truncatedUids,
        )
    }

    private fun BatterySnapshot.toEntity() = SnapshotEntity(
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
    )

    private fun SystemTimers.toEntity(snapshotId: Long) = SnapshotSystemEntity(
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

    private fun SnapshotSystemEntity.toDomain() = SystemTimers(
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

    private fun UidSnapshot.toEntity(snapshotId: Long) = SnapshotUidEntity(
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
    )

    private fun SnapshotUidEntity.toDomain(
        components: List<SnapshotComponentEntity>,
        wakelocks: List<SnapshotWakelockEntity>,
        alarms: List<SnapshotAlarmEntity>,
        sensors: List<SnapshotUidSensorEntity>,
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
    )

    private fun BatterySnapshot.componentEntities(snapshotId: Long): List<SnapshotComponentEntity> = buildList {
        deviceComponentsMah.forEach { (component, mah) ->
            add(SnapshotComponentEntity(snapshotId, DEVICE_UID, component, mah))
        }
        uids.forEach { uid ->
            uid.componentsMah.forEach { (component, mah) ->
                add(SnapshotComponentEntity(snapshotId, uid.uid, component, mah))
            }
        }
    }

    private suspend fun BatterySnapshot.mergeWakelocks(snapshotId: Long, snapshotDao: com.akane.inspiration.database.SnapshotDao) {
        uids.forEach { uid ->
            uid.partialWakelocks.forEach { wakelock ->
                snapshotDao.mergeWakelock(
                    snapshotId = snapshotId,
                    kind = PARTIAL_WAKELOCK,
                    uid = uid.uid,
                    name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                    durationMs = wakelock.durationMs,
                    bgDurationMs = wakelock.bgDurationMs,
                    count = wakelock.count,
                )
            }
        }
        kernelWakelocks.forEach { wakelock ->
            snapshotDao.mergeWakelock(
                snapshotId = snapshotId,
                kind = KERNEL_WAKELOCK,
                uid = DEVICE_UID,
                name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                durationMs = wakelock.durationMs,
                bgDurationMs = 0L,
                count = wakelock.count,
            )
        }
        wakeupReasons.forEach { wakelock ->
            snapshotDao.mergeWakelock(
                snapshotId = snapshotId,
                kind = WAKEUP_REASON,
                uid = DEVICE_UID,
                name = wakelock.name.ifBlank { UNNAMED_WAKELOCK },
                durationMs = wakelock.durationMs,
                bgDurationMs = 0L,
                count = wakelock.count,
            )
        }
    }

    private fun BatterySnapshot.alarmEntities(snapshotId: Long): List<SnapshotAlarmEntity> {
        val counts = uids.flatMap { uid -> uid.wakeupAlarms.map { alarm -> (uid.uid to alarm.tag) to alarm.count } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
        val dumpWakeups = uids.flatMap { uid -> uid.alarmDumpWakeups.map { alarm -> (uid.uid to alarm.tag) to alarm.count } }
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

    private fun HistoryEvent.toEntity(statsStartMs: Long) = HistoryEventEntity(
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

    private fun HistoryEventEntity.toDomain() = HistoryEvent(
        historyTimeMs = historyTimeMs,
        wallMs = wallMs,
        kind = kind.toInt(),
        levelPct = levelPct ?: 0,
        chargeUah = chargeUah ?: -1L,
        temperatureDeciC = temperatureDeciC ?: 0,
        voltageMv = voltageMv ?: 0,
        plugType = plugType?.toInt() ?: 0,
    )

    private fun LiveReading.toEntity() = TelemetrySampleEntity(
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

    private fun TelemetrySampleEntity.toDomain() = TelemetrySample(
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

    private fun ChargeSessionEntity.toDomain() = ChargeSessionRecord(
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

    private fun ToolkitActionRecord.toEntity() = ToolkitActionEntity(
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
