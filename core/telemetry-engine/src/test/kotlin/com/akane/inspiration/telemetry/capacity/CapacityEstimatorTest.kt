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
    fun `extractor keeps only completed plugged segments rising at least forty points`() {
        val segments = ChargeSegmentExtractor.fromHistory(
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
        val verdict = CapacityEstimator.evaluate(
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
        val tooSmall = CapacityEstimator.evaluate(
            validSegment(deltaChargeUah = 499_000),
            kernel(),
            emptyList(),
            consecutiveRejections = 0,
        )
        val tooLarge = CapacityEstimator.evaluate(
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
        val verdict = CapacityEstimator.evaluate(
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
        val verdict = CapacityEstimator.evaluate(
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
        val estimate = CapacityEstimator.combine(
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
        val verdict = CapacityEstimator.evaluate(
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
        val estimate = CapacityEstimator.combine(
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
    ) = HistoryEvent(
        historyTimeMs = timeMs,
        wallMs = timeMs + 10_000L,
        kind = kind,
        levelPct = levelPct,
        chargeUah = chargeUah,
        temperatureDeciC = 250,
        voltageMv = 4_000,
        plugType = if (kind == HistoryKind.UNPLUGGED) 0 else 1,
    )
}
