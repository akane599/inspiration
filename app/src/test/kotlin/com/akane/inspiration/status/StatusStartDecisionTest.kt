package com.akane.inspiration.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusStartDecisionTest {
    private val calls = mutableListOf<String>()
    private val gate = StatusStartGate()

    private fun start(
        foreground: Boolean,
        shouldRun: Boolean,
    ) = StatusStartDecision.start(
        gate = gate,
        enterForeground = { foreground.also { calls += "foreground" } },
        shouldRun = { shouldRun.also { calls += "shouldRun" } },
        ensureCollector = { calls += "collector" },
        stop = { calls += "stop" },
    )

    @Test
    fun `an allowed start enters the foreground before anything else and keeps collecting`() {
        assertEquals(StatusStartDecision.Start.STICKY, start(foreground = true, shouldRun = true))
        assertEquals(listOf("foreground", "shouldRun", "collector"), calls)
    }

    @Test
    fun `a blocked start still enters the foreground before it stops`() {
        assertEquals(StatusStartDecision.Start.NOT_STICKY, start(foreground = true, shouldRun = false))
        assertEquals(listOf("foreground", "shouldRun", "stop"), calls)
    }

    @Test
    fun `a refused foreground stops without reading the switch or collecting`() {
        assertEquals(StatusStartDecision.Start.NOT_STICKY, start(foreground = false, shouldRun = true))
        assertEquals(listOf("foreground", "stop"), calls)
    }

    @Test
    fun `a stopped service drops a finished redraw without reading the switch`() {
        val post = StatusStartDecision.post(stopped = true) { true.also { calls += "shouldRun" } }

        assertEquals(StatusStartDecision.Post.DROP, post)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a redraw posts only while the notification may be shown, else the service stops`() {
        assertEquals(StatusStartDecision.Post.SHOW, StatusStartDecision.post(stopped = false) { true })
        assertEquals(StatusStartDecision.Post.STOP, StatusStartDecision.post(stopped = false) { false })
    }

    private fun sync(shouldRun: Boolean) =
        StatusStartDecision.sync(gate, { shouldRun }, { calls += "start" }, { calls += "stop" }, { calls += "refused" })

    @Test
    fun `sync starts the service when it should run and stops it otherwise`() {
        sync(shouldRun = true)
        start(foreground = true, shouldRun = true)
        sync(shouldRun = false)

        assertEquals(listOf("start", "foreground", "shouldRun", "collector", "stop"), calls)
    }

    @Test
    fun `a stop is never sent while a foreground start is still pending (R-06)`() {
        sync(shouldRun = true)
        sync(shouldRun = false)

        // stopService now would make the platform crash the app: startForegroundService without startForeground.
        assertEquals(listOf("start"), calls)
        assertTrue(gate.pending)
    }

    @Test
    fun `the pending start enters the foreground, re-reads the switch and stops itself (R-06)`() {
        sync(shouldRun = true)
        sync(shouldRun = false)

        assertEquals(StatusStartDecision.Start.NOT_STICKY, start(foreground = true, shouldRun = false))
        assertEquals(listOf("start", "foreground", "shouldRun", "stop"), calls)
        assertFalse(gate.pending)
    }

    @Test
    fun `a refused foreground settles the pending start, so a later stop goes through`() {
        sync(shouldRun = true)
        start(foreground = false, shouldRun = true)
        sync(shouldRun = false)

        assertEquals(listOf("start", "foreground", "stop", "stop"), calls)
        assertFalse(gate.pending)
    }

    @Test
    fun `a start that throws in the service still settles the pending start`() {
        sync(shouldRun = true)

        assertThrows(IllegalStateException::class.java) {
            StatusStartDecision.start(gate, { true }, { error("container") }, {}, {})
        }
        assertFalse(gate.pending)
    }

    @Test
    fun `a sticky restart the controller did not ask for leaves no start pending`() {
        start(foreground = true, shouldRun = true)

        assertFalse(gate.pending)
    }

    @Test
    fun `a start the platform refuses is reported, never thrown into the boot receiver, and is not pending`() {
        val refusal = IllegalStateException("ForegroundServiceStartNotAllowedException")
        var reported: IllegalStateException? = null

        StatusStartDecision.sync(gate, { true }, { throw refusal }, { calls += "stop" }, { reported = it })

        assertEquals(refusal, reported)
        assertEquals(emptyList<String>(), calls)
        assertFalse(gate.pending)
        sync(shouldRun = false)
        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `sync contains only refusals`() {
        assertThrows(SecurityException::class.java) {
            StatusStartDecision.sync(gate, { true }, { throw SecurityException("missing permission") }, {}, {})
        }
    }
}
