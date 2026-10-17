package com.akane.inspiration.capture

import android.content.Intent
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

    fun chargeJobShouldRun(
        lastPluggedWallMs: Long?,
        lastChargeJobWallMs: Long?,
    ): Boolean = when {
        lastPluggedWallMs == null -> lastChargeJobWallMs == null
        lastChargeJobWallMs == null -> true
        else -> lastChargeJobWallMs < lastPluggedWallMs
    }

    fun appOpenDebounced(
        lastAppOpenWallMs: Long?,
        nowWallMs: Long,
    ): Boolean = lastAppOpenWallMs == null || nowWallMs - lastAppOpenWallMs >= APP_OPEN_DEBOUNCE_MS

    const val APP_OPEN_DEBOUNCE_MS = 60_000L
    const val BROADCAST_FULL_DEADLINE_MS = 2_000L

    private val broadcastTriggers = mapOf(
        Intent.ACTION_SCREEN_ON to CaptureTrigger.SCREEN_ON,
        Intent.ACTION_SCREEN_OFF to CaptureTrigger.SCREEN_OFF,
        Intent.ACTION_POWER_CONNECTED to CaptureTrigger.POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED to CaptureTrigger.POWER_DISCONNECTED,
        Intent.ACTION_BATTERY_LOW to CaptureTrigger.BATTERY_LOW,
        Intent.ACTION_BATTERY_OKAY to CaptureTrigger.BATTERY_OKAY,
    )
}
