// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import app.whisp.MainActivity
import app.whisp.WhispApp
import app.whisp.data.DlsiteLoginRequired
import app.whisp.data.ProgressEntity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Thin MediaLibraryService owning ExoPlayer lifecycle, SystemUI media session, and progress IO. */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class PlaybackService : MediaLibraryService() {
    companion object {
        const val TIMER = "whisp.timer"
        const val LOOP = "whisp.loop"
        const val RECENT_ROOT = "whisp.recent"
    }

    private val graph
        get() = (application as WhispApp).graph

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var loop: AbLoopController
    private lateinit var timer: SleepTimerController
    private var session: MediaLibrarySession? = null
    private var currentTrackId: String? = null
    private var checkpointJob: Job? = null
    private var currentWakeMode: Int = C.WAKE_MODE_LOCAL
    private var sleepingForOffload: Boolean = false
    private var videoTrackEnabled: Boolean = false
    private var textTrackEnabled: Boolean = false

    override fun onCreate() {
        super.onCreate()
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(graph.media.playbackFactory))
                .setLoadControl(WhispLoadControl.create())
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .experimentalSetDynamicSchedulingEnabled(true)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build()
        applyTrackDisables(videoEnabled = false, textEnabled = false, force = true)
        loop = AbLoopController(player, ::publishSessionState)
        timer =
            SleepTimerController(
                context = this,
                scope = scope,
                onExpired = {
                    player.pause()
                    saveProgress(immediate = true)
                },
                onChanged = ::publishSessionState,
                getVolume = { player.volume },
                setVolume = { player.volume = it },
            )
        timer.register()
        AudioOffload.apply(player, loop, timer)
        installPlayerListener()
        session = buildMediaSession()
        graph.playback.attach(player, loop, timer, ::setVideoEnabled, ::setTextEnabled)
        observeSettings()
    }

    private fun installPlayerListener() {
        player.addAudioOffloadListener(
            object : ExoPlayer.AudioOffloadListener {
                override fun onSleepingForOffloadChanged(sleeping: Boolean) {
                    if (sleepingForOffload == sleeping) return
                    sleepingForOffload = sleeping
                    if (sleeping) saveProgress()
                    reconcileWakeMode()
                    reconcileCheckpoint()
                }
            },
        )
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) loop.onReady()
                    reconcileWakeMode()
                    saveProgress()
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    reconcileWakeMode()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) {
                        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                            if (loop.onEndOfMediaItem()) return
                            if (timer.consumeStopAfterTrack()) {
                                saveProgress(immediate = true)
                                return
                            }
                        }
                        loop.onPauseRequested()
                        saveProgress()
                    }
                    reconcileWakeMode()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem?.mediaId != currentTrackId) {
                        currentTrackId = mediaItem?.mediaId
                        loop.onTrackTransition()
                        reconcileWakeMode()
                        saveProgress()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && player.playWhenReady) player.pause()
                    if (playbackState == Player.STATE_READY) loop.onReady()
                    reconcileWakeMode()
                    val id = player.currentMediaItem?.mediaId ?: return
                    val duration = player.duration
                    if (
                        duration > 0 &&
                            (playbackState == Player.STATE_READY ||
                                playbackState == Player.STATE_ENDED)
                    ) {
                        graph.scope.launch { graph.library.dao.updateDuration(id, duration) }
                    }
                    saveProgress()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    AudioOffload.apply(player, loop, timer)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    loop.onEvents()
                    reconcileCheckpoint()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    val loopSeek = loop.onPositionDiscontinuity(oldPosition, newPosition, reason)
                    if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                        savePosition(oldPosition.mediaItem, oldPosition.positionMs)
                    }
                    reconcileWakeMode()
                    if (!loopSeek) saveProgress()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val expired =
                        generateSequence<Throwable>(error) { it.cause }
                            .any { it is DlsiteLoginRequired }
                    graph.messages.tryEmit(
                        if (expired) "DLsite 登录已过期，请在来源页重新登录" else "播放失败，请检查文件授权或网络连接",
                    )
                    saveProgress()
                }
            },
        )
    }

    private fun buildMediaSession(): MediaLibrarySession {
        val sessionPlayer =
            object : ForwardingSimpleBasePlayer(player) {
                override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                    if (playWhenReady) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
                        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)
                            player.prepare()
                    }
                    return super.handleSetPlayWhenReady(playWhenReady)
                }

                override fun handleSeek(
                    mediaItemIndex: Int,
                    positionMs: Long,
                    seekCommand: Int,
                ): ListenableFuture<*> {
                    if (
                        player.playbackState == Player.STATE_ENDED &&
                            seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION
                    ) {
                        return super.handleSeek(0, 0, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    }
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
            }
        val catalog = ResumeCatalog(graph.library, graph.settings, graph.playback)
        return MediaLibrarySession.Builder(
                this,
                sessionPlayer,
                object : MediaLibrarySession.Callback {
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): MediaSession.ConnectionResult {
                        val isSelf =
                            controller.uid == android.os.Process.myUid() &&
                                controller.packageName == packageName
                        if (!controller.isTrusted && !isSelf)
                            return MediaSession.ConnectionResult.reject()
                        val commands =
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                                .buildUpon()
                                .add(SessionCommand(TIMER, Bundle.EMPTY))
                                .add(SessionCommand(LOOP, Bundle.EMPTY))
                                .build()
                        return MediaSession.ConnectionResult.AcceptedResultBuilder(
                                session,
                                controller,
                            )
                            .setAvailableSessionCommands(commands)
                            .build()
                    }

                    override fun onGetLibraryRoot(
                        session: MediaLibrarySession,
                        browser: MediaSession.ControllerInfo,
                        params: LibraryParams?,
                    ): ListenableFuture<LibraryResult<MediaItem>> =
                        Futures.immediateFuture(LibraryResult.ofItem(recentRoot(), params))

                    override fun onGetChildren(
                        session: MediaLibrarySession,
                        browser: MediaSession.ControllerInfo,
                        parentId: String,
                        page: Int,
                        pageSize: Int,
                        params: LibraryParams?,
                    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                        if (parentId != RECENT_ROOT)
                            return Futures.immediateFuture(
                                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
                            )
                        return scope.ioResult {
                            val snapshot = if (page == 0) catalog.snapshot() else null
                            LibraryResult.ofItemList(
                                if (snapshot == null) emptyList()
                                else listOf(catalog.recentItem(snapshot)),
                                params,
                            )
                        }
                    }

                    override fun onGetItem(
                        session: MediaLibrarySession,
                        browser: MediaSession.ControllerInfo,
                        mediaId: String,
                    ): ListenableFuture<LibraryResult<MediaItem>> =
                        if (mediaId == RECENT_ROOT) {
                            Futures.immediateFuture(LibraryResult.ofItem(recentRoot(), null))
                        } else {
                            scope.ioResult {
                                val snapshot = catalog.snapshot()
                                if (
                                    snapshot == null ||
                                        snapshot.tracks[snapshot.position.index].id != mediaId
                                ) {
                                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                } else {
                                    LibraryResult.ofItem(catalog.recentItem(snapshot), null)
                                }
                            }
                        }

                    override fun onAddMediaItems(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        mediaItems: List<MediaItem>,
                    ): ListenableFuture<List<MediaItem>> = scope.ioResult {
                        mediaItems.map { item ->
                            val track = graph.library.dao.track(item.mediaId) ?: error("音轨不可用")
                            val work = graph.library.dao.work(track.workId) ?: error("作品不可用")
                            graph.playback.mediaItem(track, work)
                        }
                    }

                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle,
                    ): ListenableFuture<SessionResult> {
                        when (customCommand.customAction) {
                            TIMER -> timer.setMinutes(args.getInt("minutes", 0))
                            LOOP -> loop.set(args.getLong("start", -1), args.getLong("end", -1))
                            else ->
                                return Futures.immediateFuture(
                                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                                )
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }

                    override fun onPlaybackResumption(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        isForPlayback: Boolean,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.ioResult {
                        val snapshot = catalog.snapshot() ?: error("暂无播放记录")
                        if (isForPlayback) {
                            val items = graph.playback.mediaItems(snapshot.tracks, snapshot.work)
                            MediaSession.MediaItemsWithStartPosition(
                                items,
                                snapshot.position.index,
                                snapshot.position.positionMs,
                            )
                        } else {
                            MediaSession.MediaItemsWithStartPosition(
                                listOf(catalog.recentItem(snapshot)),
                                0,
                                snapshot.position.positionMs,
                            )
                        }
                    }
                },
            )
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.OPEN_PLAYER)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    private fun observeSettings() {
        scope.launch {
            graph.settings.settingsUpdates
                .map { it.playbackSpeed }
                .distinctUntilChanged()
                .collect { speed ->
                    if (abs(player.playbackParameters.speed - speed) > 0.005f) {
                        player.setPlaybackSpeed(speed)
                    }
                }
        }
        scope.launch {
            graph.settings.settingsUpdates
                .map { it.hideArtwork }
                .distinctUntilChanged()
                .collect { hide ->
                    val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    if (items.isNotEmpty()) {
                        val metadataByTrack =
                            withContext(Dispatchers.IO) {
                                val ids = items.map { it.mediaId }.distinct()
                                val tracks =
                                    ids.chunked(500).flatMap { graph.library.dao.tracksByIds(it) }
                                val works =
                                    tracks
                                        .map { it.workId }
                                        .distinct()
                                        .chunked(500)
                                        .flatMap { graph.library.dao.worksByIds(it) }
                                        .associateBy { it.id }
                                val artworkByCover =
                                    if (hide) emptyMap()
                                    else
                                        works.values
                                            .mapNotNull { it.coverId }
                                            .distinct()
                                            .mapNotNull { coverId ->
                                                graph.images.thumbnailBytes(coverId)?.let {
                                                    coverId to it
                                                }
                                            }
                                            .toMap()
                                tracks
                                    .mapNotNull { track ->
                                        works[track.workId]?.let { work ->
                                            track.id to
                                                graph.playback.mediaMetadata(
                                                    track,
                                                    work,
                                                    hide,
                                                    work.coverId?.let(artworkByCover::get),
                                                )
                                        }
                                    }
                                    .toMap()
                            }
                        for ((index, old) in items.withIndex()) {
                            val metadata = metadataByTrack[old.mediaId] ?: continue
                            if (
                                index < player.mediaItemCount &&
                                    player.getMediaItemAt(index).mediaId == old.mediaId
                            ) {
                                val current = player.getMediaItemAt(index)
                                player.replaceMediaItem(
                                    index,
                                    current.buildUpon().setMediaMetadata(metadata).build(),
                                )
                            }
                        }
                    }
                    val hasRecent =
                        withContext(Dispatchers.IO) { graph.library.dao.latestProgress() != null }
                    session?.notifyChildrenChanged(RECENT_ROOT, if (hasRecent) 1 else 0, null)
                }
        }
    }

    private fun setVideoEnabled(enabled: Boolean) {
        applyTrackDisables(videoEnabled = enabled, textEnabled = textTrackEnabled)
    }

    private fun setTextEnabled(enabled: Boolean) {
        applyTrackDisables(videoEnabled = videoTrackEnabled, textEnabled = enabled)
    }

    private fun applyTrackDisables(
        videoEnabled: Boolean,
        textEnabled: Boolean,
        force: Boolean = false,
    ) {
        if (!force && videoTrackEnabled == videoEnabled && textTrackEnabled == textEnabled) return
        videoTrackEnabled = videoEnabled
        textTrackEnabled = textEnabled
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !textEnabled)
                // Metadata renderer is unused and keeping it active blocks Media3 offload sleep.
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
                .build()
    }

    private fun reconcileWakeMode() {
        val item = player.currentMediaItem
        val scheme = item?.localConfiguration?.uri?.scheme?.lowercase()
        val trackId = item?.mediaId
        val isLocal =
            scheme == null ||
                scheme == "file" ||
                scheme == "content" ||
                (trackId != null && trackId in graph.media.completedDownloads.value)
        val idleBetweenBursts = !player.isLoading && player.playbackState == Player.STATE_READY
        val desired =
            if (isLocal || sleepingForOffload || idleBetweenBursts) C.WAKE_MODE_LOCAL
            else C.WAKE_MODE_NETWORK
        if (desired != currentWakeMode) {
            currentWakeMode = desired
            player.setWakeMode(desired)
        }
    }

    private fun publishSessionState() {
        player.setPauseAtEndOfMediaItems(loop.active || timer.stopAfterTrack)
        session?.setSessionExtras(
            Bundle().apply {
                putLong("timerEnd", timer.deadlineMs)
                putBoolean("timerStopAfterTrack", timer.stopAfterTrack)
                putLong("loopStart", loop.startMs)
                putLong("loopEnd", loop.endMs)
            },
        )
        AudioOffload.apply(player, loop, timer)
        graph.playback.onControllerStateChanged()
    }

    private fun reconcileCheckpoint() {
        val playbackState = player.playbackState
        val shouldRun =
            !sleepingForOffload &&
                player.playWhenReady &&
                (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
        if (shouldRun) {
            if (checkpointJob?.isActive != true) {
                checkpointJob = scope.launch {
                    while (isActive) {
                        delay(15_000)
                        if (player.isPlaying && !sleepingForOffload) saveProgress()
                    }
                }
            }
        } else {
            checkpointJob?.cancel()
            checkpointJob = null
        }
    }

    private fun saveProgress(immediate: Boolean = false) {
        if (
            !immediate &&
                loop.suppressAutoProgress &&
                player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playerError == null
        ) {
            return
        }
        savePosition(player.currentMediaItem, player.currentPosition, immediate)
    }

    private fun savePosition(item: MediaItem?, position: Long, immediate: Boolean = false) {
        val id = item?.mediaId ?: return
        val workId = item.mediaMetadata.extras?.getString("workId") ?: return
        val entity = ProgressEntity(workId, id, position.coerceAtLeast(0))
        if (immediate) graph.library.savePlaybackProgressImmediate(entity)
        else graph.library.savePlaybackProgress(entity)
    }

    private fun recentRoot(): MediaItem =
        MediaItem.Builder()
            .setMediaId(RECENT_ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Whisp")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress(immediate = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveProgress(immediate = true)
        graph.playback.detach(player)
        loop.release()
        timer.release()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}

internal fun <T> CoroutineScope.ioResult(block: suspend () -> T): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    val job =
        launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
            } catch (e: Throwable) {
                future.setException(e)
            }
        }
    future.addListener({ if (future.isCancelled) job.cancel() }, Runnable::run)
    return future
}
