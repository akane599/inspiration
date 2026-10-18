package com.akane.inspiration.di

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationScopeTest {
    @Test
    fun `an uncaught failure is handled and leaves the scope and its siblings running`() = runTest {
        val caught = mutableListOf<Throwable>()
        val scope = newApplicationScope(StandardTestDispatcher(testScheduler)) { caught += it }
        val failure = IllegalStateException("database or disk is full")
        var siblingRan = false

        scope.launch { throw failure }
        testScheduler.advanceUntilIdle()
        scope.launch { siblingRan = true }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Throwable>(failure), caught)
        assertTrue(siblingRan)
        assertTrue(scope.isActive)
    }
}
