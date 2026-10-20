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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N-06: a change of ours whose target cannot be read back, or that became protected, must not vanish from the
 * overrides list and Reset all while it is still on the device. A protected app keeps a restriction of ours
 * restorable (a lift passes rule d), a lift of ours is listed but not restorable while the app stays protected (its
 * undo would restrict it again), and an undecidable protection check has its own refusal.
 */
class ToolkitActionsUnverifiedOverrideTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val background = AppOp.RUN_ANY_IN_BACKGROUND

    private suspend fun restrict(pkg: String = PKG) {
        val outcome = actions.execute(ToolkitAction.SetAppOp(pkg, background, AppOpMode.IGNORE), confirmed = true)
        assertTrue("$outcome", outcome is ToolkitOutcome.Done)
    }

    @Test
    fun aRestrictionOnAnAppThatBecameProtectedStaysListedAndResetAllLiftsIt() =
        runTest {
            restrict()
            // The app becomes the default keyboard, SMS app or an accessibility service.
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED

            val override = actions.activeOverrides().first().single()
            assertEquals(OverrideKind.APP_OP, override.kind)
            assertEquals("ignore", override.currentRaw)
            assertNull(override.unverified)

            val report = actions.resetAll()

            assertTrue("${report.single().outcome}", report.single().outcome is ToolkitOutcome.Done)
            assertEquals("allow", backend.device(PKG)[background])
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun aBucketWeLoweredOnANowProtectedAppIsRestoredButNotLoweredAgain() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            val override = actions.activeOverrides().first().single()

            assertTrue("undoing our restriction lifts it, so it stays offered", override.restorable)
            assertEquals(ToolkitPlan.Ready, actions.planRestore(override.actionId))
            assertTrue(actions.restore(override.actionId) is ToolkitOutcome.Done)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])

            backend.writes.clear()
            val again = actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE), confirmed = true)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.PROTECTED_APP, ToolkitCode.DENY_PROTECTED), again)
            assertTrue(backend.writes.isEmpty())
        }

    @Test
    fun aLiftOfOursOnAnAppThatBecameProtectedIsListedButNotOfferedForUndoUntilItIsNoLongerProtected() =
        runTest {
            // Restricted by the user or another tool; we lifted it; then the app became protected. Undoing the lift
            // would restrict a protected app again, which rule d refuses (lift-only), so no undo is offered for it.
            backend.device(PKG)[background] = "ignore"
            assertTrue(actions.execute(ToolkitAction.SetAppOp(PKG, background, AppOpMode.ALLOW), confirmed = true) is ToolkitOutcome.Done)
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            backend.writes.clear()

            val override = actions.activeOverrides().first().single()
            assertEquals("allow", override.currentRaw)
            assertEquals("ignore", override.previousRaw)
            assertNull(override.unverified)
            assertFalse("an undo that re-restricts a protected app is not offered", override.restorable)

            val refused = ToolkitOutcome.Refused(ToolkitRefusal.PROTECTED_APP, ToolkitCode.DENY_PROTECTED)
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.PROTECTED_APP), actions.planRestore(override.actionId))
            assertEquals(refused, actions.restore(override.actionId, confirmed = true))
            assertEquals(refused, actions.resetAll().single().outcome)
            assertTrue("nothing may be written: ${backend.writes}", backend.writes.isEmpty())
            assertEquals("allow", backend.device(PKG)[background])

            // No longer protected: the same change is offered for undo again and puts the restriction back.
            backend.protected.clear()
            val again = actions.activeOverrides().first().single()
            assertTrue(again.restorable)
            assertTrue(actions.restore(again.actionId, confirmed = true) is ToolkitOutcome.Done)
            assertEquals("ignore", backend.device(PKG)[background])
        }

    @Test
    fun aBucketWeRaisedOnANowProtectedAppIsListedButNotOfferedForUndo() =
        runTest {
            backend.buckets[PKG] = StandbyBucket.RARE.rawValue
            assertTrue(actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE), confirmed = true) is ToolkitOutcome.Done)
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            backend.writes.clear()

            val override = actions.activeOverrides().first().single()
            assertEquals(StandbyBucket.ACTIVE.rawValue.toString(), override.currentRaw)
            assertFalse(override.restorable)
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.PROTECTED_APP), actions.planRestore(override.actionId))
            assertTrue(backend.writes.isEmpty())
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        }

    @Test
    fun aProtectedAppRefusesEveryRestrictingChangeBeforeAnythingIsWritten() =
        runTest {
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            val refused = ToolkitOutcome.Refused(ToolkitRefusal.PROTECTED_APP, ToolkitCode.DENY_PROTECTED)

            assertEquals(refused, actions.appState(PKG))
            assertEquals(ToolkitPlan.Refused(ToolkitRefusal.PROTECTED_APP), actions.plan(ToolkitAction.ForceStop(PKG)))
            assertEquals(refused, actions.execute(ToolkitAction.SetAppOp(PKG, background, AppOpMode.IGNORE), confirmed = true))
            assertEquals(refused, actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RESTRICTED), confirmed = true))
            assertEquals(refused, actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true))
            assertTrue(backend.writes.isEmpty())
            assertTrue(audit.rows.all { it.status == ToolkitActionStatus.REFUSED && it.code == ToolkitCode.DENY_PROTECTED })
        }

    @Test
    fun aProtectedAppTakesNoNewChangeEvenOneThatLiftsARestriction() =
        runTest {
            // Restricted by the user or another tool, not by us: there is no change of ours to undo, and a new
            // change would start a chain whose original could never be written back to a protected app.
            backend.device(PKG)[background] = "ignore"
            backend.buckets[PKG] = StandbyBucket.RARE.rawValue
            val allow = ToolkitAction.SetAppOp(PKG, background, AppOpMode.ALLOW)
            val active = ToolkitAction.SetBucket(PKG, StandbyBucket.ACTIVE)

            for (code in listOf(ToolkitCode.DENY_PROTECTED, ToolkitCode.PROTECTION_UNKNOWN)) {
                backend.protected[PKG] = code
                val reason = if (code == ToolkitCode.DENY_PROTECTED) ToolkitRefusal.PROTECTED_APP else ToolkitRefusal.PROTECTION_UNKNOWN

                assertEquals(ToolkitPlan.Refused(reason), actions.plan(allow))
                assertEquals(ToolkitPlan.Refused(reason), actions.plan(active))
                assertEquals(ToolkitOutcome.Refused(reason, code), actions.execute(allow, confirmed = true))
                assertEquals(ToolkitOutcome.Refused(reason, code), actions.execute(active, confirmed = true))
            }

            assertTrue("nothing may be written: ${backend.writes}", backend.writes.isEmpty())
            assertEquals("ignore", backend.device(PKG)[background])
            assertEquals(StandbyBucket.RARE.rawValue, backend.buckets[PKG])
            assertTrue(actions.activeOverrides().first().isEmpty())
        }

    @Test
    fun anUpdatedSystemAppKeepsItsWarningWhenItBecomesProtected() =
        runTest {
            backend.updatedSystem += PKG
            restrict()
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            val override = actions.activeOverrides().first().single()

            // The protected read carries the refusal in its code; the updated-system warning still reaches the plan.
            assertEquals(ToolkitPlan.NeedsConfirmation(ToolkitWarning.UPDATED_SYSTEM_APP), actions.planRestore(override.actionId))
            assertEquals(
                ToolkitOutcome.Refused(ToolkitRefusal.NOT_CONFIRMED, ToolkitClientCode.NOT_CONFIRMED),
                actions.restore(override.actionId),
            )
            assertEquals("ignore", backend.device(PKG)[background])
        }

    @Test
    fun anUndecidableProtectionCheckHasItsOwnRefusal() =
        runTest {
            backend.protected[PKG] = ToolkitCode.PROTECTION_UNKNOWN
            val refused = ToolkitOutcome.Refused(ToolkitRefusal.PROTECTION_UNKNOWN, ToolkitCode.PROTECTION_UNKNOWN)

            assertEquals(refused, actions.appState(PKG))
            assertEquals(refused, actions.execute(ToolkitAction.SetAppOp(PKG, background, AppOpMode.IGNORE), confirmed = true))
            assertTrue(backend.writes.isEmpty())

            // An older service answers the same code without a value; it is still its own refusal.
            backend.protected.clear()
            backend.denied[PKG] = ToolkitCode.PROTECTION_UNKNOWN
            assertEquals(refused, actions.appState(PKG))
        }

    @Test
    fun anOverrideWhoseReadIsRefusedOutrightStaysListedAsUnverifiedAndResetAllReportsIt() =
        runTest {
            restrict()
            // Updates uninstalled: now a pure system app, every call refused with no value.
            backend.denied[PKG] = ToolkitCode.DENY_SYSTEM

            val override = actions.activeOverrides().first().single()
            assertEquals(ToolkitRefusal.PROTECTED_APP, override.unverified)
            assertEquals("ignore", override.currentRaw)
            assertEquals("allow", override.previousRaw)

            val item = actions.resetAll().single()

            assertEquals(override.actionId, item.override.actionId)
            assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.PROTECTED_APP, ToolkitCode.DENY_SYSTEM), item.outcome)
            assertEquals("ignore", backend.device(PKG)[background])
            assertEquals(listOf(override.actionId), actions.activeOverrides().first().map { it.actionId })
        }

    @Test
    fun aFailedReadBackKeepsEveryOverrideListedAsUnverified() =
        runTest {
            restrict()
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED), confirmed = true)
            backend.scriptedRead = ToolkitResult.failed(ToolkitCode.PARSE)

            val overrides = actions.activeOverrides().first()

            assertEquals(setOf(OverrideKind.APP_OP, OverrideKind.DOZE), overrides.map { it.kind }.toSet())
            assertTrue(overrides.all { it.unverified == ToolkitRefusal.READ_FAILED })
            assertEquals(DozeProfile.BALANCED.constants, overrides.single { it.kind == OverrideKind.DOZE }.currentRaw)

            backend.scriptedRead = null
            val fresh = actions.activeOverrides().first()
            assertEquals(2, fresh.size)
            assertTrue(fresh.all { it.unverified == null })
        }

    @Test
    fun anUninstalledAppTakesItsOverrideWithIt() =
        runTest {
            restrict()
            backend.denied[PKG] = ToolkitCode.NOT_INSTALLED

            assertTrue(actions.activeOverrides().first().isEmpty())
            assertTrue(actions.resetAll().isEmpty())
        }

    @Test
    fun aPendingWriteOnAnAppThatBecameProtectedIsSettledByItsReadBack() =
        runTest {
            // The write lands, but its answer is lost: the row stays PENDING for a read-back.
            backend.unavailableAfterWrite = GatewayUnavailable.STALE_SERVICE
            actions.execute(ToolkitAction.SetAppOp(PKG, background, AppOpMode.IGNORE), confirmed = true)
            backend.unavailableAfterWrite = null
            val pending = audit.rows.single()
            assertEquals(ToolkitActionStatus.PENDING, pending.status)

            // A protected read without a value (it failed, or an older service) is inconclusive: still PENDING.
            backend.denied[PKG] = ToolkitCode.DENY_PROTECTED
            actions.reconcile()
            assertEquals(ToolkitActionStatus.PENDING, audit.byId(pending.id).status)

            backend.denied.clear()
            backend.protected[PKG] = ToolkitCode.DENY_PROTECTED
            actions.reconcile()

            assertEquals(ToolkitActionStatus.DONE, audit.byId(pending.id).status)
            assertEquals(listOf(pending.id), actions.activeOverrides().first().map { it.actionId })
        }
}
