package com.akane.inspiration.capture

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object ChargingCaptureScheduler {
    fun arm(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ChargingCaptureJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .setMinimumLatency(MINIMUM_LATENCY_MS)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }

    const val JOB_ID = 29
    const val MINIMUM_LATENCY_MS = 45 * 60 * 1_000L
}
