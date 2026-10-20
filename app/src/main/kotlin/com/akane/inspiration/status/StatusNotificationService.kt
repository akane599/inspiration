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

    /** Set under [postLock] in [onDestroy], so a redraw already in flight cannot post after the service stops. */
    private var stopped = false
    private val postLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        StatusNotifications.ensureChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val start =
            StatusStartDecision.start(
                // onStartCommand runs on Main, like the controller's sync, so the gate needs no lock.
                gate = StatusNotificationController.startGate,
                enterForeground = ::enterForeground,
                // A sticky restart bypasses the controller, so re-check here (e.g. POST_NOTIFICATIONS revoked meanwhile).
                shouldRun = { shouldRun(container()) },
                ensureCollector = {
                    if (collector?.isActive != true) {
                        val container = container()
                        collector = scope.launch { collect(container) }
                    }
                },
                stop = { stopSelf() },
            )
        return if (start == StatusStartDecision.Start.STICKY) START_STICKY else START_NOT_STICKY
    }

    /** Every start must reach startForeground in time; a repeat start keeps the text already on screen. */
    private fun enterForeground(): Boolean {
        val shown = lastNotification ?: StatusNotifications.build(this, StatusNotifications.placeholder(this))
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, shown, foregroundServiceType())
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enter the foreground", e)
            false
        }
    }

    private fun container(): AppContainer = (application as InspirationApplication).appContainer

    override fun onDestroy() {
        synchronized(postLock) { stopped = true }
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private suspend fun collect(container: AppContainer) {
        // light() reads a sticky broadcast and battery properties; it never touches Shizuku.
        val source = AndroidTelemetrySource(applicationContext, container.shizukuManager, container.clocks)
        redraw(container, source)
        container.statusTicks.collect { redraw(container, source) }
    }

    private suspend fun redraw(
        container: AppContainer,
        source: AndroidTelemetrySource,
    ) {
        try {
            if (stopIfBlocked(container)) return
            val light = source.light()
            val nowMs = light.clock.wallMs
            // The rows since the last unplug; the whole retention window is read only when it holds no unplug.
            val history = container.store.unplugWindowSnapshots(nowMs - HISTORY_WINDOW_MS, nowMs)
            val summary = UnplugSummaryCalculator.compute(history, light.toSnapshot())
            val text =
                StatusNotificationContent.build(
                    summary,
                    StatusNotifications.strings(this),
                    StatusNotifications.format(this),
                )
            val notification = StatusNotifications.build(this, text)
            synchronized(postLock) {
                when (StatusStartDecision.post(stopped) { shouldRun(container) }) {
                    StatusStartDecision.Post.DROP -> {
                        return
                    }

                    StatusStartDecision.Post.STOP -> {
                        stopNow()
                        return
                    }

                    StatusStartDecision.Post.SHOW -> {
                        lastNotification = notification
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One bad redraw must not end the collector; the notification keeps its previous text.
            Log.w(TAG, "Could not redraw the status notification", e)
        }
    }

    private fun stopIfBlocked(container: AppContainer): Boolean {
        if (shouldRun(container)) return false
        stopNow()
        return true
    }

    private fun stopNow() {
        synchronized(postLock) { stopped = true }
        stopSelf()
    }

    private fun shouldRun(container: AppContainer): Boolean =
        StatusNotificationController.shouldRun(container.statusNotificationEnabled.value, container.notificationsBlocked())

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
        fun LightRead.toSnapshot() =
            BatterySnapshot(
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
