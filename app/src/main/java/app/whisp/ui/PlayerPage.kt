// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.whisp.R
import app.whisp.data.*

@Composable
internal fun PlayerPage(
    detail: WorkDetails?,
    track: TrackEntity?,
    model: AppViewModel,
    back: () -> Unit,
) {
    val state by model.player.collectAsStateWithLifecycle()
    val cues by model.cues.collectAsStateWithLifecycle()
    val loading by model.subtitleLoading.collectAsStateWithLifecycle()
    val subtitleError by model.subtitleError.collectAsStateWithLifecycle()
    val queueTracks by model.queueTracks.collectAsStateWithLifecycle()
    val opening by model.openingTrackId.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val audioOnly by model.audioOnly.collectAsStateWithLifecycle()
    val embeddedSubtitle by model.embeddedSubtitle.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = rememberWhispHaptics()
    val bookmarks by model.bookmarks.collectAsStateWithLifecycle()
    var queue by rememberSaveable { mutableStateOf(false) }
    var fragments by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<BookmarkDraft?>(null) }
    var subtitleAction by remember(track?.id) { mutableStateOf<Cue?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }
    val markA by model.loopDraftStart.collectAsStateWithLifecycle()
    val history by model.progress.collectAsStateWithLifecycle()
    val fallbackPosition =
        remember(history, track?.id) {
            history.firstOrNull { it.trackId == track?.id }?.positionMs ?: 0L
        }
    LaunchedEffect(track?.id, state.trackId) {
        if (state.trackId == null) model.loadSubtitles(track?.id)
    }
    val shownTrack = track?.takeIf { it.id == state.trackId && opening == null }
    if (detail == null || track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", action = back)
        }
        return
    }
    val video =
        if (state.trackId == track.id && state.tracksKnown) state.hasVideo else track.isVideo
    val showVideo = video && !audioOnly && (shownTrack != null || opening != null)
    LaunchedEffect(showVideo) { if (!showVideo) fullscreen = false }
    BackHandler(fullscreen && showVideo) { fullscreen = false }
    if (fullscreen && showVideo) {
        val timeline = model.position.collectAsStateWithLifecycle()
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
            model,
            track.id,
            track.title,
            state,
            shownTrack != null,
            caption,
            settings.subtitleSize,
            exit = { fullscreen = false },
            timeline = {
                PlayerTimeline(
                    track = track,
                    state = state,
                    shownTrack = shownTrack,
                    markA = markA,
                    fallbackPosition = fallbackPosition,
                    model = model,
                )
            },
        )
    } else
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
            val width = maxWidth
            val height = maxHeight
            if (width > height)
                Row(Modifier.fillMaxSize()) {
                    PlayerCover(
                        detail = detail,
                        track = track,
                        state = state,
                        shownTrack = shownTrack,
                        video = video,
                        showVideo = showVideo,
                        audioOnly = audioOnly,
                        model = model,
                        back = back,
                        onFullscreen = { fullscreen = true },
                        modifier =
                            Modifier.fillMaxHeight().width(width * if (showVideo) .55f else .38f),
                    )
                    PlayerControls(
                        track = track,
                        state = state,
                        shownTrack = shownTrack,
                        opening = opening,
                        cues = cues,
                        loading = loading,
                        subtitleError = subtitleError,
                        embeddedSubtitle = embeddedSubtitle,
                        settings = settings,
                        markA = markA,
                        fallbackPosition = fallbackPosition,
                        hasQueue = queueTracks.isNotEmpty(),
                        model = model,
                        onSelectSubtitle = { subtitleAction = it },
                        onBookmarkCue = { cue ->
                            bookmark =
                                BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                        },
                        onOpenQueue = { queue = true },
                        onOpenFragments = { fragments = true },
                        onOpenTimer = { timer = true },
                        onOpenMore = { more = true },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            else
                Column(Modifier.fillMaxSize()) {
                    PlayerCover(
                        detail = detail,
                        track = track,
                        state = state,
                        shownTrack = shownTrack,
                        video = video,
                        showVideo = showVideo,
                        audioOnly = audioOnly,
                        model = model,
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
                                    else height * .29f,
                                ),
                    )
                    PlayerControls(
                        track = track,
                        state = state,
                        shownTrack = shownTrack,
                        opening = opening,
                        cues = cues,
                        loading = loading,
                        subtitleError = subtitleError,
                        embeddedSubtitle = embeddedSubtitle,
                        settings = settings,
                        markA = markA,
                        fallbackPosition = fallbackPosition,
                        hasQueue = queueTracks.isNotEmpty(),
                        model = model,
                        onSelectSubtitle = { subtitleAction = it },
                        onBookmarkCue = { cue ->
                            bookmark =
                                BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                        },
                        onOpenQueue = { queue = true },
                        onOpenFragments = { fragments = true },
                        onOpenTimer = { timer = true },
                        onOpenMore = { more = true },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
        }
    if (queue)
        PlayerQueueSheet(
            queueTracks = queueTracks,
            activeTrackId = state.trackId,
            onSelect = model::selectQueuedTrack,
            onDismiss = { queue = false },
        )
    if (fragments)
        PlayerFragmentsSheet(
            detail = detail,
            track = track,
            state = state,
            markA = markA,
            shownTrack = shownTrack,
            cues = cues,
            settings = settings,
            bookmarks = bookmarks,
            model = model,
            haptics = haptics,
            onCreateBookmark = { bookmark = it },
            onDismiss = { fragments = false },
        )
    if (timer)
        SleepTimerDialog(
            timerEnd = state.timerEnd,
            haptics = haptics,
            onSelectMinutes = model::timer,
            onDismiss = { timer = false },
        )
    bookmark?.let { draft ->
        SaveBookmarkDialog(
            draft = draft,
            haptics = haptics,
            onSave = { title ->
                model.saveBookmark(title, draft.end, draft.position, draft.trackId)
            },
            onDismiss = { bookmark = null },
        )
    }
    subtitleAction?.let { cue ->
        SubtitleActionSheet(
            cue = cue,
            subtitleSize = settings.subtitleSize,
            canSeek = shownTrack != null,
            onPlayFromHere = {
                model.playWork(
                    detail,
                    track.id,
                    startPosition = cue.startMs + settings.subtitleOffsetMs,
                )
            },
            onSaveCue = {
                bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
            },
            onDismiss = { subtitleAction = null },
        )
    }
    if (more)
        PlayerMoreSheet(
            context = context,
            onPreciseSeek = { precise = true },
            onPreferences = { preferences = true },
            onRestart = { model.playWork(detail, restart = true) },
            onExportDiagnostics = model::exportPlaybackDiagnostics,
            onDismiss = { more = false },
        )
    if (precise) {
        val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
        PreciseSeekDialog(
            trackId = track.id,
            duration = duration,
            canSeek = shownTrack != null,
            initialPosition = model::currentPosition,
            onSeek = model::seek,
            onDismiss = { precise = false },
        )
    }
    if (preferences)
        PlaybackPreferencesDialog(
            settings = settings,
            currentSpeed = state.speed,
            model = model,
            onDismiss = { preferences = false },
        )
}

@Composable
private fun PlayerCover(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    video: Boolean,
    showVideo: Boolean,
    audioOnly: Boolean,
    model: AppViewModel,
    back: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Surface)) {
        if (showVideo)
            VideoOutput(
                model,
                track.id,
                shownTrack != null,
                state.playing,
                Modifier.matchParentSize(),
            )
        else
            Artwork(
                model,
                detail.work.coverId,
                detail.work.title,
                Modifier.matchParentSize(),
                sharedKey = detail.work.id.takeUnless { video },
            )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(listOf(Ink.copy(alpha = .12f), Color.Transparent, Ink)),
                ),
        )
        CompositionLocalProvider(LocalCoverControls provides true) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconAction(
                    Icons.Outlined.KeyboardArrowDown,
                    stringResource(R.string.action_collapse_player),
                    action = back,
                )
                Row {
                    if (video)
                        IconAction(
                            if (audioOnly) Icons.Outlined.Videocam else Icons.Outlined.Headphones,
                            if (audioOnly) "显示画面" else "只听声音",
                        ) {
                            model.setAudioOnly(!audioOnly)
                        }
                    IconAction(
                        if (detail.work.favorite) Icons.Outlined.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        stringResource(R.string.action_favorite),
                        if (detail.work.favorite) Rose else Cream,
                        enabled = true,
                        feedback = true,
                    ) {
                        model.favorite(detail.work.id)
                    }
                }
            }
        }
        Text(
            shortTitle(detail.work.title),
            fontSize = 25.sp,
            lineHeight = 32.sp,
            fontFamily = FontFamily.Serif,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .padding(
                        start = 24.dp,
                        end = if (showVideo) 64.dp else 24.dp,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
        )
        if (showVideo)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                IconAction(Icons.Outlined.Fullscreen, "全屏", enabled = shownTrack != null) {
                    onFullscreen()
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
    model: AppViewModel,
    onSelect: (Cue) -> Unit,
    onBookmark: (Cue) -> Unit,
    modifier: Modifier,
) {
    if (
        cues.isEmpty() &&
            track.subtitleId == null &&
            embeddedSubtitle.isNotBlank() &&
            activeTrackId == track.id
    ) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                embeddedSubtitle,
                fontSize = settings.subtitleSize.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // Collecting model.position inside PlayerSubtitles isolates 200ms ticks from the rest of
    // PlayerPage.
    val timeline = model.position.collectAsStateWithLifecycle()
    key(track.id) {
        val cueState by
            remember(cues, track.id, activeTrackId, fallbackPosition, settings.subtitleOffsetMs) {
                derivedStateOf(structuralEqualityPolicy()) {
                    val pos = if (activeTrackId == track.id) timeline.value else fallbackPosition
                    val position = (pos - settings.subtitleOffsetMs).coerceAtLeast(0)
                    SubtitleTimeline.anchor(cues, position) to
                        SubtitleTimeline.current(cues, position)
                }
            }
        SubtitlePane(
            cues,
            loading,
            subtitleError,
            cueState.first,
            cueState.second,
            settings.subtitleSize,
            modifier,
            onSelect = onSelect,
            onRetry = { model.retrySubtitles(track.id) },
            onBookmark = onBookmark,
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
    model: AppViewModel,
) {
    val timeline = model.position.collectAsStateWithLifecycle()
    var dragging by remember(track.id) { mutableStateOf<Long?>(null) }
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    val positionProvider: () -> Long = {
        if (state.trackId == track.id) timeline.value else fallbackPosition
    }
    Column(Modifier.padding(horizontal = 25.dp)) {
        key(track.id) {
            PrecisionSeekBar(
                positionProvider = positionProvider,
                duration = duration,
                enabled = shownTrack != null && duration > 0,
                loopStart = state.loopStart.takeIf { it >= 0 } ?: markA,
                loopEnd = state.loopEnd,
                onPreview = { dragging = it },
                onSeek = model::seek,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(dragging ?: positionProvider()), color = Muted, fontSize = 12.sp)
            Text(clock(duration), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerControls(
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    opening: String?,
    cues: List<Cue>,
    loading: Boolean,
    subtitleError: String?,
    embeddedSubtitle: String,
    settings: Settings,
    markA: Long,
    fallbackPosition: Long,
    hasQueue: Boolean,
    model: AppViewModel,
    onSelectSubtitle: (Cue) -> Unit,
    onBookmarkCue: (Cue) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier,
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
    Column(modifier) {
        Text(
            (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
        )
        PlayerSubtitles(
            track = track,
            activeTrackId = state.trackId,
            cues = cues,
            loading = loading,
            subtitleError = subtitleError,
            embeddedSubtitle = embeddedSubtitle,
            settings = settings,
            fallbackPosition = fallbackPosition,
            model = model,
            onSelect = onSelectSubtitle,
            onBookmark = onBookmarkCue,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        PlayerTimeline(
            track = track,
            state = state,
            shownTrack = shownTrack,
            markA = markA,
            fallbackPosition = fallbackPosition,
            model = model,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconAction(
                Icons.Outlined.SkipPrevious,
                stringResource(R.string.action_previous_track),
                enabled = opening == null && state.hasPrevious,
                action = model::previous,
            )
            IconAction(
                Icons.Outlined.Replay10,
                stringResource(R.string.action_rewind_10),
                enabled = shownTrack != null,
            ) {
                model.skip(-10000)
            }
            Box(contentAlignment = Alignment.Center) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val reduced = LocalReducedMotion.current
                val scale by
                    animateFloatAsState(
                        if (pressed && !reduced) .92f else 1f,
                        tween(90),
                        label = "play-press",
                    )
                FilledIconButton(
                    onClick = model::togglePlay,
                    modifier = Modifier.size(62.dp),
                    shape = CircleShape,
                    interactionSource = interaction,
                ) {
                    Icon(
                        if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        playControlLabel(state),
                        Modifier.size(32.dp).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
                if (state.buffering)
                    CircularProgressIndicator(
                        Modifier.size(66.dp),
                        color = Rose,
                        strokeWidth = 1.dp,
                    )
            }
            IconAction(
                Icons.Outlined.Forward10,
                stringResource(R.string.action_forward_10),
                enabled = shownTrack != null,
            ) {
                model.skip(10000)
            }
            IconAction(
                Icons.Outlined.SkipNext,
                stringResource(R.string.action_next_track),
                enabled = opening == null && state.hasNext,
                action = model::next,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.AutoMirrored.Outlined.QueueMusic,
                stringResource(R.string.action_queue),
                Muted,
                enabled = opening == null && hasQueue,
                action = onOpenQueue,
            )
            IconAction(
                repeatIcon,
                repeatLabel,
                if (state.repeatMode != Player.REPEAT_MODE_OFF) Rose else Muted,
                enabled = shownTrack != null,
                action = model::cycleRepeatMode,
            )
            IconAction(
                Icons.Outlined.Bookmarks,
                stringResource(R.string.action_fragments),
                if (state.loopStart >= 0 || markA >= 0) Rose else Muted,
                action = onOpenFragments,
            )
            IconAction(
                Icons.Outlined.Bedtime,
                stringResource(R.string.action_sleep_timer),
                if (state.timerEnd > 0) Rose else Muted,
                action = onOpenTimer,
            )
            IconAction(
                Icons.Outlined.MoreHoriz,
                stringResource(R.string.action_more_playback),
                Muted,
                action = onOpenMore,
            )
        }
    }
}

@Composable
private fun SubtitlePane(
    cues: List<Cue>,
    loading: Boolean,
    error: String?,
    anchor: Int,
    active: Int,
    size: Int,
    modifier: Modifier,
    onSelect: (Cue) -> Unit,
    onRetry: () -> Unit,
    onBookmark: (Cue) -> Unit,
) {
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    var previousAnchor by remember { mutableIntStateOf(-1) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(dragging) { if (dragging) following = false }
    BoxWithConstraints(modifier.testTag("subtitle-pane"), contentAlignment = Alignment.Center) {
        val inset = maxHeight * .24f
        LaunchedEffect(anchor, following, cues, inset, reduced) {
            if (!following) {
                previousAnchor = -1
                return@LaunchedEffect
            }
            if (anchor < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor }
            val animate = SubtitleFollow.animate(previousAnchor, anchor, item != null, reduced)
            previousAnchor = anchor
            if (animate && item != null)
                list.animateScrollBy(
                    item.offset.toFloat(),
                    tween(180, easing = FastOutSlowInEasing),
                )
            else list.scrollToItem(anchor)
        }
        if (cues.isEmpty()) {
            if (loading)
                CircularProgressIndicator(Modifier.size(24.dp), color = Rose, strokeWidth = 2.dp)
            else if (error != null) TextButton(onClick = onRetry) { Text("重试字幕", fontSize = 17.sp) }
            else Text("暂无字幕", color = Muted, fontSize = 17.sp)
        } else
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = 28.dp,
                        end = 28.dp,
                        top = inset,
                        bottom = maxHeight * .65f,
                    ),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(cues, key = { index, cue -> "$index|${cue.startMs}|${cue.endMs}" }) {
                    index,
                    cue ->
                    val color by
                        animateColorAsState(
                            if (index == active) Cream else Muted,
                            tween(100),
                            label = "subtitle-highlight",
                        )
                    Text(
                        cue.text,
                        color = color,
                        fontSize = size.sp,
                        lineHeight = (size + 10).sp,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("subtitle-$index")
                                .combinedClickable(
                                    hapticFeedbackEnabled = LocalTouchFeedback.current,
                                    onClick = { onSelect(cue) },
                                    onLongClickLabel = "保存这句",
                                    onLongClick = { onBookmark(cue) },
                                ),
                    )
                }
            }
        if (!following)
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                IconAction(Icons.Outlined.MyLocation, "跟随字幕", Rose) { following = true }
            }
    }
}
