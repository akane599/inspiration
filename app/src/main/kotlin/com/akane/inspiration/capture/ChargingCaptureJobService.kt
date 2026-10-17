package com.akane.inspiration.capture

import android.app.job.JobParameters
import android.app.job.JobService
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChargingCaptureJobService : JobService() {
    private val runningJobs = mutableMapOf<Int, Job>()
    private val runner = ChargingCaptureRunner()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as InspirationApplication
        val job = app.appContainer.applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val container = app.appContainer
                val store = container.store
                val historyAvailable = container.capturer.ingestHistory(FULL_CAPTURE_DEADLINE_MS)
                val lastPluggedHistoryWallMs = if (historyAvailable) {
                    store.historyBetween(0L, Long.MAX_VALUE)
                        .lastOrNull { it.kind == HistoryKind.PLUGGED }
                        ?.wallMs
                } else {
                    null
                }
                val lastPowerConnectedWallMs = store.latestByTrigger(CaptureTrigger.POWER_CONNECTED)?.clock?.wallMs
                val lastChargeJob = store.latestByTrigger(CaptureTrigger.CHARGE_JOB)
                runner.run(
                    shouldCapture = CapturePolicy.chargeJobShouldRun(
                        lastPluggedHistoryWallMs = lastPluggedHistoryWallMs,
                        lastPowerConnectedWallMs = lastPowerConnectedWallMs,
                        lastChargeJobWallMs = lastChargeJob?.clock?.wallMs,
                        lastChargeJobBootCount = lastChargeJob?.clock?.bootCount,
                        currentBootCount = container.clocks.bootCount(),
                        historyAvailable = historyAvailable,
                    ),
                    capture = {
                        container.capturer.captureFull(
                            trigger = CaptureTrigger.CHARGE_JOB,
                            deadlineMs = FULL_CAPTURE_DEADLINE_MS,
                            ingestHistory = true,
                        )
                    },
                    prune = { store.prune(container.clocks.wallMs()) },
                )
                if (isActive) {
                    // A same-ID schedule cancels active work, so finish before re-arming the next attempt.
                    jobFinished(params, false)
                    ChargingCaptureScheduler.arm(this@ChargingCaptureJobService, reArmFromJob = true)
                }
            } catch (_: CancellationException) {
                // onStopJob has already stopped this job; re-arm only after its coroutine is cancelled.
                ChargingCaptureScheduler.arm(this@ChargingCaptureJobService, reArmFromJob = true)
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                synchronized(runningJobs) {
                    if (runningJobs[params.jobId] === currentJob) {
                        runningJobs.remove(params.jobId)
                    }
                }
            }
        }
        synchronized(runningJobs) {
            runningJobs[params.jobId]?.cancel()
            runningJobs[params.jobId] = job
        }
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(runningJobs) {
            runningJobs.remove(params.jobId)?.cancel()
        }
        return false
    }

    private companion object {
        const val FULL_CAPTURE_DEADLINE_MS = 10_000L
    }
}
