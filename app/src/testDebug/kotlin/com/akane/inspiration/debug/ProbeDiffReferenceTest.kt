package com.akane.inspiration.debug

import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** PROBE_DIFF / PROBE_INSIGHTS diff the reference they report, never a silent latest-two stand-in (SQ-58). */
class ProbeDiffReferenceTest {
    /** Two FULL captures an hour apart on AC power: no unplug in the history or among the captures. */
    private val store = SnapshotsStore(listOf(snapshot(1, minutes(60)), snapshot(2, minutes(120))))

    @Test
    fun `a requested reference with no boundary is unavailable, not the latest two`() =
        runTest {
            assertEquals(ProbePair.Unavailable("no_reference"), store.resolveProbePair("since_unplugged"))
        }

    @Test
    fun `an unknown reference kind is unavailable, not the latest two`() =
        runTest {
            assertEquals(ProbePair.Unavailable("unknown_reference_kind"), store.resolveProbePair("since_lunch"))
        }

    @Test
    fun `no requested reference diffs the latest two`() =
        runTest {
            val pair = store.resolveProbePair(null)

            assertTrue("$pair", pair is ProbePair.Found)
            val input = (pair as ProbePair.Found).input
            assertEquals("latest_two", input.reference)
            assertEquals(1L, input.ref.id)
            assertEquals(2L, input.now.id)
        }

    @Test
    fun `a resolved reference reports the kind it resolved`() =
        runTest {
            val pair = store.resolveProbePair("last_24h")

            assertTrue("$pair", pair is ProbePair.Found)
            val input = (pair as ProbePair.Found).input
            assertEquals("last_24h", input.reference)
            assertEquals(minutes(60), input.coveredFromWallMs)
            assertEquals(minutes(120), input.coveredToWallMs)
        }

    @Test
    fun `an empty store has no snapshots`() =
        runTest {
            assertEquals(ProbePair.Unavailable("no_snapshots"), SnapshotsStore(emptyList()).resolveProbePair("since_unplugged"))
        }

    /** Snapshots in memory with an empty history; every other store member throws, so none is leaned on unnoticed. */
    private class SnapshotsStore(
        private val snapshots: List<BatterySnapshot>,
    ) : SnapshotStore by unsupported() {
        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? =
            snapshots.filter { kind == null || it.kind == kind }.maxByOrNull { it.clock.wallMs }

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? = snapshots.filter { it.clock.wallMs <= wallMs && (kind == null || it.kind == kind) }.maxByOrNull { it.clock.wallMs }

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? =
            snapshots
                .filter { it.clock.wallMs >= wallMs && (kind == null || it.kind == kind) }
                .filter { bootCount == null || it.clock.bootCount == bootCount }
                .minByOrNull { it.clock.wallMs }

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? =
            snapshots.filter { it.trigger == trigger }.maxByOrNull { it.clock.wallMs }

        override suspend fun latestClockByTrigger(trigger: CaptureTrigger): SnapshotClock? =
            super<SnapshotStore>.latestClockByTrigger(trigger)

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = emptyList()

        override suspend fun lastHistoryOfEachKindBefore(wallMs: Long): List<HistoryEvent> =
            super<SnapshotStore>.lastHistoryOfEachKindBefore(wallMs)
    }

    private companion object {
        fun unsupported(): SnapshotStore =
            Proxy.newProxyInstance(
                SnapshotStore::class.java.classLoader,
                arrayOf(SnapshotStore::class.java),
            ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as SnapshotStore

        fun minutes(value: Long): Long = value * 60_000L

        fun snapshot(
            id: Long,
            wallMs: Long,
        ) = BatterySnapshot(
            id = id,
            kind = SnapshotKind.FULL,
            trigger = CaptureTrigger.APP_OPEN,
            clock = SnapshotClock(wallMs, wallMs, wallMs, bootCount = 1, statsStartMs = null, statsDurationMs = null),
            battery = BatteryState(88, true, null, null, null, null, null, null),
            system = null,
            uids = emptyList(),
            deviceComponentsMah = emptyMap(),
            kernelWakelocks = emptyList(),
            wakeupReasons = emptyList(),
            cpuFreq = emptyList(),
            availability = emptyMap(),
            truncatedUids = 0,
        )
    }
}
