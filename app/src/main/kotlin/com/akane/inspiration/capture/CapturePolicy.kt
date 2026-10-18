package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger

sealed interface CapturePlan {
    data class Light(val trigger: CaptureTrigger) : CapturePlan

    data class Full(
        val trigger: CaptureTrigger,
        val deadlineMs: Long,
    ) : CapturePlan
}

object CapturePolicy {
    fun forBroadcast(
        action: String,
        serviceWarm: Boolean,
    ): CapturePlan? {
        val trigger = broadcastTriggers[action] ?: return null
        return when (action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            -> CapturePlan.Light(trigger)

            else -> if (serviceWarm) {
                CapturePlan.Full(trigger, BROADCAST_FULL_DEADLINE_MS)
            } else {
                CapturePlan.Light(trigger)
            }
        }
    }

    /**
     * The receiver's outer guard. A full plan's own read deadline must not be the whole budget: the guard
     * would cancel a read that finished just in time mid-insert, or the miss and light fallback after a late
     * one. Read deadline plus [BROADCAST_PERSIST_BUDGET_MS] stays well inside the 10 s receiver limit.
     */
    fun broadcastGuardMs(plan: CapturePlan): Long = when (plan) {
        is CapturePlan.Full -> plan.deadlineMs + BROADCAST_PERSIST_BUDGET_MS
        is CapturePlan.Light -> BROADCAST_FULL_DEADLINE_MS
    }

    fun chargeJobShouldRun(
        lastPluggedHistoryWallMs: Long?,
        lastPowerConnectedWallMs: Long?,
        lastChargeJobWallMs: Long?,
        lastChargeJobBootCount: Int?,
        currentBootCount: Int,
        historyAvailable: Boolean,
    ): Boolean {
        if (lastChargeJobWallMs == null || lastChargeJobBootCount != currentBootCount) return true
        val sessionStartWallMs = if (historyAvailable) lastPluggedHistoryWallMs else lastPowerConnectedWallMs
        return sessionStartWallMs != null && lastChargeJobWallMs < sessionStartWallMs
    }

    fun appOpenDebounced(
        lastAppOpenWallMs: Long?,
        nowWallMs: Long,
    ): Boolean = lastAppOpenWallMs == null || nowWallMs - lastAppOpenWallMs >= APP_OPEN_DEBOUNCE_MS

    const val APP_OPEN_DEBOUNCE_MS = 60_000L
    const val BROADCAST_FULL_DEADLINE_MS = 2_000L
    const val BROADCAST_PERSIST_BUDGET_MS = 1_500L

    private val broadcastTriggers = mapOf(
        Intent.ACTION_SCREEN_ON to CaptureTrigger.SCREEN_ON,
        Intent.ACTION_SCREEN_OFF to CaptureTrigger.SCREEN_OFF,
        Intent.ACTION_POWER_CONNECTED to CaptureTrigger.POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED to CaptureTrigger.POWER_DISCONNECTED,
        Intent.ACTION_BATTERY_LOW to CaptureTrigger.BATTERY_LOW,
        Intent.ACTION_BATTERY_OKAY to CaptureTrigger.BATTERY_OKAY,
    )
}

/**
 * Captures one broadcast [plan]. A full capture that misses its deadline (the miss is recorded) falls back to a
 * light snapshot, so the trigger still has a row: the "since unplug" anchor and the charge job read it.
 */
internal suspend fun SnapshotCapturer.captureFor(plan: CapturePlan): CaptureResult = when (plan) {
    is CapturePlan.Light -> captureLight(plan.trigger)
    is CapturePlan.Full -> captureFull(trigger = plan.trigger, deadlineMs = plan.deadlineMs, ingestHistory = false)
        .let { result -> if (result is CaptureResult.Missed) captureLight(plan.trigger) else result }
}
