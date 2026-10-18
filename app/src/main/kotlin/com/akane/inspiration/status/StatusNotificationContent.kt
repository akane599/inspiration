package com.akane.inspiration.status

import com.akane.inspiration.designsystem.component.format.MINUS_SIGN
import com.akane.inspiration.designsystem.component.format.NO_VALUE
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.telemetry.status.UnplugSummary
import java.time.ZoneId
import kotlin.math.abs

/**
 * The already-resolved texts [StatusNotificationContent] composes, so the composition stays a plain JVM function.
 * Every field except [chargingHint] is a `String.format` template with numbered arguments, so a language can
 * reorder them ("%18" in Turkish, "18%" in English).
 *
 * - [percent]: `%1$s` is the number.
 * - [titleSinceUnplug]: `%1$s` signed drop, `%2$s` elapsed time.
 * - [titleSinceTime]: `%1$s` signed drop, `%2$s` the anchor time, `%3$s` elapsed time (no unplug was seen).
 * - [detail]: `%1$s` screen-on rate, `%2$s` screen-off rate, `%3$s` deep-sleep share.
 * - [charging]: `%1$s` the battery level.
 */
data class StatusStrings(
    val percent: String,
    val titleSinceUnplug: String,
    val titleSinceTime: String,
    val detail: String,
    val charging: String,
    val chargingHint: String,
)

/** What the notification shows: a one-line [title] and the [text] under it. */
data class StatusText(val title: String, val text: String)

/** Pure: the status notification's wording for one [UnplugSummary]; unavailable parts read "—". */
object StatusNotificationContent {
    private const val DAY_MS = 24 * 3_600_000L

    fun build(
        summary: UnplugSummary,
        strings: StatusStrings,
        format: TelemetryFormat,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StatusText = when (summary) {
        is UnplugSummary.Charging -> StatusText(
            title = strings.charging.format(format.locale, level(summary.levelNow, strings, format)),
            text = strings.chargingHint,
        )
        is UnplugSummary.Discharging -> discharging(summary, strings, format, zone)
    }

    private fun discharging(
        summary: UnplugSummary.Discharging,
        strings: StatusStrings,
        format: TelemetryFormat,
        zone: ZoneId,
    ): StatusText {
        val locale = format.locale
        val drop = signedDrop(summary.dropPct, strings, format)
        val elapsed = format.duration(summary.elapsedMs)
        val title = if (summary.anchorIsUnplug) {
            strings.titleSinceUnplug.format(locale, drop, elapsed)
        } else {
            val since = if (summary.elapsedMs >= DAY_MS) {
                format.dateTime(summary.anchorWallMs, zone)
            } else {
                format.time(summary.anchorWallMs, zone)
            }
            strings.titleSinceTime.format(locale, drop, since, elapsed)
        }
        val text = strings.detail.format(
            locale,
            rate(summary.screenOnPctPerHour, format),
            rate(summary.screenOffPctPerHour, format),
            summary.deepSleepRatio?.let { percent(it * 100, strings, format) } ?: NO_VALUE,
        )
        return StatusText(title, text)
    }

    private fun level(levelNow: Int?, strings: StatusStrings, format: TelemetryFormat): String =
        levelNow?.let { percent(it.toDouble(), strings, format) } ?: NO_VALUE

    /** "−18%": the typographic minus in front of the localised percentage, "0%" when nothing was lost. */
    private fun signedDrop(dropPct: Int, strings: StatusStrings, format: TelemetryFormat): String {
        val body = percent(abs(dropPct).toDouble(), strings, format)
        return if (dropPct > 0) MINUS_SIGN + body else body
    }

    private fun percent(value: Double, strings: StatusStrings, format: TelemetryFormat): String =
        strings.percent.format(format.locale, format.number(value))

    private fun rate(value: Double?, format: TelemetryFormat): String =
        if (value == null) NO_VALUE else format.percentPerHour(value).text
}
