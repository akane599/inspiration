package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.model.AwakeViolation
import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.model.insightOrder
import com.akane.inspiration.diagnostics.sensor.GPS_SENSOR_HANDLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRuleEngineTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    @Test
    fun missingDumpDoesNotInventForegroundInactivityOrSuppressValidEnergyRules() {
        val base = diffOf(
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
                RuleId.AWAKE_VIOLATION to "no_screen_off_time",
                RuleId.MODEM_DRAIN to "screen_power_zero",
                RuleId.SENSOR_LEAK to "not_screen_off_interval",
            ),
            report.notEvaluable,
        )
    }

    @Test
    fun invalidBatteryStatsGatesR1R3R4ButNotR2() {
        val report = engine.evaluate(
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
        val report = engine.evaluate(
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
        val report = engine.evaluate(
            inputOf(
                diffOf(uids = listOf(uidDelta(10_001, valid = false), uidDelta(10_002), uidDelta(10_003, valid = false))),
                perUidAvailable = false,
            ),
        )
        assertEquals(2, report.skippedInvalidUids)
    }

    @Test
    fun insightsAreSortedByInsightOrder() {
        val diff = diffOf(
            uids = listOf(
                uidDelta(10_001, totalMah = 5.0, bgMah = 4.5),
                uidDelta(10_002, totalMah = 9.0, bgMah = 8.5),
                uidDelta(10_003, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 6 * MIN), sensor(ACCEL_HANDLE, 13 * MIN))),
            ),
            sleep = sleepStats(2 * HOUR, 720_000),
            components = mapOf("SCREEN" to 10.0, "MOBILE_RADIO" to 9.0),
        )
        val report = engine.evaluate(inputOf(diff, metricsOf(screenOffMs = 2 * HOUR)))

        assertEquals(report.insights.sortedWith(insightOrder), report.insights)
        // RED first: GPS leak (360 000 ms) before the vampires (9 mAh, 5 mAh); then the AMBER set.
        val kinds = report.insights.map { it::class }
        assertEquals(
            listOf(
                SensorLeak::class,
                NightVampire::class,
                NightVampire::class,
                SensorLeak::class,
                ModemDrain::class,
                AwakeViolation::class,
            ),
            kinds,
        )
        assertEquals(listOf(10_002, 10_001), report.insights.filterIsInstance<NightVampire>().map { it.subjectUid })
    }
}
