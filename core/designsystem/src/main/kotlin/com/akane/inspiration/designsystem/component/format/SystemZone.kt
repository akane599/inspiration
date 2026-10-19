package com.akane.inspiration.designsystem.component.format

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import java.time.DateTimeException
import java.time.ZoneId
import java.util.TimeZone

/**
 * The system time zone as one remembered instance, for every composable that takes a `zone`.
 *
 * `ZoneId` is not a stable Compose type, so it is compared by identity, and each `ZoneId.systemDefault()` call builds a
 * new object: passing a fresh one on every recomposition defeats skipping in the charts and rebuilds the hypnogram's
 * draw cache. This instance changes only when the user changes the time zone ([Intent.ACTION_TIMEZONE_CHANGED]), so a
 * screen still follows a new zone without leaving composition.
 */
@Composable
fun rememberSystemZoneId(): ZoneId {
    val context = LocalContext.current
    // The default structural policy skips a write of an equal zone, so the instance (and skipping) survives it.
    val zone = remember { mutableStateOf(ZoneId.systemDefault()) }
    // Previews and screenshot renders have no broadcasts to follow.
    if (LocalInspectionMode.current) return zone.value
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    zone.value = zoneAfterChange(intent.getStringExtra(Intent.EXTRA_TIMEZONE))
                }
            }
        // A protected system broadcast, so no export flag is needed (as with the battery broadcast in HealthRoute).
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        // A change while this was out of composition is picked up on return.
        zone.value = ZoneId.systemDefault()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone.value
}

/**
 * [rememberSystemZoneId] as a `java.util.TimeZone`, for screens that take one. `TimeZone.getDefault()` returns a new
 * copy on every call, so it defeats skipping the same way.
 */
@Composable
fun rememberSystemTimeZone(): TimeZone {
    val zone = rememberSystemZoneId()
    return remember(zone) { TimeZone.getTimeZone(zone) }
}

/**
 * The zone a time-zone broadcast names in [Intent.EXTRA_TIMEZONE]; the system default when the extra is missing or not
 * a known zone. Reading the extra does not depend on the broadcast arriving after the process's default was reset.
 */
internal fun zoneAfterChange(id: String?): ZoneId =
    try {
        if (id != null) ZoneId.of(id) else ZoneId.systemDefault()
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }
