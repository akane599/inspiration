package com.akane.inspiration.status

import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Something worth redrawing the status notification for just happened" (a capture finished or an app-open
 * refresh succeeded). Nothing here polls: ticks come only from captures that already run. A burst collapses
 * to one pending tick (DROP_OLDEST), and a tick with no collector is simply dropped.
 */
class StatusTicks {
    private val mutableTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val ticks: SharedFlow<Unit> = mutableTicks.asSharedFlow()

    fun emit() {
        mutableTicks.tryEmit(Unit)
    }

    /** Runs [block], then emits a tick however it ended (result, miss, timeout or exception). */
    inline fun <T> emitAfter(block: () -> T): T = try {
        block()
    } finally {
        emit()
    }
}

/** The notification channel the status service posts on; T3b creates it, the Toolkit card reads its state. */
object StatusChannel {
    const val ID = "status"

    /** Blocked when the app's notifications are off as a whole, or the user silenced this channel entirely. */
    fun isBlocked(notificationsEnabled: Boolean, channelImportance: Int?): Boolean =
        !notificationsEnabled || channelImportance == NotificationManagerCompat.IMPORTANCE_NONE
}
