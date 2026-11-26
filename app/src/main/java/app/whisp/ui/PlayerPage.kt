// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Subtitle auto-scroll policy: animate only single-step transitions while visible. */
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
    val displayCues by playback.subtitles.displayCues.collectAsStateWithLifecycle()
    val activePrimarySubId by playback.subtitles.activePrimaryId.collectAsStateWithLifecycle()
    val activeSecondarySubId by playback.subtitles.activeSecondaryId.collectAsStateWithLifecycle()
    val loading by playback.subtitles.loading.collectAsStateWithLifecycle()
    val subtitleError by playback.subtitles.error.collectAsStateWithLifecycle()
    val queueTracks by playback.queueTracks.collectAsStateWithLifecycle()
    val opening by playback.openingTrackId.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val trackState by
        remember(track?.id) {
                track?.id?.let { graph.library.observeTrackState(it) } ?: flowOf(null)
            }
            .collectAsStateWithLifecycle(initialValue = null)
    val audioOnly by playback.audioOnly.collectAsStateWithLifecycle()
    val embeddedSubtitle by playback.embeddedSubtitle.collectAsStateWithLifecycle()
    val markA by playback.loopDraftStart.collectAsStateWithLifecycle()
    val lastClearedLoop by playback.lastClearedLoop.collectAsStateWithLifecycle()
    val workProgress by
        remember(detail?.work?.id) {
                detail?.work?.id?.let { graph.library.observeProgress(it) } ?: flowOf(null)
            }
            .collectAsStateWithLifecycle(initialValue = null)
    val workBookmarks by
        remember(detail?.work?.id) {
                detail?.work?.id?.let { graph.library.observeBookmarks(it) } ?: flowOf(emptyList())
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var fragments by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var subtitleTune by rememberSaveable { mutableStateOf(false) }
    var subtitlePicker by rememberSaveable { mutableStateOf(false) }
    var subtitleSearch by rememberSaveable { mutableStateOf(false) }
    var inlineAbBar by rememberSaveable { mutableStateOf(false) }
    var showScript by rememberSaveable(detail?.work?.id) { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<BookmarkDraft?>(null) }
    var playlistRequest by remember { mutableStateOf<PlaylistAddRequest?>(null) }
    var subtitleAction by remember(track?.id) { mutableStateOf<Cue?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }
    var refreshingSource by remember { mutableStateOf(false) }
    var followSignal by remember(track?.id) { mutableIntStateOf(0) }

    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val audioOutput by graph.audioDevices.output.collectAsStateWithLifecycle()

    DisposableEffect(activity, view, settings.bedsideDimMode, fullscreen) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        val previousKeepOn = view.keepScreenOn
        if (settings.bedsideDimMode && !fullscreen && window != null) {
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            view.keepScreenOn = true
        }
        onDispose {
            if (
                window != null &&
                    originalBrightness != null &&
                    settings.bedsideDimMode &&
                    !fullscreen
            ) {
                val lp = window.attributes
                if (lp.screenBrightness != originalBrightness) {
                    lp.screenBrightness = originalBrightness
                    window.attributes = lp
                }
            }
            if (settings.bedsideDimMode && !fullscreen) {
                view.keepScreenOn = previousKeepOn
            }
        }
    }

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
            IconAction(WhispIcons.AutoMirrored.Outlined.ArrowBack, "返回", action = back)
        }
        return
    }

    // Only keep Media3's embedded text renderer active while the player screen is visible and no
    // external subtitle file is bound; disabling it in background/screen-off unlocks audio offload.
    val subtitleOwner = remember { Any() }
    val wantsEmbeddedSubtitles = track.subtitleId == null && activePrimarySubId == null
    LifecycleResumeEffect(playback, subtitleOwner, wantsEmbeddedSubtitles) {
        playback.subtitleVisibility(subtitleOwner, wantsEmbeddedSubtitles)
        onPauseOrDispose { playback.subtitleVisibility(subtitleOwner, false) }
    }

    val trackOffsetMs = trackState?.subtitleOffsetMs ?: 0L
    val trackBookmarks =
        remember(workBookmarks, track.id) { workBookmarks.filter { it.trackId == track.id } }
    val subtitleCandidates =
        remember(track.id, detail.attachments) {
            LibraryIndexer.subtitleCandidates(track, detail.attachments)
        }
    val hasSubtitleOverride = trackState?.subtitleSelectionOrNull() != null

    val scripts =
        remember(detail.attachments) {
            detail.attachments.filter { it.kind == "script" }.sortedBy { it.relativePath }
        }
    val preferredScriptId =
        remember(track.scriptId, scripts) {
            track.scriptId?.takeIf { id -> scripts.any { it.id == id } }
                ?: scripts.firstOrNull()?.id
        }

    val onRefreshOnlineWork: (() -> Unit)? =
        if (detail.work.originKind == SourceKind.ASMRONE && detail.work.sourceId != null) {
            {
                if (!refreshingSource) {
                    refreshingSource = true
                    scope.launch {
                        try {
                            val sourceId = detail.work.sourceId
                            val asmrId = detail.work.rj?.let(::asmrWorkNumber)
                            if (asmrId != null) {
                                graph.sync.importAsmrWork(sourceId, asmrId, existingOnly = true)
                                val updated =
                                    withContext(Dispatchers.IO) {
                                        graph.library.workDetails(detail.work.id)
                                    }
                                if (updated != null) {
                                    playback.playWork(
                                        updated,
                                        track.id,
                                        startPosition = playback.currentPosition(),
                                    )
                                }
                            } else {
                                playback.retryPlayback()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            graph.messages.tryEmit(e.message ?: "刷新作品地址失败")
                        } finally {
                            refreshingSource = false
                        }
                    }
                }
            }
        } else null

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
        Modifier.pointerInput(back, dismissThresholdPx, reduced, haptics) {
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
        val captionPair by
            remember(
                cues,
                displayCues,
                track.id,
                state.trackId,
                fallbackPosition,
                trackOffsetMs,
                embeddedSubtitle,
                activePrimarySubId,
            ) {
                derivedStateOf {
                    val pos = if (state.trackId == track.id) timeline.value else fallbackPosition
                    val current =
                        SubtitleTimeline.current(
                            cues,
                            (pos - trackOffsetMs).coerceAtLeast(0),
                        )
                    val matched = displayCues.getOrNull(current)
                    if (matched != null) matched.text to matched.secondaryText
                    else
                        (if (track.subtitleId == null && activePrimarySubId == null)
                            embeddedSubtitle
                        else "") to null
                }
            }
        val fullscreenPreviewCaption: ((Long) -> String?)? =
            remember(cues, trackBookmarks, trackOffsetMs) {
                if (cues.isEmpty() && trackBookmarks.isEmpty()) null
                else
                    { posMs ->
                        val nearBookmark =
                            trackBookmarks
                                .minByOrNull { abs(it.positionMs - posMs) }
                                ?.takeIf { abs(it.positionMs - posMs) <= 2_500L }
                        val adjusted = (posMs - trackOffsetMs).coerceAtLeast(0L)
                        val cueText =
                            SubtitleTimeline.current(cues, adjusted)
                                .takeIf { it >= 0 }
                                ?.let { cues[it].text }
                        cueText ?: nearBookmark?.let { "★ ${it.title}" }
                    }
            }
        FullScreenVideo(
            playback = playback,
            trackId = track.id,
            title = track.title,
            state = state,
            active = shownTrack != null,
            caption = captionPair.first,
            secondaryCaption = captionPair.second,
            subtitleSize = settings.subtitleSize,
            skipSeconds = settings.skipSeconds,
            hasQueue = queueTracks.isNotEmpty(),
            previewCaptionProvider = fullscreenPreviewCaption,
            onOpenQueue = { queue = true },
            exit = { fullscreen = false },
            timeline = { onScrubPreview, onScrubbingChange ->
                PlayerTimeline(
                    track = track,
                    state = state,
                    shownTrack = shownTrack,
                    markA = markA,
                    fallbackPosition = fallbackPosition,
                    playback = playback,
                    cues = cues,
                    bookmarks = trackBookmarks,
                    subtitleOffsetMs = trackOffsetMs,
                    onScrubPreview = onScrubPreview,
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
                val targetPos = (cue.startMs + trackOffsetMs).coerceAtLeast(0L)
                haptics.tick()
                followSignal++
                if (shownTrack != null) {
                    // Keep paused state intact when browsing subtitles silently; resume only if
                    // already playing.
                    playback.seek(targetPos)
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
                    displayCues = displayCues,
                    trackBookmarks = trackBookmarks,
                    trackOffsetMs = trackOffsetMs,
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
                    refreshingSource = refreshingSource,
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
                                (state.loopStart - trackOffsetMs).coerceAtLeast(0),
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
                    onCloseInlineAb = {
                        inlineAbBar = false
                        playback.dismissClearedLoop()
                    },
                    onOpenQueue = { queue = true },
                    onOpenFragments = { fragments = true },
                    onOpenTimer = { timer = true },
                    onOpenSubtitleTune = { subtitleTune = true },
                    onOpenPreferences = { preferences = true },
                    onOpenMore = { more = true },
                    onRefreshOnlineWork = onRefreshOnlineWork,
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
                    trackOffsetMs = trackOffsetMs,
                    onTrackOffsetChange = { nextOffset ->
                        scope.launch { graph.library.setTrackSubtitleOffset(track.id, nextOffset) }
                    },
                    onUpdateSettings = { transform ->
                        scope.launch { graph.settings.update(transform) }
                    },
                    hasCandidates = subtitleCandidates.isNotEmpty(),
                    onOpenPicker = { subtitlePicker = true },
                    onOpenSearch = { subtitleSearch = true },
                    onClose = { subtitleTune = false },
                )
            }
            if (settings.bedsideDimMode) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
            }
        }
    }

    if (queue) {
        PlayerQueueSheet(
            queueTracks = queueTracks,
            activeTrackId = state.trackId,
            shuffleEnabled = state.shuffleEnabled,
            onToggleShuffle = playback::toggleShuffle,
            onMoveTrack = playback::moveQueuedTrack,
            onSelect = playback::selectQueuedTrack,
            onDismiss = { queue = false },
            detail = detail,
            onSwitchSectionTrack = { targetTrackId ->
                val savedPos = graph.library.savedTrackPosition(targetTrackId)
                playback.playWork(detail, targetTrackId, startPosition = savedPos)
            },
        )
    }
    val importSubtitleLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    val fileName =
                        withContext(Dispatchers.IO) {
                            context.contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "external.vtt"
                    val added =
                        graph.offline.importExternalSubtitle(
                            workId = detail.work.id,
                            trackId = track.id,
                            uri = uri,
                            displayName = fileName,
                        )
                    graph.messages.tryEmit("已导入外挂字幕：${added.fileName}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.messages.tryEmit(e.message ?: "导入字幕失败")
                }
            }
        }

    if (fragments) {
        PlayerFragmentsSheet(
            detail = detail,
            track = track,
            state = state,
            markA = markA,
            shownTrack = shownTrack,
            cues = cues,
            trackOffsetMs = trackOffsetMs,
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
            onAddBookmarkToPlaylist = { item ->
                playlistRequest =
                    PlaylistAddRequest(
                        workId = detail.work.id,
                        trackIds = listOf(item.trackId),
                        startMs = item.positionMs,
                        endMs = item.endMs,
                        label = item.title,
                    )
            },
            haptics = haptics,
            onCreateBookmark = { bookmark = it },
            onOpenInlineAb = { inlineAbBar = true },
            onDismiss = { fragments = false },
        )
    }
    playlistRequest?.let { req ->
        AddToPlaylistSheet(
            graph = graph,
            request = req,
            onDismiss = { playlistRequest = null },
        )
    }
    if (timer) {
        SleepTimerDialog(
            timerEnd = state.timerEnd,
            stopAfterTrack = state.timerStopAfterTrack,
            sleepFadeSeconds = settings.sleepFadeSeconds,
            shakeExtendTimer = settings.shakeExtendTimer,
            timerStartAnchor = state.timerStartAnchor,
            haptics = haptics,
            onSelectMinutes = playback::setTimer,
            onExtendMinutes = playback::extendTimer,
            onStopAfterTrack = playback::setStopAfterTrack,
            onSelectFadeSeconds = { sec ->
                scope.launch { graph.settings.update { it.copy(sleepFadeSeconds = sec) } }
            },
            onToggleShakeExtend = { enabled ->
                scope.launch { graph.settings.update { it.copy(shakeExtendTimer = enabled) } }
            },
            onJumpToStartAnchor = playback::jumpToTimerStartAnchor,
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
        val cueStart = (cue.startMs + trackOffsetMs).coerceAtLeast(0L)
        val cueEnd =
            cue.endMs
                .takeIf { it < Long.MAX_VALUE }
                ?.let { (it + trackOffsetMs).coerceAtLeast(0L) }
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
                bookmark = BookmarkDraft.fromCue(track.id, cue, trackOffsetMs)
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
                copyTextWithPrivacy(
                    context = context,
                    label = "subtitle",
                    text = cue.text,
                    sensitive =
                        settings.hideArtwork || settings.hideRecents || settings.appLockEnabled,
                )
                haptics.tick()
                graph.messages.tryEmit("已复制台词")
            },
            onDismiss = { subtitleAction = null },
        )
    }
    if (subtitlePicker) {
        SubtitlePickerSheet(
            candidates = subtitleCandidates,
            activePrimaryId = activePrimarySubId ?: track.subtitleId,
            activeSecondaryId = activeSecondarySubId,
            hasOverride = hasSubtitleOverride,
            onSelect = { primaryId, secondaryId ->
                scope.launch {
                    graph.library.setTrackSubtitleSelection(
                        track.id,
                        TrackSubtitleSelection(primaryId, secondaryId),
                    )
                }
            },
            onResetAuto = {
                scope.launch { graph.library.setTrackSubtitleSelection(track.id, null) }
            },
            onImportExternal = { importSubtitleLauncher.launch(arrayOf("*/*")) },
            onDismiss = { subtitlePicker = false },
        )
    }
    if (subtitleSearch && (displayCues.isNotEmpty() || subtitleCandidates.isNotEmpty())) {
        SubtitleSearchSheet(
            cues = displayCues,
            offsetMs = trackOffsetMs,
            onJump = { cue ->
                val targetPos = (cue.startMs + trackOffsetMs).coerceAtLeast(0L)
                followSignal++
                if (shownTrack != null) playback.seek(targetPos)
                else playback.playWork(detail, track.id, startPosition = targetPos)
            },
            onSearchWork = { q -> playback.subtitles.searchWorkSubtitles(detail, q) },
            onJumpTrack = { targetTrack, cue ->
                val offset = graph.library.subtitleOffsetFor(targetTrack.id)
                val targetPos = (cue.startMs + offset).coerceAtLeast(0L)
                followSignal++
                playback.playWork(detail, targetTrack.id, startPosition = targetPos)
            },
            onDismiss = { subtitleSearch = false },
        )
    }
    if (more) {
        PlayerMoreSheet(
            context = context,
            currentSpeed = state.speed,
            audioOutputLabel = audioOutput.label,
            bedsideDimMode = settings.bedsideDimMode,
            hasSubtitles = hasAnySubtitles,
            hasSubtitleCandidates = true,
            onSpeedChange = playback::setPlaybackSpeed,
            onToggleBedsideDim = {
                scope.launch {
                    graph.settings.update { it.copy(bedsideDimMode = !it.bedsideDimMode) }
                }
            },
            onOpenDetail = { openDetail(detail.work.id) },
            onSubtitleTune = { subtitleTune = true },
            onSubtitlePicker = { subtitlePicker = true },
            onSubtitleSearch = { subtitleSearch = true },
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
            trackOffsetMs = trackOffsetMs,
            workVolumeDb = detail.work.volumeOffsetDb,
            onWorkVolumeDbChange = { nextDb ->
                scope.launch { graph.library.updateWorkVolumeOffset(detail.work.id, nextDb) }
            },
            onTrackOffsetChange = { nextOffset ->
                scope.launch { graph.library.setTrackSubtitleOffset(track.id, nextOffset) }
            },
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
                WhispIcons.Outlined.KeyboardArrowDown,
                stringResource(R.string.action_collapse_player),
                haptic = HapticKind.Tick,
                action = back,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasScripts) {
                    IconAction(
                        WhispIcons.Outlined.Description,
                        if (showScript) "关闭台本" else "查看台本",
                        tint = if (showScript) Rose else Cream,
                        haptic = HapticKind.Tick,
                        action = onToggleScript,
                    )
                }
                if (video) {
                    IconAction(
                        if (audioOnly) WhispIcons.Outlined.Videocam
                        else WhispIcons.Outlined.Headphones,
                        if (audioOnly) "显示画面" else "只听声音",
                        haptic = HapticKind.Tick,
                        action = onToggleAudioOnly,
                    )
                }
                IconAction(
                    WhispIcons.Outlined.Info,
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
    val reduced = LocalReducedMotion.current
    var videoOverlayVisible by remember(track.id, showVideo) { mutableStateOf(true) }
    val showOverlay = !showVideo || videoOverlayVisible || !state.playing
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .then(dismissDragModifier)
            .then(
                if (showVideo) {
                    Modifier.pointerInput(shownTrack != null) {
                        detectTapGestures(
                            onTap = { videoOverlayVisible = !videoOverlayVisible },
                            onDoubleTap = { if (shownTrack != null) onFullscreen() },
                        )
                    }
                } else Modifier,
            ),
    ) {
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
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(if (reduced) 0 else 140)),
            exit = fadeOut(tween(if (reduced) 0 else 140)),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
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
                                WhispIcons.Outlined.Fullscreen,
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
    }
}

@Composable
internal fun CoverFocusPane(
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
                if (detail.work.favorite) WhispIcons.Filled.Favorite
                else WhispIcons.Outlined.FavoriteBorder,
                stringResource(R.string.action_favorite),
                if (detail.work.favorite) Rose else Muted,
                feedback = true,
                action = onToggleFavorite,
            )
        }
    }
}
