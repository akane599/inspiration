package com.akane.inspiration.dashboard

import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Layout constants of the Cockpit that are not spacing tokens. */
internal object CockpitMetrics {
    /** From this content width the story and gauge sit beside the metric grid. */
    val TwoColumnMinWidth = 600.dp

    /** From this font scale the metric tiles stack in one column so their figures keep their lines. */
    const val StackTilesFontScale = 1.3f
}

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

/** A bare local time ("2:10 PM") for the default marker label. */
internal fun formatTime(ms: Long, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(ms))
