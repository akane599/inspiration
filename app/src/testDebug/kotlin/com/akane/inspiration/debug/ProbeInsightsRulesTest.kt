package com.akane.inspiration.debug

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.calibration.CalibrationInput
import com.akane.inspiration.telemetry.calibration.CalibrationLocks
import com.akane.inspiration.telemetry.calibration.CalibrationStart
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** PROBE_INSIGHTS and PROBE follow the app's interval and calibration rules (SQ-3). */
class ProbeInsightsRulesTest {
    @Test
    fun `a pair with no wall time between its snapshots is not analyzed`() =
        runTest {
            val store = HistoryStore(staleTail, horizonWallMs = minutes(120))

            assertNull(store.probeIntervalMetrics(snapshot(minutes(60)), minutes(60), minutes(60)))
            assertNull(store.probeIntervalMetrics(snapshot(minutes(60)), minutes(60), minutes(59)))
            assertTrue(store.reads.isEmpty())
        }

    @Test
    fun `time past the stored history horizon is unknown`() =
        runTest {
            val store = HistoryStore(staleTail, horizonWallMs = minutes(120))

            val metrics = requireNotNull(store.probeIntervalMetrics(snapshot(minutes(480)), 0, minutes(480)))

            assertEquals(minutes(120), metrics.screenOffMs)
            assertEquals(minutes(360), metrics.unknownMs)
        }

    @Test
    fun `an endpoint that ingested history is its own horizon`() =
        runTest {
            val store = HistoryStore(staleTail, horizonWallMs = minutes(120))

            val metrics =
                requireNotNull(store.probeIntervalMetrics(snapshot(minutes(480), ingested = true), 0, minutes(480)))

            assertEquals(minutes(480), metrics.screenOffMs)
            assertEquals(0L, metrics.unknownMs)
        }

    @Test
    fun `only the interval's own history is read`() =
        runTest {
            val store = HistoryStore(staleTail + event(600, HistoryKind.SCREEN_ON, 80), horizonWallMs = null)

            store.probeIntervalMetrics(snapshot(minutes(200)), minutes(30), minutes(200))

            assertTrue(store.reads.isNotEmpty())
            assertTrue("reads ${store.reads}", store.reads.all { (_, to) -> to <= minutes(200) })
        }

    @Test
    fun `calibration starts from the persisted locks, not the one reading`() {
        // Unplugged and discharging, read the documented way round (negative current).
        val reading =
            CalibrationInput(
                sysfsCurrentRaw = -800_000L,
                sysfsVoltageRaw = 4_000_000L,
                bmCurrentNowUa = -800_000,
                bmVoltageMv = 4_000,
                isCharging = false,
                plugged = false,
            )

        assertTrue(probeCalibration(null, reading).dischargeIsNegative)
        // A kernel whose earlier readings locked discharge as positive keeps that lock against one contrary reading.
        val settled = CalibrationStart(locks = CalibrationLocks(dischargePositive = true))
        assertFalse(probeCalibration(settled, reading).dischargeIsNegative)
    }

    /** Unplugged with the screen off at 90% from minute 0, two level drops, nothing stored after minute 100. */
    private val staleTail =
        listOf(
            event(0, HistoryKind.UNPLUGGED, 90),
            event(0, HistoryKind.SCREEN_OFF, 90),
            event(60, HistoryKind.LEVEL, 89),
            event(100, HistoryKind.LEVEL, 88),
        )

    /** An in-memory history; every other store member throws, so the probe cannot lean on one unnoticed. */
    private class HistoryStore(
        private val events: List<HistoryEvent>,
        private val horizonWallMs: Long?,
    ) : SnapshotStore by unsupported() {
        val reads = mutableListOf<Pair<Long, Long>>()

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> {
            reads += fromWallMs to toWallMs
            return events.filter { it.wallMs in fromWallMs..toWallMs }
        }

        override suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
            super<SnapshotStore>.lastHistoryOfEachKindBefore(wallMs)

        override suspend fun newestHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
            super<SnapshotStore>.newestHistoryOfEachKindBefore(wallMs)

        override suspend fun historyHorizonWallMs(): Long? = horizonWallMs
    }

    private companion object {
        fun unsupported(): SnapshotStore =
            Proxy.newProxyInstance(
                SnapshotStore::class.java.classLoader,
                arrayOf(SnapshotStore::class.java),
            ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as SnapshotStore

        fun minutes(value: Long): Long = value * 60_000L

        fun event(
            minute: Long,
            kind: Int,
            levelPct: Int,
        ) = HistoryEvent(
            historyTimeMs = minutes(minute),
            wallMs = minutes(minute),
            kind = kind,
            levelPct = levelPct,
            chargeUah = -1L,
            temperatureDeciC = 250,
            voltageMv = 4000,
            plugType = 0,
        )

        fun snapshot(
            wallMs: Long,
            ingested: Boolean = false,
        ) = BatterySnapshot(
            id = 1,
            kind = if (ingested) SnapshotKind.FULL else SnapshotKind.LIGHT,
            trigger = if (ingested) CaptureTrigger.APP_OPEN else CaptureTrigger.SCREEN_OFF,
            clock = SnapshotClock(wallMs, wallMs, wallMs, bootCount = 1, statsStartMs = null, statsDurationMs = null),
            battery = BatteryState(88, false, null, null, null, null, null, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = if (ingested) mapOf(HISTORY_SOURCE to SourceAvailability(true, null)) else emptyMap(),
            truncatedUids = 0,
        )
    }
}
