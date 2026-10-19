package com.akane.inspiration.capture

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobRegistryTest {
    private val registry = JobRegistry()

    @Test
    fun `a new start cancels the run it replaces`() {
        val older = Job()
        val newer = Job()

        registry.put(1, older)
        registry.put(1, newer)

        assertTrue(older.isCancelled)
        assertFalse(newer.isCancelled)
    }

    @Test
    fun `a replaced run ending late leaves the newer run for onStopJob to cancel`() {
        val older = Job()
        val newer = Job()
        registry.put(1, older)
        registry.put(1, newer)

        registry.removeIfSame(1, older)
        registry.cancel(1)

        assertTrue(newer.isCancelled)
    }

    @Test
    fun `a run that ends removes itself so a later stop cancels nothing`() {
        val run = Job()
        registry.put(1, run)

        registry.removeIfSame(1, run)
        registry.cancel(1)

        assertFalse(run.isCancelled)
    }

    @Test
    fun `a stop cancels only the run of its own job id`() {
        val first = Job()
        val second = Job()
        registry.put(1, first)
        registry.put(2, second)

        registry.cancel(1)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
    }
}
