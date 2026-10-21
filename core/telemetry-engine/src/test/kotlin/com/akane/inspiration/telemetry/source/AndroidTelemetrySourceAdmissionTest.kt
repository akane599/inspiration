package com.akane.inspiration.telemetry.source

import android.os.ParcelFileDescriptor
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.DumpResult
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Physical workers, not waiting coroutines, own the process-wide four-call budget (audit A01). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTelemetrySourceAdmissionTest {
    @Test
    fun `repeated blocked deadlines share four slots across sources and recover only after worker exit`() {
        val caller = Caller()
        val executor = Executors.newFixedThreadPool(8)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        val entries = AtomicInteger()
        val service =
            object : ITelemetryUserService.Default() {
                override fun readKernelBattery(): KernelBatterySnapshot {
                    entries.incrementAndGet()
                    release.awaitUninterruptibly()
                    return KERNEL
                }
            }
        val telemetry = source(service, dispatcher)
        try {
            repeat(SLOTS) { index ->
                val call = caller.scope.async { telemetry.kernelBatterySnapshot(DEADLINE_MS) }
                assertTrue(caller.until { entries.get() == index + 1 })
                caller.scheduler.advanceTimeBy(DEADLINE_MS)
                assertTrue("a timeout cannot wait for physical completion", caller.until { call.isCompleted })
                assertNull(call.getCompleted())
            }
            // The same live source retries, while fresh job/service sources compete for the same process budget.
            repeat(12) { index ->
                val retrySource = if (index % 2 == 0) telemetry else source(service, dispatcher)
                val retry = caller.scope.async { retrySource.kernelBatterySnapshot(DEADLINE_MS) }
                assertTrue(caller.until { retry.isCompleted || entries.get() > SLOTS })
                assertTrue("capacity exhaustion must fail fast without advancing the deadline", retry.isCompleted)
                assertNull(retry.getCompleted())
                assertEquals("logical timeouts must not admit more physical calls", SLOTS, entries.get())
            }
            assertEquals(1L, release.count)
            release.countDown()
            executor.shutdown()
            assertTrue("wait for the whole worker runnable, including slot release", executor.awaitTermination(4, TimeUnit.SECONDS))
            assertAllSlotsAvailable(caller)
        } finally {
            release.countDown()
            caller.close()
            executor.shutdown()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            dispatcher.close()
        }
    }

    @Test
    fun `external cancellation also retains slots until blocked workers exit`() {
        val caller = Caller()
        val executor = Executors.newFixedThreadPool(8)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        val entries = AtomicInteger()
        val service =
            object : ITelemetryUserService.Default() {
                override fun readKernelBattery(): KernelBatterySnapshot {
                    entries.incrementAndGet()
                    release.awaitUninterruptibly()
                    return KERNEL
                }
            }
        try {
            repeat(SLOTS) { index ->
                val call = caller.scope.async { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) }
                assertTrue(caller.until { entries.get() == index + 1 })
                call.cancel()
                assertTrue(caller.until { call.isCompleted })
                assertTrue(call.isCancelled)
            }
            val retry = caller.scope.async { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) }
            assertTrue(caller.until { retry.isCompleted || entries.get() > SLOTS })
            assertTrue("cancelling the caller does not free the physical worker", retry.isCompleted)
            assertNull(retry.getCompleted())
            assertEquals(SLOTS, entries.get())
        } finally {
            release.countDown()
            caller.close()
            executor.shutdown()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            dispatcher.close()
        }
        val recoveryCaller = Caller()
        try {
            assertAllSlotsAvailable(recoveryCaller)
        } finally {
            recoveryCaller.close()
        }
    }

    @Test
    fun `cancellation before worker execution skips the service and releases once the runnable exits`() {
        val caller = Caller()
        val dispatcher = QueuedDispatcher()
        val entries = AtomicInteger()
        val service =
            object : ITelemetryUserService.Default() {
                override fun readKernelBattery(): KernelBatterySnapshot {
                    entries.incrementAndGet()
                    return KERNEL
                }
            }
        try {
            val calls = List(SLOTS) { caller.scope.async { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) } }
            caller.scheduler.runCurrent()
            assertEquals(SLOTS, dispatcher.size)
            calls.forEach { it.cancel() }
            caller.scheduler.runCurrent()
            assertTrue(calls.all { it.isCancelled && it.isCompleted })
            val retry = caller.scope.async { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) }
            caller.scheduler.runCurrent()
            assertTrue("queued cancelled workers still own their slots", retry.isCompleted)
            assertNull(retry.getCompleted())
            assertEquals(SLOTS, dispatcher.size)
            dispatcher.drain()
            assertEquals("no transaction starts after cancellation", 0, entries.get())
            assertAllSlotsAvailable(caller)
        } finally {
            caller.close()
            dispatcher.drain()
        }
    }

    @Test
    fun `repeated executor rejection propagates without leaking or inflating the budget`() {
        val caller = Caller()
        val failure = RejectedExecutionException("test scheduling rejection")
        val dispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable): Unit = throw failure
            }
        try {
            repeat(SLOTS * 2) {
                val call = caller.scope.async { runCatching { source(KernelService(), dispatcher).kernelBatterySnapshot(DEADLINE_MS) } }
                caller.scheduler.runCurrent()
                assertTrue(call.isCompleted)
                // Coroutine stack-trace recovery may copy the exception; its type/message are the contract.
                val thrown = call.getCompleted().exceptionOrNull()
                assertTrue(thrown is RejectedExecutionException)
                assertEquals(failure.message, thrown?.message)
            }
            assertAllSlotsAvailable(caller)
        } finally {
            caller.close()
        }
    }

    @Test
    fun `service failure and service cancellation release their physical slots`() {
        val caller = Caller()
        val dispatcher = QueuedDispatcher()
        val cancellation = CancellationException("service cancellation")
        try {
            repeat(SLOTS * 2) { index ->
                val service =
                    object : ITelemetryUserService.Default() {
                        override fun readKernelBattery(): KernelBatterySnapshot =
                            if (index % 2 == 0) throw IllegalStateException("remote failure") else throw cancellation
                    }
                val call = caller.scope.async { runCatching { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) } }
                caller.scheduler.runCurrent()
                assertEquals(1, dispatcher.size)
                dispatcher.drain()
                caller.scheduler.runCurrent()
                val result = call.getCompleted()
                if (index % 2 == 0) {
                    assertNull(result.getOrThrow())
                } else {
                    assertTrue(result.exceptionOrNull() is CancellationException)
                    assertEquals(cancellation.message, result.exceptionOrNull()?.message)
                }
            }
            assertAllSlotsAvailable(caller)
        } finally {
            caller.close()
            dispatcher.drain()
        }
    }

    @Test
    fun `a late dump FD is closed after cancellation while the transaction was blocked`() {
        val caller = Caller()
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val pipe = ParcelFileDescriptor.createPipe()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service =
            object : ITelemetryUserService.Default() {
                override fun openDump(target: Int): DumpResult {
                    entered.countDown()
                    release.awaitUninterruptibly()
                    return DumpResult(true, null, pipe[0])
                }
            }
        val call = caller.scope.async { source(service, dispatcher).full(CaptureTrigger.MARKER, null) }
        try {
            assertTrue(caller.until { entered.count == 0L })
            call.cancel()
            assertTrue(caller.until { call.isCompleted })
            assertTrue("the service still owns the not-yet-returned FD", pipe[0].fileDescriptor.valid())
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            assertFalse("the abandoned Binder result must close its FD", pipe[0].fileDescriptor.valid())
            assertAllSlotsAvailable(caller)
        } finally {
            release.countDown()
            caller.close()
            executor.shutdown()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            dispatcher.close()
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun `a returned dump FD is closed if cancellation wins before its continuation consumes it`() {
        val caller = Caller()
        val dispatcher = QueuedDispatcher()
        val pipe = ParcelFileDescriptor.createPipe()
        val service =
            object : ITelemetryUserService.Default() {
                override fun openDump(target: Int) = DumpResult(true, null, pipe[0])
            }
        val call = caller.scope.async { source(service, dispatcher).full(CaptureTrigger.MARKER, null) }
        try {
            caller.scheduler.runCurrent()
            dispatcher.runNext() // full() enters IO, suspends on openDump.
            dispatcher.runNext() // openDump returns an FD; the readDump continuation is only queued.
            assertTrue(pipe[0].fileDescriptor.valid())
            assertFalse(call.isCompleted)
            call.cancel()
            dispatcher.drain()
            caller.scheduler.runCurrent()
            assertTrue(call.isCancelled && call.isCompleted)
            assertFalse("prompt cancellation must dispose of a result already handed to resume", pipe[0].fileDescriptor.valid())
            assertAllSlotsAvailable(caller)
        } finally {
            caller.close()
            dispatcher.drain()
            pipe.forEach { it.close() }
        }
    }

    /** Filling four queued calls and rejecting the fifth detects both leaked and double-released permits. */
    private fun assertAllSlotsAvailable(caller: Caller) {
        val dispatcher = QueuedDispatcher()
        val service = KernelService()
        val calls = List(SLOTS + 1) { caller.scope.async { source(service, dispatcher).kernelBatterySnapshot(DEADLINE_MS) } }
        try {
            caller.scheduler.runCurrent()
            assertEquals("all four, and only four, permits must be restored", SLOTS, dispatcher.size)
            assertTrue(calls.last().isCompleted)
            assertNull(calls.last().getCompleted())
            dispatcher.drain()
            caller.scheduler.runCurrent()
            calls.take(SLOTS).forEach { assertSame(KERNEL, it.getCompleted()) }
        } finally {
            calls.forEach { it.cancel() }
            dispatcher.drain()
            caller.scheduler.runCurrent()
        }
    }

    private fun source(service: ITelemetryUserService, dispatcher: CoroutineDispatcher): AndroidTelemetrySource {
        val context = RuntimeEnvironment.getApplication()
        return AndroidTelemetrySource(
            context,
            DirectShizuku(service),
            ioDispatcher = dispatcher,
            // Identity has its own physical gate; keep this suite focused on telemetry Binder admission.
            uidIdentityReader = UidIdentityReader(AndroidUidIdentityBackend(context), maxCalls = 0),
        )
    }

    private class KernelService : ITelemetryUserService.Default() {
        override fun readKernelBattery() = KERNEL
    }

    private class DirectShizuku(private val service: ITelemetryUserService) : ShizukuManager {
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)
        override fun requestPermission() = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun holdBinding(): AutoCloseable = AutoCloseable {}
        override suspend fun <T> withTelemetryService(timeoutMs: Long, block: suspend (ITelemetryUserService) -> T): T? = block(service)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val size: Int get() = queue.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runNext() = queue.removeFirst().run()
        fun drain() {
            while (queue.isNotEmpty()) runNext()
        }
    }

    private class Caller : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        fun until(done: () -> Boolean): Boolean {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            do {
                scheduler.runCurrent()
                if (done()) return true
                Thread.sleep(1)
            } while (System.nanoTime() - end < 0)
            return false
        }
        override fun close() = scope.cancel()
    }

    private fun CountDownLatch.awaitUninterruptibly() {
        var interrupted = false
        try {
            while (true) {
                try {
                    await()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SLOTS = 4
        const val DEADLINE_MS = 100L
        val KERNEL = KernelBatterySnapshot.unavailable("test")
    }
}
