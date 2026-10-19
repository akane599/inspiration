package com.akane.inspiration.telemetry.app

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and answers its value, or null when it threw. Containers answer with values, but a throwing
 * data source (a Room error such as a full disk, a closed database) must degrade a card or end an action
 * with a notice, not crash the screen. Cancellation is rethrown, so the caller's scope still stops.
 */
suspend fun <T> attempt(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
