package de.cs.transkribio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages app settings using SharedPreferences.
 * Provides reactive StateFlow for settings changes.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _postProcessingEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_POST_PROCESSING, true)
    )
    val postProcessingEnabled: StateFlow<Boolean> = _postProcessingEnabled.asStateFlow()

    fun setPostProcessingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_POST_PROCESSING, enabled).apply()
        _postProcessingEnabled.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "transkribio_settings"
        private const val KEY_POST_PROCESSING = "post_processing_enabled"

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
