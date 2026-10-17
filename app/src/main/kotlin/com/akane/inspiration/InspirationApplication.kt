package com.akane.inspiration

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
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
    }

    private companion object {
        const val TAG = "InspirationApplication"
    }
}
