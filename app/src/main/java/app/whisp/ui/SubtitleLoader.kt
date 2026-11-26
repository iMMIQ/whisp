// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.os.PowerManager
import android.util.LruCache
import app.whisp.data.Cue
import app.whisp.data.DisplayCue
import app.whisp.data.LibraryIndexer
import app.whisp.data.LibraryStore
import app.whisp.data.SettingsStore
import app.whisp.data.SubtitleLang
import app.whisp.data.SubtitleTimeline
import app.whisp.data.TextEncoding
import app.whisp.data.TrackEntity
import app.whisp.data.TrackSubtitleSelection
import app.whisp.data.WorkDetails
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class WorkSubtitleMatch(
    val track: TrackEntity,
    val cue: Cue,
)

/**
 * Subtitle loading, encoding-aware decoding, language-priority auto-bilingual pairing, next-track
 * prefetching, whole-work subtitle search, and bounded LRU caching owned by PlaybackCoordinator.
 */
class SubtitleLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val settings: SettingsStore,
) {
    val cues = MutableStateFlow<List<Cue>>(emptyList())
    val displayCues = MutableStateFlow<List<DisplayCue>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val activePrimaryId = MutableStateFlow<String?>(null)
    val activeSecondaryId = MutableStateFlow<String?>(null)

    var currentTrackId: String? = null
        private set

    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val attachmentCache = LruCache<String, List<Cue>>(24)
    private var job: Job? = null
    private var prefetchJob: Job? = null
    private var deferredTrackId: String? = null
    private var encoding: String = settings.settings.value.textEncoding
    private var preferredPrimaryLang: String = settings.settings.value.preferredPrimarySubtitleLang
    private var preferredSecondaryLang: String =
        settings.settings.value.preferredSecondarySubtitleLang
    private var lastSelection: TrackSubtitleSelection? = null

    init {
        scope.launch {
            settings.settingsUpdates
                .map { it.textEncoding }
                .distinctUntilChanged()
                .collect(::onEncodingChanged)
        }
        scope.launch {
            settings.settingsUpdates
                .map { it.preferredPrimarySubtitleLang to it.preferredSecondarySubtitleLang }
                .distinctUntilChanged()
                .collect { (primary, secondary) ->
                    if (primary != preferredPrimaryLang || secondary != preferredSecondaryLang) {
                        preferredPrimaryLang = primary
                        preferredSecondaryLang = secondary
                        val active = currentTrackId
                        if (active != null && lastSelection == null) {
                            load(active, force = true, allowDeferred = false)
                        }
                    }
                }
        }
        scope.launch {
            library.trackStates.collect { states ->
                val active = currentTrackId ?: return@collect
                val next = states[active]?.subtitleSelectionOrNull()
                if (next != lastSelection) {
                    lastSelection = next
                    load(active, force = true, allowDeferred = false)
                }
            }
        }
        scope.launch {
            cues.subscriptionCount.collect { count ->
                if (count > 0) ensureLoaded()
            }
        }
    }

    fun onEncodingChanged(newEncoding: String) {
        if (encoding == newEncoding) return
        encoding = newEncoding
        attachmentCache.evictAll()
        val active = currentTrackId
        if (active != null) load(active, force = true)
    }

    fun removeCached(ids: Set<String>) {
        // Track removals may invalidate associated attachments; evict safely if any match.
        ids.forEach { attachmentCache.remove(it) }
    }

    fun ensureLoaded() {
        val pending = deferredTrackId ?: return
        if (pending == currentTrackId && job?.isActive != true) {
            deferredTrackId = null
            load(pending, force = false, allowDeferred = false)
        }
    }

    private suspend fun resolveSubtitleIds(
        track: TrackEntity,
        selection: TrackSubtitleSelection?,
    ): Pair<String?, String?> {
        if (selection != null) {
            val primary = selection.primaryId ?: track.subtitleId
            val secondary = selection.secondaryId?.takeIf { it != primary }
            return primary to secondary
        }
        if (
            preferredPrimaryLang == SubtitleLang.AUTO && preferredSecondaryLang == SubtitleLang.NONE
        ) {
            return track.subtitleId to null
        }
        val attachments =
            withContext(Dispatchers.IO) { library.dao.attachmentsForWorks(listOf(track.workId)) }
        return LibraryIndexer.pickAutoSubtitles(
            track = track,
            attachments = attachments,
            primaryLang = preferredPrimaryLang,
            secondaryLang = preferredSecondaryLang,
        )
    }

    /**
     * Silently prefetches the next queued track's primary subtitle so track transitions are
     * instant.
     */
    fun prefetch(trackId: String?) {
        if (trackId == null || trackId == currentTrackId) return
        val interactive = runCatching { powerManager?.isInteractive != false }.getOrDefault(true)
        if (!interactive) return
        prefetchJob?.cancel()
        val requestedEncoding = encoding
        prefetchJob = scope.launch {
            runCatching {
                val track = library.track(trackId) ?: return@launch
                val selection = library.loadSubtitleSelection(trackId)
                val (primaryId, _) = resolveSubtitleIds(track, selection)
                if (primaryId == null || attachmentCache[primaryId] != null) return@launch
                val parsed = fetchAndParseAttachment(primaryId, requestedEncoding) ?: return@launch
                if (requestedEncoding == encoding) {
                    attachmentCache.put(primaryId, parsed)
                }
            }
        }
    }

    fun load(id: String?, force: Boolean = false, allowDeferred: Boolean = true) {
        val selection = id?.let { library.subtitleSelectionFor(it) }
        if (
            !force &&
                id != null &&
                id == currentTrackId &&
                selection == lastSelection &&
                (job?.isActive == true || (allowDeferred && deferredTrackId == id))
        ) {
            return
        }
        job?.cancel()
        deferredTrackId = null
        currentTrackId = id
        lastSelection = selection
        cues.value = emptyList()
        displayCues.value = emptyList()
        activePrimaryId.value = null
        activeSecondaryId.value = null
        loading.value = false
        error.value = null
        if (id == null) return
        val interactive = runCatching { powerManager?.isInteractive != false }.getOrDefault(true)
        if (allowDeferred && !force && !interactive && cues.subscriptionCount.value == 0) {
            deferredTrackId = id
            return
        }
        val requestedEncoding = encoding
        val nextJob =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val track = library.track(id) ?: return@launch
                    val effectiveSelection = selection ?: library.loadSubtitleSelection(id)
                    lastSelection = effectiveSelection
                    val (primaryId, secondaryId) = resolveSubtitleIds(track, effectiveSelection)
                    if (currentTrackId == id) {
                        activePrimaryId.value = primaryId
                        activeSecondaryId.value = secondaryId
                    }
                    if (primaryId == null) return@launch
                    val cachedPrimary = if (force) null else attachmentCache[primaryId]
                    val cachedSecondary =
                        if (secondaryId == null || force) null else attachmentCache[secondaryId]
                    if (cachedPrimary != null && (secondaryId == null || cachedSecondary != null)) {
                        val merged =
                            withContext(Dispatchers.Default) {
                                SubtitleTimeline.mergeBilingual(
                                    cachedPrimary,
                                    cachedSecondary.orEmpty(),
                                )
                            }
                        ensureActive()
                        if (requestedEncoding == encoding && currentTrackId == id) {
                            cues.value = cachedPrimary
                            displayCues.value = merged
                        }
                        return@launch
                    }
                    loading.value = true
                    val primaryParsed =
                        cachedPrimary
                            ?: fetchAndParseAttachment(primaryId, requestedEncoding)
                            ?: return@launch
                    val secondaryParsed =
                        if (secondaryId != null) {
                            cachedSecondary
                                ?: runCatching {
                                    fetchAndParseAttachment(secondaryId, requestedEncoding)
                                }
                                    .getOrNull()
                        } else null
                    val merged =
                        withContext(Dispatchers.Default) {
                            SubtitleTimeline.mergeBilingual(
                                primaryParsed,
                                secondaryParsed.orEmpty(),
                            )
                        }
                    ensureActive()
                    if (requestedEncoding == encoding) {
                        attachmentCache.put(primaryId, primaryParsed)
                        if (secondaryId != null && secondaryParsed != null) {
                            attachmentCache.put(secondaryId, secondaryParsed)
                        }
                        if (currentTrackId == id) {
                            cues.value = primaryParsed
                            displayCues.value = merged
                        }
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

    /** Searches across all subtitled tracks of [detail] for cues matching [query]. */
    suspend fun searchWorkSubtitles(
        detail: WorkDetails,
        query: String,
        maxResults: Int = 120,
    ): List<WorkSubtitleMatch> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val requestedEncoding = encoding
            val results = mutableListOf<WorkSubtitleMatch>()
            for (track in detail.tracks) {
                currentCoroutineContext().ensureActive()
                val selection = library.loadSubtitleSelection(track.id)
                val (primaryId, secondaryId) = resolveSubtitleIds(track, selection)
                val ids = listOfNotNull(primaryId, secondaryId).distinct()
                if (ids.isEmpty()) continue
                val seenStart = HashSet<Long>()
                for (subId in ids) {
                    val parsed =
                        attachmentCache[subId]
                            ?: runCatching { fetchAndParseAttachment(subId, requestedEncoding) }
                                .getOrNull()
                                ?.also {
                                    if (requestedEncoding == encoding)
                                        attachmentCache.put(subId, it)
                                }
                            ?: continue
                    for (cue in parsed) {
                        if (cue.text.contains(q, ignoreCase = true) && seenStart.add(cue.startMs)) {
                            results += WorkSubtitleMatch(track, cue)
                            if (results.size >= maxResults) return@withContext results
                        }
                    }
                }
            }
            results
        }

    private suspend fun fetchAndParseAttachment(
        attachmentId: String,
        requestedEncoding: String,
    ): List<Cue>? {
        val attachment = library.dao.attachment(attachmentId) ?: return null
        val bytes = library.readAsset(attachment.id)
        return withContext(Dispatchers.Default) {
            SubtitleTimeline.parse(
                TextEncoding.decode(bytes, requestedEncoding),
                attachment.fileName.substringAfterLast('.'),
            )
        }
    }

    fun retry(id: String? = currentTrackId) {
        if (id != null && id == currentTrackId && error.value != null && job?.isActive != true) {
            load(id, force = true, allowDeferred = false)
        }
    }
}
