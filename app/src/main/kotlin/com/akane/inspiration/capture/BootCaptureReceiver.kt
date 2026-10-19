package com.akane.inspiration.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.status.StatusNotificationController
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.launch

class BootCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val trigger = BootCapturePlan.forAction(intent.action) ?: return
        val app = context.applicationContext as InspirationApplication
        // Synchronous and before goAsync: a foreground service may start only inside the boot exemption window.
        StatusNotificationController.sync(app)
        val pendingResult = goAsync()
        app.appContainer.applicationScope.launch {
            BroadcastCaptureRunner { Log.w("BootCaptureReceiver", "Boot capture or scheduling failed", it) }.run(
                capture = { app.appContainer.capturer.captureLight(trigger) },
                afterCapture = { ChargingCaptureScheduler.arm(app) },
                finish = { pendingResult.finish() },
            )
        }
    }
}

/**
 * The light capture each action of [BootCaptureReceiver] records; null for any other action. An app update is
 * not a reboot: recorded as BOOT it would wipe the known screen state of the "since unplug" summary (audit N-14).
 */
internal object BootCapturePlan {
    fun forAction(action: String?): CaptureTrigger? =
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> CaptureTrigger.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> CaptureTrigger.PACKAGE_REPLACED
            else -> null
        }
}
