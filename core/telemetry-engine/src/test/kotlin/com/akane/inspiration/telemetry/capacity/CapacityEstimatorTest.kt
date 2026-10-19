package com.akane.inspiration.telemetry.capacity

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.calibration.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityEstimatorTest {
    @Test
    fun `charge sessions never span a boot or a mid-charge reset`() {
        for (boundary in listOf(HistoryKind.RESET, HistoryKind.START, HistoryKind.SHUTDOWN)) {
            // The RESET here is recorded while still plugged and charging goes on afterwards.
            assertTrue(
                oneSessionSegments(
                    listOf(
                        historyEvent(0, HistoryKind.PLUGGED, 20, 1_000_000),
                        historyEvent(500, boundary, 40, 2_000_000),
                        historyEvent(700, HistoryKind.LEVEL, 60, 2_800_000),
                        historyEvent(1_000, HistoryKind.UNPLUGGED, 80, 3_700_000),
                    ),
                ).isEmpty(),
            )
        }
    }

    @Test
    fun `a reset record that carries the unplug ends the charge`() {
        // Walker shape: PLUGGED, LEVEL, RESET(unplugged), LEVEL - no UNPLUGGED is ever emitted.
        val segment =
            oneSessionSegments(
                listOf(
                    historyEvent(0, HistoryKind.PLUGGED, 30, 1_000_000),
                    historyEvent(500, HistoryKind.LEVEL, 95, 3_600_000),
                    historyEvent(1_000, HistoryKind.RESET, 95, 3_600_000, plugType = 0),
                    historyEvent(1_500, HistoryKind.LEVEL, 94, 3_560_000, plugType = 0),
                ),
            ).single()

        assertEquals(30, segment.startPct)
        assertEquals(95, segment.endPct)
        assertEquals(11_000L, segment.endWallMs)
        assertEquals(2_600_000L, segment.deltaChargeUah)
    }

    @Test
    fun `a plugged reset followed by an unplug at the same instant ends the charge`() {
        // Walker shape: PLUGGED, LEVEL, RESET(plugged), UNPLUGGED, all closing at one timestamp.
        val segment =
            oneSessionSegments(
                listOf(
                    historyEvent(0, HistoryKind.PLUGGED, 30, 1_000_000),
                    historyEvent(500, HistoryKind.LEVEL, 95, 3_600_000),
                    historyEvent(1_000, HistoryKind.RESET, 95, 3_600_000),
                    historyEvent(1_000, HistoryKind.SCREEN_ON, 95, 3_600_000),
                    historyEvent(1_000, HistoryKind.UNPLUGGED, 95, 3_600_000),
                ),
            ).single()

        assertEquals(95, segment.endPct)
        assertEquals(11_000L, segment.endWallMs)
    }

    @Test
    fun `a charge split across stats sessions is extracted once under its starting session`() {
        val oldSession =
            listOf(
                historyEvent(0, HistoryKind.PLUGGED, 30, 1_000_000),
                historyEvent(500, HistoryKind.LEVEL, 95, 3_600_000),
            )
        // The new session's history clock restarts below the old one's.
        val resetCarriesUnplug =
            listOf(
                historyEvent(100, HistoryKind.RESET, 95, 3_600_000, plugType = 0, wallMs = 20_000),
                historyEvent(200, HistoryKind.LEVEL, 94, 3_560_000, plugType = 0, wallMs = 20_100),
            )
        val resetThenUnplug =
            listOf(
                historyEvent(100, HistoryKind.RESET, 95, 3_600_000, wallMs = 20_000),
                historyEvent(100, HistoryKind.UNPLUGGED, 95, 3_600_000, wallMs = 20_000),
            )

        for (newSession in listOf(resetCarriesUnplug, resetThenUnplug)) {
            val (statsStartMs, segment) =
                ChargeSegmentExtractor
                    .fromSessions(
                        listOf(1_000L to oldSession, 2_000L to newSession),
                    ).single()

            assertEquals(1_000L, statsStartMs)
            assertEquals(10_000L, segment.startWallMs)
            assertEquals(20_000L, segment.endWallMs)
            assertEquals(65, segment.endPct - segment.startPct)
        }
    }

    @Test
    fun `a backward clock step mid charge keeps the charge under the label it started in`() {
        // BatteryStatsImpl shifts its reported start by every wall-clock step, so the continuing session is re-paged
        // under a label one step smaller: the newer run has the smaller label. History time runs on.
        val stepMs = 60_000L
        val beforeStep =
            listOf(
                historyEvent(0, HistoryKind.PLUGGED, 30, 1_000_000),
                historyEvent(1_800_000, HistoryKind.LEVEL, 60, 2_200_000),
            )
        val afterStep =
            listOf(
                historyEvent(3_000_000, HistoryKind.LEVEL, 90, 3_400_000, wallMs = 3_010_000 - stepMs),
                historyEvent(3_600_000, HistoryKind.UNPLUGGED, 95, 3_600_000, wallMs = 3_610_000 - stepMs),
            )

        val (statsStartMs, segment) =
            ChargeSegmentExtractor
                .fromSessions(
                    listOf(1_000_000L to beforeStep, 1_000_000L - stepMs to afterStep),
                ).single()

        assertEquals(1_000_000L, statsStartMs)
        assertEquals(10_000L to 3_610_000L - stepMs, segment.startWallMs to segment.endWallMs)
        assertEquals(65, segment.endPct - segment.startPct)
        assertEquals(2_600_000L, segment.deltaChargeUah)
    }

    @Test
    fun `extractor keeps only completed plugged segments rising at least forty points`() {
        val segments =
            oneSessionSegments(
                listOf(
                    historyEvent(timeMs = 0, kind = HistoryKind.PLUGGED, levelPct = 40, chargeUah = 1_000_000),
                    historyEvent(timeMs = 1_000, kind = HistoryKind.LEVEL, levelPct = 80, chargeUah = 2_000_000),
                    historyEvent(timeMs = 2_000, kind = HistoryKind.UNPLUGGED, levelPct = 80, chargeUah = 2_000_000),
                    historyEvent(timeMs = 3_000, kind = HistoryKind.PLUGGED, levelPct = 30, chargeUah = 1_000_000),
                    historyEvent(timeMs = 4_000, kind = HistoryKind.UNPLUGGED, levelPct = 69, chargeUah = 1_900_000),
                ),
            )

        assertEquals(1, segments.size)
        assertEquals(40, segments.single().endPct - segments.single().startPct)
        assertEquals(1_000_000L, segments.single().deltaChargeUah)
    }

    @Test
    fun `evaluate rejects a segment hotter than forty five degrees Celsius`() {
        val verdict =
            CapacityEstimator.evaluate(
                segment = validSegment(maxTemperatureDeciC = 451),
                kernel = kernel(),
                recentAcceptedChronological = emptyList(),
                consecutiveRejections = 0,
            )

        assertFalse(verdict.accepted)
        assertEquals("temperature_too_high", verdict.rejectReason)
    }

    @Test
    fun `evaluate rejects estimates outside half to one point two times design capacity`() {
        val tooSmall =
            CapacityEstimator.evaluate(
                validSegment(deltaChargeUah = 499_000),
                kernel(),
                emptyList(),
                consecutiveRejections = 0,
            )
        val tooLarge =
            CapacityEstimator.evaluate(
                validSegment(deltaChargeUah = 1_201_000),
                kernel(),
                emptyList(),
                consecutiveRejections = 0,
            )

        assertEquals("outside_design_bounds", tooSmall.rejectReason)
        assertEquals("outside_design_bounds", tooLarge.rejectReason)
    }

    @Test
    fun `evaluate applies median deviation only after three accepted samples`() {
        val verdict =
            CapacityEstimator.evaluate(
                validSegment(deltaChargeUah = 1_300_000),
                kernel(designMah = 1_500.0),
                recentAcceptedChronological = listOf(1_000.0, 1_000.0, 1_000.0),
                consecutiveRejections = 0,
            )

        assertFalse(verdict.accepted)
        assertEquals("median_deviation", verdict.rejectReason)
    }

    @Test
    fun `evaluate disables median rejection after three consecutive rejections`() {
        val verdict =
            CapacityEstimator.evaluate(
                validSegment(deltaChargeUah = 1_300_000),
                kernel(designMah = 1_500.0),
                recentAcceptedChronological = listOf(1_000.0, 1_000.0, 1_000.0),
                consecutiveRejections = 3,
            )

        assertTrue(verdict.accepted)
        assertEquals(1_300.0, verdict.estimateMah!!, 0.0)
    }

    @Test
    fun `combine uses chronological EMA and keeps kernel FCC as the primary display`() {
        val estimate =
            CapacityEstimator.combine(
                acceptedChronological = listOf(1_000.0, 2_000.0, 1_000.0),
                kernel = kernel(chargeFullMah = 2_000.0),
            )

        assertEquals(1_210.0, estimate.measuredMah!!, 0.001)
        assertEquals(2_000.0, estimate.kernelChargeFullMah!!, 0.0)
        assertEquals(3, estimate.sampleCount)
        assertEquals(Confidence.HIGH, estimate.confidence)
    }

    @Test
    fun `evaluate marks an estimate within one percent of kernel FCC as not independent`() {
        val verdict =
            CapacityEstimator.evaluate(
                validSegment(deltaChargeUah = 1_000_000),
                kernel(chargeFullMah = 1_005.0),
                emptyList(),
                consecutiveRejections = 0,
            )

        assertTrue(verdict.accepted)
        assertFalse(verdict.independent)
    }

    @Test
    fun `combine hides a measured result that is not independent from kernel FCC`() {
        val estimate =
            CapacityEstimator.combine(
                acceptedChronological = listOf(1_000.0),
                kernel = kernel(chargeFullMah = 1_005.0),
            )

        assertFalse(estimate.independent)
        assertNull(estimate.measuredMah)
    }

    @Test
    fun `trapezoid integration returns null when samples have a gap over five seconds`() {
        val result = Integrator.trapezoidMah(listOf(0L to 1_000.0, 5_001L to 1_000.0))

        assertNull(result)
    }

    @Test
    fun `integrated charge is normalized by the fraction of battery charged`() {
        val segment = validSegment().copy(startPct = 40, endPct = 80, deltaChargeUah = null, integratedMah = 1_000.0)
        val verdict = CapacityEstimator.evaluate(segment, kernel(designMah = 2_500.0), emptyList(), 0)

        assertTrue(verdict.accepted)
        assertEquals(2_500.0, verdict.estimateMah!!, 0.0)
    }

    /** The production [ChargeSegmentExtractor.fromSessions] over a single stats session. */
    private fun oneSessionSegments(events: List<HistoryEvent>): List<ChargeSegment> =
        ChargeSegmentExtractor.fromSessions(listOf(1_000L to events)).map { it.second }

    private fun validSegment(
        deltaChargeUah: Long = 1_000_000,
        maxTemperatureDeciC: Int? = 250,
    ) = ChargeSegment(
        startHistoryTimeMs = 0L,
        startWallMs = 0L,
        endWallMs = 1L,
        startPct = 0,
        endPct = 100,
        deltaChargeUah = deltaChargeUah,
        integratedMah = null,
        maxTemperatureDeciC = maxTemperatureDeciC,
    )

    private fun kernel(
        chargeFullMah: Double? = 1_500.0,
        designMah: Double? = 1_000.0,
    ) = KernelCapacity(
        chargeFullMah = chargeFullMah,
        designMah = designMah,
        cycleCount = 100,
    )

    private fun historyEvent(
        timeMs: Long,
        kind: Int,
        levelPct: Int,
        chargeUah: Long,
        plugType: Int = if (kind == HistoryKind.UNPLUGGED) 0 else 1,
        wallMs: Long = timeMs + 10_000L,
    ) = HistoryEvent(
        historyTimeMs = timeMs,
        wallMs = wallMs,
        kind = kind,
        levelPct = levelPct,
        chargeUah = chargeUah,
        temperatureDeciC = 250,
        voltageMv = 4_000,
        plugType = plugType,
    )
}
