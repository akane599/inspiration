package com.akane.inspiration.diagnostics.rules

import com.akane.inspiration.diagnostics.model.ModemDrain
import com.akane.inspiration.diagnostics.model.RuleId
import com.akane.inspiration.diagnostics.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModemDrainRuleTest {
    private val engine = DiagnosticRuleEngine(fakeCatalog)

    private fun report(
        radio: Double?,
        screen: Double?,
        bins: List<Long> = emptyList(),
        scanningMs: Long? = null,
        radioActiveMs: Long? = null,
    ) = engine.evaluate(
        inputOf(
            diffOf(
                components = listOfNotNull(radio?.let { "MOBILE_RADIO" to it }, screen?.let { "SCREEN" to it }).toMap(),
                bins = bins,
                scanningMs = scanningMs,
                radioActiveMs = radioActiveMs,
            ),
        ),
    )

    private fun drains(radio: Double?, screen: Double?) = report(radio, screen).insights.filterIsInstance<ModemDrain>()

    @Test
    fun radioMustExceedThirtyFivePercentOfScreenStrictly() {
        assertTrue(drains(radio = 35.0, screen = 100.0).isEmpty())
        val hit = drains(radio = 35.01, screen = 100.0).single()
        assertEquals(Severity.AMBER, hit.severity)
        assertEquals(35.01, hit.mobileRadioMah, 0.0)
        assertEquals(100.0, hit.screenMah, 0.0)
    }

    @Test
    fun radioMustBeAtLeastFiveMah() {
        assertTrue(drains(radio = 4.99, screen = 10.0).isEmpty())
        assertEquals(1, drains(radio = 5.0, screen = 10.0).size)
    }

    @Test
    fun absentRadioComponentCountsAsZero() {
        val report = report(radio = null, screen = 10.0)
        assertTrue(report.insights.isEmpty())
        assertTrue(RuleId.MODEM_DRAIN !in report.notEvaluable)
    }

    @Test
    fun invalidComponentsAreNotEvaluable() {
        fun reason(invalid: Set<String>) = engine.evaluate(
            inputOf(diffOf(components = mapOf("MOBILE_RADIO" to 9.0, "SCREEN" to 10.0), invalidComponents = invalid)),
        ).notEvaluable[RuleId.MODEM_DRAIN]
        assertEquals("component_invalid", reason(setOf("MOBILE_RADIO")))
        assertEquals("component_invalid", reason(setOf("SCREEN")))
        assertEquals(null, reason(setOf("WIFI")))
    }

    @Test
    fun missingOrNonPositiveScreenPowerIsNotEvaluable() {
        assertEquals("screen_power_zero", report(radio = 9.0, screen = null).notEvaluable[RuleId.MODEM_DRAIN])
        assertEquals("screen_power_zero", report(radio = 9.0, screen = 0.0).notEvaluable[RuleId.MODEM_DRAIN])
        assertEquals("screen_power_zero", report(radio = 9.0, screen = -1.0).notEvaluable[RuleId.MODEM_DRAIN])
    }

    @Test
    fun invalidBatteryStatsIsNotEvaluableAndOutranksComponentGates() {
        val diff = diffOf(components = mapOf("MOBILE_RADIO" to 9.0), batteryStats = false)
        assertEquals("battery_stats_invalid", engine.evaluate(inputOf(diff)).notEvaluable[RuleId.MODEM_DRAIN])
    }

    @Test
    fun perUidAvailabilityDoesNotGateTheRule() {
        val diff = diffOf(components = mapOf("MOBILE_RADIO" to 9.0, "SCREEN" to 10.0))
        val report = engine.evaluate(inputOf(diff, perUidAvailable = false))
        assertEquals(1, report.insights.filterIsInstance<ModemDrain>().size)
        assertTrue(RuleId.MODEM_DRAIN !in report.notEvaluable)
    }

    @Test
    fun evidenceCarriesSignalBinsScanningAndRadioActive() {
        val hit = report(
            radio = 9.0,
            screen = 10.0,
            bins = listOf(100L, 200L, 300L, 400L, 0L),
            scanningMs = 1_234,
            radioActiveMs = 5_678,
        ).insights.filterIsInstance<ModemDrain>().single()
        assertTrue(hit.signalAvailable)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0, 0.0), hit.signalBinPct)
        assertEquals(1_234L, hit.scanningMs)
        assertEquals(5_678L, hit.radioActiveMs)
    }

    @Test
    fun emptyBinsMeanSignalUnavailableAndNoPercentages() {
        val hit = report(radio = 9.0, screen = 10.0).insights.filterIsInstance<ModemDrain>().single()
        assertFalse(hit.signalAvailable)
        assertTrue(hit.signalBinPct.isEmpty())
        assertEquals(null, hit.scanningMs)
        assertEquals(null, hit.radioActiveMs)
    }

    @Test
    fun allZeroBinsAreAvailableButHaveNoPercentages() {
        val hit = report(radio = 9.0, screen = 10.0, bins = listOf(0L, 0L, 0L, 0L, 0L))
            .insights.filterIsInstance<ModemDrain>().single()
        assertTrue(hit.signalAvailable)
        assertTrue(hit.signalBinPct.isEmpty())
    }
}
