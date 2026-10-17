package com.akane.inspiration.sleeplab

import androidx.compose.ui.unit.dp
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Layout constants of Sleep Lab that are not spacing tokens. */
internal object SleepLabMetrics {
    /** From this content width the tabs and the CPU card sit side by side under the hypnogram. */
    val TwoColumnMinWidth = 600.dp
}

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val KHZ_PER_MHZ = 1_000.0
private const val KHZ_PER_GHZ = 1_000_000L
private const val PERCENT = 100.0

/**
 * A wakelock duration. [TelemetryFormat.duration] counts whole minutes, which would turn the typical wakelock
 * (a few seconds) into "0 min", so anything under a minute is shown in whole seconds with [secondUnit].
 */
internal fun formatSpan(format: TelemetryFormat, durationMs: Long, secondUnit: String): String =
    if (durationMs < MS_PER_MINUTE) {
        "${format.number((durationMs.coerceAtLeast(0L) / MS_PER_SECOND).toDouble())} $secondUnit"
    } else {
        format.duration(durationMs)
    }

/** "1804 MHz", or "2.84 GHz" from one gigahertz up. */
internal fun formatFrequency(format: TelemetryFormat, freqKhz: Long, mhzUnit: String, ghzUnit: String): String =
    if (freqKhz >= KHZ_PER_GHZ) {
        "${format.number(freqKhz.toDouble() / KHZ_PER_GHZ, decimals = 2)} $ghzUnit"
    } else {
        "${format.number(freqKhz / KHZ_PER_MHZ)} $mhzUnit"
    }

/** A 0..1 [share] as a whole-number percentage figure, without the unit. */
internal fun formatShare(format: TelemetryFormat, share: Float): String = format.number(share * PERCENT)

/** "10/16/26, 2:10 PM" in the locale's short date-time style. */
internal fun formatDateTime(ms: Long, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(ms))

/**
 * A range for the slice banner: the start with its date, the end as a bare time when it is the same day, so
 * "10/16/26, 2:10 PM – 4:20 PM". A range over midnight repeats the date on the end.
 */
internal fun formatRange(fromMs: Long, toMs: Long, locale: Locale, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(fromMs).atZone(zone)
    val end = Instant.ofEpochMilli(toMs).atZone(zone)
    val endText = if (start.toLocalDate() == end.toLocalDate()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(end)
    } else {
        formatDateTime(toMs, locale, zone)
    }
    return "${formatDateTime(fromMs, locale, zone)} – $endText"
}

/** A count for a plural string: a count past Int range cannot be a plural quantity, so it saturates. */
internal fun Long.asQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
