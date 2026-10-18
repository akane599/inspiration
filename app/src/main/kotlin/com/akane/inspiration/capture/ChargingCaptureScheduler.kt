package com.akane.inspiration.capture

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicLong

object ChargingCaptureScheduler {
    /** Immediate (zero-latency) arms so far in this process; a running job compares it to see one replaced it. */
    private val immediateArms = AtomicLong()

    fun immediateArmCount(): Long = immediateArms.get()

    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) arm(context)
    }

    fun arm(
        context: Context,
        reArmFromJob: Boolean = false,
    ) {
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, ChargingCaptureJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
        minimumLatencyMs(reArmFromJob)?.let(builder::setMinimumLatency)
        // Counted before schedule(): the same-ID schedule stops a running job, whose re-arm must then see it.
        if (!reArmFromJob) immediateArms.incrementAndGet()
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
    }

    fun minimumLatencyMs(reArmFromJob: Boolean): Long? =
        MINIMUM_LATENCY_MS.takeIf { reArmFromJob }

    const val JOB_ID = 29
    const val MINIMUM_LATENCY_MS = 45 * 60 * 1_000L
}
