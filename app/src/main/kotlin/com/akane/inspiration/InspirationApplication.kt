package com.akane.inspiration

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.inspiration.capture.ChargingCaptureScheduler
import com.akane.inspiration.capture.RuntimeCaptureReceiver
import com.akane.inspiration.status.StatusNotificationController
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class InspirationApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

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
        appContainer.statusSync = { StatusNotificationController.sync(this) }
        appContainer.shizukuManager.start()
        appContainer.start()
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

    /** Delegates to the container's one debounced, mutex-guarded refresh (H7). */
    fun captureAppOpen() {
        ChargingCaptureScheduler.ensureScheduled(this)
        appContainer.applicationScope.launch { appContainer.refresh() }
    }

    private companion object {
        const val TAG = "InspirationApplication"
    }
}
