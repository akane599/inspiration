package com.akane.inspiration.telemetry.status

import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind

/** A light snapshot carrying only what [UnplugSummaryCalculator] reads: clocks, level, plug state and trigger. */
internal fun statusSnapshot(
    wallMs: Long,
    elapsedMs: Long = wallMs,
    uptimeMs: Long = elapsedMs,
    bootCount: Int = 1,
    level: Int? = 80,
    plugged: Boolean? = false,
    trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
) = BatterySnapshot(
    id = 0L,
    kind = SnapshotKind.LIGHT,
    trigger = trigger,
    clock = SnapshotClock(wallMs, elapsedMs, uptimeMs, bootCount, statsStartMs = null, statsDurationMs = null),
    battery = BatteryState(level, plugged, null, null, null, null, null, null),
    system = null,
    uids = emptyList(),
    deviceComponentsMah = emptyMap(),
    kernelWakelocks = emptyList(),
    wakeupReasons = emptyList(),
    cpuFreq = emptyList(),
    availability = emptyMap(),
    truncatedUids = 0,
)
