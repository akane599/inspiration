package com.akane.inspiration.telemetry.live

import com.akane.inspiration.telemetry.calibration.PowerCalibrator
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.source.KernelRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

class LiveTelemetry(
    private val source: TelemetrySource,
    private val calibrator: PowerCalibrator,
    private val clocks: Clocks,
    private val store: SnapshotStore? = null,
) {
    fun readings(periodMs: Long = DEFAULT_PERIOD_MS): Flow<LiveReading> = flow {
        require(periodMs > 0) { "periodMs must be positive" }
        while (currentCoroutineContext().isActive) {
            source.kernelReading()?.toReading()?.also { reading ->
                emit(reading)
                if (reading.plugged == true) store?.insertSamples(listOf(reading))
            }
            delay(periodMs)
        }
    }

    private fun KernelRead.toReading(): LiveReading {
        val power = calibrationInput?.let { input ->
            calibrator.toReading(input, calibrator.calibrate(listOf(input)))
        }
        return LiveReading(
            wallMs = light.clock.wallMs,
            elapsedRealtimeMs = light.clock.elapsedRealtimeMs,
            bootCount = light.clock.bootCount,
            currentMa = power?.currentMa?.roundToInt()
                ?: KernelUnits.normalizeCurrentMa(light.currentMa, light.chargingOrNull()),
            voltageMv = power?.voltageV?.times(1_000)?.roundToInt() ?: light.voltageMv,
            watts = power?.watts,
            temperatureDeciC = kernel?.temperatureDeciC ?: light.temperatureDeciC,
            levelPct = kernel?.capacityPercent ?: light.levelPct,
            plugged = light.plugged,
            chargeCounterUah = light.chargeCounterUah,
            thermalStatus = light.thermalStatus,
        )
    }

    // The battery's own direction decides the current sign; plugged alone cannot (a plugged battery can drain).
    private fun LightRead.chargingOrNull(): Boolean? = when (status) {
        "charging", "full" -> true
        "discharging" -> false
        else -> null
    }

    private companion object {
        const val DEFAULT_PERIOD_MS = 1_500L
    }
}
