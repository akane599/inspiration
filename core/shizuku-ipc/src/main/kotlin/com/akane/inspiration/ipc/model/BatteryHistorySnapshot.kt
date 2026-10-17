package com.akane.inspiration.ipc.model

import kotlinx.parcelize.Parcelize

/**
 * One keep-oldest page of battery history (docs/plans/phase-2.md E3). [events] hold only
 * `historyTimeMs > afterHistoryTimeMs`; [truncated] means more events follow and the caller pages
 * on from the last event's [HistoryEvent.historyTimeMs]. [sessionChanged] means [statsStartMs]
 * differs from the requested session, so the page restarted from cursor 0.
 */
@Parcelize
data class BatteryHistorySnapshot(
    val available: Boolean,
    val reason: String?,
    val statsStartMs: Long,
    val sessionChanged: Boolean,
    val events: List<HistoryEvent>,
    val truncated: Boolean,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): BatteryHistorySnapshot = BatteryHistorySnapshot(
            available = false,
            reason = reason,
            statsStartMs = 0L,
            sessionChanged = false,
            events = emptyList(),
            truncated = false,
        )
    }
}

/**
 * A battery-history transition. [historyTimeMs] is the framework history clock (monotonic);
 * [wallMs] is rebuilt from the latest CURRENT_TIME/RESET anchor, or -1 before the first anchor.
 * [chargeUah] is -1 when the framework did not report a positive charge.
 */
@Parcelize
data class HistoryEvent(
    val historyTimeMs: Long,
    val wallMs: Long,
    val kind: Int,
    val levelPct: Int,
    val chargeUah: Long,
    val temperatureDeciC: Int,
    val voltageMv: Int,
    val plugType: Int,
) : android.os.Parcelable

/** [HistoryEvent.kind] values (pinned in docs/plans/phase-2.md). */
object HistoryKind {
    const val SCREEN_ON = 1
    const val SCREEN_OFF = 2
    const val PLUGGED = 3
    const val UNPLUGGED = 4
    const val LEVEL = 5
    const val RESET = 6
    const val START = 7
    const val SHUTDOWN = 8
    const val DEEP_IDLE_ON = 9
    const val DEEP_IDLE_OFF = 10
}
