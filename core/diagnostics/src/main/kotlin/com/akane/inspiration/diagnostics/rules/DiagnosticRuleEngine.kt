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
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.UidDelta
import com.akane.inspiration.telemetry.snapshot.isApplicationUid

/**
 * The shortest screen-off time the screen-off rules evaluate. A SCREEN_ON broadcast binds the telemetry service for
 * a FULL end anchor only after a screen-off period this long (audit R-03).
 */
const val MIN_SCREEN_OFF_MS = 30 * 60_000L

/** Rule 2 fires AMBER above this awake share of screen-off time (TEMPLATE.md §4: 8 %). */
const val AWAKE_AMBER_RATIO = 0.08

/** Rule 2 fires RED above this awake share of screen-off time. */
const val AWAKE_RED_RATIO = 0.20

/** Rule 2's [DiagnosticReport.notEvaluable] reason: the period has screen-off figures, but less than [MIN_SCREEN_OFF_MS]. */
const val NO_SCREEN_OFF_TIME = "no_screen_off_time"

/**
 * Rule 2's [DiagnosticReport.notEvaluable] reason: the period has no screen-off figures at all, because the
 * battery-stats dump does not compare across it (a stats reset, or an end without the dump, such as a LIGHT
 * snapshot). Never worded as a short screen-off (audit B-04).
 */
const val NO_SCREEN_OFF_FIGURES = "no_screen_off_figures"

/** Rule 2's [DiagnosticReport.notEvaluable] reason: screen-off time with no awake time recorded for it. */
const val NO_AWAKE_TIME = "no_awake_time"

/**
 * Night Vampire's and Sensor Leak's [DiagnosticReport.notEvaluable] reason: the interval has application UIDs but
 * none could be checked (owner not proven at both ends, usage list truncated, counters reset or package changed), so
 * the rule saw no app at all. Never an all-clear (audit B-03). The excluded UIDs stay counted in
 * [DiagnosticReport.skippedInvalidUids].
 */
const val APPS_UNPROVEN = "apps_unproven"

/**
 * Sensor Leak's [DiagnosticReport.notEvaluable] reason: an app held a non-GPS sensor, but the sensor catalog could
 * not be built ([SensorCatalog.available] is false), so whether it was an accelerometer or gyroscope is unknown.
 * Never an all-clear (SQ-44).
 */
const val SENSOR_CATALOG_UNAVAILABLE = "sensor_catalog_unavailable"

/**
 * The four deterministic rules of docs/plans/phase-3.md F4. Every rule either returns insights
 * (possibly none: evaluable but not firing) or exactly one [DiagnosticReport.notEvaluable] reason.
 */
class DiagnosticRuleEngine(
    private val sensors: SensorCatalog,
) {
    fun evaluate(input: DiagnosticInput): DiagnosticReport {
        val diff = input.diff
        val validUids = diff.uids.filter { it.valid && !it.packageChanged }
        val screenOffOnly = isScreenOffOnly(input)
        // Rules that scan only valid app UIDs would find nothing and read as "no anomaly" (audit B-03).
        val appsUnproven = diff.uids.any { isApplicationUid(it.uid) } && validUids.none { isApplicationUid(it.uid) }

        val outcomes =
            listOf(
                RuleId.NIGHT_VAMPIRE to nightVampire(input, validUids, appsUnproven),
                RuleId.AWAKE_VIOLATION to awakeViolation(diff, validUids, screenOffOnly),
                RuleId.MODEM_DRAIN to modemDrain(diff),
                RuleId.SENSOR_LEAK to sensorLeak(input, validUids, appsUnproven, screenOffOnly),
            )
        val insights = outcomes.flatMap { (_, outcome) -> (outcome as? Outcome.Insights)?.items.orEmpty() }
        val notEvaluable =
            outcomes
                .mapNotNull { (rule, outcome) ->
                    (outcome as? Outcome.NotEvaluable)?.let { rule to it.reason }
                }.toMap()
        return DiagnosticReport(
            insights = insights.sortedWith(insightOrder),
            notEvaluable = notEvaluable,
            skippedInvalidUids = diff.uids.count { !it.valid || it.packageChanged },
        )
    }

    /**
     * Screen-off only: no unknown time, at least [MIN_SCREEN_OFF_MS] screen-off, and no more screen-on time than
     * the resolver's [ReferenceResolver.CAPTURE_SLACK_MS] (audit R-03). A screen-off period ends at the screen-on
     * broadcast's own capture, which lands after the history SCREEN_ON, so a real night carries up to that much
     * screen-on lag; any longer screen-on time is a mixed interval. The lag stays in the per-UID deltas (a few
     * seconds against thresholds of minutes), and Sensor Leak scales its motion threshold by
     * [com.akane.inspiration.telemetry.interval.IntervalMetrics.screenOffMs], not by the interval length.
     */
    private fun isScreenOffOnly(input: DiagnosticInput): Boolean {
        val metrics = input.metrics ?: return false
        return metrics.screenOnMs <= ReferenceResolver.CAPTURE_SLACK_MS && metrics.unknownMs == 0L &&
            metrics.screenOffMs >= MIN_SCREEN_OFF_MS
    }

    /** The gates shared by the rules that read batterystats; rules without per-UID inputs keep the default. */
    private fun gate(
        diff: SnapshotDiff,
        perUidAvailable: Boolean = true,
    ): Outcome.NotEvaluable? =
        when {
            !diff.validity.batteryStats -> Outcome.NotEvaluable("battery_stats_invalid")
            !perUidAvailable -> Outcome.NotEvaluable("per_uid_unavailable")
            else -> null
        }

    // R1 Night Vampire: an app burning power in the background (foreground services excluded).
    private fun nightVampire(
        input: DiagnosticInput,
        validUids: List<UidDelta>,
        appsUnproven: Boolean,
    ): Outcome {
        gate(input.diff, input.perUidAvailable)?.let { return it }
        if (!input.diff.validity.dumpCounters) return Outcome.NotEvaluable("source_absent")
        if (appsUnproven) return Outcome.NotEvaluable(APPS_UNPROVEN)
        val minMah = maxOf(MIN_VAMPIRE_MAH, VAMPIRE_DISCHARGE_FRACTION * (input.diff.dischargedMah ?: 0.0))
        val items =
            validUids.mapNotNull { uid ->
                if (!isApplicationUid(uid.uid) || (uid.bgMah == null && uid.cachedMah == null)) return@mapNotNull null
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
    private fun awakeViolation(
        diff: SnapshotDiff,
        validUids: List<UidDelta>,
        screenOffOnly: Boolean,
    ): Outcome {
        val sleep = diff.sleep ?: return Outcome.NotEvaluable(NO_SCREEN_OFF_FIGURES)
        val screenOffMs = sleep.screenOffMs ?: return Outcome.NotEvaluable(NO_SCREEN_OFF_FIGURES)
        if (screenOffMs < MIN_SCREEN_OFF_MS) return Outcome.NotEvaluable(NO_SCREEN_OFF_TIME)
        val awakeMs = sleep.screenOffAwakeMs ?: return Outcome.NotEvaluable(NO_AWAKE_TIME)
        val ratio = awakeMs.toDouble() / screenOffMs
        val severity =
            when {
                ratio > AWAKE_RED_RATIO -> Severity.RED
                ratio > AWAKE_AMBER_RATIO -> Severity.AMBER
                else -> return Outcome.Insights(emptyList())
            }
        val scope = if (screenOffOnly) CulpritScope.SCREEN_OFF_ONLY else CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS
        return Outcome.Insights(listOf(AwakeViolation(severity, ratio, screenOffMs, scope, culprits(diff, validUids))))
    }

    /** The top three contributors of TEMPLATE.md §4 Rule 2, one per kind, which Sleep Lab's culprit card lists. */
    private fun culprits(
        diff: SnapshotDiff,
        validUids: List<UidDelta>,
    ): List<Culprit> {
        val partial =
            validUids.takeIf { diff.validity.dumpCounters }.orEmpty().flatMap { uid ->
                uid.partialWakelocks.map { Culprit(EntryKind.PARTIAL, it.name, uid.uid, it.durationMs, it.count) }
            }
        val kernel =
            if (diff.validity.kernelWakelocks) {
                diff.kernelWakelocks
                    .filter { it.name !in diff.validity.invalidKernelWakelocks }
                    .map { Culprit(EntryKind.KERNEL, it.name, null, it.durationMs, it.count) }
            } else {
                emptyList()
            }
        val alarms =
            validUids.takeIf { diff.validity.dumpCounters }.orEmpty().flatMap { uid ->
                uid.wakeupAlarms.map { Culprit(EntryKind.ALARM, it.tag, uid.uid, null, it.count) }
            }
        return listOfNotNull(
            top(partial) { it.durationMs ?: 0L },
            top(kernel) { it.durationMs ?: 0L },
            top(alarms) { it.count ?: 0L },
        )
    }

    /** Largest positive [magnitude]; ties resolve by name, then uid, so the result is deterministic. */
    private fun top(
        candidates: List<Culprit>,
        magnitude: (Culprit) -> Long,
    ): Culprit? =
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
    private fun sensorLeak(
        input: DiagnosticInput,
        validUids: List<UidDelta>,
        appsUnproven: Boolean,
        screenOffOnly: Boolean,
    ): Outcome {
        gate(input.diff, input.perUidAvailable)?.let { return it }
        if (!input.diff.validity.dumpCounters) return Outcome.NotEvaluable("source_absent")
        val metrics = input.metrics
        if (!screenOffOnly || metrics == null) return Outcome.NotEvaluable("not_screen_off_interval")
        if (input.diff.uids.none { it.sensors.isNotEmpty() }) return Outcome.NotEvaluable("source_absent")
        if (appsUnproven) return Outcome.NotEvaluable(APPS_UNPROVEN)

        val motionThresholdMs = maxOf(MOTION_MIN_MS.toDouble(), MOTION_SCREEN_OFF_FRACTION * metrics.screenOffMs)
        val apps = validUids.filter { isApplicationUid(it.uid) }
        val items =
            apps.flatMap { uid ->
                val leaks = mutableListOf<SensorLeak>()

                fun leak(
                    severity: Severity,
                    kind: SensorKind,
                    durationMs: Long,
                ) {
                    leaks += SensorLeak(severity, uid.uid, uid.packageName, kind, durationMs)
                }
                val gpsMs = uid.sensors.filter { it.handle == GPS_SENSOR_HANDLE }.maxOfOrNull { it.totalMs } ?: 0L
                if (gpsMs >= GPS_MIN_MS) leak(Severity.RED, SensorKind.GPS, gpsMs)
                // The longest handle of a kind stands for it: calibrated and uncalibrated variants overlap in time.
                val motionMs =
                    uid.sensors
                        .filter { it.handle != GPS_SENSOR_HANDLE }
                        .groupBy({ sensors.kindOf(it.handle) }, { it.totalMs })
                        .mapValues { (_, durations) -> durations.max() }
                for (kind in listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE)) {
                    val durationMs = motionMs[kind] ?: continue
                    if (durationMs >= motionThresholdMs) leak(Severity.AMBER, kind, durationMs)
                }
                leaks
            }
        // Without the catalog every non-GPS handle reads as OTHER, so no motion check could fire: an empty result
        // would be a false all-clear. A GPS leak needs no catalog and is a real finding, so it is still reported.
        val motionUnchecked = !sensors.available && apps.any { uid -> uid.sensors.any { it.handle != GPS_SENSOR_HANDLE } }
        if (items.isEmpty() && motionUnchecked) return Outcome.NotEvaluable(SENSOR_CATALOG_UNAVAILABLE)
        return Outcome.Insights(items)
    }

    private sealed interface Outcome {
        data class Insights(
            val items: List<Insight>,
        ) : Outcome

        data class NotEvaluable(
            val reason: String,
        ) : Outcome
    }

    private companion object {
        const val MIN_VAMPIRE_MAH = 2.0
        const val VAMPIRE_DISCHARGE_FRACTION = 0.01
        const val VAMPIRE_BG_SHARE = 0.60
        const val VAMPIRE_MAX_FG_ACTIVITY_MS = 60_000L

        const val COMPONENT_SCREEN = "SCREEN"
        const val COMPONENT_MOBILE_RADIO = "MOBILE_RADIO"
        const val MODEM_SCREEN_RATIO = 0.35
        const val MODEM_MIN_MAH = 5.0

        const val GPS_MIN_MS = 5 * 60_000L
        const val MOTION_MIN_MS = 10 * 60_000L
        const val MOTION_SCREEN_OFF_FRACTION = 0.10
    }
}
