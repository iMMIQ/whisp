// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@androidx.annotation.OptIn(UnstableApi::class)
class AppViewModel(
    val repository: LibraryRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    val library = repository.library
    val sources = repository.sources
    val progress = repository.progress
    val bookmarks = repository.bookmarks
    val settings = repository.settings
    val downloads = repository.downloads
    val busy = repository.busy
    val asmrQuery = MutableStateFlow(decodeAsmrQuery(savedState["asmr-query"]))
    val asmrHistory = repository.asmrSearchHistory
    val asmrFacets = MutableStateFlow<List<AsmrFacet>>(emptyList())
    val asmrPreview = MutableStateFlow(AsmrPreviewState())
    private var previewJob: Job? = null

    fun submitAsmrQuery(query: AsmrQuery) {
        asmrQuery.value = query.copy(text = normalizedAsmrInput(query.text))
        action { repository.rememberAsmrSearch(query.text) }
    }

    fun prepareAsmrPreview(
        sourceId: String,
        seed: AsmrWork,
        metadataClient: DlsiteClient = repository.dlsite,
        retry: Boolean = false,
    ) {
        val current = asmrPreview.value
        if (!retry && current.sourceId == sourceId && current.seed?.id == seed.id) return
        previewJob?.cancel()
        asmrPreview.value = AsmrPreviewState(sourceId, seed, loading = true)
        previewJob = viewModelScope.launch {
            try {
                val preview = repository.previewAsmrWork(sourceId, seed.id, metadataClient)
                asmrPreview.value = AsmrPreviewState(sourceId, seed, preview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                asmrPreview.value = AsmrPreviewState(sourceId, seed, error = e.message ?: "作品读取失败")
            }
        }
    }

    fun restoreAsmrPreview(sourceId: String, id: Long) {
        previewJob?.cancel()
        asmrPreview.value = AsmrPreviewState(sourceId, loading = true)
        previewJob = viewModelScope.launch {
            try {
                val seed = repository.asmrWork(sourceId, id)
                asmrPreview.value = AsmrPreviewState(sourceId, seed, loading = true)
                val preview = repository.previewAsmrWork(sourceId, id)
                asmrPreview.value = AsmrPreviewState(sourceId, seed, preview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                asmrPreview.value =
                    asmrPreview.value.copy(loading = false, error = e.message ?: "作品读取失败")
            }
        }
    }

    suspend fun saveAsmrPreview(preview: AsmrPreview): WorkDetails {
        repository.addAsmrPreview(
            preview.copy(
                metadata = dlsiteStates.value[preview.work.workId]?.details ?: preview.metadata,
            ),
        )
        return repository.workDetails(preview.work.workId) ?: error("作品保存失败")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val asmrCatalog =
        asmrQuery
            .filterNotNull()
            .distinctUntilChanged()
            .flatMapLatest { query ->
                Pager(
                        PagingConfig(
                            pageSize = 20,
                            initialLoadSize = 20,
                            prefetchDistance = 4,
                            maxSize = 100,
                            enablePlaceholders = false,
                        ),
                    ) {
                        AsmrPagingSource { page ->
                            repository.asmrPage(query, page).also { result ->
                                asmrFacets.update { previous ->
                                    (result.works.flatMap { it.facets } + previous)
                                        .distinct()
                                        .take(256)
                                }
                            }
                        }
                    }
                    .flow
            }
            .cachedIn(viewModelScope)
    private val dlsiteLoader = DlsiteDetailsLoader(viewModelScope, repository)
    val dlsiteStates: StateFlow<Map<String, DlsiteUiState>>
        get() = dlsiteLoader.states

    fun acceptDlsiteDetails(id: String, details: DlsiteDetails) = dlsiteLoader.accept(id, details)

    fun loadDlsiteDetails(
        workId: String,
        rj: String,
        part: String = "product",
        seed: DlsiteDetails? = null,
        force: Boolean = false,
        client: DlsiteClient = repository.dlsite,
    ) = dlsiteLoader.load(workId, rj, part, seed, force, client)

    // Event snapshots never advance merely because playback time passes.
    val player = MutableStateFlow(PlaybackState())
    val position = MutableStateFlow(0L)
    val cues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleLoading = MutableStateFlow(false)
    val subtitleError = MutableStateFlow<String?>(null)
    val queueTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val openingTrackId = MutableStateFlow<String?>(null)
    val loopDraftStart = MutableStateFlow(-1L)
    val videoPlayer = MutableStateFlow<Player?>(null)
    val audioOnly = savedState.getStateFlow("video-audio-only", false)
    val embeddedSubtitle = MutableStateFlow("")
    private var videoVisible = false
    private var videoOwner: Any? = null
    private var loopDraftTrack: String? = null
    private var subtitleTrackId: String? = null
    private val subtitleCache = LruCache<String, List<Cue>>(16)
    val error = MutableStateFlow<String?>(null)
    private var subtitleJob: Job? = null
    private var queueJob: Job? = null
    private var queueItems = emptyList<MediaItem>()
    private var openingJob: Job? = null
    private var openingWorkId: String? = null
    private var openingGeneration = 0
    private var openingPlayWhenReady = true
    private var controller: MediaController? = null
    private var closed = false
    private val future =
        MediaController.Builder(
                repository.context,
                SessionToken(
                    repository.context,
                    ComponentName(repository.context, PlaybackService::class.java),
                ),
            )
            .setListener(
                object : MediaController.Listener {
                    override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                        updatePlayer()
                    }
                },
            )
            .buildAsync()

    init {
        viewModelScope.launch {
            asmrQuery.collect { savedState["asmr-query"] = it?.let(::encodeAsmrQuery) }
        }
        future.addListener(
            {
                if (!closed)
                    runCatching {
                        controller =
                            future.get().also { c ->
                                c.addListener(
                                    object : Player.Listener {
                                        override fun onEvents(
                                            player: Player,
                                            events: Player.Events,
                                        ) {
                                            updatePlayer()
                                        }
                                    },
                                )
                            }
                        videoPlayer.value = controller
                        publishVideoVisibility()
                        updatePlayer()
                    }
                        .onFailure { error.value = "播放服务连接失败" }
            },
            ContextCompat.getMainExecutor(repository.context),
        )
        viewModelScope.launch { repository.messages.collect { error.value = it } }
    }

    internal fun refreshPlayer() = updatePlayer()

    fun setAudioOnly(value: Boolean) {
        savedState["video-audio-only"] = value
        if (value) setVideoVisible(false)
    }

    internal fun videoVisibility(owner: Any, visible: Boolean) {
        if (visible) {
            videoOwner = owner
            setVideoVisible(true)
        } else if (videoOwner === owner) {
            videoOwner = null
            setVideoVisible(false)
        }
    }

    private fun setVideoVisible(value: Boolean) {
        if (!value) videoOwner = null
        if (videoVisible == value) return
        videoVisible = value
        publishVideoVisibility()
    }

    private fun publishVideoVisibility() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.VIDEO, Bundle.EMPTY),
            Bundle().apply { putBoolean("visible", videoVisible) },
        )
    }

    // Called only inside the resumed player page's lifecycle. Events still update
    // pause/seek/buffering immediately, without keeping an idle UI clock running.
    internal suspend fun observeTimeline() {
        updatePlayer()
        player
            .map { it.playing }
            .distinctUntilChanged()
            .collectLatest { playing ->
                if (playing)
                    while (currentCoroutineContext().isActive) {
                        if (openingTrackId.value == null) position.value = currentPosition()
                        delay(200)
                    }
            }
    }

    fun currentPosition(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: position.value

    private fun updatePlayer() {
        val c = controller ?: return
        if (openingTrackId.value != null) return
        val extras = c.sessionExtras
        val id = c.currentMediaItem?.mediaId
        val size = c.videoSize
        val hasVideo =
            c.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } ||
                (id != null &&
                    player.value.trackId == id &&
                    player.value.hasVideo &&
                    c.mediaMetadata.mediaType ==
                        androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO)
        val previousAspect =
            player.value.videoAspectRatio.takeIf { player.value.trackId == id } ?: 16f / 9f
        val aspect =
            if (size.width > 0 && size.height > 0)
                (size.width * size.pixelWidthHeightRatio / size.height).takeIf {
                    it.isFinite() && it > 0
                } ?: previousAspect
            else previousAspect
        embeddedSubtitle.value =
            c.currentCues.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        position.value = c.currentPosition.coerceAtLeast(0)
        player.value =
            PlaybackState(
                id,
                c.isPlaying,
                c.currentPosition.coerceAtLeast(0),
                c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
                c.playbackState == Player.STATE_BUFFERING,
                extras.getLong("timerEnd", 0),
                extras.getLong("loopStart", -1),
                extras.getLong("loopEnd", -1),
                c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playerError == null,
                c.hasPreviousMediaItem(),
                c.hasNextMediaItem(),
                c.playbackState == Player.STATE_ENDED,
                c.playerError != null,
                updateQueue(c),
                hasVideo,
                aspect,
                !c.currentTracks.isEmpty,
            )
        if (loopDraftTrack != id) loopDraftStart.value = -1
        if (id != null && id != subtitleTrackId) loadSubtitles(id)
    }

    private fun updateQueue(c: MediaController): List<String> {
        val timeline = c.currentTimeline
        val window = Timeline.Window()
        val items = buildList {
            var index = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
            while (index != C.INDEX_UNSET && size < timeline.windowCount) {
                add(timeline.getWindow(index, window).mediaItem)
                index =
                    timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        val known = queueTracks.value.associateBy { it.id }
        val snapshots = items.mapIndexed { index, item ->
            val track =
                known[item.mediaId]
                    ?: TrackEntity(
                        item.mediaId,
                        item.mediaMetadata.extras?.getString("workId").orEmpty(),
                        item.mediaMetadata.title?.toString().orEmpty().ifBlank {
                            "音轨 ${index + 1}"
                        },
                        "",
                        "",
                        index + 1,
                    )
            if (item.mediaId == c.currentMediaItem?.mediaId && c.duration > 0)
                track.copy(durationMs = c.duration)
            else track
        }
        queueTracks.value = snapshots
        if (queueItems != items) {
            queueItems = items
            queueJob?.cancel()
            queueJob =
                if (items.isEmpty()) null
                else
                    viewModelScope.launch {
                        try {
                            val indexed = snapshots.map { repository.track(it.id) ?: it }
                            ensureActive()
                            if (queueItems == items) {
                                val latest = queueTracks.value.associateBy { it.id }
                                queueTracks.value = indexed.map { track ->
                                    track.copy(
                                        durationMs =
                                            latest[track.id]?.durationMs?.takeIf { it > 0 }
                                                ?: track.durationMs,
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // MediaItem snapshots keep the active queue readable during a
                            // transient database failure.
                        }
                    }
        }
        return items.map { it.mediaId }
    }

    private fun resume(c: MediaController) {
        retrySubtitles(c.currentMediaItem?.mediaId)
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "操作失败"
            }
        }
    }

    fun playWork(
        detail: WorkDetails,
        trackId: String? = null,
        shuffle: Boolean = false,
        startPosition: Long? = null,
        restart: Boolean = false,
    ) {
        val generation = ++openingGeneration
        openingJob?.cancel()
        openingWorkId = detail.work.id
        openingPlayWhenReady = true
        openingJob = viewModelScope.launch {
            try {
                repository.flushPlaybackProgress()
                val checkpoint = repository.progress(detail.work.id)
                val activeId =
                    controller?.currentMediaItem?.mediaId?.takeIf { id ->
                        detail.tracks.any { it.id == id }
                    }
                val tracks =
                    repository.availableTracks(
                        TrackSections.queue(
                            detail.tracks,
                            if (restart) trackId else trackId ?: activeId ?: checkpoint?.trackId,
                        ),
                    )
                require(trackId == null || tracks.any { it.id == trackId }) {
                    "音轨文件不可用，请检查来源或离线保存"
                }
                require(tracks.isNotEmpty()) { "作品没有音轨" }
                val recent = progress.value.firstOrNull { it.workId == detail.work.id }
                val provisional =
                    tracks.firstOrNull { it.id == trackId }
                        ?: tracks.firstOrNull { it.id == controller?.currentMediaItem?.mediaId }
                        ?: tracks.firstOrNull { it.id == recent?.trackId }
                        ?: tracks.first()
                openingTrackId.value = provisional.id
                player.value =
                    player.value.copy(
                        trackId = provisional.id,
                        playing = false,
                        positionMs =
                            startPosition
                                ?: if (provisional.id == controller?.currentMediaItem?.mediaId)
                                    currentPosition()
                                else 0,
                        durationMs = provisional.durationMs,
                        buffering = true,
                        playWhenReady = openingPlayWhenReady,
                    )
                position.value = player.value.positionMs
                if (subtitleTrackId != provisional.id) loadSubtitles(provisional.id)
                else retrySubtitles(provisional.id)
                val c =
                    controller
                        ?: withContext(Dispatchers.IO) { future.get() }.also { controller = it }
                // Continue the active work from the live player, never its older database
                // checkpoint.
                val currentIndex = tracks.indexOfFirst { it.id == c.currentMediaItem?.mediaId }
                val continuesCurrent =
                    !restart &&
                        startPosition == null &&
                        !shuffle &&
                        currentIndex >= 0 &&
                        (trackId == null || trackId == tracks[currentIndex].id)
                val sameQueue =
                    c.mediaItemCount == tracks.size &&
                        tracks.indices.all { c.getMediaItemAt(it).mediaId == tracks[it].id }
                if (continuesCurrent && sameQueue) {
                    openingTrackId.value = null
                    c.shuffleModeEnabled = false
                    c.repeatMode = Player.REPEAT_MODE_OFF
                    if (openingPlayWhenReady) resume(c) else c.pause()
                    updatePlayer()
                    return@launch
                }
                val point =
                    if (continuesCurrent && c.playbackState != Player.STATE_ENDED)
                        QueuePosition(currentIndex, c.currentPosition.coerceAtLeast(0))
                    else if (restart) QueuePosition(0, 0)
                    else if (trackId != null)
                        QueuePosition(tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0), 0)
                    else PlaybackQueue.resume(tracks, checkpoint)
                c.pause()
                val position = startPosition?.coerceAtLeast(0) ?: point.positionMs
                val selected = tracks[point.index]
                openingTrackId.value = selected.id
                player.value =
                    player.value.copy(
                        trackId = selected.id,
                        positionMs = position,
                        durationMs = selected.durationMs,
                        playing = false,
                        buffering = true,
                        playWhenReady = openingPlayWhenReady,
                    )
                this@AppViewModel.position.value = position
                loadSubtitles(selected.id)
                if (sameQueue) c.seekTo(point.index, position)
                else
                    c.setMediaItems(
                        tracks.map { repository.mediaItem(it, detail.work) },
                        point.index,
                        position,
                    )
                c.shuffleModeEnabled = shuffle
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.prepare()
                if (openingPlayWhenReady) c.play() else c.pause()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: "无法打开作品"
            } finally {
                if (generation == openingGeneration) {
                    openingWorkId = null
                    openingTrackId.value = null
                    updatePlayer()
                }
            }
        }
    }

    fun playBookmark(detail: WorkDetails, saved: BookmarkEntity, repeat: Boolean = false) {
        playWork(detail, saved.trackId, startPosition = saved.positionMs)
        val job = openingJob
        val generation = openingGeneration
        viewModelScope.launch {
            job?.join()
            if (repeat && generation == openingGeneration && player.value.trackId == saved.trackId)
                saved.endMs
                    ?.takeIf { it - saved.positionMs >= 500 }
                    ?.let { loop(saved.positionMs, it) }
        }
    }

    fun togglePlay() {
        if (openingJob?.isActive == true) {
            openingPlayWhenReady = !openingPlayWhenReady
            if (!openingPlayWhenReady) controller?.pause()
            player.value = player.value.copy(playWhenReady = openingPlayWhenReady, playing = false)
            return
        }
        controller?.let { c ->
            if (c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playerError == null)
                c.pause()
            else if (c.mediaItemCount > 0) resume(c)
            else
                action {
                    repository.latestDetails()?.let { playWork(it) }
                }
            updatePlayer()
        }
    }

    fun seek(position: Long) {
        controller?.let { c ->
            val duration = c.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            val target =
                if (duration != null) position.coerceIn(0, duration) else position.coerceAtLeast(0)
            val state = player.value
            if (state.loopStart >= 0 && target !in state.loopStart..state.loopEnd) loop()
            c.seekTo(target)
            updatePlayer()
        }
    }

    fun skip(delta: Long) {
        seek((controller?.currentPosition ?: player.value.positionMs) + delta)
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
        updatePlayer()
    }

    fun previous() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
        updatePlayer()
    }

    fun selectQueuedTrack(id: String) {
        val c = controller ?: return
        val index = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id }
        if (index == null) {
            error.value = "播放队列已更新"
            return
        }
        if (index != c.currentMediaItemIndex) c.seekTo(index, 0)
        resume(c)
        updatePlayer()
    }

    fun timer(minutes: Int) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.TIMER, Bundle.EMPTY),
            Bundle().apply { putInt("minutes", minutes) },
        )
    }

    fun loop(start: Long = -1, end: Long = -1) {
        player.value = player.value.copy(loopStart = start, loopEnd = end)
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
            Bundle().apply {
                putLong("start", start)
                putLong("end", end)
            },
        )
    }

    fun favorite(id: String) = action { repository.toggleFavorite(id) }

    fun deleteBookmark(id: String) = action { repository.deleteBookmark(id) }

    fun exportPlaybackDiagnostics() {
        val context = repository.context
        context.startActivity(
            Intent.createChooser(repository.playbackDiagnostics.shareIntent(), "播放诊断")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun download(id: String) = action { repository.offlineStore.downloadWork(id) }

    private suspend fun cancelOpening(workIds: Set<String>) {
        if (openingWorkId in workIds) {
            ++openingGeneration
            openingJob?.cancelAndJoin()
            openingWorkId = null
            openingTrackId.value = null
        }
    }

    private fun detachTracks(ids: Set<String>) {
        controller?.let { c ->
            if (c.currentMediaItem?.mediaId in ids) {
                c.pause()
                c.stop()
                loadSubtitles(null)
            }
            for (index in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(index).mediaId in ids)
                c.removeMediaItem(index)
        }
        ids.forEach { subtitleCache.remove(it) }
    }

    fun removeSource(sourceId: String) = action {
        if (repository.source(sourceId)?.kindType == SourceKind.DLSITE) {
            cancelOpening(repository.sourceWorkIds(sourceId).toSet())
            val trackIds = repository.sourceWorkTrackIds(sourceId)
            detachTracks(trackIds)
        }
        repository.removeSource(sourceId)
        updatePlayer()
    }

    suspend fun removeAsmrWork(detail: WorkDetails) {
        cancelOpening(setOf(detail.work.id))
        detachTracks(detail.tracks.map { it.id }.toSet())
        repository.removeAsmrWork(detail.work.id)
        updatePlayer()
    }

    fun markLoopStart() {
        loopDraftTrack = player.value.trackId
        loopDraftStart.value = currentPosition()
        loop()
    }

    fun markLoopEnd(): Boolean {
        val start = loopDraftStart.value
        val end = currentPosition()
        if (start < 0 || end - start < 500) {
            error.value = "循环终点需晚于起点"
            return false
        }
        loop(start, end)
        loopDraftStart.value = -1
        return true
    }

    fun clearLoop() {
        loop()
        loopDraftStart.value = -1
    }

    fun saveBookmark(
        title: String,
        endMs: Long? = null,
        positionMs: Long = currentPosition(),
        trackId: String? = player.value.trackId,
    ) = action {
        val track = repository.track(trackId ?: return@action) ?: return@action
        repository.saveBookmark(
            BookmarkEntity(
                UUID.randomUUID().toString(),
                track.workId,
                track.id,
                positionMs.coerceAtLeast(0),
                title.ifBlank { track.title },
                endMs,
            ),
        )
    }

    fun loadSubtitles(id: String?) {
        if (id != null && id == subtitleTrackId && subtitleJob?.isActive == true) return
        subtitleJob?.cancel()
        subtitleTrackId = id
        cues.value = id?.let { subtitleCache[it] }.orEmpty()
        subtitleLoading.value = false
        subtitleError.value = null
        if (id == null || subtitleCache[id] != null) return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val track = repository.track(id) ?: return@launch
                    val attachmentId = track.subtitleId ?: return@launch
                    subtitleLoading.value = true
                    val detail = repository.workDetails(track.workId) ?: return@launch
                    val attachment =
                        detail.attachments.firstOrNull { it.id == attachmentId } ?: return@launch
                    val bytes = repository.readAsset(attachment.id)
                    val parsed =
                        withContext(Dispatchers.Default) {
                            SubtitleTimeline.parse(
                                bytes.toString(Charsets.UTF_8),
                                attachment.fileName.substringAfterLast('.'),
                            )
                        }
                    ensureActive()
                    subtitleCache.put(id, parsed)
                    if (subtitleTrackId == id) cues.value = parsed
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (subtitleTrackId == id && subtitleJob === currentCoroutineContext()[Job]) {
                        subtitleError.value = "字幕暂时不可用"
                    }
                } finally {
                    if (subtitleTrackId == id && subtitleJob === currentCoroutineContext()[Job])
                        subtitleLoading.value = false
                }
            }
        subtitleJob = job
        job.start()
    }

    fun retrySubtitles(id: String? = subtitleTrackId) {
        if (
            id != null &&
                id == subtitleTrackId &&
                subtitleError.value != null &&
                subtitleJob?.isActive != true
        )
            loadSubtitles(id)
    }

    override fun onCleared() {
        closed = true
        setVideoVisible(false)
        videoPlayer.value = null
        MediaController.releaseFuture(future)
    }
}
