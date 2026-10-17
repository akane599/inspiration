package com.akane.inspiration.ipc.client

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind

/**
 * Primitive copy of one framework `BatteryStats.HistoryItem`. The framework iterator reuses one
 * mutable item, so adapters copy it before handing it on.
 */
data class HistoryRecord(
    val time: Long,
    val currentTime: Long,
    val cmd: Int,
    val states: Int,
    val states2: Int,
    val batteryLevel: Int,
    val plugType: Int,
    val chargeUah: Int,
    val temperatureDeciC: Int,
    val voltageMv: Int,
)

/** Read-only view of a `BatteryUsageStats` queried with `includeBatteryHistory()`. */
interface BatteryHistoryAdapter : AutoCloseable {
    val statsStartMs: Long

    /** Called at most once; the walk may stop early. */
    fun records(): Iterator<HistoryRecord>

    /** Releases the history iterator and the stats object; never throws. */
    override fun close()
}

/**
 * `BatteryStats.HistoryItem` command and state bits, identical in the android-35, android-36 and
 * android-37.0 sources (BatteryStats.java HistoryItem; android-36 :1888-1996, :2602).
 */
internal object HistoryBits {
    const val CMD_UPDATE = 0
    const val CMD_START = 4
    const val CMD_CURRENT_TIME = 5
    const val CMD_OVERFLOW = 6
    const val CMD_RESET = 7
    const val CMD_SHUTDOWN = 8

    const val STATE_BATTERY_PLUGGED_FLAG = 1 shl 19
    const val STATE_SCREEN_ON_FLAG = 1 shl 20
    const val STATE2_DEVICE_IDLE_SHIFT = 25
    const val STATE2_DEVICE_IDLE_MASK = 0x3 shl STATE2_DEVICE_IDLE_SHIFT
    const val DEVICE_IDLE_MODE_DEEP = 2
}

/** Turns a history walk into E3 events. Pure; driven by JVM tests through [HistoryRecord]s. */
internal object BatteryHistoryExtractor {
    const val MAX_EVENTS = 4096

    fun extract(
        sessionStatsStartMs: Long,
        records: Iterator<HistoryRecord>,
        requestedStatsStartMs: Long,
        afterHistoryTimeMs: Long,
        maxEvents: Int,
    ): BatteryHistorySnapshot {
        val sessionChanged = sessionStatsStartMs != requestedStatsStartMs
        val after = if (sessionChanged) 0L else afterHistoryTimeMs
        val cap = maxEvents.coerceIn(0, MAX_EVENTS)
        val events = ArrayList<HistoryEvent>()
        val walker = HistoryWalker()
        var truncated = false
        while (records.hasNext()) {
            val record = records.next()
            // State is tracked from the start of history even before the cursor.
            val itemEvents = walker.advance(record)
            if (record.time <= after || itemEvents.isEmpty()) continue
            if (events.size + itemEvents.size > cap) {
                truncated = true
                dropTrailingTime(events, record.time)
                break
            }
            events += itemEvents
        }
        return BatteryHistorySnapshot(
            available = true,
            reason = null,
            statsStartMs = sessionStatsStartMs,
            sessionChanged = sessionChanged,
            events = events,
            truncated = truncated,
        )
    }

    /**
     * The next page resumes at `historyTimeMs > last`, so a page must not end in the middle of
     * one timestamp. Keeps them only if they are the whole page (no progress otherwise).
     */
    private fun dropTrailingTime(events: MutableList<HistoryEvent>, time: Long) {
        val keep = events.indexOfLast { it.historyTimeMs != time } + 1
        if (keep > 0) events.subList(keep, events.size).clear()
    }
}

/** Tracks screen/plug/deep-idle/level baselines and the wall-clock anchor across one walk. */
private class HistoryWalker {
    private var anchorWallMs: Long? = null
    private var anchorTimeMs = 0L
    private var screenOn: Boolean? = null
    private var plugged: Boolean? = null
    private var deepIdle: Boolean? = null
    private var level: Int? = null

    fun advance(record: HistoryRecord): List<HistoryEvent> {
        if (record.cmd == HistoryBits.CMD_CURRENT_TIME || record.cmd == HistoryBits.CMD_RESET) {
            anchorWallMs = record.currentTime
            anchorTimeMs = record.time
        }
        val kinds = when (record.cmd) {
            HistoryBits.CMD_RESET -> listOf(HistoryKind.RESET).also { setBaseline(record) }
            HistoryBits.CMD_START -> listOf(HistoryKind.START).also { setBaseline(record) }
            HistoryBits.CMD_SHUTDOWN -> listOf(HistoryKind.SHUTDOWN)
            HistoryBits.CMD_OVERFLOW -> emptyList<Int>().also { clearBaseline() }
            HistoryBits.CMD_UPDATE, HistoryBits.CMD_CURRENT_TIME -> transitions(record)
            else -> emptyList()
        }
        return kinds.map { kind -> event(record, kind) }
    }

    private fun transitions(record: HistoryRecord): List<Int> {
        val kinds = ArrayList<Int>(2)
        val nowScreenOn = record.states and HistoryBits.STATE_SCREEN_ON_FLAG != 0
        val nowPlugged = record.states and HistoryBits.STATE_BATTERY_PLUGGED_FLAG != 0
        val nowDeepIdle = record.isDeepIdle()
        if (screenOn != null && screenOn != nowScreenOn) {
            kinds += if (nowScreenOn) HistoryKind.SCREEN_ON else HistoryKind.SCREEN_OFF
        }
        if (plugged != null && plugged != nowPlugged) {
            kinds += if (nowPlugged) HistoryKind.PLUGGED else HistoryKind.UNPLUGGED
        }
        if (deepIdle != null && deepIdle != nowDeepIdle) {
            kinds += if (nowDeepIdle) HistoryKind.DEEP_IDLE_ON else HistoryKind.DEEP_IDLE_OFF
        }
        if (level != null && level != record.batteryLevel) kinds += HistoryKind.LEVEL
        setBaseline(record)
        return kinds
    }

    private fun setBaseline(record: HistoryRecord) {
        screenOn = record.states and HistoryBits.STATE_SCREEN_ON_FLAG != 0
        plugged = record.states and HistoryBits.STATE_BATTERY_PLUGGED_FLAG != 0
        deepIdle = record.isDeepIdle()
        level = record.batteryLevel
    }

    /** After an overflow the next item's state is a fresh baseline, not a transition. */
    private fun clearBaseline() {
        screenOn = null
        plugged = null
        deepIdle = null
        level = null
    }

    private fun HistoryRecord.isDeepIdle(): Boolean =
        (states2 and HistoryBits.STATE2_DEVICE_IDLE_MASK) shr HistoryBits.STATE2_DEVICE_IDLE_SHIFT ==
            HistoryBits.DEVICE_IDLE_MODE_DEEP

    private fun event(record: HistoryRecord, kind: Int) = HistoryEvent(
        historyTimeMs = record.time,
        wallMs = anchorWallMs?.let { it + (record.time - anchorTimeMs) } ?: -1L,
        kind = kind,
        levelPct = record.batteryLevel,
        chargeUah = if (record.chargeUah > 0) record.chargeUah.toLong() else -1L,
        temperatureDeciC = record.temperatureDeciC,
        voltageMv = record.voltageMv,
        plugType = record.plugType,
    )
}
