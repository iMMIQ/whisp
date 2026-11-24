// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import kotlin.math.ceil
import kotlinx.coroutines.delay
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
    val bg = MaterialTheme.colorScheme.background
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
    val reduced = LocalReducedMotion.current

    val fallbackPosition =
        remember(history, track?.id) {
            history.firstOrNull { it.trackId == track?.id }?.positionMs ?: 0L
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

    val hasAnySubtitles =
        cues.isNotEmpty() ||
            loading ||
            subtitleError != null ||
            (track.subtitleId == null && embeddedSubtitle.isNotBlank() && state.trackId == track.id)
    val coverFocusMode = !showVideo && !hasAnySubtitles

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
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(bg)
                .drawBehind {
                    drawRect(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(ambient.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(size.width * 0.5f, size.height * 0.18f),
                                radius = maxOf(size.width, size.height) * 0.72f,
                            ),
                    )
                }
                .navigationBarsPadding(),
        ) {
            val width = maxWidth
            val height = maxHeight
            val onToggleFavorite: () -> Unit = {
                scope.launch { graph.library.toggleFavorite(detail.work.id) }
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
                    fallbackPosition = fallbackPosition,
                    hasQueue = queueTracks.isNotEmpty(),
                    coverFocusMode = coverFocusMode && width <= height,
                    showTrackHeader = showTrackHeader,
                    video = video,
                    playback = playback,
                    onAmbientColor = { ambientSeed = it },
                    onSelectSubtitle = { subtitleAction = it },
                    onBookmarkCue = { cue ->
                        bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                    },
                    onOpenQueue = { queue = true },
                    onOpenFragments = { fragments = true },
                    onOpenTimer = { timer = true },
                    onOpenPreferences = { preferences = true },
                    onOpenMore = { more = true },
                    modifier = mod,
                )
            }
            if (width > height) {
                Row(Modifier.fillMaxSize()) {
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
                            playback = playback,
                            onToggleFavorite = onToggleFavorite,
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
                            playback = playback,
                            onToggleFavorite = onToggleFavorite,
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
                            favorite = detail.work.favorite,
                            video = video,
                            audioOnly = audioOnly,
                            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
                            onToggleFavorite = onToggleFavorite,
                            back = back,
                            overlayBackdrop = false,
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
                            playback = playback,
                            onToggleFavorite = onToggleFavorite,
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
                                        else height * .30f,
                                    ),
                        )
                        controls(Modifier.weight(1f).fillMaxWidth(), true)
                    }
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
private fun PlayerTopBar(
    favorite: Boolean,
    video: Boolean,
    audioOnly: Boolean,
    onToggleAudioOnly: () -> Unit,
    onToggleFavorite: () -> Unit,
    back: () -> Unit,
    overlayBackdrop: Boolean,
) {
    CompositionLocalProvider(LocalCoverControls provides overlayBackdrop) {
        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.Outlined.KeyboardArrowDown,
                stringResource(R.string.action_collapse_player),
                action = back,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (video) {
                    IconAction(
                        if (audioOnly) Icons.Outlined.Videocam else Icons.Outlined.Headphones,
                        if (audioOnly) "显示画面" else "只听声音",
                        action = onToggleAudioOnly,
                    )
                }
                IconAction(
                    if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    stringResource(R.string.action_favorite),
                    if (favorite) Rose else Cream,
                    enabled = true,
                    feedback = true,
                    action = onToggleFavorite,
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
    playback: PlaybackCoordinator,
    onToggleFavorite: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    back: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerTopBar(
            favorite = detail.work.favorite,
            video = video,
            audioOnly = audioOnly,
            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
            onToggleFavorite = onToggleFavorite,
            back = back,
            overlayBackdrop = false,
        )
        Column(
            Modifier.weight(1f).padding(horizontal = 20.dp),
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
    playback: PlaybackCoordinator,
    onToggleFavorite: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    back: () -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    var dragDistance by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.background(MaterialTheme.colorScheme.surface).pointerInput(back) {
            detectVerticalDragGestures(
                onDragStart = { dragDistance = 0f },
                onDragEnd = {
                    if (dragDistance > 72.dp.toPx()) back()
                    dragDistance = 0f
                },
                onDragCancel = { dragDistance = 0f },
                onVerticalDrag = { _, delta -> dragDistance += delta },
            )
        },
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
            favorite = detail.work.favorite,
            video = video,
            audioOnly = audioOnly,
            onToggleAudioOnly = { playback.setAudioOnly(!audioOnly) },
            onToggleFavorite = onToggleFavorite,
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
                    ),
        )
        if (showVideo) {
            CompositionLocalProvider(LocalCoverControls provides true) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    IconAction(Icons.Outlined.Fullscreen, "全屏", enabled = shownTrack != null) {
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
    onAmbientColor: (Color) -> Unit,
    modifier: Modifier,
) {
    val trackTitle = (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title
    Column(
        modifier.testTag("subtitle-pane").padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Artwork(
            image = coverImage,
            description = detail.work.title,
            modifier =
                Modifier.widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(shape)
                    .border(0.5.dp, Cream.copy(alpha = 0.14f), shape),
            sharedKey = detail.work.id.takeUnless { video },
            onAmbientColor = onAmbientColor,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            shortTitle(detail.work.title).ifBlank { detail.work.title },
            style = MaterialTheme.typography.headlineSmall,
            color = Cream,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.work.artist.isNotBlank()) {
            Text(
                detail.work.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            trackTitle,
            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
            color = Rose.copy(alpha = 0.88f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
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
    val buffered = playback.bufferedMs.collectAsStateWithLifecycle()
    var dragging by remember(track.id) { mutableStateOf<Long?>(null) }
    var fineBounds by remember(track.id) { mutableStateOf<LongRange?>(null) }
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    val positionProvider: () -> Long = {
        if (state.trackId == track.id) timeline.value else fallbackPosition
    }
    val bufferedProvider: () -> Long = {
        if (state.trackId == track.id) buffered.value else 0L
    }
    Column(Modifier.padding(horizontal = 18.dp)) {
        key(track.id) {
            PrecisionSeekBar(
                positionProvider = positionProvider,
                duration = duration,
                enabled = shownTrack != null && duration > 0,
                loopStart = state.loopStart.takeIf { it >= 0 } ?: markA,
                loopEnd = state.loopEnd,
                onPreview = { dragging = it },
                onSeek = playback::seek,
                bufferedProvider = bufferedProvider,
                onFineBounds = { fineBounds = it },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val leftLabel =
                fineBounds?.let { clock(it.first) } ?: clock(dragging ?: positionProvider())
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
}

@Composable
private fun ActiveModifiersRow(
    state: PlaybackState,
    markA: Long,
    onOpenPreferences: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenFragments: () -> Unit,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.timerEnd) {
        if (state.timerEnd > 0L) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(15_000L)
            }
        }
    }
    val showSpeed = abs(state.speed - 1f) > 0.01f
    val remainingMin =
        if (state.timerEnd > nowMs) ceil((state.timerEnd - nowMs) / 60_000.0).toInt() else 0
    val showTimer = state.timerEnd > 0L && remainingMin > 0
    val showLoop = state.loopStart >= 0L || markA >= 0L
    if (!showSpeed && !showTimer && !showLoop) return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpeed) {
            val speedText =
                if (state.speed == 2.0f) "2x"
                else if (state.speed == 1.0f) "1x" else "${state.speed}x"
            StatusPill(text = speedText, onClick = onOpenPreferences)
        }
        if (showLoop) {
            val text =
                if (state.loopStart >= 0L) "A–B ${clock(state.loopStart)}–${clock(state.loopEnd)}"
                else "A ${clock(markA)}"
            StatusPill(text = text, onClick = onOpenFragments)
        }
        if (showTimer) {
            StatusPill(text = "定时 ${remainingMin}分", onClick = onOpenTimer)
        }
    }
}

@Composable
private fun StatusPill(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.clip(shape)
            .background(Rose.copy(alpha = 0.14f))
            .border(0.5.dp, Rose.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = Rose,
            style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
        )
    }
}

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
    fallbackPosition: Long,
    hasQueue: Boolean,
    coverFocusMode: Boolean,
    showTrackHeader: Boolean,
    video: Boolean,
    playback: PlaybackCoordinator,
    onAmbientColor: (Color) -> Unit,
    onSelectSubtitle: (Cue) -> Unit,
    onBookmarkCue: (Cue) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenPreferences: () -> Unit,
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
    val reduced = LocalReducedMotion.current
    Column(modifier) {
        if (showTrackHeader) {
            Text(
                (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title,
                style = MaterialTheme.typography.titleMedium.merge(TabularNumeralStyle),
                color = Cream.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        if (coverFocusMode) {
            CoverFocusPane(
                detail = detail,
                track = track,
                coverImage = coverImage,
                video = video,
                onAmbientColor = onAmbientColor,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
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
        }
        ActiveModifiersRow(
            state = state,
            markA = markA,
            onOpenPreferences = onOpenPreferences,
            onOpenTimer = onOpenTimer,
            onOpenFragments = onOpenFragments,
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
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
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
                val scale by
                    animateFloatAsState(
                        if (pressed && !reduced) .92f else 1f,
                        tween(90),
                        label = "play-press",
                    )
                val glowAlpha by
                    animateFloatAsState(
                        if (state.playing) 0.22f else 0f,
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
                    onClick = playback::togglePlay,
                    modifier = Modifier.size(62.dp),
                    shape = CircleShape,
                    interactionSource = interaction,
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
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
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
                if (abs(state.speed - 1f) > 0.01f) Rose else Muted,
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
            else if (error != null)
                FilledTonalButton(onClick = onRetry) { Text("重试字幕", fontSize = 15.sp) }
            else Text("暂无字幕", color = Muted.copy(alpha = 0.7f), fontSize = 16.sp)
        } else {
            LazyColumn(
                Modifier.fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.12f to Color.Black,
                                    0.88f to Color.Black,
                                    1.0f to Color.Transparent,
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
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
                    val highlightAlpha by
                        animateFloatAsState(
                            if (isActive) 0.10f else 0f,
                            tween(if (reduced) 0 else 160),
                            label = "subtitle-bg",
                        )
                    // Draw highlight behind without altering layout bounds or line height.
                    Text(
                        cue.text,
                        color = color,
                        fontSize = size.sp,
                        lineHeight = (size + 10).sp,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("subtitle-$index")
                                .drawBehind {
                                    if (highlightAlpha > 0.005f) {
                                        val padX = 12.dp.toPx()
                                        val padY = 6.dp.toPx()
                                        drawRoundRect(
                                            color = Rose.copy(alpha = highlightAlpha),
                                            topLeft = Offset(-padX, -padY),
                                            size =
                                                Size(
                                                    this.size.width + padX * 2f,
                                                    this.size.height + padY * 2f,
                                                ),
                                            cornerRadius = CornerRadius(12.dp.toPx()),
                                        )
                                    }
                                }
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
        AnimatedVisibility(
            visible = !following && cues.isNotEmpty(),
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
