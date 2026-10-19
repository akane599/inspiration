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
    private val runningJobs = JobRegistry()
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
                                // The job runs in the app process, so a Cockpit or Health on screen re-reads at once.
                                onHistoryStored = container.storeTicks::tick,
                                runner = runner,
                            )
                        },
                        finish = { jobFinished(params, false) },
                        immediateArmSinceStart = { ChargingCaptureScheduler.immediateArmCount() != immediateArmsAtStart },
                        reArm = {
                            ChargingCaptureScheduler.reArmIfUnchanged(this@ChargingCaptureJobService, immediateArmsAtStart)
                        },
                        log = { message, error -> Log.w(TAG, message, error) },
                    )
                } finally {
                    runningJobs.removeIfSame(params.jobId, currentCoroutineContext()[Job])
                }
            }
        runningJobs.put(params.jobId, job)
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJobs.cancel(params.jobId)
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

/**
 * The running charge-job coroutines by job id; thread-safe. A new start cancels the run it replaces. A run that
 * ends removes its entry only while it is still the registered one (identity, not just the id): the late `finally`
 * of a replaced run must not drop the newer run, which `onStopJob` still has to cancel.
 */
internal class JobRegistry {
    private val jobs = mutableMapOf<Int, Job>()

    fun put(
        jobId: Int,
        job: Job,
    ) {
        synchronized(jobs) {
            jobs[jobId]?.cancel()
            jobs[jobId] = job
        }
    }

    fun removeIfSame(
        jobId: Int,
        job: Job?,
    ) {
        synchronized(jobs) {
            if (jobs[jobId] === job) jobs.remove(jobId)
        }
    }

    fun cancel(jobId: Int) {
        synchronized(jobs) {
            jobs.remove(jobId)?.cancel()
        }
    }
}
