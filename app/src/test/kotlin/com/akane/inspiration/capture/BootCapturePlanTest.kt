package com.akane.inspiration.capture

import android.content.Intent
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootCapturePlanTest {
    @Test
    fun `a completed boot is captured as BOOT`() {
        assertEquals(CaptureTrigger.BOOT, BootCapturePlan.forAction(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `an app update is captured as PACKAGE_REPLACED, not as a boot`() {
        assertEquals(CaptureTrigger.PACKAGE_REPLACED, BootCapturePlan.forAction(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `any other action is ignored`() {
        assertNull(BootCapturePlan.forAction(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(BootCapturePlan.forAction(Intent.ACTION_PACKAGE_REPLACED))
        assertNull(BootCapturePlan.forAction(null))
    }
}
