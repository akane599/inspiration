package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDifferTest {
    @Test
    fun `same session computes device and uid deltas`() {
        val diff = SnapshotDiffer.diff(
            snapshot(
                elapsed = 1_000,
                uids = listOf(uid(totalMah = 2.0, components = mapOf("cpu" to 1.0))),
                components = mapOf("screen" to 1.0),
                level = 80,
                discharged = 2.0,
            ),
            snapshot(
                elapsed = 2_000,
                duration = 2_000,
                uids = listOf(uid(totalMah = 3.5, components = mapOf("cpu" to 1.5))),
                components = mapOf("screen" to 1.25),
                level = 77,
                discharged = 2.4,
            ),
        )

        assertTrue(diff.validity.batteryStats)
        assertEquals(1.5, diff.uids.single().totalMah, 0.0)
        assertEquals(0.5, diff.uids.single().componentsMah.getValue("cpu"), 0.0)
        assertEquals(0.25, diff.deviceComponentsMah.getValue("screen"), 0.0)
        assertEquals(3, diff.levelDropPct)
        assertEquals(0.4, diff.dischargedMah!!, 0.000_001)
    }

    @Test
    fun `reset reboot and session skew invalidate their counter sources`() {
        val ref = snapshot(elapsed = 1_000)
        assertFalse(SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, statsStart = 200_000)).validity.batteryStats)
        assertFalse(SnapshotDiffer.diff(ref, snapshot(elapsed = 2_000, boot = 2)).validity.kernelCounters)
        val skewed = snapshot(elapsed = 2_000, duration = 2_000, systemStart = 97_000)
        val diff = SnapshotDiffer.diff(ref, skewed)
        assertTrue(diff.validity.batteryStats)
        assertTrue(diff.validity.problems.contains("session_skew"))
    }

    @Test
    fun `negative component is reported without invalidating the battery stats session`() {
        val diff = SnapshotDiffer.diff(
            snapshot(
                elapsed = 1_000,
                uids = listOf(
                    uid(totalMah = 3.0, components = mapOf("SENSORS" to 0.053898)),
                    uid(uid = 10_002, totalMah = 1.0),
                ),
                components = mapOf("SENSORS" to 0.053898),
            ),
            snapshot(
                elapsed = 2_000,
                duration = 2_000,
                uids = listOf(
                    uid(totalMah = 3.0, components = mapOf("SENSORS" to 0.045978)),
                    uid(uid = 10_002, totalMah = 1.1),
                ),
                components = mapOf("SENSORS" to 0.045978),
            ),
        )

        assertTrue(diff.validity.batteryStats)
        assertFalse(diff.uids.first().valid)
        assertTrue(diff.uids.last().valid)
        assertEquals(setOf("SENSORS"), diff.validity.invalidDeviceComponents)
        assertEquals(-0.00792, diff.uids.first().componentsMah.getValue("SENSORS"), 0.000_001)
        assertEquals(0.1, diff.uids.last().totalMah, 0.000_001)
        assertTrue(diff.validity.problems.contains("negative_uid_component:10001:SENSORS"))
        assertTrue(diff.validity.problems.contains("negative_device_component:SENSORS"))
    }

    @Test
    fun `negative kernel wakelock is reported without invalidating kernel counters session`() {
        val diff = SnapshotDiffer.diff(
            snapshot(elapsed = 1_000, kernelWakelocks = listOf(KernelWakelock("sensor", 10, 1))),
            snapshot(elapsed = 2_000, duration = 2_000, kernelWakelocks = listOf(KernelWakelock("sensor", 9, 1))),
        )

        assertTrue(diff.validity.kernelCounters)
        assertEquals(setOf("sensor"), diff.validity.invalidKernelWakelocks)
        assertEquals(-1, diff.kernelWakelocks.single().durationMs)
        assertTrue(diff.validity.problems.contains("negative_kernel_wakelock:sensor"))
    }

    @Test
    fun `missing uid has a zero delta and package changes start a new application uid`() {
        val missing = SnapshotDiffer.diff(
            snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 3.0))),
            snapshot(elapsed = 2_000, duration = 2_000),
        )
        assertEquals(0.0, missing.uids.single().totalMah, 0.0)

        val changed = SnapshotDiffer.diff(
            snapshot(elapsed = 1_000, uids = listOf(uid(totalMah = 3.0, packageName = "old"))),
            snapshot(elapsed = 2_000, duration = 2_000, uids = listOf(uid(totalMah = 2.0, packageName = "new"))),
        )
        assertTrue(changed.uids.single().packageChanged)
        assertEquals(2.0, changed.uids.single().totalMah, 0.0)
    }

    @Test
    fun `light snapshots calculate deep sleep from elapsed and uptime`() {
        val diff = SnapshotDiffer.diff(
            snapshot(kind = SnapshotKind.LIGHT, elapsed = 1_000, uptime = 600),
            snapshot(kind = SnapshotKind.LIGHT, elapsed = 11_000, uptime = 2_600, duration = 2_000),
        )

        assertEquals(8_000, diff.sleep!!.deepSleepMs)
        assertNull(diff.sleep.screenOffMs)
    }

    @Test
    fun `resolver supports all kinds and reports uncovered gaps`() = runTest {
        val snapshots = listOf(
            snapshot(id = 1, wall = 1_000, elapsed = 1_000),
            snapshot(id = 2, wall = 2_000, elapsed = 2_000, duration = 2_000, trigger = CaptureTrigger.SCREEN_OFF),
            snapshot(id = 3, wall = 3_000, elapsed = 3_000, duration = 3_000, trigger = CaptureTrigger.SCREEN_ON),
            snapshot(id = 4, wall = 5_000, elapsed = 5_000, duration = 5_000),
        )
        val store = FakeStore(
            snapshots = snapshots,
            history = listOf(
                history(1_500, HistoryKind.UNPLUGGED),
                history(2_000, HistoryKind.SCREEN_OFF),
                history(3_000, HistoryKind.SCREEN_ON),
            ),
            markers = mapOf(7L to (1_000L to snapshots.first())),
        )
        val resolver = ReferenceResolver(store)

        assertNotNull(resolver.resolve(ReferenceKind.SinceUnplugged, 5_000))
        assertEquals(2_000, resolver.resolve(ReferenceKind.SinceScreenOff, 5_000)!!.boundaryFromWallMs)
        assertNotNull(resolver.resolve(ReferenceKind.ScreenOnSession, 5_000))
        assertNotNull(resolver.resolve(ReferenceKind.Last24h, 5_000))
        assertNotNull(resolver.resolve(ReferenceKind.CustomMarker(7), 5_000))
        val sliced = resolver.resolve(ReferenceKind.TimeSlice(1_500, 4_000), 5_000)!!
        assertEquals(1_500, sliced.gapMs)
        assertTrue(sliced.perUidAvailable)
    }

    private fun snapshot(
        id: Long = 0,
        kind: SnapshotKind = SnapshotKind.FULL,
        trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
        wall: Long = 1_000,
        elapsed: Long,
        uptime: Long = elapsed / 2,
        boot: Int = 1,
        statsStart: Long = 100_000,
        duration: Long = 1_000,
        systemStart: Long? = null,
        uids: List<UidSnapshot> = emptyList(),
        components: Map<String, Double> = emptyMap(),
        kernelWakelocks: List<KernelWakelock> = emptyList(),
        level: Int? = null,
        discharged: Double? = null,
    ) = BatterySnapshot(
        id = id,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wall, elapsed, uptime, boot, statsStart, duration),
        battery = BatteryState(level, null, null, null, null, null, null, discharged),
        system = systemStart?.let { start -> systemTimers(start) },
        uids = uids,
        deviceComponentsMah = components,
        kernelWakelocks = kernelWakelocks,
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
        availability = emptyMap(),
        truncatedUids = 0,
    )

    private fun uid(
        uid: Int = 10_001,
        totalMah: Double,
        packageName: String = "package",
        components: Map<String, Double> = emptyMap(),
    ) = UidSnapshot(
        uid = uid,
        packageName = packageName,
        totalMah = totalMah,
        fgMah = null,
        bgMah = null,
        fgsMah = null,
        cachedMah = null,
        componentsMah = components,
        cpuUserMs = 0,
        cpuSystemMs = 0,
        fgActivityMs = 0,
        fgServiceMs = 0,
        partialWakelocks = emptyList(),
        wakeupAlarms = emptyList(),
    )

    private fun systemTimers(start: Long) = com.akane.inspiration.telemetry.dump.SystemTimers(
        startClockTimeMs = start,
        totalRealtimeMs = 0,
        totalUptimeMs = 0,
        batteryRealtimeMs = 0,
        batteryUptimeMs = 0,
        screenOffRealtimeMs = 0,
        screenOffUptimeMs = 0,
        screenDozeMs = 0,
        screenOnMs = 0,
        interactiveMs = 0,
        deepDozeMs = 0,
        deepDozeCount = 0,
        lightDozeMs = 0,
        dischargeTotalMah = 0.0,
        dischargeScreenOffMah = 0.0,
        chargeTimeRemainingMs = null,
        dischargeTimeRemainingMs = null,
    )

    private fun history(wallMs: Long, kind: Int) = HistoryEvent(0, wallMs, kind, 0, -1, 0, 0, 0)

    private class FakeStore(
        private val snapshots: List<BatterySnapshot>,
        private val history: List<HistoryEvent>,
        private val markers: Map<Long, Pair<Long, BatterySnapshot>>,
    ) : SnapshotStore {
        override suspend fun insert(snapshot: BatterySnapshot): Long = error("unused")
        override suspend fun byId(id: Long): BatterySnapshot? = snapshots.firstOrNull { it.id == id }
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = snapshots.filterKind(kind).maxByOrNull { it.clock.wallMs }
        override suspend fun firstAtOrAfter(wallMs: Long, kind: SnapshotKind?, statsStartMs: Long?, bootCount: Int?) =
            snapshots.filterKind(kind).filter { it.clock.wallMs >= wallMs }
                .filter { statsStartMs == null || it.clock.statsStartMs == statsStartMs }
                .filter { bootCount == null || it.clock.bootCount == bootCount }
                .minByOrNull { it.clock.wallMs }
        override suspend fun lastAtOrBefore(wallMs: Long, kind: SnapshotKind?) = snapshots.filterKind(kind)
            .filter { it.clock.wallMs <= wallMs }.maxByOrNull { it.clock.wallMs }
        override suspend fun latestByTrigger(trigger: CaptureTrigger) = snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }
        override suspend fun insertHistory(events: List<HistoryEvent>, statsStartMs: Long) = Unit
        override suspend fun historyBetween(fromWallMs: Long, toWallMs: Long) = history.filter { it.wallMs in fromWallMs..toWallMs }
        override suspend fun historyCursor(): Pair<Long, Long>? = null
        override suspend fun setHistoryCursor(statsStartMs: Long, lastHistoryTimeMs: Long) = Unit
        override suspend fun addMarker(snapshotId: Long, label: String, wallMs: Long): Long = error("unused")
        override suspend fun marker(id: Long) = markers[id]
        override suspend fun insertSamples(samples: List<LiveReading>) = Unit
        override suspend fun upsertChargeSession(statsStartMs: Long, segment: ChargeSegment, verdict: SegmentVerdict) = Unit
        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()
        override suspend fun recordMiss(wallMs: Long, trigger: CaptureTrigger, reason: String) = Unit
        override suspend fun prune(nowWallMs: Long) = Unit

        private fun List<BatterySnapshot>.filterKind(kind: SnapshotKind?) = if (kind == null) this else filter { it.kind == kind }
    }
}
