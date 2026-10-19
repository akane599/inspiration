package com.akane.inspiration.diagnostics.story

import com.akane.inspiration.telemetry.snapshot.SleepStats

/**
 * The least screen-off time a deep-sleep share is reported for: the 15-minute floor the interval metrics and the
 * unplug summary use for a rate. Over a shorter screen-off (say 20 s still awake after the screen went off) the
 * share is noise, and a 0% would read as a phone that failed to sleep.
 */
const val MIN_SLEEP_WINDOW_MS: Long = 15 * 60_000L

/**
 * [SleepStats.deepSleepRatio] when the interval has at least [MIN_SLEEP_WINDOW_MS] of screen-off time, else null.
 * The Battery Story, the Cockpit tile and Sleep Lab read the share through this, so they agree on when it is shown.
 */
fun SleepStats.reportableDeepSleepRatio(): Double? =
    deepSleepRatio?.takeIf { (screenOffMs ?: 0L) >= MIN_SLEEP_WINDOW_MS }
