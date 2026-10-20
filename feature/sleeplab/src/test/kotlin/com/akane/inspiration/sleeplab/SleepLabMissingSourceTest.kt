package com.akane.inspiration.sleeplab

import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.rules.DiagnosticInput
import com.akane.inspiration.diagnostics.rules.DiagnosticRuleEngine
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.SnapshotDiff
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real [SnapshotDiffer] pairs whose missing data has a known cause. Sleep Lab must name that cause (a missing
 * snapshot source, a battery-stats reset, a restart), never a short screen-off or a restart that did not happen.
 */
class SleepLabMissingSourceTest {
    private val engine = DiagnosticRuleEngine { SensorKind.OTHER }
    private val statsStart = NOW_MS - 20 * HOUR_MS
    private val system =
        SystemTimers(
            startClockTimeMs = statsStart, totalRealtimeMs = 10 * HOUR_MS, totalUptimeMs = 5 * HOUR_MS,
            batteryRealtimeMs = 10 * HOUR_MS, batteryUptimeMs = 5 * HOUR_MS, screenOffRealtimeMs = 6 * HOUR_MS,
            screenOffUptimeMs = HOUR_MS, screenDozeMs = 0, screenOnMs = 4 * HOUR_MS, interactiveMs = 4 * HOUR_MS,
            deepDozeMs = 0, deepDozeCount = 0, lightDozeMs = 0, dischargeTotalMah = 100.0, dischargeScreenOffMah = 20.0,
            chargeTimeRemainingMs = null, dischargeTimeRemainingMs = null,
        )
    private val everySource =
        mapOf(
            "usage" to SourceAvailability(true, null), "dump" to SourceAvailability(true, null),
            "kernel" to SourceAvailability(true, null), "cpu" to SourceAvailability(true, null),
        )

    /** A FULL capture at the start of a 10 h window, every source read. */
    private val fullRef: BatterySnapshot =
        snapshot(NOW_MS - 10 * HOUR_MS).copy(
            trigger = CaptureTrigger.SCREEN_OFF,
            clock = snapshotClock(NOW_MS - 10 * HOUR_MS).copy(
                elapsedRealtimeMs = 10 * HOUR_MS, uptimeMs = 5 * HOUR_MS, statsStartMs = statsStart, statsDurationMs = 10 * HOUR_MS,
            ),
            system = system,
            kernelWakelocks = listOf(KernelWakelock("PowerManagerService.WakeLocks", 1_000, 1)),
            cpuFreq = listOf(CpuFreqTime(0, 1_000_000, 1_000)),
            availability = everySource,
        )

    /** A FULL capture 10 h later in the same boot and battery-stats session: an 8 h night with 30 min awake. */
    private val fullEnd: BatterySnapshot =
        fullRef.copy(
            id = NOW_MS,
            trigger = CaptureTrigger.SCREEN_ON,
            clock = fullRef.clock.copy(wallMs = NOW_MS, elapsedRealtimeMs = 20 * HOUR_MS, uptimeMs = 6 * HOUR_MS, statsDurationMs = 20 * HOUR_MS),
            system = system.copy(
                totalRealtimeMs = 20 * HOUR_MS, totalUptimeMs = 6 * HOUR_MS, batteryRealtimeMs = 20 * HOUR_MS,
                batteryUptimeMs = 6 * HOUR_MS, screenOffRealtimeMs = 14 * HOUR_MS, screenOffUptimeMs = HOUR_MS + 30 * MIN_MS,
                screenOnMs = 6 * HOUR_MS, interactiveMs = 6 * HOUR_MS,
            ),
            kernelWakelocks = listOf(KernelWakelock("PowerManagerService.WakeLocks", 9_000, 5)),
            cpuFreq = listOf(CpuFreqTime(0, 1_000_000, 5_000)),
        )

    /** A LIGHT capture 10 h later in the same boot (Shizuku not ready, or a broadcast that could not bind). */
    private val lightEnd: BatterySnapshot =
        snapshot(NOW_MS).copy(
            kind = SnapshotKind.LIGHT,
            trigger = CaptureTrigger.SCREEN_ON,
            clock = snapshotClock(NOW_MS).copy(
                elapsedRealtimeMs = 20 * HOUR_MS, uptimeMs = 6 * HOUR_MS, statsStartMs = null, statsDurationMs = null,
            ),
            availability = mapOf("light" to SourceAvailability(true, null)),
        )

    private fun analysed(
        ref: BatterySnapshot,
        now: BatterySnapshot,
    ): Pair<SnapshotDiff, AnalysisUi.Ready> {
        val perUid = ref.kind == SnapshotKind.FULL && now.kind == SnapshotKind.FULL
        val diff = SnapshotDiffer.diff(ref, now)
        val report = engine.evaluate(DiagnosticInput(diff, intervalMetrics(), perUidAvailable = perUid))
        val analysis =
            intervalAnalysis(
                resolved = resolved(perUidAvailable = perUid).copy(ref = ref, now = now),
                diff = diff,
                report = report,
            )
        return diff to sleepLabAnalysis(analysis, emptyMap())
    }

    @Test
    fun `a full pair of one boot and session reads every source`() {
        val (diff, ui) = analysed(fullRef, fullEnd)

        assertTrue(diff.validity.batteryStats && diff.validity.dumpCounters && diff.validity.cpuFreq)
        assertEquals(AwakeCheckUi.WithinLimit, ui.awake)
        assertTrue(ui.kernelWakelocks is ListUi.Rows)
        assertTrue(ui.cpu is CpuUi.Ready)
    }

    @Test
    fun `an 8 h night ending in a LIGHT snapshot says the end lacks the counters, not that screen-off was short`() {
        val (diff, ui) = analysed(fullRef, lightEnd)

        assertTrue("same boot: kernel counters compare", diff.validity.kernelCounters)
        assertNull("no screen-off figures: diff.sleep=${diff.sleep}", diff.sleep?.screenOffMs)
        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.NotCaptured), ui.awake)
        assertEquals(ListUi.Unavailable(UnavailableCause.NotCaptured), ui.kernelWakelocks)
        assertEquals(CpuUi.NotCaptured, ui.cpu)
        // The per-app tabs already say a light end can't rank apps.
        assertEquals(ListUi.NeedsFullSnapshots, ui.appWakelocks)
        assertEquals(ListUi.NeedsFullSnapshots, ui.alarms)
    }

    @Test
    fun `a same-boot pair whose kernel never reported cpufreq stats shows no frequency data, not a restart`() {
        val noCpu = everySource + ("cpu" to SourceAvailability(false, "unavailable"))
        val (diff, ui) = analysed(fullRef.copy(cpuFreq = emptyList(), availability = noCpu), fullEnd.copy(cpuFreq = emptyList(), availability = noCpu))

        assertTrue("same boot, increasing elapsed", diff.validity.kernelCounters)
        assertEquals(CpuUi.NoData, ui.cpu)
    }

    @Test
    fun `a pair whose end missed the battery-stats dump says so rather than claiming a reset or a restart`() {
        val noDump = everySource + ("dump" to SourceAvailability(false, "timeout"))
        val (diff, ui) = analysed(fullRef, fullEnd.copy(system = null, kernelWakelocks = emptyList(), availability = noDump))

        assertTrue("same battery-stats session", diff.validity.batteryStats)
        assertFalse(diff.validity.dumpCounters)
        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.NotCaptured), ui.awake)
        assertEquals(ListUi.Unavailable(UnavailableCause.NotCaptured), ui.appWakelocks)
        assertEquals(ListUi.Unavailable(UnavailableCause.NotCaptured), ui.alarms)
        assertEquals(ListUi.Unavailable(UnavailableCause.NotCaptured), ui.kernelWakelocks)
        assertTrue("frequency counters don't come from the dump", ui.cpu is CpuUi.Ready)
    }

    @Test
    fun `a battery-stats reset within one boot says the counters were reset, not that the device restarted`() {
        val resetStart = NOW_MS - 2 * HOUR_MS
        val reset =
            fullEnd.copy(
                clock = fullEnd.clock.copy(statsStartMs = resetStart, statsDurationMs = 2 * HOUR_MS),
                system = system.copy(startClockTimeMs = resetStart, totalRealtimeMs = 2 * HOUR_MS, batteryRealtimeMs = 2 * HOUR_MS),
            )
        val (diff, ui) = analysed(fullRef, reset)

        assertTrue("same boot", diff.validity.kernelCounters)
        assertFalse("a new battery-stats session", diff.validity.batteryStats)
        assertEquals(AwakeCheckUi.NotEvaluable(AwakeSkipReason.StatsReset), ui.awake)
        assertEquals(ListUi.Unavailable(UnavailableCause.StatsReset), ui.appWakelocks)
        assertEquals(ListUi.Unavailable(UnavailableCause.StatsReset), ui.alarms)
        assertEquals(ListUi.Unavailable(UnavailableCause.StatsReset), ui.kernelWakelocks)
        assertTrue("a stats reset leaves the kernel's own counters alone", ui.cpu is CpuUi.Ready)
    }

    @Test
    fun `a restart within one battery-stats session says the kernel counters restarted and keeps the rest`() {
        val rebooted =
            fullEnd.copy(
                clock = fullEnd.clock.copy(bootCount = fullRef.clock.bootCount + 1, elapsedRealtimeMs = HOUR_MS, uptimeMs = HOUR_MS / 2),
            )
        val (diff, ui) = analysed(fullRef, rebooted)

        assertFalse("a new boot", diff.validity.kernelCounters)
        assertTrue("battery stats persist across the restart", diff.validity.batteryStats)
        assertEquals(ListUi.Unavailable(UnavailableCause.DeviceRestarted), ui.kernelWakelocks)
        assertEquals(CpuUi.Unavailable, ui.cpu)
        // The screen-off figures come from battery stats, which survive the restart.
        assertEquals(AwakeCheckUi.WithinLimit, ui.awake)
    }
}
