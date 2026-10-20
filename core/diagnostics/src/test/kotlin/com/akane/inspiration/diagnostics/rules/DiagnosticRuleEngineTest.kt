package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.analysis.lightSnapshot
import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.model.insightOrder
import com.akane.inspiration.diagnostics.sensor.GPS_SENSOR_HANDLE
import com.akane.inspiration.diagnostics.story.BatteryStoryBuilder
import com.akane.inspiration.diagnostics.story.StorySentence
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRuleEngineTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    @Test
    fun truncatedUsageNeverBecomesVampiresSensorLeaksOrUidCulprits() {
        val previousUid =
            UidSnapshot(
                uid = 10_001,
                packageName = "pkg.10001",
                totalMah = 40.0,
                fgMah = null,
                bgMah = 39.0,
                fgsMah = null,
                cachedMah = null,
                componentsMah = emptyMap(),
                cpuUserMs = 0,
                cpuSystemMs = 0,
                fgActivityMs = 0,
                fgServiceMs = 0,
                partialWakelocks = listOf(wakelock("partial", 0)),
                wakeupAlarms = emptyList(),
                sensors = listOf(sensor(GPS_SENSOR_HANDLE, 0)),
                ownerInstallFingerprint = UidOwnerFingerprint.create(10_001, 7, listOf(UidOwnerInstall("test.owner", 10_001, 123))),
            )
        val ref =
            lightSnapshot(0).let { snapshot ->
                snapshot.copy(
                    kind = SnapshotKind.FULL,
                    clock = snapshot.clock.copy(statsStartMs = 100_000, statsDurationMs = HOUR),
                    uids = listOf(previousUid),
                    uidIdentityScope = UidIdentityScope(0, 7, "test-reader", 0),
                    deviceComponentsMah = mapOf("SCREEN" to 10.0, "MOBILE_RADIO" to 20.0),
                    kernelWakelocks = listOf(KernelWakelock("independent kernel", 0, 0)),
                )
            }
        val now =
            ref.copy(
                clock = ref.clock.copy(wallMs = 2 * HOUR, elapsedRealtimeMs = 2 * HOUR, statsDurationMs = 3 * HOUR),
                uids =
                    listOf(
                        previousUid.copy(
                            totalMah = 50.0,
                            bgMah = 49.0,
                            partialWakelocks = listOf(wakelock("partial", HOUR)),
                            sensors = listOf(sensor(GPS_SENSOR_HANDLE, 6 * MIN)),
                        ),
                    ),
                deviceComponentsMah = mapOf("SCREEN" to 20.0, "MOBILE_RADIO" to 40.0),
                kernelWakelocks = listOf(KernelWakelock("independent kernel", HOUR, 1)),
            )

        fun report(
            refTruncated: Int,
            nowTruncated: Int,
            summed: Boolean = false,
            identityKnown: Boolean = true,
        ) = engine.evaluate(
            inputOf(
                SnapshotDiffer
                    .diff(
                        ref.copy(
                            truncatedUids = refTruncated,
                            uids = listOf(if (refTruncated > 0) previousUid.copy(totalMah = 0.0, bgMah = null) else previousUid),
                            availability = if (summed) mapOf("usage" to SourceAvailability(true, "device_from_uid_sum")) else emptyMap(),
                        ),
                        now.copy(truncatedUids = nowTruncated, uidIdentityScope = now.uidIdentityScope.takeIf { identityKnown }),
                    ).copy(sleep = sleepStats(2 * HOUR, HOUR)),
                metricsOf(),
            ),
        )

        val complete = report(0, 0)
        assertTrue(complete.insights.any { it is NightVampire })
        assertTrue(complete.insights.any { it is SensorLeak })
        assertTrue(
            complete.insights
                .filterIsInstance<AwakeViolation>()
                .single()
                .culprits
                .any { it.uid == 10_001 },
        )

        for ((refTruncated, nowTruncated) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val truncated = report(refTruncated, nowTruncated)
            assertEquals(1, truncated.skippedInvalidUids)
            assertTrue(truncated.insights.none { it is NightVampire || it is SensorLeak })
            // Its only app was left out, so the per-app rules checked nothing: not evaluable, never a pass (B-03).
            assertEquals(APPS_UNPROVEN, truncated.notEvaluable[RuleId.NIGHT_VAMPIRE])
            assertEquals(APPS_UNPROVEN, truncated.notEvaluable[RuleId.SENSOR_LEAK])
            val culprits =
                truncated.insights
                    .filterIsInstance<AwakeViolation>()
                    .single()
                    .culprits
            assertEquals(listOf("independent kernel"), culprits.map { it.name })
            assertTrue(culprits.all { it.uid == null })
            assertTrue(truncated.insights.any { it is ModemDrain })
        }
        val unknownIdentity = report(0, 0, identityKnown = false)
        assertEquals(1, unknownIdentity.skippedInvalidUids)
        assertTrue(unknownIdentity.insights.none { it is NightVampire || it is SensorLeak })
        assertEquals(APPS_UNPROVEN, unknownIdentity.notEvaluable[RuleId.NIGHT_VAMPIRE])
        assertEquals(APPS_UNPROVEN, unknownIdentity.notEvaluable[RuleId.SENSOR_LEAK])
        assertEquals(
            listOf("independent kernel"),
            unknownIdentity.insights
                .filterIsInstance<AwakeViolation>()
                .single()
                .culprits
                .map { it.name },
        )
        assertTrue(unknownIdentity.insights.any { it is ModemDrain })

        // The same component values are not independent when the truncated endpoint sums UID rows.
        val incompleteAggregate = report(1, 0, summed = true)
        assertTrue(incompleteAggregate.insights.none { it is ModemDrain })
        assertEquals("screen_power_zero", incompleteAggregate.notEvaluable[RuleId.MODEM_DRAIN])
    }

    @Test
    fun changedPackageCountersNeverBecomeAnomaliesOrAwakeCulprits() {
        val uid =
            uidDelta(
                10_001,
                totalMah = 10.0,
                bgMah = 9.0,
                sensors = listOf(sensor(GPS_SENSOR_HANDLE, 6 * MIN)),
                wakelocks = listOf(wakelock("old identity", HOUR)),
            )

        fun evaluate(changed: Boolean) =
            engine.evaluate(
                inputOf(
                    diffOf(uids = listOf(uid.copy(packageChanged = changed)), sleep = sleepStats(2 * HOUR, HOUR)),
                    metricsOf(),
                ),
            )
        val comparable = evaluate(false)
        assertTrue(comparable.insights.any { it is NightVampire })
        assertTrue(comparable.insights.any { it is SensorLeak })
        assertTrue(
            comparable.insights
                .filterIsInstance<AwakeViolation>()
                .single()
                .culprits
                .isNotEmpty(),
        )
        val changed = evaluate(true)
        assertEquals(1, changed.skippedInvalidUids)
        assertTrue(changed.insights.none { it is NightVampire || it is SensorLeak })
        assertEquals(APPS_UNPROVEN, changed.notEvaluable[RuleId.NIGHT_VAMPIRE])
        assertEquals(APPS_UNPROVEN, changed.notEvaluable[RuleId.SENSOR_LEAK])
        assertTrue(
            changed.insights
                .filterIsInstance<AwakeViolation>()
                .single()
                .culprits
                .isEmpty(),
        )
    }

    @Test
    fun missingDumpDoesNotInventForegroundInactivityOrSuppressValidEnergyRules() {
        val base =
            diffOf(
                uids = listOf(uidDelta(10_001, totalMah = 10.0, bgMah = 9.0)),
                components = mapOf("SCREEN" to 10.0, "MOBILE_RADIO" to 20.0),
            )
        val report = engine.evaluate(inputOf(base.copy(validity = base.validity.copy(dumpCounters = false))))
        assertEquals("source_absent", report.notEvaluable[RuleId.NIGHT_VAMPIRE])
        assertEquals("source_absent", report.notEvaluable[RuleId.SENSOR_LEAK])
        assertTrue(report.insights.none { it is NightVampire || it is SensorLeak })
        assertTrue(report.insights.any { it is ModemDrain })
    }

    @Test
    fun emptyDiffEvaluatesEveryRuleWithTheExpectedReasons() {
        val report = engine.evaluate(inputOf(diffOf()))
        assertTrue(report.insights.isEmpty())
        assertEquals(0, report.skippedInvalidUids)
        assertEquals(
            mapOf(
                // diffOf() carries no sleep figures at all, which is not a short screen-off.
                RuleId.AWAKE_VIOLATION to "no_screen_off_figures",
                RuleId.MODEM_DRAIN to "screen_power_zero",
                RuleId.SENSOR_LEAK to "not_screen_off_interval",
            ),
            report.notEvaluable,
        )
    }

    @Test
    fun invalidBatteryStatsGatesR1R3R4ButNotR2() {
        val report =
            engine.evaluate(
                inputOf(diffOf(batteryStats = false, sleep = sleepStats(2 * HOUR, 1_000)), metricsOf()),
            )
        assertEquals(
            mapOf(
                RuleId.NIGHT_VAMPIRE to "battery_stats_invalid",
                RuleId.MODEM_DRAIN to "battery_stats_invalid",
                RuleId.SENSOR_LEAK to "battery_stats_invalid",
            ),
            report.notEvaluable,
        )
    }

    @Test
    fun missingPerUidDataGatesR1AndR4Only() {
        val report =
            engine.evaluate(
                inputOf(
                    diffOf(sleep = sleepStats(2 * HOUR, 1_000), components = mapOf("SCREEN" to 10.0)),
                    metricsOf(),
                    perUidAvailable = false,
                ),
            )
        assertEquals(
            mapOf(RuleId.NIGHT_VAMPIRE to "per_uid_unavailable", RuleId.SENSOR_LEAK to "per_uid_unavailable"),
            report.notEvaluable,
        )
    }

    @Test
    fun skippedInvalidUidsAreCountedEvenWhenAGateTrips() {
        val report =
            engine.evaluate(
                inputOf(
                    diffOf(uids = listOf(uidDelta(10_001, valid = false), uidDelta(10_002), uidDelta(10_003, valid = false))),
                    perUidAvailable = false,
                ),
            )
        assertEquals(2, report.skippedInvalidUids)
    }

    /**
     * Audit B-03: when no application UID could be checked (owner unproven, usage truncated, counters reset or package
     * changed), the per-app rules saw no app at all. That is not an all-clear, so the story must not say "No anomalies".
     */
    @Test
    fun noCheckableAppMakesThePerAppRulesNotEvaluableAndWithholdsTheAllClear() {
        val diff =
            diffOf(
                uids =
                    listOf(
                        // A platform UID needs no owner proof; its sensor rows show the sensor source is present.
                        uidDelta(1_000, totalMah = 30.0, sensors = listOf(sensor(ACCEL_HANDLE, 5 * MIN))),
                        // Two apps that would fire Night Vampire and Sensor Leak, had their identity been proven.
                        uidDelta(10_123, totalMah = 80.0, bgMah = 79.0, valid = false, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 2 * HOUR))),
                        uidDelta(10_456, totalMah = 40.0, cachedMah = 35.0, valid = false),
                    ),
                components = mapOf("SCREEN" to 10.0, "MOBILE_RADIO" to 1.0),
                sleep = sleepStats(screenOffMs = 8 * HOUR, screenOffAwakeMs = 10 * MIN),
                dischargedMah = 500.0,
            )
        val metrics = metricsOf(screenOffMs = 8 * HOUR)

        val report = engine.evaluate(inputOf(diff, metrics))
        val story = BatteryStoryBuilder().build(metrics, diff.sleep, report, endsNow = false)

        assertTrue(
            "no app was checked (notEvaluable=${report.notEvaluable}), yet the story says: ${story.sentences}",
            story.sentences.none { it is StorySentence.Anomalies },
        )
        assertEquals(2, report.skippedInvalidUids)
        assertTrue(report.insights.isEmpty())
        assertEquals(
            mapOf(RuleId.NIGHT_VAMPIRE to APPS_UNPROVEN, RuleId.SENSOR_LEAK to APPS_UNPROVEN),
            report.notEvaluable,
        )
    }

    @Test
    fun oneCheckableAppKeepsThePerAppRulesEvaluable() {
        val diff =
            diffOf(
                uids =
                    listOf(
                        uidDelta(10_123, totalMah = 80.0, bgMah = 79.0, valid = false, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 2 * HOUR))),
                        uidDelta(10_456, totalMah = 3.0, bgMah = 0.5, sensors = listOf(sensor(ACCEL_HANDLE, MIN))),
                    ),
            )

        val report = engine.evaluate(inputOf(diff, metricsOf(screenOffMs = 8 * HOUR)))

        assertEquals(1, report.skippedInvalidUids)
        assertTrue(RuleId.NIGHT_VAMPIRE !in report.notEvaluable)
        assertTrue(RuleId.SENSOR_LEAK !in report.notEvaluable)
    }

    @Test
    fun aDiffWithoutAppUidsHasNoAppToProve() {
        // Platform UIDs only, including system in user 10 with counters that went negative: none of them is an app.
        val diff =
            diffOf(
                uids =
                    listOf(
                        uidDelta(1_000, sensors = listOf(sensor(ACCEL_HANDLE, MIN))),
                        uidDelta(1_001_000, valid = false, sensors = listOf(sensor(ACCEL_HANDLE, MIN))),
                    ),
            )

        val report = engine.evaluate(inputOf(diff, metricsOf(screenOffMs = 8 * HOUR)))

        assertTrue(RuleId.NIGHT_VAMPIRE !in report.notEvaluable)
        assertTrue(RuleId.SENSOR_LEAK !in report.notEvaluable)
    }

    @Test
    fun insightsAreSortedByInsightOrder() {
        val diff =
            diffOf(
                uids =
                    listOf(
                        uidDelta(10_001, totalMah = 5.0, bgMah = 4.5),
                        uidDelta(10_002, totalMah = 9.0, bgMah = 8.5),
                        uidDelta(10_003, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 6 * MIN), sensor(ACCEL_HANDLE, 13 * MIN))),
                    ),
                sleep = sleepStats(2 * HOUR, 720_000),
                components = mapOf("SCREEN" to 10.0, "MOBILE_RADIO" to 9.0),
            )
        val report = engine.evaluate(inputOf(diff, metricsOf(screenOffMs = 2 * HOUR)))

        assertEquals(report.insights.sortedWith(insightOrder), report.insights)
        // RED first, by rule priority: the vampires (9 mAh, 5 mAh) before the GPS leak, whose 360 000 ms is in
        // another unit; then the AMBER set in the same rule priority.
        val kinds = report.insights.map { it::class }
        assertEquals(
            listOf(
                NightVampire::class,
                NightVampire::class,
                SensorLeak::class,
                ModemDrain::class,
                AwakeViolation::class,
                SensorLeak::class,
            ),
            kinds,
        )
        assertEquals(listOf(10_002, 10_001), report.insights.filterIsInstance<NightVampire>().map { it.subjectUid })
    }
}
