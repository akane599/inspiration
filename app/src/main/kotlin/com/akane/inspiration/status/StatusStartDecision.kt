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
     * bypasses the controller and an OFF that arrived while the start was pending was deferred to this read (R-06).
     * A refused foreground or a run that may not show its notification calls [stop]; else [ensureCollector] runs.
     * However it ends (including by throwing), the start is [StatusStartGate.settled].
     */
    fun start(
        gate: StatusStartGate,
        enterForeground: () -> Boolean,
        shouldRun: () -> Boolean,
        ensureCollector: () -> Unit,
        stop: () -> Unit,
    ): Start {
        try {
            if (!enterForeground() || !shouldRun()) {
                stop()
                return Start.NOT_STICKY
            }
            ensureCollector()
            return Start.STICKY
        } finally {
            gate.settled()
        }
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
     *
     * While a start is [StatusStartGate.pending] the stop is not sent (R-06): stopping a service that has not yet
     * called `startForeground` makes the platform crash the app ("Context.startForegroundService() did not then
     * call Service.startForeground()"), and no catch here can intercept that. The pending start's [start] call
     * re-reads [shouldRun] once it is in the foreground and stops the service itself.
     */
    fun sync(
        gate: StatusStartGate,
        shouldRun: () -> Boolean,
        start: () -> Unit,
        stop: () -> Unit,
        refused: (IllegalStateException) -> Unit,
    ) {
        try {
            if (shouldRun()) {
                start()
                gate.started()
            } else if (!gate.pending) {
                stop()
            }
        } catch (e: IllegalStateException) {
            refused(e)
        }
    }
}

/**
 * Whether a `startForegroundService` call has not reached the service's `onStartCommand` yet (audit R-06).
 *
 * Main thread only: [StatusStartDecision.sync] runs from `MainActivity`, the boot receiver's `onReceive` and the
 * Toolkit switch's intent handler, and `onStartCommand` runs on Main too, so one sync never interleaves with one
 * start and no lock is needed.
 *
 * Only `onStartCommand` settles a start; `onDestroy` deliberately does not. An instance stopped by an OFF is
 * destroyed on Main after a later ON may already have queued a new, still-pending start, so clearing there would
 * let the next OFF stop that start. The flag can stay true only when a start reached an instance that was already
 * in the foreground (the platform does not wait for `startForeground` then) and that instance stopped before
 * handling it. No instance is running in that state, so the stop it holds back is a no-op, and the next start's
 * `onStartCommand` clears it.
 */
internal class StatusStartGate {
    var pending: Boolean = false
        private set

    fun started() {
        pending = true
    }

    fun settled() {
        pending = false
    }
}
