package com.akane.inspiration.sleeplab

import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.diagnostics.rules.DiagnosticInput
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.SleepStats
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The culprit card's state, fed by the real rule engine so a changed rule output cannot drift past the mapping. */
class SleepLabCulpritsTest {
    private val engine = DiagnosticRuleEngine { SensorKind.OTHER }

    private fun sleep(
        screenOffMs: Long = 8 * HOUR_MS,
        awakeMs: Long? = 2 * HOUR_MS,
    ) = SleepStats(
        deepSleepMs = 6 * HOUR_MS,
        screenOffDeepSleepMs = 6 * HOUR_MS,
        screenOffAwakeMs = awakeMs,
        screenOffMs = screenOffMs,
        screenOnMs = 2 * HOUR_MS,
        deepSleepRatio = 0.6,
    )

    private val night =
        snapshotDiff(
            uids =
                listOf(
                    uidDelta(10_001, "com.chat", wakelocks = listOf(wakelock("*job*/com.chat/.Sync", 40 * MIN_MS, 12), wakelock("short", 1_000, 1))),
                    uidDelta(10_002, "com.mail", alarms = listOf(UidAlarm("*walarm*:com.mail.POLL", 30))),
                    uidDelta(10_003, null, valid = false, wakelocks = listOf(wakelock("reset", 99 * HOUR_MS, 1))),
                ),
            kernel = listOf(KernelWakelock("wlan_rx_wake", 20 * MIN_MS, 200), KernelWakelock("battery", 5_000, 2)),
            sleep = sleep(),
        )

    /** A screen-off-only interval: under the capture slack of screen-on time, no unknown time. */
    private fun screenOffMetrics(screenOnMs: Long = 0) = intervalMetrics().copy(screenOnMs = screenOnMs, screenOffMs = 8 * HOUR_MS)

    private fun analysed(
        diff: SnapshotDiff = night,
        perUid: Boolean = true,
        metrics: IntervalMetrics = screenOffMetrics(),
    ) = intervalAnalysis(
        resolved = resolved(perUidAvailable = perUid),
        diff = diff,
        report = engine.evaluate(DiagnosticInput(diff, metrics, perUid)),
    )

    @Test
    fun `a fired rule lists one culprit per kind with its owner's label, duration and count`() {
        val ui = awakeCheckUi(analysed(), mapOf("com.chat" to "Chat")) as AwakeCheckUi.Violation

        assertEquals(Severity.RED, ui.severity)
        assertEquals(0.25, ui.awakeRatio, 1e-9)
        assertEquals(8 * HOUR_MS, ui.screenOffMs)
        assertEquals(CulpritScope.SCREEN_OFF_ONLY, ui.scope)
        assertFalse(ui.needsFullSnapshots)
        assertEquals("the invalid uid is counted, never ranked", 1, ui.skippedApps)
        assertEquals(
            listOf(
                CulpritRow(EntryKind.PARTIAL, "*job*/com.chat/.Sync", 10_001, "com.chat", "Chat", 40 * MIN_MS, 12),
                CulpritRow(EntryKind.KERNEL, "wlan_rx_wake", null, null, null, 20 * MIN_MS, 200),
                // No label for com.mail: the package stands in for it.
                CulpritRow(EntryKind.ALARM, "*walarm*:com.mail.POLL", 10_002, "com.mail", "com.mail", null, 30),
            ),
            ui.culprits,
        )
    }

    @Test
    fun `a mixed interval says its culprits were counted over the whole period`() {
        val ui = awakeCheckUi(analysed(metrics = screenOffMetrics(screenOnMs = 2 * HOUR_MS)), emptyMap()) as AwakeCheckUi.Violation

        assertEquals(CulpritScope.WHOLE_INTERVAL_CONTRIBUTORS, ui.scope)
    }

    @Test
    fun `without per-uid data only the kernel culprit is listed and the card asks for full snapshots`() {
        val ui = awakeCheckUi(analysed(perUid = false), mapOf("com.chat" to "Chat")) as AwakeCheckUi.Violation

        assertTrue(ui.needsFullSnapshots)
        assertEquals(listOf(EntryKind.KERNEL), ui.culprits.map { it.kind })
        assertEquals("the needs-full state already says no app was checked", 0, ui.skippedApps)
    }

    @Test
    fun `an awake share within the limit shows no card`() {
        val calm = night.copy(sleep = sleep(awakeMs = 20 * MIN_MS))

        assertEquals(AwakeCheckUi.WithinLimit, awakeCheckUi(analysed(diff = calm), emptyMap()))
    }

    @Test
    fun `a check that could not run says why instead of passing`() {
        val short = night.copy(sleep = sleep(screenOffMs = 20 * MIN_MS))
        val noAwake = night.copy(sleep = sleep(awakeMs = null))
        val noSleep = night.copy(sleep = null)

        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.ShortScreenOff), awakeCheckUi(analysed(diff = short), emptyMap()))
        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.NoAwakeTime), awakeCheckUi(analysed(diff = noAwake), emptyMap()))
        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.ShortScreenOff), awakeCheckUi(analysed(diff = noSleep), emptyMap()))
    }

    @Test
    fun `an unknown not-evaluable reason is a skipped check, never a pass`() {
        val report = engine.evaluate(DiagnosticInput(night, screenOffMetrics(), true))
        val odd = report.copy(insights = emptyList(), notEvaluable = mapOf(RuleId.AWAKE_VIOLATION to "new_reason"))

        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.Other), awakeCheckUi(intervalAnalysis(diff = night, report = odd), emptyMap()))
    }

    @Test
    fun `the whole analysis carries the culprit card with labelled owners`() {
        val analysis = analysed()
        val labels = visiblePackages(analysis).associateWith { it.uppercase() }

        val ui = sleepLabAnalysis(analysis, labels).awake as AwakeCheckUi.Violation
        assertEquals(listOf("COM.CHAT", null, "COM.MAIL"), ui.culprits.map { it.appLabel })
    }

    @Test
    fun `every dictionary action is worded and an unknown one is not`() {
        listOf("standby_bucket", "appops_wake_lock", "bg_dexopt").forEach { assertTrue(it, actionGuidanceRes(it) != null) }
        assertNull(actionGuidanceRes("reboot_now"))
    }
}
