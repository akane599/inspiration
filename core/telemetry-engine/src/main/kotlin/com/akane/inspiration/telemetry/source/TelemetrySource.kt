package com.akane.inspiration.telemetry.source

import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.ClusterFreqTime
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.dump.AlarmDump
import com.akane.inspiration.telemetry.dump.DumpParse
import com.akane.inspiration.telemetry.dump.DumpSnapshot
import com.akane.inspiration.telemetry.dump.PowerDump
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock

interface TelemetrySource {
    suspend fun full(trigger: CaptureTrigger, historyCursor: Pair<Long, Long>?): FullRead?

    fun light(): LightRead

    suspend fun kernelReading(): KernelRead? = null
}

data class FullRead(
    val light: LightRead,
    val usage: BatteryUsageSnapshot?,
    val batteryStats: DumpParse<DumpSnapshot>,
    val power: DumpParse<PowerDump>,
    val alarm: DumpParse<AlarmDump>,
    val kernel: KernelBatterySnapshot?,
    val cpuTimes: List<ClusterFreqTime>,
    val history: List<BatteryHistorySnapshot>,
)

data class LightRead(
    val clock: SnapshotClock,
    val levelPct: Int?,
    val plugged: Boolean?,
    val status: String?,
    val temperatureDeciC: Int?,
    val voltageMv: Int?,
    val currentMa: Int?,
    val chargeCounterUah: Long?,
    val thermalStatus: Int? = null,
)

data class KernelRead(
    val kernel: KernelBatterySnapshot?,
    val calibrationInput: CalibrationInput?,
    val light: LightRead,
)
