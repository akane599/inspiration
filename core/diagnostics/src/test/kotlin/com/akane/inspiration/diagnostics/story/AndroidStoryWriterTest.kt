package com.akane.inspiration.diagnostics.story

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.diagnostics.model.RuleId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidStoryWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val writer = AndroidStoryWriter(context)
    private val tr = Locale.forLanguageTag("tr")

    // TEMPLATE.md section 5 Ekran 1 item 2: 14 h, 48%, 8.2 %/h on, 0.5 %/h off, 94.6% deep sleep.
    private val example = BatteryStory(
        listOf(
            StorySentence.Summary(hours = 14.0, levelUsedPct = 48),
            StorySentence.ScreenOnRate(8.2, UsageRating.NORMAL),
            StorySentence.ScreenOffRate(0.5, UsageRating.EXCELLENT),
            StorySentence.DeepSleep(0.946),
            StorySentence.Anomalies(0, null),
        ),
    )

    @Test
    fun rendersTheSectionFiveExampleInEnglish() {
        assertEquals(
            "In the last 14 hours you used 48% of your battery. " +
                "With the screen on you used 8.2% per hour (normal). " +
                "With the screen off you used 0.5% per hour (excellent). " +
                "Deep sleep ratio was 94.6%. " +
                "No anomalies detected.",
            writer.render(example, Locale.ENGLISH),
        )
    }

    @Test
    fun rendersTheSectionFiveExampleInTurkishWithCommaDecimals() {
        assertEquals(
            "Son 14 saatte pilin %48 kadarını kullandın. " +
                "Ekran açıkken saatte %8,2 tükettin (normal). " +
                "Ekran kapalıyken saatte %0,5 tükettin (kusursuz). " +
                "Derin uyku oranı %94,6. " +
                "Herhangi bir anomali tespit edilmedi.",
            writer.render(example, tr),
        )
    }

    @Test
    fun anomalySentenceNamesTheTopRuleInBothLocales() {
        val story = BatteryStory(listOf(StorySentence.Anomalies(3, RuleId.AWAKE_VIOLATION)))
        assertEquals("3 anomalies detected, most notable: Awake violation.", writer.render(story, Locale.ENGLISH))
        assertEquals("3 anomali tespit edildi, en belirgini: Uyanıklık ihlali.", writer.render(story, tr))
    }

    @Test
    fun singleAnomalyUsesTheSingularPlural() {
        val story = BatteryStory(listOf(StorySentence.Anomalies(1, RuleId.SENSOR_LEAK)))
        assertEquals("1 anomaly detected: Sensor leak.", writer.render(story, Locale.ENGLISH))
    }

    @Test
    fun hoursPluralFollowsTheDisplayedValue() {
        fun hours(value: Double) = writer.render(BatteryStory(listOf(StorySentence.Summary(value, 5))), Locale.ENGLISH)
        assertEquals("In the last 1 hour you used 5% of your battery.", hours(1.0))
        assertEquals("In the last 1 hour you used 5% of your battery.", hours(1.04))
        assertEquals("In the last 1.5 hours you used 5% of your battery.", hours(1.5))
        assertEquals("In the last 1.5 hours you used 5% of your battery.", hours(1.54))
    }

    @Test
    fun intervalsUnderOneHourReadInMinutes() {
        fun summary(hours: Double, locale: Locale) =
            writer.render(BatteryStory(listOf(StorySentence.Summary(hours, 5))), locale)
        val thirtySeconds = 30.0 / 3600.0
        assertEquals("In the last 1 minute you used 5% of your battery.", summary(thirtySeconds, Locale.ENGLISH))
        assertEquals("Son 1 dakikada pilin %5 kadarını kullandın.", summary(thirtySeconds, tr))
        assertEquals("In the last 45 minutes you used 5% of your battery.", summary(0.75, Locale.ENGLISH))
        assertEquals("Son 45 dakikada pilin %5 kadarını kullandın.", summary(0.75, tr))
    }

    @Test
    fun aboutOneHourBoundaryKeepsTheHoursForm() {
        fun summary(hours: Double, locale: Locale) =
            writer.render(BatteryStory(listOf(StorySentence.Summary(hours, 5))), locale)
        // 0.96 h displays as 1.0 h, so it must not become "58 minutes".
        assertEquals("In the last 1 hour you used 5% of your battery.", summary(0.96, Locale.ENGLISH))
        assertEquals("Son 1 saatte pilin %5 kadarını kullandın.", summary(0.96, tr))
        // 0.94 h displays as 0.9 h, below the boundary, so it reads in minutes.
        assertEquals("In the last 56 minutes you used 5% of your battery.", summary(0.94, Locale.ENGLISH))
    }

    @Test
    fun turkishHoursUseCommaDecimal() {
        val text = writer.render(BatteryStory(listOf(StorySentence.Summary(2.5, 5))), tr)
        assertEquals("Son 2,5 saatte pilin %5 kadarını kullandın.", text)
    }

    @Test
    fun anIntervalThatEndedInThePastIsNotTheLastHoursInEitherLocale() {
        fun past(hours: Double, locale: Locale) =
            writer.render(BatteryStory(listOf(StorySentence.Summary(hours, 20, endsNow = false))), locale)
        assertEquals("Over these 4 hours you used 20% of your battery.", past(4.0, Locale.ENGLISH))
        assertEquals("Bu 4 saatte pilin %20 kadarını kullandın.", past(4.0, tr))
        assertEquals("Over this 1 hour you used 20% of your battery.", past(1.0, Locale.ENGLISH))
        assertEquals("Bu 2,5 saatte pilin %20 kadarını kullandın.", past(2.5, tr))
        assertEquals("Over these 45 minutes you used 20% of your battery.", past(0.75, Locale.ENGLISH))
        assertEquals("Bu 45 dakikada pilin %20 kadarını kullandın.", past(0.75, tr))
        assertEquals("Over this 1 minute you used 20% of your battery.", past(30.0 / 3600.0, Locale.ENGLISH))
    }

    @Test
    fun highRatingsAndEmptyStory() {
        val story = BatteryStory(
            listOf(
                StorySentence.ScreenOnRate(12.0, UsageRating.HIGH),
                StorySentence.ScreenOffRate(5.0, UsageRating.VERY_HIGH),
            ),
        )
        assertEquals(
            "With the screen on you used 12.0% per hour (high). With the screen off you used 5.0% per hour (very high).",
            writer.render(story, Locale.ENGLISH),
        )
        assertEquals("", writer.render(BatteryStory(emptyList()), Locale.ENGLISH))
    }
}
