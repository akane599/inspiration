package com.akane.inspiration.telemetry.capture

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.diff.BatteryStatsSession
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
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.status.LightCurrent
import com.akane.inspiration.telemetry.store.RETENTION_WINDOW_MS
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.retentionNowMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [calibrationStore] is the live gauge's persisted calibration: with it, the stored light current gets the gauge's
 * unit and sign, or is left out where nothing settled them ([LightCurrent]). Without it, the current is stored as read.
 */
class SnapshotCapturer(
    private val source: TelemetrySource,
    private val store: SnapshotStore,
    private val clocks: Clocks,
    private val calibrationStore: CalibrationStore? = null,
) {
    /**
     * History ingests run one at a time (the app-open refresh, a marker, the charge job all share this capturer):
     * each starts from the cursor, so one that overlapped a slower one would page the same history again.
     */
    private val historyIngestLock = Mutex()

    /** With [ingestHistory] it waits for any other history ingest to finish; [deadlineMs] bounds the read. */
    suspend fun captureFull(
        trigger: CaptureTrigger,
        deadlineMs: Long,
        ingestHistory: Boolean,
    ): CaptureResult =
        if (ingestHistory) {
            historyIngestLock.withLock { readAndSave(trigger, deadlineMs, ingestHistory = true) }
        } else {
            readAndSave(trigger, deadlineMs, ingestHistory = false)
        }

    private suspend fun readAndSave(
        trigger: CaptureTrigger,
        deadlineMs: Long,
        ingestHistory: Boolean,
    ): CaptureResult {
        val cursor = if (ingestHistory) store.historyCursor() ?: (0L to 0L) else null
        val read =
            try {
                withTimeoutOrNull(deadlineMs) {
                    if (ingestHistory) source.full(trigger, cursor) { persistPage(it) } else source.full(trigger, cursor)
                }
            } catch (cancelled: CancellationException) {
                // The caller's guard ended a read that outlived this deadline: the miss is still worth recording.
                missed(trigger, "cancelled")
                throw cancelled
            }
        if (read == null) return missed(trigger, "deadline_or_service_unavailable")
        val snapshot = mergeFull(trigger, read)
        val id = store.insert(snapshot)
        return CaptureResult.Saved(id, SnapshotKind.FULL)
    }

    /**
     * Reads once and persists only the history. The returned [HistoryIngest] keeps that read, so a caller that
     * then decides to capture saves it with [captureFull] instead of making a second full read. Each history page
     * is persisted as it arrives, so the pages read before the deadline count even when the read misses it.
     */
    suspend fun ingestHistory(
        trigger: CaptureTrigger,
        deadlineMs: Long,
    ): HistoryIngest =
        historyIngestLock.withLock {
            val cursor = store.historyCursor() ?: (0L to 0L)
            val read = withTimeoutOrNull(deadlineMs) { source.full(trigger, cursor) { persistPage(it) } }
            HistoryIngest(trigger, read)
        }

    /** Saves the snapshot of [ingest]'s read; a read that failed or timed out is recorded as a miss, not retried. */
    suspend fun captureFull(ingest: HistoryIngest): CaptureResult {
        val read = ingest.read ?: return missed(ingest.trigger, "deadline_or_service_unavailable")
        val id = store.insert(mergeFull(ingest.trigger, read))
        return CaptureResult.Saved(id, SnapshotKind.FULL)
    }

    suspend fun captureLight(trigger: CaptureTrigger): CaptureResult {
        val read = LightCurrent.calibrate(source.light(), calibrationStore)
        val id = store.insert(read.toSnapshot(storedTrigger(trigger, read.clock)))
        return CaptureResult.Saved(id, SnapshotKind.LIGHT)
    }

    /**
     * [CaptureTrigger.STOPPED_STATE_EXIT] for a [CaptureTrigger.BOOT] capture whose [clock] continues the boot of the
     * newest stored capture (Android 15 sends BOOT_COMPLETED when a force-stopped app leaves the stopped state); any
     * other trigger as is. Only the label: readers find boots from the clocks. A capture that ran in the new boot
     * before BOOT_COMPLETED arrived also labels a real reboot so.
     */
    private suspend fun storedTrigger(
        trigger: CaptureTrigger,
        clock: SnapshotClock,
    ): CaptureTrigger {
        if (trigger != CaptureTrigger.BOOT) return trigger
        val previous = store.latest()?.clock ?: return trigger
        return if (clock.sameBootAs(previous)) CaptureTrigger.STOPPED_STATE_EXIT else trigger
    }

    private suspend fun missed(
        trigger: CaptureTrigger,
        reason: String,
    ): CaptureResult.Missed {
        // A caller's guard may share this deadline and fire first; the miss is still worth recording.
        withContext(NonCancellable) { store.recordMiss(clocks.wallMs(), trigger, reason) }
        return CaptureResult.Missed(reason)
    }

    /**
     * Stores one page and moves the cursor past it; a page that arrived in time is kept past the deadline.
     *
     * Events the retention prune would delete now are skipped. A relabelled stats session is paged again from 0 (so
     * is the current one, once, after the store's one-time cursor rewind), and without this the rows the prune already
     * deleted would come back as new rows: first in the new run, ahead of the newer rows that still exist in an earlier
     * run, so ingest order would no longer be chronology.
     */
    private suspend fun persistPage(page: com.akane.inspiration.ipc.model.BatteryHistorySnapshot) {
        if (!page.available) return
        withContext(NonCancellable) {
            val cutoffWallMs = store.retentionNowMs(clocks) - RETENTION_WINDOW_MS
            store.insertHistory(page.events.filter { it.wallMs >= cutoffWallMs }, page.statsStartMs)
            page.events.lastOrNull()?.let { store.setHistoryCursor(page.statsStartMs, it.historyTimeMs) }
        }
    }

    private fun mergeFull(
        trigger: CaptureTrigger,
        read: FullRead,
    ): BatterySnapshot {
        val usage = read.usage?.takeIf { it.available }
        val identity = read.uidIdentity?.takeIf { usage != null && usage.truncatedUids == 0 && it.scope.isKnown() }
        val dump = (read.batteryStats as? DumpParse.Ok)?.value
        val sessionSkew =
            usage != null && dump != null && !BatteryStatsSession.sameStart(dump.system.startClockTimeMs, usage.statsStartMs)
        val usableDump = dump?.takeUnless { sessionSkew }
        val clock =
            read.light.clock.copy(
                statsStartMs = usage?.statsStartMs,
                statsDurationMs = usage?.statsDurationMs,
            )
        val uidDump = usableDump?.uids?.associateBy { it.uid }.orEmpty()
        // The first row wins for a repeated UID, as a per-UID linear search would pick it.
        val uidUsage =
            usage
                ?.uids
                .orEmpty()
                .distinctBy { it.uid }
                .associateBy { it.uid }
        val alarms = mergeAlarms(uidDump.mapValues { it.value.wakeupAlarms })
        val alarmDumps = alarmDumpWakeups(read.alarm)
        val uids =
            (uidUsage.keys + uidDump.keys + alarmDumps.keys)
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
                        ownerInstallFingerprint =
                            identity
                                ?.takeIf { it.scope.covers(uid) }
                                ?.fingerprintsByUid
                                ?.get(uid)
                                ?.let(UidOwnerFingerprint::known),
                    )
                }
        val deviceComponents =
            usage?.deviceComponents?.takeIf { it.isNotEmpty() }
                ?: uids.flatMap { it.componentsMah.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val availability =
            linkedMapOf(
                SOURCE_USAGE to
                    SourceAvailability(usage != null, usage?.reason ?: if (usage == null) read.usage?.reason ?: "unavailable" else null),
                SOURCE_DUMP to
                    SourceAvailability(
                        usableDump != null,
                        if (sessionSkew) "session_skew" else (read.batteryStats as? DumpParse.Unavailable)?.reason,
                    ),
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
            uidIdentityScope = identity?.scope,
        )
    }

    private fun FullRead.toBatteryState(): BatteryState =
        BatteryState(
            levelPct = kernel?.capacityPercent ?: light.levelPct,
            plugged = light.plugged,
            status = kernel?.status ?: light.status,
            temperatureDeciC = kernel?.temperatureDeciC ?: light.temperatureDeciC,
            voltageMv = light.voltageMv,
            currentMa = LightCurrent.calibrate(light, calibrationStore).currentMa,
            chargeCounterUah = light.chargeCounterUah,
            dischargedMah = usage?.takeIf { it.available }?.dischargedPowerMah,
        )

    private fun LightRead.toSnapshot(trigger: CaptureTrigger): BatterySnapshot =
        BatterySnapshot(
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
        (alarm as? DumpParse.Ok)
            ?.value
            ?.filterStats
            .orEmpty()
            .filter { it.wakeupCount > 0 }
            .groupBy { it.uid }
            .mapValues { (_, stats) ->
                stats.groupBy { it.tag }.map { (tag, items) -> UidAlarm(tag, items.sumOf { it.wakeupCount }) }
            }

    private fun mergeWakelocks(rows: List<UidWakelock>): List<UidWakelock> =
        rows
            .groupBy { it.name.ifBlank { "<unnamed>" } }
            .map { (name, values) ->
                UidWakelock(name, values.sumOf { it.durationMs }, values.sumOf { it.bgDurationMs }, values.sumOf { it.count })
            }

    private fun mergeKernelWakelocks(rows: List<com.akane.inspiration.telemetry.dump.KernelWakelock>) =
        rows
            .groupBy { it.name.ifBlank { "<unnamed>" } }
            .map { (name, values) ->
                com.akane.inspiration.telemetry.dump
                    .KernelWakelock(name, values.sumOf { it.durationMs }, values.sumOf { it.count })
            }

    private companion object {
        const val SOURCE_USAGE = "usage"
        const val SOURCE_DUMP = "dump"
        const val SOURCE_KERNEL = "kernel"
        const val SOURCE_CPU = "cpu"
        const val SOURCE_ALARM = "alarm"
        const val SOURCE_HISTORY = com.akane.inspiration.telemetry.store.HISTORY_SOURCE
        const val SOURCE_LIGHT = "light"
        const val PROCESS_FOREGROUND = "FOREGROUND"
        const val PROCESS_BACKGROUND = "BACKGROUND"
        const val PROCESS_FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
        const val PROCESS_CACHED = "CACHED"
    }
}

/** One full read whose history is already persisted; null [read] means it failed or missed its deadline. */
class HistoryIngest internal constructor(
    internal val trigger: CaptureTrigger,
    internal val read: FullRead?,
) {
    val historyAvailable: Boolean get() = read?.history?.any { it.available } == true
}

sealed interface CaptureResult {
    data class Saved(
        val id: Long,
        val kind: SnapshotKind,
    ) : CaptureResult

    data class Missed(
        val reason: String,
    ) : CaptureResult
}
