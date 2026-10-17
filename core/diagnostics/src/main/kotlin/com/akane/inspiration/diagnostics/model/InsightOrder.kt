package com.akane.inspiration.diagnostics.model

/**
 * Severity descending, then evidence magnitude descending, then subject uid (null last), rule
 * and name ascending. Magnitudes: totalMah, awakeRatio, mobileRadioMah, durationMs.
 */
val insightOrder: Comparator<Insight> = compareByDescending<Insight> { it.severity }
    .thenByDescending { magnitudeOf(it) }
    .thenBy(nullsLast<Int>()) { it.subjectUid }
    .thenBy { it.rule }
    .thenBy { nameOf(it) }

private fun magnitudeOf(insight: Insight): Double = when (insight) {
    is NightVampire -> insight.totalMah
    is AwakeViolation -> insight.awakeRatio
    is ModemDrain -> insight.mobileRadioMah
    is SensorLeak -> insight.durationMs.toDouble()
}

private fun nameOf(insight: Insight): String = when (insight) {
    is NightVampire -> insight.packageName.orEmpty()
    is SensorLeak -> "${insight.packageName.orEmpty()}/${insight.sensor.name}"
    is AwakeViolation, is ModemDrain -> ""
}
