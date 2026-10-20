package com.akane.inspiration.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.ipc.ShizukuState

class RuntimeCaptureReceiver : BroadcastReceiver() {
    private val runner = BroadcastCaptureRunner { Log.w("RuntimeCaptureReceiver", "Event capture failed", it) }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as InspirationApplication
        val container = app.appContainer
        val action = intent.action ?: return
        val serviceWarm = container.shizukuManager.telemetryService.value != null
        val shizukuReady = container.shizukuManager.state.value is ShizukuState.Ready
        // The guard is sized for the most a plan can cost here; a screen-on capture settles its plan off the main
        // thread, after reading how long the screen was off from the store (audit R-03).
        val ceiling = CapturePolicy.forBroadcast(action, serviceWarm, shizukuReady, screenOffForMs = Long.MAX_VALUE) ?: return
        val decideOnDuration = CapturePolicy.needsScreenOffDuration(action, serviceWarm, shizukuReady)
        val pendingResult = goAsync()
        runner.start(
            scope = container.applicationScope,
            deadlineMs = CapturePolicy.broadcastGuardMs(ceiling),
            capture = {
                val plan =
                    if (decideOnDuration) {
                        CapturePolicy.forBroadcast(action, serviceWarm, shizukuReady, container.screenOffForMs()) ?: ceiling
                    } else {
                        ceiling
                    }
                container.statusTicker.emitAfter { container.capturer.captureFor(plan) }
            },
            finish = { pendingResult.finish() },
        )
    }
}
