// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import app.whisp.MainActivity
import app.whisp.WhispApp
import app.whisp.data.ProgressEntity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val repository
        get() = (application as WhispApp).repository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var timerEnd = 0L
    private var loopStart = -1L
    private var loopEnd = -1L
    private var currentTrackId: String? = null
    private var checkpointJob: Job? = null
    private var loopJob: Job? = null
    private var timerJob: Job? = null
    private var pendingLoopSeek: Pair<String?, Long>? = null
    private var automaticLoopSeek = false
    private var videoController: MediaSession.ControllerInfo? = null
    // Player callbacks arrive serially on the main thread, so enqueue order
    // matches event order and the single consumer below preserves it.
    private val diagnosticTasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val decoderCorrelator = DecoderCorrelator(::onCorrelatedDecoder)

    companion object {
        const val TIMER = "whisp.timer"
        const val LOOP = "whisp.loop"
        const val VIDEO = "whisp.video"
        const val RECENT_ROOT = "whisp.recent"
    }

    override fun onCreate() {
        super.onCreate()
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(repository.playbackFactory))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build()
        setVideoEnabled(false)
        updateAudioOffload()
        scope.launch(Dispatchers.IO) {
            // A single consumer keeps dispatch order. Isolate failures so one
            // corrupt entry cannot stop all later diagnostics.
            for (task in diagnosticTasks) {
                try {
                    task()
                } catch (_: Exception) {
                    android.util.Log.w("WhispPlayback", "Diagnostic write skipped")
                }
            }
        }
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    decoderCorrelator.onFormatChanged(
                        "audio",
                        FormatSnapshot(format.sampleMimeType, format.codecs),
                        formatContinuity(decoderReuseEvaluation),
                    )
                }

                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    decoderCorrelator.onFormatChanged(
                        "video",
                        FormatSnapshot(format.sampleMimeType, format.codecs),
                        formatContinuity(decoderReuseEvaluation),
                    )
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoderCorrelator.onDecoderInitialized("audio", decoderName)
                }

                override fun onAudioDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    decoderCorrelator.onDecoderReleased("audio")
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoderCorrelator.onDecoderInitialized("video", decoderName)
                }

                override fun onVideoDecoderReleased(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                ) {
                    decoderCorrelator.onDecoderReleased("video")
                }
            },
        )
        player.addAudioOffloadListener(
            object : ExoPlayer.AudioOffloadListener {
                override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
                    diagnosticTasks.trySend {
                        repository.playbackDiagnostics.record(
                            DecoderAudit.offloaded(offloadedPlayback)
                                .copy(id = UUID.randomUUID().toString()),
                        )
                    }
                }
            },
        )
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    saveProgress()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) {
                        // ExoPlayer owns the exact EOF boundary; polling can miss it and
                        // advance to the next item before the A–B coroutine wakes up.
                        if (
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                                loopStart >= 0 &&
                                loopEnd > loopStart
                        ) {
                            seekLoopStart()
                            player.play()
                            return
                        }
                        automaticLoopSeek = false
                        saveProgress()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem?.mediaId != currentTrackId) {
                        currentTrackId = mediaItem?.mediaId
                        automaticLoopSeek = false
                        pendingLoopSeek = null
                        loopStart = -1
                        loopEnd = -1
                        player.setPauseAtEndOfMediaItems(false)
                        publishExtras()
                        updateAudioOffload()
                        saveProgress()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && player.playWhenReady) player.pause()
                    val id = player.currentMediaItem?.mediaId ?: return
                    val duration = player.duration
                    // Duration settles when content is ready; later buffering flips need no
                    // rewrite.
                    if (
                        duration > 0 &&
                            (playbackState == Player.STATE_READY ||
                                playbackState == Player.STATE_ENDED)
                    )
                        repository.scope.launch { repository.dao.updateDuration(id, duration) }
                    saveProgress()
                }

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    loopJob?.cancel()
                    loopJob = null
                    reconcilePlaybackJobs()
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.isPlaying && pendingLoopSeek == null) automaticLoopSeek = false
                    reconcilePlaybackJobs()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    val loopSeek =
                        reason == Player.DISCONTINUITY_REASON_SEEK &&
                            pendingLoopSeek ==
                                (newPosition.mediaItem?.mediaId to newPosition.positionMs)
                    pendingLoopSeek = null
                    if (!loopSeek) {
                        automaticLoopSeek = false
                        loopJob?.cancel()
                        loopJob = null
                    }
                    // Save the departing work before the next work replaces the active item.
                    if (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId)
                        savePosition(oldPosition.mediaItem, oldPosition.positionMs)
                    // Check the resulting position, including relative seeks from media controls.
                    if (
                        loopStart >= 0 &&
                            (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId ||
                                newPosition.positionMs !in loopStart..loopEnd)
                    ) {
                        loopStart = -1
                        loopEnd = -1
                        player.setPauseAtEndOfMediaItems(false)
                        publishExtras()
                        updateAudioOffload()
                    }
                    if (!loopSeek) saveProgress()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val expired =
                        generateSequence<Throwable>(error) { it.cause }
                            .any { it is app.whisp.data.DlsiteLoginRequired }
                    repository.messages.tryEmit(
                        if (expired) "DLsite 登录已过期，请在来源页重新登录" else "播放失败，请检查文件授权或网络连接",
                    )
                    saveProgress()
                }
            },
        )
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
                    // MediaSession seeks to the current item's default position before a play
                    // request at EOF. Treat that implicit replay as restarting the queue.
                    if (
                        player.playbackState == Player.STATE_ENDED &&
                            seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION
                    ) {
                        return super.handleSeek(0, 0, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    }
                    return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
            }
        val catalog = ResumeCatalog(repository)
        session =
            MediaLibrarySession.Builder(
                    this,
                    sessionPlayer,
                    object : MediaLibrarySession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult {
                            if (!controller.isTrusted && controller.packageName != packageName)
                                return MediaSession.ConnectionResult.reject()
                            val commands =
                                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                                    .buildUpon()
                                    .add(SessionCommand(TIMER, Bundle.EMPTY))
                                    .add(SessionCommand(LOOP, Bundle.EMPTY))
                                    .build()
                            val available =
                                if (controller.packageName == packageName)
                                    commands
                                        .buildUpon()
                                        .add(SessionCommand(VIDEO, Bundle.EMPTY))
                                        .build()
                                else commands
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(
                                    session,
                                    controller,
                                )
                                .setAvailableSessionCommands(available)
                                .build()
                        }

                        override fun onDisconnected(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ) {
                            if (videoController == controller) {
                                videoController = null
                                setVideoEnabled(false)
                            }
                        }

                        override fun onGetLibraryRoot(
                            session: MediaLibrarySession,
                            browser: MediaSession.ControllerInfo,
                            params: LibraryParams?,
                        ): ListenableFuture<LibraryResult<MediaItem>> {
                            // Legacy browser root queries can block the main thread, so this result
                            // is immediate.
                            return Futures.immediateFuture(
                                LibraryResult.ofItem(recentRoot(), params),
                            )
                        }

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
                            } else
                                scope.ioResult {
                                    val snapshot = catalog.snapshot()
                                    if (
                                        snapshot == null ||
                                            snapshot.tracks[snapshot.position.index].id != mediaId
                                    )
                                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                    else LibraryResult.ofItem(catalog.recentItem(snapshot), null)
                                }

                        override fun onAddMediaItems(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            mediaItems: List<MediaItem>,
                        ): ListenableFuture<List<MediaItem>> = scope.ioResult {
                            mediaItems.map { item ->
                                val track = repository.dao.track(item.mediaId) ?: error("音轨不可用")
                                val work = repository.dao.work(track.workId) ?: error("作品不可用")
                                repository.mediaItem(track, work)
                            }
                        }

                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            customCommand: SessionCommand,
                            args: Bundle,
                        ): ListenableFuture<SessionResult> {
                            when (customCommand.customAction) {
                                VIDEO -> {
                                    if (controller.packageName != packageName)
                                        return Futures.immediateFuture(
                                            SessionResult(SessionError.ERROR_PERMISSION_DENIED),
                                        )
                                    val visible = args.getBoolean("visible")
                                    if (visible) videoController = controller
                                    if (videoController == controller) {
                                        setVideoEnabled(visible)
                                        if (!visible) videoController = null
                                    }
                                    return Futures.immediateFuture(
                                        SessionResult(SessionResult.RESULT_SUCCESS),
                                    )
                                }
                                TIMER -> {
                                    timerEnd =
                                        SleepTimer.deadlineMs(
                                            SystemClock.elapsedRealtime(),
                                            args.getInt("minutes", 0),
                                        )
                                    scheduleTimer()
                                    updateAudioOffload()
                                }
                                LOOP -> {
                                    val segment =
                                        LoopState.validate(
                                            args.getLong("start", -1),
                                            args.getLong("end", -1),
                                            player.duration.takeIf { it > 0 },
                                            player.currentMediaItem != null,
                                        )
                                    loopStart = segment?.startMs ?: -1
                                    loopEnd = segment?.endMs ?: -1
                                    player.setPauseAtEndOfMediaItems(loopStart >= 0)
                                    loopJob?.cancel()
                                    loopJob = null
                                    pendingLoopSeek = null
                                    automaticLoopSeek = false
                                    reconcilePlaybackJobs()
                                    updateAudioOffload()
                                }
                                else ->
                                    return Futures.immediateFuture(
                                        SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                                    )
                            }
                            publishExtras()
                            return Futures.immediateFuture(
                                SessionResult(SessionResult.RESULT_SUCCESS),
                            )
                        }

                        override fun onPlaybackResumption(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            isForPlayback: Boolean,
                        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                            scope.ioResult {
                                val snapshot = catalog.snapshot() ?: error("暂无播放记录")
                                if (isForPlayback) {
                                    val items =
                                        snapshot.tracks.map {
                                            repository.mediaItem(it, snapshot.work)
                                        }
                                    MediaSession.MediaItemsWithStartPosition(
                                        items,
                                        snapshot.position.index,
                                        snapshot.position.positionMs,
                                    )
                                } else {
                                    // Boot-time requests must not resolve audio or connect to a
                                    // remote
                                    // source.
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
        scope.launch {
            repository.settingsUpdates
                .map { it.hideArtwork }
                .distinctUntilChanged()
                .collect { hide ->
                    // Service ownership makes privacy work with no Activity or ViewModel alive.
                    val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    for ((index, old) in items.withIndex()) {
                        val metadata =
                            withContext(Dispatchers.IO) {
                                val track =
                                    repository.dao.track(old.mediaId) ?: return@withContext null
                                val work =
                                    repository.dao.work(track.workId) ?: return@withContext null
                                repository.mediaMetadata(track, work, hide)
                            } ?: continue
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
                    val hasRecent =
                        withContext(Dispatchers.IO) { repository.dao.latestProgress() != null }
                    session?.notifyChildrenChanged(RECENT_ROOT, if (hasRecent) 1 else 0, null)
                }
        }
    }

    private fun setVideoEnabled(enabled: Boolean) {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
    }

    private fun reconcilePlaybackJobs() {
        val state = player.playbackState
        if (
            player.playWhenReady && (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        ) {
            if (checkpointJob?.isActive != true)
                checkpointJob = scope.launch {
                    // Rebuffering and automatic loop seeks must not restart the deadline.
                    while (isActive) {
                        delay(15_000)
                        if (player.isPlaying) saveProgress()
                    }
                }
        } else {
            checkpointJob?.cancel()
            checkpointJob = null
        }
        if (player.isPlaying && loopStart >= 0 && loopEnd > loopStart) {
            if (loopJob?.isActive != true)
                loopJob = scope.launch {
                    while (isActive && player.isPlaying && loopStart >= 0 && loopEnd > loopStart) {
                        var remaining = loopEnd - player.currentPosition
                        if (remaining <= 0) {
                            seekLoopStart()
                            remaining = loopEnd - loopStart
                        }
                        delay(LoopSchedule.delayMs(remaining, player.playbackParameters.speed))
                    }
                }
        } else {
            loopJob?.cancel()
            loopJob = null
        }
    }

    private fun seekLoopStart() {
        automaticLoopSeek = true
        pendingLoopSeek = player.currentMediaItem?.mediaId to loopStart
        player.seekTo(loopStart)
    }

    private fun scheduleTimer() {
        timerJob?.cancel()
        timerJob = null
        if (timerEnd <= 0) return
        timerJob = scope.launch {
            // elapsedRealtime keeps the deadline independent of wall-clock changes.
            while (isActive && timerEnd > SystemClock.elapsedRealtime()) delay(
                timerEnd - SystemClock.elapsedRealtime(),
            )
            timerEnd = 0
            player.pause()
            publishExtras()
            updateAudioOffload()
            saveProgress()
        }
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

    private fun publishExtras() {
        session?.setSessionExtras(
            Bundle().apply {
                putLong("timerEnd", timerEnd)
                putLong("loopStart", loopStart)
                putLong("loopEnd", loopEnd)
            },
        )
    }

    private fun updateAudioOffload() {
        if (!AudioOffload.supported(Build.VERSION.SDK_INT)) return
        // Loop polling and the sleep timer rely on Handler deadlines, which stall
        // while offloaded playback lets the CPU sleep. Switching the preference
        // reselections the audio track, so only do it on loop/timer transitions.
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    AudioOffload.preferences(
                        enabled = !AudioOffload.needsPreciseDeadline(loopStart, loopEnd, timerEnd),
                    ),
                )
                .build()
    }

    private fun onCorrelatedDecoder(
        kind: String,
        snapshot: FormatSnapshot?,
        decoder: String?,
        id: String,
        replaceProvisional: Boolean,
    ) {
        // A single consumer keeps dispatch order: launch() alone does not
        // guarantee that coroutines start in call order.
        diagnosticTasks.trySend {
            val candidates = runCatching {
                val mime = snapshot?.mime ?: return@runCatching emptyList<CodecCandidate>()
                MediaCodecUtil.getDecoderInfos(mime, false, false).map {
                    CodecCandidate(it.name, it.hardwareAccelerated)
                }
            }
                .getOrDefault(emptyList())
            val event =
                DecoderAudit.resolve(
                        kind,
                        snapshot?.mime,
                        snapshot?.codecs,
                        decoder,
                        candidates,
                    )
                    .copy(id = id)
            if (replaceProvisional) repository.playbackDiagnostics.replaceById(id, event)
            else repository.playbackDiagnostics.record(event)
        }
    }

    private fun saveProgress() {
        // Automatic A–B seeks can also generate buffering/ready callbacks. The
        // checkpoint records these loops; pause/error/end still flush immediately.
        if (
            automaticLoopSeek &&
                player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playerError == null
        )
            return
        savePosition(player.currentMediaItem, player.currentPosition)
    }

    private fun savePosition(item: MediaItem?, position: Long) {
        val id = item?.mediaId ?: return
        val workId = item.mediaMetadata.extras?.getString("workId") ?: return
        repository.savePlaybackProgress(ProgressEntity(workId, id, position.coerceAtLeast(0)))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onDestroy() {
        saveProgress()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
