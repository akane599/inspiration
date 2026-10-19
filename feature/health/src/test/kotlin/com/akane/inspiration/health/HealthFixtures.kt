package com.akane.inspiration.health

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.app.KernelCapacitySnapshot
import com.akane.inspiration.telemetry.app.RefreshResult
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.TelemetrySample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

internal const val HOUR_MS = 3_600_000L
internal const val MIN_MS = 60_000L
internal const val SEC_MS = 1_000L
internal const val NOW_MS = 1_000L * HOUR_MS

/** [HealthViewModel.factory] with the shaping on the test's dispatcher, so `runCurrent` runs it. */
internal fun testFactory(
    deps: HealthDependencies,
    shaping: CoroutineDispatcher,
): ViewModelProvider.Factory = viewModelFactory { initializer { HealthViewModel(deps, shaping) } }

internal class FakeClocks(
    var wall: Long = NOW_MS,
) : Clocks {
    override fun wallMs() = wall

    override fun elapsedRealtimeMs() = wall

    override fun uptimeMs() = wall

    override fun bootCount() = 1
}

/** A scripted app side: counts what the ViewModel asks for so tests can prove what it stopped asking for. */
internal class FakeHealthDeps(
    override val clocks: FakeClocks = FakeClocks(),
) : HealthDependencies {
    var kernel: KernelCapacitySnapshot? = kernelSnapshot()
    var kernelThrows = false
    var healthSamples: List<HealthSample>? = emptyList()
    var sessions: List<ChargeSessionRecord>? = emptyList()
    var history: List<HistoryEvent>? = emptyList()
    var samples: List<TelemetrySample>? = emptyList()
    var refreshResult: RefreshResult = RefreshResult.Refreshed(NOW_MS)
    var refreshCalls = 0

    /** The coordinator's tick: a Refreshed result records its time, as the coordinator does before returning it. */
    override val lastRefreshWallMs = MutableStateFlow<Long?>(null)

    /** Stored-history reads, i.e. how many times the screen loaded. */
    var historyReads = 0

    /** While set, refresh() suspends until it completes, so a test can act during a running refresh. */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** Readings the live flow produced, i.e. how many reads were requested of the device. */
    var readingsRequested = 0
    var liveCollections = 0
    var requestedPeriodMs = 0L
    var bindingsOpened = 0
    var bindingsClosed = 0

    override suspend fun refresh(): RefreshResult {
        refreshCalls++
        refreshGate?.await()
        return refreshResult.also { if (it is RefreshResult.Refreshed) lastRefreshWallMs.value = it.wallMs }
    }

    override suspend fun kernelBattery(): KernelCapacitySnapshot? {
        if (kernelThrows) error("kernel read failed")
        return kernel
    }

    override suspend fun healthSamples(): List<HealthSample> = healthSamples ?: error("health samples unavailable")

    override suspend fun chargeSessions(): List<ChargeSessionRecord> = sessions ?: error("sessions unavailable")

    override suspend fun samplesBetween(
        from: Long,
        to: Long,
    ): List<TelemetrySample> = samples ?: error("samples unavailable")

    override suspend fun historyBetween(
        from: Long,
        to: Long,
    ): List<HistoryEvent> {
        historyReads++
        return history ?: error("history unavailable")
    }

    override fun liveReadings(periodMs: Long): Flow<LiveReading> =
        flow {
            liveCollections++
            requestedPeriodMs = periodMs
            while (true) {
                readingsRequested++
                emit(liveReading(wallMs = clocks.wall + readingsRequested * periodMs, levelPct = 40 + readingsRequested))
                delay(periodMs)
            }
        }

    override fun holdBinding(): AutoCloseable {
        bindingsOpened++
        return AutoCloseable { bindingsClosed++ }
    }
}

internal fun kernelSnapshot(
    chargeFullMah: Double? = 4_300.0,
    designMah: Double? = 5_000.0,
    cycles: Int? = 212,
) = KernelCapacitySnapshot(
    chargeCounterMah = 2_000.0,
    chargeFullMah = chargeFullMah,
    designMah = designMah,
    cycleCount = cycles,
    levelPct = 50,
    temperatureDeciC = 310,
)

internal fun liveReading(
    wallMs: Long = NOW_MS,
    levelPct: Int? = 50,
    plugged: Boolean? = true,
    tempDeciC: Int? = 310,
    voltageMv: Int? = 4_000,
    currentMa: Int? = 2_000,
    thermalStatus: Int? = 0,
) = LiveReading(
    wallMs = wallMs,
    elapsedRealtimeMs = wallMs,
    bootCount = 1,
    currentMa = currentMa,
    voltageMv = voltageMv,
    watts = currentMa?.let { it * (voltageMv ?: 0) / 1_000_000.0 },
    temperatureDeciC = tempDeciC,
    levelPct = levelPct,
    plugged = plugged,
    thermalStatus = thermalStatus,
)

internal fun sample(
    wallMs: Long,
    level: Int? = 50,
    plugged: Boolean? = true,
    tempDeciC: Int? = 310,
    voltageMv: Int? = 4_000,
    currentMa: Int? = 2_000,
    thermalStatus: Int? = 0,
) = TelemetrySample(
    wallMs = wallMs,
    elapsedRealtimeMs = wallMs,
    bootCount = 1,
    currentMa = currentMa,
    voltageMv = voltageMv,
    watts = currentMa?.let { it * (voltageMv ?: 0) / 1_000_000.0 },
    temperatureDeciC = tempDeciC,
    levelPct = level,
    plugged = plugged,
    thermalStatus = thermalStatus,
)

internal fun event(
    wallMs: Long,
    kind: Int = HistoryKind.LEVEL,
    level: Int = 50,
    tempDeciC: Int = 310,
    voltageMv: Int = 4_000,
) = HistoryEvent(
    historyTimeMs = wallMs,
    wallMs = wallMs,
    kind = kind,
    levelPct = level,
    chargeUah = 0,
    temperatureDeciC = tempDeciC,
    voltageMv = voltageMv,
    plugType = 1,
)

internal fun healthSample(
    wallMs: Long,
    fcc: Double = 4_300.0,
    design: Double = 5_000.0,
    cycles: Int = 100,
    measured: Double? = null,
) = HealthSample(wallMs = wallMs, fccMah = fcc, designMah = design, cycleCount = cycles, measuredMah = measured)

internal fun sessionRecord(
    endWallMs: Long,
    estimateMah: Double? = 4_200.0,
    accepted: Boolean = true,
    independent: Boolean = true,
) = ChargeSessionRecord(
    statsStartMs = 0,
    startHistoryTimeMs = endWallMs - HOUR_MS,
    startWallMs = endWallMs - HOUR_MS,
    endWallMs = endWallMs,
    startPct = 10,
    endPct = 90,
    deltaChargeUah = null,
    integratedMah = null,
    maxTemperatureDeciC = null,
    estimateMah = estimateMah,
    independent = independent,
    accepted = accepted,
    rejectReason = null,
)

/** A [CurvePoint] with sensible defaults; override only what a test is about. */
internal fun point(
    wallMs: Long,
    level: Int = 50,
    tempC: Double? = 30.0,
    voltageMv: Int? = 4_000,
    watts: Double? = 8.0,
    currentMa: Int? = 2_000,
    thermal: Int? = 0,
    fromHistory: Boolean = false,
) = CurvePoint(wallMs, level, watts, currentMa, voltageMv, tempC, thermal, fromHistory)
