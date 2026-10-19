package com.akane.inspiration.status

/**
 * The status service's start, post and sync decisions without Android types, so JVM tests pin their order
 * (audit N-28). [StatusNotificationService] and [StatusNotificationController] only supply the effects.
 */
internal object StatusStartDecision {
    enum class Start { STICKY, NOT_STICKY }

    enum class Post { SHOW, DROP, STOP }

    /**
     * One `onStartCommand`. Every start enters the foreground first, including one about to stop: a service started
     * with `startForegroundService` that does not call `startForeground` in time is killed
     * (`ForegroundServiceDidNotStartInTimeException`). Only then is [shouldRun] read, because a sticky restart
     * bypasses the controller. A refused foreground or a run that may not show its notification calls [stop]; else
     * [ensureCollector] runs.
     */
    fun start(
        enterForeground: () -> Boolean,
        shouldRun: () -> Boolean,
        ensureCollector: () -> Unit,
        stop: () -> Unit,
    ): Start {
        if (!enterForeground() || !shouldRun()) {
            stop()
            return Start.NOT_STICKY
        }
        ensureCollector()
        return Start.STICKY
    }

    /**
     * For a finished redraw, under the post lock: a stopped service never posts again (and [shouldRun] is not read),
     * and one that may no longer show its notification stops instead of posting.
     */
    fun post(
        stopped: Boolean,
        shouldRun: () -> Boolean,
    ): Post =
        when {
            stopped -> Post.DROP
            !shouldRun() -> Post.STOP
            else -> Post.SHOW
        }

    /**
     * Starts the service when [shouldRun], else stops it. A refused start is passed to [refused], never thrown
     * (`ForegroundServiceStartNotAllowedException` is an [IllegalStateException]): the boot receiver syncs
     * synchronously, and the next app open or boot retries.
     */
    fun sync(
        shouldRun: () -> Boolean,
        start: () -> Unit,
        stop: () -> Unit,
        refused: (IllegalStateException) -> Unit,
    ) {
        try {
            if (shouldRun()) start() else stop()
        } catch (e: IllegalStateException) {
            refused(e)
        }
    }
}
