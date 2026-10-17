package com.akane.inspiration.capture

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object ChargingCaptureScheduler {
    fun arm(
        context: Context,
        reArmFromJob: Boolean = false,
    ) {
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, ChargingCaptureJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
        minimumLatencyMs(reArmFromJob)?.let(builder::setMinimumLatency)
        context.getSystemService(JobScheduler::class.java).schedule(builder.build())
    }

    fun minimumLatencyMs(reArmFromJob: Boolean): Long? =
        MINIMUM_LATENCY_MS.takeIf { reArmFromJob }

    const val JOB_ID = 29
    const val MINIMUM_LATENCY_MS = 45 * 60 * 1_000L
}
