package com.akane.inspiration.diagnostics.story

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.akane.inspiration.diagnostics.R
import com.akane.inspiration.diagnostics.model.RuleId
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/** Renders a [BatteryStory] as one paragraph from `res/values` (en) or `res/values-tr` (tr). */
class AndroidStoryWriter(private val context: Context) {

    // Callers pass the current UI locale (whose split is always installed) or English (the base values),
    // and the app ships as an APK, so no language split can be missing.
    @SuppressLint("AppBundleLocaleChanges")
    fun render(story: BatteryStory, locale: Locale): String {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val resources = context.createConfigurationContext(config).resources
        val oneDecimal = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        val trimmedDecimal = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }
        return story.sentences.joinToString(" ") { sentence ->
            when (sentence) {
                is StorySentence.Summary -> summary(resources, trimmedDecimal, sentence)
                is StorySentence.ScreenOnRate -> resources.getString(
                    R.string.story_screen_on_rate,
                    oneDecimal.format(sentence.pctPerHour),
                    resources.getString(ratingRes(sentence.rating)),
                )
                is StorySentence.ScreenOffRate -> resources.getString(
                    R.string.story_screen_off_rate,
                    oneDecimal.format(sentence.pctPerHour),
                    resources.getString(ratingRes(sentence.rating)),
                )
                is StorySentence.DeepSleep -> resources.getString(
                    R.string.story_deep_sleep,
                    oneDecimal.format(sentence.ratio * PERCENT),
                )
                is StorySentence.Anomalies -> anomalies(resources, sentence)
            }
        }
    }

    private fun summary(resources: Resources, hoursFormat: NumberFormat, sentence: StorySentence.Summary): String {
        // The plural quantity follows the displayed value, so 1.04 h reads "1 hour" but 1.5 h "1.5 hours".
        val displayedTenths = (sentence.hours * 10).roundToLong()
        // "In the last ..." only holds for an interval that runs up to now; a past one reads "Over these ...".
        if (displayedTenths < 10L) {
            // "In the last 0 hours" is meaningless: below one displayed hour, report minutes.
            val minutes = maxOf(1L, (sentence.hours * MINUTES_PER_HOUR).roundToLong()).toInt()
            val minutesRes =
                if (sentence.endsNow) R.plurals.story_summary_minutes else R.plurals.story_summary_past_minutes
            return resources.getQuantityString(minutesRes, minutes, minutes, sentence.levelUsedPct)
        }
        val quantity = if (displayedTenths == 10L) 1 else 2
        return resources.getQuantityString(
            if (sentence.endsNow) R.plurals.story_summary else R.plurals.story_summary_past,
            quantity,
            hoursFormat.format(displayedTenths / 10.0),
            sentence.levelUsedPct,
        )
    }

    private fun anomalies(resources: Resources, sentence: StorySentence.Anomalies): String {
        val topRule = sentence.topRule
        return when {
            sentence.count <= 0 -> resources.getString(R.string.story_anomalies_none)
            topRule == null -> resources.getQuantityString(R.plurals.story_anomalies_count, sentence.count, sentence.count)
            else -> resources.getQuantityString(
                R.plurals.story_anomalies_top,
                sentence.count,
                sentence.count,
                resources.getString(ruleNameRes(topRule)),
            )
        }
    }

    private fun ratingRes(rating: UsageRating): Int = when (rating) {
        UsageRating.EXCELLENT -> R.string.story_rating_excellent
        UsageRating.NORMAL -> R.string.story_rating_normal
        UsageRating.HIGH -> R.string.story_rating_high
        UsageRating.VERY_HIGH -> R.string.story_rating_very_high
    }

    private fun ruleNameRes(rule: RuleId): Int = when (rule) {
        RuleId.NIGHT_VAMPIRE -> R.string.rule_name_night_vampire
        RuleId.AWAKE_VIOLATION -> R.string.rule_name_awake_violation
        RuleId.MODEM_DRAIN -> R.string.rule_name_modem_drain
        RuleId.SENSOR_LEAK -> R.string.rule_name_sensor_leak
    }

    private companion object {
        const val PERCENT = 100.0
        const val MINUTES_PER_HOUR = 60.0
    }
}
