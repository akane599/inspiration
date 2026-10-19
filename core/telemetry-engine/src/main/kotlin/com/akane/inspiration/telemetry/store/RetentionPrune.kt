package com.akane.inspiration.telemetry.store

import com.akane.inspiration.telemetry.common.Clocks

/**
 * Prunes the retention at a "now" that a forward wall-clock jump cannot move ahead of this boot's monotonic clock.
 *
 * `wallMs - elapsedRealtimeMs` stays constant within a boot until the clock is set, so this boot's smallest stored
 * offset plus the current elapsedRealtime is "now" in the boot's earliest clock frame. The prune uses the earlier
 * of that and the wall clock:
 * - Forward jump (manual date change, bad NITZ/NTP value): rows are aged by elapsedRealtime, so what this boot
 *   recorded days ago survives while rows really older than the retention still go. Skipping the prune would only
 *   postpone the loss to the next capture, whose snapshot already carries the jumped clock.
 * - Backward jump: the wall clock is the earlier one, so rows only live longer until it catches up.
 * - After a reboot (no snapshot of this boot yet) or with an unknown boot count (0), elapsedRealtime gives no
 *   reference across the gap, so the wall clock is trusted as given.
 *
 * A snapshot taken before the clock was corrected forward only delays pruning, until it ages out by elapsedRealtime.
 */
suspend fun SnapshotStore.pruneRetention(clocks: Clocks) {
    val wallMs = clocks.wallMs()
    val elapsedMs = clocks.elapsedRealtimeMs()
    val bootCount = clocks.bootCount()
    val bootFrameNowMs = if (bootCount > 0) minClockOffsetMs(bootCount)?.let { it + elapsedMs } else null
    prune(if (bootFrameNowMs == null) wallMs else minOf(wallMs, bootFrameNowMs))
}
