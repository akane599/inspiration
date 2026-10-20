package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.diagnostics.rules.MIN_SCREEN_OFF_MS
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface CapturePlan {
    data class Light(val trigger: CaptureTrigger) : CapturePlan

    data class Full(
        val trigger: CaptureTrigger,
        val deadlineMs: Long,
    ) : CapturePlan
}

object CapturePolicy {
    /**
     * A broadcast takes a FULL capture without a history ingest while the telemetry service is bound
     * ([serviceWarm]), else a LIGHT one. The screen broadcasts also bind it on demand when Shizuku is ready (audit
     * R-03): the screen-off interval needs batterystats and per-UID data at both ends, and a LIGHT row has neither.
     * SCREEN_OFF always does; SCREEN_ON only when the screen was off ([screenOffForMs], from the stored screen
     * captures) for at least [MIN_SCREEN_OFF_MS], the shortest period the screen-off rules evaluate, so a short
     * lock/unlock cycle costs one bind, not two.
     */
    fun forBroadcast(
        action: String,
        serviceWarm: Boolean,
        shizukuReady: Boolean = false,
        screenOffForMs: Long? = null,
    ): CapturePlan? {
        val trigger = broadcastTriggers[action] ?: return null
        return when {
            serviceWarm -> CapturePlan.Full(trigger, BROADCAST_FULL_DEADLINE_MS)
            shizukuReady && bindsOnDemand(trigger, screenOffForMs) -> CapturePlan.Full(trigger, BROADCAST_BIND_FULL_DEADLINE_MS)
            else -> CapturePlan.Light(trigger)
        }
    }

    private fun bindsOnDemand(
        trigger: CaptureTrigger,
        screenOffForMs: Long?,
    ): Boolean =
        when (trigger) {
            CaptureTrigger.SCREEN_OFF -> true
            CaptureTrigger.SCREEN_ON -> screenOffForMs != null && screenOffForMs >= MIN_SCREEN_OFF_MS
            else -> false
        }

    /** Whether [forBroadcast] decides on [screenOffForMs] for [action]; only then the receiver reads the store for it. */
    fun needsScreenOffDuration(
        action: String,
        serviceWarm: Boolean,
        shizukuReady: Boolean,
    ): Boolean = action == Intent.ACTION_SCREEN_ON && !serviceWarm && shizukuReady

    /**
     * How long the screen has been off, from the newest SCREEN_OFF capture ([lastScreenOff]) when it is of this
     * boot and newer than the newest SCREEN_ON capture ([lastScreenOn]); elapsed time, so a wall-clock change cannot
     * stretch it. Null when unknown: no such capture, or a newer screen-on one (its screen-off capture was missed or
     * is still being written).
     */
    fun screenOffForMs(
        lastScreenOff: SnapshotClock?,
        lastScreenOn: SnapshotClock?,
        nowElapsedMs: Long,
        bootCount: Int,
    ): Long? {
        if (lastScreenOff == null || lastScreenOff.bootCount != bootCount) return null
        if (lastScreenOn != null && lastScreenOn.bootCount == bootCount && lastScreenOn.elapsedRealtimeMs >= lastScreenOff.elapsedRealtimeMs) {
            return null
        }
        return (nowElapsedMs - lastScreenOff.elapsedRealtimeMs).takeIf { it >= 0L }
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

    const val APP_OPEN_DEBOUNCE_MS = 60_000L
    const val BROADCAST_FULL_DEADLINE_MS = 2_000L

    /**
     * A screen capture that binds the service first: a cold bind took 295 ms on API 37 (PROGRESS, US-1), and the
     * source gives up on one after 5 s. With [BROADCAST_PERSIST_BUDGET_MS] the guard is 7.5 s, inside the 10 s
     * receiver limit; a capture that misses it stores a LIGHT row instead.
     */
    const val BROADCAST_BIND_FULL_DEADLINE_MS = 6_000L
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
 * light snapshot, so the trigger still has a row: the "since unplug" anchor and the charge job read it. The
 * fallback also runs when the receiver's guard has already cancelled the capture.
 */
internal suspend fun SnapshotCapturer.captureFor(plan: CapturePlan): CaptureResult = when (plan) {
    is CapturePlan.Light -> captureLight(plan.trigger)
    is CapturePlan.Full -> try {
        captureFull(trigger = plan.trigger, deadlineMs = plan.deadlineMs, ingestHistory = false)
    } catch (cancelled: CancellationException) {
        lightFallback(plan.trigger)
        throw cancelled
    }.let { result -> if (result is CaptureResult.Missed) lightFallback(plan.trigger) else result }
}

private suspend fun SnapshotCapturer.lightFallback(trigger: CaptureTrigger): CaptureResult =
    withContext(NonCancellable) { captureLight(trigger) }
