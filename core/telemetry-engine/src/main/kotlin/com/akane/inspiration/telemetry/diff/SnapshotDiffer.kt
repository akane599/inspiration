package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.UidDelta
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import kotlin.math.abs

object SnapshotDiffer {
    private const val SESSION_TOLERANCE_MS = 2_000L

    fun diff(ref: BatterySnapshot, now: BatterySnapshot): SnapshotDiff {
        val problems = mutableListOf<String>()
        val sessionSkew = hasSessionSkew(ref) || hasSessionSkew(now)
        if (sessionSkew) problems += "session_skew"

        val batteryStatsSessionValid = sameBatteryStatsSession(ref, now)
        val kernelCounterSessionValid = sameBootWithIncreasingElapsed(ref, now)
        val batteryStatsValid = batteryStatsSessionValid
        val kernelCountersValid = kernelCounterSessionValid
        if (!batteryStatsSessionValid) problems += "battery_stats_session"
        if (!kernelCounterSessionValid) problems += "kernel_clock_session"

        val uids = if (batteryStatsSessionValid) {
            diffUids(ref, now, problems)
        } else {
            emptyList()
        }
        val components = if (batteryStatsSessionValid) {
            diffComponents(ref.deviceComponentsMah, now.deviceComponentsMah, "device_component", problems)
        } else {
            emptyMap()
        }
        val kernelWakelocks = if (kernelCounterSessionValid) {
            diffKernelWakelocks(ref.kernelWakelocks, now.kernelWakelocks, problems)
        } else {
            emptyList()
        }

        return SnapshotDiff(
            ref = ref.clock,
            now = now.clock,
            validity = DiffValidity(
                batteryStats = batteryStatsValid,
                kernelCounters = kernelCountersValid,
                problems = problems,
                invalidDeviceComponents = components.filterValues { it < 0.0 }.keys,
                invalidKernelWakelocks = kernelWakelocks
                    .filter { it.durationMs < 0 || it.count < 0 }
                    .mapTo(sortedSetOf()) { it.name },
            ),
            durationMs = now.clock.elapsedRealtimeMs - ref.clock.elapsedRealtimeMs,
            uids = uids,
            deviceComponentsMah = components,
            kernelWakelocks = kernelWakelocks,
            sleep = sleepStats(ref, now, batteryStatsSessionValid && !sessionSkew, kernelCounterSessionValid),
            levelDropPct = ref.battery.levelPct?.let { previous ->
                now.battery.levelPct?.let { current -> previous - current }
            },
            dischargedMah = ref.battery.dischargedMah?.let { previous ->
                now.battery.dischargedMah?.minus(previous)
            },
        )
    }

    private fun sameBatteryStatsSession(ref: BatterySnapshot, now: BatterySnapshot): Boolean {
        val refStart = ref.clock.statsStartMs ?: return false
        val nowStart = now.clock.statsStartMs ?: return false
        val refDuration = ref.clock.statsDurationMs ?: return false
        val nowDuration = now.clock.statsDurationMs ?: return false
        return abs(nowStart - refStart) <= SESSION_TOLERANCE_MS && nowDuration >= refDuration
    }

    private fun sameBootWithIncreasingElapsed(ref: BatterySnapshot, now: BatterySnapshot): Boolean =
        ref.clock.bootCount == now.clock.bootCount && now.clock.elapsedRealtimeMs > ref.clock.elapsedRealtimeMs

    private fun hasSessionSkew(snapshot: BatterySnapshot): Boolean {
        val statsStart = snapshot.clock.statsStartMs ?: return false
        val dumpStart = snapshot.system?.startClockTimeMs ?: return false
        return abs(dumpStart - statsStart) > SESSION_TOLERANCE_MS
    }

    private fun diffUids(
        ref: BatterySnapshot,
        now: BatterySnapshot,
        problems: MutableList<String>,
    ): List<UidDelta> {
        val refByUid = ref.uids.associateBy(UidSnapshot::uid)
        val nowByUid = now.uids.associateBy(UidSnapshot::uid)
        return (refByUid.keys + nowByUid.keys).sorted().map { uid ->
            val previous = refByUid[uid]
            val current = nowByUid[uid]
            if (current == null) {
                UidDelta(
                    uid = uid,
                    packageName = previous?.packageName,
                    packageChanged = false,
                    valid = true,
                    totalMah = 0.0,
                    fgMah = previous?.fgMah?.let { 0.0 },
                    bgMah = previous?.bgMah?.let { 0.0 },
                    fgsMah = previous?.fgsMah?.let { 0.0 },
                    cachedMah = previous?.cachedMah?.let { 0.0 },
                    componentsMah = previous?.componentsMah?.mapValues { 0.0 }.orEmpty(),
                    cpuUserMs = 0,
                    cpuSystemMs = 0,
                    fgActivityMs = 0,
                    fgServiceMs = 0,
                    partialWakelocks = previous?.partialWakelocks?.map { it.copy(durationMs = 0, bgDurationMs = 0, count = 0) }.orEmpty(),
                    wakeupAlarms = previous?.wakeupAlarms?.map { it.copy(count = 0) }.orEmpty(),
                )
            } else {
                val packageChanged = uid >= FIRST_APPLICATION_UID && previous != null && previous.packageName != current.packageName
                val baseline = if (packageChanged) null else previous
                val delta = UidDelta(
                    uid = uid,
                    packageName = current.packageName,
                    packageChanged = packageChanged,
                    valid = true,
                    totalMah = current.totalMah - (baseline?.totalMah ?: 0.0),
                    fgMah = delta(current.fgMah, baseline?.fgMah),
                    bgMah = delta(current.bgMah, baseline?.bgMah),
                    fgsMah = delta(current.fgsMah, baseline?.fgsMah),
                    cachedMah = delta(current.cachedMah, baseline?.cachedMah),
                    componentsMah = diffComponents(baseline?.componentsMah.orEmpty(), current.componentsMah, "uid_component:$uid", problems),
                    cpuUserMs = current.cpuUserMs - (baseline?.cpuUserMs ?: 0),
                    cpuSystemMs = current.cpuSystemMs - (baseline?.cpuSystemMs ?: 0),
                    fgActivityMs = current.fgActivityMs - (baseline?.fgActivityMs ?: 0),
                    fgServiceMs = current.fgServiceMs - (baseline?.fgServiceMs ?: 0),
                    partialWakelocks = diffUidWakelocks(baseline?.partialWakelocks.orEmpty(), current.partialWakelocks, uid, problems),
                    wakeupAlarms = diffUidAlarms(baseline?.wakeupAlarms.orEmpty(), current.wakeupAlarms, uid, problems),
                )
                val valid = !hasNegative(delta)
                if (!valid) problems += "negative_uid:$uid"
                delta.copy(valid = valid)
            }
        }
    }

    private fun diffComponents(
        ref: Map<String, Double>,
        now: Map<String, Double>,
        label: String,
        problems: MutableList<String>,
    ): Map<String, Double> = (ref.keys + now.keys).sorted().associateWith { name ->
        val value = (now[name] ?: 0.0) - (ref[name] ?: 0.0)
        if (value < 0.0) problems += "negative_$label:$name"
        value
    }

    private fun diffKernelWakelocks(
        ref: List<KernelWakelock>,
        now: List<KernelWakelock>,
        problems: MutableList<String>,
    ): List<KernelWakelock> {
        val refByName = ref.groupBy(KernelWakelock::name).mapValues { (_, values) ->
            KernelWakelock(values.first().name, values.sumOf(KernelWakelock::durationMs), values.sumOf(KernelWakelock::count))
        }
        val nowByName = now.groupBy(KernelWakelock::name).mapValues { (_, values) ->
            KernelWakelock(values.first().name, values.sumOf(KernelWakelock::durationMs), values.sumOf(KernelWakelock::count))
        }
        return (refByName.keys + nowByName.keys).sorted().map { name ->
            val previous = refByName[name]
            val current = nowByName[name]
            KernelWakelock(
                name = name,
                durationMs = (current?.durationMs ?: 0) - (previous?.durationMs ?: 0),
                count = (current?.count ?: 0) - (previous?.count ?: 0),
            ).also { delta ->
                if (delta.durationMs < 0 || delta.count < 0) problems += "negative_kernel_wakelock:$name"
            }
        }
    }

    private fun diffUidWakelocks(
        ref: List<UidWakelock>,
        now: List<UidWakelock>,
        uid: Int,
        problems: MutableList<String>,
    ): List<UidWakelock> = (ref.map(UidWakelock::name) + now.map(UidWakelock::name)).distinct().sorted().map { name ->
        val previous = ref.filter { it.name == name }
        val current = now.filter { it.name == name }
        UidWakelock(
            name = name,
            durationMs = current.sumOf(UidWakelock::durationMs) - previous.sumOf(UidWakelock::durationMs),
            bgDurationMs = current.sumOf(UidWakelock::bgDurationMs) - previous.sumOf(UidWakelock::bgDurationMs),
            count = current.sumOf(UidWakelock::count) - previous.sumOf(UidWakelock::count),
        ).also { delta ->
            if (delta.durationMs < 0 || delta.bgDurationMs < 0 || delta.count < 0) {
                problems += "negative_uid_wakelock:$uid:$name"
            }
        }
    }

    private fun diffUidAlarms(
        ref: List<UidAlarm>,
        now: List<UidAlarm>,
        uid: Int,
        problems: MutableList<String>,
    ): List<UidAlarm> = (ref.map(UidAlarm::tag) + now.map(UidAlarm::tag)).distinct().sorted().map { tag ->
        val count = now.filter { it.tag == tag }.sumOf(UidAlarm::count) - ref.filter { it.tag == tag }.sumOf(UidAlarm::count)
        UidAlarm(tag, count).also { delta ->
            if (delta.count < 0) problems += "negative_uid_alarm:$uid:$tag"
        }
    }

    private fun sleepStats(
        ref: BatterySnapshot,
        now: BatterySnapshot,
        batteryStatsValid: Boolean,
        kernelCountersValid: Boolean,
    ): SleepStats? {
        if (batteryStatsValid && ref.system != null && now.system != null) {
            val batteryRealtime = now.system.batteryRealtimeMs - ref.system.batteryRealtimeMs
            val batteryUptime = now.system.batteryUptimeMs - ref.system.batteryUptimeMs
            val screenOffRealtime = now.system.screenOffRealtimeMs - ref.system.screenOffRealtimeMs
            val screenOffUptime = now.system.screenOffUptimeMs - ref.system.screenOffUptimeMs
            return SleepStats(
                deepSleepMs = batteryRealtime - batteryUptime,
                screenOffDeepSleepMs = screenOffRealtime - screenOffUptime,
                screenOffAwakeMs = screenOffUptime,
                screenOffMs = screenOffRealtime,
                screenOnMs = now.system.screenOnMs - ref.system.screenOnMs,
                deepSleepRatio = if (screenOffRealtime > 0) {
                    (screenOffRealtime - screenOffUptime).toDouble() / screenOffRealtime
                } else {
                    null
                },
            )
        }
        if (kernelCountersValid) {
            return SleepStats(
                deepSleepMs = (now.clock.elapsedRealtimeMs - ref.clock.elapsedRealtimeMs) -
                    (now.clock.uptimeMs - ref.clock.uptimeMs),
                screenOffDeepSleepMs = null,
                screenOffAwakeMs = null,
                screenOffMs = null,
                screenOnMs = null,
                deepSleepRatio = null,
            )
        }
        return null
    }

    private fun hasNegative(delta: UidDelta): Boolean =
        delta.totalMah < 0.0 ||
            listOf(delta.fgMah, delta.bgMah, delta.fgsMah, delta.cachedMah).any { it != null && it < 0.0 } ||
            delta.componentsMah.values.any { it < 0.0 } ||
            delta.cpuUserMs < 0 ||
            delta.cpuSystemMs < 0 ||
            delta.fgActivityMs < 0 ||
            delta.fgServiceMs < 0 ||
            delta.partialWakelocks.any { it.durationMs < 0 || it.bgDurationMs < 0 || it.count < 0 } ||
            delta.wakeupAlarms.any { it.count < 0 }

    private fun delta(now: Double?, ref: Double?): Double? = now?.minus(ref ?: 0.0)

    private const val FIRST_APPLICATION_UID = 10_000
}
