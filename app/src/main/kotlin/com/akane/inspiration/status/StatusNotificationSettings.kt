package com.akane.inspiration.status

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two booleans [StatusNotificationSettings] stores; a seam so the logic runs on the JVM without Robolectric. */
interface BooleanStore {
    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)
}

/** [BooleanStore] over platform [SharedPreferences]. */
class SharedPreferencesBooleanStore(private val prefs: SharedPreferences) : BooleanStore {
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * The status-notification switch (on by default) and whether the one-time POST_NOTIFICATIONS prompt was shown.
 * [enabled] is a hot [StateFlow] seeded from the store, so the Toolkit card reflects a change at once.
 */
class StatusNotificationSettings(private val store: BooleanStore) {
    private val mutableEnabled = MutableStateFlow(store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))

    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Persists first, then publishes, so a collector never sees a value the store does not have. */
    fun setEnabled(value: Boolean) {
        store.putBoolean(KEY_ENABLED, value)
        mutableEnabled.value = value
    }

    fun postPromptShown(): Boolean = store.getBoolean(KEY_POST_PROMPT_SHOWN, false)

    fun markPostPromptShown() = store.putBoolean(KEY_POST_PROMPT_SHOWN, true)

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_POST_PROMPT_SHOWN = "postPromptShown"
        private const val DEFAULT_ENABLED = true
        private const val PREFS_NAME = "status_notification"

        fun from(context: Context): StatusNotificationSettings = StatusNotificationSettings(
            SharedPreferencesBooleanStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )
    }
}
