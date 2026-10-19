package com.akane.inspiration.health

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.TelemetrySample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the Health screen needs from the app, pinned by docs/plans/phase-5.md ("Feature dependencies").
 * `:app` implements it in AppContainer; the screen never sees the container, the raw gateway or an Intent.
 * Changing a member needs a story-log DECISION.
 */
interface HealthDependencies {
    val clocks: Clocks

    /**
     * A strictly increasing tick, normally the wall time, of the last capture in this process that ingested battery
     * history, whoever ran it: a refresh (this screen's, the app-open capture its refresh joined, or the re-run when
     * Shizuku became ready), a marker, or the charge job's pass (after its charge-session and health analysis); null
     * before the first. After the wall clock went back it is one past the previous value instead. A new value means
     * newly stored history, samples and health records, which a read made earlier does not show. It is not the
     * refresh debounce's clock.
     */
    val lastRefreshWallMs: StateFlow<Long?>

    suspend fun refresh(): RefreshResult

    /** Kernel battery nodes converted to mAh; null when the kernel read is unavailable. */
    suspend fun kernelBattery(): KernelCapacitySnapshot?

    suspend fun healthSamples(): List<HealthSample>

    suspend fun chargeSessions(): List<ChargeSessionRecord>

    suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample>

    /** Cold flow: it reads only while collected. The caller starts and stops it with screen visibility (H13). */
    fun liveReadings(periodMs: Long): Flow<LiveReading>

    /** Keeps the Shizuku service bound; close it when the screen is hidden. */
    fun holdBinding(): AutoCloseable

    suspend fun historyBetween(
        from: Long,
        to: Long,
    ): List<HistoryEvent>
}
