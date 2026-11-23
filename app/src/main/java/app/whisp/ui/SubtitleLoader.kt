// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.util.LruCache
import app.whisp.data.Cue
import app.whisp.data.LibraryStore
import app.whisp.data.SettingsStore
import app.whisp.data.SubtitleTimeline
import app.whisp.data.TextEncoding
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Subtitle loading, encoding-aware decoding, and bounded LRU caching owned by PlaybackCoordinator.
 */
class SubtitleLoader(
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    settings: SettingsStore,
) {
    val cues = MutableStateFlow<List<Cue>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    var currentTrackId: String? = null
        private set

    private val cache = LruCache<String, List<Cue>>(16)
    private var job: Job? = null
    private var encoding: String = settings.settings.value.textEncoding

    init {
        scope.launch {
            settings.settingsUpdates
                .map { it.textEncoding }
                .distinctUntilChanged()
                .collect(::onEncodingChanged)
        }
    }

    fun onEncodingChanged(newEncoding: String) {
        if (encoding == newEncoding) return
        encoding = newEncoding
        cache.evictAll()
        val active = currentTrackId
        if (active != null) load(active, force = true)
    }

    fun removeCached(ids: Set<String>) {
        ids.forEach { cache.remove(it) }
    }

    fun load(id: String?, force: Boolean = false) {
        if (!force && id != null && id == currentTrackId && job?.isActive == true) return
        job?.cancel()
        currentTrackId = id
        val cached = if (force || id == null) null else cache[id]
        cues.value = cached.orEmpty()
        loading.value = false
        error.value = null
        if (id == null || cached != null) return
        val requestedEncoding = encoding
        val nextJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val track = library.track(id) ?: return@launch
                    val attachmentId = track.subtitleId ?: return@launch
                    loading.value = true
                    val attachment = library.dao.attachment(attachmentId) ?: return@launch
                    val bytes = library.readAsset(attachment.id)
                    val parsed =
                        withContext(Dispatchers.Default) {
                            SubtitleTimeline.parse(
                                TextEncoding.decode(bytes, requestedEncoding),
                                attachment.fileName.substringAfterLast('.'),
                            )
                        }
                    ensureActive()
                    if (requestedEncoding == encoding) {
                        cache.put(id, parsed)
                        if (currentTrackId == id) cues.value = parsed
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (currentTrackId == id && job === currentCoroutineContext()[Job]) {
                        error.value = "字幕暂时不可用"
                    }
                } finally {
                    if (currentTrackId == id && job === currentCoroutineContext()[Job]) {
                        loading.value = false
                    }
                }
            }
        job = nextJob
        nextJob.start()
    }

    fun retry(id: String? = currentTrackId) {
        if (id != null && id == currentTrackId && error.value != null && job?.isActive != true) {
            load(id)
        }
    }
}
