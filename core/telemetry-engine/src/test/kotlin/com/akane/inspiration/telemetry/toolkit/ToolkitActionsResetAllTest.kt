package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SQ-86: `resetAll` puts each active override back to the value before the first change of its chain. */
class ToolkitActionsResetAllTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val op = AppOp.RUN_ANY_IN_BACKGROUND

    private fun opNow() = backend.device(PKG)[op]

    @Test
    fun resetAllDoesNotRestrictAnAppTheUserSwitchedBackByHand() =
        runTest {
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT))
            backend.writes.clear()

            val report = actions.resetAll()

            assertEquals("default", opNow())
            assertTrue("no write may restrict the app again: ${backend.writes}", backend.writes.none { "ignore" in it })
            // back at its original value, so it is no active override at all (SQ-92)
            assertTrue(report.isEmpty())
        }

    @Test
    fun backToTheOriginalByTwoChangesWritesNothing() =
        runTest {
            backend.device(PKG)[op] = "ignore" // the user's own setting before this app touched it
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT))
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
            backend.writes.clear()

            val report = actions.resetAll()

            assertTrue(backend.writes.isEmpty())
            assertEquals("ignore", opNow())
            assertTrue(report.isEmpty())
        }

    @Test
    fun twoChangesToTheSameBucketRestoreTheOriginalNotTheIntermediate() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

            val report = actions.resetAll()

            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals(1, report.size)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun twoDozeProfilesRestoreTheOriginalNotTheIntermediate() =
        runTest {
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT))

            val report = actions.resetAll()

            assertNull(backend.dozeRaw)
            assertEquals(1, report.size)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun aChainEndsWhereSomethingElseChangedTheTarget() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue // changed outside the app
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true)

            val report = actions.resetAll()

            // the value found before the second change, not the one before the first
            assertEquals(StandbyBucket.FREQUENT.rawValue, backend.buckets[PKG])
            assertEquals(1, report.size)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun anOverrideChangedExternallyIsNotTouched() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue
            backend.device(PKG)[op] = "default"
            backend.dozeRaw = "inactive_to=1"
            backend.writes.clear()

            val report = actions.resetAll()

            assertTrue(report.isEmpty())
            assertTrue(backend.writes.isEmpty())
            assertEquals(StandbyBucket.WORKING_SET.rawValue, backend.buckets[PKG])
            assertEquals("default", opNow())
            assertEquals("inactive_to=1", backend.dozeRaw)
        }

    @Test
    fun resetAllRefusesAPartialBucketRestoreWhenTheTargetDivergesAfterEnumeration() =
        runTest {
            backend.buckets[PKG] = StandbyBucket.RARE.rawValue
            val initial =
                (
                    actions.execute(
                        ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED),
                        confirmed = true,
                    ) as ToolkitOutcome.Done
                ).value
            backend.scriptedWrite =
                ToolkitResult.failed(
                    ToolkitCode.NOT_EFFECTIVE,
                    before = initial.afterRaw,
                    after = "${StandbyBucket.FREQUENT.rawValue}",
                )
            backend.afterWrite = { backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue }
            assertTrue(actions.restore(initial.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val partial = audit.rows.last()

            assertStaleReset(partial.id, "getBucket($PKG)") { backend.buckets[PKG] = StandbyBucket.WORKING_SET.rawValue }

            assertEquals(StandbyBucket.WORKING_SET.rawValue, backend.buckets[PKG])
        }

    @Test
    fun resetAllRefusesAnAppOpThatDivergesAfterEnumeration() =
        runTest {
            val row = (actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true) as ToolkitOutcome.Done).value
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "ignore", after = "foreground")
            backend.afterWrite = { backend.device(PKG)[op] = "foreground" }
            assertTrue(actions.restore(row.id) is ToolkitOutcome.Failed)
            backend.afterWrite = {}
            val partial = audit.rows.last()

            assertStaleReset(partial.id, "getAppOps($PKG)") { backend.device(PKG)[op] = "errored" }

            assertEquals("errored", opNow())
        }

    @Test
    fun resetAllRefusesDozeThatDivergesAfterEnumeration() =
        runTest {
            val row = (actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED)) as ToolkitOutcome.Done).value

            assertStaleReset(row.id, "getDozeState") { backend.dozeRaw = "inactive_to=77" }

            assertEquals("inactive_to=77", backend.dozeRaw)
        }

    private suspend fun assertStaleReset(
        actionId: Long,
        readName: String,
        diverge: () -> Unit,
    ) {
        backend.writes.clear()
        val before = audit.byId(actionId)
        val rowCount = audit.rows.size
        var reads = 0
        backend.onRead = { name -> if (name == readName && ++reads == 2) diverge() }

        val report = actions.resetAll()

        assertEquals(2, reads)
        assertEquals(actionId, report.single().override.actionId)
        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.STALE, ToolkitCode.STALE), report.single().outcome)
        assertTrue("the external change is not a reset target", backend.writes.isEmpty())
        assertEquals(before, audit.byId(actionId))
        assertEquals(rowCount + 1, audit.rows.size)
        assertEquals(ToolkitActionStatus.REFUSED, audit.rows.last().status)
        assertEquals(actionId, audit.rows.last().restoresId)
        assertEquals(ToolkitCode.STALE, audit.rows.last().code)
    }

    @Test
    fun anOverrideWithoutARestorableOriginalIsRefusedNotReplayedFromTheIntermediate() =
        runTest {
            backend.nullBeforeOnBucketWrite = true
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            backend.nullBeforeOnBucketWrite = false
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))
            backend.writes.clear()

            val report = actions.resetAll()

            assertEquals(listOf<ToolkitOutcome<*>>(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE)), report.map { it.outcome })
            assertTrue(backend.writes.isEmpty())
        }
}
