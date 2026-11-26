// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import app.whisp.MainActivity
import app.whisp.WhispApp
import app.whisp.data.ProgressEntity
import app.whisp.system.SleepShakeDetector
import app.whisp.system.WhispShortcuts
import app.whisp.system.WhispWidgetProvider
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
        const val QUICK_BOOKMARK = "whisp.quick_bookmark"
        const val EXTEND_TIMER = "whisp.extend_timer"
        const val RECENT_ROOT = ResumeCatalog.RECENT_ROOT
        private const val LONG_PAUSE_REWIND_THRESHOLD_MS = 5 * 60_000L
        private const val SMART_RESUME_REWIND_MS = 3_000L
        private const val IDLE_POOL_CLOSE_DELAY_MS = 30_000L
    }

    private val graph
        get() = (application as WhispApp).graph

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var loop: AbLoopController
    private lateinit var timer: SleepTimerController
    private lateinit var shakeDetector: SleepShakeDetector
    private val audioProcessor = WhispAudioProcessor()
    private var session: MediaLibrarySession? = null
    private var currentTrackId: String? = null
    private var checkpointJob: Job? = null
    private var idleCloseJob: Job? = null
    private var softFadeJob: Job? = null
    private var workVolumeJob: Job? = null
    private var activeWorkId: String? = null
    private var currentWakeMode: Int = C.WAKE_MODE_LOCAL
    private var sleepingForOffload: Boolean = false
    private var videoTrackEnabled: Boolean = false
    private var textTrackEnabled: Boolean = false
    private var currentSkipMs: Long = 10_000L
    private var dspEffectsActive: Boolean = false
    private var gaplessPriority: Boolean = false
    private var softFadePauseEnabled: Boolean = true
    private var smartResumeRewindEnabled: Boolean = true
    private var shakeExtendTimerEnabled: Boolean = false
    private var autoDeviceProfileEnabled: Boolean = false
    private var applyingDeviceProfile: Boolean = false
    private var lastAppliedDeviceKey: String? = null
    private var configuredVolumeGain: Float = 1f
    private var lastAppliedGain: Float = 1f
    private var pausedAtElapsedMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val initialSettings = graph.settings.settings.value
        currentSkipMs = initialSettings.skipSeconds * 1000L
        dspEffectsActive = initialSettings.hasDspEffects
        gaplessPriority = initialSettings.gaplessPriority
        softFadePauseEnabled = initialSettings.softFadePause
        smartResumeRewindEnabled = initialSettings.smartResumeRewind
        shakeExtendTimerEnabled = initialSettings.shakeExtendTimer
        autoDeviceProfileEnabled = initialSettings.autoDeviceAudioProfile
        configuredVolumeGain = initialSettings.volumeGain
        lastAppliedGain = initialSettings.volumeGain
        audioProcessor.swapChannels = initialSettings.swapChannels
        audioProcessor.monoAudio = initialSettings.monoAudio
        audioProcessor.nightDynamics = initialSettings.nightDynamics
        audioProcessor.nightDynamicsStrength = initialSettings.nightDynamicsStrength
        audioProcessor.stereoBalance = initialSettings.stereoBalance
        audioProcessor.highPassFilter = initialSettings.highPassFilter
        dspEffectsActive = audioProcessor.hasActiveEffects

        val renderersFactory =
            object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink =
                    DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioProcessors(arrayOf(audioProcessor))
                        .build()
            }
        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory =
            DefaultMediaSourceFactory(graph.media.playbackFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(WhispLoadErrorHandlingPolicy())

        player =
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(WhispLoadControl.create())
                .setAudioAttributes(
                    buildAudioAttributes(
                        initialSettings.duckOnInterrupt,
                        initialSettings.preserveBinaural,
                    ),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .experimentalSetDynamicSchedulingEnabled(true)
                .setSeekBackIncrementMs(currentSkipMs)
                .setSeekForwardIncrementMs(currentSkipMs)
                .build()
        if (configuredVolumeGain < 0.999f) {
            player.volume = configuredVolumeGain
        }
        applyTrackDisables(videoEnabled = false, textEnabled = false, force = true)
        loop = AbLoopController(player, ::publishSessionState)
        val persistedAnchor =
            graph.settings.sleepStartAnchor.value?.let {
                TimerStartAnchor(it.trackId, it.positionMs, it.workId)
            }
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
                    captureStartAnchor = {
                        player.currentMediaItem?.let { item ->
                            TimerStartAnchor(
                                trackId = item.mediaId,
                                positionMs = player.currentPosition.coerceAtLeast(0L),
                                workId = item.mediaMetadata.extras?.getString("workId").orEmpty(),
                            )
                        }
                    },
                    initialStartAnchor = persistedAnchor,
                    onStartAnchorChanged = { anchor ->
                        graph.scope.launch {
                            graph.settings.saveSleepStartAnchor(
                                anchor?.let {
                                    app.whisp.data.PersistedSleepAnchor(
                                        workId = it.workId,
                                        trackId = it.trackId,
                                        positionMs = it.positionMs,
                                    )
                                },
                            )
                        }
                    },
                )
                .apply {
                    fadeDurationMs = initialSettings.sleepFadeSeconds * 1000L
                }
        timer.register()
        shakeDetector =
            SleepShakeDetector(this) {
                if (timer.deadlineMs > 0L) {
                    timer.extendMinutes(15)
                    graph.messages.tryEmit("摇一摇已延长定时 15 分钟")
                }
            }
        applyAudioOffload()
        installPlayerListener()
        session = buildMediaSession()
        updateMediaButtonPreferences()
        graph.playback.attach(player, loop, timer, ::setVideoEnabled, ::setTextEnabled)
        observeSettings()
        observeAudioOutputProfiles()
        refreshSystemSurfaces()
    }

    private fun buildAudioAttributes(
        duckOnInterrupt: Boolean,
        preserveBinaural: Boolean,
    ): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(
                if (duckOnInterrupt) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH,
            )
            .setSpatializationBehavior(
                if (preserveBinaural) C.SPATIALIZATION_BEHAVIOR_NEVER
                else C.SPATIALIZATION_BEHAVIOR_AUTO,
            )
            .build()

    private fun applyAudioOffload() {
        AudioOffload.apply(
            player = player,
            loop = loop,
            timer = timer,
            dspEffectsActive = dspEffectsActive,
            gaplessRequired = gaplessPriority,
        )
    }

    private fun startSoftResumeFadeIfEligible() {
        softFadeJob?.cancel()
        softFadeJob = null
        timer.restorePendingVolumeIfNeeded()
        if (!softFadePauseEnabled || timer.fading) return
        val target = player.volume
        // Respect external mute (e.g. integration tests or intentional 0 volume).
        if (target <= 0.01f) return
        player.volume = (target * 0.15f).coerceAtLeast(0.02f)
        softFadeJob = scope.launch {
            val steps = 8
            for (step in 1..steps) {
                delay(24L)
                if (!player.playWhenReady || timer.fading) break
                val fraction = step.toFloat() / steps.toFloat()
                player.volume = target * (0.15f + 0.85f * fraction * fraction)
            }
            if (!timer.fading) player.volume = target
        }
    }

    private fun applySmartResumeRewindIfNeeded() {
        val pausedAt = pausedAtElapsedMs
        pausedAtElapsedMs = 0L
        if (!smartResumeRewindEnabled || pausedAt <= 0L || loop.active) return
        val pauseDuration = SystemClock.elapsedRealtime() - pausedAt
        if (pauseDuration < LONG_PAUSE_REWIND_THRESHOLD_MS) return
        val pos = player.currentPosition
        if (pos > 5_000L && player.playbackState == Player.STATE_READY) {
            player.seekTo((pos - SMART_RESUME_REWIND_MS).coerceAtLeast(0L))
        }
    }

    private fun reconcileIdleConnectionClose() {
        val active = player.playWhenReady || player.isPlaying || player.isLoading
        if (active) {
            idleCloseJob?.cancel()
            idleCloseJob = null
        } else if (idleCloseJob?.isActive != true) {
            idleCloseJob = scope.launch {
                delay(IDLE_POOL_CLOSE_DELAY_MS)
                if (!player.playWhenReady && !player.isPlaying && !player.isLoading) {
                    withContext(Dispatchers.IO) {
                        graph.sources.smb.closeIdle()
                        graph.sources.nfs.closeIdle()
                    }
                }
            }
        }
    }

    private fun updateMediaButtonPreferences() {
        val currentSession = session ?: return
        val skipSec = (currentSkipMs / 1000L).toInt()
        val backIcon =
            when (skipSec) {
                5 -> CommandButton.ICON_SKIP_BACK_5
                15 -> CommandButton.ICON_SKIP_BACK_15
                30 -> CommandButton.ICON_SKIP_BACK_30
                else -> CommandButton.ICON_SKIP_BACK_10
            }
        val forwardIcon =
            when (skipSec) {
                5 -> CommandButton.ICON_SKIP_FORWARD_5
                15 -> CommandButton.ICON_SKIP_FORWARD_15
                30 -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD_10
            }
        val buttons =
            listOf(
                CommandButton.Builder(backIcon)
                    .setDisplayName("后退${skipSec}秒")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder(forwardIcon)
                    .setDisplayName("前进${skipSec}秒")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_BOOKMARK_UNFILLED)
                    .setDisplayName("保存片段")
                    .setSessionCommand(SessionCommand(QUICK_BOOKMARK, Bundle.EMPTY))
                    .build(),
                CommandButton.Builder(CommandButton.ICON_PLUS)
                    .setDisplayName("定时+15分")
                    .setSessionCommand(SessionCommand(EXTEND_TIMER, Bundle.EMPTY))
                    .build(),
            )
        currentSession.setMediaButtonPreferences(buttons)
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
                    if (isPlaying) {
                        loop.onReady()
                        graph.playback.clearPlaybackError()
                    } else if (!player.playWhenReady) {
                        timer.restorePendingVolumeIfNeeded()
                    }
                    reconcileWakeMode()
                    reconcileIdleConnectionClose()
                    saveProgress()
                    refreshSystemSurfaces()
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    reconcileWakeMode()
                    reconcileIdleConnectionClose()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) {
                        applySmartResumeRewindIfNeeded()
                        startSoftResumeFadeIfEligible()
                    } else {
                        softFadeJob?.cancel()
                        softFadeJob = null
                        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                            if (loop.onEndOfMediaItem()) return
                            if (timer.consumeStopAfterTrack()) {
                                saveProgress(immediate = true)
                                return
                            }
                        } else if (
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                        ) {
                            pausedAtElapsedMs = SystemClock.elapsedRealtime()
                        }
                        loop.onPauseRequested()
                        saveProgress()
                    }
                    reconcileWakeMode()
                    reconcileIdleConnectionClose()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (mediaItem?.mediaId != currentTrackId) {
                        currentTrackId = mediaItem?.mediaId
                        pausedAtElapsedMs = 0L
                        loop.onTrackTransition()
                        observeActiveWorkVolume(
                            mediaItem?.mediaMetadata?.extras?.getString("workId"),
                        )
                        reconcileWakeMode()
                        saveProgress()
                        refreshSystemSurfaces()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && player.playWhenReady) player.pause()
                    if (playbackState == Player.STATE_READY) {
                        loop.onReady()
                        graph.playback.clearPlaybackError()
                    }
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
                    applyAudioOffload()
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
                    val scheme =
                        player.currentMediaItem?.localConfiguration?.uri?.scheme?.lowercase()
                    val info = PlaybackErrorFormatter.describe(error, scheme)
                    graph.playback.setPlaybackError(info)
                    graph.messages.tryEmit(info.message)
                    saveProgress()
                }
            },
        )
    }

    private fun buildMediaSession(): MediaLibrarySession {
        val sessionPlayer =
            object : ForwardingSimpleBasePlayer(player) {
                override fun getState(): State =
                    super.getState()
                        .buildUpon()
                        .setSeekBackIncrementMs(currentSkipMs)
                        .setSeekForwardIncrementMs(currentSkipMs)
                        .build()

                override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                    if (playWhenReady) {
                        if (graph.playback.isBlockedBySpeakerGuard()) {
                            graph.messages.tryEmit("已开启外放防社死，请连接耳机或打开应用确认")
                            return Futures.immediateVoidFuture()
                        }
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
                    if (seekCommand == Player.COMMAND_SEEK_BACK) {
                        val target = (player.currentPosition - currentSkipMs).coerceAtLeast(0L)
                        return super.handleSeek(
                            player.currentMediaItemIndex,
                            target,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        )
                    }
                    if (seekCommand == Player.COMMAND_SEEK_FORWARD) {
                        val dur = player.duration.takeIf { it > 0L }
                        val raw = player.currentPosition + currentSkipMs
                        val target = if (dur != null) raw.coerceAtMost(dur) else raw
                        return super.handleSeek(
                            player.currentMediaItemIndex,
                            target,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        )
                    }
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
                                .add(SessionCommand(QUICK_BOOKMARK, Bundle.EMPTY))
                                .add(SessionCommand(EXTEND_TIMER, Bundle.EMPTY))
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
                        Futures.immediateFuture(
                            LibraryResult.ofItem(
                                if (params?.isRecent == true) recentRoot() else libraryRoot(),
                                params,
                            ),
                        )

                    override fun onGetChildren(
                        session: MediaLibrarySession,
                        browser: MediaSession.ControllerInfo,
                        parentId: String,
                        page: Int,
                        pageSize: Int,
                        params: LibraryParams?,
                    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                        if (
                            parentId !in
                                setOf(
                                    ResumeCatalog.ROOT,
                                    ResumeCatalog.RECENT_ROOT,
                                    ResumeCatalog.FAVORITES_ROOT,
                                    ResumeCatalog.OFFLINE_ROOT,
                                )
                        ) {
                            return Futures.immediateFuture(
                                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
                            )
                        }
                        return scope.ioResult {
                            val items =
                                if (page == 0) catalog.childrenFor(parentId) else emptyList()
                            LibraryResult.ofItemList(items, params)
                        }
                    }

                    override fun onGetItem(
                        session: MediaLibrarySession,
                        browser: MediaSession.ControllerInfo,
                        mediaId: String,
                    ): ListenableFuture<LibraryResult<MediaItem>> = scope.ioResult {
                        val item = catalog.itemFor(mediaId)
                        if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        else LibraryResult.ofItem(item, null)
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
                            QUICK_BOOKMARK -> graph.playback.quickBookmarkCurrent()
                            EXTEND_TIMER -> {
                                val mins = args.getInt("minutes", 15).coerceIn(1, 60)
                                timer.extendMinutes(mins)
                                graph.messages.tryEmit("已延长定时 $mins 分钟")
                            }
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
                .map { it.skipSeconds }
                .distinctUntilChanged()
                .collect { seconds ->
                    val nextMs = seconds * 1000L
                    if (currentSkipMs != nextMs) {
                        currentSkipMs = nextMs
                        updateMediaButtonPreferences()
                    }
                }
        }
        scope.launch {
            graph.settings.settingsUpdates
                .map {
                    listOf(
                        it.swapChannels,
                        it.monoAudio,
                        it.nightDynamics,
                        it.nightDynamicsStrength,
                        it.stereoBalance,
                        it.highPassFilter,
                        it.gaplessPriority,
                        it.softFadePause,
                        it.smartResumeRewind,
                        it.sleepFadeSeconds,
                        it.shakeExtendTimer,
                        it.autoDeviceAudioProfile,
                    )
                }
                .distinctUntilChanged()
                .collect {
                    val s = graph.settings.settings.value
                    audioProcessor.swapChannels = s.swapChannels
                    audioProcessor.monoAudio = s.monoAudio
                    audioProcessor.nightDynamics = s.nightDynamics
                    audioProcessor.nightDynamicsStrength = s.nightDynamicsStrength
                    audioProcessor.stereoBalance = s.stereoBalance
                    audioProcessor.highPassFilter = s.highPassFilter
                    dspEffectsActive = audioProcessor.hasActiveEffects
                    gaplessPriority = s.gaplessPriority
                    softFadePauseEnabled = s.softFadePause
                    smartResumeRewindEnabled = s.smartResumeRewind
                    shakeExtendTimerEnabled = s.shakeExtendTimer
                    autoDeviceProfileEnabled = s.autoDeviceAudioProfile
                    timer.fadeDurationMs = s.sleepFadeSeconds * 1000L
                    applyAudioOffload()
                    reconcileShakeDetector()
                    persistActiveDeviceProfileIfNeeded(s)
                }
        }
        scope.launch {
            graph.settings.settingsUpdates
                .map { it.duckOnInterrupt to it.preserveBinaural }
                .distinctUntilChanged()
                .collect { (duck, preserveBinaural) ->
                    val desired = buildAudioAttributes(duck, preserveBinaural)
                    if (player.audioAttributes != desired) {
                        player.setAudioAttributes(desired, true)
                    }
                }
        }
        scope.launch {
            graph.settings.settingsUpdates
                .map { it.volumeGain }
                .distinctUntilChanged()
                .collect { gain ->
                    configuredVolumeGain = gain
                    if (abs(lastAppliedGain - gain) > 0.005f) {
                        lastAppliedGain = gain
                        // Respect explicit external mute (0f) unless the user adjusts volume gain.
                        if (!timer.fading) {
                            softFadeJob?.cancel()
                            softFadeJob = null
                            player.volume = gain
                        }
                    }
                    persistActiveDeviceProfileIfNeeded(graph.settings.settings.value)
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
                    session?.notifyChildrenChanged(ResumeCatalog.ROOT, if (hide) 1 else 3, null)
                    refreshSystemSurfaces()
                }
        }
    }

    private fun observeAudioOutputProfiles() {
        scope.launch {
            graph.audioDevices.output.collect { output ->
                if (!autoDeviceProfileEnabled || !output.hasExternalOutput) {
                    if (!output.hasExternalOutput) lastAppliedDeviceKey = null
                    return@collect
                }
                if (output.deviceKey == lastAppliedDeviceKey) return@collect
                lastAppliedDeviceKey = output.deviceKey
                val saved = graph.settings.deviceAudioProfileFor(output.deviceKey)
                if (saved != null) {
                    applyingDeviceProfile = true
                    try {
                        graph.settings.update { it.applyingDeviceAudioProfile(saved) }
                    } finally {
                        applyingDeviceProfile = false
                    }
                } else {
                    persistActiveDeviceProfileIfNeeded(graph.settings.settings.value)
                }
            }
        }
    }

    private fun persistActiveDeviceProfileIfNeeded(settings: app.whisp.data.Settings) {
        if (!autoDeviceProfileEnabled || applyingDeviceProfile) return
        val output = graph.audioDevices.output.value
        if (!output.hasExternalOutput) return
        lastAppliedDeviceKey = output.deviceKey
        val profile = settings.toDeviceAudioProfile()
        graph.scope.launch { graph.settings.saveDeviceAudioProfile(output.deviceKey, profile) }
    }

    private fun observeActiveWorkVolume(workId: String?) {
        if (workId == activeWorkId && workVolumeJob?.isActive == true) return
        activeWorkId = workId
        workVolumeJob?.cancel()
        workVolumeJob = null
        if (workId.isNullOrBlank()) {
            audioProcessor.workGainLinear = 1f
            dspEffectsActive = audioProcessor.hasActiveEffects
            applyAudioOffload()
            return
        }
        workVolumeJob = scope.launch {
            graph.library
                .observeDetails(workId)
                .map { it?.work?.volumeOffsetDb ?: 0f }
                .distinctUntilChanged()
                .collect { db ->
                    audioProcessor.workGainLinear = WhispAudioProcessor.dbToLinear(db)
                    dspEffectsActive = audioProcessor.hasActiveEffects
                    applyAudioOffload()
                }
        }
    }

    private fun reconcileShakeDetector() {
        if (::shakeDetector.isInitialized) {
            shakeDetector.setActive(shakeExtendTimerEnabled && timer.deadlineMs > 0L)
        }
    }

    private fun refreshSystemSurfaces() {
        WhispWidgetProvider.requestUpdate(this)
        graph.scope.launch { WhispShortcuts.refresh(this@PlaybackService, graph) }
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
        applyAudioOffload()
        reconcileShakeDetector()
        graph.playback.onControllerStateChanged()
        refreshSystemSurfaces()
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
        val clamped = position.coerceAtLeast(0)
        val entity = ProgressEntity(workId, id, clamped)
        if (immediate) graph.library.savePlaybackProgressImmediate(entity)
        else graph.library.savePlaybackProgress(entity)
        // Persist per-track position asynchronously for effortless multi-track/edition switching.
        if (clamped > 0L) {
            graph.scope.launch { graph.library.rememberTrackPosition(id, clamped) }
        }
    }

    private fun libraryRoot(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ResumeCatalog.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Whisp")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

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
        softFadeJob?.cancel()
        idleCloseJob?.cancel()
        workVolumeJob?.cancel()
        if (::shakeDetector.isInitialized) shakeDetector.release()
        graph.playback.detach(player)
        loop.release()
        timer.release()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        WhispWidgetProvider.requestUpdate(this)
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
