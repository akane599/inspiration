package com.akane.inspiration.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.status.StatusNotificationController
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.launch

class BootCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in BOOT_ACTIONS) return
        val app = context.applicationContext as InspirationApplication
        // Synchronous and before goAsync: a foreground service may start only inside the boot exemption window.
        StatusNotificationController.sync(app)
        val pendingResult = goAsync()
        app.appContainer.applicationScope.launch {
            try {
                app.appContainer.capturer.captureLight(CaptureTrigger.BOOT)
                ChargingCaptureScheduler.arm(app)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
