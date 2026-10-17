package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.RuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryStoryBuilderTest {
    private val builder = BatteryStoryBuilder()

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
