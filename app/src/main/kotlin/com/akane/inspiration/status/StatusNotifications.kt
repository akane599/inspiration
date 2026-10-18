package com.akane.inspiration.status

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.akane.inspiration.MainActivity
import com.akane.inspiration.R
import com.akane.inspiration.designsystem.component.format.TelemetryFormat
import com.akane.inspiration.designsystem.component.format.TelemetryUnits
import com.akane.inspiration.designsystem.R as DesignSystemR

/** The Android side of the status notification: its channel, the localised inputs and the [Notification] itself. */
internal object StatusNotifications {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                StatusChannel.ID,
                context.getString(R.string.status_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.status_channel_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    fun strings(context: Context) =
        StatusStrings(
            percent = context.getString(R.string.status_percent),
            titleSinceUnplug = context.getString(R.string.status_title_since_unplug),
            titleSinceTime = context.getString(R.string.status_title_since_time),
            detail = context.getString(R.string.status_detail),
            charging = context.getString(R.string.status_charging),
            chargingHint = context.getString(R.string.status_charging_hint),
        )

    fun format(context: Context): TelemetryFormat {
        val locale = context.resources.configuration.locales[0]
        return TelemetryFormat(
            locale,
            TelemetryUnits(
                milliamps = context.getString(DesignSystemR.string.telemetry_unit_ma),
                watts = context.getString(DesignSystemR.string.telemetry_unit_w),
                millivolts = context.getString(DesignSystemR.string.telemetry_unit_mv),
                celsius = context.getString(DesignSystemR.string.telemetry_unit_celsius),
                percentPerHour = context.getString(DesignSystemR.string.telemetry_unit_percent_per_hour),
                milliampHoursPerHour = context.getString(DesignSystemR.string.telemetry_unit_mah_per_hour),
                hour = context.getString(DesignSystemR.string.telemetry_unit_hour),
                minute = context.getString(DesignSystemR.string.telemetry_unit_minute),
            ),
        )
    }

    fun placeholder(context: Context) = StatusText(context.getString(R.string.status_reading), "")

    fun build(
        context: Context,
        content: StatusText,
    ): Notification {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, StatusChannel.ID)
            .setSmallIcon(R.drawable.ic_stat_status)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // A deferred FGS notification is posted ~10 s late and overwrites the first redraw's content.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
