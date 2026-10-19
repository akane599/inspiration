package com.akane.inspiration.telemetry.diff

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every [SnapshotStore] read that returns a [BatterySnapshot] is a full load in the Room store (the snapshot row
 * plus its child tables), so a resolution should load only `ref` and `end` and read other capture times through
 * the clock-only [SnapshotStore.latestClockByTrigger].
 */
class ReferenceResolverTest {
    @Test
    fun `last 24h loads only the reference and the end snapshot`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(snapshot(NOW - 20 * HOUR_MS, CaptureTrigger.APP_OPEN), snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.Last24h, NOW)!!

            assertEquals(NOW - 1_000L, resolved.now.clock.wallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `since unplugged without history reads the disconnect time without loading that snapshot`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(
                        snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED),
                        snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN),
                    ),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!

            assertEquals(NOW - 5 * HOUR_MS, resolved.boundaryFromWallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `screen off without history reads both capture times without loading them`() =
        runTest {
            val store =
                LoadCountingStore(
                    listOf(
                        snapshot(NOW - 3 * HOUR_MS, CaptureTrigger.SCREEN_OFF),
                        snapshot(NOW - 2 * HOUR_MS, CaptureTrigger.SCREEN_ON),
                        snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN),
                    ),
                )

            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceScreenOff, NOW, emptyList())!!

            assertEquals(NOW - 3 * HOUR_MS, resolved.boundaryFromWallMs)
            assertEquals(NOW - 2 * HOUR_MS, resolved.now.clock.wallMs)
            assertEquals(listOf("lastAtOrBefore", "firstAtOrAfter"), store.loads)
        }

    @Test
    fun `a boundary past now still needs a snapshot at or before now`() =
        runTest {
            val slice = ReferenceKind.TimeSlice(NOW - HOUR_MS, NOW + HOUR_MS)
            val after = snapshot(NOW + 1_000L, CaptureTrigger.APP_OPEN)

            assertNull(ReferenceResolver(LoadCountingStore(listOf(after))).resolve(slice, NOW, emptyList()))

            val before = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)
            val resolved = ReferenceResolver(LoadCountingStore(listOf(before, after))).resolve(slice, NOW, emptyList())!!
            assertEquals(before, resolved.ref)
            assertEquals(after, resolved.now)
        }

    @Test
    fun `the end snapshot alone after the boundary is never paired with itself`() =
        runTest {
            val end = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)
            val resolver = ReferenceResolver(LoadCountingStore(listOf(snapshot(NOW - 30 * HOUR_MS, CaptureTrigger.APP_OPEN), end)))

            assertNull(resolver.resolve(ReferenceKind.Last24h, NOW, emptyList()))
        }

    @Test
    fun `a full end falls back to the earlier light unplug anchor without per-uid data`() =
        runTest {
            val unplug = snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)
            val end = snapshot(NOW - 1_000L, CaptureTrigger.APP_OPEN)

            val resolved =
                ReferenceResolver(
                    LoadCountingStore(listOf(unplug, end)),
                ).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!

            assertEquals(unplug, resolved.ref)
            assertEquals(end, resolved.now)
            assertFalse(resolved.perUidAvailable)
            assertEquals(unplug.clock.wallMs, resolved.coveredFromWallMs)
        }

    @Test
    fun `light only snapshots resolve against an earlier light row or not at all`() =
        runTest {
            val unplug = snapshot(NOW - 5 * HOUR_MS, CaptureTrigger.POWER_DISCONNECTED, SnapshotKind.LIGHT)

            assertNull(ReferenceResolver(LoadCountingStore(listOf(unplug))).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList()))

            val screenOn = snapshot(NOW - HOUR_MS, CaptureTrigger.SCREEN_ON, SnapshotKind.LIGHT)
            val resolved =
                ReferenceResolver(LoadCountingStore(listOf(unplug, screenOn))).resolve(ReferenceKind.SinceUnplugged, NOW, emptyList())!!
            assertEquals(unplug, resolved.ref)
            assertEquals(screenOn, resolved.now)
            assertFalse(resolved.perUidAvailable)
        }

    private fun snapshot(
        wallMs: Long,
        trigger: CaptureTrigger,
        kind: SnapshotKind = SnapshotKind.FULL,
    ) = BatterySnapshot(
        id = wallMs,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wallMs, wallMs, wallMs, 1, 10L, null),
        battery = BatteryState(60, false, "discharging", 300, 4_000, -200, 1_000_000L, null),
        system = null,
        uids = emptyList(),
        deviceComponentsMah = emptyMap(),
        kernelWakelocks = emptyList(),
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
        availability = emptyMap(),
        truncatedUids = 0,
    )

    /** Records every read that returns a whole snapshot; [latestClockByTrigger] reads only a clock, like Room's. */
    private class LoadCountingStore(
        private val snapshots: List<BatterySnapshot>,
    ) : SnapshotStore {
        val loads = mutableListOf<String>()

        private fun <T : BatterySnapshot?> loaded(
            name: String,
            result: T,
        ): T {
            if (result != null) loads += name
            return result
        }

        override suspend fun byId(id: Long): BatterySnapshot? = loaded("byId", snapshots.firstOrNull { it.id == id })

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? =
            loaded("latest", snapshots.filter { kind == null || it.kind == kind }.maxByOrNull { it.clock.wallMs })

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? =
            loaded(
                "firstAtOrAfter",
                snapshots
                    .filter {
                        it.clock.wallMs >= wallMs && (kind == null || it.kind == kind) &&
                            (statsStartMs == null || it.clock.statsStartMs == statsStartMs) &&
                            (bootCount == null || it.clock.bootCount == bootCount)
                    }.minByOrNull { it.clock.wallMs },
            )

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? =
            loaded(
                "lastAtOrBefore",
                snapshots.filter { it.clock.wallMs <= wallMs && (kind == null || it.kind == kind) }.maxByOrNull { it.clock.wallMs },
            )

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
            loaded("latestByTrigger", snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs })

        override suspend fun latestClockByTrigger(trigger: CaptureTrigger): SnapshotClock? =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }?.clock

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

        override suspend fun insert(snapshot: BatterySnapshot): Long = error("unused")

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = snapshots.filter { it.clock.wallMs in fromWallMs..toWallMs }

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) = Unit

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = emptyList()

        override suspend fun historyCursor(): Pair<Long, Long>? = null

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) = Unit

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = error("unused")

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = emptyList()

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) = Unit

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = error("unused")

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) = Unit

        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private companion object {
        const val HOUR_MS = 60L * 60_000L
        const val NOW = 1_790_000_000_000L
    }
}
