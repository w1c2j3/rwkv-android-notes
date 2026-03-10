package com.example.rwkvnotes.config

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.inferenceStore by preferencesDataStore(name = "inference_settings")

data class InferenceSettings(
    val maxTokens: Int,
    val temperature: Double,
    val topP: Double,
)

@Singleton
class InferenceSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    appConfig: AppConfig,
) {
    private val defaultMaxTokens = appConfig.model.maxTokens
    private val defaultTemperature = appConfig.model.temperature
    private val defaultTopP = appConfig.model.topP

    val settingsFlow: Flow<InferenceSettings> = context.inferenceStore.data.map { prefs ->
        InferenceSettings(
            maxTokens = prefs[KEY_MAX_TOKENS] ?: defaultMaxTokens,
            temperature = prefs[KEY_TEMPERATURE] ?: defaultTemperature,
            topP = prefs[KEY_TOP_P] ?: defaultTopP,
        )
    }

    suspend fun update(maxTokens: Int, temperature: Double, topP: Double) {
        require(maxTokens > 0) { "maxTokens must be > 0" }
        require(temperature >= 0.0) { "temperature must be >= 0" }
        require(topP in 0.0..1.0) { "topP must be in [0,1]" }
        context.inferenceStore.edit { prefs ->
            prefs[KEY_MAX_TOKENS] = maxTokens
            prefs[KEY_TEMPERATURE] = temperature
            prefs[KEY_TOP_P] = topP
        }
    }

    companion object {
        private val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        private val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        private val KEY_TOP_P = doublePreferencesKey("top_p")
    }
}
