// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.LoopState
import app.whisp.playback.PlaybackCoordinator
import app.whisp.playback.SleepTimer
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Subtitle auto-scroll policy: animate only single-step transitions while visible. */
internal object SubtitleFollow {
    fun animate(previous: Int, current: Int, visible: Boolean, reducedMotion: Boolean): Boolean =
        !reducedMotion &&
            visible &&
            previous >= 0 &&
            previous != current &&
            abs(current - previous) <= 1
}

private fun rewindIconFor(seconds: Int) =
    when (seconds) {
        5 -> Icons.Outlined.Replay5
        30 -> Icons.Outlined.Replay30
        else -> Icons.Outlined.Replay10
    }

private fun forwardIconFor(seconds: Int) =
    when (seconds) {
        5 -> Icons.Outlined.Forward5
        30 -> Icons.Outlined.Forward30
        else -> Icons.Outlined.Forward10
    }

@Composable
internal fun PlayerPage(
    detail: WorkDetails?,
    track: TrackEntity?,
    graph: AppGraph,
    back: () -> Unit,
    openDetail: (String) -> Unit = {},
) {
    val bg = MaterialTheme.colorScheme.background
    val amoled = LocalAmoledBlack.current
    val snackbarHost = LocalSnackbarHost.current
    val playback = graph.playback
    val state by playback.state.collectAsStateWithLifecycle()
    val cues by playback.subtitles.cues.collectAsStateWithLifecycle()
    val loading by playback.subtitles.loading.collectAsStateWithLifecycle()
    val subtitleError by playback.subtitles.error.collectAsStateWithLifecycle()
    val queueTracks by playback.queueTracks.collectAsStateWithLifecycle()
    val opening by playback.openingTrackId.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val audioOnly by playback.audioOnly.collectAsStateWithLifecycle()
    val embeddedSubtitle by playback.embeddedSubtitle.collectAsStateWithLifecycle()
    val markA by playback.loopDraftStart.collectAsStateWithLifecycle()
    val lastClearedLoop by playback.lastClearedLoop.collectAsStateWithLifecycle()
    val workProgress by
        remember(detail?.work?.id) {
                detail?.work?.id?.let { graph.library.observeProgress(it) } ?: flowOf(null)
            }
            .collectAsStateWithLifecycle(initialValue = null)

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var fragments by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var subtitleTune by rememberSaveable { mutableStateOf(false) }
    var inlineAbBar by rememberSaveable { mutableStateOf(false) }
    var showScript by rememberSaveable(detail?.work?.id) { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<BookmarkDraft?>(null) }
    var subtitleAction by remember(track?.id) { mutableStateOf<Cue?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }
    var followSignal by remember(track?.id) { mutableIntStateOf(0) }

    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current

    val fallbackPosition =
        remember(workProgress, track?.id) {
            workProgress?.takeIf { it.trackId == track?.id }?.positionMs ?: 0L
        }
    LaunchedEffect(track?.id, state.trackId) {
        if (state.trackId == null) playback.subtitles.load(track?.id)
    }
    val shownTrack = track?.takeIf { it.id == state.trackId && opening == null }
    if (detail == null || track == null) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", action = back)
        }
        return
    }

    // Only keep Media3's embedded text renderer active while the player screen is visible and no
    // external subtitle file is bound; disabling it in background/screen-off unlocks audio offload.
    val subtitleOwner = remember { Any() }
    val wantsEmbeddedSubtitles = track.subtitleId == null
    LifecycleResumeEffect(playback, subtitleOwner, wantsEmbeddedSubtitles) {
        playback.subtitleVisibility(subtitleOwner, wantsEmbeddedSubtitles)
        onPauseOrDispose { playback.subtitleVisibility(subtitleOwner, false) }
    }

    val scripts =
        remember(detail.attachments) {
            detail.attachments.filter { it.kind == "script" }.sortedBy { it.relativePath }
        }
    val preferredScriptId =
        remember(track.scriptId, scripts) {
            track.scriptId?.takeIf { id -> scripts.any { it.id == id } }
                ?: scripts.firstOrNull()?.id
        }

    val coverImage =
        remember(detail.work.coverId) { detail.work.coverId?.let { WhispImage.Asset(it) } }
    var ambientSeed by
        remember(detail.work.coverId) {
            mutableStateOf(cachedAmbientColor(coverImage?.diskKey) ?: RoseContainer)
        }
    val ambient by
        animateColorAsState(
            ambientSeed,
            tween(if (reduced) 0 else 420, easing = FastOutSlowInEasing),
            label = "player-ambient",
        )

    val video =
        if (state.trackId == track.id && state.tracksKnown) state.hasVideo else track.isVideo
    val showVideo = video && !audioOnly && (shownTrack != null || opening != null)
    LaunchedEffect(showVideo) { if (!showVideo) fullscreen = false }
    BackHandler(fullscreen && showVideo) { fullscreen = false }
    BackHandler(subtitleTune && !fullscreen) { subtitleTune = false }

    val hasAnySubtitles =
        cues.isNotEmpty() ||
            loading ||
            subtitleError != null ||
            (track.subtitleId == null && embeddedSubtitle.isNotBlank() && state.trackId == track.id)
    val coverFocusMode = !showVideo && !hasAnySubtitles && !(showScript && scripts.isNotEmpty())

    // Continuous finger-following pull-down dismiss across ALL player modes.
    val dismissOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { 88.dp.toPx() }
    val dismissDragModifier =
        Modifier.pointerInput(back, dismissThresholdPx, reduced) {
            val velocityTracker = VelocityTracker()
            var crossed = false
            detectVerticalDragGestures(
                onDragStart = {
                    velocityTracker.resetTracking()
                    crossed = false
                },
                onDragEnd = {
                    val velocityY = velocityTracker.calculateVelocity().y
                    if (dismissOffsetY.value >= dismissThresholdPx || velocityY > 1100f) {
                        back()
                    } else {
                        scope.launch {
                            dismissOffsetY.animateTo(
                                0f,
                                if (reduced) snap()
                                else
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                            )
                        }
                    }
                },
                onDragCancel = {
                    scope.launch {
                        dismissOffsetY.animateTo(
                            0f,
                            if (reduced) snap()
                            else
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                        )
                    }
                },
                onVerticalDrag = { change, delta ->
                    val next = (dismissOffsetY.value + delta).coerceAtLeast(0f)
                    if (next > 0f || dismissOffsetY.value > 0f) {
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { dismissOffsetY.snapTo(next) }
                        val nowCrossed = next >= dismissThresholdPx
                        if (nowCrossed && !crossed) {
                            crossed = true
                            haptics.tick()
                        } else if (!nowCrossed) {
                            crossed = false
                        }
                    }
                },
            )
        }

    if (fullscreen && showVideo) {
        val timeline = playback.positionMs.collectAsStateWithLifecycle()
        val caption by
            remember(
                cues,
                track.id,
                state.trackId,
                fallbackPosition,
                settings.subtitleOffsetMs,
                embeddedSubtitle,
            ) {
                derivedStateOf {
                    val pos = if (state.trackId == track.id) timeline.value else fallbackPosition
                    val current =
                        SubtitleTimeline.current(
                            cues,
                            (pos - settings.subtitleOffsetMs).coerceAtLeast(0),
                        )
                    cues.getOrNull(current)?.text
                        ?: if (track.subtitleId == null) embeddedSubtitle else ""
                }
            }
        FullScreenVideo(
            playback = playback,
            trackId = track.id,
            title = track.title,
            state = state,
            active = shownTrack != null,
            caption = caption,
            subtitleSize = settings.subtitleSize,
            skipSeconds = settings.skipSeconds,
            exit = { fullscreen = false },
            timeline = { onScrubbingChange ->
                PlayerTimeline(
                    track = track,
                    state = state,
                    shownTrack = shownTrack,
                    markA = markA,
                    fallbackPosition = fallbackPosition,
                    playback = playback,
                    cues = cues,
                    subtitleOffsetMs = settings.subtitleOffsetMs,
                    onScrubPreview = {},
                    onScrubbingChange = onScrubbingChange,
                )
            },
        )
    } else {
        val dismissRangePx = with(density) { 260.dp.toPx() }
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val offsetY = dismissOffsetY.value
                    val dragFraction = (offsetY / dismissRangePx).coerceIn(0f, 1f)
                    val dismissScale = 1f - dragFraction * 0.06f
                    translationY = offsetY
                    scaleX = dismissScale
                    scaleY = dismissScale
                    if (offsetY > 1f) {
                        val dismissCorner = (dragFraction * 28f).dp
                        shape = RoundedCornerShape(topStart = dismissCorner, topEnd = dismissCorner)
                        clip = true
                    }
                }
                .background(bg)
                .drawWithCache {
                    val brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    ambient.copy(alpha = if (amoled) 0.18f else 0.34f),
                                    Color.Transparent,
                                ),
                            center = Offset(size.width * 0.5f, size.height * 0.18f),
                            radius = maxOf(size.width, size.height) * 0.72f,
                        )
                    onDrawBehind { drawRect(brush = brush) }
                }
                .navigationBarsPadding(),
        ) {
            val width = maxWidth
            val height = maxHeight
            val onToggleFavorite: () -> Unit = {
                scope.launch { graph.library.toggleFavorite(detail.work.id) }
            }
            val onOpenWorkDetail: () -> Unit = { openDetail(detail.work.id) }
            val onDirectSeekCue: (Cue) -> Unit = { cue ->
                val targetPos = (cue.startMs + settings.subtitleOffsetMs).coerceAtLeast(0L)
                haptics.tick()
                followSignal++
                if (shownTrack != null) {
                    playback.seek(targetPos)
                    if (!state.playWhenReady) playback.togglePlay()
                } else {
                    playback.playWork(detail, track.id, startPosition = targetPos)
                }
            }
            val controls: @Composable (Modifier, Boolean) -> Unit = { mod, showTrackHeader ->
                PlayerControls(
                    detail = detail,
                    track = track,
                    coverImage = coverImage,
                    state = state,
                    shownTrack = shownTrack,
                    opening = opening,
                    cues = cues,
                    loading = loading,
                    subtitleError = subtitleError,
                    embeddedSubtitle = embeddedSubtitle,
                    settings = settings,
                    markA = markA,
                    lastClearedLoop = lastClearedLoop,
                    inlineAbBar = inlineAbBar,
                    fallbackPosition = fallbackPosition,
                    hasQueue = queueTracks.isNotEmpty(),
                    coverFocusMode = coverFocusMode && width <= height,
                    showTrackHeader = showTrackHeader,
                    showScript = showScript && scripts.isNotEmpty(),
                    scripts = scripts,
                    preferredScriptId = preferredScriptId,
                    video = video,
                    followSignal = followSignal,
                    dismissDragModifier = dismissDragModifier,
                    graph = graph,
                    playback = playback,
                    onToggleFavorite = onToggleFavorite,
                    onOpenWorkDetail = onOpenWorkDetail,
                    onAmbientColor = { ambientSeed = it },
                    onDirectSeekCue = onDirectSeekCue,
                    onLongPressSubtitle = { subtitleAction = it },
                    onSaveLoopAsBookmark = {
                        val cueIndex =
                            SubtitleTimeline.anchor(
                                cues,
                                (state.loopStart - settings.subtitleOffsetMs).coerceAtLeast(0),
                            )
                        bookmark =
                            BookmarkDraft.forLoop(
                                track.id,
                                state.loopStart,
                                state.loopEnd,
                                cues.getOrNull(cueIndex)?.text
                                    ?: "${clock(state.loopStart)}–${clock(state.loopEnd)}",
                            )
                    },
                    onToggleInlineAb = { inlineAbBar = !inlineAbBar },
                    onOpenQueue = { queue = true },
                    onOpenFragments = { fragments = true },
                    onOpenTimer = { timer = true },
                    onOpenPreferences = { preferences = true },
                    onOpenMore = { more = true },
                    modifier = mod,
                )
            }
            if (width > height) {
                Row(Modifier.fillMaxSize().displayCutoutPadding()) {
                    if (showVideo) {
                        PlayerCover(
                            detail = detail,
                            coverImage = coverImage,
                            track = track,
                            state = state,
                            shownTrack = shownTrack,
                            video = video,
                            showVideo = true,
                            audioOnly = audioOnly,
                            hasScripts = scripts.isNotEmpty(),
                            showScript = showScript,
                            playback = playback,
                            dismissDragModifier = dismissDragModifier,
                            onToggleScript = { showScript = !showScript },
                            onOpenWorkDetail = onOpenWorkDetail,
                            onAmbientColor = { ambientSeed = it },
                            back = back,
                            onFullscreen = { fullscreen = true },
                            modifier = Modifier.fillMaxHeight().width(width * .55f),
                        )
                    } else {
                        LandscapeCoverPane(
                            detail = detail,
                            coverImage = coverImage,
                            video = video,
                            audioOnly = audioOnly,
                            hasScripts = scripts.isNotEmpty(),
                            showScript = showScript,
                            playback = playback,
                            dismissDragModifier = dismissDragModifier,
                            onToggleScript = { showScript = !showScript },
                            onOpenWorkDetail = onOpenWorkDetail,
                            onAmbientColor = { ambientSeed = it },
                            back = back,
                            modifier = Modifier.fillMaxHeight().width(width * .40f),
                        )
                    }
                    controls(Modifier.weight(1f).fillMaxHeight().statusBarsPadding(), true)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (coverFocusMode) {
                        PlayerTopBar(
                            video = video,
                            audioOnly = audioOnly,
                            hasScripts = scripts.isNotEmpty(),
                            showScript = showScript,
                            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
                            onToggleScript = { showScript = !showScript },
                            onOpenWorkDetail = onOpenWorkDetail,
                            back = back,
                            overlayBackdrop = false,
                            modifier = dismissDragModifier,
                        )
                        controls(Modifier.weight(1f).fillMaxWidth(), false)
                    } else {
                        PlayerCover(
                            detail = detail,
                            coverImage = coverImage,
                            track = track,
                            state = state,
                            shownTrack = shownTrack,
                            video = video,
                            showVideo = showVideo,
                            audioOnly = audioOnly,
                            hasScripts = scripts.isNotEmpty(),
                            showScript = showScript,
                            playback = playback,
                            dismissDragModifier = dismissDragModifier,
                            onToggleScript = { showScript = !showScript },
                            onOpenWorkDetail = onOpenWorkDetail,
                            onAmbientColor = { ambientSeed = it },
                            back = back,
                            onFullscreen = { fullscreen = true },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(
                                        if (showVideo)
                                            minOf(
                                                width / state.videoAspectRatio.coerceIn(.5f, 3f),
                                                height * .43f,
                                            )
                                        else height * .28f,
                                    ),
                        )
                        controls(Modifier.weight(1f).fillMaxWidth(), true)
                    }
                }
            }
            // Non-occluding live subtitle adjustment bar over bottom controls only.
            AnimatedVisibility(
                visible = subtitleTune,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter =
                    fadeIn(tween(if (reduced) 0 else 150)) +
                        slideInVertically(tween(if (reduced) 0 else 180)) { it / 2 },
                exit =
                    fadeOut(tween(if (reduced) 0 else 120)) +
                        slideOutVertically(tween(if (reduced) 0 else 150)) { it / 2 },
            ) {
                LiveSubtitleTuneOverlay(
                    settings = settings,
                    onUpdateSettings = { transform ->
                        scope.launch { graph.settings.update(transform) }
                    },
                    onClose = { subtitleTune = false },
                )
            }
        }
    }

    if (queue) {
        PlayerQueueSheet(
            queueTracks = queueTracks,
            activeTrackId = state.trackId,
            onSelect = playback::selectQueuedTrack,
            onDismiss = { queue = false },
            detail = detail,
            onSwitchSectionTrack = { targetTrackId ->
                playback.playWork(detail, targetTrackId, startPosition = 0L)
            },
        )
    }
    if (fragments) {
        val workBookmarks by
            remember(detail.work.id) { graph.library.observeBookmarks(detail.work.id) }
                .collectAsStateWithLifecycle(initialValue = emptyList())
        PlayerFragmentsSheet(
            detail = detail,
            track = track,
            state = state,
            markA = markA,
            shownTrack = shownTrack,
            cues = cues,
            settings = settings,
            bookmarks = workBookmarks,
            playback = playback,
            onDeleteBookmark = { item ->
                scope.launch {
                    graph.library.deleteBookmark(item.id)
                    val res =
                        snackbarHost?.showSnackbar(
                            message = "已删除片段",
                            actionLabel = "撤销",
                            duration = SnackbarDuration.Short,
                        )
                    if (res == SnackbarResult.ActionPerformed) {
                        graph.library.saveBookmark(item)
                    }
                }
            },
            haptics = haptics,
            onCreateBookmark = { bookmark = it },
            onOpenInlineAb = { inlineAbBar = true },
            onDismiss = { fragments = false },
        )
    }
    if (timer) {
        SleepTimerDialog(
            timerEnd = state.timerEnd,
            stopAfterTrack = state.timerStopAfterTrack,
            haptics = haptics,
            onSelectMinutes = playback::setTimer,
            onExtendMinutes = playback::extendTimer,
            onStopAfterTrack = playback::setStopAfterTrack,
            onDismiss = { timer = false },
        )
    }
    bookmark?.let { draft ->
        SaveBookmarkDialog(
            draft = draft,
            haptics = haptics,
            onSave = { title ->
                scope.launch {
                    graph.library.saveBookmark(
                        BookmarkEntity(
                            id = UUID.randomUUID().toString(),
                            workId = detail.work.id,
                            trackId = draft.trackId,
                            positionMs = draft.position,
                            title = title.ifBlank { clock(draft.position) },
                            endMs = draft.end,
                        ),
                    )
                }
            },
            onDismiss = { bookmark = null },
        )
    }
    subtitleAction?.let { cue ->
        val cueStart = (cue.startMs + settings.subtitleOffsetMs).coerceAtLeast(0L)
        val cueEnd =
            cue.endMs
                .takeIf { it < Long.MAX_VALUE }
                ?.let { (it + settings.subtitleOffsetMs).coerceAtLeast(0L) }
                ?.takeIf { it - cueStart >= 500L }
        SubtitleActionSheet(
            cue = cue,
            subtitleSize = settings.subtitleSize,
            canSeek = shownTrack != null,
            onPlayFromHere = {
                followSignal++
                playback.playWork(detail, track.id, startPosition = cueStart)
            },
            onSaveCue = {
                bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
            },
            onMarkLoopStart = {
                playback.setLoopDraftStart(cueStart)
                inlineAbBar = true
                haptics.tick()
            },
            onLoopCue =
                cueEnd?.let { end ->
                    {
                        playback.setLoop(cueStart, end)
                        playback.seek(cueStart)
                        inlineAbBar = true
                        haptics.confirm()
                    }
                },
            onCopyCue = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("subtitle", cue.text))
                haptics.tick()
                graph.messages.tryEmit("已复制台词")
            },
            onDismiss = { subtitleAction = null },
        )
    }
    if (more) {
        PlayerMoreSheet(
            context = context,
            currentSpeed = state.speed,
            hasSubtitles = hasAnySubtitles,
            onSpeedChange = playback::setPlaybackSpeed,
            onOpenDetail = { openDetail(detail.work.id) },
            onSubtitleTune = { subtitleTune = true },
            onPreciseSeek = { precise = true },
            onPreferences = { preferences = true },
            onRestart = { playback.playWork(detail, restart = true) },
            onDismiss = { more = false },
        )
    }
    if (precise) {
        val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
        PreciseSeekDialog(
            trackId = track.id,
            duration = duration,
            canSeek = shownTrack != null,
            initialPosition = playback::currentPosition,
            onSeek = playback::seek,
            onDismiss = { precise = false },
        )
    }
    if (preferences) {
        PlaybackPreferencesDialog(
            settings = settings,
            currentSpeed = state.speed,
            onSpeedChange = playback::setPlaybackSpeed,
            onUpdateSettings = { transform -> scope.launch { graph.settings.update(transform) } },
            onDismiss = { preferences = false },
        )
    }
}

@Composable
private fun PlayerTopBar(
    video: Boolean,
    audioOnly: Boolean,
    hasScripts: Boolean,
    showScript: Boolean,
    onToggleAudioOnly: () -> Unit,
    onToggleScript: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    back: () -> Unit,
    overlayBackdrop: Boolean,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalCoverControls provides overlayBackdrop) {
        Row(
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.Outlined.KeyboardArrowDown,
                stringResource(R.string.action_collapse_player),
                haptic = HapticKind.Tick,
                action = back,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasScripts) {
                    IconAction(
                        Icons.Outlined.Description,
                        if (showScript) "关闭台本" else "查看台本",
                        tint = if (showScript) Rose else Cream,
                        haptic = HapticKind.Tick,
                        action = onToggleScript,
                    )
                }
                if (video) {
                    IconAction(
                        if (audioOnly) Icons.Outlined.Videocam else Icons.Outlined.Headphones,
                        if (audioOnly) "显示画面" else "只听声音",
                        haptic = HapticKind.Tick,
                        action = onToggleAudioOnly,
                    )
                }
                IconAction(
                    Icons.Outlined.Info,
                    "作品详情",
                    haptic = HapticKind.Tick,
                    action = onOpenWorkDetail,
                )
            }
        }
    }
}

@Composable
private fun LandscapeCoverPane(
    detail: WorkDetails,
    coverImage: WhispImage?,
    video: Boolean,
    audioOnly: Boolean,
    hasScripts: Boolean,
    showScript: Boolean,
    playback: PlaybackCoordinator,
    onToggleScript: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    back: () -> Unit,
    modifier: Modifier,
    dismissDragModifier: Modifier,
) {
    Column(modifier.padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerTopBar(
            video = video,
            audioOnly = audioOnly,
            hasScripts = hasScripts,
            showScript = showScript,
            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
            onToggleScript = onToggleScript,
            onOpenWorkDetail = onOpenWorkDetail,
            back = back,
            overlayBackdrop = false,
            modifier = dismissDragModifier,
        )
        Column(
            Modifier.weight(1f).then(dismissDragModifier).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val shape = RoundedCornerShape(20.dp)
            Artwork(
                image = coverImage,
                description = detail.work.title,
                modifier =
                    Modifier.fillMaxWidth()
                        .aspectRatio(1.25f)
                        .clip(shape)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                sharedKey = detail.work.id.takeUnless { video },
                onAmbientColor = onAmbientColor,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                shortTitle(detail.work.title).ifBlank { detail.work.title },
                style = MaterialTheme.typography.titleLarge,
                color = Cream,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenWorkDetail),
            )
            if (detail.work.artist.isNotBlank()) {
                Text(
                    detail.work.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayerCover(
    detail: WorkDetails,
    coverImage: WhispImage?,
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    video: Boolean,
    showVideo: Boolean,
    audioOnly: Boolean,
    hasScripts: Boolean,
    showScript: Boolean,
    playback: PlaybackCoordinator,
    onToggleScript: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    back: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier,
    dismissDragModifier: Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(MaterialTheme.colorScheme.surface).then(dismissDragModifier)) {
        if (showVideo) {
            VideoOutput(
                playback = playback,
                trackId = track.id,
                active = shownTrack != null,
                playing = state.playing,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Artwork(
                image = coverImage,
                description = detail.work.title,
                modifier = Modifier.matchParentSize(),
                sharedKey = detail.work.id.takeUnless { video },
                onAmbientColor = onAmbientColor,
            )
        }
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to bg.copy(alpha = .44f),
                        0.32f to Color.Transparent,
                        0.66f to bg.copy(alpha = .58f),
                        1.0f to bg,
                    ),
                ),
        )
        PlayerTopBar(
            video = video,
            audioOnly = audioOnly,
            hasScripts = hasScripts,
            showScript = showScript,
            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
            onToggleScript = onToggleScript,
            onOpenWorkDetail = onOpenWorkDetail,
            back = back,
            overlayBackdrop = true,
        )
        Text(
            shortTitle(detail.work.title).ifBlank { detail.work.title },
            style = MaterialTheme.typography.headlineSmall,
            color = Cream,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .padding(
                        start = 22.dp,
                        end = if (showVideo) 64.dp else 22.dp,
                        top = 12.dp,
                        bottom = 10.dp,
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenWorkDetail),
        )
        if (showVideo) {
            CompositionLocalProvider(LocalCoverControls provides true) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    IconAction(
                        Icons.Outlined.Fullscreen,
                        "全屏",
                        enabled = shownTrack != null,
                        haptic = HapticKind.Tick,
                    ) {
                        onFullscreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverFocusPane(
    detail: WorkDetails,
    track: TrackEntity,
    coverImage: WhispImage?,
    video: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    modifier: Modifier,
    dismissDragModifier: Modifier,
) {
    val trackTitle = (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title
    Column(
        modifier.testTag("subtitle-pane").then(dismissDragModifier).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Artwork(
            image = coverImage,
            description = detail.work.title,
            modifier =
                Modifier.widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(shape)
                    .border(0.5.dp, Cream.copy(alpha = 0.14f), shape),
            sharedKey = detail.work.id.takeUnless { video },
            onAmbientColor = onAmbientColor,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            shortTitle(detail.work.title).ifBlank { detail.work.title },
            style = MaterialTheme.typography.headlineSmall,
            color = Cream,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenWorkDetail),
        )
        if (detail.work.artist.isNotBlank()) {
            Text(
                detail.work.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                trackTitle,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                color = Rose.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconAction(
                if (detail.work.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                stringResource(R.string.action_favorite),
                if (detail.work.favorite) Rose else Muted,
                feedback = true,
                action = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun EmbeddedScriptPane(
    scripts: List<AttachmentEntity>,
    preferredId: String?,
    graph: AppGraph,
    encoding: String,
    modifier: Modifier,
) {
    var selectedId by
        rememberSaveable(scripts.map { it.id }) {
            mutableStateOf(preferredId ?: scripts.first().id)
        }
    val selected = scripts.firstOrNull { it.id == selectedId } ?: scripts.first()
    var rawBytes by remember(selected.id) { mutableStateOf<ByteArray?>(null) }
    var chunks by remember(selected.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(selected.id) { mutableStateOf(true) }
    var error by remember(selected.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(selected.id) {
        loading = true
        error = null
        try {
            rawBytes = graph.library.readAsset(selected.id, 64 * 1024 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "台本读取失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(rawBytes, encoding) {
        val bytes = rawBytes ?: return@LaunchedEffect
        chunks =
            withContext(Dispatchers.Default) {
                chunkLongText(TextEncoding.decode(bytes, encoding))
            }
    }

    Column(modifier.testTag("script-pane")) {
        if (scripts.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scripts, key = { it.id }) { item ->
                    FilterChip(
                        selected = item.id == selected.id,
                        onClick = { selectedId = item.id },
                        label = {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                loading ->
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        color = Rose,
                        strokeWidth = 2.dp,
                    )
                error != null ->
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                chunks != null ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        itemsIndexed(chunks!!, key = { idx, _ -> idx }) { _, chunk ->
                            Text(
                                chunk,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Cream.copy(alpha = 0.92f),
                                lineHeight = 28.sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun PlayerSubtitles(
    track: TrackEntity,
    activeTrackId: String?,
    cues: List<Cue>,
    loading: Boolean,
    subtitleError: String?,
    embeddedSubtitle: String,
    settings: Settings,
    fallbackPosition: Long,
    scrubPreviewMs: Long?,
    followSignal: Int,
    playback: PlaybackCoordinator,
    onDirectSeek: (Cue) -> Unit,
    onLongPress: (Cue) -> Unit,
    modifier: Modifier,
) {
    if (
        cues.isEmpty() &&
            track.subtitleId == null &&
            embeddedSubtitle.isNotBlank() &&
            activeTrackId == track.id
    ) {
        Box(modifier.testTag("subtitle-pane").padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                embeddedSubtitle,
                color = Cream,
                fontSize = settings.subtitleSize.sp,
                lineHeight = (settings.subtitleSize + 10).sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // Collecting positionMs inside PlayerSubtitles isolates timeline ticks from PlayerPage.
    val timeline = playback.positionMs.collectAsStateWithLifecycle()
    key(track.id) {
        val cueState by
            remember(
                cues,
                track.id,
                activeTrackId,
                fallbackPosition,
                settings.subtitleOffsetMs,
                scrubPreviewMs,
            ) {
                derivedStateOf(structuralEqualityPolicy()) {
                    val pos =
                        scrubPreviewMs
                            ?: if (activeTrackId == track.id) timeline.value else fallbackPosition
                    val position = (pos - settings.subtitleOffsetMs).coerceAtLeast(0)
                    SubtitleTimeline.anchor(cues, position) to
                        SubtitleTimeline.current(cues, position)
                }
            }
        SubtitlePane(
            cues = cues,
            loading = loading,
            error = subtitleError,
            anchor = cueState.first,
            active = cueState.second,
            size = settings.subtitleSize,
            followSignal = followSignal,
            previewing = scrubPreviewMs != null,
            modifier = modifier,
            onDirectSeek = onDirectSeek,
            onRetry = { playback.subtitles.retry(track.id) },
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun PlayerTimeline(
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    markA: Long,
    fallbackPosition: Long,
    playback: PlaybackCoordinator,
    cues: List<Cue> = emptyList(),
    subtitleOffsetMs: Long = 0L,
    onScrubPreview: (Long?) -> Unit = {},
    onScrubbingChange: ((Boolean) -> Unit)? = null,
) {
    val timeline = playback.positionMs.collectAsStateWithLifecycle()
    val buffered = playback.bufferedMs.collectAsStateWithLifecycle()
    var dragging by remember(track.id) { mutableStateOf<Long?>(null) }
    var fineBounds by remember(track.id) { mutableStateOf<LongRange?>(null) }
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    val positionProvider: () -> Long =
        remember(state.trackId, track.id, fallbackPosition) {
            { if (state.trackId == track.id) timeline.value else fallbackPosition }
        }
    val bufferedProvider: () -> Long =
        remember(state.trackId, track.id) {
            { if (state.trackId == track.id) buffered.value else 0L }
        }
    val previewCaptionProvider: ((Long) -> String?)? =
        remember(cues, subtitleOffsetMs) {
            if (cues.isEmpty()) null
            else
                { posMs ->
                    val adjusted = (posMs - subtitleOffsetMs).coerceAtLeast(0L)
                    val idx = SubtitleTimeline.current(cues, adjusted)
                    cues.getOrNull(idx)?.text
                }
        }
    Column(Modifier.padding(horizontal = 18.dp)) {
        key(track.id) {
            PrecisionSeekBar(
                positionProvider = positionProvider,
                duration = duration,
                enabled = shownTrack != null && duration > 0,
                loopStart = state.loopStart.takeIf { it >= 0 } ?: markA,
                loopEnd = state.loopEnd,
                onPreview = {
                    dragging = it
                    onScrubPreview(it)
                },
                onSeek = playback::seek,
                bufferedProvider = bufferedProvider,
                onFineBounds = { fineBounds = it },
                previewCaptionProvider = previewCaptionProvider,
                onScrubbingChange = onScrubbingChange,
            )
        }
        TimelineLabels(
            positionProvider = positionProvider,
            dragging = dragging,
            fineBounds = fineBounds,
            duration = duration,
        )
    }
}

@Composable
private fun TimelineLabels(
    positionProvider: () -> Long,
    dragging: Long?,
    fineBounds: LongRange?,
    duration: Long,
) {
    val displaySeconds by
        remember(positionProvider, dragging) {
            derivedStateOf { (dragging ?: positionProvider()).coerceAtLeast(0L) / 1000L }
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val leftLabel = fineBounds?.let { clock(it.first) } ?: clock(displaySeconds * 1000L)
        val rightLabel = fineBounds?.let { clock(it.last) } ?: clock(duration)
        Text(
            leftLabel,
            color = if (fineBounds != null) Rose.copy(alpha = 0.8f) else Muted,
            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
        )
        Text(
            rightLabel,
            color = if (fineBounds != null) Rose.copy(alpha = 0.8f) else Muted,
            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
        )
    }
}

@Composable
private fun ActiveModifiersRow(
    state: PlaybackState,
    markA: Long,
    onOpenPreferences: () -> Unit,
    onOpenTimer: () -> Unit,
    onToggleInlineAb: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.timerEnd, lifecycle) {
        if (state.timerEnd > 0L) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    nowElapsed = now
                    val remainingMs = state.timerEnd - now
                    if (remainingMs <= 0L) break
                    val delayMs = ((remainingMs - 1L) % 60_000L) + 1L
                    delay(delayMs.coerceAtLeast(1_000L))
                }
            }
        }
    }
    val showSpeed = abs(state.speed - 1f) > 0.01f
    val remainingMin = SleepTimer.remainingMinutes(state.timerEnd, nowElapsed)
    val showTimer = state.timerEnd > 0L && remainingMin > 0
    val showStopAfterTrack = state.timerStopAfterTrack
    val showLoop = state.loopStart >= 0L || markA >= 0L
    if (!showSpeed && !showTimer && !showStopAfterTrack && !showLoop) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpeed) {
            StatusPill(text = formatSpeedLabel(state.speed), onClick = onOpenPreferences)
        }
        if (showLoop) {
            val text =
                if (state.loopStart >= 0L) "A–B ${clock(state.loopStart)}–${clock(state.loopEnd)}"
                else "A ${clock(markA)}"
            StatusPill(text = text, onClick = onToggleInlineAb)
        }
        if (showTimer) {
            StatusPill(text = "定时 ${remainingMin}分", onClick = onOpenTimer)
        } else if (showStopAfterTrack) {
            StatusPill(text = "播完本曲停", onClick = onOpenTimer)
        }
    }
}

@Composable
private fun StatusPill(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    // Enlarged 36dp minimum touch area while preserving compact visual capsule.
    Box(
        Modifier.heightIn(min = 36.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clip(shape)
                .background(Rose.copy(alpha = 0.14f))
                .border(0.5.dp, Rose.copy(alpha = 0.32f), shape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text,
                color = Rose,
                style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
            )
        }
    }
}

@Composable
private fun InlineAbLoopBar(
    state: PlaybackState,
    markA: Long,
    lastCleared: LoopState?,
    enabled: Boolean,
    playback: PlaybackCoordinator,
    onSaveLoop: () -> Unit,
    onClose: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    val shape = RoundedCornerShape(14.dp)
    if (state.loopStart < 0L && markA < 0L && lastCleared != null) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已退出循环 ${clock(lastCleared.startMs)}–${clock(lastCleared.endMs)}",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                color = Muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = {
                    haptics.confirm()
                    playback.restoreLastClearedLoop()
                },
            ) {
                Text("恢复")
            }
            IconAction(Icons.Outlined.Close, "关闭提示", Muted, action = playback::dismissClearedLoop)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, Rose.copy(alpha = 0.28f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when {
                    state.loopStart >= 0L ->
                        "A–B ${clock(state.loopStart)} – ${clock(state.loopEnd)}"
                    markA >= 0L -> "起点 A ${clock(markA)}"
                    else -> "A–B 循环"
                },
                style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                color = Rose,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.loopStart >= 0L) {
                IconAction(
                    Icons.Outlined.BookmarkAdd,
                    "存为循环片段",
                    Rose,
                    haptic = HapticKind.Tick,
                    action = onSaveLoop,
                )
            }
            IconAction(
                Icons.Outlined.Close,
                "关闭 A–B 条",
                Muted,
                haptic = HapticKind.Tick,
            ) {
                playback.clearLoop()
                onClose()
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.loopStart >= 0L) {
                SmallStepButton("A−0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaStartMs = -500L)
                }
                SmallStepButton("A+0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaStartMs = 500L)
                }
                Spacer(Modifier.weight(1f))
                SmallStepButton("B−0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaEndMs = -500L)
                }
                SmallStepButton("B+0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaEndMs = 500L)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        playback.markLoopStart()
                        haptics.tick()
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (markA >= 0L) "重设 A" else "设起点 A", fontSize = 13.sp)
                }
                Button(
                    onClick = { if (playback.markLoopEnd()) haptics.confirm() },
                    enabled = enabled && markA >= 0L,
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("设终点 B", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.height(34.dp)
            .clip(shape)
            .background(Rose.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
            color = Cream,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerControls(
    detail: WorkDetails,
    track: TrackEntity,
    coverImage: WhispImage?,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    opening: String?,
    cues: List<Cue>,
    loading: Boolean,
    subtitleError: String?,
    embeddedSubtitle: String,
    settings: Settings,
    markA: Long,
    lastClearedLoop: LoopState?,
    inlineAbBar: Boolean,
    fallbackPosition: Long,
    hasQueue: Boolean,
    coverFocusMode: Boolean,
    showTrackHeader: Boolean,
    showScript: Boolean,
    scripts: List<AttachmentEntity>,
    preferredScriptId: String?,
    video: Boolean,
    followSignal: Int,
    graph: AppGraph,
    playback: PlaybackCoordinator,
    onToggleFavorite: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    onDirectSeekCue: (Cue) -> Unit,
    onLongPressSubtitle: (Cue) -> Unit,
    onSaveLoopAsBookmark: () -> Unit,
    onToggleInlineAb: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier,
    dismissDragModifier: Modifier,
) {
    val repeatIcon =
        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne
        else Icons.Outlined.Repeat
    val repeatLabel =
        when (state.repeatMode) {
            Player.REPEAT_MODE_ALL -> stringResource(R.string.action_repeat_all)
            Player.REPEAT_MODE_ONE -> stringResource(R.string.action_repeat_one)
            else -> stringResource(R.string.action_repeat_off)
        }
    val reduced = LocalReducedMotion.current
    val amoled = LocalAmoledBlack.current
    val haptics = rememberWhispHaptics()
    var scrubPreviewMs by remember(track.id) { mutableStateOf<Long?>(null) }

    fun jumpCue(delta: Int) {
        if (cues.isEmpty() || shownTrack == null) return
        val adjusted = (playback.currentPosition() - settings.subtitleOffsetMs).coerceAtLeast(0L)
        val current = SubtitleTimeline.anchor(cues, adjusted)
        val target = (current + delta).coerceIn(0, cues.lastIndex)
        onDirectSeekCue(cues[target])
    }

    Column(modifier) {
        if (showTrackHeader) {
            Row(
                Modifier.fillMaxWidth()
                    .then(dismissDragModifier)
                    .padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title,
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumeralStyle),
                    color = Cream.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconAction(
                    if (detail.work.favorite) Icons.Filled.Favorite
                    else Icons.Outlined.FavoriteBorder,
                    stringResource(R.string.action_favorite),
                    if (detail.work.favorite) Rose else Muted,
                    feedback = true,
                    action = onToggleFavorite,
                )
            }
        }
        when {
            showScript && scripts.isNotEmpty() ->
                EmbeddedScriptPane(
                    scripts = scripts,
                    preferredId = preferredScriptId,
                    graph = graph,
                    encoding = settings.textEncoding,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            coverFocusMode ->
                CoverFocusPane(
                    detail = detail,
                    track = track,
                    coverImage = coverImage,
                    video = video,
                    dismissDragModifier = dismissDragModifier,
                    onToggleFavorite = onToggleFavorite,
                    onOpenWorkDetail = onOpenWorkDetail,
                    onAmbientColor = onAmbientColor,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            else ->
                PlayerSubtitles(
                    track = track,
                    activeTrackId = state.trackId,
                    cues = cues,
                    loading = loading,
                    subtitleError = subtitleError,
                    embeddedSubtitle = embeddedSubtitle,
                    settings = settings,
                    fallbackPosition = fallbackPosition,
                    scrubPreviewMs = scrubPreviewMs,
                    followSignal = followSignal,
                    playback = playback,
                    onDirectSeek = onDirectSeekCue,
                    onLongPress = onLongPressSubtitle,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
        }
        if (inlineAbBar || lastClearedLoop != null) {
            InlineAbLoopBar(
                state = state,
                markA = markA,
                lastCleared = lastClearedLoop,
                enabled = shownTrack != null,
                playback = playback,
                onSaveLoop = onSaveLoopAsBookmark,
                onClose = { onToggleInlineAb() },
            )
        } else {
            ActiveModifiersRow(
                state = state,
                markA = markA,
                onOpenPreferences = onOpenPreferences,
                onOpenTimer = onOpenTimer,
                onToggleInlineAb = onToggleInlineAb,
            )
        }
        PlayerTimeline(
            track = track,
            state = state,
            shownTrack = shownTrack,
            markA = markA,
            fallbackPosition = fallbackPosition,
            playback = playback,
            cues = cues,
            subtitleOffsetMs = settings.subtitleOffsetMs,
            onScrubPreview = { scrubPreviewMs = it },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconAction(
                Icons.Outlined.SkipPrevious,
                stringResource(R.string.action_previous_track),
                enabled = opening == null && (state.hasPrevious || shownTrack != null),
                haptic = HapticKind.Tick,
                action = playback::previous,
            )
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = shownTrack != null,
                        onClickLabel = "后退${settings.skipSeconds}秒",
                        onLongClickLabel = if (cues.isNotEmpty()) "上一句字幕" else null,
                        onClick = {
                            haptics.tick()
                            playback.skip(-settings.skipSeconds * 1000L)
                        },
                        onLongClick =
                            if (cues.isNotEmpty()) {
                                { jumpCue(-1) }
                            } else null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    rewindIconFor(settings.skipSeconds),
                    stringResource(R.string.action_rewind_10),
                    tint = if (shownTrack != null) Cream else Cream.copy(alpha = 0.35f),
                )
            }
            Box(contentAlignment = Alignment.Center) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by
                    animateFloatAsState(
                        if (pressed && !reduced) .92f else 1f,
                        if (reduced) snap()
                        else
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessHigh,
                            ),
                        label = "play-press",
                    )
                val glowAlpha by
                    animateFloatAsState(
                        if (state.playing) (if (amoled) 0.12f else 0.22f) else 0f,
                        tween(if (reduced) 0 else 280),
                        label = "play-glow",
                    )
                if (glowAlpha > 0.01f) {
                    Box(
                        Modifier.size(74.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Rose.copy(alpha = glowAlpha), Color.Transparent),
                                ),
                            ),
                    )
                }
                FilledIconButton(
                    onClick = {
                        haptics.confirm()
                        playback.togglePlay()
                    },
                    modifier = Modifier.size(62.dp),
                    shape = CircleShape,
                    interactionSource = interaction,
                    colors =
                        if (amoled)
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = RoseContainer,
                                contentColor = Rose,
                            )
                        else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    AnimatedContent(
                        targetState = state.playWhenReady,
                        transitionSpec = {
                            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            else fadeIn(tween(120)) togetherWith fadeOut(tween(90))
                        },
                        label = "play-pause-icon",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            playControlLabel(state),
                            Modifier.size(32.dp).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                }
                if (state.buffering) {
                    CircularProgressIndicator(
                        Modifier.size(70.dp),
                        color = Rose,
                        trackColor = Rose.copy(alpha = 0.18f),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
            Box(
                Modifier.size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = shownTrack != null,
                        onClickLabel = "前进${settings.skipSeconds}秒",
                        onLongClickLabel = if (cues.isNotEmpty()) "下一句字幕" else null,
                        onClick = {
                            haptics.tick()
                            playback.skip(settings.skipSeconds * 1000L)
                        },
                        onLongClick =
                            if (cues.isNotEmpty()) {
                                { jumpCue(1) }
                            } else null,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    forwardIconFor(settings.skipSeconds),
                    stringResource(R.string.action_forward_10),
                    tint = if (shownTrack != null) Cream else Cream.copy(alpha = 0.35f),
                )
            }
            IconAction(
                Icons.Outlined.SkipNext,
                stringResource(R.string.action_next_track),
                enabled = opening == null && state.hasNext,
                haptic = HapticKind.Tick,
                action = playback::next,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.AutoMirrored.Outlined.QueueMusic,
                stringResource(R.string.action_queue),
                Muted,
                enabled = opening == null && hasQueue,
                haptic = HapticKind.Tick,
                action = onOpenQueue,
            )
            IconAction(
                repeatIcon,
                repeatLabel,
                if (state.repeatMode != Player.REPEAT_MODE_OFF) Rose else Muted,
                enabled = shownTrack != null,
                haptic = HapticKind.Tick,
                action = playback::cycleRepeatMode,
            )
            IconAction(
                Icons.Outlined.Bookmarks,
                stringResource(R.string.action_fragments),
                if (state.loopStart >= 0 || markA >= 0) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenFragments,
            )
            IconAction(
                Icons.Outlined.Bedtime,
                stringResource(R.string.action_sleep_timer),
                if (state.timerEnd > 0 || state.timerStopAfterTrack) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenTimer,
            )
            IconAction(
                Icons.Outlined.MoreHoriz,
                stringResource(R.string.action_more_playback),
                if (abs(state.speed - 1f) > 0.01f) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenMore,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtitlePane(
    cues: List<Cue>,
    loading: Boolean,
    error: String?,
    anchor: Int,
    active: Int,
    size: Int,
    followSignal: Int,
    previewing: Boolean,
    modifier: Modifier,
    onDirectSeek: (Cue) -> Unit,
    onRetry: () -> Unit,
    onLongPress: (Cue) -> Unit,
) {
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    var previousAnchor by remember { mutableIntStateOf(-1) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(followSignal) { if (followSignal > 0) following = true }
    BoxWithConstraints(modifier.testTag("subtitle-pane"), contentAlignment = Alignment.Center) {
        val inset = maxHeight * .24f
        val effectiveFollowing = following || previewing
        LaunchedEffect(anchor, effectiveFollowing, cues, inset, reduced, previewing) {
            if (!effectiveFollowing) {
                previousAnchor = -1
                return@LaunchedEffect
            }
            if (anchor < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor }
            val animate =
                !previewing && SubtitleFollow.animate(previousAnchor, anchor, item != null, reduced)
            previousAnchor = anchor
            if (animate && item != null) {
                list.animateScrollBy(
                    item.offset.toFloat(),
                    tween(180, easing = FastOutSlowInEasing),
                )
            } else {
                list.scrollToItem(anchor)
            }
        }
        if (cues.isEmpty()) {
            if (loading)
                CircularProgressIndicator(Modifier.size(24.dp), color = Rose, strokeWidth = 2.dp)
            else if (error != null)
                FilledTonalButton(onClick = onRetry) { Text("重试字幕", fontSize = 15.sp) }
            else Text("暂无字幕", color = Muted.copy(alpha = 0.7f), fontSize = 16.sp)
        } else {
            val bg = MaterialTheme.colorScheme.background
            val cueShape = remember { RoundedCornerShape(12.dp) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = inset,
                        bottom = maxHeight * .65f,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(cues, key = { index, _ -> index }) { index, cue ->
                    val isActive = index == active
                    val targetColor =
                        when {
                            isActive -> Cream
                            active >= 0 && index < active -> Muted.copy(alpha = 0.42f)
                            else -> Muted.copy(alpha = 0.68f)
                        }
                    val color by
                        animateColorAsState(
                            targetColor,
                            tween(if (reduced) 0 else 140),
                            label = "subtitle-highlight",
                        )
                    val highlightAlphaState =
                        animateFloatAsState(
                            if (isActive) 0.11f else 0f,
                            tween(if (reduced) 0 else 160),
                            label = "subtitle-bg",
                        )
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(cueShape)
                            .drawBehind {
                                val alpha = highlightAlphaState.value
                                if (alpha > 0.001f) drawRect(Rose.copy(alpha = alpha))
                            }
                            .combinedClickable(
                                hapticFeedbackEnabled = LocalTouchFeedback.current,
                                onClickLabel = "跳转到这句",
                                onClick = { onDirectSeek(cue) },
                                onLongClickLabel = "字幕操作",
                                onLongClick = { onLongPress(cue) },
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            cue.text,
                            color = color,
                            fontSize = size.sp,
                            lineHeight = (size + 10).sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("subtitle-$index"),
                        )
                    }
                }
            }
            Box(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Brush.verticalGradient(listOf(bg, Color.Transparent))),
            )
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, bg))),
            )
        }
        AnimatedVisibility(
            visible = !following && !previewing && cues.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
            enter =
                fadeIn(tween(if (reduced) 0 else 150)) +
                    slideInVertically(tween(if (reduced) 0 else 150)) { it / 2 },
            exit =
                fadeOut(tween(if (reduced) 0 else 120)) +
                    slideOutVertically(tween(if (reduced) 0 else 120)) { it / 2 },
        ) {
            val pillShape = RoundedCornerShape(999.dp)
            Row(
                Modifier.clip(pillShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(0.5.dp, Rose.copy(alpha = 0.35f), pillShape)
                    .clickable { following = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.MyLocation,
                    "跟随字幕",
                    tint = Rose,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "回到当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cream,
                )
            }
        }
    }
}
