package com.elmdash.core.telemetry

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cacheDataStore by preferencesDataStore(name = "elmdash_telemetry_cache")

/**
 * Persists last known scalar telemetry values for Zero-Blank-UI on cold start.
 */
class TelemetryCache(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Map<String, Double> {
        val raw = context.cacheDataStore.data.first()[KEY_CACHED_VALUES] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Double>>(raw)
        }.getOrDefault(emptyMap())
    }

    suspend fun save(values: Map<String, Double>) {
        if (values.isEmpty()) return
        val encoded = json.encodeToString(values)
        context.cacheDataStore.edit { prefs ->
            prefs[KEY_CACHED_VALUES] = encoded
        }
    }

    companion object {
        private val KEY_CACHED_VALUES = stringPreferencesKey("cached_values_json")
    }
}
