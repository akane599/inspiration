package com.akane.inspiration.capture

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicLong

object ChargingCaptureScheduler {
    private val schedulingLock = Any()

    /** Immediate (zero-latency) arms so far in this process; a running job compares it to see one replaced it. */
    private val immediateArms = AtomicLong()

    fun immediateArmCount(): Long = immediateArms.get()

    fun ensureScheduled(context: Context) {
        ensureScheduled(
            hasPendingJob = { context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null },
            schedule = { jobId, latencyMs -> schedule(context, jobId, latencyMs) },
        )
    }

    fun arm(
        context: Context,
        reArmFromJob: Boolean = false,
    ) {
        arm(reArmFromJob, schedule = { jobId, latencyMs -> schedule(context, jobId, latencyMs) })
    }

    internal fun reArmIfUnchanged(
        context: Context,
        expectedImmediateArms: Long,
    ) {
        reArmIfUnchanged(expectedImmediateArms, schedule = { jobId, latencyMs -> schedule(context, jobId, latencyMs) })
    }

    internal fun ensureScheduled(
        hasPendingJob: () -> Boolean,
        schedule: (Int, Long?) -> Unit,
    ) {
        synchronized(schedulingLock) {
            if (!hasPendingJob()) arm(schedule = schedule)
        }
    }

    internal fun arm(
        reArmFromJob: Boolean = false,
        schedule: (Int, Long?) -> Unit,
    ) {
        synchronized(schedulingLock) {
            // Counted before schedule(): the same-ID schedule stops a running job, whose re-arm must then see it.
            if (!reArmFromJob) immediateArms.incrementAndGet()
            schedule(JOB_ID, minimumLatencyMs(reArmFromJob))
        }
    }

    internal fun reArmIfUnchanged(
        expectedImmediateArms: Long,
        schedule: (Int, Long?) -> Unit,
    ) {
        synchronized(schedulingLock) {
            // The runner's unlocked precheck is only an optimization: keep this check and submission atomic.
            if (immediateArms.get() != expectedImmediateArms) return
            arm(reArmFromJob = true, schedule = schedule)
        }
    }

    private fun schedule(
        context: Context,
        jobId: Int,
        minimumLatencyMs: Long?,
    ) {
        val builder =
            JobInfo
                .Builder(jobId, ComponentName(context, ChargingCaptureJobService::class.java))
                .setRequiresCharging(true)
                .setPersisted(true)
        minimumLatencyMs?.let(builder::setMinimumLatency)
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
    }

    fun minimumLatencyMs(reArmFromJob: Boolean): Long? = MINIMUM_LATENCY_MS.takeIf { reArmFromJob }

    const val JOB_ID = 29
    const val MINIMUM_LATENCY_MS = 45 * 60 * 1_000L
}
