package com.akane.inspiration.telemetry.dump

import com.akane.inspiration.telemetry.proto.AlarmManagerServiceDumpProto
import com.akane.inspiration.telemetry.proto.BatteryStatsServiceDumpProto
import com.akane.inspiration.telemetry.proto.SystemProto
import com.akane.inspiration.telemetry.proto.UidProto

object DumpParser {
    fun parseBatteryStats(bytes: ByteArray): DumpParse<DumpSnapshot> = try {
        val root = BatteryStatsServiceDumpProto.parseFrom(bytes)
        if (!root.hasBatterystats()) {
            DumpParse.Unavailable("batterystats dump is missing its root message")
        } else {
            val stats = root.batterystats
            when {
                stats.reportVersion < MIN_BATTERYSTATS_REPORT_VERSION -> {
                    DumpParse.Unavailable("unsupported batterystats report version ${stats.reportVersion}")
                }

                else -> DumpParse.Ok(stats.toSnapshot())
            }
        }
    } catch (_: Exception) {
        DumpParse.Unavailable("malformed batterystats dump")
    }

    fun parseAlarm(bytes: ByteArray): DumpParse<AlarmDump> = try {
        val dump = AlarmManagerServiceDumpProto.parseFrom(bytes)
        DumpParse.Ok(
            AlarmDump(
                filterStats = dump.alarmStatsList.flatMap { stat ->
                    stat.filtersList.map { filter ->
                        AlarmFilterStat(
                            uid = stat.broadcast.uid,
                            packageName = stat.broadcast.packageName,
                            tag = filter.tag,
                            count = filter.count.toLong(),
                            wakeupCount = filter.wakeupCount.toLong(),
                        )
                    }
                },
            ),
        )
    } catch (_: Exception) {
        DumpParse.Unavailable("malformed alarm dump")
    }

    private fun com.akane.inspiration.telemetry.proto.BatteryStatsProto.toSnapshot(): DumpSnapshot {
        val system = system
        val battery = system.battery
        val misc = system.misc
        val discharge = system.batteryDischarge
        return DumpSnapshot(
            reportVersion = reportVersion,
            system = SystemTimers(
                startClockTimeMs = battery.startClockTimeMs,
                totalRealtimeMs = battery.totalRealtimeMs,
                totalUptimeMs = battery.totalUptimeMs,
                batteryRealtimeMs = battery.batteryRealtimeMs,
                batteryUptimeMs = battery.batteryUptimeMs,
                screenOffRealtimeMs = battery.screenOffRealtimeMs,
                screenOffUptimeMs = battery.screenOffUptimeMs,
                screenDozeMs = battery.screenDozeDurationMs,
                screenOnMs = misc.screenOnDurationMs,
                interactiveMs = misc.interactiveDurationMs,
                deepDozeMs = misc.deepDozeEnabledDurationMs,
                deepDozeCount = misc.deepDozeCount.toLong(),
                lightDozeMs = misc.lightDozeEnabledDurationMs,
                dischargeTotalMah = discharge.totalMah.toDouble(),
                dischargeScreenOffMah = discharge.totalMahScreenOff.toDouble(),
                chargeTimeRemainingMs = system.knownChargeTimeRemainingMs(),
                dischargeTimeRemainingMs = system.knownDischargeTimeRemainingMs(),
                signalStrengthMs = system.phoneSignalStrengthList.toSignalBins(),
                signalScanningMs = if (system.hasSignalScanning()) system.signalScanning.durationMs else null,
                mobileRadioActiveMs = if (misc.hasMobileRadioActiveDurationMs()) misc.mobileRadioActiveDurationMs else null,
            ),
            kernelWakelocks = system.kernelWakelockList.toMergedKernelWakelocks(),
            wakeupReasons = system.wakeupReasonList.toMergedWakeupReasons(),
            uids = uidsList.map { uid ->
                UidDump(
                    uid = uid.uid,
                    partialWakelocks = uid.wakelocksList.map { wakelock ->
                        UidWakelock(
                            name = wakelock.name,
                            durationMs = wakelock.partial.durationMs,
                            bgDurationMs = wakelock.backgroundPartial.durationMs,
                            count = wakelock.partial.count,
                        )
                    },
                    wakeupAlarms = uid.wakeupAlarmList.sumByTag(),
                    cpuUserMs = uid.cpu.userDurationMs,
                    cpuSystemMs = uid.cpu.systemDurationMs,
                    fgActivityMs = uid.foregroundActivity.durationMs,
                    fgServiceMs = uid.foregroundService.durationMs,
                    sensors = uid.sensorsList.toMergedSensors(),
                )
            },
        )
    }

    private fun SystemProto.knownChargeTimeRemainingMs(): Long? =
        chargeTimeRemainingMs.takeIf {
            timeRemainingCase == SystemProto.TimeRemainingCase.CHARGE_TIME_REMAINING_MS && it >= 0
        }

    private fun SystemProto.knownDischargeTimeRemainingMs(): Long? =
        dischargeTimeRemainingMs.takeIf {
            timeRemainingCase == SystemProto.TimeRemainingCase.DISCHARGE_TIME_REMAINING_MS && it >= 0
        }

    // Bins are the telephony SignalStrength names 0..4 (none/unknown .. great). Empty when the dump has
    // no recognised bin; a bin missing alongside present ones counts as 0 ms. Unknown names are ignored.
    private fun List<SystemProto.PhoneSignalStrength>.toSignalBins(): List<Long> {
        val known = filter { it.name in 0 until SIGNAL_BIN_COUNT }
        if (known.isEmpty()) return emptyList()
        val totals = LongArray(SIGNAL_BIN_COUNT)
        known.forEach { totals[it.name] += it.total.durationMs }
        return totals.toList()
    }

    private fun List<UidProto.Sensor>.toMergedSensors(): List<UidSensor> =
        groupBy { it.id }.map { (handle, rows) ->
            UidSensor(
                handle = handle,
                totalMs = rows.sumOf { it.apportioned.durationMs },
                bgMs = rows.sumOf { it.background.durationMs },
                count = rows.sumOf { it.apportioned.count },
            )
        }

    private fun List<SystemProto.KernelWakelock>.toMergedKernelWakelocks(): List<KernelWakelock> =
        fold(linkedMapOf<String, KernelWakelock>()) { merged, wakelock ->
            merged.addWakelock(wakelock.name, wakelock.total.durationMs, wakelock.total.count)
            merged
        }.values.toList()

    private fun List<SystemProto.WakeupReason>.toMergedWakeupReasons(): List<KernelWakelock> =
        fold(linkedMapOf<String, KernelWakelock>()) { merged, reason ->
            merged.addWakelock(reason.name, reason.total.durationMs, reason.total.count)
            merged
        }.values.toList()

    private fun MutableMap<String, KernelWakelock>.addWakelock(
        rawName: String,
        durationMs: Long,
        count: Long,
    ) {
        val name = rawName.ifEmpty { UNNAMED_WAKELOCK }
        this[name] = this[name]?.let { previous ->
            previous.copy(
                durationMs = previous.durationMs + durationMs,
                count = previous.count + count,
            )
        } ?: KernelWakelock(name, durationMs, count)
    }

    private fun List<com.akane.inspiration.telemetry.proto.UidProto.WakeupAlarm>.sumByTag(): List<UidAlarm> =
        fold(linkedMapOf<String, Long>()) { sums, alarm ->
            sums[alarm.name] = (sums[alarm.name] ?: 0) + alarm.count
            sums
        }.map { (tag, count) -> UidAlarm(tag, count) }

    private const val MIN_BATTERYSTATS_REPORT_VERSION = 35
    private const val UNNAMED_WAKELOCK = "<unnamed>"
    private const val SIGNAL_BIN_COUNT = 5
}
