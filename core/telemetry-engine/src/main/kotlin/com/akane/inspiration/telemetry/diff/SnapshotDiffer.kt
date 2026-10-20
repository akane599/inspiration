package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CpuFreqDelta
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.DiffValidity
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.UidDelta
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.snapshot.isApplicationUid

object SnapshotDiffer {
    fun diff(
        ref: BatterySnapshot,
        now: BatterySnapshot,
    ): SnapshotDiff {
        val problems = mutableListOf<String>()
        val sessionSkew = hasSessionSkew(ref) || hasSessionSkew(now)
        if (sessionSkew) problems += "session_skew"

        val batteryStatsSessionValid = sameBatteryStatsSession(ref, now)
        val kernelCounterSessionValid = sameBootWithIncreasingElapsed(ref, now)
        val batteryStatsValid = batteryStatsSessionValid
        val kernelCountersValid = kernelCounterSessionValid
        val dumpCountersValid =
            batteryStatsSessionValid && !sessionSkew &&
                ref.sourceAvailable("dump") && now.sourceAvailable("dump")
        val kernelWakelocksValid = kernelCounterSessionValid && dumpCountersValid
        val cpuFreqValid = kernelCounterSessionValid && ref.sourceAvailable("cpu") && now.sourceAvailable("cpu")
        val alarmDumpValid =
            batteryStatsSessionValid && kernelCounterSessionValid &&
                ref.sourceAvailable("alarm") && now.sourceAvailable("alarm")
        if (!batteryStatsSessionValid) problems += "battery_stats_session"
        if (!kernelCounterSessionValid) problems += "kernel_clock_session"

        val observedUids =
            if (batteryStatsSessionValid) {
                diffUids(ref, now, problems, dumpCountersValid, alarmDumpValid)
            } else {
                emptyList()
            }
        // A dump/alarm union row can hide a parcel-dropped usage baseline behind zero energy.
        // Without per-UID source provenance, neither endpoint's UID energy is safely comparable.
        val incompleteUidUsage = ref.truncatedUids > 0 || now.truncatedUids > 0
        if (incompleteUidUsage) problems += "truncated_uid_usage"
        val uids = if (incompleteUidUsage) observedUids.map { it.copy(valid = false) } else observedUids
        val incompleteDeviceComponents = ref.hasTruncatedUidComponents() || now.hasTruncatedUidComponents()
        if (incompleteDeviceComponents) problems += "truncated_device_components"
        val components =
            if (batteryStatsSessionValid && !incompleteDeviceComponents) {
                diffComponents(ref.deviceComponentsMah, now.deviceComponentsMah, "device_component", problems)
            } else {
                emptyMap()
            }
        val kernelWakelocks =
            if (kernelWakelocksValid) {
                diffKernelWakelocks(ref.kernelWakelocks, now.kernelWakelocks, problems)
            } else {
                emptyList()
            }

        val refSystem = ref.system.takeIf { dumpCountersValid }
        val nowSystem = now.system.takeIf { dumpCountersValid }
        val signalStrength =
            diffSignalBins(
                refSystem?.signalStrengthMs.orEmpty(),
                nowSystem?.signalStrengthMs.orEmpty(),
                problems,
            )
        val signalScanning =
            diffTimer(
                refSystem?.signalScanningMs,
                nowSystem?.signalScanningMs,
                "negative_signal_scanning",
                problems,
            )
        val mobileRadioActive =
            diffTimer(
                refSystem?.mobileRadioActiveMs,
                nowSystem?.mobileRadioActiveMs,
                "negative_mobile_radio_active",
                problems,
            )

        return SnapshotDiff(
            ref = ref.clock,
            now = now.clock,
            validity =
                DiffValidity(
                    batteryStats = batteryStatsValid,
                    kernelCounters = kernelCountersValid,
                    dumpCounters = dumpCountersValid,
                    kernelWakelocks = kernelWakelocksValid,
                    cpuFreq = cpuFreqValid,
                    alarmDumpCounters = alarmDumpValid,
                    problems = problems,
                    invalidDeviceComponents = components.filterValues { it < 0.0 }.keys,
                    invalidKernelWakelocks =
                        kernelWakelocks
                            .filter { it.durationMs < 0 || it.count < 0 }
                            .mapTo(sortedSetOf()) { it.name },
                ),
            durationMs = now.clock.elapsedRealtimeMs - ref.clock.elapsedRealtimeMs,
            uids = uids,
            deviceComponentsMah = components,
            kernelWakelocks = kernelWakelocks,
            sleep = sleepStats(ref, now, dumpCountersValid, kernelCounterSessionValid),
            dischargedMah =
                ref.battery.dischargedMah?.let { previous ->
                    now.battery.dischargedMah?.minus(previous)
                },
            signalStrengthMs = signalStrength,
            signalScanningMs = signalScanning,
            mobileRadioActiveMs = mobileRadioActive,
            dischargeMah = if (dumpCountersValid) dischargeDelta(ref, now) { it.dischargeTotalMah } else null,
            dischargeScreenOffMah = if (dumpCountersValid) dischargeDelta(ref, now) { it.dischargeScreenOffMah } else null,
            cpuFreqDelta = if (cpuFreqValid) diffCpuFreq(ref.cpuFreq, now.cpuFreq, problems) else emptyList(),
        )
    }

    // Empty maps predate explicit source availability (and are used by legacy fixtures).
    // Once metadata exists, a missing source is unknown rather than an observed zero.
    private fun BatterySnapshot.sourceAvailable(source: String): Boolean = availability[source]?.available ?: availability.isEmpty()

    private fun BatterySnapshot.hasTruncatedUidComponents(): Boolean =
        truncatedUids > 0 && availability["usage"]?.reason == "device_from_uid_sum"

    /** Null unless both sides carry system timers from the same battery-stats session ([BatteryStatsSession.same]). */
    private fun dischargeDelta(
        ref: BatterySnapshot,
        now: BatterySnapshot,
        field: (SystemTimers) -> Double,
    ): Double? {
        if (!sameBatteryStatsSession(ref, now)) return null
        val previous = ref.system ?: return null
        val current = now.system ?: return null
        return field(current) - field(previous)
    }

    private fun diffCpuFreq(
        ref: List<CpuFreqTime>,
        now: List<CpuFreqTime>,
        problems: MutableList<String>,
    ): List<CpuFreqDelta> {
        val refTimes = ref.groupBy { it.policy to it.freqKhz }.mapValues { (_, values) -> values.sumOf(CpuFreqTime::timeMs) }
        val nowTimes = now.groupBy { it.policy to it.freqKhz }.mapValues { (_, values) -> values.sumOf(CpuFreqTime::timeMs) }
        return (refTimes.keys + nowTimes.keys)
            .sortedWith(compareBy({ it.first }, { it.second }))
            .mapNotNull { key ->
                val time = (nowTimes[key] ?: 0L) - (refTimes[key] ?: 0L)
                if (time < 0) {
                    problems += "negative_cpu_freq:${key.first}:${key.second}"
                    null
                } else {
                    CpuFreqDelta(key.first, key.second, time)
                }
            }
    }

    private fun sameBatteryStatsSession(
        ref: BatterySnapshot,
        now: BatterySnapshot,
    ): Boolean = BatteryStatsSession.same(ref.clock, now.clock)

    private fun sameBootWithIncreasingElapsed(
        ref: BatterySnapshot,
        now: BatterySnapshot,
    ): Boolean = ref.clock.bootCount == now.clock.bootCount && now.clock.elapsedRealtimeMs > ref.clock.elapsedRealtimeMs

    /**
     * Each scope's reader epoch and package-change sequence prove that one capture's opening and
     * closing reads bracket a consistent package state; they are not compared across snapshots.
     * Across snapshots only the user namespace and boot must match: per-UID owner/install
     * fingerprints then decide whether an application UID is still the same incarnation.
     */
    private fun sameUidNamespace(
        ref: BatterySnapshot,
        now: BatterySnapshot,
    ): Boolean {
        val refScope = ref.uidIdentityScope?.takeIf { it.isKnown() } ?: return false
        val nowScope = now.uidIdentityScope?.takeIf { it.isKnown() } ?: return false
        return refScope.userId == nowScope.userId &&
            refScope.userSerial == nowScope.userSerial &&
            ref.clock.bootCount >= 0 &&
            sameBootWithIncreasingElapsed(ref, now)
    }

    private fun hasSessionSkew(snapshot: BatterySnapshot): Boolean {
        val statsStart = snapshot.clock.statsStartMs ?: return false
        val dumpStart = snapshot.system?.startClockTimeMs ?: return false
        return !BatteryStatsSession.sameStart(dumpStart, statsStart)
    }

    private fun diffUids(
        ref: BatterySnapshot,
        now: BatterySnapshot,
        problems: MutableList<String>,
        dumpCountersValid: Boolean,
        alarmDumpValid: Boolean,
    ): List<UidDelta> {
        // Missing observations are not zero baselines. Keep independently measured usage energy,
        // but omit incomparable dump counters and expose their availability on DiffValidity.
        fun UidSnapshot.comparable() =
            copy(
                cpuUserMs = if (dumpCountersValid) cpuUserMs else 0L,
                cpuSystemMs = if (dumpCountersValid) cpuSystemMs else 0L,
                fgActivityMs = if (dumpCountersValid) fgActivityMs else 0L,
                fgServiceMs = if (dumpCountersValid) fgServiceMs else 0L,
                partialWakelocks = if (dumpCountersValid) partialWakelocks else emptyList(),
                wakeupAlarms = if (dumpCountersValid) wakeupAlarms else emptyList(),
                sensors = if (dumpCountersValid) sensors else emptyList(),
                alarmDumpWakeups = if (alarmDumpValid) alarmDumpWakeups else emptyList(),
            )
        val refByUid = ref.uids.map { it.comparable() }.associateBy(UidSnapshot::uid)
        val nowByUid = now.uids.map { it.comparable() }.associateBy(UidSnapshot::uid)
        val sameNamespace = sameUidNamespace(ref, now)
        // The capturer stores a known scope only for an untruncated usage read, which lists every UID
        // with accrued usage. A UID absent from such a reference therefore had nothing to subtract.
        val refMembershipComplete =
            ref.kind == SnapshotKind.FULL && ref.sourceAvailable("usage") &&
                ref.truncatedUids == 0 && ref.uidIdentityScope?.isKnown() == true
        return (refByUid.keys + nowByUid.keys).sorted().map { uid ->
            val previous = refByUid[uid]
            val current = nowByUid[uid]
            val applicationUid = isApplicationUid(uid)
            val comparableScope =
                sameNamespace && ref.uidIdentityScope?.covers(uid) == true && now.uidIdentityScope?.covers(uid) == true
            val previousFingerprint = UidOwnerFingerprint.known(previous?.ownerInstallFingerprint)
            val currentFingerprint = UidOwnerFingerprint.known(current?.ownerInstallFingerprint)
            val knownOwners = comparableScope && previousFingerprint != null && currentFingerprint != null
            val packageChanged = applicationUid && knownOwners && previousFingerprint != currentFingerprint
            val provenZeroBaseline =
                previous == null && comparableScope && refMembershipComplete && currentFingerprint != null
            val identityValid =
                !applicationUid || (knownOwners && previousFingerprint == currentFingerprint) || provenZeroBaseline
            if (!identityValid) problems += if (packageChanged) "uid_identity_changed:$uid" else "uid_identity_unknown:$uid"
            if (current == null) {
                UidDelta(
                    uid = uid,
                    packageName = previous?.packageName,
                    packageChanged = false,
                    valid = identityValid,
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
                    sensors = previous?.sensors?.map { it.copy(totalMs = 0, bgMs = 0, count = 0) }.orEmpty(),
                    alarmDumpWakeups = previous?.alarmDumpWakeups?.map { it.copy(count = 0) }.orEmpty(),
                )
            } else {
                // The highest-drain package is only a display hint. Never reset to a zero baseline
                // on hint rotation or unknown identity: application output requires proof at both endpoints,
                // or a complete reference membership that proves the absent UID started from zero.
                val baseline = previous
                val delta =
                    UidDelta(
                        uid = uid,
                        packageName = current.packageName,
                        packageChanged = packageChanged,
                        valid = true,
                        totalMah = current.totalMah - (baseline?.totalMah ?: 0.0),
                        fgMah = delta(current.fgMah, baseline?.fgMah),
                        bgMah = delta(current.bgMah, baseline?.bgMah),
                        fgsMah = delta(current.fgsMah, baseline?.fgsMah),
                        cachedMah = delta(current.cachedMah, baseline?.cachedMah),
                        componentsMah =
                            diffComponents(
                                baseline?.componentsMah.orEmpty(),
                                current.componentsMah,
                                "uid_component:$uid",
                                problems,
                            ),
                        cpuUserMs = current.cpuUserMs - (baseline?.cpuUserMs ?: 0),
                        cpuSystemMs = current.cpuSystemMs - (baseline?.cpuSystemMs ?: 0),
                        fgActivityMs = current.fgActivityMs - (baseline?.fgActivityMs ?: 0),
                        fgServiceMs = current.fgServiceMs - (baseline?.fgServiceMs ?: 0),
                        partialWakelocks = diffUidWakelocks(baseline?.partialWakelocks.orEmpty(), current.partialWakelocks, uid, problems),
                        wakeupAlarms =
                            diffUidAlarms(
                                baseline?.wakeupAlarms.orEmpty(),
                                current.wakeupAlarms,
                                uid,
                                problems,
                                "negative_uid_alarm",
                            ),
                        sensors = diffUidSensors(baseline?.sensors.orEmpty(), current.sensors, uid, problems),
                        alarmDumpWakeups =
                            diffUidAlarms(
                                baseline?.alarmDumpWakeups.orEmpty(),
                                current.alarmDumpWakeups,
                                uid,
                                problems,
                                "negative_uid_alarm_dump",
                            ),
                    )
                val countersValid = !hasNegative(delta)
                if (!countersValid) problems += "negative_uid:$uid"
                delta.copy(valid = identityValid && countersValid)
            }
        }
    }

    private fun diffComponents(
        ref: Map<String, Double>,
        now: Map<String, Double>,
        label: String,
        problems: MutableList<String>,
    ): Map<String, Double> =
        (ref.keys + now.keys).sorted().associateWith { name ->
            val value = (now[name] ?: 0.0) - (ref[name] ?: 0.0)
            if (value < 0.0) problems += "negative_$label:$name"
            value
        }

    private fun diffKernelWakelocks(
        ref: List<KernelWakelock>,
        now: List<KernelWakelock>,
        problems: MutableList<String>,
    ): List<KernelWakelock> {
        val refByName =
            ref.groupBy(KernelWakelock::name).mapValues { (_, values) ->
                KernelWakelock(values.first().name, values.sumOf(KernelWakelock::durationMs), values.sumOf(KernelWakelock::count))
            }
        val nowByName =
            now.groupBy(KernelWakelock::name).mapValues { (_, values) ->
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

    // The per-UID name diffs group each side once (linear) instead of filtering both lists per name.
    private fun diffUidWakelocks(
        ref: List<UidWakelock>,
        now: List<UidWakelock>,
        uid: Int,
        problems: MutableList<String>,
    ): List<UidWakelock> {
        val refByName = ref.groupBy(UidWakelock::name)
        val nowByName = now.groupBy(UidWakelock::name)
        return (refByName.keys + nowByName.keys).sorted().map { name ->
            val previous = refByName[name].orEmpty()
            val current = nowByName[name].orEmpty()
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
    }

    private fun diffUidAlarms(
        ref: List<UidAlarm>,
        now: List<UidAlarm>,
        uid: Int,
        problems: MutableList<String>,
        negativeProblem: String,
    ): List<UidAlarm> {
        val refByTag = ref.groupBy(UidAlarm::tag)
        val nowByTag = now.groupBy(UidAlarm::tag)
        return (refByTag.keys + nowByTag.keys).sorted().map { tag ->
            val count = nowByTag[tag].orEmpty().sumOf(UidAlarm::count) - refByTag[tag].orEmpty().sumOf(UidAlarm::count)
            UidAlarm(tag, count).also { delta ->
                if (delta.count < 0) problems += "$negativeProblem:$uid:$tag"
            }
        }
    }

    private fun diffUidSensors(
        ref: List<UidSensor>,
        now: List<UidSensor>,
        uid: Int,
        problems: MutableList<String>,
    ): List<UidSensor> {
        val refByHandle = ref.groupBy(UidSensor::handle)
        val nowByHandle = now.groupBy(UidSensor::handle)
        return (refByHandle.keys + nowByHandle.keys).sorted().map { handle ->
            val previous = refByHandle[handle].orEmpty()
            val current = nowByHandle[handle].orEmpty()
            UidSensor(
                handle = handle,
                totalMs = current.sumOf(UidSensor::totalMs) - previous.sumOf(UidSensor::totalMs),
                bgMs = current.sumOf(UidSensor::bgMs) - previous.sumOf(UidSensor::bgMs),
                count = current.sumOf(UidSensor::count) - previous.sumOf(UidSensor::count),
            ).also { delta ->
                if (delta.totalMs < 0 || delta.bgMs < 0 || delta.count < 0) {
                    problems += "negative_uid_sensor:$uid:$handle"
                }
            }
        }
    }

    private fun diffSignalBins(
        ref: List<Long>,
        now: List<Long>,
        problems: MutableList<String>,
    ): List<Long> {
        if (ref.isEmpty() || now.isEmpty() || ref.size != now.size) return emptyList()
        val bins = now.zip(ref) { current, previous -> current - previous }
        val negative = bins.indices.filter { bins[it] < 0 }
        negative.forEach { problems += "negative_signal_strength:$it" }
        return if (negative.isEmpty()) bins else emptyList()
    }

    private fun diffTimer(
        ref: Long?,
        now: Long?,
        problem: String,
        problems: MutableList<String>,
    ): Long? {
        if (ref == null || now == null) return null
        val value = now - ref
        if (value < 0) {
            problems += problem
            return null
        }
        return value
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
                deepSleepRatio =
                    if (screenOffRealtime > 0) {
                        (screenOffRealtime - screenOffUptime).toDouble() / screenOffRealtime
                    } else {
                        null
                    },
            )
        }
        if (kernelCountersValid) {
            return SleepStats(
                deepSleepMs =
                    (now.clock.elapsedRealtimeMs - ref.clock.elapsedRealtimeMs) -
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
            delta.wakeupAlarms.any { it.count < 0 } ||
            delta.alarmDumpWakeups.any { it.count < 0 } ||
            delta.sensors.any { it.totalMs < 0 || it.bgMs < 0 || it.count < 0 }

    private fun delta(
        now: Double?,
        ref: Double?,
    ): Double? = now?.minus(ref ?: 0.0)
}
