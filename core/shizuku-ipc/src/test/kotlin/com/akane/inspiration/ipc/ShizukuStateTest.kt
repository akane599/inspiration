package com.akane.inspiration.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuStateTest {
    @Test
    fun `each Shizuku state that is not ready has its own blocked reason`() {
        assertEquals(BlockedReason.NOT_INSTALLED, ShizukuState.NotInstalled.blockedReason())
        assertEquals(BlockedReason.NOT_RUNNING, ShizukuState.NotRunning.blockedReason())
        assertEquals(BlockedReason.PERMISSION_REQUIRED, ShizukuState.PermissionRequired(ShizukuBackend.SUI).blockedReason())
        assertEquals(BlockedReason.PERMISSION_DENIED, ShizukuState.PermissionDenied().blockedReason())
        assertEquals(BlockedReason.UNSUPPORTED_SERVER, ShizukuState.UnsupportedServer(12).blockedReason())
        assertNull(ShizukuState.Ready(2000, 13).blockedReason())
    }
}
