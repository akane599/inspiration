package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.SleepStats

/**
 * Builds the Battery Story from measured facts only; a sentence whose input is missing is omitted, and so is a
 * deep-sleep share measured over less than [MIN_SLEEP_WINDOW_MS] of screen-off.
 */
class BatteryStoryBuilder {
    fun build(metrics: IntervalMetrics?, sleep: SleepStats?, report: DiagnosticReport?): BatteryStory {
        val sentences = buildList {
            if (metrics != null) {
                val duration = metrics.toWallMs - metrics.fromWallMs
                val measured = metrics.screenOnMs + metrics.screenOffMs + metrics.chargingMs
                if (duration > 0 && metrics.startLevel != null && metrics.endLevel != null &&
                    metrics.unknownMs == 0L && metrics.discontinuities.isEmpty() && measured == duration
                ) {
                    add(StorySentence.Summary(duration / MS_PER_HOUR, metrics.levelUsedPct))
                }
                metrics.screenOnPctPerHour?.let { add(StorySentence.ScreenOnRate(it, screenOnRating(it))) }
                metrics.screenOffPctPerHour?.let { add(StorySentence.ScreenOffRate(it, screenOffRating(it))) }
            }
            sleep?.reportableDeepSleepRatio()?.let { add(StorySentence.DeepSleep(it)) }
            if (report != null && (report.insights.isNotEmpty() || report.notEvaluable.isEmpty())) {
                add(StorySentence.Anomalies(report.insights.size, report.insights.firstOrNull()?.rule))
            }
        }
        return BatteryStory(sentences)
    }

    internal fun screenOnRating(pctPerHour: Double): UsageRating = when {
        pctPerHour <= 10.0 -> UsageRating.NORMAL
        pctPerHour <= 15.0 -> UsageRating.HIGH
        else -> UsageRating.VERY_HIGH
    }

    internal fun screenOffRating(pctPerHour: Double): UsageRating = when {
        pctPerHour <= 0.8 -> UsageRating.EXCELLENT
        pctPerHour <= 2.0 -> UsageRating.NORMAL
        pctPerHour <= 4.0 -> UsageRating.HIGH
        else -> UsageRating.VERY_HIGH
    }

    private companion object {
        const val MS_PER_HOUR = 3.6e6
    }
}
