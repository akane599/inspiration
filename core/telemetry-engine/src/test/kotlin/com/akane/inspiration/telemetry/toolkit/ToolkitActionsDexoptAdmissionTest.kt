package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A-05: exercise actual concurrent start calls stopped before the backend admits or rejects them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolkitActionsDexoptAdmissionTest {
    @Test
    fun previousTerminalStatesCannotSettleAStartBlockedBeforeAdmission() =
        runTest {
            for (previous in listOf(BgDexoptState.IDLE, BgDexoptState.FINISHED, BgDexoptState.CANCELLED, BgDexoptState.UNKNOWN)) {
                val fixture = Fixture(this)
                fixture.status(previous, exit = 3)
                val start = async { fixture.actions.startBgDexopt() }
                val admission = fixture.backend.admissions.receive()
                try {
                    val row = fixture.audit.rows.single()
                    assertFalse(start.isCompleted)
                    assertEquals(
                        "prior state $previous is not this attempt's outcome",
                        BgDexoptStatus.Unknown,
                        fixture.actions.bgDexoptStatus().first(),
                    )
                    fixture.actions.reconcile()
                    assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                    assertNull(fixture.audit.byId(row.id).endedAt)

                    fixture.status(BgDexoptState.RUNNING)
                    admission.accept()
                    assertTrue(start.await() is ToolkitOutcome.Done)
                    assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                    assertEquals(1, fixture.holds.open)

                    fixture.status(BgDexoptState.FINISHED)
                    assertTrue(fixture.actions.bgDexoptStatus().first() is BgDexoptStatus.Finished)
                    assertEquals(
                        "completed admission is no longer protected from reconciliation",
                        ToolkitActionStatus.DONE,
                        fixture.audit.byId(row.id).status,
                    )
                    assertEquals(0, fixture.holds.open)
                } finally {
                    admission.abandon()
                    start.join()
                }
            }
        }

    @Test
    fun interruptedAdmissionRetainsDurableUncertaintyAcrossRecreationEvenAfterAnEarlyCancel() =
        runTest {
            for (cancelBeforeAdmission in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.status(BgDexoptState.IDLE)
                val interruption = CancellationException("service disappeared before the admission reply")
                val start =
                    async {
                        try {
                            fixture.actions.startBgDexopt()
                            null
                        } catch (error: CancellationException) {
                            error
                        }
                    }
                val admission = fixture.backend.admissions.receive()
                try {
                    val row = fixture.audit.rows.single()
                    assertEquals(BgDexoptStatus.Unknown, fixture.actions.bgDexoptStatus().first())
                    if (cancelBeforeAdmission) assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                    assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)

                    // The remote call can have reached admission, but its reply never reaches settlement.
                    fixture.status(BgDexoptState.RUNNING)
                    admission.reply.completeExceptionally(interruption)
                    val observed = start.await()
                    assertEquals(interruption.javaClass, observed?.javaClass)
                    assertEquals(interruption.message, observed?.message)
                    // Coroutine stacktrace recovery may copy the exception, retaining the original cause.
                    assertSame(interruption, generateSequence<Throwable>(observed) { it.cause }.last())
                    assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                    assertNull(fixture.audit.byId(row.id).endedAt)

                    val recreatedBackend = FakeBackend()
                    recreatedBackend.dexoptStatuses.clear()
                    recreatedBackend.dexoptStatuses.add(serviceStatus(BgDexoptState.UNKNOWN))
                    val recreated = fixture.recreated(recreatedBackend)
                    assertEquals(
                        "the pending row survives without any process-local admission set",
                        BgDexoptStatus.Unknown,
                        recreated.bgDexoptStatus().first(),
                    )
                    recreated.reconcile()
                    assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                    assertEquals(ToolkitOutcome.Done(Unit), recreated.cancelBgDexopt())
                    assertEquals(ToolkitActionStatus.CANCELLED, fixture.audit.byId(row.id).status)
                } finally {
                    admission.abandon()
                    start.join()
                }
            }
        }

    @Test
    fun cancelBeforeControllerEntryIsResponsiveAndCannotEraseAStartThatLaterSucceeds() =
        runTest {
            val fixture = Fixture(this)
            val start = async { fixture.actions.startBgDexopt() }
            val admission = fixture.backend.admissions.receive()
            try {
                val row = fixture.audit.rows.single()
                val cancel = async { fixture.actions.cancelBgDexopt() }
                val plan = async { fixture.actions.plan(ToolkitAction.SetBucket(PKG, StandbyBucket.RARE)) }
                runCurrent()
                assertTrue("cancel must not wait for slow admission", cancel.isCompleted)
                assertTrue("unrelated planning must not wait for slow admission", plan.isCompleted)
                assertEquals(ToolkitOutcome.Done(Unit), cancel.await())
                assertEquals(ToolkitPlan.Ready, plan.await())
                assertFalse(start.isCompleted)
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                assertNull(fixture.audit.byId(row.id).endedAt)
                assertEquals(BgDexoptStatus.Unknown, fixture.actions.bgDexoptStatus().first())

                // A cancel completed before the controller entered tryStart cannot fence that later entry.
                fixture.status(BgDexoptState.RUNNING)
                admission.accept()
                assertTrue(start.await() is ToolkitOutcome.Done)
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                assertEquals(1, fixture.holds.open)
                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals("finally removed the admission protection", ToolkitActionStatus.CANCELLED, fixture.audit.byId(row.id).status)
                assertEquals(0, fixture.holds.open)
            } finally {
                admission.abandon()
                start.join()
            }
        }

    @Test
    fun aProbingStartRefusedAfterCancelSettlesItsOwnRowAndWakesTheUnknownCollector() =
        runTest {
            val fixture = Fixture(this)
            val start = async { fixture.actions.startBgDexopt() }
            val admission = fixture.backend.admissions.receive()
            val seen = mutableListOf<BgDexoptStatus>()
            val collector = launch { fixture.actions.bgDexoptStatus().collect { seen += it } }
            try {
                runCurrent()
                assertEquals(listOf(BgDexoptStatus.Unknown), seen)
                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals(
                    ToolkitActionStatus.PENDING,
                    fixture.audit.rows
                        .single()
                        .status,
                )

                // Cancellation during the controller's plugged probe rejects admission at its generation check.
                admission.refuse(ToolkitCode.BUSY)
                assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.BUSY, ToolkitCode.BUSY), start.await())
                runCurrent()
                assertEquals(
                    ToolkitActionStatus.REFUSED,
                    fixture.audit.rows
                        .single()
                        .status,
                )
                assertEquals("completion is published after in-flight protection is removed", BgDexoptStatus.Idle, seen.last())
                assertEquals(0, fixture.holds.open)
            } finally {
                collector.cancel()
                admission.abandon()
                start.join()
            }
        }

    @Test
    fun aConcurrentSuccessfulStartSupersedesSettledOwnersButNeverAnotherInFlightAdmission() =
        runTest {
            val fixture = Fixture(this)
            val oldStart = async { fixture.actions.startBgDexopt() }
            val oldAdmission = fixture.backend.admissions.receive()
            oldAdmission.accept()
            val old = (oldStart.await() as ToolkitOutcome.Done).value
            fixture.status(BgDexoptState.FINISHED)

            val first = async { fixture.actions.startBgDexopt() }
            val firstAdmission = fixture.backend.admissions.receive()
            val firstRow = fixture.audit.rows.last()
            val second = async { fixture.actions.startBgDexopt() }
            val secondAdmission = fixture.backend.admissions.receive()
            try {
                val secondRow = fixture.audit.rows.last()
                fixture.status(BgDexoptState.RUNNING)
                secondAdmission.accept()
                assertTrue(second.await() is ToolkitOutcome.Done)
                assertEquals("the completed older owner is superseded", ToolkitActionStatus.CANCELLED, fixture.audit.byId(old.id).status)
                assertEquals("superseded", fixture.audit.byId(old.id).afterRaw)
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(firstRow.id).status)
                assertNull(fixture.audit.byId(firstRow.id).endedAt)
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(secondRow.id).status)
                assertTrue(
                    "RUNNING remains observable during another admission",
                    fixture.actions.bgDexoptStatus().first() is BgDexoptStatus.Running,
                )

                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals(
                    "the cancel settles nothing itself, and a start still in admission masks the status",
                    ToolkitActionStatus.PENDING,
                    fixture.audit.byId(secondRow.id).status,
                )
                assertEquals(
                    "the still-blocked admission remains uncertain",
                    ToolkitActionStatus.PENDING,
                    fixture.audit.byId(firstRow.id).status,
                )
                firstAdmission.refuse(ToolkitCode.BUSY)
                assertEquals(ToolkitOutcome.Refused(ToolkitRefusal.BUSY, ToolkitCode.BUSY), first.await())
                assertEquals(ToolkitActionStatus.REFUSED, fixture.audit.byId(firstRow.id).status)
                assertEquals(
                    BgDexoptStatus.Cancelled(BgDexoptCancelReason.USER, 2_000L),
                    fixture.actions.bgDexoptStatus().first(),
                )
                assertEquals(
                    "the admitted owner's row settles from the service status",
                    ToolkitActionStatus.CANCELLED,
                    fixture.audit.byId(secondRow.id).status,
                )
                assertEquals(BgDexoptReason.USER, fixture.audit.byId(secondRow.id).code)
                fixture.status(BgDexoptState.IDLE)
                assertEquals(BgDexoptStatus.Idle, fixture.actions.bgDexoptStatus().first())
            } finally {
                firstAdmission.abandon()
                secondAdmission.abandon()
                first.join()
                second.join()
            }
        }

    @Test
    fun aBackendExceptionKeepsTheRowPendingButFinallyRemovesItsLocalProtection() =
        runTest {
            val fixture = Fixture(this)
            val failure = AdmissionFailure("binder reply lost")
            val start =
                async {
                    try {
                        fixture.actions.startBgDexopt()
                        null
                    } catch (error: AdmissionFailure) {
                        error
                    }
                }
            val admission = fixture.backend.admissions.receive()
            try {
                val row = fixture.audit.rows.single()
                assertEquals(BgDexoptStatus.Unknown, fixture.actions.bgDexoptStatus().first())
                admission.reply.completeExceptionally(failure)
                assertSame(failure, start.await())
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                assertEquals(ToolkitClientCode.WRITE_ERROR, fixture.audit.byId(row.id).code)
                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals(ToolkitActionStatus.CANCELLED, fixture.audit.byId(row.id).status)
            } finally {
                admission.abandon()
                start.join()
            }
        }

    @Test
    fun anAuditSettlementExceptionCannotLeaveTheAdmissionIdProtectedForever() =
        runTest {
            val fixture = Fixture(this)
            val start = async { fixture.actions.startBgDexopt() }
            val admission = fixture.backend.admissions.receive()
            try {
                val row = fixture.audit.rows.single()
                fixture.store.failNextUpdate = true
                admission.accept()
                // The service started the job, so a settlement that cannot be stored is "sent, unconfirmed" (R-05).
                assertEquals(ToolkitOutcome.Unavailable(ToolkitUnavailable.UNVERIFIED), start.await())
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals(
                    "cleanup must also run when settlement throws",
                    ToolkitActionStatus.CANCELLED,
                    fixture.audit.byId(row.id).status,
                )
            } finally {
                admission.abandon()
                start.join()
            }
        }

    @Test
    fun callerCancellationDuringBlockedAdmissionStillSettlesAndCleansUpItsId() =
        runTest {
            val fixture = Fixture(this)
            val caller = launch { fixture.actions.startBgDexopt() }
            val admission = fixture.backend.admissions.receive()
            try {
                val row = fixture.audit.rows.single()
                caller.cancel()
                runCurrent()
                assertFalse("the write-ahead operation remains fenced against caller cancellation", caller.isCompleted)
                assertEquals(BgDexoptStatus.Unknown, fixture.actions.bgDexoptStatus().first())
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)

                fixture.status(BgDexoptState.RUNNING)
                admission.accept()
                caller.join()
                assertTrue(caller.isCancelled)
                assertEquals(ToolkitActionStatus.PENDING, fixture.audit.byId(row.id).status)
                assertEquals(ToolkitCode.OK, fixture.audit.byId(row.id).code)
                assertEquals(1, fixture.holds.open)
                assertEquals(ToolkitOutcome.Done(Unit), fixture.actions.cancelBgDexopt())
                assertEquals(
                    "cancelled caller did not prevent finally cleanup",
                    ToolkitActionStatus.CANCELLED,
                    fixture.audit.byId(row.id).status,
                )
                assertEquals(0, fixture.holds.open)
            } finally {
                admission.abandon()
                caller.join()
            }
        }

    private class Fixture(
        private val scope: TestScope,
    ) {
        val backend = AdmissionBackend()
        val audit = FakeAudit()
        val store = FailingUpdateAudit(audit)
        val holds = FakeHolds()
        val actions = recreated(backend)

        fun status(
            state: Int,
            exit: Int = 0,
        ) {
            backend.device.dexoptStatuses.clear()
            backend.device.dexoptStatuses.add(serviceStatus(state, exit = exit))
        }

        fun recreated(backend: ToolkitBackend) =
            ToolkitActions(
                backend,
                store,
                holds::hold,
                nowMs = { scope.testScheduler.currentTime },
                supervisorScope = scope.backgroundScope,
                elapsedMs = { scope.testScheduler.currentTime },
            )
    }

    /** Each actual backend invocation blocks independently before its admission reply can settle a row. */
    private class AdmissionBackend(
        val device: FakeBackend = FakeBackend(),
    ) : ToolkitBackend by device {
        val admissions = Channel<Admission>(Channel.UNLIMITED)

        override suspend fun startBgDexopt(): GatewayReply<ToolkitResult> {
            val admission = Admission()
            admissions.send(admission)
            return admission.reply.await()
        }
    }

    private class Admission {
        val reply = CompletableDeferred<GatewayReply<ToolkitResult>>()

        fun accept() {
            reply.complete(GatewayReply.Reply(ToolkitResult.done(before = "${BgDexoptState.IDLE}", after = "${BgDexoptState.RUNNING}")))
        }

        fun refuse(code: Int) {
            reply.complete(GatewayReply.Reply(ToolkitResult.refused(code)))
        }

        fun abandon() {
            reply.complete(GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE))
        }
    }

    private class FailingUpdateAudit(
        private val delegate: ToolkitAuditStore,
    ) : ToolkitAuditStore by delegate {
        var failNextUpdate = false

        override suspend fun update(record: ToolkitActionRecord) {
            if (failNextUpdate) {
                failNextUpdate = false
                throw AdmissionFailure("audit settlement failed")
            }
            delegate.update(record)
        }
    }

    private class AdmissionFailure(
        message: String,
    ) : IllegalStateException(message)
}
