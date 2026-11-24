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

object LibrarySort {
    const val ADDED_DESC = "added_desc"
    const val ADDED_ASC = "added_asc"
    const val RECENT = "recent"
    const val TITLE_ASC = "title_asc"
    const val TITLE_DESC = "title_desc"
    const val OFFLINE_FIRST = "offline_first"

    val supported = listOf(ADDED_DESC, ADDED_ASC, RECENT, TITLE_ASC, TITLE_DESC, OFFLINE_FIRST)
}

val SupportedSkipSeconds = listOf(5, 10, 15, 30)

val SupportedSleepFadeSeconds = listOf(12, 15, 30)

@Serializable
data class TrackSubtitleSelection(
    val primaryId: String? = null,
    val secondaryId: String? = null,
)

@Serializable
data class DeviceAudioProfile(
    val swapChannels: Boolean = false,
    val monoAudio: Boolean = false,
    val nightDynamics: Boolean = false,
    val volumeGain: Float = 1f,
) {
    fun normalized(): DeviceAudioProfile = copy(volumeGain = volumeGain.coerceIn(0.15f, 1f))
}

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
    val skipSeconds: Int = 10,
    val librarySort: String = LibrarySort.ADDED_DESC,
    val swapChannels: Boolean = false,
    val monoAudio: Boolean = false,
    val nightDynamics: Boolean = false,
    val volumeGain: Float = 1f,
    val gaplessPriority: Boolean = false,
    val softFadePause: Boolean = true,
    val duckOnInterrupt: Boolean = true,
    val smartResumeRewind: Boolean = true,
    val sleepFadeSeconds: Int = 15,
    val preserveBinaural: Boolean = true,
    val speakerGuard: Boolean = false,
    val autoDeviceAudioProfile: Boolean = false,
    val shakeExtendTimer: Boolean = false,
    val wifiOnlyDownloads: Boolean = true,
    val appLockEnabled: Boolean = false,
    val bedsideDimMode: Boolean = false,
) {
    val hasDspEffects: Boolean
        get() = swapChannels || monoAudio || nightDynamics

    fun toDeviceAudioProfile(): DeviceAudioProfile =
        DeviceAudioProfile(
            swapChannels = swapChannels,
            monoAudio = monoAudio,
            nightDynamics = nightDynamics,
            volumeGain = volumeGain,
        )

    fun applyingDeviceAudioProfile(profile: DeviceAudioProfile): Settings {
        val clean = profile.normalized()
        return copy(
            swapChannels = clean.swapChannels,
            monoAudio = clean.monoAudio,
            nightDynamics = clean.nightDynamics,
            volumeGain = clean.volumeGain,
        )
    }

    fun normalized(): Settings =
        copy(
            subtitleSize = subtitleSize.coerceIn(16, 30),
            subtitleOffsetMs = subtitleOffsetMs.coerceIn(-10_000, 10_000),
            streamingCacheMiB = streamingCacheMiB.takeIf { it in listOf(64, 128, 256, 512) } ?: 128,
            textEncoding =
                textEncoding.takeIf { it in TextEncoding.supported } ?: TextEncoding.AUTO,
            playbackSpeed = playbackSpeed.coerceIn(0.5f, 2.0f),
            skipSeconds = skipSeconds.takeIf { it in SupportedSkipSeconds } ?: 10,
            librarySort =
                librarySort.takeIf { it in LibrarySort.supported } ?: LibrarySort.ADDED_DESC,
            volumeGain = volumeGain.coerceIn(0.15f, 1f),
            sleepFadeSeconds = sleepFadeSeconds.takeIf { it in SupportedSleepFadeSeconds } ?: 15,
        )
}

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val settingsKey = stringPreferencesKey("settings-json")
    private val searchHistoryKey = stringPreferencesKey("asmr-search-history")
    private val trackSubtitleOffsetsKey = stringPreferencesKey("track-subtitle-offsets")
    private val trackSubtitleSelectionsKey = stringPreferencesKey("track-subtitle-selections")
    private val trackPositionsKey = stringPreferencesKey("track-positions")
    private val deviceAudioProfilesKey = stringPreferencesKey("device-audio-profiles")

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

    val wifiOnlyDownloadsUpdates: Flow<Boolean> =
        settingsUpdates.map { it.wifiOnlyDownloads }.distinctUntilChanged()

    val deviceAudioProfiles: StateFlow<Map<String, DeviceAudioProfile>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        settingsJson.decodeFromString<Map<String, DeviceAudioProfile>>(
                            prefs[deviceAudioProfilesKey] ?: "{}",
                        )
                    }
                    .getOrDefault(emptyMap())
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val trackSubtitleOffsets: StateFlow<Map<String, Long>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        settingsJson.decodeFromString<Map<String, Long>>(
                            prefs[trackSubtitleOffsetsKey] ?: "{}",
                        )
                    }
                    .getOrDefault(emptyMap())
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val trackSubtitleSelections: StateFlow<Map<String, TrackSubtitleSelection>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        settingsJson.decodeFromString<Map<String, TrackSubtitleSelection>>(
                            prefs[trackSubtitleSelectionsKey] ?: "{}",
                        )
                    }
                    .getOrDefault(emptyMap())
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val trackPositions: StateFlow<Map<String, Long>> =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        settingsJson.decodeFromString<Map<String, Long>>(
                            prefs[trackPositionsKey] ?: "{}",
                        )
                    }
                    .getOrDefault(emptyMap())
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

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

    fun subtitleOffsetFor(trackId: String?): Long {
        if (trackId == null) return 0L
        return trackSubtitleOffsets.value[trackId] ?: 0L
    }

    fun subtitleSelectionFor(trackId: String?): TrackSubtitleSelection? = trackId?.let {
        trackSubtitleSelections.value[it]
    }

    fun savedTrackPosition(trackId: String?): Long = trackId?.let { trackPositions.value[it] } ?: 0L

    suspend fun setTrackSubtitleOffset(trackId: String, offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-10_000L, 10_000L)
        context.preferences.edit { prefs ->
            val current = runCatching {
                settingsJson.decodeFromString<LinkedHashMap<String, Long>>(
                    prefs[trackSubtitleOffsetsKey] ?: "{}",
                )
            }
                .getOrDefault(LinkedHashMap())
            current.remove(trackId)
            if (clamped != 0L) current[trackId] = clamped
            while (current.size > 200) {
                val eldest = current.entries.iterator().next().key
                current.remove(eldest)
            }
            prefs[trackSubtitleOffsetsKey] = settingsJson.encodeToString<Map<String, Long>>(current)
        }
    }

    suspend fun setTrackSubtitleSelection(trackId: String, selection: TrackSubtitleSelection?) {
        context.preferences.edit { prefs ->
            val current = runCatching {
                settingsJson.decodeFromString<LinkedHashMap<String, TrackSubtitleSelection>>(
                    prefs[trackSubtitleSelectionsKey] ?: "{}",
                )
            }
                .getOrDefault(LinkedHashMap())
            current.remove(trackId)
            if (
                selection != null && (selection.primaryId != null || selection.secondaryId != null)
            ) {
                current[trackId] = selection
            }
            while (current.size > 200) {
                val eldest = current.entries.iterator().next().key
                current.remove(eldest)
            }
            prefs[trackSubtitleSelectionsKey] =
                settingsJson.encodeToString<Map<String, TrackSubtitleSelection>>(current)
        }
    }

    suspend fun rememberTrackPosition(trackId: String, positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        context.preferences.edit { prefs ->
            val current = runCatching {
                settingsJson.decodeFromString<LinkedHashMap<String, Long>>(
                    prefs[trackPositionsKey] ?: "{}",
                )
            }
                .getOrDefault(LinkedHashMap())
            current.remove(trackId)
            if (clamped > 0L) current[trackId] = clamped
            while (current.size > 300) {
                val eldest = current.entries.iterator().next().key
                current.remove(eldest)
            }
            prefs[trackPositionsKey] = settingsJson.encodeToString<Map<String, Long>>(current)
        }
    }

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

    fun deviceAudioProfileFor(deviceKey: String?): DeviceAudioProfile? = deviceKey?.let {
        deviceAudioProfiles.value[it]
    }

    suspend fun saveDeviceAudioProfile(deviceKey: String, profile: DeviceAudioProfile) {
        val cleanKey = deviceKey.trim().take(120)
        if (cleanKey.isEmpty()) return
        context.preferences.edit { prefs ->
            val current = runCatching {
                settingsJson.decodeFromString<LinkedHashMap<String, DeviceAudioProfile>>(
                    prefs[deviceAudioProfilesKey] ?: "{}",
                )
            }
                .getOrDefault(LinkedHashMap())
            current.remove(cleanKey)
            current[cleanKey] = profile.normalized()
            while (current.size > 24) {
                val eldest = current.entries.iterator().next().key
                current.remove(eldest)
            }
            prefs[deviceAudioProfilesKey] =
                settingsJson.encodeToString<Map<String, DeviceAudioProfile>>(current)
        }
    }

    suspend fun replaceBackupMaps(
        settings: Settings,
        subtitleOffsets: Map<String, Long>,
        subtitleSelections: Map<String, TrackSubtitleSelection>,
        trackPositions: Map<String, Long>,
        deviceProfiles: Map<String, DeviceAudioProfile>,
    ) {
        context.preferences.edit { prefs ->
            prefs[settingsKey] = settingsJson.encodeToString(settings.normalized())
            if (subtitleOffsets.isNotEmpty()) {
                val existing = runCatching {
                    settingsJson.decodeFromString<LinkedHashMap<String, Long>>(
                        prefs[trackSubtitleOffsetsKey] ?: "{}",
                    )
                }
                    .getOrDefault(LinkedHashMap())
                subtitleOffsets.forEach { (k, v) -> existing[k] = v.coerceIn(-10_000L, 10_000L) }
                while (existing.size > 200) existing.remove(existing.entries.first().key)
                prefs[trackSubtitleOffsetsKey] =
                    settingsJson.encodeToString<Map<String, Long>>(existing)
            }
            if (subtitleSelections.isNotEmpty()) {
                val existing = runCatching {
                    settingsJson.decodeFromString<
                        LinkedHashMap<String, TrackSubtitleSelection>,
                    >(
                        prefs[trackSubtitleSelectionsKey] ?: "{}",
                    )
                }
                    .getOrDefault(LinkedHashMap())
                existing.putAll(subtitleSelections)
                while (existing.size > 200) existing.remove(existing.entries.first().key)
                prefs[trackSubtitleSelectionsKey] =
                    settingsJson.encodeToString<Map<String, TrackSubtitleSelection>>(existing)
            }
            if (trackPositions.isNotEmpty()) {
                val existing = runCatching {
                    settingsJson.decodeFromString<LinkedHashMap<String, Long>>(
                        prefs[trackPositionsKey] ?: "{}",
                    )
                }
                    .getOrDefault(LinkedHashMap())
                trackPositions.forEach { (k, v) -> if (v > 0L) existing[k] = v }
                while (existing.size > 300) existing.remove(existing.entries.first().key)
                prefs[trackPositionsKey] = settingsJson.encodeToString<Map<String, Long>>(existing)
            }
            if (deviceProfiles.isNotEmpty()) {
                val existing = runCatching {
                    settingsJson.decodeFromString<
                        LinkedHashMap<String, DeviceAudioProfile>,
                    >(
                        prefs[deviceAudioProfilesKey] ?: "{}",
                    )
                }
                    .getOrDefault(LinkedHashMap())
                deviceProfiles.forEach { (k, v) -> existing[k] = v.normalized() }
                while (existing.size > 24) existing.remove(existing.entries.first().key)
                prefs[deviceAudioProfilesKey] =
                    settingsJson.encodeToString<Map<String, DeviceAudioProfile>>(existing)
            }
        }
    }
}
