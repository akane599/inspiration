package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `toolkit_action` retention: display rows expire past the newest 500, override chain links do not. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolkitActionRetentionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "toolkit-retention-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun bucketRow(
        status: ToolkitActionStatus,
        before: String?,
        after: String?,
    ) = ToolkitActionRecord(
        startedAt = 1L,
        kind = "bucket",
        pkg = "a.b",
        op = "standby_bucket",
        param = "rare",
        status = status,
        beforeRaw = before,
        afterRaw = after,
    )

    private suspend fun fillDisplayHistory(rows: Int) =
        repeat(rows) { n ->
            store.insertToolkitAction(
                ToolkitActionRecord(startedAt = 100L + n, kind = "force_stop", op = "force_stop", status = ToolkitActionStatus.REFUSED),
            )
        }

    @Test
    fun `a failed write that moved its target outlives the display bound and one that did not expires`() =
        runBlocking {
            val landed = store.insertToolkitAction(bucketRow(ToolkitActionStatus.FAILED, before = "10", after = "30"))
            val unchanged = store.insertToolkitAction(bucketRow(ToolkitActionStatus.FAILED, before = "20", after = "20"))
            val unread = store.insertToolkitAction(bucketRow(ToolkitActionStatus.FAILED, before = "20", after = null))

            fillDisplayHistory(510)

            val ids = store.toolkitActions(Int.MAX_VALUE).map { it.id }
            assertTrue(landed in ids)
            assertFalse(unchanged in ids)
            assertFalse(unread in ids)
            assertEquals(500, ids.count { it > unread })
        }
}
