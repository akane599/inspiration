package com.akane.inspiration.diagnostics.analysis

import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.CulpritScope
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.sensor.GPS_SENSOR_HANDLE
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit R-03, end to end (resolver -> differ -> metrics -> rules): a night shaped as production now stores it. The
 * screen-off broadcast takes a FULL capture without a history ingest (binding the service on demand), the screen-on
 * one closing a night of at least 30 min does too, landing [ON_LAG_MS] after the history SCREEN_ON, and the morning
 * app open ingests the history that holds the night's SCREEN_OFF and SCREEN_ON.
 */
class NightIntervalAnalysisTest {
    private val off = NIGHT_START
    private val on = NIGHT_START + 8 * HOUR

    private val history =
        listOf(
            historyEvent(off - HOUR, HistoryKind.UNPLUGGED, level = 90),
            historyEvent(off - HOUR, HistoryKind.SCREEN_ON, level = 90),
            historyEvent(off, HistoryKind.SCREEN_OFF, level = 85),
            historyEvent(off, HistoryKind.LEVEL, level = 85),
            historyEvent(off + 4 * HOUR, HistoryKind.LEVEL, level = 70),
            historyEvent(on - 10 * MIN, HistoryKind.LEVEL, level = 55),
            historyEvent(on, HistoryKind.SCREEN_ON, level = 55),
        )

    /** The night as stored: the screen-off capture 26 ms after the history event, the screen-on one [ON_LAG_MS] after. */
    private fun night(screenCaptures: SnapshotKind): FakeSnapshotStore {
        val offAt = off + 26
        return FakeSnapshotStore(
            snapshots =
                listOf(
                    capture(offAt, CaptureTrigger.SCREEN_OFF, nightMs = 0, kind = screenCaptures),
                    capture(on + ON_LAG_MS, CaptureTrigger.SCREEN_ON, nightMs = on + ON_LAG_MS - offAt, kind = screenCaptures),
                    capture(on + 3_000, CaptureTrigger.APP_OPEN, nightMs = on + 3_000 - offAt, ingestedHistory = true),
                ),
            history = history,
        )
    }

    @Test
    fun `a night with full screen captures evaluates sensor leak, night vampire and awake violation`() =
        runTest {
            val analysis = IntervalAnalyzer(night(SnapshotKind.FULL), ruleEngine()).analyze(ReferenceKind.SinceScreenOff, NOW)!!

            assertEquals(CaptureTrigger.SCREEN_OFF, analysis.resolved.ref.trigger)
            assertEquals(CaptureTrigger.SCREEN_ON, analysis.resolved.now.trigger)
            assertTrue(analysis.resolved.perUidAvailable)
            assertTrue(analysis.diff.validity.batteryStats)
            // The screen-on capture's lag is screen-on time inside the window, within the resolver's capture slack.
            assertEquals(ON_LAG_MS, analysis.metrics.screenOnMs)
            assertEquals(0L, analysis.metrics.unknownMs)

            val report = analysis.report
            for (rule in listOf(RuleId.SENSOR_LEAK, RuleId.NIGHT_VAMPIRE, RuleId.AWAKE_VIOLATION)) {
                assertNull("$rule: ${report.notEvaluable[rule]}", report.notEvaluable[rule])
            }
            val leak = report.insights.filterIsInstance<SensorLeak>().single()
            assertEquals(SensorKind.GPS, leak.sensor)
            assertEquals(APP_UID, leak.subjectUid)
            assertEquals(APP_UID, report.insights.filterIsInstance<NightVampire>().single().subjectUid)
            val awake = report.insights.filterIsInstance<AwakeViolation>().single()
            assertEquals(CulpritScope.SCREEN_OFF_ONLY, awake.culpritScope)
            assertEquals(0.3, awake.awakeRatio, 1e-6)
            assertTrue(awake.culprits.any { it.uid == APP_UID })
        }

    @Test
    fun `the night view keeps the window and level and time figures of light screen captures`() =
        runTest {
            val full = IntervalAnalyzer(night(SnapshotKind.FULL), ruleEngine()).analyze(ReferenceKind.SinceScreenOff, NOW)!!
            // Before R-03 the screen broadcasts stored LIGHT rows at the same times.
            val light = IntervalAnalyzer(night(SnapshotKind.LIGHT), ruleEngine()).analyze(ReferenceKind.SinceScreenOff, NOW)!!

            assertEquals(light.resolved.coveredFromWallMs, full.resolved.coveredFromWallMs)
            assertEquals(light.resolved.coveredToWallMs, full.resolved.coveredToWallMs)
            assertEquals(on + ON_LAG_MS, full.resolved.coveredToWallMs)
            assertEquals(light.metrics, full.metrics)
            assertEquals(on - (off + 26), full.metrics.screenOffMs)
            assertEquals(30, full.metrics.levelUsedPct)
            assertEquals(30, full.metrics.levelDropScreenOff)
            assertEquals(0, full.metrics.levelDropScreenOn)

            // LIGHT endpoints carry no batterystats, so the rules could not run on that night.
            assertFalse(light.diff.validity.batteryStats)
            assertEquals("battery_stats_invalid", light.report.notEvaluable[RuleId.SENSOR_LEAK])
            assertEquals("battery_stats_invalid", light.report.notEvaluable[RuleId.NIGHT_VAMPIRE])
            assertTrue(RuleId.AWAKE_VIOLATION in light.report.notEvaluable)
        }

    @Test
    fun `a night no capture closes has no analysis but the history alone still measures it`() =
        runTest {
            // The screen-on broadcast was never captured; the app was opened 30 s into the morning and ingested history.
            val offAt = off + 26
            val store =
                FakeSnapshotStore(
                    snapshots =
                        listOf(
                            capture(offAt, CaptureTrigger.SCREEN_OFF, nightMs = 0),
                            capture(on + 30_000, CaptureTrigger.APP_OPEN, nightMs = on + 30_000 - offAt, ingestedHistory = true),
                        ),
                    history = history,
                )
            val analyzer = IntervalAnalyzer(store, ruleEngine())

            assertNull(analyzer.analyze(ReferenceKind.SinceScreenOff, NOW))
            val metrics = analyzer.historyOnly(ReferenceKind.SinceScreenOff, NOW)!!

            // Timed by the history's own SCREEN_OFF and SCREEN_ON, not by any capture.
            assertEquals(off, metrics.fromWallMs)
            assertEquals(on, metrics.toWallMs)
            assertEquals(8 * HOUR, metrics.screenOffMs)
            assertEquals(0L, metrics.screenOnMs)
            assertEquals(0L, metrics.unknownMs)
            assertEquals(30, metrics.levelDropScreenOff)
            assertEquals(30.0 / 8, metrics.screenOffPctPerHour!!, 1e-9)
            assertNull("only since screen off has a history-timed period", analyzer.historyOnly(ReferenceKind.SinceUnplugged, NOW))
        }

    /**
     * A FULL (or LIGHT) capture [nightMs] into the night. FULL rows carry batterystats counters of one stats session
     * that grow with it: screen-off time by all of it and awake screen-off time by 30%; the app's background energy,
     * GPS and wakelock grow too.
     */
    private fun capture(
        wallMs: Long,
        trigger: CaptureTrigger,
        nightMs: Long,
        kind: SnapshotKind = SnapshotKind.FULL,
        ingestedHistory: Boolean = false,
    ): BatterySnapshot {
        val elapsedMs = wallMs - BOOT_WALL
        val clock = SnapshotClock(wallMs, elapsedMs, elapsedMs / 2, bootCount = 1, statsStartMs = null, statsDurationMs = null)
        if (kind == SnapshotKind.LIGHT) return lightSnapshot(wallMs, elapsedMs, trigger = trigger).copy(clock = clock)
        val app =
            UidSnapshot(
                uid = APP_UID,
                packageName = PACKAGE,
                totalMah = 20.0 + nightMs * 10.0 / HOUR,
                fgMah = null,
                bgMah = 5.0 + nightMs * 9.5 / HOUR,
                fgsMah = null,
                cachedMah = null,
                componentsMah = emptyMap(),
                cpuUserMs = 0,
                cpuSystemMs = 0,
                fgActivityMs = 0,
                fgServiceMs = 0,
                partialWakelocks = listOf(UidWakelock("sync", durationMs = nightMs / 4, bgDurationMs = nightMs / 4, count = 1 + nightMs / HOUR)),
                wakeupAlarms = emptyList(),
                sensors = listOf(UidSensor(GPS_SENSOR_HANDLE, totalMs = nightMs / 2, bgMs = nightMs / 2, count = 1)),
                ownerInstallFingerprint = UidOwnerFingerprint.create(APP_UID, USER_SERIAL, listOf(UidOwnerInstall(PACKAGE, APP_UID, 123L))),
            )
        return BatterySnapshot(
            id = wallMs,
            kind = SnapshotKind.FULL,
            trigger = trigger,
            clock = clock.copy(statsStartMs = STATS_START, statsDurationMs = elapsedMs),
            battery = BatteryState(60, false, "discharging", 300, 4_000, -200, 1_000_000L, dischargedMah = 500.0 + nightMs * 75.0 / HOUR),
            system =
                SystemTimers(
                    startClockTimeMs = STATS_START,
                    totalRealtimeMs = elapsedMs,
                    totalUptimeMs = elapsedMs / 2,
                    batteryRealtimeMs = elapsedMs,
                    batteryUptimeMs = elapsedMs / 2,
                    screenOffRealtimeMs = 10 * HOUR + nightMs,
                    screenOffUptimeMs = 2 * HOUR + nightMs * 3 / 10,
                    screenDozeMs = 0,
                    screenOnMs = 5 * HOUR,
                    interactiveMs = 5 * HOUR,
                    deepDozeMs = 0,
                    deepDozeCount = 0,
                    lightDozeMs = 0,
                    dischargeTotalMah = 500.0 + nightMs * 75.0 / HOUR,
                    dischargeScreenOffMah = 200.0 + nightMs * 75.0 / HOUR,
                    chargeTimeRemainingMs = null,
                    dischargeTimeRemainingMs = null,
                ),
            uids = listOf(app),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability =
                listOf("usage", "dump", "kernel", "alarm", "cpu").associateWith { SourceAvailability(true, null) } +
                    // A FULL broadcast capture reads no history; the morning app open ingests it.
                    (HISTORY_SOURCE to SourceAvailability(ingestedHistory, null)),
            truncatedUids = 0,
            uidIdentityScope = UidIdentityScope(userId = 0, userSerial = USER_SERIAL, readerEpoch = "reader", packageChangeSequence = 0),
        )
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val BOOT_WALL = 1_790_000_000_000L
        const val NIGHT_START = BOOT_WALL + 20 * HOUR
        const val NOW = NIGHT_START + 8 * HOUR + MIN
        const val STATS_START = BOOT_WALL + HOUR

        /** How long after the history SCREEN_ON its broadcast's capture lands. */
        const val ON_LAG_MS = 800L
        const val APP_UID = 10_123
        const val USER_SERIAL = 7L
        const val PACKAGE = "com.example.tracker"
    }
}
