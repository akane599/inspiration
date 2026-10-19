package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-07: a write that never reached the service (no binding in time, a stale API on its own session) changed nothing.
 * It is final at once, so a later read-back cannot turn it into an override of ours that Reset all would "restore",
 * and an unsent dexopt start does not hold the card in Unknown. A write lost after dispatch still stays PENDING.
 */
class ToolkitActionsUnsentWriteTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val active = StandbyBucket.ACTIVE.rawValue

    @Test
    fun anUnsentBucketWriteIsFinalAndADriftedTargetIsNotOurs() =
        runTest {
            backend.unsentWrite = GatewayUnavailable.NO_SERVICE

            val outcome = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), outcome)
            assertTrue(backend.writes.isEmpty())
            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.FAILED, row.status)
            assertEquals(ToolkitClientCode.NO_SERVICE, row.code)
            assertNull(row.afterRaw)
            assertNotNull(row.endedAt)

            // Shizuku stays down for a while and the bucket decays on its own, then the service is back.
            backend.unsentWrite = null
            backend.buckets[PKG] = StandbyBucket.FREQUENT.rawValue
            actions.reconcile()

            assertEquals(row, audit.byId(row.id))
            assertTrue(actions.activeOverrides().first().isEmpty())
            assertTrue(actions.resetAll().isEmpty())
            assertTrue("Reset all must not write: ${backend.writes}", backend.writes.isEmpty())
            assertEquals(StandbyBucket.FREQUENT.rawValue, backend.buckets[PKG])
        }

    @Test
    fun anUnsentWriteIsNotClaimedWhenTheTargetReachesTheIntendedValueByItself() =
        runTest {
            val op = AppOp.RUN_IN_BACKGROUND
            backend.unsentWrite = GatewayUnavailable.STALE_SERVICE

            val outcome = actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE), confirmed = true)

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.STALE_SERVICE), outcome)
            assertEquals(ToolkitClientCode.STALE_SERVICE, audit.rows.single().code)
            // Something else (the user in Settings, say) sets the very value the unsent write asked for.
            backend.unsentWrite = null
            backend.device(PKG)[op] = AppOpMode.IGNORE.argv
            actions.reconcile()

            assertEquals(ToolkitActionStatus.FAILED, audit.rows.single().status)
            assertTrue(actions.activeOverrides().first().isEmpty())
            assertTrue(actions.resetAll().isEmpty())
            assertEquals(AppOpMode.IGNORE.argv, backend.device(PKG)[op])
        }

    @Test
    fun aWriteLostAfterDispatchStillStaysPendingForReadBack() =
        runTest {
            backend.writeUnavailable = GatewayUnavailable.NO_SERVICE

            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)

            val row = audit.rows.single()
            assertEquals(ToolkitActionStatus.PENDING, row.status)
            assertEquals(active.toString(), row.beforeRaw)
        }

    @Test
    fun anUnsentDexoptStartLeavesTheCardIdleNotUnknown() =
        runTest {
            backend.unsentWrite = GatewayUnavailable.NO_SERVICE

            val outcome = actions.startBgDexopt()

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.NO_SERVICE), outcome)
            assertEquals(ToolkitActionStatus.FAILED, audit.rows.single().status)
            assertTrue(audit.rows.none { it.status == ToolkitActionStatus.PENDING })

            // The service comes back as a fresh process that cannot know about earlier jobs.
            backend.unsentWrite = null
            backend.dexoptStatuses.clear()
            backend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))

            assertEquals(BgDexoptStatus.Idle, actions.bgDexoptStatus().first())
            assertEquals(0, holds.opened)
        }
}
