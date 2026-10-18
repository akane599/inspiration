package com.akane.inspiration.di

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide scope. A [SupervisorJob] keeps siblings alive, but without a handler an uncaught
 * exception still reaches the thread's default handler and kills the process (a Room error during
 * reconcile on every Shizuku Ready, say), so it is logged instead.
 */
fun newApplicationScope(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    onUncaught: (Throwable) -> Unit = { Log.e("ApplicationScope", "uncaught in applicationScope", it) },
): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> onUncaught(error) })
