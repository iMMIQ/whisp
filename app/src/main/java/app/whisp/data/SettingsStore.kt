// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

private val Context.preferences by preferencesDataStore("settings")

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
)

internal class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val sizeKey = intPreferencesKey("subtitle-size")
    private val offsetKey = longPreferencesKey("subtitle-offset")
    private val privateKey = booleanPreferencesKey("hide-artwork")
    private val motionKey = booleanPreferencesKey("reduce-motion")
    private val cacheKey = intPreferencesKey("streaming-cache-mib")
    private val recentsKey = booleanPreferencesKey("hide-recents")
    private val feedbackKey = booleanPreferencesKey("touch-feedback")
    private val encodingKey = stringPreferencesKey("text-encoding")
    private val speedKey = floatPreferencesKey("playback-speed")
    private val searchHistoryKey = stringPreferencesKey("asmr-search-history")

    // This flow has no placeholder value: background resumption must read real privacy preferences.
    val settingsUpdates: Flow<Settings> =
        context.preferences.data.map {
            Settings(
                it[sizeKey] ?: 21,
                it[offsetKey] ?: 0,
                it[privateKey] ?: false,
                it[motionKey] ?: false,
                it[cacheKey] ?: 128,
                it[recentsKey] ?: false,
                it[feedbackKey] ?: true,
                it[encodingKey]?.takeIf { enc -> enc in TextEncoding.supported }
                    ?: TextEncoding.AUTO,
                (it[speedKey] ?: 1f).coerceIn(0.5f, 2.0f),
            )
        }

    val settings: StateFlow<Settings> =
        settingsUpdates.stateIn(scope, SharingStarted.Eagerly, Settings())

    val streamingCacheMiBUpdates: Flow<Int> =
        context.preferences.data.map { it[cacheKey] ?: 128 }.distinctUntilChanged()

    val asmrSearchHistory: StateFlow<List<String>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        Json.parseToJsonElement(prefs[searchHistoryKey] ?: "[]")
                            .jsonArray
                            .map { it.jsonPrimitive.content }
                            .take(20)
                    }
                    .getOrDefault(emptyList())
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun setSettings(
        size: Int? = null,
        offset: Long? = null,
        hide: Boolean? = null,
        reduceMotion: Boolean? = null,
        streamingCacheMiB: Int? = null,
        hideRecents: Boolean? = null,
        touchFeedback: Boolean? = null,
        textEncoding: String? = null,
        playbackSpeed: Float? = null,
    ) {
        context.preferences.edit { prefs ->
            size?.let { prefs[sizeKey] = it.coerceIn(16, 30) }
            offset?.let { prefs[offsetKey] = it.coerceIn(-10000, 10000) }
            hide?.let { prefs[privateKey] = it }
            reduceMotion?.let { prefs[motionKey] = it }
            hideRecents?.let { prefs[recentsKey] = it }
            touchFeedback?.let { prefs[feedbackKey] = it }
            streamingCacheMiB?.let {
                require(it in listOf(64, 128, 256, 512))
                prefs[cacheKey] = it
            }
            textEncoding?.let {
                require(it in TextEncoding.supported)
                prefs[encodingKey] = it
            }
            playbackSpeed?.let { prefs[speedKey] = it.coerceIn(0.5f, 2.0f) }
        }
    }

    suspend fun rememberAsmrSearch(text: String) {
        val clean = normalizedAsmrInput(text).take(300)
        if (clean.isBlank()) return
        context.preferences.edit { prefs ->
            val previous = runCatching {
                Json.parseToJsonElement(prefs[searchHistoryKey] ?: "[]").jsonArray.map {
                    it.jsonPrimitive.content
                }
            }
                .getOrDefault(emptyList())
            prefs[searchHistoryKey] =
                JsonArray((listOf(clean) + previous).distinct().take(20).map(::JsonPrimitive))
                    .toString()
        }
    }

    suspend fun clearAsmrSearchHistory() {
        context.preferences.edit { it.remove(searchHistoryKey) }
    }
}
