package com.akane.inspiration.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class ChargeJobRunTest {
    @Test
    fun `a delayed submission already in flight cannot replace a newer immediate JOB29`() {
        val scheduler = FakeJobScheduler()
        val generation = ChargingCaptureScheduler.immediateArmCount()
        val delayedSubmission = Gate()
        val delayed =
            TestThread {
                ChargingCaptureScheduler.reArmIfUnchanged(generation) { jobId, latencyMs ->
                    delayedSubmission.pause()
                    scheduler.schedule(jobId, latencyMs)
                }
            }
        var immediate: TestThread? = null
        var immediateWasBlocked = false
        try {
            delayedSubmission.awaitEntered()
            val contender = TestThread { ChargingCaptureScheduler.arm(schedule = scheduler::schedule) }
            immediate = contender
            immediateWasBlocked = contender.awaitBlockedOrFinished()
        } finally {
            delayedSubmission.release()
            delayed.join()
            immediate?.join()
        }

        assertEquals("stale 45-minute rearm must not replace immediate JOB29", JobDescriptor(29, null), scheduler.getPendingJob(29))
        assertEquals(listOf(JobDescriptor(29, 2_700_000L), JobDescriptor(29, null)), scheduler.submissions)
        assertTrue("immediate schedule must share the delayed submission lock", immediateWasBlocked)
        assertEquals(generation + 1, ChargingCaptureScheduler.immediateArmCount())
    }

    @Test
    fun `an immediate submission holds the lock until stale rearm can check its generation`() {
        val scheduler = FakeJobScheduler()
        val generation = ChargingCaptureScheduler.immediateArmCount()
        val immediateSubmission = Gate()
        val immediate =
            TestThread {
                ChargingCaptureScheduler.arm { jobId, latencyMs ->
                    immediateSubmission.pause()
                    scheduler.schedule(jobId, latencyMs)
                }
            }
        var delayed: TestThread? = null
        var delayedWasBlocked = false
        try {
            immediateSubmission.awaitEntered()
            val contender =
                TestThread {
                    ChargingCaptureScheduler.reArmIfUnchanged(generation, schedule = scheduler::schedule)
                }
            delayed = contender
            delayedWasBlocked = contender.awaitBlockedOrFinished()
        } finally {
            immediateSubmission.release()
            immediate.join()
            delayed?.join()
        }

        assertTrue("generation check must share the immediate submission lock", delayedWasBlocked)
        assertEquals(listOf(JobDescriptor(29, null)), scheduler.submissions)
        assertEquals(JobDescriptor(29, null), scheduler.getPendingJob(29))
    }

    @Test
    fun `ensure pending decision and immediate arm are atomic against a delayed rearm`() {
        val scheduler = FakeJobScheduler()
        val generation = ChargingCaptureScheduler.immediateArmCount()
        val pendingLookup = Gate()
        val ensure =
            TestThread {
                ChargingCaptureScheduler.ensureScheduled(
                    hasPendingJob = {
                        val pending = scheduler.getPendingJob(29) != null
                        pendingLookup.pause()
                        pending
                    },
                    schedule = scheduler::schedule,
                )
            }
        var delayed: TestThread? = null
        var delayedWasBlocked = false
        try {
            pendingLookup.awaitEntered()
            val contender =
                TestThread {
                    ChargingCaptureScheduler.reArmIfUnchanged(generation, schedule = scheduler::schedule)
                }
            delayed = contender
            delayedWasBlocked = contender.awaitBlockedOrFinished()
        } finally {
            pendingLookup.release()
            ensure.join()
            delayed?.join()
        }

        assertTrue("ensure lookup and arm must share the rearm lock", delayedWasBlocked)
        assertEquals(listOf(JobDescriptor(29, null)), scheduler.submissions)
        assertEquals(JobDescriptor(29, null), scheduler.getPendingJob(29))
        assertEquals(generation + 1, ChargingCaptureScheduler.immediateArmCount())
    }

    @Test
    fun `unchanged generation submits the delayed JOB29 without counting an immediate arm`() {
        val scheduler = FakeJobScheduler()
        val generation = ChargingCaptureScheduler.immediateArmCount()

        ChargingCaptureScheduler.reArmIfUnchanged(generation, schedule = scheduler::schedule)

        assertEquals(listOf(JobDescriptor(29, 2_700_000L)), scheduler.submissions)
        assertEquals(JobDescriptor(29, 2_700_000L), scheduler.getPendingJob(29))
        assertEquals(generation, ChargingCaptureScheduler.immediateArmCount())
    }

    @Test
    fun `ensure leaves an existing pending job and generation unchanged`() {
        val scheduler = FakeJobScheduler()
        scheduler.schedule(29, 2_700_000L)
        val generation = ChargingCaptureScheduler.immediateArmCount()

        ChargingCaptureScheduler.ensureScheduled(
            hasPendingJob = { scheduler.getPendingJob(29) != null },
            schedule = scheduler::schedule,
        )

        assertEquals(listOf(JobDescriptor(29, 2_700_000L)), scheduler.submissions)
        assertEquals(generation, ChargingCaptureScheduler.immediateArmCount())
    }

    @Test
    fun `authoritative rearm preserves an immediate arm after a stale runner precheck on every exit`() =
        runTest {
            for (failure in listOf(null, IllegalStateException("capture failed"), CancellationException("stopped"))) {
                val scheduler = FakeJobScheduler()
                val generation = ChargingCaptureScheduler.immediateArmCount()
                var finishes = 0
                val errors = mutableListOf<String>()

                runChargeJob(
                    work = { failure?.let { throw it } },
                    finish = { finishes++ },
                    immediateArmSinceStart = {
                        val changed = ChargingCaptureScheduler.immediateArmCount() != generation
                        // Reproduce an immediate arm between the runner's optional precheck and authoritative rearm.
                        ChargingCaptureScheduler.arm(schedule = scheduler::schedule)
                        changed
                    },
                    reArm = { ChargingCaptureScheduler.reArmIfUnchanged(generation, schedule = scheduler::schedule) },
                    log = { message, _ -> errors += message },
                )

                assertEquals(listOf(JobDescriptor(29, null)), scheduler.submissions)
                assertEquals(JobDescriptor(29, null), scheduler.getPendingJob(29))
                assertEquals(if (failure is CancellationException) 0 else 1, finishes)
                assertEquals(
                    if (failure != null &&
                        failure !is CancellationException
                    ) {
                        listOf("charge job capture failed")
                    } else {
                        emptyList<String>()
                    },
                    errors,
                )
            }
        }

    @Test
    fun `a refused immediate submission retains its generation and releases the scheduling lock`() {
        val scheduler = FakeJobScheduler()
        val generation = ChargingCaptureScheduler.immediateArmCount()

        assertThrows(IllegalStateException::class.java) {
            ChargingCaptureScheduler.arm { _, _ -> throw IllegalStateException("too many jobs") }
        }
        ChargingCaptureScheduler.reArmIfUnchanged(generation, schedule = scheduler::schedule)
        assertTrue(scheduler.submissions.isEmpty())
        assertEquals(generation + 1, ChargingCaptureScheduler.immediateArmCount())

        val retry =
            TestThread {
                ChargingCaptureScheduler.reArmIfUnchanged(generation + 1, schedule = scheduler::schedule)
            }
        retry.join()
        assertEquals(listOf(JobDescriptor(29, 2_700_000L)), scheduler.submissions)
    }

    private data class JobDescriptor(
        val jobId: Int,
        val minimumLatencyMs: Long?,
    )

    /** Only models Android's same-ID replacement; scheduling policy runs in production callbacks. */
    private class FakeJobScheduler {
        private val pending = mutableMapOf<Int, JobDescriptor>()
        val submissions = mutableListOf<JobDescriptor>()

        @Synchronized
        fun schedule(
            jobId: Int,
            minimumLatencyMs: Long?,
        ) {
            val descriptor = JobDescriptor(jobId, minimumLatencyMs)
            pending[jobId] = descriptor
            submissions += descriptor
        }

        @Synchronized
        fun getPendingJob(jobId: Int): JobDescriptor? = pending[jobId]
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun pause() {
            entered.countDown()
            assertTrue("submission was not released", released.await(5, TimeUnit.SECONDS))
        }

        fun awaitEntered() {
            assertTrue("submission did not enter", entered.await(5, TimeUnit.SECONDS))
        }

        fun release() = released.countDown()
    }

    private class TestThread(
        action: () -> Unit,
    ) {
        private val failure = AtomicReference<Throwable>()
        private val thread =
            Thread {
                try {
                    action()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply {
                isDaemon = true
                start()
            }

        fun awaitBlockedOrFinished(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.isAlive) {
                if (thread.state == Thread.State.BLOCKED) return true
                check(System.nanoTime() < deadline) { "scheduler thread neither blocked nor finished" }
                Thread.yield()
            }
            return false
        }

        fun join() {
            thread.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("scheduler thread did not finish", thread.isAlive)
            failure.get()?.let { throw AssertionError("scheduler thread failed", it) }
        }
    }

    private var finished = 0
    private var reArms = 0
    private var immediateArm = false
    private var reArmFailure: RuntimeException? = null
    private val logged = mutableListOf<String>()

    private suspend fun runJob(work: suspend () -> Unit) =
        runChargeJob(
            work = work,
            finish = { finished += 1 },
            immediateArmSinceStart = { immediateArm },
            reArm = {
                reArms += 1
                reArmFailure?.let { throw it }
            },
            log = { message, _ -> logged += message },
        )

    @Test
    fun `a completed run finishes and re-arms once`() =
        runTest {
            runJob { }

            assertEquals(1, finished)
            assertEquals(1, reArms)
        }

    @Test
    fun `a stop that lands after the last suspension point still re-arms`() =
        runTest {
            // onStopJob cancelled the coroutine, but nothing suspended afterwards, so no CancellationException.
            launch { runJob { currentCoroutineContext().job.cancel() } }.join()

            assertEquals("a stopped job is not finished", 0, finished)
            assertEquals(1, reArms)
        }

    @Test
    fun `a stopped run re-arms once`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val job = launch { runJob { gate.await() } }
            runCurrent()

            job.cancel()
            job.join()

            assertEquals(0, finished)
            assertEquals(1, reArms)
        }

    @Test
    fun `a run stopped by an immediate boot arm does not replace it with the delayed one`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val job = launch { runJob { gate.await() } }
            runCurrent()

            immediateArm = true // BOOT_COMPLETED armed the same job ID, which stops this run
            job.cancel()
            job.join()

            assertEquals(0, reArms)
        }

    @Test
    fun `a failed run finishes and a failing re-arm is logged instead of thrown`() =
        runTest {
            reArmFailure = IllegalStateException("too many jobs")

            runJob { throw IllegalStateException("disk full") }

            assertEquals(1, finished)
            assertEquals(1, reArms)
            assertEquals(listOf("charge job capture failed", "charge job re-arm failed"), logged)
        }
}
