package com.akane.inspiration.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The process-wide "the store took in battery history" tick: one new value after every capture that ingested it,
 * whichever entry point ran that capture (the app-open refresh, a marker, the charge job's pass). The analysis session
 * recomputes on it and the visible screens re-read the store on it, so none of them depends on who wrote.
 *
 * Nothing here polls: a tick comes only from a capture that already ran. The value is the wall time of the tick, or
 * one past the previous value when the wall clock has not moved past it (same millisecond, or a clock set back), so
 * every tick is a new value and a [StateFlow] never conflates it away. It is independent of the refresh debounce.
 */
class StoreTicks(
    private val nowWallMs: () -> Long,
) {
    private val mutableLastWallMs = MutableStateFlow<Long?>(null)

    /** Null until the first tick in this process. */
    val lastWallMs: StateFlow<Long?> = mutableLastWallMs.asStateFlow()

    fun tick() {
        mutableLastWallMs.update { last ->
            val now = nowWallMs()
            if (last != null && now <= last) last + 1 else now
        }
    }
}
