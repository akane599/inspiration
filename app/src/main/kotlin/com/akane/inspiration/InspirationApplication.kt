package com.akane.inspiration

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.inspiration.capture.ChargingCaptureScheduler
import com.akane.inspiration.capture.RuntimeCaptureReceiver
import com.akane.inspiration.diagnostics.sensor.AndroidSensorCatalog
import com.akane.inspiration.status.StatusNotificationController
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

class InspirationApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // minSdk 31 is always >= P, where the hidden-API restrictions start. Exempt only the one hidden
        // member the app process reads; the privileged reads run in the Shizuku UserService (shell UID).
        // Without the exemption the sensor catalog falls back to OTHER for every handle.
        try {
            if (!HiddenApiBypass.addHiddenApiExemptions(AndroidSensorCatalog.GET_HANDLE_SIGNATURE)) {
                Log.w(TAG, "Hidden API exemption for Sensor.getHandle() was not applied")
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to add hidden API exemptions", throwable)
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
        appContainer.applicationScope.launch {
            // Access granted in the Shizuku app sends no callback, so it is re-read before the capture needs it.
            // The re-read makes binder calls, which is why it runs here and not on Main in onStart (R-08).
            appContainer.shizukuManager.refresh()
            appContainer.refresh()
        }
    }

    private companion object {
        const val TAG = "InspirationApplication"
    }
}
