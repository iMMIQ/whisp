// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.whisp.data.*
import app.whisp.ui.PlaybackState
import app.whisp.ui.SubtitleLoader
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Single-source-of-truth in-process playback coordinator. Directly observes and controls the active
 * ExoPlayer inside [PlaybackService] instead of duplicating state over MediaController IPC.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val images: WhispImages,
    private val settings: SettingsStore,
    private val messages: MutableSharedFlow<String>,
) {
    val state = MutableStateFlow(PlaybackState())
    val positionMs = MutableStateFlow(0L)
    val bufferedMs = MutableStateFlow(0L)
    val queueTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val openingTrackId = MutableStateFlow<String?>(null)
    val embeddedSubtitle = MutableStateFlow("")
    val playerFlow = MutableStateFlow<Player?>(null)
    val loopDraftStart = MutableStateFlow(-1L)
    val lastClearedLoop = MutableStateFlow<LoopState?>(null)
    val audioOnly = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val subtitles = SubtitleLoader(context, scope, library, settings)

    private var activePlayer: ExoPlayer? = null
    private var activeLoop: AbLoopController? = null
    private var activeTimer: SleepTimerController? = null
    private var videoToggle: ((Boolean) -> Unit)? = null
    private var textToggle: ((Boolean) -> Unit)? = null
    private val videoOwners = mutableSetOf<Any>()
    private val subtitleOwners = mutableSetOf<Any>()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var openJob: Job? = null
    private var pendingStartPlaying = true
    private var queueLoadJob: Job? = null
    private val queueSnapshot = mutableMapOf<String, TrackEntity>()

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refreshFromPlayer(player)
            }

            override fun onCues(cueGroup: CueGroup) {
                embeddedSubtitle.value =
                    cueGroup.cues
                        .mapNotNull { it.text?.toString()?.trim()?.ifEmpty { null } }
                        .joinToString("\n")
            }
        }

    internal fun attach(
        player: ExoPlayer,
        loop: AbLoopController,
        timer: SleepTimerController,
        setVideoEnabled: (Boolean) -> Unit,
        setTextEnabled: (Boolean) -> Unit = {},
    ) {
        activePlayer?.removeListener(playerListener)
        activePlayer = player
        activeLoop = loop
        activeTimer = timer
        videoToggle = setVideoEnabled
        textToggle = setTextEnabled
        player.addListener(playerListener)
        playerFlow.value = player
        setVideoEnabled(videoOwners.isNotEmpty() && !audioOnly.value)
        setTextEnabled(subtitleOwners.isNotEmpty())
        refreshFromPlayer(player)
    }

    internal fun detach(player: ExoPlayer) {
        if (activePlayer !== player) return
        player.removeListener(playerListener)
        activePlayer = null
        activeLoop = null
        activeTimer = null
        videoToggle = null
        textToggle = null
        playerFlow.value = null
        state.value = PlaybackState()
        positionMs.value = 0L
        bufferedMs.value = 0L
        queueTracks.value = emptyList()
        embeddedSubtitle.value = ""
        loopDraftStart.value = -1L
        lastClearedLoop.value = null
        subtitles.load(null)
    }

    internal fun onControllerStateChanged() {
        activePlayer?.let(::refreshFromPlayer)
    }

    fun refreshPlayer() {
        activePlayer?.let(::refreshFromPlayer)
        subtitles.ensureLoaded()
    }

    fun currentPosition(): Long =
        activePlayer?.currentPosition?.coerceAtLeast(0L) ?: positionMs.value

    /** Connects to PlaybackService on demand so MediaLibraryService stays started & bound. */
    private suspend fun ensurePlayer(): ExoPlayer {
        activePlayer?.let {
            return it
        }
        val future =
            controllerFuture
                ?: MediaController.Builder(
                        context,
                        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
                    )
                    .buildAsync()
                    .also { controllerFuture = it }
        if (!future.isDone) {
            suspendCancellableCoroutine<Unit> { continuation ->
                future.addListener(
                    {
                        try {
                            future.get()
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (e: Exception) {
                            if (controllerFuture === future) controllerFuture = null
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    },
                    { runnable -> scope.launch { runnable.run() } },
                )
            }
        }
        return activePlayer ?: playerFlow.filterNotNull().first() as ExoPlayer
    }

    /**
     * Ticks live playback position ONLY while collected by a visible foreground PlayerPage AND the
     * player is actively playing or buffering. Suspends with zero wakeups while paused.
     */
    suspend fun observeTimeline() {
        combine(
                state.map { it.playing || it.buffering }.distinctUntilChanged(),
                subtitles.cues.map { it.isNotEmpty() }.distinctUntilChanged(),
            ) { active, hasSubtitles ->
                active to hasSubtitles
            }
            .distinctUntilChanged()
            .collectLatest { (active, hasSubtitles) ->
                syncTimelineOnce()
                if (!active) return@collectLatest
                val intervalMs = if (hasSubtitles) 150L else 350L
                while (currentCoroutineContext().isActive) {
                    delay(intervalMs)
                    syncTimelineOnce()
                }
            }
    }

    private fun syncTimelineOnce() {
        val player = activePlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        if (positionMs.value != pos) positionMs.value = pos
        val buf = player.bufferedPosition.coerceAtLeast(0L)
        if (bufferedMs.value != buf) bufferedMs.value = buf
    }

    fun playWork(
        detail: WorkDetails,
        trackId: String? = null,
        shuffle: Boolean = false,
        restart: Boolean = false,
        startPosition: Long? = null,
    ) {
        error.value = null
        openJob?.cancel()
        pendingStartPlaying = true
        val initialRequested = trackId ?: detail.tracks.firstOrNull()?.id
        openingTrackId.value = initialRequested
        openJob = scope.launch {
            try {
                val savedProgress =
                    if (!restart && !shuffle && trackId == null && startPosition == null)
                        library.progress(detail.work.id)
                    else null
                val rawQueue = TrackSections.queue(detail.tracks, trackId ?: savedProgress?.trackId)
                val available = library.availableTracks(detail.work, rawQueue)
                if (trackId != null && available.none { it.id == trackId }) {
                    reportError("音轨文件不可用，请检查来源或重新下载")
                    return@launch
                }
                if (available.isEmpty()) {
                    reportError("音轨文件不可用，请检查来源或重新下载")
                    return@launch
                }
                val ordered = if (shuffle) available.shuffled() else available
                val resume =
                    when {
                        shuffle -> QueuePosition(0, 0L)
                        restart -> QueuePosition(0, 0L)
                        trackId != null ->
                            QueuePosition(
                                ordered.indexOfFirst { it.id == trackId }.coerceAtLeast(0),
                                startPosition?.coerceAtLeast(0L) ?: 0L,
                            )
                        else -> {
                            val pos = PlaybackQueue.resume(ordered, savedProgress)
                            QueuePosition(
                                pos.index,
                                startPosition?.coerceAtLeast(0L) ?: pos.positionMs,
                            )
                        }
                    }
                val selectedTrack = ordered[resume.index]
                openingTrackId.value = selectedTrack.id
                val player = ensurePlayer()
                val currentId = player.currentMediaItem?.mediaId
                val currentQueueIds =
                    (0 until player.mediaItemCount).map {
                        player.getMediaItemAt(it).mediaId
                    }
                val targetIds = ordered.map { it.id }
                val continuingSame =
                    !restart && !shuffle && startPosition == null && currentId == selectedTrack.id
                if (continuingSame) subtitles.retry(selectedTrack.id)
                if (continuingSame && currentQueueIds == targetIds) {
                    ordered.forEach { queueSnapshot[it.id] = it }
                    player.shuffleModeEnabled = false
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(resume.index, 0)
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (pendingStartPlaying) player.play() else player.pause()
                    refreshFromPlayer(player)
                    return@launch
                }
                val resumePosition =
                    if (continuingSame) player.currentPosition.coerceAtLeast(0L)
                    else resume.positionMs
                val items = withContext(Dispatchers.IO) { mediaItems(ordered, detail.work) }
                ordered.forEach { queueSnapshot[it.id] = it }
                player.shuffleModeEnabled = shuffle
                player.setMediaItems(items, resume.index, resumePosition)
                player.prepare()
                if (pendingStartPlaying) player.play() else player.pause()
                refreshFromPlayer(player)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(e.message ?: "无法播放该作品")
            } finally {
                if (openJob === currentCoroutineContext()[Job]) {
                    openingTrackId.value = null
                }
            }
        }
    }

    fun playBookmark(detail: WorkDetails, bookmark: BookmarkEntity, repeat: Boolean = false) {
        playWork(detail, bookmark.trackId, startPosition = bookmark.positionMs)
        if (repeat && bookmark.endMs != null && bookmark.endMs > bookmark.positionMs) {
            scope.launch {
                openJob?.join()
                if (state.value.trackId == bookmark.trackId) {
                    setLoop(bookmark.positionMs, bookmark.endMs)
                }
            }
        }
    }

    fun togglePlay() {
        if (openJob?.isActive == true) {
            pendingStartPlaying = !pendingStartPlaying
            state.update { it.copy(playWhenReady = pendingStartPlaying) }
            return
        }
        val player = activePlayer ?: return
        if (player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
            player.play()
        }
    }

    fun seek(position: Long) {
        val player = activePlayer ?: return
        val duration = player.duration.takeIf { it > 0 }
        val clamped =
            if (duration != null) position.coerceIn(0L, duration) else position.coerceAtLeast(0L)
        positionMs.value = clamped
        player.seekTo(clamped)
    }

    fun skip(deltaMs: Long) {
        val player = activePlayer ?: return
        seek(player.currentPosition + deltaMs)
    }

    fun next() {
        activePlayer?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previous() {
        activePlayer?.let {
            if (it.currentPosition > 3000L || !it.hasPreviousMediaItem()) it.seekTo(0L)
            else it.seekToPreviousMediaItem()
        }
    }

    fun selectQueuedTrack(trackId: String) {
        val player = activePlayer ?: return
        val index =
            (0 until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).mediaId == trackId
            } ?: return
        player.seekTo(index, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        activePlayer?.setPlaybackSpeed(clamped)
        scope.launch { settings.update { it.copy(playbackSpeed = clamped) } }
    }

    fun cycleRepeatMode() {
        val player = activePlayer ?: return
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
    }

    fun setTimer(minutes: Int) {
        activeTimer?.setMinutes(minutes)
    }

    fun extendTimer(deltaMinutes: Int) {
        activeTimer?.extendMinutes(deltaMinutes)
    }

    fun setStopAfterTrack(enabled: Boolean) {
        activeTimer?.setStopAfterTrack(enabled)
    }

    fun setLoop(start: Long, end: Long) {
        loopDraftStart.value = -1L
        activeLoop?.set(start, end)
    }

    fun setLoopDraftStart(positionMs: Long) {
        val player = activePlayer ?: return
        if (player.currentMediaItem == null) return
        val duration = player.duration.takeIf { it > 0 }
        val clamped =
            if (duration != null) positionMs.coerceIn(0L, duration)
            else positionMs.coerceAtLeast(0L)
        activeLoop?.clear()
        loopDraftStart.value = clamped
    }

    fun adjustLoop(deltaStartMs: Long = 0L, deltaEndMs: Long = 0L) {
        val loop = activeLoop ?: return
        if (loop.active) {
            val nextStart = (loop.startMs + deltaStartMs).coerceAtLeast(0L)
            val nextEnd = (loop.endMs + deltaEndMs).coerceAtLeast(nextStart + 500L)
            loop.set(nextStart, nextEnd)
        } else if (loopDraftStart.value >= 0L && deltaStartMs != 0L) {
            loopDraftStart.value = (loopDraftStart.value + deltaStartMs).coerceAtLeast(0L)
        }
    }

    fun restoreLastClearedLoop() {
        val prev = activeLoop?.lastClearedBySeek ?: return
        val player = activePlayer ?: return
        activeLoop?.set(prev.startMs, prev.endMs)
        player.seekTo(prev.startMs)
    }

    fun dismissClearedLoop() {
        activeLoop?.dismissClearedHistory()
        lastClearedLoop.value = null
    }

    fun markLoopStart() {
        val player = activePlayer ?: return
        if (player.currentMediaItem == null) return
        loopDraftStart.value = player.currentPosition.coerceAtLeast(0L)
    }

    fun markLoopEnd(): Boolean {
        val player = activePlayer ?: return false
        val start = loopDraftStart.value
        val end = player.currentPosition.coerceAtLeast(0L)
        if (start < 0L || end - start < 500L) {
            reportError("循环区间需至少 0.5 秒")
            return false
        }
        loopDraftStart.value = -1L
        activeLoop?.set(start, end)
        return true
    }

    fun clearLoop() {
        loopDraftStart.value = -1L
        activeLoop?.clear()
    }

    fun videoVisibility(owner: Any, visible: Boolean) {
        if (visible) videoOwners += owner else videoOwners -= owner
        videoToggle?.invoke(videoOwners.isNotEmpty() && !audioOnly.value)
    }

    fun subtitleVisibility(owner: Any, visible: Boolean) {
        if (visible) subtitleOwners += owner else subtitleOwners -= owner
        textToggle?.invoke(subtitleOwners.isNotEmpty())
        if (visible) subtitles.ensureLoaded()
    }

    fun setAudioOnly(enabled: Boolean) {
        audioOnly.value = enabled
        videoToggle?.invoke(videoOwners.isNotEmpty() && !enabled)
    }

    fun detachTracks(ids: Set<String>) {
        if (ids.isEmpty()) return
        subtitles.removeCached(ids)
        ids.forEach(queueSnapshot::remove)
        val player = activePlayer ?: return
        val currentId = player.currentMediaItem?.mediaId
        if (currentId != null && currentId in ids) {
            openJob?.cancel()
            openingTrackId.value = null
            player.pause()
            player.clearMediaItems()
            refreshFromPlayer(player)
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId in ids) player.removeMediaItem(i)
        }
        refreshFromPlayer(player)
    }

    fun mediaMetadata(
        track: TrackEntity,
        work: WorkEntity,
        hide: Boolean,
        artworkBytes: ByteArray? = if (hide) null else images.thumbnailBytes(work.coverId),
    ): MediaMetadata {
        val extras = Bundle().apply { putString("workId", work.id) }
        val builder =
            MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
        if (hide) {
            builder.setTitle("正在收听")
        } else {
            builder.setTitle(track.title)
            if (work.artist.isNotBlank()) builder.setArtist(work.artist)
            builder.setAlbumTitle(work.title)
            artworkBytes?.let {
                builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
        return builder.build()
    }

    suspend fun mediaItem(track: TrackEntity, work: WorkEntity): MediaItem {
        val hide = settings.settingsUpdates.first().hideArtwork
        val artwork = if (hide) null else images.thumbnailBytes(work.coverId)
        return buildMediaItem(track, work, hide, artwork)
    }

    /** Builds a queue sharing a single hideArtwork preference lookup & cover thumbnail encode. */
    suspend fun mediaItems(tracks: List<TrackEntity>, work: WorkEntity): List<MediaItem> {
        if (tracks.isEmpty()) return emptyList()
        val hide = settings.settingsUpdates.first().hideArtwork
        val artwork = if (hide) null else images.thumbnailBytes(work.coverId)
        return tracks.map { buildMediaItem(it, work, hide, artwork) }
    }

    private suspend fun buildMediaItem(
        track: TrackEntity,
        work: WorkEntity,
        hide: Boolean,
        artwork: ByteArray?,
    ): MediaItem {
        val uri = library.resolveTrackUri(track, work)
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri.toUri())
            .setMimeType(MediaFormats.videoMimeType(track.fileName))
            .setCustomCacheKey(track.id)
            .setMediaMetadata(mediaMetadata(track, work, hide, artwork))
            .build()
    }

    private fun reportError(message: String) {
        error.value = message
        messages.tryEmit(message)
    }

    private fun refreshFromPlayer(player: Player) {
        val item = player.currentMediaItem
        val trackId = item?.mediaId
        val previousTrackId = state.value.trackId
        if (trackId != previousTrackId) {
            embeddedSubtitle.value = ""
            loopDraftStart.value = -1L
            subtitles.load(trackId)
        }
        val orderedIndices = orderedQueueIndices(player)
        val queueIds = orderedIndices.map { player.getMediaItemAt(it).mediaId }
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val pos = player.currentPosition.coerceAtLeast(0L)
        if (positionMs.value != pos) positionMs.value = pos
        val buf = player.bufferedPosition.coerceAtLeast(0L)
        if (bufferedMs.value != buf) bufferedMs.value = buf

        val currentTracks = player.currentTracks
        val hasVideo =
            currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.isSupported
            }
        val tracksKnown =
            !currentTracks.isEmpty ||
                player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_ENDED
        val videoSize = player.videoSize
        val aspectRatio =
            if (videoSize.width > 0 && videoSize.height > 0) {
                (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(
                    0.35f,
                    3f,
                )
            } else if (trackId == previousTrackId) {
                state.value.videoAspectRatio
            } else {
                16f / 9f
            }

        val nextState =
            PlaybackState(
                trackId = trackId,
                playing = player.isPlaying,
                positionMs = pos,
                durationMs = duration,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                timerEnd = activeTimer?.deadlineMs ?: 0L,
                timerStopAfterTrack = activeTimer?.stopAfterTrack == true,
                loopStart = activeLoop?.startMs ?: -1L,
                loopEnd = activeLoop?.endMs ?: -1L,
                playWhenReady = player.playWhenReady,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                ended = player.playbackState == Player.STATE_ENDED,
                failed = player.playerError != null,
                queueIds = queueIds,
                hasVideo = hasVideo,
                videoAspectRatio = aspectRatio,
                tracksKnown = tracksKnown,
                speed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
            )
        lastClearedLoop.value = activeLoop?.lastClearedBySeek
        val prev = state.value
        val sameDiscrete =
            prev.trackId == nextState.trackId &&
                prev.playing == nextState.playing &&
                prev.durationMs == nextState.durationMs &&
                prev.buffering == nextState.buffering &&
                prev.timerEnd == nextState.timerEnd &&
                prev.timerStopAfterTrack == nextState.timerStopAfterTrack &&
                prev.loopStart == nextState.loopStart &&
                prev.loopEnd == nextState.loopEnd &&
                prev.playWhenReady == nextState.playWhenReady &&
                prev.hasPrevious == nextState.hasPrevious &&
                prev.hasNext == nextState.hasNext &&
                prev.ended == nextState.ended &&
                prev.failed == nextState.failed &&
                prev.queueIds == nextState.queueIds &&
                prev.hasVideo == nextState.hasVideo &&
                prev.videoAspectRatio == nextState.videoAspectRatio &&
                prev.tracksKnown == nextState.tracksKnown &&
                prev.speed == nextState.speed &&
                prev.repeatMode == nextState.repeatMode
        if (!sameDiscrete) state.value = nextState
        reconcileQueueTracks(player, queueIds)
    }

    private fun reconcileQueueTracks(player: Player, queueIds: List<String>) {
        if (queueIds.isEmpty()) {
            queueTracks.value = emptyList()
            return
        }
        val missing = queueIds.filter { it !in queueSnapshot }
        if (missing.isEmpty()) {
            val resolved = queueIds.mapNotNull { queueSnapshot[it] }
            if (queueTracks.value != resolved) queueTracks.value = resolved
            return
        }
        val fallbacks =
            orderedQueueIndices(player).mapIndexed { position, index ->
                val item = player.getMediaItemAt(index)
                queueSnapshot[item.mediaId]
                    ?: TrackEntity(
                        id = item.mediaId,
                        workId = item.mediaMetadata.extras?.getString("workId").orEmpty(),
                        title = item.mediaMetadata.title?.toString().orEmpty(),
                        fileName = item.mediaMetadata.title?.toString().orEmpty(),
                        relativePath = "",
                        order = position + 1,
                    )
            }
        queueTracks.value = fallbacks
        queueLoadJob?.cancel()
        queueLoadJob = scope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    missing.chunked(500).flatMap { library.dao.tracksByIds(it) }
                }
            loaded.forEach { queueSnapshot[it.id] = it }
            val currentPlayer = activePlayer ?: return@launch
            val currentIds =
                orderedQueueIndices(currentPlayer).map {
                    currentPlayer.getMediaItemAt(it).mediaId
                }
            queueTracks.value = currentIds.mapIndexed { idx, id ->
                queueSnapshot[id]
                    ?: fallbacks.getOrNull(idx)
                    ?: TrackEntity(
                        id = id,
                        workId = "",
                        title = "",
                        fileName = "",
                        relativePath = "",
                        order = idx + 1,
                    )
            }
        }
    }

    private fun orderedQueueIndices(player: Player): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return (0 until player.mediaItemCount).toList()
        val shuffle = player.shuffleModeEnabled
        return buildList(timeline.windowCount) {
            var index = timeline.getFirstWindowIndex(shuffle)
            while (index != C.INDEX_UNSET && size < timeline.windowCount) {
                add(index)
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            }
        }
    }
}
