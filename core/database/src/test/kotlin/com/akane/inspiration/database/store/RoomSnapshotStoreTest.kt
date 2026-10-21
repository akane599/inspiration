package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.SnapshotWakelockEntity
import com.akane.inspiration.database.ToolkitActionEntity
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.ipc.model.UidUsage
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.ChargeSegmentExtractor
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.diff.BatteryStatsSession
import com.akane.inspiration.telemetry.diff.ReferenceKind
import com.akane.inspiration.telemetry.diff.ReferenceResolver
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.DumpParse
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
import com.akane.inspiration.telemetry.snapshot.UidIdentityProof
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.snapshot.UidSnapshot
import com.akane.inspiration.telemetry.source.FullRead
import com.akane.inspiration.telemetry.source.LightRead
import com.akane.inspiration.telemetry.source.TelemetrySource
import com.akane.inspiration.telemetry.status.UnplugSummary
import com.akane.inspiration.telemetry.status.UnplugSummaryCalculator
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HISTORY_PAGE_CAP_REASON
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SAMPLES_BETWEEN_LIMIT
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.store.pruneRetention
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
    fun `round trips full light and partial availability snapshots`() =
        runBlocking {
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
                    availability =
                        mapOf(
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
    fun `identity round trips atomically while partial malformed and cross user evidence stays unknown`() =
        runBlocking {
            val scope = UidIdentityScope(0, 7, "test-reader", 0)
            val fingerprint = requireNotNull(UidOwnerFingerprint.create(10_001, 7, listOf(UidOwnerInstall("test.owner", 10_001, 123))))
            val snapshot =
                fullSnapshot(uids = listOf(uidSnapshot().copy(ownerInstallFingerprint = fingerprint)))
                    .copy(truncatedUids = 0, uidIdentityScope = scope)
            val id = store.insert(snapshot)
            assertEquals(snapshot.copy(id = id), store.byId(id))
            assertEquals(
                fingerprint,
                database
                    .snapshotDao()
                    .uidsForSnapshot(id)
                    .single()
                    .ownerInstallFingerprint,
            )
            val secondaryUid = 110_001
            val secondaryFingerprint =
                requireNotNull(UidOwnerFingerprint.create(secondaryUid, 0, listOf(UidOwnerInstall("test.owner", secondaryUid, 123))))
            val secondary =
                snapshot.copy(
                    uidIdentityScope = scope.copy(userId = 1, userSerial = 0),
                    uids = listOf(snapshot.uids.single().copy(uid = secondaryUid, ownerInstallFingerprint = secondaryFingerprint)),
                )
            val secondaryId = store.insert(secondary)
            assertEquals(secondary.copy(id = secondaryId), store.byId(secondaryId))
            assertTrue(store.unplugWindow(0, Long.MAX_VALUE).snapshots.all { it.uidIdentityScope == null && it.uids.isEmpty() })
            assertTrue(store.snapshotsBetween(0, Long.MAX_VALUE).all { it.uidIdentityScope == null && it.uids.isEmpty() })
            val sql = database.openHelper.writableDatabase
            for (column in listOf(
                "uidIdentityUserId",
                "uidIdentityUserSerial",
                "uidIdentityReaderEpoch",
                "uidIdentityPackageChangeSequence",
            )) {
                val partial = store.insert(snapshot)
                sql.execSQL("UPDATE snapshot SET `$column` = NULL WHERE id = ?", arrayOf(partial))
                val read = requireNotNull(store.byId(partial))
                assertNull(read.uidIdentityScope)
                assertNull(read.uids.single().ownerInstallFingerprint)
            }
            sql.execSQL(
                "UPDATE snapshot_uid SET ownerInstallFingerprint = ? WHERE snapshotId = ?",
                arrayOf<Any?>("v2:" + "a".repeat(64), id),
            )
            assertNull(
                store
                    .byId(id)!!
                    .uids
                    .single()
                    .ownerInstallFingerprint,
            )
            val crossUser = store.insert(snapshot.copy(uidIdentityScope = scope.copy(userId = 1)))
            assertNull(
                store
                    .byId(crossUser)!!
                    .uids
                    .single()
                    .ownerInstallFingerprint,
            )
            val invalidScope = store.insert(snapshot.copy(uidIdentityScope = scope.copy(userSerial = -1)))
            assertNull(store.byId(invalidScope)!!.uidIdentityScope)
        }

    @Test
    fun `child insertion failure rolls back snapshot scope and uid evidence together`() =
        runBlocking {
            val scope = UidIdentityScope(0, 7, "test-reader", 0)
            val fingerprint = UidOwnerFingerprint.create(10_001, 7, listOf(UidOwnerInstall("test.owner", 10_001, 123)))
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_cpu BEFORE INSERT ON snapshot_cpu_freq BEGIN SELECT RAISE(ABORT, 'test child failure'); END",
            )
            val frequency = CpuFreqTime(0, 1_000, 10)
            val snapshot =
                fullSnapshot(
                    uids = listOf(uidSnapshot().copy(ownerInstallFingerprint = fingerprint)),
                    cpuFreq = listOf(frequency),
                ).copy(uidIdentityScope = scope, truncatedUids = 0)
            assertTrue(runCatching { store.insert(snapshot) }.isFailure)
            assertNull(store.latest())
            for (table in listOf("snapshot", "snapshot_uid", "snapshot_component", "snapshot_cpu_freq")) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        }

    @Test
    fun `capture Room differ retains shared hint rotation but suppresses legacy scope changes and truncation`() =
        runBlocking {
            val scope = UidIdentityScope(0, 7, "test-reader", 0)
            val fingerprint =
                requireNotNull(
                    UidOwnerFingerprint.create(
                        10_001,
                        7,
                        listOf(
                            UidOwnerInstall("shared.a", 10_001, 123),
                            UidOwnerInstall("shared.b", 10_001, 456),
                        ),
                    ),
                )
            var elapsed = 1_000L
            var total = 30.0
            var hint = "shared.a"
            var proof: UidIdentityProof? = UidIdentityProof(scope, mapOf(10_001 to fingerprint))
            var truncated = 0
            val source =
                object : TelemetrySource {
                    override fun light() =
                        LightRead(
                            SnapshotClock(elapsed, elapsed, elapsed / 2, 1, null, null),
                            70,
                            false,
                            "discharging",
                            null,
                            null,
                            null,
                            null,
                        )

                    override suspend fun full(
                        trigger: CaptureTrigger,
                        historyCursor: Pair<Long, Long>?,
                    ) = FullRead(
                        light(),
                        BatteryUsageSnapshot(
                            true,
                            null,
                            10,
                            10 + elapsed,
                            total,
                            0,
                            0,
                            listOf(UidUsage(10_001, hint, total, emptyMap(), emptyMap())),
                            deviceComponents = mapOf("CPU" to total),
                            truncatedUids = truncated,
                        ),
                        DumpParse.Unavailable("test"),
                        DumpParse.Unavailable("test"),
                        null,
                        emptyList(),
                        emptyList(),
                        proof,
                    )
                }
            val capturer = SnapshotCapturer(source, store, TestClocks(1_000, 1_000))

            suspend fun capture(): BatterySnapshot {
                val result = capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = 1_000, ingestHistory = false)
                assertTrue(result is com.akane.inspiration.telemetry.capture.CaptureResult.Saved)
                return requireNotNull(store.latest())
            }
            val ref = capture()
            elapsed = 2_000
            total = 50.0
            hint = "shared.b"
            val now = capture()
            val good = SnapshotDiffer.diff(ref, now)
            assertTrue(good.uids.single().valid)
            assertFalse(good.uids.single().packageChanged)
            assertEquals(20.0, good.uids.single().totalMah, 0.0)
            // A restarted service process after a package update: new reader epoch and sequence, same owners.
            proof = UidIdentityProof(scope.copy(readerEpoch = "restarted-reader", packageChangeSequence = 39), mapOf(10_001 to fingerprint))
            val restarted = SnapshotDiffer.diff(ref, capture())
            assertTrue(restarted.uids.single().valid)
            assertEquals(20.0, restarted.uids.single().totalMah, 0.0)
            for (badProof in listOf(null, UidIdentityProof(scope.copy(userSerial = 8), mapOf(10_001 to fingerprint)))) {
                proof = badProof
                val withheld = SnapshotDiffer.diff(ref, capture())
                assertFalse(withheld.uids.single().valid)
                assertEquals(20.0, withheld.deviceComponentsMah.getValue("CPU"), 0.0)
            }
            proof = UidIdentityProof(scope, mapOf(10_001 to fingerprint))
            truncated = 1
            val truncatedNow = capture()
            assertNull(truncatedNow.uidIdentityScope)
            assertFalse(
                SnapshotDiffer
                    .diff(ref, truncatedNow)
                    .uids
                    .single()
                    .valid,
            )
            assertTrue(
                SnapshotDiffer
                    .diff(ref, truncatedNow)
                    .validity.problems
                    .contains("truncated_uid_usage"),
            )
            capturer.captureLight(CaptureTrigger.SCREEN_OFF)
            assertNull(store.latest()!!.uidIdentityScope)
        }

    @Test
    fun `round trips several uids without mixing their child rows`() =
        runBlocking {
            val first = uidSnapshot()
            val second =
                uidSnapshot().copy(
                    uid = 10_002,
                    packageName = "com.example.other",
                    componentsMah = linkedMapOf("gps" to 0.7),
                    partialWakelocks = listOf(UidWakelock("other", 1L, 2L, 3L)),
                    wakeupAlarms = listOf(UidAlarm("other_alarm", 4L)),
                    sensors = listOf(UidSensor(3, 5L, 6L, 7L)),
                )
            val bare =
                uidSnapshot().copy(
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
    fun `insert merges duplicate and blank wakelock names per kind and uid`() =
        runBlocking {
            val snapshot =
                fullSnapshot(
                    uids =
                        listOf(
                            uidSnapshot().copy(
                                partialWakelocks =
                                    listOf(
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
    fun `round trips signal columns for empty and five bins and uid sensors`() =
        runBlocking {
            val emptyBins = fullSnapshot(system = timers().copy(signalScanningMs = 5L, mobileRadioActiveMs = 6L))
            val emptyId = store.insert(emptyBins)
            val emptyRead = store.byId(emptyId)!!
            assertEquals(emptyList<Long>(), emptyRead.system!!.signalStrengthMs)
            assertEquals(5L, emptyRead.system!!.signalScanningMs)
            assertEquals(6L, emptyRead.system!!.mobileRadioActiveMs)
            assertNull(database.snapshotDao().systemForSnapshot(emptyId)!!.signalBin0Ms)

            val sensors = listOf(UidSensor(-10_000, 100L, 40L, 3L), UidSensor(7, 200L, 0L, 9L))
            val fiveBins =
                fullSnapshot(
                    wallMs = 2_000L,
                    system =
                        timers().copy(
                            signalStrengthMs = listOf(1L, 0L, 3L, 4L, 5L),
                            signalScanningMs = 7L,
                            mobileRadioActiveMs = 8L,
                        ),
                    uids = listOf(uidSnapshot().copy(sensors = sensors)),
                )
            val fiveId = store.insert(fiveBins)
            assertEquals(fiveBins.copy(id = fiveId), store.byId(fiveId))
            assertEquals(
                sensors.sortedBy { it.handle },
                store
                    .byId(fiveId)!!
                    .uids
                    .single()
                    .sensors,
            )
        }

    @Test
    fun `signal bins beyond five are not persisted`() =
        runBlocking {
            val id = store.insert(fullSnapshot(system = timers().copy(signalStrengthMs = listOf(1L, 2L, 3L, 4L, 5L, 6L))))
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.byId(id)!!.system!!.signalStrengthMs)
        }

    @Test
    fun `history horizon is the newest full capture whose history read was complete`() =
        runBlocking {
            assertEquals(null, store.historyHorizonWallMs())
            val complete = mapOf("history" to SourceAvailability(true, null))
            store.insert(fullSnapshot(wallMs = 1_000L, availability = complete))
            assertEquals(1_000L, store.historyHorizonWallMs())

            // Newer rows that did not complete a history read leave the horizon where it was.
            store.insert(fullSnapshot(wallMs = 2_000L, availability = mapOf("history" to SourceAvailability(true, "remote"))))
            // A read stopped at its page cap stored older pages only (audit B-02).
            store.insert(
                fullSnapshot(wallMs = 2_500L, availability = mapOf("history" to SourceAvailability(true, HISTORY_PAGE_CAP_REASON))),
            )
            store.insert(fullSnapshot(wallMs = 3_000L, availability = mapOf("history" to SourceAvailability(false, null))))
            store.insert(fullSnapshot(wallMs = 4_000L, availability = mapOf("usage" to SourceAvailability(true, null))))
            store.insert(fullSnapshot(kind = SnapshotKind.LIGHT, wallMs = 5_000L, availability = complete))
            assertEquals(1_000L, store.historyHorizonWallMs())

            store.insert(fullSnapshot(trigger = CaptureTrigger.CHARGE_JOB, wallMs = 6_000L, availability = complete))
            assertEquals(6_000L, store.historyHorizonWallMs())
        }

    @Test
    fun `lookup methods honor filters and ordering`() =
        runBlocking {
            val old = store.insert(fullSnapshot(wallMs = 1_000L, statsStartMs = 10L, bootCount = 1))
            val wrongKind =
                store.insert(
                    fullSnapshot(
                        kind = SnapshotKind.LIGHT,
                        trigger = CaptureTrigger.SCREEN_OFF,
                        wallMs = 2_000L,
                        statsStartMs = 10L,
                        bootCount = 1,
                    ),
                )
            val wrongSession = store.insert(fullSnapshot(wallMs = 3_000L, statsStartMs = 2_011L, bootCount = 1))
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
    fun `firstAtOrAfter matches a battery-stats session shifted by a wall-clock step within tolerance`() =
        runBlocking {
            store.insert(fullSnapshot(wallMs = 2_000L, statsStartMs = 12_001L, bootCount = 1))
            val shifted = store.insert(fullSnapshot(wallMs = 3_000L, statsStartMs = 8_500L, bootCount = 1))

            assertEquals(shifted, store.firstAtOrAfter(1_500L, SnapshotKind.FULL, 10_000L, 1)?.id)
            assertNull(store.firstAtOrAfter(1_500L, SnapshotKind.FULL, 20_000L, 1))
        }

    @Test
    fun `firstInSession finds the reference from before a wall-clock step past the tolerance`() =
        runBlocking {
            // The wall clock stepped 5 s between the two rows and the reported start moved with it.
            val beforeStep = store.insert(fullSnapshot(wallMs = 2_000L, elapsedMs = 2_000L, statsStartMs = 10_000L))
            val afterStep = store.insert(fullSnapshot(wallMs = 8_000L, elapsedMs = 3_000L, statsStartMs = 15_000L))
            val session = SnapshotClock(14_000L, 9_000L, 4_500L, 1, 15_000L, 9_000L)

            assertEquals(beforeStep, store.firstInSession(1_500L, SnapshotKind.FULL, session, 1)?.id)
            assertTrue(BatteryStatsSession.sameStart(store.byId(beforeStep)!!.clock, session))
            // The raw start lookup still sees only the row after the step.
            assertEquals(afterStep, store.firstAtOrAfter(1_500L, SnapshotKind.FULL, 15_000L, 1)?.id)
            // Another boot's elapsed clock proves nothing, so only the raw start matches there.
            assertEquals(afterStep, store.firstInSession(1_500L, SnapshotKind.FULL, session.copy(bootCount = 2), null)?.id)
            // A start shift the step does not explain is another session.
            assertNull(store.firstInSession(1_500L, SnapshotKind.FULL, session.copy(statsStartMs = 18_000L), 1))
            // A row not earlier on the session's elapsed clock is not matched through the step.
            val notLater = session.copy(wallMs = 12_000L, elapsedRealtimeMs = 2_000L, statsStartMs = 20_000L)
            assertNull(store.firstInSession(1_500L, SnapshotKind.FULL, notLater, 1))
            assertEquals(beforeStep, store.firstInSession(1_500L, SnapshotKind.FULL, notLater.copy(elapsedRealtimeMs = 2_001L, statsStartMs = 20_001L), 1)?.id)
        }

    @Test
    fun `lastHistoryOfEachKindBefore ignores events whose wall time is unknown`() =
        runBlocking {
            fun event(
                kind: Int,
                historyTimeMs: Long,
                wallMs: Long,
            ) = HistoryEvent(
                historyTimeMs = historyTimeMs,
                wallMs = wallMs,
                kind = kind,
                levelPct = 50,
                chargeUah = -1L,
                temperatureDeciC = 300,
                voltageMv = 3_900,
                plugType = 0,
            )
            val known = event(HistoryKind.SCREEN_ON, historyTimeMs = 30, wallMs = 300)
            store.insertHistory(
                listOf(
                    event(HistoryKind.UNPLUGGED, historyTimeMs = 10, wallMs = -1),
                    event(HistoryKind.SCREEN_ON, historyTimeMs = 20, wallMs = -1),
                    known,
                ),
                statsStartMs = 1L,
            )

            assertEquals(listOf(known), store.lastHistoryOfEachKindBefore(400L))
            assertEquals(emptyList<HistoryEvent>(), store.lastHistoryOfEachKindBefore(300L))
        }

    @Test
    fun `lastHistoryOfEachKindBefore returns only the newest event per kind strictly before the bound`() =
        runBlocking {
            fun event(
                kind: Int,
                wallMs: Long,
            ) = HistoryEvent(
                historyTimeMs = wallMs,
                wallMs = wallMs,
                kind = kind,
                levelPct = 50,
                chargeUah = -1L,
                temperatureDeciC = 300,
                voltageMv = 3_900,
                plugType = 0,
            )
            val events =
                listOf(
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
    fun `a RESET stays ahead of the screen state it restates at the same wall time`() =
        runBlocking {
            fun event(
                kind: Int,
                historyTimeMs: Long,
            ) = HistoryEvent(
                historyTimeMs = historyTimeMs,
                wallMs = 1_000L + historyTimeMs,
                kind = kind,
                levelPct = 100,
                chargeUah = -1L,
                temperatureDeciC = 300,
                voltageMv = 3_900,
                plugType = 0,
            )
            // An older screen-on puts SCREEN_ON ahead of RESET in kind order; the walker inserts RESET, then its state.
            store.insertHistory(
                listOf(
                    event(HistoryKind.SCREEN_ON, 100),
                    event(HistoryKind.RESET, 500),
                    event(HistoryKind.SCREEN_ON, 500),
                    event(HistoryKind.DEEP_IDLE_ON, 500),
                ),
                statsStartMs = 1L,
            )
            val restated = listOf(HistoryKind.RESET, HistoryKind.SCREEN_ON, HistoryKind.DEEP_IDLE_ON)
            // The interface default that fakes and previews use, over the same rows.
            val scanning =
                object : SnapshotStore by store {
                    override suspend fun lastHistoryOfEachKindBefore(wallMs: Long) = super.lastHistoryOfEachKindBefore(wallMs)
                }

            assertEquals(restated, store.historyBetween(0L, Long.MAX_VALUE).drop(1).map { it.kind })
            assertEquals(restated, store.lastHistoryOfEachKindBefore(Long.MAX_VALUE).map { it.kind })
            assertEquals(restated, scanning.lastHistoryOfEachKindBefore(Long.MAX_VALUE).map { it.kind })
        }

    @Test
    fun `history ignores reingested events across stats sessions and cursor round trips`() =
        runBlocking {
            val event =
                HistoryEvent(
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
            assertEquals(
                7L,
                database
                    .historyDao()
                    .between(0L, 100L)
                    .single()
                    .statsStartMs,
            )
            assertEquals(8L to 10L, store.historyCursor())
        }

    @Test
    fun `a backward clock step mid charge keeps the charge segment across the relabelled session`() =
        runBlocking {
            fun event(
                historyTimeMs: Long,
                wallMs: Long,
                kind: Int,
                levelPct: Int,
                chargeUah: Long,
            ) = HistoryEvent(
                historyTimeMs = historyTimeMs,
                wallMs = wallMs,
                kind = kind,
                levelPct = levelPct,
                chargeUah = chargeUah,
                temperatureDeciC = 300,
                voltageMv = 4_000,
                plugType = if (kind == HistoryKind.UNPLUGGED) 0 else 1,
            )
            val label = 5_000_000L
            val stepMs = 60_000L
            val beforeStep =
                listOf(
                    event(0L, 10_000L, HistoryKind.PLUGGED, 30, 1_000_000L),
                    event(1_800_000L, 1_810_000L, HistoryKind.LEVEL, 60, 2_200_000L),
                )
            store.insertHistory(beforeStep, statsStartMs = label)
            store.setHistoryCursor(statsStartMs = label, lastHistoryTimeMs = 1_800_000L)
            // BatteryStatsImpl shifts its start back by the step: the service re-pages from 0 under the smaller
            // label, the stored events are ignored and only the new ones (a step behind on the wall) are inserted.
            val afterStep =
                listOf(
                    event(3_000_000L, 3_010_000L - stepMs, HistoryKind.LEVEL, 90, 3_400_000L),
                    event(3_600_000L, 3_610_000L - stepMs, HistoryKind.UNPLUGGED, 95, 3_600_000L),
                )
            store.insertHistory(beforeStep + afterStep, statsStartMs = label - stepMs)

            val (statsStartMs, segment) = ChargeSegmentExtractor.fromSessions(store.historySessions()).single()

            assertEquals(label, statsStartMs)
            assertEquals(10_000L to 3_610_000L - stepMs, segment.startWallMs to segment.endWallMs)
            assertEquals(2_600_000L, segment.deltaChargeUah)
        }

    @Test
    fun `historySessions returns label runs in ingestion order whatever the label values`() =
        runBlocking {
            fun event(
                historyTimeMs: Long,
                wallMs: Long = historyTimeMs,
            ) = HistoryEvent(
                historyTimeMs = historyTimeMs,
                wallMs = wallMs,
                kind = HistoryKind.LEVEL,
                levelPct = 50,
                chargeUah = -1L,
                temperatureDeciC = 300,
                voltageMv = 4_000,
                plugType = 0,
            )
            val label = 5_000_000L
            // A backward step relabels the session with a smaller start; a later forward step can restore the label.
            store.insertHistory(listOf(event(10L, wallMs = -1L), event(20L), event(30L)), statsStartMs = label)
            store.insertHistory(listOf(event(40L)), statsStartMs = label - 60_000L)
            store.insertHistory(listOf(event(50L)), statsStartMs = label)

            assertEquals(
                listOf(label to listOf(20L, 30L), label - 60_000L to listOf(40L), label to listOf(50L)),
                store.historySessions().map { (statsStartMs, events) -> statsStartMs to events.map { it.historyTimeMs } },
            )
        }

    @Test
    fun `alarm dump wakeups round trip beside batterystats alarm counts`() =
        runBlocking {
            val both =
                uidSnapshot().copy(
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
    fun `marker resolves its stored wall time and rejects future markers`() =
        runBlocking {
            val snapshot = fullSnapshot()
            val snapshotId = store.insert(snapshot)
            val wallMs = snapshot.clock.wallMs
            val markerId = store.addMarker(snapshotId, "now", wallMs)
            val futureId = store.addMarker(snapshotId, "future", wallMs + 1)

            assertEquals(wallMs, store.marker(markerId)!!.first)
            assertEquals(snapshotId, store.marker(markerId)!!.second.id)
            val resolver = ReferenceResolver(store)
            // Right after "Mark now" the marker's snapshot is still the newest: no zero-length self-pair.
            assertNull(resolver.resolve(ReferenceKind.CustomMarker(markerId), wallMs))
            assertNull(resolver.resolve(ReferenceKind.CustomMarker(futureId), wallMs))

            val nextId = store.insert(fullSnapshot(wallMs = wallMs + 1_000L))
            val resolved = resolver.resolve(ReferenceKind.CustomMarker(markerId), wallMs + 1_000L)!!
            assertEquals(snapshotId, resolved.ref.id)
            assertEquals(nextId, resolved.now.id)
            assertEquals(wallMs, resolved.boundaryFromWallMs)
            assertEquals(0L, resolved.gapMs)
        }

    @Test
    fun `markers are listed newest first`() =
        runBlocking {
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
    fun `samplesBetween is inclusive and ordered`() =
        runBlocking {
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
    fun `samplesBetween returns at most the limit, the newest ones, oldest first`() =
        runBlocking {
            val count = SAMPLES_BETWEEN_LIMIT + 2
            store.insertSamples((1..count).map { LiveReading(it.toLong(), it.toLong(), 1, -10, 4_000, 0.4, 300, 50, true) })

            val samples = store.samplesBetween(from = 0L, to = Long.MAX_VALUE)

            assertEquals(SAMPLES_BETWEEN_LIMIT, samples.size)
            assertEquals("the two oldest are dropped", 3L, samples.first().wallMs)
            assertEquals(count.toLong(), samples.last().wallMs)
            assertTrue(samples.zipWithNext().all { (a, b) -> a.wallMs < b.wallMs })
        }

    @Test
    fun `health samples round trip oldest first and an equal wallMs replaces`() =
        runBlocking {
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
    fun `charge sessions read back what upsertChargeSession wrote ordered by end time`() =
        runBlocking {
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
    fun `acceptedEstimatesChronological returns the newest N oldest first`() =
        runBlocking {
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
    fun `toolkit action inserts updates and lists with an explicit stored status code`() =
        runBlocking {
            val pending =
                ToolkitActionRecord(
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
            assertEquals(
                "pending",
                database
                    .toolkitActionDao()
                    .newest(10)
                    .last()
                    .status,
            )
            assertEquals(listOf(pending.copy(id = id)), store.pendingToolkitActions())

            val done =
                pending.copy(
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
            assertEquals(
                "done",
                database
                    .toolkitActionDao()
                    .newest(10)
                    .last()
                    .status,
            )
        }

    @Test
    fun `toolkit action skips rows whose stored status is unknown`() =
        runBlocking {
            val id =
                store.insertToolkitAction(
                    ToolkitActionRecord(
                        startedAt = 1L,
                        kind = "force_stop",
                        pkg = "a.b",
                        op = "FORCE_STOP",
                        status = ToolkitActionStatus.DONE,
                    ),
                )
            database.toolkitActionDao().insert(
                ToolkitActionEntity(startedAt = 2L, kind = "x", op = "x", status = "from_a_newer_build"),
            )

            assertEquals(listOf(id), store.toolkitActions(10).map { it.id })
        }

    @Test
    fun `display pruning preserves pending writes and original restore chain`() =
        runBlocking {
            val ids =
                listOf(ToolkitActionStatus.RESTORED, ToolkitActionStatus.DONE, ToolkitActionStatus.PENDING).mapIndexed { i, status ->
                    store.insertToolkitAction(
                        ToolkitActionRecord(
                            startedAt = i.toLong(),
                            kind = "bucket",
                            pkg = "a.b",
                            op = "bucket",
                            status = status,
                            beforeRaw = if (i == 0) "10" else "30",
                            afterRaw = "40",
                        ),
                    )
                }
            repeat(510) { n ->
                store.insertToolkitAction(
                    ToolkitActionRecord(
                        startedAt = 100L + n,
                        kind = "force_stop",
                        op = "force_stop",
                        status = ToolkitActionStatus.REFUSED,
                    ),
                )
            }
            val retained = store.toolkitActions(Int.MAX_VALUE)
            assertTrue(retained.map { it.id }.containsAll(ids))
            assertEquals(500, retained.count { it.kind == "force_stop" })
            assertEquals(listOf(ids.last()), store.pendingToolkitActions().map { it.id })
        }

    @Test
    fun `toolkit actions are pruned to the newest 500 rows on insert`() =
        runBlocking {
            val ids =
                (1..502).map { n ->
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
    fun `prune delegates retention to database sql`() =
        runBlocking {
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
    fun `retention prune after a forward wall clock jump keeps what this boot recorded days ago`() =
        runBlocking {
            val twoDaysAgo = store.insert(bootSnapshot(elapsedMs = 3L * DAY_MS))
            val latest = store.insert(bootSnapshot(elapsedMs = 5L * DAY_MS))
            store.insertHistory(listOf(levelEvent(wallMs = BOOT_WALL_MS + 4L * DAY_MS)), statsStartMs = 10L)
            // One minute later the wall clock reads a year ahead; the capture saves with it and then prunes.
            val nowElapsedMs = 5L * DAY_MS + 60_000L
            val jumped = TestClocks(wallMs = BOOT_WALL_MS + nowElapsedMs + 365L * DAY_MS, elapsedMs = nowElapsedMs)
            store.insert(bootSnapshot(elapsedMs = nowElapsedMs, wallMs = jumped.wallMs()))

            store.pruneRetention(jumped)

            assertNotNull("2 days old by elapsedRealtime", store.byId(twoDaysAgo))
            assertNotNull(store.byId(latest))
            assertEquals("history cannot be re-ingested once pruned", 1, store.historyBetween(0L, Long.MAX_VALUE).size)
        }

    @Test
    fun `retention prune after a forward wall clock jump still expires what is thirty days old by elapsed time`() =
        runBlocking {
            val expired = store.insert(bootSnapshot(elapsedMs = 1L * DAY_MS))
            val kept = store.insert(bootSnapshot(elapsedMs = 3L * DAY_MS))
            val nowElapsedMs = 32L * DAY_MS + 12L * HOUR_MS

            store.pruneRetention(TestClocks(wallMs = BOOT_WALL_MS + nowElapsedMs + 365L * DAY_MS, elapsedMs = nowElapsedMs))

            assertNull(store.byId(expired))
            assertNotNull(store.byId(kept))
        }

    @Test
    fun `retention prune after a backward wall clock jump only keeps rows longer`() =
        runBlocking {
            // 31 days old by elapsed time, 29 days by the wall clock that was set back 2 days.
            val old = store.insert(bootSnapshot(elapsedMs = 1L * DAY_MS))
            val nowElapsedMs = 32L * DAY_MS

            store.pruneRetention(TestClocks(wallMs = BOOT_WALL_MS + nowElapsedMs - 2L * DAY_MS, elapsedMs = nowElapsedMs))

            assertNotNull(store.byId(old))
        }

    @Test
    fun `retention prune trusts the wall clock when no stored snapshot shares the current boot`() =
        runBlocking {
            // elapsedRealtime restarted with the reboot, so the previous boot's rows can only be aged by the wall clock.
            val expired = store.insert(bootSnapshot(elapsedMs = 1L * DAY_MS))
            val kept = store.insert(bootSnapshot(elapsedMs = 3L * DAY_MS))
            val rebooted = TestClocks(wallMs = BOOT_WALL_MS + 32L * DAY_MS, elapsedMs = 60_000L, bootCount = 2)
            store.insert(bootSnapshot(elapsedMs = rebooted.elapsedRealtimeMs(), wallMs = rebooted.wallMs(), bootCount = 2))

            store.pruneRetention(rebooted)

            assertNull(store.byId(expired))
            assertNotNull(store.byId(kept))
        }

    @Test
    fun `snapshotsBetween returns both kinds oldest first within inclusive bounds without per-uid data`() =
        runBlocking {
            store.insert(fullSnapshot(wallMs = 900L))
            val late =
                store.insert(
                    fullSnapshot(kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_ON, wallMs = 3_000L, system = null),
                )
            val first = store.insert(fullSnapshot(wallMs = 1_000L))
            val tie =
                store.insert(
                    fullSnapshot(kind = SnapshotKind.LIGHT, trigger = CaptureTrigger.SCREEN_OFF, wallMs = 1_000L, system = null),
                )
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
    fun `an unplug after wall rollback remains the usable reference for both latest trigger APIs`() =
        runBlocking {
            store = RoomSnapshotStore(database, currentBootCount = { 7 })
            store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 10_000L, bootCount = 7))
            val unplug = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 20_000L, bootCount = 7))
            val nowWallMs = 1_001_000L
            val now =
                store.insert(
                    triggerSnapshot(wallMs = nowWallMs, elapsedMs = 21_000L, bootCount = 7, trigger = CaptureTrigger.APP_OPEN),
                )

            assertLatestTrigger(unplug)
            // Runtime unplug captures need not ingest history; the resolver must use the corrected clock-only fallback.
            assertTrue(store.historyBetween(0L, nowWallMs).isEmpty())
            val resolved = ReferenceResolver(store).resolve(ReferenceKind.SinceUnplugged, nowWallMs)

            assertNotNull("the old future-dated unplug must not hide the new usable reference", resolved)
            assertEquals(unplug, resolved!!.ref.id)
            assertEquals(now, resolved.now.id)
            assertEquals(1_000_000L, resolved.boundaryFromWallMs)
            assertEquals(nowWallMs, resolved.boundaryToWallMs)
            assertEquals(0L, resolved.gapMs)
        }

    @Test
    fun `known current boot elapsed order beats a late older capture and later saved other boots`() =
        runBlocking {
            store = RoomSnapshotStore(database, currentBootCount = { 7 })
            val newest = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 20_000L, bootCount = 7))
            // Captured earlier but persisted later: saved ID alone would select this stale same-boot row.
            store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 10_000L, bootCount = 7))
            store.insert(triggerSnapshot(wallMs = 3_000_000L, elapsedMs = 90_000L, bootCount = 6))
            // Boot numbers are identities, not a chronology we can sort; even a numerically higher one cannot win.
            store.insert(triggerSnapshot(wallMs = 4_000_000L, elapsedMs = 100_000L, bootCount = 99))
            store.insert(triggerSnapshot(wallMs = 5_000_000L, elapsedMs = 200_000L, bootCount = 0))
            store.insert(triggerSnapshot(wallMs = 6_000_000L, elapsedMs = 300_000L, bootCount = 7, trigger = CaptureTrigger.SCREEN_ON))

            assertLatestTrigger(newest)
        }

    @Test
    fun `elapsed ties in the current boot use saved id rather than wall time`() =
        runBlocking {
            store = RoomSnapshotStore(database, currentBootCount = { 7 })
            store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 20_000L, bootCount = 7))
            val latestSaved = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 20_000L, bootCount = 7))

            assertLatestTrigger(latestSaved)
        }

    @Test
    fun `unknown current boot uses saved id without comparing elapsed values across boots`() =
        runBlocking {
            var currentBoot: Int? = null
            store = RoomSnapshotStore(database, currentBootCount = { currentBoot })
            store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 90_000L, bootCount = 99))
            store.insert(triggerSnapshot(wallMs = 1_900_000L, elapsedMs = 80_000L, bootCount = 0))
            store.insert(triggerSnapshot(wallMs = 1_800_000L, elapsedMs = 70_000L, bootCount = -1))
            val latestSaved = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 1_000L, bootCount = 6))

            for (unknown in listOf(null, 0, -1)) {
                currentBoot = unknown
                assertLatestTrigger(latestSaved)
            }
            // Existing constructor callers without a provider have the same best-effort saved-id fallback.
            store = RoomSnapshotStore(database)
            assertLatestTrigger(latestSaved)
        }

    @Test
    fun `a missing current boot capture for this trigger falls back to the latest saved row`() =
        runBlocking {
            store = RoomSnapshotStore(database, currentBootCount = { 7 })
            store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 90_000L, bootCount = 99))
            val latestSaved = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 1_000L, bootCount = 6))
            store.insert(triggerSnapshot(wallMs = 3_000_000L, elapsedMs = 100_000L, bootCount = 7, trigger = CaptureTrigger.SCREEN_ON))

            assertLatestTrigger(latestSaved)
            assertLatestTrigger(null, CaptureTrigger.BATTERY_LOW)
        }

    @Test
    fun `latest trigger samples the current boot provider for each lookup`() =
        runBlocking {
            var currentBoot = 7
            store = RoomSnapshotStore(database, currentBootCount = { currentBoot })
            val firstBoot = store.insert(triggerSnapshot(wallMs = 2_000_000L, elapsedMs = 90_000L, bootCount = 7))
            val secondBoot = store.insert(triggerSnapshot(wallMs = 1_000_000L, elapsedMs = 1_000L, bootCount = 8))

            assertLatestTrigger(firstBoot)
            currentBoot = 8
            assertLatestTrigger(secondBoot)
        }

    @Test
    fun `latestClockByTrigger reads the clock of the newest capture of that trigger`() =
        runBlocking {
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
    fun `unplugWindow starts its snapshots at the screen capture before the last plugged row and keeps the summary`() =
        runBlocking {
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

            val window = store.unplugWindow(from, now.clock.wallMs).snapshots

            assertEquals(listOf(screenOff, connected, chargeJob, unplug, screenOn, screenOffAgain), window.map { it.id })
            val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
            assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, now.clock.wallMs), now), summary)
            assertEquals(base + 90 * MINUTE_MS, summary.anchorWallMs)
            // The screen-off state from before the charge rates the first hour after the unplug: 5 % over 2 h off.
            assertEquals(2.5, summary.screenOffPctPerHour!!, 1e-9)
        }

    @Test
    fun `unplugWindow falls back to the newest disconnect and ignores marks outside the range`() =
        runBlocking {
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

            val window = store.unplugWindow(from, to).snapshots

            assertEquals(listOf(screenOn, unplug, later, atNow), window.map { it.id })
            val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
            assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, to), now), summary)
            assertEquals(base + 30 * MINUTE_MS, summary.anchorWallMs)
        }

    @Test
    fun `unplugWindow keeps the screen state across a BOOT row of the same boot`() =
        runBlocking { assertUpdateKeepsScreenState(CaptureTrigger.BOOT) }

    @Test
    fun `unplugWindow keeps the screen state across an app update`() =
        runBlocking { assertUpdateKeepsScreenState(CaptureTrigger.PACKAGE_REPLACED) }

    /** An app update while charging, recorded as [updateTrigger] (0.2.3 used BOOT) in the same boot. */
    private suspend fun assertUpdateKeepsScreenState(updateTrigger: CaptureTrigger) {
        val base = 40L * DAY_MS
        store.insert(statusPoint(CaptureTrigger.APP_OPEN, base, plugged = true, level = 99))
        val screenOff = store.insert(statusPoint(CaptureTrigger.SCREEN_OFF, base + 10 * MINUTE_MS, plugged = true, level = 100))
        // The newest plugged row, so the mark.
        val updated = store.insert(statusPoint(updateTrigger, base + 20 * MINUTE_MS, plugged = true, level = 100))
        val unplug = store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, base + 30 * MINUTE_MS, plugged = false, level = 100))
        val now = statusPoint(CaptureTrigger.APP_OPEN, base + 150 * MINUTE_MS, plugged = false, level = 94)
        val from = now.clock.wallMs - 30L * DAY_MS

        val window = store.unplugWindow(from, now.clock.wallMs).snapshots

        assertEquals(listOf(screenOff, updated, unplug), window.map { it.id })
        assertEquals(updateTrigger, window[1].trigger)
        val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
        assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, now.clock.wallMs), now), summary)
        // The screen stayed off from before the update through the 2 h since the unplug: 6 % over 2 h.
        assertEquals(3.0, summary.screenOffPctPerHour!!, 1e-9)
    }

    @Test
    fun `unplugWindow forgets the screen state at a reboot the boot count misses`() =
        runBlocking {
            val base = 40L * DAY_MS

            // BOOT_COUNT unreadable (0): only elapsedRealtime running backwards shows the reboot after the mark.
            fun point(
                trigger: CaptureTrigger,
                minute: Long,
                elapsedMinute: Long,
                plugged: Boolean,
                level: Int,
            ) = statusPoint(trigger, base + minute * MINUTE_MS, plugged, level).let {
                it.copy(
                    clock =
                        it.clock.copy(
                            elapsedRealtimeMs = elapsedMinute * MINUTE_MS,
                            uptimeMs = elapsedMinute * MINUTE_MS,
                            bootCount = 0,
                        ),
                )
            }
            val screenOff = store.insert(point(CaptureTrigger.SCREEN_OFF, 10, elapsedMinute = 300, plugged = true, level = 99))
            val chargeJob = store.insert(point(CaptureTrigger.CHARGE_JOB, 20, elapsedMinute = 310, plugged = true, level = 100))
            val rebooted = store.insert(point(CaptureTrigger.APP_OPEN, 30, elapsedMinute = 2, plugged = false, level = 100))
            val now = point(CaptureTrigger.APP_OPEN, 150, elapsedMinute = 122, plugged = false, level = 94)
            val from = now.clock.wallMs - 30L * DAY_MS

            val window = store.unplugWindow(from, now.clock.wallMs).snapshots

            assertEquals(listOf(screenOff, chargeJob, rebooted), window.map { it.id })
            val summary = UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging
            assertEquals(UnplugSummaryCalculator.compute(store.snapshotsBetween(from, now.clock.wallMs), now), summary)
            assertEquals(6, summary.dropPct)
            assertNull(summary.screenOffPctPerHour)
            assertNull(summary.deepSleepRatio)
        }

    @Test
    fun `unplugWindow reads the whole range when it holds no unplug`() =
        runBlocking {
            val to = 40L * DAY_MS
            val from = to - 30L * DAY_MS
            store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, from - 1L, plugged = false, level = 100))
            store.insert(statusPoint(CaptureTrigger.APP_OPEN, from, plugged = null, level = 90))
            store.insert(statusPoint(CaptureTrigger.SCREEN_OFF, from + HOUR_MS, plugged = null, level = 89))
            store.insert(statusPoint(CaptureTrigger.APP_OPEN, to - HOUR_MS, plugged = null, level = 80))
            val now = statusPoint(CaptureTrigger.APP_OPEN, to, plugged = false, level = 79)

            val window = store.unplugWindow(from, to).snapshots

            assertEquals(store.snapshotsBetween(from, to), window)
            assertEquals(3, window.size)
            assertFalse((UnplugSummaryCalculator.compute(window, now) as UnplugSummary.Discharging).anchorIsUnplug)
        }

    @Test
    fun `unplugWindow adds the screen and plug history from the screen state before the window and keeps the summary`() =
        runBlocking {
            val base = 40L * DAY_MS
            store.insert(statusPoint(CaptureTrigger.APP_OPEN, base - 3 * HOUR_MS, plugged = true, level = 90))
            val screenOn = store.insert(statusPoint(CaptureTrigger.SCREEN_ON, base, plugged = true, level = 100))
            val chargeJob = store.insert(statusPoint(CaptureTrigger.CHARGE_JOB, base + 20 * MINUTE_MS, plugged = true, level = 100))
            val late = store.insert(statusPoint(CaptureTrigger.SCREEN_ON, base + 600 * MINUTE_MS, plugged = false, level = 87))
            val now = statusPoint(CaptureTrigger.APP_OPEN, base + 605 * MINUTE_MS, plugged = false, level = 86)
            val from = now.clock.wallMs - 30L * DAY_MS
            val seed = historyEvent(HistoryKind.SCREEN_ON, base - 2 * HOUR_MS, level = 95)
            val kept =
                listOf(
                    seed,
                    historyEvent(HistoryKind.UNPLUGGED, base + 30 * MINUTE_MS, level = 100),
                    historyEvent(HistoryKind.SCREEN_OFF, base + 60 * MINUTE_MS, level = 99),
                    historyEvent(HistoryKind.SCREEN_ON, base + 540 * MINUTE_MS, level = 91),
                )
            store.insertHistory(
                listOf(
                    historyEvent(HistoryKind.SCREEN_OFF, base - 3 * HOUR_MS, level = 90),
                    historyEvent(HistoryKind.LEVEL, base + 300 * MINUTE_MS, level = 95),
                ) + kept,
                statsStartMs = 1L,
            )

            val window = store.unplugWindow(from, now.clock.wallMs)

            assertEquals(listOf(screenOn, chargeJob, late), window.snapshots.map { it.id })
            assertEquals(kept, window.history)
            val summary = UnplugSummaryCalculator.compute(window.snapshots, now, window.history) as UnplugSummary.Discharging
            val whole =
                UnplugSummaryCalculator.compute(
                    store.snapshotsBetween(from, now.clock.wallMs),
                    now,
                    store.historyBetween(from, now.clock.wallMs),
                )
            assertEquals(whole, summary)
            assertEquals(base + 30 * MINUTE_MS, summary.anchorWallMs)
            assertEquals(8 / 8.0, summary.screenOffPctPerHour!!, 1e-9)
        }

    @Test
    fun `unplugWindow reaches back to a history unplug older than its snapshots and keeps the summary`() =
        runBlocking {
            val base = 40L * DAY_MS
            // No plugged row: the late disconnect is the mark, and its window starts at the screen capture before it.
            val open = store.insert(statusPoint(CaptureTrigger.APP_OPEN, base, plugged = null, level = 100))
            val screenOn = store.insert(statusPoint(CaptureTrigger.SCREEN_ON, base + 10 * MINUTE_MS, plugged = null, level = 99))
            val late = store.insert(statusPoint(CaptureTrigger.POWER_DISCONNECTED, base + 300 * MINUTE_MS, plugged = false, level = 90))
            val now = statusPoint(CaptureTrigger.APP_OPEN, base + 310 * MINUTE_MS, plugged = false, level = 89)
            val from = now.clock.wallMs - 30L * DAY_MS
            val kept =
                listOf(
                    historyEvent(HistoryKind.SCREEN_OFF, base - 30 * MINUTE_MS, level = 100),
                    historyEvent(HistoryKind.UNPLUGGED, base + 5 * MINUTE_MS, level = 100),
                    historyEvent(HistoryKind.SCREEN_ON, base + 9 * MINUTE_MS, level = 99),
                )
            store.insertHistory(listOf(historyEvent(HistoryKind.SCREEN_ON, base - HOUR_MS, level = 100)) + kept, statsStartMs = 1L)
            assertEquals(listOf(screenOn, late), database.snapshotDao().unplugWindow(from, now.clock.wallMs).map { it.id })

            val window = store.unplugWindow(from, now.clock.wallMs)

            assertEquals(listOf(open, screenOn, late), window.snapshots.map { it.id })
            assertEquals(kept, window.history)
            val summary = UnplugSummaryCalculator.compute(window.snapshots, now, window.history) as UnplugSummary.Discharging
            val whole =
                UnplugSummaryCalculator.compute(
                    store.snapshotsBetween(from, now.clock.wallMs),
                    now,
                    store.historyBetween(from, now.clock.wallMs),
                )
            assertEquals(whole, summary)
            assertEquals(base + 5 * MINUTE_MS, summary.anchorWallMs)
            assertEquals(11, summary.dropPct)
        }

    private fun historyEvent(
        kind: Int,
        wallMs: Long,
        level: Int,
    ) = HistoryEvent(
        historyTimeMs = wallMs,
        wallMs = wallMs,
        kind = kind,
        levelPct = level,
        chargeUah = -1L,
        temperatureDeciC = 300,
        voltageMv = 3_900,
        plugType = 0,
    )

    private fun statusPoint(
        trigger: CaptureTrigger,
        wallMs: Long,
        plugged: Boolean?,
        level: Int,
    ) = fullSnapshot(
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
        elapsedMs: Long = wallMs + 1L,
        system: SystemTimers? = timers(),
        uids: List<UidSnapshot> = listOf(uidSnapshot()),
        deviceComponentsMah: Map<String, Double> = linkedMapOf("screen" to 1.5, "wifi" to 2.5),
        kernelWakelocks: List<KernelWakelock> = listOf(KernelWakelock("kernel", 30L, 3L)),
        wakeupReasons: List<KernelWakelock> = listOf(KernelWakelock("reason", 40L, 4L)),
        cpuFreq: List<CpuFreqTime> = listOf(CpuFreqTime(0, 300_000L, 500L)),
        availability: Map<String, SourceAvailability> =
            linkedMapOf(
                "cpu" to SourceAvailability(true, null),
                "usage" to SourceAvailability(false, "unavailable"),
            ),
    ) = BatterySnapshot(
        id = 0L,
        kind = kind,
        trigger = trigger,
        clock = SnapshotClock(wallMs, elapsedMs, wallMs + 2L, bootCount, statsStartMs, 9L),
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

    private suspend fun assertLatestTrigger(
        expectedId: Long?,
        trigger: CaptureTrigger = CaptureTrigger.POWER_DISCONNECTED,
    ) {
        assertEquals(expectedId, store.latestByTrigger(trigger)?.id)
        val expectedClock = expectedId?.let { store.byId(it)!!.clock }
        assertEquals(expectedClock, store.latestClockByTrigger(trigger))
    }

    private fun triggerSnapshot(
        wallMs: Long,
        elapsedMs: Long,
        bootCount: Int,
        trigger: CaptureTrigger = CaptureTrigger.POWER_DISCONNECTED,
    ) = bootSnapshot(elapsedMs, wallMs, bootCount).copy(trigger = trigger)

    /** A snapshot of boot [bootCount], by default read while the wall clock still matches [BOOT_WALL_MS] + elapsed. */
    private fun bootSnapshot(
        elapsedMs: Long,
        wallMs: Long = BOOT_WALL_MS + elapsedMs,
        bootCount: Int = 1,
    ) = fullSnapshot(wallMs = wallMs, bootCount = bootCount).let {
        it.copy(clock = it.clock.copy(elapsedRealtimeMs = elapsedMs, uptimeMs = elapsedMs))
    }

    private fun levelEvent(wallMs: Long) =
        HistoryEvent(
            historyTimeMs = 5_000L,
            wallMs = wallMs,
            kind = HistoryKind.LEVEL,
            levelPct = 61,
            chargeUah = -1L,
            temperatureDeciC = 300,
            voltageMv = 4_000,
            plugType = 0,
        )

    private fun segment(
        startHistoryTimeMs: Long,
        endWallMs: Long,
    ) = ChargeSegment(
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

    private fun uidSnapshot() =
        UidSnapshot(
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

    private fun timers() =
        SystemTimers(
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

        /** Boot 1 of the clock-jump tests started at this wall time. */
        const val BOOT_WALL_MS = 100L * DAY_MS
    }

    private class TestClocks(
        private val wallMs: Long,
        private val elapsedMs: Long,
        private val bootCount: Int = 1,
    ) : Clocks {
        override fun wallMs() = wallMs

        override fun elapsedRealtimeMs() = elapsedMs

        override fun uptimeMs() = elapsedMs

        override fun bootCount() = bootCount
    }
}
