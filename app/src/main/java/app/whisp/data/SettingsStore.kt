// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val settingsJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class AppPreferences(
    val settings: Settings = Settings(),
    val asmrSearchHistory: List<String> = emptyList(),
    val deviceAudioProfiles: Map<String, DeviceAudioProfile> = emptyMap(),
)

private object AppPreferencesSerializer : Serializer<AppPreferences> {
    override val defaultValue: AppPreferences = AppPreferences()

    override suspend fun readFrom(input: InputStream): AppPreferences {
        val raw = input.readBytes().toString(Charsets.UTF_8)
        if (raw.isBlank()) return defaultValue
        return try {
            settingsJson.decodeFromString<AppPreferences>(raw)
        } catch (e: SerializationException) {
            throw CorruptionException("Corrupted settings file", e)
        }
    }

    override suspend fun writeTo(t: AppPreferences, output: OutputStream) {
        output.write(settingsJson.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}

private val Context.appPreferencesStore by
    dataStore(
        fileName = "whisp_settings.json",
        serializer = AppPreferencesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppPreferences() },
    )

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
    private val preferencesFlow: Flow<AppPreferences> = context.appPreferencesStore.data

    // No placeholder value: background resumption and privacy hooks read persisted preferences.
    val settingsUpdates: Flow<Settings> =
        preferencesFlow.map { it.settings.normalized() }.distinctUntilChanged()

    val settings: StateFlow<Settings> =
        settingsUpdates.stateIn(scope, SharingStarted.Eagerly, Settings())

    val streamingCacheMiBUpdates: Flow<Int> =
        settingsUpdates.map { it.streamingCacheMiB }.distinctUntilChanged()

    val wifiOnlyDownloadsUpdates: Flow<Boolean> =
        settingsUpdates.map { it.wifiOnlyDownloads }.distinctUntilChanged()

    val deviceAudioProfiles: StateFlow<Map<String, DeviceAudioProfile>> =
        preferencesFlow
            .map { it.deviceAudioProfiles }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    val asmrSearchHistory: StateFlow<List<String>> =
        preferencesFlow
            .map { it.asmrSearchHistory.take(20) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    suspend fun update(transform: (Settings) -> Settings) {
        context.appPreferencesStore.updateData { prefs ->
            prefs.copy(settings = transform(prefs.settings.normalized()).normalized())
        }
    }

    suspend fun rememberAsmrSearch(text: String) {
        val clean = normalizedAsmrInput(text).take(300)
        if (clean.isBlank()) return
        context.appPreferencesStore.updateData { prefs ->
            val next = (listOf(clean) + prefs.asmrSearchHistory).distinct().take(20)
            prefs.copy(asmrSearchHistory = next)
        }
    }

    suspend fun clearAsmrSearchHistory() {
        context.appPreferencesStore.updateData { prefs ->
            if (prefs.asmrSearchHistory.isEmpty()) prefs
            else prefs.copy(asmrSearchHistory = emptyList())
        }
    }

    suspend fun deviceAudioProfileFor(deviceKey: String?): DeviceAudioProfile? = deviceKey?.let {
        currentDeviceAudioProfiles()[it]
    }

    suspend fun currentDeviceAudioProfiles(): Map<String, DeviceAudioProfile> =
        preferencesFlow.first().deviceAudioProfiles

    suspend fun saveDeviceAudioProfile(deviceKey: String, profile: DeviceAudioProfile) {
        val cleanKey = deviceKey.trim().take(120)
        if (cleanKey.isEmpty()) return
        context.appPreferencesStore.updateData { prefs ->
            val next = LinkedHashMap(prefs.deviceAudioProfiles)
            next.remove(cleanKey)
            next[cleanKey] = profile.normalized()
            while (next.size > 24) {
                next.remove(next.entries.iterator().next().key)
            }
            prefs.copy(deviceAudioProfiles = next)
        }
    }

    suspend fun restoreBackup(
        settings: Settings,
        deviceProfiles: Map<String, DeviceAudioProfile>,
    ) {
        context.appPreferencesStore.updateData { prefs ->
            val mergedProfiles =
                if (deviceProfiles.isEmpty()) prefs.deviceAudioProfiles
                else {
                    val next = LinkedHashMap(prefs.deviceAudioProfiles)
                    deviceProfiles.forEach { (k, v) -> next[k] = v.normalized() }
                    while (next.size > 24) {
                        next.remove(next.entries.iterator().next().key)
                    }
                    next
                }
            prefs.copy(
                settings = settings.normalized(),
                deviceAudioProfiles = mergedProfiles,
            )
        }
    }
}
