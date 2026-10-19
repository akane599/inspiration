package com.akane.inspiration.telemetry.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AttemptTest {
    @Test
    fun `a block that answers gives its value`() =
        runTest {
            assertEquals(42, attempt { 42 })
        }

    @Test
    fun `a block that throws gives null instead of the exception`() =
        runTest {
            assertNull(attempt<Int> { throw IllegalStateException("database or disk is full") })
        }

    @Test
    fun `cancellation is rethrown, never turned into null`() =
        runTest {
            val cancelled = CancellationException("left the screen")
            try {
                attempt<Int> { throw cancelled }
                fail("cancellation was swallowed")
            } catch (e: CancellationException) {
                assertSame(cancelled, e)
            }
        }
}
