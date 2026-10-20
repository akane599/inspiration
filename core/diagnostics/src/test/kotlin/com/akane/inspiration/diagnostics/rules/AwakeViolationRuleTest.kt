package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.Culprit
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.telemetry.diff.ReferenceResolver.Companion.CAPTURE_SLACK_MS
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwakeViolationRuleTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    // 30 min of screen-off: 8 % is 144 000 ms and 20 % is 360 000 ms.
    private val offMs = 30 * MIN

    private fun awake(awakeMs: Long, offMs: Long? = this.offMs) =
        engine.evaluate(inputOf(diffOf(sleep = sleepStats(offMs, awakeMs)))).insights.filterIsInstance<AwakeViolation>()

    @Test
    fun ratioBoundaries() {
        assertTrue(awake(144_000).isEmpty())
        val amber = awake(144_001).single()
        assertEquals(Severity.AMBER, amber.severity)
        assertEquals(144_001.0 / offMs, amber.awakeRatio, 1e-12)
        assertEquals(offMs, amber.screenOffMs)
        assertEquals(Severity.AMBER, awake(360_000).single().severity)
        assertEquals(Severity.RED, awake(360_001).single().severity)
    }

    @Test
    fun screenOffTimeBoundaryAtThirtyMinutes() {
        val justUnder = engine.evaluate(inputOf(diffOf(sleep = sleepStats(30 * MIN - 1, 30 * MIN - 1))))
        assertEquals("no_screen_off_time", justUnder.notEvaluable[RuleId.AWAKE_VIOLATION])
        assertTrue(justUnder.insights.isEmpty())
        assertEquals(1, awake(awakeMs = 30 * MIN, offMs = 30 * MIN).size)
    }

    @Test
    fun notEvaluableReasons() {
        // Missing figures (no comparable battery-stats dump) are not a short screen-off (audit B-04).
        val noSleep = engine.evaluate(inputOf(diffOf(sleep = null)))
        assertEquals("no_screen_off_figures", noSleep.notEvaluable[RuleId.AWAKE_VIOLATION])
        val noOffTime = engine.evaluate(inputOf(diffOf(sleep = sleepStats(null, 1_000_000))))
        assertEquals("no_screen_off_figures", noOffTime.notEvaluable[RuleId.AWAKE_VIOLATION])
        val noAwake = engine.evaluate(inputOf(diffOf(sleep = sleepStats(2 * HOUR, null))))
        assertEquals("no_awake_time", noAwake.notEvaluable[RuleId.AWAKE_VIOLATION])
    }

    @Test
    fun evaluableButQuietEmitsNothing() {
        val report = engine.evaluate(inputOf(diffOf(sleep = sleepStats(2 * HOUR, 1_000))))
        assertTrue(report.insights.isEmpty())
        assertTrue(RuleId.AWAKE_VIOLATION !in report.notEvaluable)
    }

    @Test
    fun awakeRuleDoesNotDependOnBatteryStatsGateOrPerUid() {
        val report = engine.evaluate(
            inputOf(diffOf(sleep = sleepStats(offMs, 400_000), batteryStats = false), perUidAvailable = false),
        )
        assertEquals(1, report.insights.filterIsInstance<AwakeViolation>().size)
        assertTrue(RuleId.AWAKE_VIOLATION !in report.notEvaluable)
    }

    private fun culpritDiff(
        kernelCounters: Boolean = true,
        invalidKernel: Set<String> = emptySet(),
        kernel: List<KernelWakelock> = listOf(KernelWakelock("main", 9_000, 5), KernelWakelock("alarmtimer", 20_000, 7)),
    ): SnapshotDiff = diffOf(
        sleep = sleepStats(offMs, 400_000),
        kernelCounters = kernelCounters,
        invalidKernel = invalidKernel,
        kernel = kernel,
        uids = listOf(
            uidDelta(
                10_001,
                wakelocks = listOf(wakelock("a", 4_000, count = 2), wakelock("b", 6_000, count = 3)),
                alarms = listOf(UidAlarm("x", 4), UidAlarm("y", 9)),
            ),
            uidDelta(
                10_002,
                wakelocks = listOf(wakelock("c", 5_000)),
                alarms = listOf(UidAlarm("z", 6)),
            ),
            // The invalid UID holds bigger numbers that must not be used.
            uidDelta(
                10_003,
                valid = false,
                wakelocks = listOf(wakelock("huge", 900_000)),
                alarms = listOf(UidAlarm("huge", 999)),
            ),
        ),
    )

    private fun culprits(diff: SnapshotDiff, metrics: com.akane.inspiration.telemetry.interval.IntervalMetrics? = null) =
        engine.evaluate(inputOf(diff, metrics)).insights.filterIsInstance<AwakeViolation>().single().culprits

    @Test
    fun culpritsAreTheTopOfEachKindAcrossValidUids() {
        assertEquals(
            listOf(
                Culprit(EntryKind.PARTIAL, "b", 10_001, 6_000, 3),
                Culprit(EntryKind.KERNEL, "alarmtimer", null, 20_000, 7),
                Culprit(EntryKind.ALARM, "y", 10_001, null, 9),
            ),
            culprits(culpritDiff()),
        )
    }

    @Test
    fun kernelCulpritSkippedWhenKernelCountersInvalid() {
        val result = culprits(culpritDiff(kernelCounters = false))
        assertTrue(result.none { it.kind == EntryKind.KERNEL })
        assertEquals(2, result.size)
    }

    @Test
    fun kernelCulpritSkippedWhenItsNameIsInvalid() {
        val onlyInvalid = culprits(
            culpritDiff(invalidKernel = setOf("alarmtimer"), kernel = listOf(KernelWakelock("alarmtimer", 20_000, -1))),
        )
        assertTrue(onlyInvalid.none { it.kind == EntryKind.KERNEL })
        // An invalid top entry is passed over for the best valid one.
        val next = culprits(culpritDiff(invalidKernel = setOf("alarmtimer")))
        assertEquals("main", next.single { it.kind == EntryKind.KERNEL }.name)
    }

    @Test
    fun culpritsWithNoPositiveContributionAreOmitted() {
        val diff = diffOf(
            sleep = sleepStats(offMs, 400_000),
            kernel = listOf(KernelWakelock("idle", 0, 0)),
            uids = listOf(uidDelta(10_001, wakelocks = listOf(wakelock("z", 0)), alarms = listOf(UidAlarm("q", 0)))),
        )
        assertTrue(culprits(diff).isEmpty())
    }

    @Test
    fun culpritScopeFollowsScreenOffOnlyEligibility() {
        val diff = culpritDiff()
        val scope = { metrics: com.akane.inspiration.telemetry.interval.IntervalMetrics? ->
            engine.evaluate(inputOf(diff, metrics)).insights.filterIsInstance<AwakeViolation>().single().culpritScope
        }
        assertEquals(CulpritScope.SCREEN_OFF_ONLY, scope(metricsOf(screenOnMs = 0, screenOffMs = 30 * MIN)))
        // Audit R-03: screen-on lag up to the resolver's capture slack is the anchor's, not a screen-on period.
        assertEquals(CulpritScope.SCREEN_OFF_ONLY, scope(metricsOf(screenOnMs = CAPTURE_SLACK_MS, screenOffMs = 2 * HOUR)))
        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, scope(metricsOf(screenOnMs = CAPTURE_SLACK_MS + 1, screenOffMs = 2 * HOUR)))
        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, scope(metricsOf(unknownMs = 1)))
        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, scope(metricsOf(screenOnMs = 1, unknownMs = 1)))
        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, scope(metricsOf(screenOffMs = 30 * MIN - 1)))
        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, scope(null))
    }
}
