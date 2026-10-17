package com.akane.inspiration.diagnostics.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InsightOrderTest {
    private fun vampire(uid: Int, mah: Double, severity: Severity = Severity.RED, pkg: String? = "p$uid") =
        NightVampire(severity, uid, pkg, mah, bgShare = 0.9, fgActivityMs = 0)

    private fun awake(ratio: Double, severity: Severity) =
        AwakeViolation(severity, ratio, screenOffMs = 3_600_000, CulpritScope.SCREEN_OFF_ONLY, emptyList())

    private fun modem(mah: Double) =
        ModemDrain(Severity.AMBER, mah, 10.0, signalAvailable = false, signalBinPct = emptyList(), scanningMs = null, radioActiveMs = null)

    private fun leak(uid: Int, ms: Long, sensor: SensorKind = SensorKind.GPS, severity: Severity = Severity.RED) =
        SensorLeak(severity, uid, "p$uid", sensor, ms)

    @Test
    fun ruleAndSubjectUidAreFixedPerType() {
        assertEquals(RuleId.NIGHT_VAMPIRE, vampire(10_001, 1.0).rule)
        assertEquals(RuleId.AWAKE_VIOLATION, awake(0.1, Severity.AMBER).rule)
        assertEquals(RuleId.MODEM_DRAIN, modem(6.0).rule)
        assertEquals(RuleId.SENSOR_LEAK, leak(10_002, 1).rule)
        assertEquals(null, awake(0.1, Severity.AMBER).subjectUid)
        assertEquals(null, modem(6.0).subjectUid)
        assertEquals(10_001, vampire(10_001, 1.0).subjectUid)
    }

    @Test
    fun severityDescendingThenMagnitudeDescending() {
        val amberBig = vampire(10_001, 99.0, Severity.AMBER)
        val redSmall = vampire(10_002, 2.0, Severity.RED)
        val redBig = vampire(10_003, 5.0, Severity.RED)
        val redAwake = awake(0.30, Severity.RED)
        val redLeak = leak(10_004, 600_000)
        val sorted = listOf(amberBig, redSmall, redAwake, redBig, redLeak).sortedWith(insightOrder)
        // Red first; among reds the larger evidence number wins (leak ms > vampire mAh > awake ratio).
        assertEquals(listOf(redLeak, redBig, redSmall, redAwake, amberBig), sorted)
    }

    @Test
    fun magnitudeFieldPerRule() {
        val sorted = listOf(modem(5.0), modem(12.0), awake(0.1, Severity.AMBER)).sortedWith(insightOrder)
        assertEquals(12.0, (sorted[0] as ModemDrain).mobileRadioMah, 0.0)
        assertEquals(5.0, (sorted[1] as ModemDrain).mobileRadioMah, 0.0)
        assertEquals(RuleId.AWAKE_VIOLATION, sorted[2].rule)
    }

    @Test
    fun equalEvidenceFallsBackToUidThenName() {
        val a = leak(10_002, 300_000, SensorKind.GPS)
        val b = leak(10_001, 300_000, SensorKind.GPS)
        val c = leak(10_001, 300_000, SensorKind.GYROSCOPE, Severity.RED)
        assertEquals(listOf(b, c, a), listOf(a, c, b).sortedWith(insightOrder))
    }

    @Test
    fun nullSubjectUidSortsAfterUidWhenEvidenceTies() {
        val awakeTie = awake(5.0, Severity.RED)
        val vampireTie = vampire(10_001, 5.0)
        assertEquals(listOf(vampireTie, awakeTie), listOf(awakeTie, vampireTie).sortedWith(insightOrder))
    }
}
