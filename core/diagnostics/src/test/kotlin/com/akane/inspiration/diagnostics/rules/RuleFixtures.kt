package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.sensor.SensorCatalog
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.UidDelta

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN

internal const val ACCEL_HANDLE = 1
internal const val ACCEL_UNCAL_HANDLE = 2
internal const val GYRO_HANDLE = 4
internal const val OTHER_HANDLE = 9

internal val fakeCatalog = SensorCatalog { handle ->
    when (handle) {
        ACCEL_HANDLE, ACCEL_UNCAL_HANDLE -> SensorKind.ACCELEROMETER
        GYRO_HANDLE -> SensorKind.GYROSCOPE
        else -> SensorKind.OTHER
    }
}

private val clock = SnapshotClock(
    wallMs = 0,
    elapsedRealtimeMs = 0,
    uptimeMs = 0,
    bootCount = 1,
    statsStartMs = null,
    statsDurationMs = null,
)

internal fun uidDelta(
    uid: Int,
    totalMah: Double = 0.0,
    bgMah: Double? = null,
    cachedMah: Double? = null,
    fgsMah: Double? = null,
    fgActivityMs: Long = 0,
    valid: Boolean = true,
    packageName: String? = "pkg.$uid",
    wakelocks: List<UidWakelock> = emptyList(),
    alarms: List<UidAlarm> = emptyList(),
    sensors: List<UidSensor> = emptyList(),
) = UidDelta(
    uid = uid,
    packageName = packageName,
    packageChanged = false,
    valid = valid,
    totalMah = totalMah,
    fgMah = null,
    bgMah = bgMah,
    fgsMah = fgsMah,
    cachedMah = cachedMah,
    componentsMah = emptyMap(),
    cpuUserMs = 0,
    cpuSystemMs = 0,
    fgActivityMs = fgActivityMs,
    fgServiceMs = 0,
    partialWakelocks = wakelocks,
    wakeupAlarms = alarms,
    sensors = sensors,
)

internal fun wakelock(name: String, durationMs: Long, count: Long = 1) =
    UidWakelock(name = name, durationMs = durationMs, bgDurationMs = 0, count = count)

internal fun sensor(handle: Int, totalMs: Long) = UidSensor(handle = handle, totalMs = totalMs, bgMs = 0, count = 1)

internal fun sleepStats(screenOffMs: Long?, screenOffAwakeMs: Long?) = SleepStats(
    deepSleepMs = 0,
    screenOffDeepSleepMs = null,
    screenOffAwakeMs = screenOffAwakeMs,
    screenOffMs = screenOffMs,
    screenOnMs = null,
    deepSleepRatio = null,
)

internal fun diffOf(
    uids: List<UidDelta> = emptyList(),
    components: Map<String, Double> = emptyMap(),
    kernel: List<KernelWakelock> = emptyList(),
    sleep: SleepStats? = null,
    dischargedMah: Double? = null,
    batteryStats: Boolean = true,
    kernelCounters: Boolean = true,
    invalidComponents: Set<String> = emptySet(),
    invalidKernel: Set<String> = emptySet(),
    bins: List<Long> = emptyList(),
    scanningMs: Long? = null,
    radioActiveMs: Long? = null,
) = SnapshotDiff(
    ref = clock,
    now = clock,
    validity = DiffValidity(
        batteryStats = batteryStats,
        kernelCounters = kernelCounters,
        problems = emptyList(),
        invalidDeviceComponents = invalidComponents,
        invalidKernelWakelocks = invalidKernel,
    ),
    durationMs = 0,
    uids = uids,
    deviceComponentsMah = components,
    kernelWakelocks = kernel,
    sleep = sleep,
    levelDropPct = null,
    dischargedMah = dischargedMah,
    signalStrengthMs = bins,
    signalScanningMs = scanningMs,
    mobileRadioActiveMs = radioActiveMs,
)

internal fun metricsOf(screenOnMs: Long = 0, screenOffMs: Long = 2 * HOUR, unknownMs: Long = 0) = IntervalMetrics(
    fromWallMs = 0,
    toWallMs = screenOnMs + screenOffMs + unknownMs,
    screenOnMs = screenOnMs,
    screenOffMs = screenOffMs,
    unknownMs = unknownMs,
    chargingMs = 0,
    startLevel = null,
    endLevel = null,
    levelUsedPct = 0,
    levelDropScreenOn = 0,
    levelDropScreenOff = 0,
    screenOnPctPerHour = null,
    screenOffPctPerHour = null,
    discontinuities = emptyList(),
)

internal fun inputOf(
    diff: SnapshotDiff,
    metrics: IntervalMetrics? = null,
    perUidAvailable: Boolean = true,
) = DiagnosticInput(diff, metrics, perUidAvailable)
