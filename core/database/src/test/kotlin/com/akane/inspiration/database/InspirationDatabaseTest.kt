package com.akane.inspiration.database

import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
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
    fun `version one health samples survive migration and accept missing cycle counts`() =
        runBlocking {
            database.close()
            val schema =
                org.json
                    .JSONObject(
                        java.io
                            .File(
                                "schemas/com.akane.inspiration.database.InspirationDatabase/1.json",
                            ).readText(),
                    ).getJSONObject("database")
            context.openOrCreateDatabase(databaseName, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) {
                        old.execSQL(
                            indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")),
                        )
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO battery_health_sample VALUES (1, 4800, 5000, 123, 4700)")
                old.version = 1
            }
            database = InspirationDatabase.create(context, databaseName)
            val original = HealthSampleEntity(1, 4800.0, 5000.0, 123, null)
            assertEquals(listOf(original), database.healthDao().all())
            val missingCycles = HealthSampleEntity(2, 4750.0, 5000.0, null)
            database.healthDao().insert(missingCycles)
            assertEquals(listOf(original, missingCycles), database.healthDao().all())
        }

    @Test
    fun `version two derived estimates are cleared once while raw kernel health survives`() =
        runBlocking {
            database.healthDao().insert(HealthSampleEntity(1, 4800.0, 5000.0, 123, 4500.0))
            database.chargeSessionDao().upsert(
                ChargeSessionEntity(
                    statsStartMs = 1,
                    startHistoryTimeMs = 100,
                    startWallMs = 100,
                    endWallMs = 200,
                    startPct = 20,
                    endPct = 80,
                    estimateMah = 4500.0,
                    independent = true,
                    accepted = true,
                ),
            )
            database.close()
            context.openOrCreateDatabase(databaseName, 0, null).use { it.version = 2 }
            database = InspirationDatabase.create(context, databaseName)
            assertTrue(database.chargeSessionDao().all().isEmpty())
            assertEquals(listOf(HealthSampleEntity(1, 4800.0, 5000.0, 123, null)), database.healthDao().all())
            database.healthDao().insert(HealthSampleEntity(2, 4800.0, 5000.0, 123, 4700.0))
            database.close()
            database = InspirationDatabase.create(context, databaseName)
            assertEquals(
                4700.0,
                database
                    .healthDao()
                    .all()
                    .last()
                    .measuredMah!!,
                0.0,
            )
        }

    @Test
    fun `version three databases gain the trigger index and keep their snapshots`() =
        runBlocking {
            database.close()
            context.openOrCreateDatabase(databaseName, 0, null).use { old ->
                createExportedSchema(old, version = 3)
                old.execSQL(
                    "INSERT INTO snapshot (kind, trigger, wallMs, elapsedRealtimeMs, uptimeMs, bootCount) " +
                        "VALUES ('LIGHT', 'SCREEN_ON', 5, 6, 7, 1)",
                )
                assertFalse(TRIGGER_INDEX in old.rawQuery("PRAGMA index_list(snapshot)", null).strings("name"))
                old.version = 3
            }

            // Opening runs MIGRATION_3_4, then Room checks the result against the version 4 entities.
            database = InspirationDatabase.create(context, databaseName)

            assertEquals(5L, database.snapshotDao().latestByTrigger("SCREEN_ON")!!.wallMs)
            val sqlite = database.openHelper.readableDatabase
            assertEquals(listOf("trigger", "wallMs"), sqlite.query("PRAGMA index_info(`$TRIGGER_INDEX`)").strings("name"))
            assertLatestByTriggerSearchesTheIndex()
        }

    @Test
    fun `latestByTrigger searches the trigger index instead of scanning snapshots`() {
        assertLatestByTriggerSearchesTheIndex()
    }

    @Test
    fun `opens with write ahead logging enabled`() {
        database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("wal", cursor.getString(0).lowercase())
        }
    }

    @Test
    fun `deleting a snapshot cascades to its children`() =
        runBlocking {
            val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
            database.snapshotDao().insertAvailability(
                SnapshotAvailabilityEntity(snapshotId, source = "usage", available = true),
            )

            assertEquals(1, database.snapshotDao().deleteById(snapshotId))
            assertTrue(database.snapshotDao().availabilityForSnapshot(snapshotId).isEmpty())
        }

    @Test
    fun `deleting a snapshot cascades to its uid sensors`() =
        runBlocking {
            val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
            database.snapshotDao().insertUidSensors(
                listOf(SnapshotUidSensorEntity(snapshotId, uid = 10_001, handle = -10_000, totalMs = 5L, bgMs = 1L, count = 2L)),
            )
            assertEquals(1, database.snapshotDao().uidSensorsForSnapshot(snapshotId).size)

            assertEquals(1, database.snapshotDao().deleteById(snapshotId))
            assertTrue(database.snapshotDao().uidSensorsForSnapshot(snapshotId).isEmpty())
        }

    @Test
    fun `history events deduplicate by stats history time and kind`() =
        runBlocking {
            val event =
                HistoryEventEntity(
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
    fun `wakelock inserts merge duplicate primary keys by summing counters`() =
        runBlocking {
            val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
            database.snapshotDao().mergeWakelock(snapshotId, "PARTIAL", 1000, "sync", 10L, 4L, 1L)
            database.snapshotDao().mergeWakelock(snapshotId, "PARTIAL", 1000, "sync", 15L, 6L, 2L)

            val wakelock = database.snapshotDao().wakelocksForSnapshot(snapshotId).single()
            assertEquals(25L, wakelock.durationMs)
            assertEquals(10L, wakelock.bgDurationMs)
            assertEquals(3L, wakelock.count)
        }

    @Test
    fun `batched wakelock merge inserts new keys and adds to stored or repeated keys`() =
        runBlocking {
            val snapshotId = database.snapshotDao().insert(snapshot(wallMs = 1L))
            database.snapshotDao().mergeWakelock(snapshotId, "PARTIAL", 1000, "sync", 10L, 4L, 1L)

            database.snapshotDao().mergeWakelocks(
                listOf(
                    SnapshotWakelockEntity(snapshotId, "PARTIAL", 1000, "sync", 15L, 6L, 2L),
                    SnapshotWakelockEntity(snapshotId, "PARTIAL", 1001, "sync", 7L, 1L, 1L),
                    SnapshotWakelockEntity(snapshotId, "KERNEL", -1, "sync", 5L, 0L, 3L),
                    SnapshotWakelockEntity(snapshotId, "KERNEL", -1, "sync", 1L, 0L, 1L),
                ),
            )

            assertEquals(
                listOf(
                    SnapshotWakelockEntity(snapshotId, "KERNEL", -1, "sync", 6L, 0L, 4L),
                    SnapshotWakelockEntity(snapshotId, "PARTIAL", 1000, "sync", 25L, 10L, 3L),
                    SnapshotWakelockEntity(snapshotId, "PARTIAL", 1001, "sync", 7L, 1L, 1L),
                ),
                database.snapshotDao().wakelocksForSnapshot(snapshotId),
            )
        }

    @Test
    fun `prune retains marker referenced snapshots and cascades unmarked children`() =
        runBlocking {
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
    fun `prune aggregates samples older than thirty days into hourly rows`() =
        runBlocking {
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
    fun `successive prunes within one hour preserve all samples in the final aggregate`() =
        runBlocking {
            val hour = 12L * MaintenanceDao.HOUR_MS
            database.telemetryDao().insertSamples(
                listOf(
                    TelemetrySampleEntity(wallMs = hour + 5, elapsedRealtimeMs = 5, bootCount = 1, currentMa = 10, voltageMv = 4_000),
                    TelemetrySampleEntity(wallMs = hour + 15, elapsedRealtimeMs = 15, bootCount = 1, currentMa = 30, voltageMv = 4_200),
                ),
            )
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

    private fun snapshot(wallMs: Long) =
        SnapshotEntity(
            kind = "FULL",
            trigger = "APP_OPEN",
            wallMs = wallMs,
            elapsedRealtimeMs = wallMs,
            uptimeMs = wallMs,
            bootCount = 1,
        )

    /** Creates the tables, indices and Room metadata of exported schema [version] in [db]. */
    private fun createExportedSchema(
        db: android.database.sqlite.SQLiteDatabase,
        version: Int,
    ) {
        val schema =
            org.json
                .JSONObject(
                    java.io
                        .File(
                            "schemas/com.akane.inspiration.database.InspirationDatabase/$version.json",
                        ).readText(),
                ).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
    }

    /** Mirrors `SnapshotDao.latestByTrigger`: the trigger index filters rows; elapsed ordering may need a sort. */
    private fun assertLatestByTriggerSearchesTheIndex() {
        for (currentBoot in listOf<Int?>(null, 7)) {
            val plan =
                database.openHelper.readableDatabase
                    .query(
                        """
                        EXPLAIN QUERY PLAN SELECT * FROM snapshot WHERE trigger = ?
                        ORDER BY CASE WHEN ? IS NOT NULL AND bootCount = ? THEN 0 ELSE 1 END,
                            CASE WHEN ? IS NOT NULL AND bootCount = ? THEN elapsedRealtimeMs END DESC,
                            id DESC
                        LIMIT 1
                        """.trimIndent(),
                        arrayOf<Any?>("SCREEN_ON", currentBoot, currentBoot, currentBoot, currentBoot),
                    ).strings("detail")
            assertTrue(plan.toString(), plan.any { "INDEX $TRIGGER_INDEX" in it })
            assertFalse(plan.toString(), plan.any { it.startsWith("SCAN") })
        }
    }

    private fun Cursor.strings(column: String): List<String> =
        use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow(column))) }
        }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
        const val TRIGGER_INDEX = "index_snapshot_trigger_wallMs"
    }
}
