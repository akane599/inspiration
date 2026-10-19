package com.akane.inspiration.status

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.akane.inspiration.InspirationApplication

/** Starts or stops the status service so it runs exactly when the switch is on and notifications can be shown. */
object StatusNotificationController {
    private const val TAG = "StatusNotification"

    /** An invisible resident service would be observer cost with no benefit, so a blocked notification means stopped. */
    fun shouldRun(
        enabled: Boolean,
        blocked: Boolean,
    ): Boolean = enabled && !blocked

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val container = (appContext as InspirationApplication).appContainer
        val intent = Intent(appContext, StatusNotificationService::class.java)
        StatusStartDecision.sync(
            shouldRun = { shouldRun(container.statusNotificationEnabled.value, container.notificationsBlocked()) },
            start = { ContextCompat.startForegroundService(appContext, intent) },
            stop = { appContext.stopService(intent) },
            // Includes ForegroundServiceStartNotAllowedException: started from a background state with no exemption.
            refused = { Log.w(TAG, "Could not sync the status service", it) },
        )
    }
}
