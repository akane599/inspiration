package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.Culprit
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.DiagnosticReport
import com.akane.inspiration.diagnostics.model.Insight
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.diagnostics.model.insightOrder
import com.akane.inspiration.diagnostics.sensor.GPS_SENSOR_HANDLE
import com.akane.inspiration.diagnostics.sensor.SensorCatalog
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.UidDelta

/**
 * The four deterministic rules of docs/plans/phase-3.md F4. Every rule either returns insights
 * (possibly none: evaluable but not firing) or exactly one [DiagnosticReport.notEvaluable] reason.
 */
class DiagnosticRuleEngine(private val sensors: SensorCatalog) {

    fun evaluate(input: DiagnosticInput): DiagnosticReport {
        val diff = input.diff
        val validUids = diff.uids.filter { it.valid && !it.packageChanged }
        val screenOffOnly = isScreenOffOnly(input)

        val outcomes = listOf(
            RuleId.NIGHT_VAMPIRE to nightVampire(input, validUids),
            RuleId.AWAKE_VIOLATION to awakeViolation(diff, validUids, screenOffOnly),
            RuleId.MODEM_DRAIN to modemDrain(diff),
            RuleId.SENSOR_LEAK to sensorLeak(input, validUids, screenOffOnly),
        )
        val insights = outcomes.flatMap { (_, outcome) -> (outcome as? Outcome.Insights)?.items.orEmpty() }
        val notEvaluable = outcomes.mapNotNull { (rule, outcome) ->
            (outcome as? Outcome.NotEvaluable)?.let { rule to it.reason }
        }.toMap()
        return DiagnosticReport(
            insights = insights.sortedWith(insightOrder),
            notEvaluable = notEvaluable,
            skippedInvalidUids = diff.uids.count { !it.valid || it.packageChanged },
        )
    }

    private fun isScreenOffOnly(input: DiagnosticInput): Boolean {
        val metrics = input.metrics ?: return false
        return metrics.screenOnMs == 0L && metrics.unknownMs == 0L && metrics.screenOffMs >= MIN_SCREEN_OFF_MS
    }

    /** The gates shared by the rules that read batterystats; rules without per-UID inputs keep the default. */
    private fun gate(diff: SnapshotDiff, perUidAvailable: Boolean = true): Outcome.NotEvaluable? = when {
        !diff.validity.batteryStats -> Outcome.NotEvaluable("battery_stats_invalid")
        !perUidAvailable -> Outcome.NotEvaluable("per_uid_unavailable")
        else -> null
    }

    // R1 Night Vampire: an app burning power in the background (foreground services excluded).
    private fun nightVampire(input: DiagnosticInput, validUids: List<UidDelta>): Outcome {
        gate(input.diff, input.perUidAvailable)?.let { return it }
        if (!input.diff.validity.dumpCounters) return Outcome.NotEvaluable("source_absent")
        val minMah = maxOf(MIN_VAMPIRE_MAH, VAMPIRE_DISCHARGE_FRACTION * (input.diff.dischargedMah ?: 0.0))
        val items = validUids.mapNotNull { uid ->
            if (uid.uid < FIRST_APP_UID || (uid.bgMah == null && uid.cachedMah == null)) return@mapNotNull null
            if (uid.totalMah < minMah) return@mapNotNull null
            val bgShare = ((uid.bgMah ?: 0.0) + (uid.cachedMah ?: 0.0)) / uid.totalMah
            if (bgShare > VAMPIRE_BG_SHARE && uid.fgActivityMs < VAMPIRE_MAX_FG_ACTIVITY_MS) {
                NightVampire(Severity.RED, uid.uid, uid.packageName, uid.totalMah, bgShare, uid.fgActivityMs)
            } else {
                null
            }
        }
        return Outcome.Insights(items)
    }

    // R2 Awake Violation: too much screen-off time spent awake, with culprits for context.
    private fun awakeViolation(diff: SnapshotDiff, validUids: List<UidDelta>, screenOffOnly: Boolean): Outcome {
        val sleep = diff.sleep ?: return Outcome.NotEvaluable("no_screen_off_time")
        val screenOffMs = sleep.screenOffMs
        if (screenOffMs == null || screenOffMs < MIN_SCREEN_OFF_MS) return Outcome.NotEvaluable("no_screen_off_time")
        val awakeMs = sleep.screenOffAwakeMs ?: return Outcome.NotEvaluable("no_awake_time")
        val ratio = awakeMs.toDouble() / screenOffMs
        val severity = when {
            ratio > AWAKE_RED_RATIO -> Severity.RED
            ratio > AWAKE_AMBER_RATIO -> Severity.AMBER
            else -> return Outcome.Insights(emptyList())
        }
        val scope = if (screenOffOnly) CulpritScope.SCREEN_OFF_ONLY else CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS
        return Outcome.Insights(listOf(AwakeViolation(severity, ratio, screenOffMs, scope, culprits(diff, validUids))))
    }

    private fun culprits(diff: SnapshotDiff, validUids: List<UidDelta>): List<Culprit> {
        val partial = validUids.takeIf { diff.validity.dumpCounters }.orEmpty().flatMap { uid ->
            uid.partialWakelocks.map { Culprit(EntryKind.PARTIAL, it.name, uid.uid, it.durationMs, it.count) }
        }
        val kernel = if (diff.validity.kernelWakelocks) {
            diff.kernelWakelocks
                .filter { it.name !in diff.validity.invalidKernelWakelocks }
                .map { Culprit(EntryKind.KERNEL, it.name, null, it.durationMs, it.count) }
        } else {
            emptyList()
        }
        val alarms = validUids.takeIf { diff.validity.dumpCounters }.orEmpty().flatMap { uid ->
            uid.wakeupAlarms.map { Culprit(EntryKind.ALARM, it.tag, uid.uid, null, it.count) }
        }
        return listOfNotNull(
            top(partial) { it.durationMs ?: 0L },
            top(kernel) { it.durationMs ?: 0L },
            top(alarms) { it.count ?: 0L },
        )
    }

    /** Largest positive [magnitude]; ties resolve by name, then uid, so the result is deterministic. */
    private fun top(candidates: List<Culprit>, magnitude: (Culprit) -> Long): Culprit? =
        candidates.filter { magnitude(it) > 0L }.minWithOrNull(
            compareByDescending<Culprit> { magnitude(it) }
                .thenBy { it.name }
                .thenBy(nullsLast<Int>()) { it.uid },
        )

    // R3 Modem Drain: mobile radio power out of proportion to the screen's.
    private fun modemDrain(diff: SnapshotDiff): Outcome {
        gate(diff)?.let { return it }
        val invalid = diff.validity.invalidDeviceComponents
        if (COMPONENT_MOBILE_RADIO in invalid || COMPONENT_SCREEN in invalid) return Outcome.NotEvaluable("component_invalid")
        val screenMah = diff.deviceComponentsMah[COMPONENT_SCREEN]
        if (screenMah == null || screenMah <= 0.0) return Outcome.NotEvaluable("screen_power_zero")
        // An absent component means 0 mAh (zero components are dropped when captured).
        val radioMah = diff.deviceComponentsMah[COMPONENT_MOBILE_RADIO] ?: 0.0
        if (radioMah <= MODEM_SCREEN_RATIO * screenMah || radioMah < MODEM_MIN_MAH) return Outcome.Insights(emptyList())
        val bins = diff.signalStrengthMs
        val binSum = bins.sum()
        return Outcome.Insights(
            listOf(
                ModemDrain(
                    severity = Severity.AMBER,
                    mobileRadioMah = radioMah,
                    screenMah = screenMah,
                    signalAvailable = bins.isNotEmpty(),
                    signalBinPct = if (binSum > 0L) bins.map { 100.0 * it / binSum } else emptyList(),
                    scanningMs = diff.signalScanningMs,
                    radioActiveMs = diff.mobileRadioActiveMs,
                ),
            ),
        )
    }

    // R4 Sensor Leak: a sensor held for a long time across a screen-off-only interval.
    private fun sensorLeak(input: DiagnosticInput, validUids: List<UidDelta>, screenOffOnly: Boolean): Outcome {
        gate(input.diff, input.perUidAvailable)?.let { return it }
        if (!input.diff.validity.dumpCounters) return Outcome.NotEvaluable("source_absent")
        val metrics = input.metrics
        if (!screenOffOnly || metrics == null) return Outcome.NotEvaluable("not_screen_off_interval")
        if (input.diff.uids.none { it.sensors.isNotEmpty() }) return Outcome.NotEvaluable("source_absent")

        val motionThresholdMs = maxOf(MOTION_MIN_MS.toDouble(), MOTION_SCREEN_OFF_FRACTION * metrics.screenOffMs)
        val items = validUids.filter { it.uid >= FIRST_APP_UID }.flatMap { uid ->
            val leaks = mutableListOf<SensorLeak>()
            fun leak(severity: Severity, kind: SensorKind, durationMs: Long) {
                leaks += SensorLeak(severity, uid.uid, uid.packageName, kind, durationMs)
            }
            val gpsMs = uid.sensors.filter { it.handle == GPS_SENSOR_HANDLE }.maxOfOrNull { it.totalMs } ?: 0L
            if (gpsMs >= GPS_MIN_MS) leak(Severity.RED, SensorKind.GPS, gpsMs)
            // The longest handle of a kind stands for it: calibrated and uncalibrated variants overlap in time.
            val motionMs = uid.sensors.filter { it.handle != GPS_SENSOR_HANDLE }
                .groupBy({ sensors.kindOf(it.handle) }, { it.totalMs })
                .mapValues { (_, durations) -> durations.max() }
            for (kind in listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE)) {
                val durationMs = motionMs[kind] ?: continue
                if (durationMs >= motionThresholdMs) leak(Severity.AMBER, kind, durationMs)
            }
            leaks
        }
        return Outcome.Insights(items)
    }

    private sealed interface Outcome {
        data class Insights(val items: List<Insight>) : Outcome
        data class NotEvaluable(val reason: String) : Outcome
    }

    private companion object {
        const val FIRST_APP_UID = 10_000
        const val MIN_SCREEN_OFF_MS = 30 * 60_000L

        const val MIN_VAMPIRE_MAH = 2.0
        const val VAMPIRE_DISCHARGE_FRACTION = 0.01
        const val VAMPIRE_BG_SHARE = 0.60
        const val VAMPIRE_MAX_FG_ACTIVITY_MS = 60_000L

        const val AWAKE_AMBER_RATIO = 0.08
        const val AWAKE_RED_RATIO = 0.20

        const val COMPONENT_SCREEN = "SCREEN"
        const val COMPONENT_MOBILE_RADIO = "MOBILE_RADIO"
        const val MODEM_SCREEN_RATIO = 0.35
        const val MODEM_MIN_MAH = 5.0

        const val GPS_MIN_MS = 5 * 60_000L
        const val MOTION_MIN_MS = 10 * 60_000L
        const val MOTION_SCREEN_OFF_FRACTION = 0.10
    }
}
