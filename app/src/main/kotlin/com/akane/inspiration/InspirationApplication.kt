package com.akane.inspiration

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.inspiration.capture.CapturePolicy
import com.akane.inspiration.capture.RuntimeCaptureReceiver
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class InspirationApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    private var lastAppOpenWallMs: Long? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to add hidden API exemptions", throwable)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        appContainer.shizukuManager.start()
        ContextCompat.registerReceiver(
            this,
            RuntimeCaptureReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun captureAppOpen() {
        val nowWallMs = appContainer.clocks.wallMs()
        if (!CapturePolicy.appOpenDebounced(lastAppOpenWallMs, nowWallMs)) return
        lastAppOpenWallMs = nowWallMs
        appContainer.applicationScope.launch {
            appContainer.capturer.captureFull(
                trigger = CaptureTrigger.APP_OPEN,
                deadlineMs = APP_OPEN_FULL_CAPTURE_DEADLINE_MS,
                ingestHistory = true,
            )
            appContainer.store.prune(appContainer.clocks.wallMs())
        }
    }

    private companion object {
        const val APP_OPEN_FULL_CAPTURE_DEADLINE_MS = 10_000L
        const val TAG = "InspirationApplication"
    }
}
