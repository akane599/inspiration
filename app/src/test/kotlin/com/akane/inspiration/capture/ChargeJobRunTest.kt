package com.akane.inspiration.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChargeJobRunTest {
    private var finished = 0
    private var reArms = 0
    private var immediateArm = false
    private var reArmFailure: RuntimeException? = null
    private val logged = mutableListOf<String>()

    private suspend fun runJob(work: suspend () -> Unit) = runChargeJob(
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
    fun `a completed run finishes and re-arms once`() = runTest {
        runJob { }

        assertEquals(1, finished)
        assertEquals(1, reArms)
    }

    @Test
    fun `a stop that lands after the last suspension point still re-arms`() = runTest {
        // onStopJob cancelled the coroutine, but nothing suspended afterwards, so no CancellationException.
        launch { runJob { currentCoroutineContext().job.cancel() } }.join()

        assertEquals("a stopped job is not finished", 0, finished)
        assertEquals(1, reArms)
    }

    @Test
    fun `a stopped run re-arms once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = launch { runJob { gate.await() } }
        runCurrent()

        job.cancel()
        job.join()

        assertEquals(0, finished)
        assertEquals(1, reArms)
    }

    @Test
    fun `a run stopped by an immediate boot arm does not replace it with the delayed one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val job = launch { runJob { gate.await() } }
        runCurrent()

        immediateArm = true // BOOT_COMPLETED armed the same job ID, which stops this run
        job.cancel()
        job.join()

        assertEquals(0, reArms)
    }

    @Test
    fun `a failed run finishes and a failing re-arm is logged instead of thrown`() = runTest {
        reArmFailure = IllegalStateException("too many jobs")

        runJob { throw IllegalStateException("disk full") }

        assertEquals(1, finished)
        assertEquals(1, reArms)
        assertEquals(listOf("charge job capture failed", "charge job re-arm failed"), logged)
    }
}
