package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-01: Android restricts background work whenever RUN_ANY_IN_BACKGROUND is not MODE_ALLOWED, and a stored
 * `default` mode is enforced as restricted. The permissive write is therefore `allow`, a live `default` is not
 * "allowed", and rows recorded by older releases (which stored "No operations." as `default` and wrote `default`
 * for "allow") restore and repair with `allow`.
 */
class ToolkitActionsAllowModeTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val op = AppOp.RUN_ANY_IN_BACKGROUND

    private fun opNow() = backend.device(PKG)[op]

    private suspend fun legacyRow(
        before: String,
        after: String,
        param: String,
    ): ToolkitActionRecord {
        val row =
            ToolkitActionRecord(
                startedAt = clock++,
                endedAt = clock++,
                kind = ToolkitAuditLog.KIND_APP_OP,
                pkg = PKG,
                op = op.argv,
                param = param,
                status = ToolkitActionStatus.DONE,
                code = 0,
                beforeRaw = before,
                afterRaw = after,
            )
        return row.copy(id = audit.insert(row))
    }

    @Test
    fun theAllowRequestWritesAllowNotDefault() =
        runTest {
            backend.device(PKG)[op] = "ignore"

            val outcome = actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.ALLOW), confirmed = true)

            assertTrue(outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", opNow())
        }

    @Test
    fun aDefaultWriteIsRefusedBeforeAnyWrite() =
        runTest {
            val outcome = actions.execute(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT), confirmed = true)

            assertEquals(ToolkitRefusal.INVALID_ARGUMENT, (outcome as ToolkitOutcome.Refused).reason)
            assertTrue(backend.writes.isEmpty())
            assertEquals(
                ToolkitPlan.Refused(ToolkitRefusal.INVALID_ARGUMENT),
                actions.plan(ToolkitAction.SetAppOp(PKG, op, AppOpMode.DEFAULT)),
            )
        }

    @Test
    fun aLiveDefaultReadingIsNotAllowed() =
        runTest {
            backend.device(PKG)[op] = "default"

            val state = (actions.appState(PKG) as ToolkitOutcome.Done).value

            assertEquals(AppOpMode.DEFAULT, state.appOps[op])
            assertEquals(AppOpMode.ALLOW, state.appOps[AppOp.WAKE_LOCK])
        }

    @Test
    fun restrictThenAllowThenResetAllRestoresWithAllow() =
        runTest {
            val restrict =
                (
                    actions.execute(
                        ToolkitAction.SetAppOp(PKG, op, AppOpMode.IGNORE),
                        confirmed = true,
                    ) as ToolkitOutcome.Done
                ).value
            assertEquals("allow", restrict.beforeRaw)
            backend.writes.clear()

            val report = actions.resetAll()

            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", opNow())
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun aLegacyNoOperationsOriginalRecordedAsDefaultRestoresWithAllow() =
        runTest {
            val row = legacyRow(before = "default", after = "ignore", param = "ignore")
            backend.device(PKG)[op] = "ignore"

            val override = actions.activeOverrides().first().single()
            assertEquals(row.id, override.actionId)
            assertEquals("allow", override.previousRaw)
            assertTrue(override.restorable)

            assertTrue(actions.restore(row.id, confirmed = true) is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", opNow())
            assertEquals(ToolkitActionStatus.RESTORED, audit.byId(row.id).status)
        }

    @Test
    fun aTargetLeftAtDefaultByAnOlderReleaseIsAnOverrideResetAllRepairs() =
        runTest {
            // older release: restrict (No operations. recorded as "default"), then "allow" wrote `default`
            legacyRow(before = "default", after = "ignore", param = "ignore")
            val allowRow = legacyRow(before = "ignore", after = "default", param = "default")
            backend.device(PKG)[op] = "default"

            val override = actions.activeOverrides().first().single()
            assertEquals(allowRow.id, override.actionId)
            assertEquals("default", override.currentRaw)
            assertEquals("allow", override.previousRaw)

            val report = actions.resetAll()

            assertTrue(report.single().outcome is ToolkitOutcome.Done)
            assertEquals(listOf("setAppOp($PKG,${op.argv},allow)"), backend.writes)
            assertEquals("allow", opNow())
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun aLegacyPendingDefaultWriteSettlesAgainstTheExactMode() =
        runTest {
            val pending =
                ToolkitActionRecord(
                    startedAt = clock++,
                    kind = ToolkitAuditLog.KIND_APP_OP,
                    pkg = PKG,
                    op = op.argv,
                    param = "default",
                    status = ToolkitActionStatus.PENDING,
                    beforeRaw = "ignore",
                ).let { it.copy(id = audit.insert(it)) }
            backend.device(PKG)[op] = "allow"

            actions.reconcile()

            // `allow` is not what that write stored, so it did not land: the row is not settled as DONE
            assertEquals(ToolkitActionStatus.FAILED, audit.byId(pending.id).status)
            assertTrue(backend.writes.isEmpty())
        }
}
