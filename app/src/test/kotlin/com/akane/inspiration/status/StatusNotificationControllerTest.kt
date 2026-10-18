package com.akane.inspiration.status

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusNotificationControllerTest {
    @Test
    fun `runs only when enabled and not blocked`() {
        assertTrue(StatusNotificationController.shouldRun(enabled = true, blocked = false))
    }

    @Test
    fun `stays stopped when the switch is off`() {
        assertFalse(StatusNotificationController.shouldRun(enabled = false, blocked = false))
    }

    @Test
    fun `stays stopped when notifications are blocked even with the switch on`() {
        assertFalse(StatusNotificationController.shouldRun(enabled = true, blocked = true))
    }

    @Test
    fun `stays stopped when off and blocked`() {
        assertFalse(StatusNotificationController.shouldRun(enabled = false, blocked = true))
    }
}
