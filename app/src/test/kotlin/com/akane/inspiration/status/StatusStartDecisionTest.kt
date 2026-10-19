package com.akane.inspiration.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatusStartDecisionTest {
    private val calls = mutableListOf<String>()

    private fun start(
        foreground: Boolean,
        shouldRun: Boolean,
    ) = StatusStartDecision.start(
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

    @Test
    fun `sync starts the service when it should run and stops it otherwise`() {
        for (shouldRun in listOf(true, false)) {
            StatusStartDecision.sync({ shouldRun }, { calls += "start" }, { calls += "stop" }, { calls += "refused" })
        }

        assertEquals(listOf("start", "stop"), calls)
    }

    @Test
    fun `a start the platform refuses is reported, never thrown into the boot receiver`() {
        val refusal = IllegalStateException("ForegroundServiceStartNotAllowedException")
        var reported: IllegalStateException? = null

        StatusStartDecision.sync({ true }, { throw refusal }, { calls += "stop" }, { reported = it })

        assertEquals(refusal, reported)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `sync contains only refusals`() {
        assertThrows(SecurityException::class.java) {
            StatusStartDecision.sync({ true }, { throw SecurityException("missing permission") }, {}, {})
        }
    }
}
