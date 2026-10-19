package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SQ-92: an override is active only while the read-back differs from the chain ORIGINAL (the oldest `before`
 * of the unbroken chain), and `activeOverrides`, `resetAll`, `planRestore` and `restore` all agree on that.
 */
class ToolkitActionsChainTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val op = AppOp.RUN_ANY_IN_BACKGROUND

    private fun opNow() = backend.device(PKG)[op]

    private suspend fun run(action: ToolkitAction): ToolkitActionRecord =
        (actions.execute(action, confirmed = true) as ToolkitOutcome.Done).value

    private suspend fun assertNoOverrides() = assertTrue(actions.activeOverrides().first().isEmpty())

    private suspend fun assertRestoreWritesNothing(row: ToolkitActionRecord) {
        backend.writes.clear()
        assertEquals(ToolkitPlan.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.planRestore(row.id))
        assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.ALREADY_RESTORED), actions.restore(row.id, confirmed = true))
        assertTrue("no write expected: ${backend.writes}", backend.writes.isEmpty())
    }

    // ---- m1: a chain that ends where it began is not an override ---------------------------

    @Test
    fun appOpIgnoreThenAllowIsNoOverrideAndItsRestoreWritesNothing() =
        runTest {
            run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            val back = run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.ALLOW))

            assertNoOverrides()
            assertRestoreWritesNothing(back)
            assertEquals("allow", opNow())
            backend.writes.clear()
            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun bucketRestrictedThenBackToTheOriginalIsNoOverrideAndItsRestoreWritesNothing() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))
            val back = run(ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE))

            assertNoOverrides()
            assertRestoreWritesNothing(back)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        }

    @Test
    fun bucketAThroughBBackToAIsNoOverride() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))
            val back = run(ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE))

            assertNoOverrides()
            assertRestoreWritesNothing(back)
            backend.writes.clear()
            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun dozeBalancedThenStandardWhenItWasUnsetIsNoOverride() =
        runTest {
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            val back = run(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))

            assertNoOverrides()
            assertRestoreWritesNothing(back)
            assertNull(backend.dozeRaw)
        }

    // ---- per-item restore goes to the chain original ---------------------------------------

    @Test
    fun restoreOfTheNewestRowOfAChainWritesTheChainOriginalNotTheIntermediate() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            val newest = run(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))
            backend.writes.clear()

            assertEquals(ToolkitPlan.Ready, actions.planRestore(newest.id))
            assertTrue(actions.restore(newest.id) is ToolkitOutcome.Done)

            assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertNoOverrides()
        }

    @Test
    fun restoreOfTheNewestRowRefusesWhenTheChainOriginalIsNotRestorable() =
        runTest {
            backend.nullBeforeOnBucketWrite = true
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            backend.nullBeforeOnBucketWrite = false
            val newest = run(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))
            backend.writes.clear()

            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.planRestore(newest.id))
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(newest.id, confirmed = true))
            assertTrue(backend.writes.isEmpty())
            assertEquals(
                false,
                actions
                    .activeOverrides()
                    .first()
                    .single()
                    .restorable,
            )
        }

    @Test
    fun restoreOfARowInTheMiddleOfAChainGoesToTheChainOriginal() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            val middle = run(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            // the target is no longer at the middle row's `after`, so this needs confirmation
            assertEquals(ToolkitWarning.RESTORE_STATE_CHANGED, (actions.planRestore(middle.id) as ToolkitPlan.NeedsConfirmation).warning)
            assertTrue(actions.restore(middle.id, confirmed = true) is ToolkitOutcome.Done)

            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(middle.id).status)
        }

    // ---- m2: app op modes in the chain walk (F-01: `default` is not `allow`) ----------------

    @Test
    fun aChainDoesNotJoinAcrossDefaultAndAllow() =
        runTest {
            backend.device(PKG)[op] = "ignore" // the user's own setting
            run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.ALLOW)) // after "allow"
            backend.device(PKG)[op] = "default" // something else stored the restricting `default` mode
            val newest = run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE)) // before "default"

            // the gap ends the chain: the newest row's original is `default`, i.e. allow (see originalState)
            val override = actions.activeOverrides().first().single()
            assertEquals(newest.id, override.actionId)
            assertEquals("allow", override.previousRaw)
        }

    // ---- m4: chain shapes -----------------------------------------------------------------

    @Test
    fun aFailedRowBetweenTwoRowsOfOneTargetDoesNotBreakTheChain() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            backend.writeFailure = IllegalStateException("binder exploded")
            try {
                actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.WORKING_SET))
            } catch (_: IllegalStateException) {
            }
            backend.writeFailure = null
            actions.reconcile() // Only readback can prove the throwing write had no effect.
            assertEquals(ToolkitActionStatus.FAILED, audit.rows.last().status)
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            val report = actions.resetAll()

            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
        }

    @Test
    fun aPendingRowBetweenTwoRowsOfOneTargetDoesNotBreakTheChain() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            audit.insert(
                ToolkitActionRecord(
                    startedAt = 1L,
                    kind = "bucket",
                    pkg = PKG,
                    op = "set",
                    param = "working_set",
                    status = ToolkitActionStatus.PENDING,
                    beforeRaw = StandbyBucket.RARE.rawValue.toString(),
                ),
            )
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            val report = actions.resetAll()

            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
        }

    @Test
    fun aCustomDozeRawIsTheChainOriginalAndComesBack() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            run(ToolkitAction.ApplyDoze(DozeProfile.AGGRESSIVE_NIGHT))

            val report = actions.resetAll()

            assertEquals("inactive_to=1234", backend.dozeRaw)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertNoOverrides()
        }

    // ---- F-06: a custom original replaced via Balanced -> Standard ---------------------------

    @Test
    fun customOriginalThroughBalancedThenStandardIsAnOverrideRestoringTheCustomValue() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            // Standard would delete the setting whose chain original is custom: the user is warned
            assertEquals(
                ToolkitPlan.NeedsConfirmation(ToolkitWarning.DOZE_OVER_CUSTOM, emptyList()),
                actions.plan(ToolkitAction.ApplyDoze(DozeProfile.STANDARD)),
            )
            val standard = run(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))
            assertNull(backend.dozeRaw)

            val override = actions.activeOverrides().first().single()
            assertEquals(standard.id, override.actionId)
            assertEquals(OverrideKind.DOZE, override.kind)
            assertNull(override.currentRaw)
            assertEquals("inactive_to=1234", override.previousRaw)
            assertTrue(override.restorable)

            backend.writes.clear()
            val report = actions.resetAll()

            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            // written back through the compare-and-swap: expected current is unset
            assertEquals(listOf("restoreDoze(inactive_to=1234,null)"), backend.writes)
            assertEquals("inactive_to=1234", backend.dozeRaw)
            assertNoOverrides()
        }

    @Test
    fun restoringTheStandardRowOfACustomChainWritesTheCustomValue() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            val standard = run(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))
            backend.writes.clear()

            assertEquals(ToolkitPlan.Ready, actions.planRestore(standard.id))
            assertTrue(actions.restore(standard.id) is ToolkitOutcome.Done)

            assertEquals(listOf("restoreDoze(inactive_to=1234,null)"), backend.writes)
            assertEquals("inactive_to=1234", backend.dozeRaw)
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(standard.id).status)
            assertNoOverrides()
        }

    @Test
    fun aCustomChainLeftUnsetButSetAgainExternallyIsNotAnOverride() =
        runTest {
            backend.dozeRaw = "inactive_to=1234"
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            run(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))
            backend.dozeRaw = "inactive_to=99" // someone else set it since

            assertNoOverrides()
            backend.writes.clear()
            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun standardOverBalancedWithAnUnsetOriginalNeedsNoCustomWarning() =
        runTest {
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))

            assertEquals(ToolkitPlan.Ready, actions.plan(ToolkitAction.ApplyDoze(DozeProfile.STANDARD)))
        }

    @Test
    fun anEmptyStoredDozeBeforeMeansUnsetAndIsRestoredAsUnset() =
        runTest {
            backend.dozeRaw = DozeProfile.BALANCED.constants
            val row =
                audit.insert(
                    ToolkitActionRecord(
                        startedAt = 1L,
                        endedAt = 2L,
                        kind = "doze",
                        op = "apply_profile",
                        param = "balanced",
                        status = ToolkitActionStatus.DONE,
                        beforeRaw = "",
                        afterRaw = DozeProfile.BALANCED.constants,
                    ),
                )
            assertEquals(
                setOf(row),
                actions
                    .activeOverrides()
                    .first()
                    .map { it.actionId }
                    .toSet(),
            )

            val report = actions.resetAll()

            assertNull(backend.dozeRaw)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
        }

    @Test
    fun interleavedBucketAndAppOpRowsOnOnePackageAreChainsOfTheirOwn() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            val bucket = run(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED))
            run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.ALLOW))

            // the app op came back to its original, the bucket did not
            assertEquals(listOf(bucket.id), actions.activeOverrides().first().map { it.actionId })
            backend.writes.clear()

            val report = actions.resetAll()

            assertEquals(listOf("setBucket($PKG,active)"), backend.writes)
            assertEquals(1, report.size)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        }

    @Test
    fun aSecondResetAllAfterASuccessfulOneIsANoOp() =
        runTest {
            run(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            run(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            run(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            assertEquals(3, actions.resetAll().count { it.outcome is ToolkitOutcome.Done })
            backend.writes.clear()

            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }
}
