package com.akane.inspiration.capture

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.telemetry.capacity.KernelCapacity
import com.akane.inspiration.telemetry.live.KernelUnits
import com.akane.inspiration.telemetry.source.AndroidTelemetrySource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

class ChargingCaptureJobService : JobService() {
    private val runningJobs = mutableMapOf<Int, Job>()
    private val runner = ChargingCaptureRunner()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as InspirationApplication
        // An immediate arm (boot, package replaced) after this point replaces the pending job and stops this one.
        val immediateArmsAtStart = ChargingCaptureScheduler.immediateArmCount()
        val job =
            app.appContainer.applicationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    runChargeJob(
                        work = {
                            val container = app.appContainer
                            runChargeJobCapture(
                                capturer = container.capturer,
                                store = container.store,
                                clocks = container.clocks,
                                deadlineMs = FULL_CAPTURE_DEADLINE_MS,
                                analyze = {
                                    ChargeHealthPipeline(
                                        store = container.store,
                                        kernelCapacity = { readKernelCapacity(app) },
                                        log = { message, error -> Log.w(TAG, message, error) },
                                    ).run(container.clocks.wallMs())
                                },
                                runner = runner,
                            )
                        },
                        finish = { jobFinished(params, false) },
                        immediateArmSinceStart = { ChargingCaptureScheduler.immediateArmCount() != immediateArmsAtStart },
                        reArm = { ChargingCaptureScheduler.arm(this@ChargingCaptureJobService, reArmFromJob = true) },
                        log = { message, error -> Log.w(TAG, message, error) },
                    )
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

    // One bounded kernel read through the existing telemetry source; null (no Shizuku, no sysfs node) skips analysis.
    private suspend fun readKernelCapacity(app: InspirationApplication): KernelCapacity? {
        val container = app.appContainer
        return AndroidTelemetrySource(app, container.shizukuManager, container.clocks)
            .kernelReading()
            ?.kernel
            ?.takeIf { it.available }
            ?.let(KernelUnits::kernelCapacity)
    }

    private companion object {
        const val FULL_CAPTURE_DEADLINE_MS = 10_000L
        const val TAG = "ChargingCaptureJob"
    }
}
