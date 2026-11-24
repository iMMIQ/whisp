// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("settings")

private val settingsJson = Json { ignoreUnknownKeys = true }

@Serializable
data class Settings(
    val subtitleSize: Int = 21,
    val subtitleOffsetMs: Long = 0,
    val hideArtwork: Boolean = false,
    val reduceMotion: Boolean = false,
    val streamingCacheMiB: Int = 128,
    val hideRecents: Boolean = false,
    val touchFeedback: Boolean = true,
    val textEncoding: String = TextEncoding.AUTO,
    val playbackSpeed: Float = 1f,
    val legacyImagesMigrated: Boolean = false,
    val amoledBlack: Boolean = false,
) {
    fun normalized(): Settings =
        copy(
            subtitleSize = subtitleSize.coerceIn(16, 30),
            subtitleOffsetMs = subtitleOffsetMs.coerceIn(-10_000, 10_000),
            streamingCacheMiB = streamingCacheMiB.takeIf { it in listOf(64, 128, 256, 512) } ?: 128,
            textEncoding =
                textEncoding.takeIf { it in TextEncoding.supported } ?: TextEncoding.AUTO,
            playbackSpeed = playbackSpeed.coerceIn(0.5f, 2.0f),
        )
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val settingsKey = stringPreferencesKey("settings-json")
    private val searchHistoryKey = stringPreferencesKey("asmr-search-history")

    private fun decode(prefs: Preferences): Settings {
        prefs[settingsKey]?.let { raw ->
            runCatching { settingsJson.decodeFromString<Settings>(raw).normalized() }
                .getOrNull()
                ?.let {
                    return it
                }
        }
        // Seamless one-way read of legacy individual keys from pre-v0.5 installs.
        return Settings(
                subtitleSize = prefs[intPreferencesKey("subtitle-size")] ?: 21,
                subtitleOffsetMs = prefs[longPreferencesKey("subtitle-offset")] ?: 0,
                hideArtwork = prefs[booleanPreferencesKey("hide-artwork")] ?: false,
                reduceMotion = prefs[booleanPreferencesKey("reduce-motion")] ?: false,
                streamingCacheMiB = prefs[intPreferencesKey("streaming-cache-mib")] ?: 128,
                hideRecents = prefs[booleanPreferencesKey("hide-recents")] ?: false,
                touchFeedback = prefs[booleanPreferencesKey("touch-feedback")] ?: true,
                textEncoding = prefs[stringPreferencesKey("text-encoding")] ?: TextEncoding.AUTO,
                playbackSpeed = prefs[floatPreferencesKey("playback-speed")] ?: 1f,
            )
            .normalized()
    }

    // No placeholder value: background resumption and privacy hooks read persisted preferences.
    val settingsUpdates: Flow<Settings> =
        context.preferences.data.map(::decode).distinctUntilChanged()

    val settings: StateFlow<Settings> =
        settingsUpdates.stateIn(scope, SharingStarted.Eagerly, Settings())

    val streamingCacheMiBUpdates: Flow<Int> =
        settingsUpdates.map { it.streamingCacheMiB }.distinctUntilChanged()

    val asmrSearchHistory: StateFlow<List<String>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        settingsJson
                            .decodeFromString<List<String>>(prefs[searchHistoryKey] ?: "[]")
                            .take(20)
                    }
                    .getOrDefault(emptyList())
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun update(transform: (Settings) -> Settings) {
        context.preferences.edit { prefs ->
            val updated = transform(decode(prefs)).normalized()
            prefs[settingsKey] = settingsJson.encodeToString(updated)
        }
    }

    suspend fun rememberAsmrSearch(text: String) {
        val clean = normalizedAsmrInput(text).take(300)
        if (clean.isBlank()) return
        context.preferences.edit { prefs ->
            val previous = runCatching {
                settingsJson.decodeFromString<List<String>>(prefs[searchHistoryKey] ?: "[]")
            }
                .getOrDefault(emptyList())
            val next = (listOf(clean) + previous).distinct().take(20)
            prefs[searchHistoryKey] = settingsJson.encodeToString(next)
        }
    }

    suspend fun clearAsmrSearchHistory() {
        context.preferences.edit { it.remove(searchHistoryKey) }
    }
}
