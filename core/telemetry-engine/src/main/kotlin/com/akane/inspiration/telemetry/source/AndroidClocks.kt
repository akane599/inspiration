package com.akane.inspiration.telemetry.source

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.akane.inspiration.telemetry.common.Clocks

class AndroidClocks(
    private val context: Context,
) : Clocks {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun uptimeMs(): Long = SystemClock.uptimeMillis()

    override fun bootCount(): Int = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.BOOT_COUNT,
        0,
    )
}
