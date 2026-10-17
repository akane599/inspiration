package com.akane.inspiration.sleeplab

import com.akane.inspiration.diagnostics.dictionary.EntryKind
import com.akane.inspiration.diagnostics.dictionary.Explanation
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepLabDiagnosticsTest {
    private fun row(uid: Int, vararg tags: String) = AlarmRow(
        uid = uid,
        packageName = "pkg.$uid",
        appLabel = "App $uid",
        wakeups = tags.size.toLong(),
        tags = persistentListOf(*tags.map { AlarmTagRow(it, 1) }.toTypedArray()),
    )

    private val generic = Explanation("alarm_prefix", "alarm", "Wake-up alarm", "An app scheduled an alarm.", null)
    private val keepAlive = Explanation("alarm_keepalive", "alarm", "Keep-alive", "A keep-alive ping.", null)

    private val explain = { kind: EntryKind, tag: String ->
        when {
            kind != EntryKind.ALARM -> null
            tag.contains("KEEPALIVE") -> keepAlive
            tag.startsWith("*walarm*:") -> generic
            else -> null
        }
    }

    @Test
    fun `an explanation shared by several alarm rows is shown once, on the first`() {
        val rows = listOf(row(1, "*walarm*:a.SYNC"), row(2, "*walarm*:b.POLL"), row(3, "*walarm*:c.TICK"))

        assertEquals(listOf(generic, null, null), alarmExplanationsOnce(rows, explain))
    }

    @Test
    fun `different explanations are each shown once and rows without one stay empty`() {
        val rows = listOf(row(1, "*walarm*:a.KEEPALIVE"), row(2, "*walarm*:b.POLL"), row(3, "unknown"), row(4, "*walarm*:d.KEEPALIVE"), row(5))

        assertEquals(listOf(keepAlive, generic, null, null, null), alarmExplanationsOnce(rows, explain))
    }
}
