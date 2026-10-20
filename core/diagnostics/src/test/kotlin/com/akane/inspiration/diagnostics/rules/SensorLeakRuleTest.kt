package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.SensorKind
import com.akane.inspiration.diagnostics.model.SensorLeak
import com.akane.inspiration.diagnostics.model.Severity
import com.akane.inspiration.diagnostics.sensor.GPS_SENSOR_HANDLE
import com.akane.inspiration.telemetry.diff.ReferenceResolver.Companion.CAPTURE_SLACK_MS
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.interval.IntervalMetrics
import com.akane.inspiration.telemetry.snapshot.UidDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLeakRuleTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    private fun leaks(
        vararg uids: UidDelta,
        metrics: IntervalMetrics? = metricsOf(screenOffMs = 2 * HOUR),
    ) = engine.evaluate(inputOf(diffOf(uids = uids.toList()), metrics)).insights.filterIsInstance<SensorLeak>()

    private fun withSensor(handle: Int, totalMs: Long, uid: Int = 10_001) =
        uidDelta(uid, sensors = listOf(sensor(handle, totalMs)))

    @Test
    fun gpsFiresRedAtFiveMinutes() {
        assertTrue(leaks(withSensor(GPS_SENSOR_HANDLE, 5 * MIN - 1)).isEmpty())
        val hit = leaks(withSensor(GPS_SENSOR_HANDLE, 5 * MIN)).single()
        assertEquals(Severity.RED, hit.severity)
        assertEquals(SensorKind.GPS, hit.sensor)
        assertEquals(5 * MIN, hit.durationMs)
        assertEquals(10_001, hit.subjectUid)
        assertEquals("pkg.10001", hit.packageName)
    }

    @Test
    fun motionSensorThresholdIsTenPercentOfScreenOffWhenLarger() {
        // 2 h screen-off: 10 % = 12 min.
        assertTrue(leaks(withSensor(ACCEL_HANDLE, 12 * MIN - 1)).isEmpty())
        val accel = leaks(withSensor(ACCEL_HANDLE, 12 * MIN)).single()
        assertEquals(Severity.AMBER, accel.severity)
        assertEquals(SensorKind.ACCELEROMETER, accel.sensor)
        assertTrue(leaks(withSensor(GYRO_HANDLE, 12 * MIN - 1)).isEmpty())
        assertEquals(SensorKind.GYROSCOPE, leaks(withSensor(GYRO_HANDLE, 12 * MIN)).single().sensor)
    }

    @Test
    fun motionSensorThresholdIsTenMinutesWhenTenPercentIsSmaller() {
        // 1 h screen-off: 10 % = 6 min, so the 10 min floor applies.
        val metrics = metricsOf(screenOffMs = HOUR)
        assertTrue(leaks(withSensor(ACCEL_HANDLE, 10 * MIN - 1), metrics = metrics).isEmpty())
        assertEquals(1, leaks(withSensor(ACCEL_HANDLE, 10 * MIN), metrics = metrics).size)
    }

    @Test
    fun otherSensorsNeverFire() {
        assertTrue(leaks(withSensor(OTHER_HANDLE, 10 * HOUR)).isEmpty())
    }

    @Test
    fun calibratedAndUncalibratedVariantsOfOneKindReportOnceWithTheLongest() {
        val uid = uidDelta(
            10_001,
            sensors = listOf(sensor(ACCEL_HANDLE, 13 * MIN), sensor(ACCEL_UNCAL_HANDLE, 15 * MIN)),
        )
        val hit = leaks(uid).single()
        assertEquals(15 * MIN, hit.durationMs)
        // Two 8-minute variants are not summed into a leak.
        val split = uidDelta(
            10_001,
            sensors = listOf(sensor(ACCEL_HANDLE, 8 * MIN), sensor(ACCEL_UNCAL_HANDLE, 8 * MIN)),
        )
        assertTrue(leaks(split).isEmpty())
    }

    @Test
    fun gpsAndMotionOnTheSameUidBothFire() {
        val uid = uidDelta(10_001, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 6 * MIN), sensor(GYRO_HANDLE, 20 * MIN)))
        assertEquals(setOf(SensorKind.GPS, SensorKind.GYROSCOPE), leaks(uid).map { it.sensor }.toSet())
    }

    @Test
    fun systemAndInvalidUidsAreSkippedAndInvalidAreCounted() {
        val report = engine.evaluate(
            inputOf(
                diffOf(
                    uids = listOf(
                        withSensor(GPS_SENSOR_HANDLE, 10 * MIN, uid = 1_000),
                        uidDelta(10_002, valid = false, sensors = listOf(sensor(GPS_SENSOR_HANDLE, 10 * MIN))),
                        withSensor(GPS_SENSOR_HANDLE, 10 * MIN, uid = 10_003),
                    ),
                ),
                metricsOf(),
            ),
        )
        assertEquals(listOf(10_003), report.insights.filterIsInstance<SensorLeak>().map { it.subjectUid })
        assertEquals(1, report.skippedInvalidUids)
    }

    /** Audit B-16: a platform UID in another user (location in user 10 is 1001021) is not an app. */
    @Test
    fun perUserPlatformUidsAreSkipped() {
        val leaks =
            leaks(
                withSensor(GPS_SENSOR_HANDLE, 10 * MIN, uid = 1_001_021),
                withSensor(GPS_SENSOR_HANDLE, 10 * MIN, uid = 1_010_003),
            )
        assertEquals(listOf(1_010_003), leaks.map { it.subjectUid })
    }

    @Test
    fun notScreenOffOnlyIntervalsAreNotEvaluable() {
        val uid = withSensor(GPS_SENSOR_HANDLE, 10 * MIN)
        val cases = listOf(
            null,
            // Screen-on time past the resolver's capture slack: a mixed interval, not a screen-off one.
            metricsOf(screenOnMs = CAPTURE_SLACK_MS + 1),
            metricsOf(screenOnMs = HOUR),
            // Unknown time stays strict, also together with screen-on lag inside the slack.
            metricsOf(unknownMs = 1),
            metricsOf(screenOnMs = 2_000, unknownMs = 1),
            metricsOf(screenOffMs = 30 * MIN - 1),
            metricsOf(screenOnMs = 2_000, screenOffMs = 30 * MIN - 1),
        )
        for (metrics in cases) {
            val report = engine.evaluate(inputOf(diffOf(uids = listOf(uid)), metrics))
            assertEquals("$metrics", "not_screen_off_interval", report.notEvaluable[RuleId.SENSOR_LEAK])
            assertTrue(report.insights.none { it is SensorLeak })
        }
        // The 30 min boundary itself is eligible.
        val eligible = engine.evaluate(inputOf(diffOf(uids = listOf(uid)), metricsOf(screenOffMs = 30 * MIN)))
        assertTrue(RuleId.SENSOR_LEAK !in eligible.notEvaluable)
    }

    /**
     * Audit R-03: a screen-off period ends at the screen-on broadcast's capture, up to the resolver's
     * [CAPTURE_SLACK_MS] after the history SCREEN_ON, so that much screen-on time still counts as screen-off only.
     */
    @Test
    fun screenOnLagWithinTheCaptureSlackIsStillScreenOffOnly() {
        val uid = withSensor(GPS_SENSOR_HANDLE, 10 * MIN)
        for (lagMs in listOf(1L, 400L, 3_000L, CAPTURE_SLACK_MS)) {
            val report = engine.evaluate(inputOf(diffOf(uids = listOf(uid)), metricsOf(screenOnMs = lagMs, screenOffMs = 8 * HOUR)))
            assertTrue("$lagMs ms: ${report.notEvaluable}", RuleId.SENSOR_LEAK !in report.notEvaluable)
            assertEquals(listOf(SensorKind.GPS), report.insights.filterIsInstance<SensorLeak>().map { it.sensor })
        }
    }

    @Test
    fun motionThresholdScalesWithScreenOffTimeNotTheIntervalLength() {
        // 10% of 3 h screen-off is 18 min; 10% of the whole interval would be 1 s more with the 10 s of lag.
        val metrics = metricsOf(screenOnMs = CAPTURE_SLACK_MS, screenOffMs = 3 * HOUR)
        assertEquals(SensorKind.ACCELEROMETER, leaks(withSensor(ACCEL_HANDLE, 18 * MIN), metrics = metrics).single().sensor)
        assertTrue(leaks(withSensor(ACCEL_HANDLE, 18 * MIN - 1), metrics = metrics).isEmpty())
    }

    @Test
    fun noSensorsOnAnyUidIsSourceAbsent() {
        val report = engine.evaluate(inputOf(diffOf(uids = listOf(uidDelta(10_001))), metricsOf()))
        assertEquals("source_absent", report.notEvaluable[RuleId.SENSOR_LEAK])
    }

    @Test
    fun sensorsOnlyOnAnInvalidUidStillMeanTheSourceIsPresent() {
        val report = engine.evaluate(
            inputOf(
                diffOf(uids = listOf(uidDelta(10_001, valid = false, sensors = listOf(UidSensor(1, 1, 0, 1))))),
                metricsOf(),
            ),
        )
        assertTrue(RuleId.SENSOR_LEAK !in report.notEvaluable)
        assertTrue(report.insights.isEmpty())
    }

    @Test
    fun gatesOrderStatsThenPerUidThenInterval() {
        val diff = diffOf(uids = listOf(withSensor(GPS_SENSOR_HANDLE, 10 * MIN)), batteryStats = false)
        assertEquals(
            "battery_stats_invalid",
            engine.evaluate(inputOf(diff, metricsOf(), perUidAvailable = false)).notEvaluable[RuleId.SENSOR_LEAK],
        )
        val light = diffOf(uids = listOf(withSensor(GPS_SENSOR_HANDLE, 10 * MIN)))
        assertEquals(
            "per_uid_unavailable",
            engine.evaluate(inputOf(light, null, perUidAvailable = false)).notEvaluable[RuleId.SENSOR_LEAK],
        )
    }
}
