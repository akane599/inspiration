package com.akane.inspiration.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akane.inspiration.InspirationApplication

class RuntimeCaptureReceiver : BroadcastReceiver() {
    private val runner = BroadcastCaptureRunner { Log.w("RuntimeCaptureReceiver", "Event capture failed", it) }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as InspirationApplication
        val plan = CapturePolicy.forBroadcast(
            action = intent.action ?: return,
            serviceWarm = app.appContainer.shizukuManager.telemetryService.value != null,
        ) ?: return
        val pendingResult = goAsync()
        runner.start(
            scope = app.appContainer.applicationScope,
            deadlineMs = (plan as? CapturePlan.Full)?.deadlineMs ?: CapturePolicy.BROADCAST_FULL_DEADLINE_MS,
            capture = {
                app.appContainer.statusTicker.emitAfter {
                    when (plan) {
                        is CapturePlan.Light -> app.appContainer.capturer.captureLight(plan.trigger)
                        is CapturePlan.Full -> app.appContainer.capturer.captureFull(
                            trigger = plan.trigger,
                            deadlineMs = plan.deadlineMs,
                            ingestHistory = false,
                        )
                    }
                }
            },
            finish = { pendingResult.finish() },
        )
    }
}
