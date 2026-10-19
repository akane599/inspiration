package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.SnapshotWakelockEntity
import com.akane.inspiration.database.ToolkitActionEntity
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.dump.KernelWakelock
import com.akane.inspiration.telemetry.dump.SystemTimers
import com.akane.inspiration.telemetry.dump.UidAlarm
import com.akane.inspiration.telemetry.dump.UidSensor
import com.akane.inspiration.telemetry.dump.UidWakelock
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.BatteryState
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.CpuFreqTime
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotClock
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.status.UnplugSummary
import com.akane.inspiration.telemetry.status.UnplugSummaryCalculator
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "snapshot-store-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `round trips full light and partial availability snapshots`() = runBlocking {
        listOf(
            fullSnapshot(),
            fullSnapshot(
                kind = SnapshotKind.LIGHT,
                trigger = CaptureTrigger.SCREEN_OFF,
                wallMs = 2_000L,
                system = null,
                uids = emptyList(),
                deviceComponentsMah = emptyMap(),
                kernelWakelocks = emptyList(),
                wakeupReasons = emptyList(),
                cpuFreq = emptyList(),
            ),
            fullSnapshot(
                trigger = CaptureTrigger.BATTERY_LOW,
                wallMs = 3_000L,
                system = null,
                availability = mapOf(
                    "usage" to SourceAvailability(false, "permission_denied"),
                    "kernel" to SourceAvailability(true, null),
                ),
            ),
        ).forEach { snapshot ->
            val id = store.insert(snapshot)
            assertEquals(snapshot.copy(id = id), store.byId(id))
        }
    }

    @Test
    fun `round trips several uids without mixing their child rows`() = runBlocking {
        val first = uidSnapshot()
        val second = uidSnapshot().copy(
            uid = 10_002,
            packageName = "com.example.other",
            componentsMah = linkedMapOf("gps" to 0.7),
            partialWakelocks = listOf(UidWakelock("other", 1L, 2L, 3L)),
            wakeupAlarms = listOf(UidAlarm("other_alarm", 4L)),
            sensors = listOf(UidSensor(3, 5L, 6L, 7L)),
        )
        val bare = uidSnapshot().copy(
            uid = 10_003,
            componentsMah = emptyMap(),
            partialWakelocks = emptyList(),
            wakeupAlarms = emptyList(),
        )
        val snapshot = fullSnapshot(uids = listOf(first, second, bare))

        val id = store.insert(snapshot)

        assertEquals(snapshot.copy(id = id), store.byId(id))
    }

    @Test
    fun `insert merges duplicate and blank wakelock names per kind and uid`() = runBlocking {
        val snapshot = fullSnapshot(
            uids = listOf(
                uidSnapshot().copy(
                    partialWakelocks = listOf(
                        UidWakelock("sync", 10L, 4L, 1L),
                        UidWakelock("", 3L, 1L, 1L),
                        UidWakelock("sync", 5L, 1L, 2L),
                        UidWakelock("  ", 2L, 0L, 1L),
                    ),
                ),
                uidSnapshot().copy(uid = 10_002, partialWakelocks = listOf(UidWakelock("sync", 7L, 0L, 1L))),
            ),
            kernelWakelocks = listOf(KernelWakelock("k", 30L, 3L), KernelWakelock("", 1L, 1L), KernelWakelock("k", 10L, 1L)),
            wakeupReasons = listOf(KernelWakelock("r", 40L, 4L), KernelWakelock("r", 2L, 1L)),
        )

        val id = store.insert(snapshot)

        assertEquals(
            listOf(
                SnapshotWakelockEntity(id, "KERNEL", -1, "<unnamed>", 1L, 0L, 1L),
                SnapshotWakelockEntity(id, "KERNEL", -1, "k", 40L, 0L, 4L),
                SnapshotWakelockEntity(id, "PARTIAL", 10_001, "<unnamed>", 5L, 1L, 2L),
                SnapshotWakelockEntity(id, "PARTIAL", 10_001, "sync", 15L, 5L, 3L),
                SnapshotWakelockEntity(id, "PARTIAL", 10_002, "sync", 7L, 0L, 1L),
                SnapshotWakelockEntity(id, "WAKEUP_REASON", -1, "r", 42L, 0L, 5L),
            ),
            database.snapshotDao().wakelocksForSnapshot(id),
        )
        val read = store.byId(id)!!
        assertEquals(listOf(KernelWakelock("<unnamed>", 1L, 1L), KernelWakelock("k", 40L, 4L)), read.kernelWakelocks)
        assertEquals(listOf(KernelWakelock("r", 42L, 5L)), read.wakeupReasons)
    }

    @Test
    fun `round trips signal columns for empty and five bins and uid sensors`() = runBlocking {
        val emptyBins = fullSnapshot(system = timers().copy(signalScanningMs = 5L, mobileRadioActiveMs = 6L))
        val emptyId = store.insert(emptyBins)
        val emptyRead = store.byId(emptyId)!!
        assertEquals(emptyList<Long>(), emptyRead.system!!.signalStrengthMs)
        assertEquals(5L, emptyRead.system!!.signalScanningMs)
        assertEquals(6L, emptyRead.system!!.mobileRadioActiveMs)
        assertNull(database.snapshotDao().systemForSnapshot(emptyId)!!.signalBin0Ms)

        val sensors = listOf(UidSensor(-10_000, 100L, 40L, 3L), UidSensor(7, 200L, 0L, 9L))
        val fiveBins = fullSnapshot(
            wallMs = 2_000L,
            system = timers().copy(
                signalStrengthMs = listOf(1L, 0L, 3L, 4L, 5L),
                signalScanningMs = 7L,
                mobileRadioActiveMs = 8L,
            ),
            uids = listOf(uidSnapshot().copy(sensors = sensors)),
        )
        val fiveId = store.insert(fiveBins)
        assertEquals(fiveBins.copy(id = fiveId), store.byId(fiveId))
        assertEquals(sensors.sortedBy { it.handle }, store.byId(fiveId)!!.uids.single().sensors)
    }

    @Test
    fun `signal bins beyond five are not persisted`() = runBlocking {
        val id = store.insert(fullSnapshot(system = timers().copy(signalStrengthMs = listOf(1L, 2L, 3L, 4L, 5L, 6L))))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.byId(id)!!.system!!.signalStrengthMs)
    }

    @Test
    fun `lookup methods honor filters and ordering`() = runBlocking {
        val old = store.insert(fullSnapshot(wallMs = 1_000L, statsStartMs = 10L, bootCount = 1))
        val wrongKind = store.insert(
            fullSnapshot(
                kind = SnapshotKind.LIGHT,
                trigger = CaptureTrigger.SCREEN_OFF,
                wallMs = 2_000L,
                statsStartMs = 10L,
                bootCount = 1,
            ),
        )
        val wrongSession = store.insert(fullSnapshot(wallMs = 3_000L, statsStartMs = 20L, bootCount = 1))
        val match = store.insert(fullSnapshot(wallMs = 4_000L, statsStartMs = 10L, bootCount = 1))
        val wrongBoot = store.insert(fullSnapshot(wallMs = 5_000L, statsStartMs = 10L, bootCount = 2))

        assertEquals(match, store.firstAtOrAfter(1_500L, SnapshotKind.FULL, 10L, 1)?.id)
        assertEquals(wrongKind, store.lastAtOrBefore(2_500L, SnapshotKind.LIGHT)?.id)
        assertEquals(wrongBoot, store.latestByTrigger(CaptureTrigger.APP_OPEN)?.id)
        assertEquals(wrongBoot, store.latest()?.id)
        assertEquals(old, store.lastAtOrBefore(1_000L, SnapshotKind.FULL)?.id)
        assertNotNull(store.byId(wrongSession))
    }

    @Test
    fun `lastHistoryOfEachKindBefore returns only the newest event per kind strictly before the bound`() = runBlocking {
        fun event(kind: Int, wallMs: Long) = HistoryEvent(
            historyTimeMs = wallMs,
            wallMs = wallMs,
            kind = kind,
            levelPct = 50,
            chargeUah = -1L,
            temperatureDeciC = 300,
            voltageMv = 3_900,
            plugType = 0,
        )
        val events = listOf(
            event(HistoryKind.SCREEN_ON, 100),
            event(HistoryKind.SCREEN_OFF, 200),
            event(HistoryKind.SCREEN_ON, 300),
            event(HistoryKind.PLUGGED, 150),
            event(HistoryKind.LEVEL, 350),
            event(HistoryKind.LEVEL, 360),
            event(HistoryKind.SHUTDOWN, 50),
            event(HistoryKind.SCREEN_OFF, 400),
            event(HistoryKind.DEEP_IDLE_ON, 500),
        )
        store.insertHistory(events, statsStartMs = 1L)

        val seeds = store.lastHistoryOfEachKindBefore(400L)

        assertEquals(
            "one row per kind, oldest first; the bound is exclusive and later events are ignored",
            listOf(
                event(HistoryKind.SHUTDOWN, 50),
                event(HistoryKind.PLUGGED, 150),
                event(HistoryKind.SCREEN_OFF, 200),
                event(HistoryKind.SCREEN_ON, 300),
                event(HistoryKind.LEVEL, 360),
            ),
            seeds,
        )
        assertEquals(emptyList<HistoryEvent>(), store.lastHistoryOfEachKindBefore(50L))
        assertEquals(events.map { it.kind }.toSet().size, store.lastHistoryOfEachKindBefore(Long.MAX_VALUE).size)
    }

    @Test
    fun `history ignores reingested events across stats sessions and cursor round trips`() = runBlocking {
        val event = HistoryEvent(
            historyTimeMs = 10L,
            wallMs = 20L,
            kind = HistoryKind.SCREEN_ON,
            levelPct = 50,
            chargeUah = 100_000L,
            temperatureDeciC = 280,
            voltageMv = 4_000,
            plugType = 1,
        )
        store.insertHistory(listOf(event), statsStartMs = 7L)
        store.insertHistory(listOf(event), statsStartMs = 8L)
        store.setHistoryCursor(statsStartMs = 8L, lastHistoryTimeMs = 10L)

        assertEquals(listOf(event), store.historyBetween(0L, 100L))
        assertEquals(7L, database.historyDao().between(0L, 100L).single().statsStartMs)
        assertEquals(8L to 10L, store.historyCursor())
    }

    @Test
    fun `alarm dump wakeups round trip beside batterystats alarm counts`() = runBlocking {
        val both = uidSnapshot().copy(
            wakeupAlarms = listOf(UidAlarm("sync", 7L)),
            alarmDumpWakeups = listOf(UidAlarm("sync", 4L), UidAlarm("dump-only", 2L)),
        )
        val snapshot = fullSnapshot(uids = listOf(both))

        val id = store.insert(snapshot)

        val read = store.byId(id)!!.uids.single()
        assertEquals(listOf(UidAlarm("sync", 7L)), read.wakeupAlarms)
        assertEquals(listOf(UidAlarm("dump-only", 2L), UidAlarm("sync", 4L)), read.alarmDumpWakeups)
        val rows = database.snapshotDao().alarmsForSnapshot(id)
        assertEquals(7L, rows.single { it.tag == "sync" }.count)
        assertEquals(4L, rows.single { it.tag == "sync" }.alarmDumpWakeups)
        assertEquals(0L, rows.single { it.tag == "dump-only" }.count)
        assertEquals(2L, rows.single { it.tag == "dump-only" }.alarmDumpWakeups)
    }

    @Test
    fun `marker resolves its stored wall time and rejects future markers`() = runBlocking {
        val snapshot = fullSnapshot()
        val snapshotId = store.insert(snapshot)
        val wallMs = snapshot.clock.wallMs
        val markerId = store.addMarker(snapshotId, "now", wallMs)
        val futureId = store.addMarker(snapshotId, "future", wallMs + 1)

        assertEquals(wallMs, store.marker(markerId)!!.first)
        assertEquals(snapshotId, store.marker(markerId)!!.second.id)
        val resolver = ReferenceResolver(store)
        val resolved = resolver.resolve(ReferenceKind.CustomMarker(markerId), wallMs)!!
        assertEquals(wallMs, resolved.boundaryFromWallMs)
        assertEquals(0L, resolved.gapMs)
        assertNull(resolver.resolve(ReferenceKind.CustomMarker(futureId), wallMs))
    }

    @Test
    fun `markers are listed newest first`() = runBlocking {
        val snapshotId = store.insert(fullSnapshot())
        val first = store.addMarker(snapshotId, "first", wallMs = 100L)
        val third = store.addMarker(snapshotId, "third", wallMs = 300L)
        val second = store.addMarker(snapshotId, "second", wallMs = 200L)

        assertEquals(
            listOf(
                Marker(third, snapshotId, "third", 300L),
                Marker(second, snapshotId, "second", 200L),
                Marker(first, snapshotId, "first", 100L),
            ),
            store.markers(),
        )
    }

    @Test
    fun `samplesBetween is inclusive and ordered`() = runBlocking {
        store.insertSamples(
            listOf(
                LiveReading(300L, 3L, 1, -30, 4_000, 1.2, 310, 50, false),
                LiveReading(100L, 1L, 1, -10, 4_100, 0.4, 290, 52, true, chargeCounterUah = 3_000_000L, thermalStatus = 3),
                LiveReading(200L, 2L, 1, null, null, null, null, null, null),
                LiveReading(400L, 4L, 1, -40, 3_900, 1.6, 320, 48, false),
            ),
        )

        val samples = store.samplesBetween(from = 100L, to = 300L)

        assertEquals(listOf(100L, 200L, 300L), samples.map { it.wallMs })
        assertEquals(TelemetrySample(100L, 1L, 1, -10, 4_100, 0.4, 290, 52, true, thermalStatus = 3), samples.first())
        assertNull(samples[1].thermalStatus)
        assertNull(samples[1].currentMa)
        assertTrue(store.samplesBetween(from = 500L, to = 600L).isEmpty())
    }

    @Test
    fun `health samples round trip oldest first and an equal wallMs replaces`() = runBlocking {
        store.insertHealthSample(HealthSample(2_000L, 4_800.0, 5_000.0, 120, measuredMah = 4_700.0))
        store.insertHealthSample(HealthSample(1_000L, 4_900.0, 5_000.0, 100))
        store.insertHealthSample(HealthSample(2_000L, 4_750.0, 5_000.0, 121))

        assertEquals(
            listOf(
                HealthSample(1_000L, 4_900.0, 5_000.0, 100, measuredMah = null),
                HealthSample(2_000L, 4_750.0, 5_000.0, 121, measuredMah = null),
            ),
            store.healthSamples(),
        )
    }

    @Test
    fun `charge sessions read back what upsertChargeSession wrote ordered by end time`() = runBlocking {
        store.upsertChargeSession(10L, segment(startHistoryTimeMs = 500L, endWallMs = 9_000L), accepted(4_900.0))
        store.upsertChargeSession(
            10L,
            segment(startHistoryTimeMs = 100L, endWallMs = 3_000L),
            SegmentVerdict(estimateMah = null, independent = false, accepted = false, rejectReason = "too_short"),
        )

        val sessions = store.chargeSessions()

        assertEquals(listOf(3_000L, 9_000L), sessions.map { it.endWallMs })
        assertEquals("too_short", sessions.first().rejectReason)
        assertFalse(sessions.first().accepted)
        assertEquals(
            ChargeSessionRecord(
                statsStartMs = 10L,
                startHistoryTimeMs = 500L,
                startWallMs = 1_000L,
                endWallMs = 9_000L,
                startPct = 20,
                endPct = 80,
                deltaChargeUah = 3_000_000L,
                integratedMah = 2_950.0,
                maxTemperatureDeciC = 380,
                estimateMah = 4_900.0,
                independent = true,
                accepted = true,
                rejectReason = null,
            ),
            sessions.last(),
        )
    }

    @Test
    fun `acceptedEstimatesChronological returns the newest N oldest first`() = runBlocking {
        for (i in 1..5) {
            store.upsertChargeSession(
                10L,
                segment(startHistoryTimeMs = i * 100L, endWallMs = i * 1_000L),
                accepted(4_000.0 + i),
            )
        }
        store.upsertChargeSession(
            10L,
            segment(startHistoryTimeMs = 600L, endWallMs = 6_000L),
            SegmentVerdict(estimateMah = null, independent = false, accepted = false, rejectReason = "too_short"),
        )

        assertEquals(listOf(4_003.0, 4_004.0, 4_005.0), store.acceptedEstimatesChronological(3))
        assertEquals(listOf(4_001.0, 4_002.0, 4_003.0, 4_004.0, 4_005.0), store.acceptedEstimatesChronological(100))
    }

    @Test
    fun `toolkit action inserts updates and lists with an explicit stored status code`() = runBlocking {
        val pending = ToolkitActionRecord(
            startedAt = 1_000L,
            kind = "app_op",
            pkg = "com.example.app",
            op = "RUN_ANY_IN_BACKGROUND",
            param = "IGNORE",
            status = ToolkitActionStatus.PENDING,
        )
        val id = store.insertToolkitAction(pending)
        assertTrue(id > 0L)
        val refused = store.insertToolkitAction(pending.copy(startedAt = 2_000L, status = ToolkitActionStatus.REFUSED, code = 3))
        assertEquals("pending", database.toolkitActionDao().newest(10).last().status)
        assertEquals(listOf(pending.copy(id = id)), store.pendingToolkitActions())

        val done = pending.copy(
            id = id,
            endedAt = 1_500L,
            status = ToolkitActionStatus.DONE,
            code = 0,
            beforeRaw = "allow",
            afterRaw = "ignore",
            restoresId = null,
        )
        store.updateToolkitAction(done)

        assertEquals(listOf(refused, id), store.toolkitActions(10).map { it.id })
        assertEquals(done, store.toolkitActions(10).last())
        assertTrue(store.pendingToolkitActions().isEmpty())
        assertEquals(listOf(refused), store.toolkitActions(1).map { it.id })
        assertEquals("done", database.toolkitActionDao().newest(10).last().status)
    }

    @Test
    fun `toolkit action skips rows whose stored status is unknown`() = runBlocking {
        val id = store.insertToolkitAction(
            ToolkitActionRecord(startedAt = 1L, kind = "force_stop", pkg = "a.b", op = "FORCE_STOP", status = ToolkitActionStatus.DONE),
        )
        database.toolkitActionDao().insert(
            ToolkitActionEntity(startedAt = 2L, kind = "x", op = "x", status = "from_a_newer_build"),
        )

        assertEquals(listOf(id), store.toolkitActions(10).map { it.id })
    }

    @Test
    fun `display pruning preserves pending writes and original restore chain`() = runBlocking {
        val ids = listOf(ToolkitActionStatus.RESTORED, ToolkitActionStatus.DONE, ToolkitActionStatus.PENDING).mapIndexed { i, status ->
            store.insertToolkitAction(ToolkitActionRecord(
                startedAt = i.toLong(), kind = "bucket", pkg = "a.b", op = "bucket", status = status,
                beforeRaw = if (i == 0) "10" else "30", afterRaw = "40",
            ))
        }
        repeat(510) { n ->
            store.insertToolkitAction(ToolkitActionRecord(
                startedAt = 100L + n, kind = "force_stop", op = "force_stop", status = ToolkitActionStatus.REFUSED,
            ))
        }
        val retained = store.toolkitActions(Int.MAX_VALUE)
        assertTrue(retained.map { it.id }.containsAll(ids))
        assertEquals(500, retained.count { it.kind == "force_stop" })
        assertEquals(listOf(ids.last()), store.pendingToolkitActions().map { it.id })
    }

    @Test
    fun `toolkit actions are pruned to the newest 500 rows on insert`() = runBlocking {
        val ids = (1..502).map { n ->
            store.insertToolkitAction(
                ToolkitActionRecord(startedAt = n.toLong(), kind = "k", op = "o", status = ToolkitActionStatus.DONE),
            )
        }

        val remaining = store.toolkitActions(limit = 1_000)

        assertEquals(500, remaining.size)
        assertEquals(ids.last(), remaining.first().id)
        assertEquals(ids[2], remaining.last().id)
    }

    @Test
    fun `prune delegates retention to database sql`() = runBlocking {
        val now = 100L * DAY_MS
        val retained = store.insert(fullSnapshot(wallMs = now - 31L * DAY_MS))
        val pruned = store.insert(fullSnapshot(wallMs = now - 31L * DAY_MS + 1L))
        store.addMarker(retained, "baseline", now)
        store.insertSamples(
            listOf(
                LiveReading(now - 31L * DAY_MS, 1L, 1, 10, 4_000, 40.0, 300, 50, false),
                LiveReading(now - 31L * DAY_MS + 1L, 2L, 1, 20, 4_200, 84.0, 320, 60, false),
            ),
        )

        store.prune(now)

        assertNotNull(store.byId(retained))
        assertNull(store.byId(pruned))
        assertTrue(database.telemetryDao().hourly(((now - 31L * DAY_MS) / HOUR_MS) * HOUR_MS) != null)
    }

    @Test
    fun `snapshotsBetween returns both kinds oldest first within inclusive bounds without per-uid data`() = runBlocking {
        store.insert(fullSnapshot(wallMs = 900L))
        val late = store.insert(fullSnapshot(kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_ON, wallMs = 3_000L, system = null))
        val first = store.insert(fullSnapshot(wallMs = 1_000L))
        val tie = store.insert(fullSnapshot(kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_OFF, wallMs = 1_000L, system = null))
        store.insert(fullSnapshot(wallMs = 3_001L))

        val read = store.snapshotsBetween(1_000L, 3_000L)

        assertEquals(listOf(first, tie, late), read.map { it.id })
        assertEquals(listOf(SnapshotKind.FULL, SnapshotKind.LIGHT, SnapshotKind.LIGHT), read.map { it.kind })
        assertTrue(read.all { it.system == null && it.uids.isEmpty() && it.kernelWakelocks.isEmpty() && it.cpuFreq.isEmpty() })
        assertEquals(store.byId(first)!!.battery, read.first().battery)
        assertEquals(store.byId(first)!!.clock, read.first().clock)
        assertEquals(emptyList<BatterySnapshot>(), store.snapshotsBetween(5_000L, 6_000L))
    }

    @Test
    fun `latestClockByTrigger reads the clock of the newest capture of that trigger`() = runBlocking {
        store.insert(fullSnapshot(trigger = CaptureTrigger.SCREEN_ON, wallMs = 1_000L, bootCount = 1))
        store.insert(fullSnapshot(trigger = CaptureTrigger.SCREEN_ON, wallMs = 3_000L, bootCount = 2))
        val newest = store.insert(fullSnapshot(trigger = CaptureTrigger.SCREEN_ON, wallMs = 3_000L, bootCount = 3))
        store.insert(fullSnapshot(trigger = CaptureTrigger.APP_OPEN, wallMs = 5_000L, bootCount = 4))

        val clock = store.latestClockByTrigger(CaptureTrigger.SCREEN_ON)

        assertEquals(store.byId(newest)!!.clock, clock)
        assertEquals(store.latestByTrigger(CaptureTrigger.SCREEN_ON)!!.clock, clock)
        assertNull(store.latestClockByTrigger(CaptureTrigger.POWER_DISCONNECTED))
    }

    @Test
    fun `unplugWindowSnapshots starts at the screen capture before the last plugged row and keeps the summary`() = runBlocking {
        val base = 40L * DAY_MS
        store.insert(statusPoint(CaptureTrigger.APP_OPEN, base, plugged = false, level = 95))
        val screenOff = store.insert(statusPoint(CaptureTrigger.SCREEN_OFF, base + 10 * MINUTE_MS, plugged = false, level = 94))
        val connected = store.insert(statusPoint(CaptureTrigger.POWER_CONNECTED, base + 20 * MINUTE_MS, plugged = true, level = 93))
        val chargeJob = store.insert(statusPoint(CaptureTrigger.CHARGE_JOB, base + 80 * MINUTE_MS, plugged = true, level = 100))
        val unplug = store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, base + 90 * MINUTE_MS, plugged = false, level = 100))
        val screenOn = store.insert(statusPoint(CaptureTrigger.SCREEN_ON, base + 150 * MINUTE_MS, plugged = false, level = 97))
        val screenOffAgain = store.insert(statusPoint(CaptureTrigger.SCREEN_OFF, base + 180 * MINUTE_MS, plugged = false, level = 94))
        val now = statusPoint(CaptureTrigger.APP_OPEN, base + 240 * MINUTE_MS, plugged = false, level = 92)
        val from = now.clock.wallMs - 30L * DAY_MS

        val window = store.unplugWindowSnapshots(from, now.clock.wallMs)

        assertEquals(listOf(screenOff, connected, chargeJob, unplug, screenOn, screenOffAgain), window.map { it.id })
        val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
        assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, now.clock.wallMs), now), summary)
        assertEquals(base + 90 * MINUTE_MS, summary.anchorWallMs)
        // The screen-off state from before the charge rates the first hour after the unplug: 5 % over 2 h off.
        assertEquals(2.5, summary.screenOffPctPerHour!!, 1e-9)
    }

    @Test
    fun `unplugWindowSnapshots falls back to the newest disconnect and ignores marks outside the range`() = runBlocking {
        val base = 40L * DAY_MS
        val to = base + 240 * MINUTE_MS
        val from = to - 30L * DAY_MS
        store.insert(statusPoint(CaptureTrigger.CHARGE_JOB, from - MINUTE_MS, plugged = true, level = 100))
        store.insert(statusPoint(CaptureTrigger.APP_OPEN, base, plugged = null, level = 90))
        val screenOn = store.insert(statusPoint(CaptureTrigger.SCREEN_ON, base + 10 * MINUTE_MS, plugged = null, level = 89))
        val unplug = store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, base + 30 * MINUTE_MS, plugged = null, level = 88))
        val later = store.insert(statusPoint(CaptureTrigger.APP_OPEN, base + 90 * MINUTE_MS, plugged = null, level = 85))
        // At "now" itself, which the calculator ignores, so it cannot be the mark either.
        val atNow = store.insert(statusPoint(CaptureTrigger.POWER_CONNECTED, to, plugged = true, level = 84))
        val now = statusPoint(CaptureTrigger.APP_OPEN, to, plugged = false, level = 84)

        val window = store.unplugWindowSnapshots(from, to)

        assertEquals(listOf(screenOn, unplug, later, atNow), window.map { it.id })
        val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
        assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, to), now), summary)
        assertEquals(base + 30 * MINUTE_MS, summary.anchorWallMs)
    }

    @Test
    fun `unplugWindowSnapshots reads the whole range when it holds no unplug`() = runBlocking {
        val to = 40L * DAY_MS
        val from = to - 30L * DAY_MS
        store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, from - 1L, plugged = false, level = 100))
        store.insert(statusPoint(CaptureTrigger.APP_OPEN, from, plugged = null, level = 90))
        store.insert(statusPoint(CaptureTrigger.SCREEN_OFF, from + HOUR_MS, plugged = null, level = 89))
        store.insert(statusPoint(CaptureTrigger.APP_OPEN, to - HOUR_MS, plugged = null, level = 80))
        val now = statusPoint(CaptureTrigger.APP_OPEN, to, plugged = false, level = 79)

        val window = store.unplugWindowSnapshots(from, to)

        assertEquals(store.snapshotsBetween(from, to), window)
        assertEquals(3, window.size)
        assertFalse((UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging).anchorIsUnplug)
    }

    private fun statusPoint(trigger: CaptureTrigger, wallMs: Long, plugged: Boolean?, level: Int) = fullSnapshot(
        kind = SnapshotKind.LIGHT,
        trigger = trigger,
        wallMs = wallMs,
        system = null,
        uids = emptyList(),
        deviceComponentsMah = emptyMap(),
        kernelWakelocks = emptyList(),
        wakeupReasons = emptyList(),
        cpuFreq = emptyList(),
    ).copy(battery = BatteryState(level, plugged, null, null, null, null, null, null))

    private fun fullSnapshot(
        kind: SnapshotKind = SnapshotKind.FULL,
        trigger: CaptureTrigger = CaptureTrigger.APP_OPEN,
        wallMs: Long = 1_000L,
        statsStartMs: Long? = 10L,
        bootCount: Int = 1,
        system: SystemTimers? = timers(),
        uids: List<UidSnapshot> = listOf(uidSnapshot()),
        deviceComponentsMah: Map<String, Double> = linkedMapOf("screen" to 1.5, "wifi" to 2.5),
        kernelWakelocks: List<KernelWakelock> = listOf(KernelWakelock("kernel", 30L, 3L)),
        wakeupReasons: List<KernelWakelock> = listOf(KernelWakelock("reason", 40L, 4L)),
        cpuFreq: List<CpuFreqTime> = listOf(CpuFreqTime(0, 300_000L, 500L)),
        availability: Map<String, SourceAvailability> = linkedMapOf(
            "cpu" to SourceAvailability(true, null),
            "usage" to SourceAvailability(false, "unavailable"),
        ),
    ) = BatterySnapshot(
        id = 0L,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wallMs, wallMs + 1L, wallMs + 2L, bootCount, statsStartMs, 9L),
        battery = BatteryState(60, false, "Discharging", 300, 4_000, -200, 1_234_000L, 4.5),
        system = system,
        uids = uids,
        deviceComponentsMah = deviceComponentsMah,
        kernelWakelocks = kernelWakelocks,
        wakeupReasons = wakeupReasons,
        cpuFreq = cpuFreq,
        availability = availability,
        truncatedUids = 2,
    )

    private fun segment(startHistoryTimeMs: Long, endWallMs: Long) = ChargeSegment(
        startHistoryTimeMs = startHistoryTimeMs,
        startWallMs = 1_000L,
        endWallMs = endWallMs,
        startPct = 20,
        endPct = 80,
        deltaChargeUah = 3_000_000L,
        integratedMah = 2_950.0,
        maxTemperatureDeciC = 380,
    )

    private fun accepted(estimateMah: Double) =
        SegmentVerdict(estimateMah = estimateMah, independent = true, accepted = true, rejectReason = null)

    private fun uidSnapshot() = UidSnapshot(
        uid = 10_001,
        packageName = "com.example.app",
        totalMah = 3.0,
        fgMah = 1.0,
        bgMah = 2.0,
        fgsMah = 0.5,
        cachedMah = 0.1,
        componentsMah = linkedMapOf("cpu" to 1.5, "wifi" to 1.5),
        cpuUserMs = 11L,
        cpuSystemMs = 12L,
        fgActivityMs = 13L,
        fgServiceMs = 14L,
        partialWakelocks = listOf(UidWakelock("partial", 15L, 16L, 17L)),
        wakeupAlarms = listOf(UidAlarm("alarm", 18L)),
    )

    private fun timers() = SystemTimers(
        startClockTimeMs = 1L,
        totalRealtimeMs = 2L,
        totalUptimeMs = 3L,
        batteryRealtimeMs = 4L,
        batteryUptimeMs = 5L,
        screenOffRealtimeMs = 6L,
        screenOffUptimeMs = 7L,
        screenDozeMs = 8L,
        screenOnMs = 9L,
        interactiveMs = 10L,
        deepDozeMs = 11L,
        deepDozeCount = 12L,
        lightDozeMs = 13L,
        dischargeTotalMah = 14.0,
        dischargeScreenOffMah = 15.0,
        chargeTimeRemainingMs = 16L,
        dischargeTimeRemainingMs = 17L,
    )

    private companion object {
        const val MINUTE_MS = 60L * 1_000L
        const val HOUR_MS = 60L * 60L * 1_000L
        const val DAY_MS = 24L * HOUR_MS
    }
}
