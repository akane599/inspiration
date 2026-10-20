package com.akane.inspiration.ipc.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryModelsTest {
    @Test
    fun `dump targets accept only pinned values`() {
        assertTrue(DumpTarget.isValid(DumpTarget.BATTERYSTATS_PROTO))
        assertTrue(DumpTarget.isValid(DumpTarget.ALARM_PROTO))
        // 2 (power proto) and 4 (deviceidle text) were removed and stay rejected.
        for (target in listOf(0, 2, 4, 5)) assertFalse("target $target", DumpTarget.isValid(target))
    }
}
