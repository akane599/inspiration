package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-01, F-13: a row belongs to its target's override chain when the values read back around it show the target
 * changed, whatever the write was reported as. A write the platform moved to a third value (reported not
 * effective) stays listed, resettable and restorable; a write of the value already there does not hide the
 * override it repeats.
 */
class ToolkitActionsObservedChangeTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val active = StandbyBucket.ACTIVE.rawValue
    private val working = StandbyBucket.WORKING_SET.rawValue
    private val frequent = StandbyBucket.FREQUENT.rawValue
    private val rare = StandbyBucket.RARE.rawValue

    private suspend fun bucketOverride(): Override? =
        actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.BUCKET && it.pkg == PKG }

    /** [bucket] is requested; the platform clamps it to [landsOn] and the service reports what it read back. */
    private suspend fun clampedWrite(
        bucket: StandbyBucket,
        landsOn: Int,
    ): ToolkitOutcome<ToolkitActionRecord> {
        val before = backend.buckets[PKG] ?: active
        backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "$before", after = "$landsOn")
        backend.afterWrite = { name ->
            if (name == "setBucket($PKG,${bucket.argv})") backend.buckets[PKG] = landsOn
        }
        val outcome = actions.execute(ToolkitAction.SetBucket(PKG, bucket), confirmed = true)
        backend.afterWrite = {}
        assertEquals("precondition: the write did change the device", landsOn, backend.buckets[PKG])
        return outcome
    }

    // ---- F-01: a write that landed on a third value ----------------------------------------

    @Test
    fun aWriteThatLandedOnAThirdValueIsReportedNotEffectiveButListedAndResetAllPutsItBack() =
        runTest {
            backend.buckets[PKG] = active

            val outcome = clampedWrite(StandbyBucket.RARE, landsOn = frequent)

            assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals("$active", row.beforeRaw)
            assertEquals("$frequent", row.afterRaw)

            val override = bucketOverride()
            assertNotNull("the device moved ACTIVE -> FREQUENT; rows=${audit.rows}", override)
            assertEquals(row.id, override?.actionId)
            assertEquals("$frequent", override?.currentRaw)
            assertEquals("$active", override?.previousRaw)

            backend.writes.clear()
            val report = actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(active, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
            assertNull(bucketOverride())
        }

    @Test
    fun aWriteThatLandedOnAThirdValueCanBeRestoredFromHistory() =
        runTest {
            backend.buckets[PKG] = active
            clampedWrite(StandbyBucket.RARE, landsOn = frequent)
            val row = audit.rows.single()

            assertEquals(ToolkitPlan.Ready, actions.planRestore(row.id))
            backend.writes.clear()
            assertTrue(actions.restore(row.id) is ToolkitOutcome.Done)

            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertEquals(active, backend.buckets[PKG])
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
        }

    @Test
    fun aWriteThatLandedOnAThirdValueOnTopOfAnOverrideKeepsTheChainOriginal() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            clampedWrite(StandbyBucket.RESTRICTED, landsOn = working)

            val override = bucketOverride()
            assertNotNull("ACTIVE -> RARE -> WORKING_SET is still ours; rows=${audit.rows}", override)
            assertEquals("$working", override?.currentRaw)
            assertEquals("$active", override?.previousRaw)
            backend.writes.clear()
            actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertEquals(active, backend.buckets[PKG])
        }

    @Test
    fun aNotEffectiveWriteThatChangedNothingIsNoLinkAndLeavesTheChainIntact() =
        runTest {
            val first = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            backend.scriptedWrite = ToolkitResult.failed(ToolkitCode.NOT_EFFECTIVE, before = "$rare", after = "$rare")

            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.FREQUENT))

            assertEquals(ToolkitOutcome.Failed(ToolkitCode.NOT_EFFECTIVE), outcome)
            val override = bucketOverride()
            assertEquals(first.id, override?.actionId)
            assertEquals("$active", override?.previousRaw)
            val failed = audit.rows.last()
            assertEquals(ToolkitActionStatus.FAILED, failed.status)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.NOT_RESTORABLE), actions.restore(failed.id))
        }

    @Test
    fun aReconciledWriteThatLandedOnAThirdValueStaysInItsChain() =
        runTest {
            backend.buckets[PKG] = frequent // RARE was issued from ACTIVE and clamped; the reply was lost
            val id =
                audit.insert(
                    ToolkitActionRecord(
                        startedAt = 1L,
                        kind = "bucket",
                        pkg = PKG,
                        op = "standby_bucket",
                        param = StandbyBucket.RARE.argv,
                        status = ToolkitActionStatus.PENDING,
                        beforeRaw = "$active",
                    ),
                )

            actions.reconcile()

            val row = audit.byId(id)
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals(ToolkitCode.NOT_EFFECTIVE, row.code)
            assertEquals("$frequent", row.afterRaw)
            val override = bucketOverride()
            assertEquals(id, override?.actionId)
            assertEquals("$active", override?.previousRaw)
            actions.resetAll()
            assertEquals(active, backend.buckets[PKG])
        }

    // ---- F-13: a write of the value already there --------------------------------------------

    @Test
    fun repeatingTheSameBucketKeepsTheOverrideListedAndResettable() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            assertEquals("precondition: one listed override", 1, actions.activeOverrides().first().size)

            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            assertEquals(rare, backend.buckets[PKG])
            val override = bucketOverride()
            assertNotNull("the device is still RARE; rows=${audit.rows}", override)
            assertEquals("$active", override?.previousRaw)
            backend.writes.clear()
            actions.resetAll()
            assertEquals(listOf("setBucket($PKG,${StandbyBucket.ACTIVE.argv})"), backend.writes)
            assertNull(bucketOverride())
        }

    @Test
    fun repeatingTheSameAppOpModeKeepsTheOverrideListed() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))
            actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE))

            assertEquals("ignore", backend.device(PKG)[op])
            val override = actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.APP_OP && it.appOp == op }
            assertNotNull("rows=${audit.rows}", override)
            assertEquals("allow", override?.previousRaw)
        }

    @Test
    fun repeatingTheSameDozeProfileKeepsTheOverrideListedAndResetUnsetsIt() =
        runTest {
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))

            val override = actions.activeOverrides().first().singleOrNull { it.kind == OverrideKind.DOZE }
            assertNotNull("rows=${audit.rows}", override)
            assertNull(override?.previousRaw)
            actions.resetAll()
            assertNull(backend.dozeRaw)
        }

    @Test
    fun aSameValueWriteWithNoEarlierChangeIsNoOverride() =
        runTest {
            backend.buckets[PKG] = rare

            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))

            assertTrue(actions.activeOverrides().first().isEmpty())
            backend.writes.clear()
            assertTrue(actions.resetAll().isEmpty())
            assertTrue(backend.writes.isEmpty())
        }
}
