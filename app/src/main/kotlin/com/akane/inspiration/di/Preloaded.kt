package com.akane.inspiration.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * A value computed once on [scope] (give it an IO scope) starting at construction, for a synchronous
 * accessor that must never do the work itself. [get] returns the value at once when the load has finished;
 * if it is still running (a cold start straight onto a screen that needs it) the caller waits for that
 * load rather than repeating it, so [load] never runs on the caller's thread.
 */
class Preloaded<T>(scope: CoroutineScope, load: () -> T) {
    private val deferred = scope.async { load() }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun get(): T = if (deferred.isCompleted) deferred.getCompleted() else runBlocking { deferred.await() }
}
