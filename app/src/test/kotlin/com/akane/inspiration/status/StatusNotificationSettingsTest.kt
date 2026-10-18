package com.akane.inspiration.status

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusNotificationSettingsTest {
    private class FakeStore(val values: MutableMap<String, Boolean> = mutableMapOf()) : BooleanStore {
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] ?: default

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun `a fresh store is enabled and the post prompt is not yet shown`() {
        val settings = StatusNotificationSettings(FakeStore())

        assertTrue(settings.enabled.value)
        assertFalse(settings.postPromptShown())
    }

    @Test
    fun `setEnabled persists and updates the flow`() {
        val store = FakeStore()
        val settings = StatusNotificationSettings(store)

        settings.setEnabled(false)

        assertFalse(settings.enabled.value)
        assertFalse(store.values.getValue(StatusNotificationSettings.KEY_ENABLED))
    }

    @Test
    fun `a new instance over the same store reads the persisted switch and prompt flag`() {
        val store = FakeStore()
        StatusNotificationSettings(store).apply {
            setEnabled(false)
            markPostPromptShown()
        }

        val reloaded = StatusNotificationSettings(store)

        assertFalse(reloaded.enabled.value)
        assertTrue(reloaded.postPromptShown())
    }
}
