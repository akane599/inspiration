package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.model.NightVampire
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightVampireRuleTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    private fun vampires(vararg uids: com.akane.inspiration.telemetry.snapshot.UidDelta, discharged: Double? = null) =
        engine.evaluate(inputOf(diffOf(uids = uids.toList(), dischargedMah = discharged)))
            .insights.filterIsInstance<NightVampire>()

    @Test
    fun bgShareFiresOnlyStrictlyAboveSixtyPercent() {
        // 3.0 / 5.0 is exactly 0.60: not above it.
        assertTrue(vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 3.0)).isEmpty())
        val hit = vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 3.01)).single()
        assertEquals(Severity.RED, hit.severity)
        assertEquals(10_001, hit.subjectUid)
        assertEquals("pkg.10001", hit.packageName)
        assertEquals(5.0, hit.totalMah, 0.0)
        assertEquals(3.01 / 5.0, hit.bgShare, 1e-12)
    }

    @Test
    fun cachedAddsToTheNumeratorAndNullCountsAsZero() {
        assertTrue(vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 1.0, cachedMah = 2.0)).isEmpty())
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 1.0, cachedMah = 2.1)).size)
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 5.0, bgMah = null, cachedMah = 3.5)).size)
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 3.5, cachedMah = null)).size)
    }

    @Test
    fun uidWithoutBgOrCachedIsSkipped() {
        assertTrue(vampires(uidDelta(10_001, totalMah = 50.0, bgMah = null, cachedMah = null, fgsMah = 50.0)).isEmpty())
    }

    @Test
    fun foregroundServiceEnergyIsNotInTheNumerator() {
        // bg 1.0 of 5.0 = 0.20 even though fgs is 4.0: a visible service is not a vampire.
        assertTrue(vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 1.0, fgsMah = 4.0)).isEmpty())
    }

    @Test
    fun foregroundActivityMustBeUnderSixtySeconds() {
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 4.5, fgActivityMs = 59_999)).size)
        assertTrue(vampires(uidDelta(10_001, totalMah = 5.0, bgMah = 4.5, fgActivityMs = 60_000)).isEmpty())
    }

    @Test
    fun absoluteFloorIsTwoMah() {
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 2.0, bgMah = 1.9)).size)
        assertTrue(vampires(uidDelta(10_001, totalMah = 1.99, bgMah = 1.9)).isEmpty())
    }

    @Test
    fun dischargeFloorIsOnePercentOfIntervalDischargeWhenLarger() {
        // 1 % of 1000 mAh = 10 mAh.
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 10.0, bgMah = 9.0), discharged = 1000.0).size)
        assertTrue(vampires(uidDelta(10_001, totalMah = 9.99, bgMah = 9.0), discharged = 1000.0).isEmpty())
        // Below 200 mAh the 2.0 floor still binds.
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 2.0, bgMah = 1.9), discharged = 100.0).size)
        // A null or negative discharge falls back to the absolute floor.
        assertEquals(1, vampires(uidDelta(10_001, totalMah = 2.0, bgMah = 1.9), discharged = -50.0).size)
    }

    @Test
    fun systemUidsAreExcluded() {
        assertTrue(vampires(uidDelta(9_999, totalMah = 5.0, bgMah = 4.5)).isEmpty())
        assertEquals(1, vampires(uidDelta(10_000, totalMah = 5.0, bgMah = 4.5)).size)
    }

    /** Audit B-16: a platform UID in another user (system in user 10 is 1001000) is not an app, whatever its raw value. */
    @Test
    fun perUserPlatformUidsAreExcluded() {
        assertTrue(vampires(uidDelta(1_001_000, totalMah = 5.0, bgMah = 4.5)).isEmpty())
        assertTrue(vampires(uidDelta(1_009_999, totalMah = 5.0, bgMah = 4.5)).isEmpty())
        assertEquals(1, vampires(uidDelta(1_010_000, totalMah = 5.0, bgMah = 4.5)).size)
    }

    @Test
    fun invalidUidsAreSkippedAndCounted() {
        val report = engine.evaluate(
            inputOf(
                diffOf(
                    uids = listOf(
                        uidDelta(10_001, totalMah = 5.0, bgMah = 4.5, valid = false),
                        uidDelta(10_002, totalMah = 5.0, bgMah = 4.5),
                        uidDelta(10_003, totalMah = 5.0, bgMah = 4.5, valid = false),
                    ),
                ),
            ),
        )
        assertEquals(listOf(10_002), report.insights.filterIsInstance<NightVampire>().map { it.subjectUid })
        assertEquals(2, report.skippedInvalidUids)
    }

    @Test
    fun notEvaluableReasons() {
        val uids = listOf(uidDelta(10_001, totalMah = 5.0, bgMah = 4.5))
        val statsInvalid = engine.evaluate(inputOf(diffOf(uids = uids, batteryStats = false)))
        assertEquals("battery_stats_invalid", statsInvalid.notEvaluable[RuleId.NIGHT_VAMPIRE])
        // The stats gate outranks the per-UID gate.
        val both = engine.evaluate(inputOf(diffOf(uids = uids, batteryStats = false), perUidAvailable = false))
        assertEquals("battery_stats_invalid", both.notEvaluable[RuleId.NIGHT_VAMPIRE])
        val light = engine.evaluate(inputOf(diffOf(uids = uids), perUidAvailable = false))
        assertEquals("per_uid_unavailable", light.notEvaluable[RuleId.NIGHT_VAMPIRE])
        assertTrue(light.insights.none { it is NightVampire })
    }

    @Test
    fun evaluableButNotFiringHasNoNotEvaluableEntry() {
        val report = engine.evaluate(inputOf(diffOf(uids = listOf(uidDelta(10_001, totalMah = 5.0, bgMah = 1.0)))))
        assertTrue(report.insights.isEmpty())
        assertTrue(RuleId.NIGHT_VAMPIRE !in report.notEvaluable)
    }
}
