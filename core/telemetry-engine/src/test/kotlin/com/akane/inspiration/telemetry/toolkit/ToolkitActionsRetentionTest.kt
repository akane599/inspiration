package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
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

    @Test
    fun anOpenPartialRestoreSurvivesPruningAndRecreation() =
        runTest {
            backend.buckets[A] = StandbyBucket.RARE.rawValue
            val change = bucket(A, StandbyBucket.RESTRICTED)
            val partial = clampedRestore(change)
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertTrue("the failed restore still owns FREQUENT", partial !in audit.deleted)
            val recreated = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })
            val override = recreated.activeOverrides().first().single { it.pkg == A }
            assertEquals(partial, override.actionId)
            assertEquals("${StandbyBucket.FREQUENT.rawValue}", override.currentRaw)
            assertEquals("${StandbyBucket.RARE.rawValue}", override.previousRaw)
            assertTrue(recreated.restore(partial) is ToolkitOutcome.Done)
            assertEquals(StandbyBucket.RARE.rawValue, backend.buckets[A])
        }

    @Test
    fun repeatedWritesAboveADivergentPartialRestoreKeepItsTypedOriginalAfterTheOldPrefixIsPruned() =
        runTest {
            backend.buckets[A] = StandbyBucket.RARE.rawValue
            val change = bucket(A, StandbyBucket.RESTRICTED)
            backend.buckets[A] = StandbyBucket.WORKING_SET.rawValue // an explicitly confirmed divergence
            val partial = clampedRestore(change)
            val repeated = List(3) { bucket(A, StandbyBucket.FREQUENT) }
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertTrue("the pre-divergence prefix can go", change in audit.deleted)
            assertTrue("the typed original must survive: ${audit.deleted}", (repeated + partial).none { it in audit.deleted })
            val recreated = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })
            val override = recreated.activeOverrides().first().single { it.pkg == A }
            assertEquals(repeated.last(), override.actionId)
            assertEquals("${StandbyBucket.FREQUENT.rawValue}", override.currentRaw)
            assertEquals("${StandbyBucket.RARE.rawValue}", override.previousRaw)
            assertTrue(recreated.resetAll().all { it.outcome is ToolkitOutcome.Done })
            assertEquals(StandbyBucket.RARE.rawValue, backend.buckets[A])

            fillDisplayHistory()
            bucket(TRIGGER, StandbyBucket.RARE)
            assertTrue("the now-closed partial chain can go", (repeated + partial).all { it in audit.deleted })
        }

    @Test
    fun writesAboveAPartialForegroundAppOpRestoreRetainItsOriginalThroughPruningAndRecreation() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            val initial = (actions.execute(ToolkitAction.SetAppOp(A, op, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "ignore", after = "foreground")
            backend.afterWrite = { backend.device(A)[op] = "foreground" }
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val partial = audit.rows.last().id
            val repeated =
                List(3) {
                    (actions.execute(ToolkitAction.SetAppOp(A, op, AppOpMode.IGNORE)) as ToolkitOutcome.Done).value.id
                }
            assertEquals("foreground", audit.byId(repeated.first()).beforeRaw)
            assertEquals(
                "allow",
                actions
                    .activeOverrides()
                    .first()
                    .single()
                    .previousRaw,
            )
            fillDisplayHistory()

            bucket(TRIGGER, StandbyBucket.RARE)

            assertTrue("raw foreground equality must preserve the continuation", (repeated + partial).none { it in audit.deleted })
            val recreated = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })
            val override = recreated.activeOverrides().first().single { it.kind == OverrideKind.APP_OP }
            assertEquals(repeated.last(), override.actionId)
            assertEquals("ignore", override.currentRaw)
            assertEquals("allow", override.previousRaw)
            backend.writes.clear()
            val report = recreated.resetAll()
            assertTrue(report.single { it.override.kind == OverrideKind.APP_OP }.outcome is ToolkitOutcome.Done)
            assertTrue("the original allow is replayed, not foreground", "setAppOp($A,${op.argv},allow)" in backend.writes)
            assertEquals("allow", backend.device(A)[op])
            assertTrue(recreated.activeOverrides().first().isEmpty())
        }

    private suspend fun clampedRestore(actionId: Long): Long {
        val before = backend.buckets.getValue(A)
        backend.scriptedWrite =
            ToolkitResult.failed(
                ToolkitCode.NOT_EFFECTIVE,
                before = "$before",
                after = "${StandbyBucket.FREQUENT.rawValue}",
            )
        backend.afterWrite = { backend.buckets[A] = StandbyBucket.FREQUENT.rawValue }
        assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), actions.restore(actionId, confirmed = true))
        backend.afterWrite = {}
        return audit.rows.last().id
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
