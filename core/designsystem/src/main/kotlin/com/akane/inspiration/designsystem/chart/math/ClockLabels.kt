package com.akane.inspiration.designsystem.chart.math

import com.akane.inspiration.designsystem.chart.model.TimeRange
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.TelemetryUnits
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The clock and tick-label rule both charts share (the scrub chart and the hypnogram). Sub-day ticks switch to the
 * date at the start of a day once the window covers two days or more. A 48 h window that does not start at midnight
 * holds two midnights, and with 12 h ticks a stricter `>` labelled both of them "00:00".
 */
internal fun TimeRange.usesDateTicks(): Boolean = durationMs >= 2 * DAY_MS

// One immutable format per locale, so the scrub readout builds none per frame.
private val clockFormats = ConcurrentHashMap<Locale, TelemetryFormat>()

/** Only the date and time styles are used here, which take no units. */
private fun clockFormat(locale: Locale): TelemetryFormat = clockFormats.getOrPut(locale) { TelemetryFormat(locale, TelemetryUnits.English) }

/** "21:30", or "16 Oct 21:30" with [withDate]; the date-time is joined with no-break spaces (`TelemetryFormat`). */
internal fun formatClock(
    tMs: Long,
    zone: ZoneId,
    withDate: Boolean,
    locale: Locale,
): String = clockFormat(locale).let { if (withDate) it.dateTime(tMs, zone) else it.time(tMs, zone) }

/**
 * Chosen from the tick step, not only the window length: day-step ticks ([dayAligned]) show the date; sub-day
 * ticks show the time, except that on a multi-day window ([usesDateTicks]) a tick at the start of a day shows
 * its date, so a 2–3 day window with 12 h ticks reads "17 Oct, 12:00, 18 Oct" rather than repeating dates.
 */
internal fun formatTickLabel(
    tMs: Long,
    zone: ZoneId,
    window: TimeRange,
    dayAligned: Boolean,
    locale: Locale,
): String =
    if (dayAligned || (window.usesDateTicks() && isStartOfDay(tMs, zone))) {
        clockFormat(locale).date(tMs, zone)
    } else {
        formatClock(tMs, zone, withDate = false, locale = locale)
    }
