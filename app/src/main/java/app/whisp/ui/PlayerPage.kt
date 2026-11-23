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
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Subtitle auto-scroll policy: animate only single-step transitions while visible. */
internal object SubtitleFollow {
    fun animate(previous: Int, current: Int, visible: Boolean, reducedMotion: Boolean): Boolean =
        !reducedMotion &&
            visible &&
            previous >= 0 &&
            previous != current &&
            abs(current - previous) <= 1
}

@Composable
internal fun PlayerPage(
    detail: WorkDetails?,
    track: TrackEntity?,
    graph: AppGraph,
    back: () -> Unit,
) {
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
    val bookmarks by graph.library.bookmarks.collectAsStateWithLifecycle()
    val history by graph.library.progress.collectAsStateWithLifecycle()

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var queue by rememberSaveable { mutableStateOf(false) }
    var fragments by rememberSaveable { mutableStateOf(false) }
    var timer by rememberSaveable { mutableStateOf(false) }
    var preferences by rememberSaveable { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<BookmarkDraft?>(null) }
    var subtitleAction by remember(track?.id) { mutableStateOf<Cue?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()

    val fallbackPosition =
        remember(history, track?.id) {
            history.firstOrNull { it.trackId == track?.id }?.positionMs ?: 0L
        }
    LaunchedEffect(track?.id, state.trackId) {
        if (state.trackId == null) playback.subtitles.load(track?.id)
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
            exit = { fullscreen = false },
            timeline = {
                PlayerTimeline(
                    track = track,
                    state = state,
                    shownTrack = shownTrack,
                    markA = markA,
                    fallbackPosition = fallbackPosition,
                    playback = playback,
                )
            },
        )
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
            val width = maxWidth
            val height = maxHeight
            val cover: @Composable (Modifier) -> Unit = { mod ->
                PlayerCover(
                    detail = detail,
                    track = track,
                    state = state,
                    shownTrack = shownTrack,
                    video = video,
                    showVideo = showVideo,
                    audioOnly = audioOnly,
                    playback = playback,
                    onToggleFavorite = {
                        scope.launch { graph.library.toggleFavorite(detail.work.id) }
                    },
                    back = back,
                    onFullscreen = { fullscreen = true },
                    modifier = mod,
                )
            }
            val controls: @Composable (Modifier) -> Unit = { mod ->
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
                    playback = playback,
                    onSelectSubtitle = { subtitleAction = it },
                    onBookmarkCue = { cue ->
                        bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                    },
                    onOpenQueue = { queue = true },
                    onOpenFragments = { fragments = true },
                    onOpenTimer = { timer = true },
                    onOpenMore = { more = true },
                    modifier = mod,
                )
            }
            if (width > height) {
                Row(Modifier.fillMaxSize()) {
                    cover(Modifier.fillMaxHeight().width(width * if (showVideo) .55f else .38f))
                    controls(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    cover(
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
                    controls(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    if (queue) {
        PlayerQueueSheet(
            queueTracks = queueTracks,
            activeTrackId = state.trackId,
            onSelect = playback::selectQueuedTrack,
            onDismiss = { queue = false },
        )
    }
    if (fragments) {
        PlayerFragmentsSheet(
            detail = detail,
            track = track,
            state = state,
            markA = markA,
            shownTrack = shownTrack,
            cues = cues,
            settings = settings,
            bookmarks = bookmarks,
            playback = playback,
            onDeleteBookmark = { id -> scope.launch { graph.library.deleteBookmark(id) } },
            haptics = haptics,
            onCreateBookmark = { bookmark = it },
            onDismiss = { fragments = false },
        )
    }
    if (timer) {
        SleepTimerDialog(
            timerEnd = state.timerEnd,
            haptics = haptics,
            onSelectMinutes = playback::setTimer,
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
        SubtitleActionSheet(
            cue = cue,
            subtitleSize = settings.subtitleSize,
            canSeek = shownTrack != null,
            onPlayFromHere = {
                playback.playWork(
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
    if (more) {
        PlayerMoreSheet(
            context = context,
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
private fun PlayerCover(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    video: Boolean,
    showVideo: Boolean,
    audioOnly: Boolean,
    playback: PlaybackCoordinator,
    onToggleFavorite: () -> Unit,
    back: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Surface)) {
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
                image = detail.work.coverId?.let { WhispImage.Asset(it) },
                description = detail.work.title,
                modifier = Modifier.matchParentSize(),
                sharedKey = detail.work.id.takeUnless { video },
            )
        }
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
                    if (video) {
                        IconAction(
                            if (audioOnly) Icons.Outlined.Videocam else Icons.Outlined.Headphones,
                            if (audioOnly) "显示画面" else "只听声音",
                        ) {
                            playback.setAudioOnly(!audioOnly)
                        }
                    }
                    IconAction(
                        if (detail.work.favorite) Icons.Outlined.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        stringResource(R.string.action_favorite),
                        if (detail.work.favorite) Rose else Cream,
                        enabled = true,
                        feedback = true,
                        action = onToggleFavorite,
                    )
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
        if (showVideo) {
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                IconAction(Icons.Outlined.Fullscreen, "全屏", enabled = shownTrack != null) {
                    onFullscreen()
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
    playback: PlaybackCoordinator,
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
    // Collecting positionMs inside PlayerSubtitles isolates timeline ticks from PlayerPage.
    val timeline = playback.positionMs.collectAsStateWithLifecycle()
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
            cues = cues,
            loading = loading,
            error = subtitleError,
            anchor = cueState.first,
            active = cueState.second,
            size = settings.subtitleSize,
            modifier = modifier,
            onSelect = onSelect,
            onRetry = { playback.subtitles.retry(track.id) },
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
    playback: PlaybackCoordinator,
) {
    val timeline = playback.positionMs.collectAsStateWithLifecycle()
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
                onSeek = playback::seek,
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
    playback: PlaybackCoordinator,
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
            playback = playback,
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
            playback = playback,
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
                action = playback::previous,
            )
            IconAction(
                Icons.Outlined.Replay10,
                stringResource(R.string.action_rewind_10),
                enabled = shownTrack != null,
            ) {
                playback.skip(-10_000)
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
                    onClick = playback::togglePlay,
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
                if (state.buffering) {
                    CircularProgressIndicator(
                        Modifier.size(66.dp),
                        color = Rose,
                        strokeWidth = 1.dp,
                    )
                }
            }
            IconAction(
                Icons.Outlined.Forward10,
                stringResource(R.string.action_forward_10),
                enabled = shownTrack != null,
            ) {
                playback.skip(10_000)
            }
            IconAction(
                Icons.Outlined.SkipNext,
                stringResource(R.string.action_next_track),
                enabled = opening == null && state.hasNext,
                action = playback::next,
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
                action = playback::cycleRepeatMode,
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
            else if (error != null) TextButton(onClick = onRetry) { Text("重试字幕", fontSize = 17.sp) }
            else Text("暂无字幕", color = Muted, fontSize = 17.sp)
        } else {
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
        }
        if (!following) {
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                IconAction(Icons.Outlined.MyLocation, "跟随字幕", Rose) { following = true }
            }
        }
    }
}
