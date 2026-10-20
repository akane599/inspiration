package com.akane.inspiration.telemetry.live

import com.akane.inspiration.telemetry.app.attempt
import com.akane.inspiration.telemetry.calibration.CalibrationEvidence
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.calibration.CalibrationStore
import com.akane.inspiration.telemetry.calibration.PowerCalibration
import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.source.KernelRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class LiveTelemetry(
    private val source: TelemetrySource,
    private val calibrator: PowerCalibrator,
    private val clocks: Clocks,
    private val store: SnapshotStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calibrationStore: CalibrationStore? = null,
) {
    /** Cold; each tick reads sysfs and the Shizuku service, so the whole loop runs on [ioDispatcher], never main. */
    fun readings(periodMs: Long = DEFAULT_PERIOD_MS): Flow<LiveReading> =
        flow {
            require(periodMs > 0) { "periodMs must be positive" }
            while (currentCoroutineContext().isActive) {
                source.kernelReading()?.toReading()?.also { reading ->
                    emit(reading)
                    if (reading.plugged == true) {
                        attempt { store?.insertSamples(listOf(reading)) }
                    }
                }
                delay(periodMs)
            }
        }.flowOn(ioDispatcher)

    /**
     * Unit, sign convention and cell layout are device constants: they are learned across every reading, so one
     * near-zero or skewed sample never re-decides them, and what is settled goes to [calibrationStore] for the next
     * process. The store is first read on the first tick (on [ioDispatcher]); a store failure only costs the head
     * start. Collectors may run concurrently, so all of it is guarded by [evidenceLock].
     */
    private val evidenceLock = Any()
    private var evidence: CalibrationEvidence? = null
    private var savedStart: CalibrationStart? = null

    private fun calibrate(input: CalibrationInput): PowerCalibration =
        synchronized(evidenceLock) {
            val current =
                evidence ?: run {
                    val restored = calibrationStore?.let { runCatching { it.load() }.getOrNull() } ?: CalibrationStart()
                    savedStart = restored
                    CalibrationEvidence(restored).also { evidence = it }
                }
            current.add(input)
            val start = current.start()
            if (start != savedStart) {
                // A save that throws is tried again on the next tick. One that fails later, on disk, goes unnoticed
                // (SharedPreferences.apply() reports nothing) and only costs the next process its head start.
                val target = calibrationStore
                val saved = target == null || runCatching { target.save(start) }.isSuccess
                if (saved) savedStart = start
            }
            current.calibration()
        }

    private fun KernelRead.toReading(): LiveReading {
        val power =
            calibrationInput?.let { input ->
                // The charge counter cross-checks the current's unit, which settles it even while plugged in.
                val counted = input.copy(chargeCounterUah = light.chargeCounterUah, elapsedRealtimeMs = light.clock.elapsedRealtimeMs)
                calibrator.toReading(input, calibrate(counted))
            }
        // A current whose unit nothing supports yet may be 1000x off: it is neither shown nor stored, never a wrong
        // number in the Health curves and roll-ups. Voltage, level and temperature do not depend on it.
        val measured = power?.takeUnless { it.currentUnitAssumed }
        return LiveReading(
            wallMs = light.clock.wallMs,
            elapsedRealtimeMs = light.clock.elapsedRealtimeMs,
            bootCount = light.clock.bootCount,
            currentMa =
                if (power == null) {
                    KernelUnits.normalizeCurrentMa(light.currentMa, light.chargingOrNull())
                } else {
                    measured?.currentMa?.roundToInt()
                },
            voltageMv = power?.voltageV?.times(1_000)?.roundToInt() ?: light.voltageMv,
            watts = measured?.watts,
            temperatureDeciC = kernel?.temperatureDeciC ?: light.temperatureDeciC,
            levelPct = kernel?.capacityPercent ?: light.levelPct,
            plugged = light.plugged,
            chargeCounterUah = light.chargeCounterUah,
            thermalStatus = light.thermalStatus,
            status = light.status,
        )
    }

    // The battery's own direction decides the current sign; plugged alone cannot (a plugged battery can drain), and
    // neither can "full", which may still supply part of the load.
    private fun LightRead.chargingOrNull(): Boolean? =
        when (status) {
            "charging" -> true
            "discharging" -> false
            else -> null
        }

    private companion object {
        const val DEFAULT_PERIOD_MS = 1_500L
    }
}
