package com.akane.inspiration.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PreloadedTest {
    @Test
    fun `the load runs on the scope, never on the thread that asks for the value`() {
        val mainThread = Executors.newSingleThreadExecutor { Thread(it, "main-sim") }
        val ioThread = Executors.newSingleThreadExecutor { Thread(it, "io-sim") }
        try {
            val loadedOn = AtomicInteger()
            val preloaded = Preloaded(CoroutineScope(ioThread.asCoroutineDispatcher())) {
                loadedOn.incrementAndGet()
                Thread.currentThread().name.substringBefore(" @")
            }

            val loader = runBlocking(mainThread.asCoroutineDispatcher()) {
                assertEquals("main-sim", Thread.currentThread().name.substringBefore(" @"))
                preloaded.get()
            }

            assertEquals("io-sim", loader)
            assertNotEquals("main-sim", loader)
            assertEquals("get() twice loads once", loader, preloaded.get())
            assertEquals(1, loadedOn.get())
        } finally {
            mainThread.shutdownNow()
            ioThread.shutdownNow()
        }
    }

    @Test
    fun `an early get waits for the load in flight instead of repeating it`() {
        val ioThread = Executors.newSingleThreadExecutor { Thread(it, "io-sim") }
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val loads = AtomicInteger()
            val preloaded = Preloaded(CoroutineScope(ioThread.asCoroutineDispatcher())) {
                loads.incrementAndGet()
                started.countDown()
                release.await()
                "ready"
            }
            started.await()

            val pending = caller.submit<String> { preloaded.get() }
            Thread.sleep(50)
            assertTrue("the caller is still waiting on the IO load", !pending.isDone)
            release.countDown()

            assertEquals("ready", pending.get())
            assertEquals(1, loads.get())
        } finally {
            caller.shutdownNow()
            ioThread.shutdownNow()
        }
    }
}
