package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit log keeps the newest [ToolkitAuditLog.RETAINED_ROWS] rows plus what an override chain still
 * needs. Links older than that go once their chain is closed or cut; an open chain keeps every link its
 * original is found through.
 */
class ToolkitActionsRetentionTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    @Test
    fun aClosedChainOlderThanTheDisplayHistoryIsDropped() =
        runTest {
            val closed = listOf(bucket(A, StandbyBucket.RARE), bucket(A, StandbyBucket.ACTIVE))
            val open = bucket(B, StandbyBucket.RARE)
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertEquals(closed, audit.deleted.sorted())
            assertTrue("the open chain's link is kept", audit.rows.any { it.id == open })
            val overrides = actions.activeOverrides().first().associateBy { it.pkg }
            assertEquals(setOf(B, TRIGGER), overrides.keys)
            assertEquals("${StandbyBucket.ACTIVE.rawValue}", overrides.getValue(B).previousRaw)
        }

    @Test
    fun aClosedPrefixGoesAndTheOpenChainAboveItKeepsItsOriginal() =
        runTest {
            val closed = listOf(bucket(A, StandbyBucket.RARE), bucket(A, StandbyBucket.ACTIVE))
            val open = listOf(bucket(A, StandbyBucket.RARE), bucket(A, StandbyBucket.FREQUENT))
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertEquals(closed, audit.deleted.sorted())
            assertTrue(audit.rows.map { it.id }.containsAll(open))
            val override = actions.activeOverrides().first().single { it.pkg == A }
            assertEquals("${StandbyBucket.ACTIVE.rawValue}", override.previousRaw)
            actions.resetAll()
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[A])
        }

    @Test
    fun anOpenChainKeepsEveryLinkItsOriginalNeeds() =
        runTest {
            val chain =
                listOf(
                    bucket(A, StandbyBucket.RARE),
                    bucket(A, StandbyBucket.FREQUENT),
                    bucket(A, StandbyBucket.WORKING_SET),
                )
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertTrue("nothing of the open chain goes: ${audit.deleted}", audit.deleted.none { it in chain })
            assertEquals(
                "${StandbyBucket.ACTIVE.rawValue}",
                actions
                    .activeOverrides()
                    .first()
                    .single { it.pkg == A }
                    .previousRaw,
            )
            actions.resetAll()
            assertEquals("back to the value before the first change", StandbyBucket.ACTIVE.rawValue, backend.buckets[A])
        }

    @Test
    fun aRestoredChangeAndItsRestoreGoTogether() =
        runTest {
            val change = bucket(A, StandbyBucket.RARE)
            val restored = actions.restore(change)
            val restore = (restored as ToolkitOutcome.Done).value.id
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertEquals(listOf(change, restore), audit.deleted.sorted())
        }

    @Test
    fun aTargetWithAnUnsettledWriteIsLeftAlone() =
        runTest {
            bucket(A, StandbyBucket.RARE)
            bucket(A, StandbyBucket.ACTIVE)
            audit.insert(
                ToolkitActionRecord(
                    startedAt = clock++,
                    kind = ToolkitAuditLog.KIND_BUCKET,
                    pkg = A,
                    op = "standby_bucket",
                    param = StandbyBucket.RARE.argv,
                    status = ToolkitActionStatus.PENDING,
                    beforeRaw = "${StandbyBucket.ACTIVE.rawValue}",
                ),
            )
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertEquals(emptyList<Long>(), audit.deleted)
        }

    @Test
    fun nothingWithinTheDisplayHistoryIsDropped() =
        runTest {
            bucket(A, StandbyBucket.RARE)
            bucket(A, StandbyBucket.ACTIVE)
            fillDisplayHistory(ToolkitAuditLog.RETAINED_ROWS - 2)

            bucket(TRIGGER, StandbyBucket.RARE)

            assertEquals(emptyList<Long>(), audit.deleted)
        }

    /** Executes a bucket change and returns its audit row id. */
    private suspend fun bucket(
        pkg: String,
        bucket: StandbyBucket,
    ): Long {
        val outcome = actions.execute(ToolkitAction.SetBucket(pkg, bucket), confirmed = true)
        return (outcome as ToolkitOutcome.Done).value.id
    }

    /** Refused force-stops: display history that belongs to no chain. */
    private suspend fun fillDisplayHistory(rows: Int = ToolkitAuditLog.RETAINED_ROWS) {
        repeat(rows) {
            audit.insert(
                ToolkitActionRecord(
                    startedAt = clock++,
                    endedAt = clock,
                    kind = ToolkitAuditLog.KIND_FORCE_STOP,
                    pkg = "com.example.other",
                    op = "force_stop",
                    status = ToolkitActionStatus.REFUSED,
                ),
            )
        }
    }

    private companion object {
        const val A = "com.example.a"
        const val B = "com.example.b"
        const val TRIGGER = "com.example.trigger"
    }
}
