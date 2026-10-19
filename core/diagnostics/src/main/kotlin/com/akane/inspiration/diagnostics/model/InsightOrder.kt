package com.akane.inspiration.diagnostics.model

/**
 * Severity descending, then the fixed [rulePriority], then evidence magnitude descending within a rule, then
 * subject uid (null last) and name ascending. The first insight is the story's "most notable" one.
 *
 * Magnitudes (totalMah, awakeRatio, mobileRadioMah, durationMs) are in different units, so they only rank
 * insights of one rule. Across rules the order is fixed: a normalised "magnitude / threshold" score would need
 * the per-interval thresholds (Night Vampire's depends on the interval's discharge, a motion leak's on its
 * screen-off time), which an insight does not carry, and even then two rules' multiples of their thresholds do
 * not mean the same battery cost.
 */
val insightOrder: Comparator<Insight> =
    compareByDescending<Insight> { it.severity }
        .thenBy { rulePriority.getValue(it.rule) }
        .thenByDescending { magnitudeOf(it) }
        .thenBy(nullsLast<Int>()) { it.subjectUid }
        .thenBy { nameOf(it) }

/**
 * Rules whose evidence is measured energy come before rules inferred from time: Night Vampire (mAh one app drew
 * in the background), Modem Drain (mAh of the mobile radio), Awake Violation (share of screen-off time awake,
 * device-wide), Sensor Leak (how long one app held a sensor; its energy is not measured).
 */
private val rulePriority: Map<RuleId, Int> =
    listOf(
        RuleId.NIGHT_VAMPIRE,
        RuleId.MODEM_DRAIN,
        RuleId.AWAKE_VIOLATION,
        RuleId.SENSOR_LEAK,
    ).withIndex().associate { (index, rule) -> rule to index }

private fun magnitudeOf(insight: Insight): Double =
    when (insight) {
        is NightVampire -> insight.totalMah
        is AwakeViolation -> insight.awakeRatio
        is ModemDrain -> insight.mobileRadioMah
        is SensorLeak -> insight.durationMs.toDouble()
    }

private fun nameOf(insight: Insight): String =
    when (insight) {
        is NightVampire -> insight.packageName.orEmpty()
        is SensorLeak -> "${insight.packageName.orEmpty()}/${insight.sensor.name}"
        is AwakeViolation, is ModemDrain -> ""
    }
