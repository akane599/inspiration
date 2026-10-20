package com.akane.inspiration.toolkit

import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import com.akane.inspiration.telemetry.toolkit.ToolkitAuditLog
import com.akane.inspiration.telemetry.toolkit.ToolkitClientCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-01: a write read back too late to credit to it (`ToolkitClientCode.UNATTRIBUTED`) may well have taken effect, so
 * History shows it as unverified, not as failed. A write that read back promptly as not effective is still failed.
 */
class ToolkitHistoryUnverifiedTest {
    private fun row(code: Int) =
        ToolkitActionRecord(
            id = 3,
            startedAt = 1_000L,
            endedAt = 400_000L,
            kind = ToolkitAuditLog.KIND_APP_OP,
            pkg = "com.example.chatty",
            op = "RUN_ANY_IN_BACKGROUND",
            param = "ignore",
            status = ToolkitActionStatus.FAILED,
            code = code,
            beforeRaw = "allow",
            afterRaw = "ignore",
        )

    @Test
    fun `a write read back too late is unverified in history, not failed`() {
        val item = ToolkitMapper.history(row(ToolkitClientCode.UNATTRIBUTED), "Chatty")

        assertTrue(item.unverified)
        assertEquals(R.string.toolkit_status_unverified, historyStatusRes(item))
    }

    @Test
    fun `a write read back promptly as not effective is still failed`() {
        val item = ToolkitMapper.history(row(ToolkitCode.NOT_EFFECTIVE), "Chatty")

        assertFalse(item.unverified)
        assertEquals(R.string.toolkit_status_failed, historyStatusRes(item))
    }
}
