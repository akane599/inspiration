package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R-05: a Room error from the audit log is "the command failed" only while nothing was sent. Once the privileged
 * write was dispatched the device may have changed, so a settle or RESTORED update that cannot be stored is the
 * "sent, but couldn't be confirmed" outcome (Unavailable(UNVERIFIED)), and the flows still tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitActionsAuditFailureTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()

    private fun TestScope.actions() =
        ToolkitActions(backend, audit, holds::hold, nowMs = { currentTime }, supervisorScope = backgroundScope, elapsedMs = { currentTime })

    /** Every emission of [ToolkitActions.history]: one more means the history and override flows ticked. */
    private fun TestScope.ticks(actions: ToolkitActions): MutableList<List<ToolkitActionRecord>> {
        val seen = mutableListOf<List<ToolkitActionRecord>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.history(10).collect { seen += it } }
        return seen
    }

    @Test
    fun aSettleThatCannotBeStoredAfterTheWriteIsUnverifiedNotFailed() =
        runTest {
            val actions = actions()
            val seen = ticks(actions)
            val before = seen.size
            audit.failUpdate = { diskFull() }

            val outcome = actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true)

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED), outcome)
            assertEquals(listOf("forceStop($PKG)"), backend.writes)
            // The row could not be settled, so it stays PENDING for a read-back to settle it later.
            assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
            assertTrue("the history and override flows must tick", seen.size > before)
        }

    @Test
    fun aRestoredMarkThatCannotBeStoredAfterTheWriteIsUnverifiedNotFailed() =
        runTest {
            val actions = actions()
            val original = (actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) as ToolkitOutcome.Done).value
            audit.failUpdate = { if (it.status == ToolkitActionStatus.RESTORED) diskFull() else null }

            val outcome = actions.restore(original.id)

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED), outcome)
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
        }

    @Test
    fun aWriteAheadRowThatCannotBeStoredStillFailsAndSendsNothing() =
        runTest {
            val actions = actions()
            audit.failInsert = { diskFull() }

            try {
                actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
                fail("a write-ahead row that cannot be stored must fail the call")
            } catch (e: RuntimeException) {
                assertTrue(e.message!!.startsWith("SQLiteFullException"))
            }

            assertTrue("nothing may be sent without its audit row: ${backend.writes}", backend.writes.isEmpty())
        }

    @Test
    fun aCancellationFromTheStoreAfterTheWriteIsNotSwallowed() =
        runTest {
            val actions = actions()
            audit.failUpdate = { CancellationException("store cancelled") }

            try {
                actions.execute(ToolkitAction.ForceStop(PKG), confirmed = true)
                fail("a cancellation must propagate")
            } catch (e: CancellationException) {
                assertEquals("store cancelled", e.message)
            }
        }

    @Test
    fun aResetAllWhoseItemCannotBeStoredAfterItsWriteKeepsTheOthersRestored() =
        runTest {
            val actions = actions()
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(OTHER, StandbyBucket.RARE))
            audit.failUpdate = { if (it.pkg == OTHER && it.restoresId != null) diskFull() else null }

            val outcomes = actions.resetAll().associate { it.override.pkg to it.outcome }

            assertEquals(setOf(PKG, OTHER), outcomes.keys)
            assertTrue("a restored item is reported restored: $outcomes", outcomes[PKG] is ToolkitOutcome.Done)
            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED), outcomes[OTHER])
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[OTHER])
        }

    @Test
    fun aResetAllWhoseItemCannotBeStoredBeforeItsWriteKeepsTheOthersRestored() =
        runTest {
            val actions = actions()
            actions.execute(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(OTHER, StandbyBucket.RARE))
            audit.failInsert = { if (it.pkg == OTHER && it.restoresId != null) diskFull() else null }

            val outcomes = actions.resetAll().associate { it.override.pkg to it.outcome }

            assertEquals(setOf(PKG, OTHER), outcomes.keys)
            assertTrue("a restored item is reported restored: $outcomes", outcomes[PKG] is ToolkitOutcome.Done)
            assertEquals(ToolkitOutcome.Failed(code = null), outcomes[OTHER])
            assertEquals(StandbyBucket.ACTIVE.rawValue, backend.buckets[PKG])
            assertEquals("nothing was sent for the item without its row", StandbyBucket.RARE.rawValue, backend.buckets[OTHER])
        }

    @Test
    fun aDexoptStartThatCannotBeStoredAfterTheServiceStartedItIsUnverified() =
        runTest {
            val actions = actions()
            audit.failUpdate = { diskFull() }

            val outcome = actions.startBgDexopt()

            assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED), outcome)
            assertEquals(listOf("startBgDexopt"), backend.writes)
            assertEquals(ToolkitActionStatus.PENDING, audit.rows.single().status)
        }

    private companion object {
        const val OTHER = "com.example.maps"
    }
}
