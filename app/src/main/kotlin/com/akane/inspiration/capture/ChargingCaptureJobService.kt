package com.akane.inspiration.capture

import android.app.job.JobParameters
import android.app.job.JobService
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.launch

class ChargingCaptureJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as InspirationApplication
        app.appContainer.applicationScope.launch {
            try {
                val store = app.appContainer.store
                val lastPluggedWallMs = store.latestByTrigger(CaptureTrigger.POWER_CONNECTED)?.clock?.wallMs
                val lastChargeJobWallMs = store.latestByTrigger(CaptureTrigger.CHARGE_JOB)?.clock?.wallMs
                if (CapturePolicy.chargeJobShouldRun(lastPluggedWallMs, lastChargeJobWallMs)) {
                    app.appContainer.capturer.captureFull(
                        trigger = CaptureTrigger.CHARGE_JOB,
                        deadlineMs = FULL_CAPTURE_DEADLINE_MS,
                        ingestHistory = true,
                    )
                    store.prune(app.appContainer.clocks.wallMs())
                }
            } finally {
                ChargingCaptureScheduler.arm(this@ChargingCaptureJobService)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    private companion object {
        const val FULL_CAPTURE_DEADLINE_MS = 10_000L
    }
}
