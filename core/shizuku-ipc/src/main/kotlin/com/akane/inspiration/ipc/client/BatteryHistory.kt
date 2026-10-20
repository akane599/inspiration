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

/**
 * First API whose `BatteryStatsHistoryIterator` implements `Iterator<HistoryItem>` (android14-release). On API 31-33
 * (android12/13-release) it has only `next(HistoryItem out): Boolean`, which this module does not walk.
 */
internal const val API_HISTORY_ITERATOR = 34

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
    const val DEVICE_IDLE_MODE_OFF = 0
    const val DEVICE_IDLE_MODE_LIGHT = 1
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
    private fun dropTrailingTime(
        events: MutableList<HistoryEvent>,
        time: Long,
    ) {
        val keep = events.indexOfLast { it.historyTimeMs != time } + 1
        if (keep > 0) events.subList(keep, events.size).clear()
    }
}

/** Tracks screen/plug/device-idle (off, light, deep)/level baselines and the wall-clock anchor across one walk. */
private class HistoryWalker {
    private var anchorWallMs: Long? = null
    private var anchorTimeMs = 0L
    private var screenOn: Boolean? = null
    private var plugged: Boolean? = null
    private var idleMode: Int? = null
    private var level: Int? = null

    fun advance(record: HistoryRecord): List<HistoryEvent> {
        if (record.cmd == HistoryBits.CMD_CURRENT_TIME || record.cmd == HistoryBits.CMD_RESET) {
            anchorWallMs = record.currentTime
            anchorTimeMs = record.time
        }
        val kinds =
            when (record.cmd) {
                HistoryBits.CMD_RESET -> resetKinds(record).also { setBaseline(record) }

                // START is written before the battery service reports anything (zeroed states), so its state is no
                // evidence and the first UPDATE after it emits the real transitions.
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
        val nowIdleMode = record.idleMode()
        if (screenOn != null && screenOn != nowScreenOn) {
            kinds += if (nowScreenOn) HistoryKind.SCREEN_ON else HistoryKind.SCREEN_OFF
        }
        if (plugged != null && plugged != nowPlugged) {
            kinds += if (nowPlugged) HistoryKind.PLUGGED else HistoryKind.UNPLUGGED
        }
        idleMode?.let { previous -> if (previous != nowIdleMode) kinds += idleTransition(previous, nowIdleMode) }
        if (level != null && level != record.batteryLevel) kinds += HistoryKind.LEVEL
        setBaseline(record)
        return kinds
    }

    /**
     * A RESET is written whole from the live state: at an unplug reset it still carries the screen and doze state
     * (with `plugType` already 0), and at an empty-buffer reset it is a copy of the current state. Consumers treat a
     * RESET as a boundary that ends every state, so the record's screen state and any doze state follow it as events
     * at the same instant; later records are transitions from this baseline. Plug state stays on the RESET's
     * `plugType`: at an unplug reset the plugged flag is still set and the next record emits the UNPLUGGED.
     */
    private fun resetKinds(record: HistoryRecord): List<Int> =
        buildList {
            add(HistoryKind.RESET)
            add(if (record.states and HistoryBits.STATE_SCREEN_ON_FLAG != 0) HistoryKind.SCREEN_ON else HistoryKind.SCREEN_OFF)
            idleEntry(record.idleMode())?.let(::add)
        }

    private fun setBaseline(record: HistoryRecord) {
        screenOn = record.states and HistoryBits.STATE_SCREEN_ON_FLAG != 0
        plugged = record.states and HistoryBits.STATE_BATTERY_PLUGGED_FLAG != 0
        idleMode = record.idleMode()
        level = record.batteryLevel
    }

    /** After an overflow the next item's state is a fresh baseline, not a transition. */
    private fun clearBaseline() {
        screenOn = null
        plugged = null
        idleMode = null
        level = null
    }

    /**
     * Exit of the old doze state first, then entry of the new one, so light to deep (or back) at
     * one timestamp reads as OFF then ON.
     */
    private fun idleTransition(
        from: Int,
        to: Int,
    ): List<Int> =
        buildList {
            when (from) {
                HistoryBits.DEVICE_IDLE_MODE_LIGHT -> add(HistoryKind.LIGHT_IDLE_OFF)
                HistoryBits.DEVICE_IDLE_MODE_DEEP -> add(HistoryKind.DEEP_IDLE_OFF)
            }
            idleEntry(to)?.let(::add)
        }

    /** The entry event of a device-idle mode; null for off. */
    private fun idleEntry(mode: Int): Int? =
        when (mode) {
            HistoryBits.DEVICE_IDLE_MODE_LIGHT -> HistoryKind.LIGHT_IDLE_ON
            HistoryBits.DEVICE_IDLE_MODE_DEEP -> HistoryKind.DEEP_IDLE_ON
            else -> null
        }

    /** The 2-bit device-idle field; the unused value 3 is treated as off. */
    private fun HistoryRecord.idleMode(): Int {
        val mode = (states2 and HistoryBits.STATE2_DEVICE_IDLE_MASK) shr HistoryBits.STATE2_DEVICE_IDLE_SHIFT
        return if (mode == HistoryBits.DEVICE_IDLE_MODE_LIGHT || mode == HistoryBits.DEVICE_IDLE_MODE_DEEP) {
            mode
        } else {
            HistoryBits.DEVICE_IDLE_MODE_OFF
        }
    }

    private fun event(
        record: HistoryRecord,
        kind: Int,
    ) = HistoryEvent(
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
