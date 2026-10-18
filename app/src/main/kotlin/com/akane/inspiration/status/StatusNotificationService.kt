package com.akane.inspiration.status

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.akane.inspiration.AppContainer
import com.akane.inspiration.InspirationApplication
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.source.AndroidTelemetrySource
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.status.UnplugSummaryCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The resident foreground service behind the status notification. It redraws once at start and then on each
 * [AppContainer.statusTicks] (a finished capture or app-open refresh); it has no battery receiver, timer or loop
 * of its own and never holds the Shizuku binding, because the numbers come from stored light snapshots plus one
 * fresh light read.
 */
class StatusNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collector: Job? = null
    private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StatusNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must reach startForeground in time; a repeat start keeps the text already on screen.
        val shown = lastNotification ?: StatusNotifications.build(this, StatusNotifications.placeholder(this))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, shown, foregroundServiceType())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (collector?.isActive != true) {
            val container = (application as InspirationApplication).appContainer
            collector = scope.launch { collect(container) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun collect(container: AppContainer) {
        // light() reads a sticky broadcast and battery properties; it never touches Shizuku.
        val source = AndroidTelemetrySource(applicationContext, container.shizukuManager, container.clocks)
        redraw(container, source)
        container.statusTicks.collect { redraw(container, source) }
    }

    private suspend fun redraw(container: AppContainer, source: AndroidTelemetrySource) {
        try {
            val light = source.light()
            val nowMs = light.clock.wallMs
            val history = container.store.snapshotsBetween(nowMs - HISTORY_WINDOW_MS, nowMs)
            val summary = UnplugSummaryCalculator.compute(history, light.toSnapshot())
            val text = StatusNotificationContent.build(
                summary,
                StatusNotifications.strings(this),
                StatusNotifications.format(this),
            )
            val notification = StatusNotifications.build(this, text)
            lastNotification = notification
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One bad redraw must not end the collector; the notification keeps its previous text.
            Log.w(TAG, "Could not redraw the status notification", e)
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    private companion object {
        const val TAG = "StatusNotification"
        const val NOTIFICATION_ID = 1
        const val HISTORY_WINDOW_MS = 30L * 24 * 3_600_000

        /** The calculator's "now" is a snapshot like the stored ones, so a light read is wrapped, never stored. */
        fun LightRead.toSnapshot() = BatterySnapshot(
            id = 0L,
            kind = SnapshotKind.LIGHT,
            trigger = CaptureTrigger.APP_OPEN,
            clock = clock,
            battery = BatteryState(levelPct, plugged, status, temperatureDeciC, voltageMv, currentMa, chargeCounterUah, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = mapOf("light" to SourceAvailability(true, null)),
            truncatedUids = 0,
        )
    }
}
