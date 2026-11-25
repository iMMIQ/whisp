// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.LoopState
import app.whisp.playback.PlaybackCoordinator
import app.whisp.playback.PlaybackErrorInfo
import app.whisp.playback.SleepTimer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Subtitle auto-scroll policy: animate only single-step transitions while visible. */
@Composable
internal fun SkipSecondsIcon(
    seconds: Int,
    forward: Boolean,
    tint: Color,
    contentDescription: String? = null,
) {
    when (seconds) {
        5 ->
            Icon(
                if (forward) WhispIcons.Outlined.Forward5 else WhispIcons.Outlined.Replay5,
                contentDescription,
                tint = tint,
            )
        10 ->
            Icon(
                if (forward) WhispIcons.Outlined.Forward10 else WhispIcons.Outlined.Replay10,
                contentDescription,
                tint = tint,
            )
        30 ->
            Icon(
                if (forward) WhispIcons.Outlined.Forward30 else WhispIcons.Outlined.Replay30,
                contentDescription,
                tint = tint,
            )
        else ->
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    WhispIcons.Outlined.Replay,
                    contentDescription,
                    tint = tint,
                    modifier =
                        Modifier.size(24.dp).graphicsLayer {
                            if (forward) scaleX = -1f
                        },
                )
                Text(
                    seconds.toString(),
                    color = tint,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
                    modifier = Modifier.offset(y = 1.dp),
                )
            }
    }
}

@Composable
internal fun PlayerTimeline(
    track: TrackEntity,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    markA: Long,
    fallbackPosition: Long,
    playback: PlaybackCoordinator,
    cues: List<Cue> = emptyList(),
    bookmarks: List<BookmarkEntity> = emptyList(),
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
    val bookmarkPositions = remember(bookmarks) { bookmarks.map { it.positionMs } }
    val previewCaptionProvider: ((Long) -> String?)? =
        remember(cues, bookmarks, subtitleOffsetMs) {
            if (cues.isEmpty() && bookmarks.isEmpty()) null
            else
                { posMs ->
                    val nearBookmark =
                        bookmarks
                            .minByOrNull { abs(it.positionMs - posMs) }
                            ?.takeIf { abs(it.positionMs - posMs) <= 2_500L }
                    val adjusted = (posMs - subtitleOffsetMs).coerceAtLeast(0L)
                    val cueText =
                        if (cues.isNotEmpty()) {
                            val idx = SubtitleTimeline.current(cues, adjusted)
                            cues.getOrNull(idx)?.text
                        } else null
                    cueText ?: nearBookmark?.let { "★ ${it.title}" }
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
                bookmarkPositions = bookmarkPositions,
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
internal fun ActiveModifiersRow(
    state: PlaybackState,
    settings: Settings,
    trackOffsetMs: Long,
    markA: Long,
    onOpenPreferences: () -> Unit,
    onOpenSubtitleTune: () -> Unit,
    onOpenTimer: () -> Unit,
    onToggleInlineAb: () -> Unit,
    onToggleShuffle: () -> Unit,
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
    val showOffset = trackOffsetMs != 0L
    val showShuffle = state.shuffleEnabled
    val showAudioMod = settings.hasDspEffects || settings.volumeGain < 0.99f
    if (
        !showSpeed &&
            !showTimer &&
            !showStopAfterTrack &&
            !showLoop &&
            !showOffset &&
            !showShuffle &&
            !showAudioMod
    ) {
        return
    }

    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpeed) {
            item(key = "mod-speed") {
                StatusPill(text = formatSpeedLabel(state.speed), onClick = onOpenPreferences)
            }
        }
        if (showShuffle) {
            item(key = "mod-shuffle") {
                StatusPill(text = "随机", onClick = onToggleShuffle)
            }
        }
        if (showAudioMod) {
            item(key = "mod-audio") {
                val label = buildList {
                    if (settings.swapChannels) add("声道反转")
                    if (settings.nightDynamics) add("夜间柔化")
                    if (settings.monoAudio) add("单声道")
                    if (settings.volumeGain < 0.99f) {
                        add("音量${(settings.volumeGain * 100f).roundToInt()}%")
                    }
                }
                    .joinToString("·")
                StatusPill(text = label, onClick = onOpenPreferences)
            }
        }
        if (showOffset) {
            item(key = "mod-offset") {
                val sign = if (trackOffsetMs > 0) "+" else ""
                StatusPill(text = "字幕 ${sign}${trackOffsetMs}ms", onClick = onOpenSubtitleTune)
            }
        }
        if (showLoop) {
            item(key = "mod-loop") {
                val text =
                    if (state.loopStart >= 0L)
                        "A–B ${clock(state.loopStart)}–${clock(state.loopEnd)}"
                    else "A ${clock(markA)}"
                StatusPill(text = text, onClick = onToggleInlineAb)
            }
        }
        if (showTimer) {
            item(key = "mod-timer") {
                StatusPill(text = "定时 ${remainingMin}分", onClick = onOpenTimer)
            }
        } else if (showStopAfterTrack) {
            item(key = "mod-stop-track") {
                StatusPill(text = "播完本曲停", onClick = onOpenTimer)
            }
        }
    }
}

@Composable
internal fun PlayerErrorBanner(
    errorInfo: PlaybackErrorInfo,
    refreshingSource: Boolean,
    onRetry: () -> Unit,
    onRefreshSource: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f))
            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), shape)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            WhispIcons.Outlined.ErrorOutline,
            null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            errorInfo.message,
            style = MaterialTheme.typography.bodySmall,
            color = Cream,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (errorInfo.canRefreshSource && onRefreshSource != null) {
            TextButton(
                onClick = onRefreshSource,
                enabled = !refreshingSource,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(if (refreshingSource) "刷新中" else "刷新地址")
            }
        }
        if (errorInfo.canRetry) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("重试", color = Rose)
            }
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
internal fun InlineAbLoopBar(
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
            IconAction(
                WhispIcons.Outlined.Close,
                "关闭提示",
                Muted,
                action = playback::dismissClearedLoop,
            )
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
                    WhispIcons.Outlined.BookmarkAdd,
                    "存为循环片段",
                    Rose,
                    haptic = HapticKind.Tick,
                    action = onSaveLoop,
                )
            }
            IconAction(
                WhispIcons.Outlined.Close,
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
                    playback.adjustLoop(deltaStartMs = -500L, preview = true)
                }
                SmallStepButton("A+0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaStartMs = 500L, preview = true)
                }
                Spacer(Modifier.weight(1f))
                SmallStepButton("B−0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaEndMs = -500L, preview = true)
                }
                SmallStepButton("B+0.5s") {
                    haptics.tick()
                    playback.adjustLoop(deltaEndMs = 500L, preview = true)
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
internal fun PlayerControls(
    detail: WorkDetails,
    track: TrackEntity,
    coverImage: WhispImage?,
    state: PlaybackState,
    shownTrack: TrackEntity?,
    opening: String?,
    cues: List<Cue>,
    displayCues: List<DisplayCue>,
    trackBookmarks: List<BookmarkEntity>,
    trackOffsetMs: Long,
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
    refreshingSource: Boolean,
    graph: AppGraph,
    playback: PlaybackCoordinator,
    onToggleFavorite: () -> Unit,
    onOpenWorkDetail: () -> Unit,
    onAmbientColor: (Color) -> Unit,
    onDirectSeekCue: (Cue) -> Unit,
    onLongPressSubtitle: (Cue) -> Unit,
    onSaveLoopAsBookmark: () -> Unit,
    onToggleInlineAb: () -> Unit,
    onCloseInlineAb: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenFragments: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenSubtitleTune: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenMore: () -> Unit,
    onRefreshOnlineWork: (() -> Unit)?,
    modifier: Modifier,
    dismissDragModifier: Modifier,
) {
    val repeatIcon =
        if (state.repeatMode == Player.REPEAT_MODE_ONE) WhispIcons.Outlined.RepeatOne
        else WhispIcons.Outlined.Repeat
    val repeatLabel =
        when (state.repeatMode) {
            Player.REPEAT_MODE_ALL -> stringResource(R.string.action_repeat_all)
            Player.REPEAT_MODE_ONE -> stringResource(R.string.action_repeat_one)
            else -> stringResource(R.string.action_repeat_off)
        }
    val reduced = LocalReducedMotion.current
    val amoled = LocalAmoledBlack.current
    val haptics = rememberWhispHaptics()
    // Store scrub preview in a MutableState box passed down without reading .value here,
    // preventing 60-120Hz recomposition of the entire PlayerControls tree during seeks.
    val scrubPreviewState = remember(track.id) { mutableStateOf<Long?>(null) }

    fun jumpCue(delta: Int) {
        if (cues.isEmpty() || shownTrack == null) return
        val adjusted = (playback.currentPosition() - trackOffsetMs).coerceAtLeast(0L)
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
                    if (detail.work.favorite) WhispIcons.Filled.Favorite
                    else WhispIcons.Outlined.FavoriteBorder,
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
                    fontSizeSp = settings.subtitleSize,
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
                    displayCues = displayCues,
                    loading = loading,
                    subtitleError = subtitleError,
                    embeddedSubtitle = embeddedSubtitle,
                    settings = settings,
                    trackOffsetMs = trackOffsetMs,
                    fallbackPosition = fallbackPosition,
                    scrubPreviewState = scrubPreviewState,
                    followSignal = followSignal,
                    playback = playback,
                    onDirectSeek = onDirectSeekCue,
                    onLongPress = onLongPressSubtitle,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
        }
        if (state.failed) {
            val info = state.errorInfo ?: PlaybackErrorInfo("播放失败，请检查文件授权或网络连接")
            PlayerErrorBanner(
                errorInfo = info,
                refreshingSource = refreshingSource,
                onRetry = playback::retryPlayback,
                onRefreshSource = onRefreshOnlineWork,
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
                onClose = onCloseInlineAb,
            )
        } else {
            ActiveModifiersRow(
                state = state,
                settings = settings,
                trackOffsetMs = trackOffsetMs,
                markA = markA,
                onOpenPreferences = onOpenPreferences,
                onOpenSubtitleTune = onOpenSubtitleTune,
                onOpenTimer = onOpenTimer,
                onToggleInlineAb = onToggleInlineAb,
                onToggleShuffle = playback::toggleShuffle,
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
            bookmarks = trackBookmarks,
            subtitleOffsetMs = trackOffsetMs,
            onScrubPreview = { scrubPreviewState.value = it },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconAction(
                WhispIcons.Outlined.SkipPrevious,
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
                SkipSecondsIcon(
                    seconds = settings.skipSeconds,
                    forward = false,
                    tint = if (shownTrack != null) Cream else Cream.copy(alpha = 0.35f),
                    contentDescription =
                        stringResource(R.string.action_rewind_seconds, settings.skipSeconds),
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
                            if (playing) WhispIcons.Filled.Pause else WhispIcons.Filled.PlayArrow,
                            playControlLabel(state),
                            Modifier.size(32.dp).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                }
                if (state.buffering || opening != null) {
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
                SkipSecondsIcon(
                    seconds = settings.skipSeconds,
                    forward = true,
                    tint = if (shownTrack != null) Cream else Cream.copy(alpha = 0.35f),
                    contentDescription =
                        stringResource(R.string.action_forward_seconds, settings.skipSeconds),
                )
            }
            IconAction(
                WhispIcons.Outlined.SkipNext,
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
                WhispIcons.AutoMirrored.Outlined.QueueMusic,
                stringResource(R.string.action_queue),
                if (state.shuffleEnabled) Rose else Muted,
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
                WhispIcons.Outlined.Bookmarks,
                stringResource(R.string.action_fragments),
                if (state.loopStart >= 0 || markA >= 0) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenFragments,
            )
            IconAction(
                WhispIcons.Outlined.Bedtime,
                stringResource(R.string.action_sleep_timer),
                if (state.timerEnd > 0 || state.timerStopAfterTrack) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenTimer,
            )
            IconAction(
                WhispIcons.Outlined.MoreHoriz,
                stringResource(R.string.action_more_playback),
                if (abs(state.speed - 1f) > 0.01f || settings.hasDspEffects) Rose else Muted,
                haptic = HapticKind.Tick,
                action = onOpenMore,
            )
        }
    }
}
