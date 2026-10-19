package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.interval.IntervalMetricsCalculator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryStoryBuilderTest {
    private val builder = BatteryStoryBuilder()

    @Test
    fun absentHistoryAndUnevaluatedRulesDoNotInventZeroUsageOrAllClear() {
        val empty = IntervalMetricsCalculator.compute(emptyList(), 0, HOUR_MS)
        val unavailable = DiagnosticReport(emptyList(), RuleId.entries.associateWith { "missing history" }, 0)
        assertEquals(emptyList<StorySentence>(), builder.build(empty, null, unavailable).sentences)
    }

    @Test
    fun partialHistoryOmitsWholeIntervalSummaryButMeasuredZeroIsRetained() {
        assertFalse(builder.build(metrics().copy(unknownMs = 1), null, null).sentences.any { it is StorySentence.Summary })
        assertEquals(StorySentence.Summary(14.0, 0), builder.build(metrics(levelUsedPct = 0), null, null).sentences.first())
    }

    @Test
    fun allInputsProduceEverySentenceInOrder() {
        val story = builder.build(metrics(), sleep(), report(nightVampire, modemDrain))
        assertEquals(
            listOf(
                StorySentence.Summary(hours = 14.0, levelUsedPct = 48),
                StorySentence.ScreenOnRate(8.2, UsageRating.NORMAL),
                StorySentence.ScreenOffRate(0.5, UsageRating.EXCELLENT),
                StorySentence.DeepSleep(0.946),
                StorySentence.Anomalies(count = 2, topRule = RuleId.NIGHT_VAMPIRE),
            ),
            story.sentences,
        )
    }

    @Test
    fun nullMetricsKeepsOnlyDeepSleepAndAnomalies() {
        val story = builder.build(null, sleep(), report())
        assertEquals(
            listOf(StorySentence.DeepSleep(0.946), StorySentence.Anomalies(0, null)),
            story.sentences,
        )
    }

    @Test
    fun nullSleepOmitsDeepSleep() {
        val story = builder.build(metrics(), null, report())
        assertEquals(
            listOf(
                StorySentence.Summary(14.0, 48),
                StorySentence.ScreenOnRate(8.2, UsageRating.NORMAL),
                StorySentence.ScreenOffRate(0.5, UsageRating.EXCELLENT),
                StorySentence.Anomalies(0, null),
            ),
            story.sentences,
        )
    }

    @Test
    fun nullDeepSleepRatioOmitsDeepSleep() {
        val story = builder.build(null, sleep(ratio = null), null)
        assertEquals(emptyList<StorySentence>(), story.sentences)
    }

    @Test
    fun twentySecondsOfScreenOffGiveNoDeepSleepSentence() {
        // A 10-minute interval: 9 min 40 s screen on, then 20 s screen off with the CPU still awake.
        val ref = fullSnapshot(2 * MINUTE_MS, systemTimers(0, 0, 0, 0, 0))
        val now = fullSnapshot(
            12 * MINUTE_MS,
            systemTimers(
                batteryRealtimeMs = 10 * MINUTE_MS,
                batteryUptimeMs = 10 * MINUTE_MS,
                screenOffRealtimeMs = 20_000,
                screenOffUptimeMs = 20_000,
                screenOnMs = 10 * MINUTE_MS - 20_000,
            ),
        )
        val sleep = SnapshotDiffer.diff(ref, now).sleep
        assertEquals("the differ measures a 0% share over the 20 s", 0.0, sleep?.deepSleepRatio ?: -1.0, 0.0)

        // "Deep sleep ratio was 0.0%" would read as a phone that failed to sleep.
        assertEquals(emptyList<StorySentence>(), builder.build(null, sleep, null).sentences)
    }

    @Test
    fun deepSleepNeedsFifteenMinutesOfScreenOff() {
        fun sentences(screenOffMs: Long?) = builder.build(null, sleep(ratio = 0.5, screenOffMs = screenOffMs), null).sentences
        assertEquals(emptyList<StorySentence>(), sentences(15 * MINUTE_MS - 1))
        assertEquals("a ratio with no screen-off time to check is not stated", emptyList<StorySentence>(), sentences(null))
        assertEquals(listOf<StorySentence>(StorySentence.DeepSleep(0.5)), sentences(15 * MINUTE_MS))
    }

    @Test
    fun nullReportOmitsAnomalies() {
        val story = builder.build(metrics(), sleep(), null)
        assertEquals(4, story.sentences.size)
        assertEquals(false, story.sentences.any { it is StorySentence.Anomalies })
    }

    @Test
    fun nullRatesOmitTheirSentencesButKeepTheSummary() {
        val story = builder.build(metrics(screenOnPctPerHour = null, screenOffPctPerHour = null), null, null)
        assertEquals(listOf<StorySentence>(StorySentence.Summary(14.0, 48)), story.sentences)
    }

    @Test
    fun onlyTheNullRateIsOmitted() {
        val onlyOff = builder.build(metrics(screenOnPctPerHour = null), null, null).sentences
        assertEquals(listOf(StorySentence.Summary(14.0, 48), StorySentence.ScreenOffRate(0.5, UsageRating.EXCELLENT)), onlyOff)
        val onlyOn = builder.build(metrics(screenOffPctPerHour = null), null, null).sentences
        assertEquals(listOf(StorySentence.Summary(14.0, 48), StorySentence.ScreenOnRate(8.2, UsageRating.NORMAL)), onlyOn)
    }

    @Test
    fun allNullInputsGiveAnEmptyStory() {
        assertEquals(emptyList<StorySentence>(), builder.build(null, null, null).sentences)
    }

    @Test
    fun summaryHoursAreWallSpanOverMillisPerHour() {
        val summary = builder.build(metrics(hours = 1.5, levelUsedPct = 7), null, null).sentences.first()
        assertEquals(StorySentence.Summary(hours = 1.5, levelUsedPct = 7), summary)
    }

    @Test
    fun screenOnRatingBoundaries() {
        assertEquals(UsageRating.NORMAL, builder.screenOnRating(10.0))
        assertEquals(UsageRating.HIGH, builder.screenOnRating(10.01))
        assertEquals(UsageRating.HIGH, builder.screenOnRating(15.0))
        assertEquals(UsageRating.VERY_HIGH, builder.screenOnRating(15.01))
        assertEquals(UsageRating.NORMAL, builder.screenOnRating(0.0))
    }

    @Test
    fun screenOffRatingBoundaries() {
        assertEquals(UsageRating.EXCELLENT, builder.screenOffRating(0.8))
        assertEquals(UsageRating.NORMAL, builder.screenOffRating(0.81))
        assertEquals(UsageRating.NORMAL, builder.screenOffRating(2.0))
        assertEquals(UsageRating.HIGH, builder.screenOffRating(2.01))
        assertEquals(UsageRating.HIGH, builder.screenOffRating(4.0))
        assertEquals(UsageRating.VERY_HIGH, builder.screenOffRating(4.01))
    }

    @Test
    fun ratingsAreAppliedThroughBuild() {
        val story = builder.build(metrics(screenOnPctPerHour = 16.0, screenOffPctPerHour = 4.5), null, null)
        assertEquals(StorySentence.ScreenOnRate(16.0, UsageRating.VERY_HIGH), story.sentences[1])
        assertEquals(StorySentence.ScreenOffRate(4.5, UsageRating.VERY_HIGH), story.sentences[2])
    }
}
