package com.akane.inspiration.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akane.inspiration.InspirationApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
        when (plan) {
            is CapturePlan.Light -> runBlocking(Dispatchers.IO) {
                runner.run(
                    capture = {
                        app.appContainer.statusTicker.emitAfter {
                            app.appContainer.capturer.captureLight(plan.trigger)
                        }
                    },
                )
            }

            is CapturePlan.Full -> {
                val pendingResult = goAsync()
                app.appContainer.applicationScope.launch {
                    runner.run(
                        capture = {
                            app.appContainer.statusTicker.emitAfter {
                                withTimeoutOrNull(plan.deadlineMs) {
                                    app.appContainer.capturer.captureFull(
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
        }
    }
}
