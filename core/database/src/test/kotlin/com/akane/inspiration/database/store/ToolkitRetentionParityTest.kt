package com.akane.inspiration.database.store

import androidx.test.core.app.ApplicationProvider
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitAuditLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parity between the hand-written `toolkit_action` retention SQL (`ToolkitActionDao.pruneTo`) and the toolkit's
 * chain predicate ([ToolkitAuditLog.changedTarget]): every status × kind × before/after case is pushed past the
 * display bound through the real `insertAndPrune`, and every row a chain walk needs must survive (audit R-18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolkitRetentionParityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var databaseName: String
    private lateinit var database: InspirationDatabase
    private lateinit var store: RoomSnapshotStore

    @Before
    fun setUp() {
        databaseName = "toolkit-parity-${System.nanoTime()}.db"
        database = InspirationDatabase.create(context, databaseName)
        store = RoomSnapshotStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private val kinds =
        listOf(
            ToolkitAuditLog.KIND_APP_OP,
            ToolkitAuditLog.KIND_BUCKET,
            ToolkitAuditLog.KIND_DOZE,
            ToolkitAuditLog.KIND_FORCE_STOP,
            ToolkitAuditLog.KIND_BG_DEXOPT,
        )

    /** Unknown, empty (unset for doze), and two distinct known values: every equality the predicates test. */
    private val raws = listOf(null, "", "allow", "ignore")

    private fun cases(): List<ToolkitActionRecord> =
        ToolkitActionStatus.entries.flatMap { status ->
            kinds.flatMap { kind ->
                raws.flatMap { before ->
                    raws.map { after ->
                        ToolkitActionRecord(
                            startedAt = 1L,
                            kind = kind,
                            pkg = "a.b",
                            op = "op",
                            status = status,
                            beforeRaw = before,
                            afterRaw = after,
                        )
                    }
                }
            }
        }

    /** Inserts every case (settled at once, or PENDING then settled the way the toolkit writes), then buries it. */
    private suspend fun runTable(settleAfterInsert: Boolean): Pair<List<ToolkitActionRecord>, Set<Long>> {
        val inserted =
            cases().map { case ->
                if (settleAfterInsert) {
                    val id = store.insertToolkitAction(case.copy(status = ToolkitActionStatus.PENDING))
                    case.copy(id = id).also { store.updateToolkitAction(it) }
                } else {
                    case.copy(id = store.insertToolkitAction(case))
                }
            }
        assertTrue(inserted.size < ToolkitAuditLog.RETAINED_ROWS)
        repeat(ToolkitAuditLog.RETAINED_ROWS + 10) { n ->
            store.insertToolkitAction(
                ToolkitActionRecord(
                    startedAt = 10L + n,
                    kind = ToolkitAuditLog.KIND_FORCE_STOP,
                    op = "force_stop",
                    status = ToolkitActionStatus.REFUSED,
                ),
            )
        }
        val retained = store.toolkitActions(Int.MAX_VALUE).map { it.id }.toSet()
        return inserted to retained
    }

    private fun assertParity(
        inserted: List<ToolkitActionRecord>,
        retained: Set<Long>,
    ) {
        val needed =
            inserted.filter {
                it.status == ToolkitActionStatus.PENDING || (it.kind in ToolkitAuditLog.OVERRIDE_KINDS && ToolkitAuditLog.changedTarget(it))
            }
        val lost = needed.filter { it.id !in retained }
        assertTrue("chain rows the prune deleted: $lost", lost.isEmpty())
        // The table must exercise the FAILED branch, and retention must still bound what no chain needs.
        assertTrue(needed.any { it.status == ToolkitActionStatus.FAILED })
        val neverNeeded =
            inserted.filter {
                it.status != ToolkitActionStatus.PENDING &&
                    (it.kind !in ToolkitAuditLog.OVERRIDE_KINDS || it.status in setOf(ToolkitActionStatus.REFUSED, ToolkitActionStatus.CANCELLED))
            }
        assertEquals(emptyList<ToolkitActionRecord>(), neverNeeded.filter { it.id in retained })
    }

    @Test
    fun `retention keeps every row the chain predicate needs past the display bound`() =
        runBlocking {
            val (inserted, retained) = runTable(settleAfterInsert = false)
            assertParity(inserted, retained)
        }

    @Test
    fun `retention keeps every chain row that settled after its pending insert`() =
        runBlocking {
            val (inserted, retained) = runTable(settleAfterInsert = true)
            assertParity(inserted, retained)
        }
}
