package com.akane.inspiration.database.store

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource

/** A history source whose every read returns the pages [read] makes of the cursor it was given. */
internal class PagedHistorySource(
    private val read: (Pair<Long, Long>?) -> List<BatteryHistorySnapshot>,
) : TelemetrySource {
    val receivedCursors = mutableListOf<Pair<Long, Long>?>()

    override suspend fun full(
        trigger: CaptureTrigger,
        historyCursor: Pair<Long, Long>?,
    ): FullRead {
        receivedCursors += historyCursor
        return FullRead(
            light = light(),
            usage = null,
            batteryStats = DumpParse.Unavailable("not_needed"),
            alarm = DumpParse.Unavailable("not_needed"),
            kernel = null,
            cpuTimes = emptyList(),
            history = read(historyCursor),
        )
    }

    override fun light(): LightRead =
        LightRead(
            clock = SnapshotClock(1L, 2L, 3L, 4, null, null),
            levelPct = 60,
            plugged = false,
            status = "discharging",
            temperatureDeciC = 250,
            voltageMv = 4_000,
            currentMa = -100,
            chargeCounterUah = 1_000_000L,
        )

    companion object {
        /** Each read returns the next of [reads], whatever the cursor. */
        fun scripted(vararg reads: List<BatteryHistorySnapshot>): PagedHistorySource {
            val queue = ArrayDeque(reads.toList())
            return PagedHistorySource { queue.removeFirst() }
        }
    }
}

/** A device clock the test moves: a step, a jump, a reboot. */
internal class MovableClocks(
    var wallMs: Long,
    var elapsedMs: Long = 2L,
    var bootCount: Int = 0,
) : Clocks {
    override fun wallMs(): Long = wallMs

    override fun elapsedRealtimeMs(): Long = elapsedMs

    override fun uptimeMs(): Long = 3L

    override fun bootCount(): Int = bootCount
}

internal fun historyPage(
    statsStartMs: Long,
    vararg events: HistoryEvent,
    truncated: Boolean = false,
    sessionChanged: Boolean = false,
) = BatteryHistorySnapshot(true, null, statsStartMs, sessionChanged, events.toList(), truncated)

internal fun historyEvent(
    historyTimeMs: Long,
    wallMs: Long,
    kind: Int = HistoryKind.LEVEL,
) = HistoryEvent(historyTimeMs, wallMs, kind, 60, -1L, 250, 4_000, 0)

internal const val INGEST_DEADLINE_MS = 5_000L
internal const val HOUR_MS = 3_600_000L
internal const val DAY_MS = 24L * HOUR_MS
internal const val WALL_BASE_MS = 1_790_000_000_000L
