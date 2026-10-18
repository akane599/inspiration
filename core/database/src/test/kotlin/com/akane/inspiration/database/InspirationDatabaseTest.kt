package com.akane.inspiration.database

import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class InspirationDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase

    @Before
    fun setUp() {
        databaseName = "inspiration-test-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `opens with write ahead logging enabled`() {
        database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("wal", cursor.getString(0).lowercase())
        }
    }

    @Test
    fun `deleting a snapshot cascades to its children`() = runBlocking {
        val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
        database.snapshotDao().insertAvailability(
            SnapshotAvailabilityEntity(snapshotId, source = "usage", available = true),
        )

        assertEquals(1, database.snapshotDao().deleteById(snapshotId))
        assertTrue(database.snapshotDao().availabilityForSnapshot(snapshotId).isEmpty())
    }

    @Test
    fun `deleting a snapshot cascades to its uid sensors`() = runBlocking {
        val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
        database.snapshotDao().insertUidSensors(
            listOf(SnapshotUidSensorEntity(snapshotId, uid = 10_001, handle = -10_000, totalMs = 5L, bgMs = 1L, count = 2L)),
        )
        assertEquals(1, database.snapshotDao().uidSensorsForSnapshot(snapshotId).size)

        assertEquals(1, database.snapshotDao().deleteById(snapshotId))
        assertTrue(database.snapshotDao().uidSensorsForSnapshot(snapshotId).isEmpty())
    }

    @Test
    fun `history events deduplicate by stats history time and kind`() = runBlocking {
        val event = HistoryEventEntity(
            statsStartMs = 10L,
            historyTimeMs = 20L,
            wallMs = 30L,
            kind = "STATE",
        )

        val insertResults = database.historyDao().insertIgnore(listOf(event, event))

        assertTrue(insertResults[0] > 0L)
        assertEquals(-1L, insertResults[1])
        assertEquals(1, database.historyDao().between(0L, 100L).size)
    }

    @Test
    fun `wakelock inserts merge duplicate primary keys by summing counters`() = runBlocking {
        val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
        database.snapshotDao().mergeWakelock(snapshotId, "PARTIAL", 1000, "sync", 10L, 4L, 1L)
        database.snapshotDao().mergeWakelock(snapshotId, "PARTIAL", 1000, "sync", 15L, 6L, 2L)

        val wakelock = database.snapshotDao().wakelocksForSnapshot(snapshotId).single()
        assertEquals(25L, wakelock.durationMs)
        assertEquals(10L, wakelock.bgDurationMs)
        assertEquals(3L, wakelock.count)
    }

    @Test
    fun `prune retains marker referenced snapshots and cascades unmarked children`() = runBlocking {
        val now = 100L * DAY_MS
        val oldSnapshotId = database.snapshotDao().insert(snapshot(wallMs = now - 31L * DAY_MS))
        val unmarkedSnapshotId = database.snapshotDao().insert(snapshot(wallMs = now - 31L * DAY_MS + 1L))
        val currentSnapshotId = database.snapshotDao().insert(snapshot(wallMs = now - DAY_MS))
        database.snapshotDao().insertAvailability(
            SnapshotAvailabilityEntity(unmarkedSnapshotId, source = "usage", available = true),
        )
        database.snapshotDao().insertUidSensors(
            listOf(SnapshotUidSensorEntity(unmarkedSnapshotId, uid = 10_001, handle = 3, totalMs = 5L, bgMs = 1L, count = 2L)),
        )
        database.markerDao().insert(MarkerEntity(snapshotId = oldSnapshotId, label = "baseline", wallMs = now))

        database.maintenanceDao().prune(now)

        assertNotNull(database.snapshotDao().byId(oldSnapshotId))
        assertNull(database.snapshotDao().byId(unmarkedSnapshotId))
        assertNotNull(database.snapshotDao().byId(currentSnapshotId))
        assertTrue(database.snapshotDao().availabilityForSnapshot(unmarkedSnapshotId).isEmpty())
        assertTrue(database.snapshotDao().uidSensorsForSnapshot(unmarkedSnapshotId).isEmpty())
    }

    @Test
    fun `prune aggregates samples older than thirty days into hourly rows`() = runBlocking {
        val now = 100L * DAY_MS
        val hourStart = 12L * MaintenanceDao.HOUR_MS
        database.telemetryDao().insertSamples(
            listOf(
                TelemetrySampleEntity(
                    wallMs = hourStart + 5L,
                    elapsedRealtimeMs = 5L,
                    bootCount = 1,
                    currentMa = 10,
                    voltageMv = 4_000,
                    watts = 40.0,
                    temperatureDeciC = 300,
                    levelPct = 40,
                ),
                TelemetrySampleEntity(
                    wallMs = hourStart + 10L,
                    elapsedRealtimeMs = 10L,
                    bootCount = 1,
                    currentMa = 20,
                    voltageMv = 4_200,
                    watts = 84.0,
                    temperatureDeciC = 320,
                    levelPct = 60,
                ),
            ),
        )

        database.maintenanceDao().prune(now)

        val aggregate = database.telemetryDao().hourly(hourStart)
        assertNotNull(aggregate)
        assertEquals(2L, aggregate!!.sampleCount)
        assertEquals(15.0, aggregate.avgCurrentMa!!, 0.001)
        assertEquals(10, aggregate.minCurrentMa)
        assertEquals(20, aggregate.maxCurrentMa)
        assertEquals(4_100.0, aggregate.avgVoltageMv!!, 0.001)
        assertEquals(62.0, aggregate.avgWatts!!, 0.001)
        assertEquals(320, aggregate.maxTemperatureDeciC)
        assertEquals(40, aggregate.minLevelPct)
        assertEquals(60, aggregate.maxLevelPct)
    }

    @Test
    fun `successive prunes within one hour preserve all samples in the final aggregate`() = runBlocking {
        val hour = 12L * MaintenanceDao.HOUR_MS
        database.telemetryDao().insertSamples(listOf(
            TelemetrySampleEntity(wallMs = hour + 5, elapsedRealtimeMs = 5, bootCount = 1, currentMa = 10, voltageMv = 4_000),
            TelemetrySampleEntity(wallMs = hour + 15, elapsedRealtimeMs = 15, bootCount = 1, currentMa = 30, voltageMv = 4_200),
        ))
        val maintenance = database.maintenanceDao()
        maintenance.prune(MaintenanceDao.RETENTION_MS + hour + 10)
        maintenance.prune(MaintenanceDao.RETENTION_MS + hour + 20)
        assertNull(database.telemetryDao().hourly(hour))
        assertEquals(2, database.telemetryDao().between(hour, hour + MaintenanceDao.HOUR_MS).size)

        maintenance.prune(MaintenanceDao.RETENTION_MS + hour + MaintenanceDao.HOUR_MS)
        val aggregate = database.telemetryDao().hourly(hour)!!
        assertEquals(2L, aggregate.sampleCount)
        assertEquals(20.0, aggregate.avgCurrentMa!!, 0.0)
        assertEquals(10, aggregate.minCurrentMa)
        assertEquals(30, aggregate.maxCurrentMa)
        assertTrue(database.telemetryDao().between(hour, hour + MaintenanceDao.HOUR_MS).isEmpty())
        maintenance.prune(MaintenanceDao.RETENTION_MS + hour + MaintenanceDao.HOUR_MS + 10)
        assertEquals(aggregate, database.telemetryDao().hourly(hour))
    }

    private fun snapshot(wallMs: Long) = SnapshotEntity(
        kind = "FULL",
        trigger = "APP_OPEN",
        wallMs = wallMs,
        elapsedRealtimeMs = wallMs,
        uptimeMs = wallMs,
        bootCount = 1,
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
