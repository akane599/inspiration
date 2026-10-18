package com.akane.inspiration.telemetry.capture

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.AlarmDump
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.dump.DumpSnapshot
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

class SnapshotCapturer(
    private val source: TelemetrySource,
    private val store: SnapshotStore,
    private val clocks: Clocks,
) {
    suspend fun captureFull(
        trigger: CaptureTrigger,
        deadlineMs: Long,
        ingestHistory: Boolean,
    ): CaptureResult {
        val cursor = if (ingestHistory) store.historyCursor() ?: (0L to 0L) else null
        val read = withTimeoutOrNull(deadlineMs) { source.full(trigger, cursor) }
        if (read == null) return missed(trigger, "deadline_or_service_unavailable")
        val snapshot = mergeFull(trigger, read)
        val id = store.insert(snapshot)
        if (ingestHistory) persistHistory(read.history)
        return CaptureResult.Saved(id, SnapshotKind.FULL)
    }

    suspend fun ingestHistory(deadlineMs: Long): Boolean {
        val cursor = store.historyCursor() ?: (0L to 0L)
        val read = withTimeoutOrNull(deadlineMs) { source.full(CaptureTrigger.CHARGE_JOB, cursor) } ?: return false
        persistHistory(read.history)
        return read.history.any { it.available }
    }

    suspend fun captureLight(trigger: CaptureTrigger): CaptureResult {
        val read = source.light()
        val id = store.insert(read.toSnapshot(trigger))
        return CaptureResult.Saved(id, SnapshotKind.LIGHT)
    }

    private suspend fun missed(trigger: CaptureTrigger, reason: String): CaptureResult.Missed {
        // A caller's guard may share this deadline and fire first; the miss is still worth recording.
        withContext(NonCancellable) { store.recordMiss(clocks.wallMs(), trigger, reason) }
        return CaptureResult.Missed(reason)
    }

    private suspend fun persistHistory(pages: List<com.akane.inspiration.ipc.model.BatteryHistorySnapshot>) {
        pages.filter { it.available }.forEach { page ->
            store.insertHistory(page.events, page.statsStartMs)
            page.events.lastOrNull()?.let { store.setHistoryCursor(page.statsStartMs, it.historyTimeMs) }
        }
    }

    private fun mergeFull(trigger: CaptureTrigger, read: FullRead): BatterySnapshot {
        val usage = read.usage?.takeIf { it.available }
        val dump = (read.batteryStats as? DumpParse.Ok)?.value
        val sessionSkew = usage != null && dump != null && abs(dump.system.startClockTimeMs - usage.statsStartMs) > MAX_SESSION_SKEW_MS
        val usableDump = dump?.takeUnless { sessionSkew }
        val clock = read.light.clock.copy(
            statsStartMs = usage?.statsStartMs,
            statsDurationMs = usage?.statsDurationMs,
        )
        val uidDump = usableDump?.uids?.associateBy { it.uid }.orEmpty()
        // The first row wins for a repeated UID, as a per-UID linear search would pick it.
        val uidUsage = usage?.uids.orEmpty().distinctBy { it.uid }.associateBy { it.uid }
        val alarms = mergeAlarms(uidDump.mapValues { it.value.wakeupAlarms })
        val alarmDumps = alarmDumpWakeups(read.alarm)
        val uids = (uidUsage.keys + uidDump.keys + alarmDumps.keys)
            .distinct()
            .sorted()
            .map { uid ->
                val usageUid = uidUsage[uid]
                val dumpUid = uidDump[uid]
                val state = usageUid?.byProcessState
                UidSnapshot(
                    uid = uid,
                    packageName = usageUid?.packageName,
                    totalMah = usageUid?.totalMah ?: 0.0,
                    fgMah = state?.get(PROCESS_FOREGROUND),
                    bgMah = state?.get(PROCESS_BACKGROUND),
                    fgsMah = state?.get(PROCESS_FOREGROUND_SERVICE),
                    cachedMah = state?.get(PROCESS_CACHED),
                    componentsMah = usageUid?.components.orEmpty(),
                    cpuUserMs = dumpUid?.cpuUserMs ?: 0L,
                    cpuSystemMs = dumpUid?.cpuSystemMs ?: 0L,
                    fgActivityMs = dumpUid?.fgActivityMs ?: 0L,
                    fgServiceMs = dumpUid?.fgServiceMs ?: 0L,
                    partialWakelocks = mergeWakelocks(dumpUid?.partialWakelocks.orEmpty()),
                    wakeupAlarms = alarms[uid].orEmpty(),
                    sensors = dumpUid?.sensors.orEmpty(),
                    alarmDumpWakeups = alarmDumps[uid].orEmpty(),
                )
            }
        val deviceComponents = usage?.deviceComponents?.takeIf { it.isNotEmpty() }
            ?: uids.flatMap { it.componentsMah.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val availability = linkedMapOf(
            SOURCE_USAGE to SourceAvailability(usage != null, usage?.reason ?: if (usage == null) read.usage?.reason ?: "unavailable" else null),
            SOURCE_DUMP to SourceAvailability(usableDump != null, if (sessionSkew) "session_skew" else (read.batteryStats as? DumpParse.Unavailable)?.reason),
            SOURCE_KERNEL to SourceAvailability(read.kernel?.available == true, read.kernel?.reason),
            SOURCE_ALARM to SourceAvailability(read.alarm is DumpParse.Ok, (read.alarm as? DumpParse.Unavailable)?.reason),
            SOURCE_CPU to SourceAvailability(read.cpuTimes.isNotEmpty(), if (read.cpuTimes.isEmpty()) "unavailable" else null),
            SOURCE_HISTORY to SourceAvailability(read.history.any { it.available }, read.history.firstOrNull { !it.available }?.reason),
        )
        if (usage != null && usage.deviceComponents.isEmpty()) {
            availability[SOURCE_USAGE] = SourceAvailability(true, "device_from_uid_sum")
        }
        return BatterySnapshot(
            id = 0L,
            kind = SnapshotKind.FULL,
            trigger = trigger,
            clock = clock,
            battery = read.toBatteryState(),
            system = usableDump?.system,
            uids = uids,
            deviceComponentsMah = deviceComponents,
            kernelWakelocks = mergeKernelWakelocks(usableDump?.kernelWakelocks.orEmpty()),
            wakeupReasons = mergeKernelWakelocks(usableDump?.wakeupReasons.orEmpty()),
            cpuFreq = read.cpuTimes.map { CpuFreqTime(it.policy, it.freqKhz, it.timeMs) },
            availability = availability,
            truncatedUids = usage?.truncatedUids ?: 0,
        )
    }

    private fun FullRead.toBatteryState(): BatteryState = BatteryState(
        levelPct = kernel?.capacityPercent ?: light.levelPct,
        plugged = light.plugged,
        status = kernel?.status ?: light.status,
        temperatureDeciC = kernel?.temperatureDeciC ?: light.temperatureDeciC,
        voltageMv = light.voltageMv,
        currentMa = light.currentMa,
        chargeCounterUah = light.chargeCounterUah,
        dischargedMah = usage?.takeIf { it.available }?.dischargedPowerMah,
    )

    private fun LightRead.toSnapshot(trigger: CaptureTrigger): BatterySnapshot = BatterySnapshot(
        id = 0L,
        kind = SnapshotKind.LIGHT,
        trigger = trigger,
        clock = clock,
        battery = BatteryState(levelPct, plugged, status, temperatureDeciC, voltageMv, currentMa, chargeCounterUah, null),
        system = null,
        uids = emptyList(),
        deviceComponentsMah = emptyMap(),
        kernelWakelocks = emptyList(),
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
        availability = mapOf(SOURCE_LIGHT to SourceAvailability(true, null)),
        truncatedUids = 0,
    )

    /** Batterystats wakeup-alarm counts per uid, duplicate tags summed. The alarm dump is not mixed in. */
    private fun mergeAlarms(dumpAlarms: Map<Int, List<UidAlarm>>): Map<Int, List<UidAlarm>> =
        dumpAlarms.mapValues { (_, alarms) ->
            alarms.groupBy { it.tag }.map { (tag, items) -> UidAlarm(tag, items.sumOf { it.count }) }
        }

    /** The alarm dump's `wakeupCount` per uid and tag, kept apart from the batterystats counts. */
    private fun alarmDumpWakeups(alarm: DumpParse<AlarmDump>): Map<Int, List<UidAlarm>> =
        (alarm as? DumpParse.Ok)?.value?.filterStats.orEmpty()
            .filter { it.wakeupCount > 0 }
            .groupBy { it.uid }
            .mapValues { (_, stats) ->
                stats.groupBy { it.tag }.map { (tag, items) -> UidAlarm(tag, items.sumOf { it.wakeupCount }) }
            }

    private fun mergeWakelocks(rows: List<UidWakelock>): List<UidWakelock> = rows
        .groupBy { it.name.ifBlank { "<unnamed>" } }
        .map { (name, values) -> UidWakelock(name, values.sumOf { it.durationMs }, values.sumOf { it.bgDurationMs }, values.sumOf { it.count }) }

    private fun mergeKernelWakelocks(rows: List<com.akane.inspiration.telemetry.dump.KernelWakelock>) = rows
        .groupBy { it.name.ifBlank { "<unnamed>" } }
        .map { (name, values) -> com.akane.inspiration.telemetry.dump.KernelWakelock(name, values.sumOf { it.durationMs }, values.sumOf { it.count }) }

    private companion object {
        const val MAX_SESSION_SKEW_MS = 2_000L
        const val SOURCE_USAGE = "usage"
        const val SOURCE_DUMP = "dump"
        const val SOURCE_KERNEL = "kernel"
        const val SOURCE_CPU = "cpu"
        const val SOURCE_ALARM = "alarm"
        const val SOURCE_HISTORY = "history"
        const val SOURCE_LIGHT = "light"
        const val PROCESS_FOREGROUND = "FOREGROUND"
        const val PROCESS_BACKGROUND = "BACKGROUND"
        const val PROCESS_FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
        const val PROCESS_CACHED = "CACHED"
    }
}

sealed interface CaptureResult {
    data class Saved(val id: Long, val kind: SnapshotKind) : CaptureResult
    data class Missed(val reason: String) : CaptureResult
}
