package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.diagnostics.model.RuleId

/** How a drain rate reads against typical values. Screen-on rates are never [EXCELLENT]. */
enum class UsageRating { EXCELLENT, NORMAL, HIGH, VERY_HIGH }

sealed interface StorySentence {
    data class Summary(val hours: Double, val levelUsedPct: Int) : StorySentence
    data class ScreenOnRate(val pctPerHour: Double, val rating: UsageRating) : StorySentence
    data class ScreenOffRate(val pctPerHour: Double, val rating: UsageRating) : StorySentence

    /** [ratio] is a fraction in `0..1`. */
    data class DeepSleep(val ratio: Double) : StorySentence
    data class Anomalies(val count: Int, val topRule: RuleId?) : StorySentence
}

data class BatteryStory(val sentences: List<StorySentence>)
