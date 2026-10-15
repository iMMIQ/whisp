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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.data.*
import kotlin.math.roundToInt

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
    var queue by remember { mutableStateOf(false) }
    var fragments by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<BookmarkDraft?>(null) }
    var subtitleAction by remember(track?.id) { mutableStateOf<Cue?>(null) }
    var more by remember { mutableStateOf(false) }
    var precise by remember { mutableStateOf(false) }
    val markA by model.loopDraftStart.collectAsStateWithLifecycle()
    val history by model.progress.collectAsStateWithLifecycle()
    val timeline = model.position.collectAsStateWithLifecycle()
    fun displayPosition() =
        if (state.trackId == track?.id) timeline.value
        else history.firstOrNull { it.trackId == track?.id }?.positionMs ?: 0
    LaunchedEffect(track?.id, state.trackId) {
        if (state.trackId == null) model.loadSubtitles(track?.id)
    }
    var dragging by remember(track?.id) { mutableStateOf<Long?>(null) }
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
    @Composable
    fun Cover(modifier: Modifier) {
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
                        Brush.verticalGradient(
                            listOf(Ink.copy(alpha = .12f), Color.Transparent, Ink),
                        ),
                    ),
            )
            CompositionLocalProvider(LocalCoverControls provides true) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconAction(Icons.Outlined.KeyboardArrowDown, "收起播放器", action = back)
                    Row {
                        if (video)
                            IconAction(
                                if (audioOnly) Icons.Outlined.Videocam
                                else Icons.Outlined.Headphones,
                                if (audioOnly) "显示画面" else "只听声音",
                            ) {
                                model.setAudioOnly(!audioOnly)
                            }
                        IconAction(
                            if (detail.work.favorite) Icons.Outlined.Favorite
                            else Icons.Outlined.FavoriteBorder,
                            "收藏作品",
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
                        fullscreen = true
                    }
                }
        }
    }
    @Composable
    fun Subtitles(modifier: Modifier) {
        if (
            cues.isEmpty() &&
                track.subtitleId == null &&
                embeddedSubtitle.isNotBlank() &&
                state.trackId == track.id
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
        key(track.id) {
            val cueState by
                remember(cues, track.id, state.trackId, history, settings.subtitleOffsetMs) {
                    derivedStateOf(structuralEqualityPolicy()) {
                        val position =
                            (displayPosition() - settings.subtitleOffsetMs).coerceAtLeast(0)
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
                onSelect = { subtitleAction = it },
                onRetry = { model.retrySubtitles(track.id) },
                onBookmark = { cue ->
                    bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                },
            )
        }
    }
    @Composable
    fun Timeline() {
        val position = displayPosition()
        Column(Modifier.padding(horizontal = 25.dp)) {
            val duration =
                state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
            key(track.id) {
                PrecisionSeekBar(
                    position,
                    duration,
                    shownTrack != null && duration > 0,
                    state.loopStart.takeIf { it >= 0 } ?: markA,
                    state.loopEnd,
                    onPreview = { dragging = it },
                    onSeek = model::seek,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(dragging ?: position), color = Muted, fontSize = 12.sp)
                Text(clock(duration), color = Muted, fontSize = 12.sp)
            }
        }
    }
    @Composable
    fun Controls(modifier: Modifier) {
        Column(modifier) {
            Text(
                (if (track.order < 9999) "%02d · ".format(track.order) else "") + track.title,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
            )
            Subtitles(Modifier.weight(1f).fillMaxWidth())
            Timeline()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconAction(
                    Icons.Outlined.SkipPrevious,
                    "上一曲",
                    enabled = opening == null && state.hasPrevious,
                    action = model::previous,
                )
                IconAction(Icons.Outlined.Replay10, "后退十秒", enabled = shownTrack != null) {
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
                IconAction(Icons.Outlined.Forward10, "前进十秒", enabled = shownTrack != null) {
                    model.skip(10000)
                }
                IconAction(
                    Icons.Outlined.SkipNext,
                    "下一曲",
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
                    "播放队列",
                    Muted,
                    enabled = opening == null && queueTracks.isNotEmpty(),
                ) {
                    queue = true
                }
                IconAction(
                    Icons.Outlined.Bookmarks,
                    "片段",
                    if (state.loopStart >= 0 || markA >= 0) Rose else Muted,
                ) {
                    fragments = true
                }
                IconAction(
                    Icons.Outlined.Bedtime,
                    "定时停止",
                    if (state.timerEnd > 0) Rose else Muted,
                ) {
                    timer = true
                }
                IconAction(Icons.Outlined.MoreHoriz, "更多播放操作", Muted) { more = true }
            }
        }
    }
    if (fullscreen && showVideo) {
        val caption by
            remember(cues, track.id, state.trackId, settings.subtitleOffsetMs, embeddedSubtitle) {
                derivedStateOf {
                    val current =
                        SubtitleTimeline.current(
                            cues,
                            (displayPosition() - settings.subtitleOffsetMs).coerceAtLeast(0),
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
            timeline = { Timeline() },
        )
    } else
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding()) {
            val width = maxWidth
            val height = maxHeight
            if (width > height)
                Row(Modifier.fillMaxSize()) {
                    Cover(Modifier.fillMaxHeight().width(width * if (showVideo) .55f else .38f))
                    Controls(Modifier.weight(1f).fillMaxHeight())
                }
            else
                Column(Modifier.fillMaxSize()) {
                    Cover(
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
                    Controls(Modifier.weight(1f).fillMaxWidth())
                }
        }
    if (queue)
        WhispSheet(onDismissRequest = { queue = false }) { dismiss ->
            Text(
                "播放队列",
                fontFamily = FontFamily.Serif,
                fontSize = 24.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
                items(queueTracks, key = { it.id }) { item ->
                    TrackRow(item, item.id == state.trackId) {
                        model.selectQueuedTrack(item.id)
                        dismiss()
                    }
                }
            }
        }
    if (fragments)
        WhispSheet(onDismissRequest = { fragments = false }) { dismiss ->
            Text(
                "片段",
                fontFamily = FontFamily.Serif,
                fontSize = 24.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
                item {
                    Column(
                        Modifier.padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.loopStart >= 0 || markA >= 0)
                            Text(
                                if (state.loopStart >= 0)
                                    "${clock(state.loopStart)} — ${clock(state.loopEnd)}"
                                else "起点 ${clock(markA)}",
                                color = Rose,
                                fontSize = 18.sp,
                            )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    model.markLoopStart()
                                    haptics.tick()
                                },
                                enabled = shownTrack != null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("设起点")
                            }
                            OutlinedButton(
                                onClick = { if (model.markLoopEnd()) haptics.confirm() },
                                enabled = shownTrack != null && markA >= 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("设终点")
                            }
                        }
                        if (state.loopStart >= 0 || markA >= 0)
                            TextButton(onClick = model::clearLoop) { Text("取消循环") }
                        FilledTonalButton(
                            onClick = {
                                val position = model.currentPosition()
                                val cueIndex =
                                    SubtitleTimeline.anchor(
                                        cues,
                                        (position - settings.subtitleOffsetMs).coerceAtLeast(0),
                                    )
                                bookmark =
                                    BookmarkDraft.atPosition(
                                        track.id,
                                        position,
                                        cues.getOrNull(cueIndex)?.text ?: track.title,
                                    )
                                dismiss()
                            },
                            enabled = shownTrack != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存当前位置")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                val saved = bookmarks.filter { it.workId == detail.work.id }
                if (saved.isEmpty())
                    item { Text("暂无片段", color = Muted, modifier = Modifier.padding(24.dp)) }
                items(saved, key = { it.id }) { saved ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                model.playBookmark(detail, saved)
                                dismiss()
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(clock(saved.positionMs), color = Rose)
                        Text(
                            saved.title,
                            Modifier.weight(1f).padding(horizontal = 14.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (saved.endMs != null)
                            IconAction(Icons.Outlined.Repeat, "循环播放片段", Muted) {
                                model.playBookmark(detail, saved, repeat = true)
                                dismiss()
                            }
                        IconAction(Icons.Outlined.Close, "删除片段", Muted) {
                            model.deleteBookmark(saved.id)
                        }
                    }
                }
            }
        }
    if (timer)
        AlertDialog(
            onDismissRequest = { timer = false },
            containerColor = Surface,
            title = { Text("定时停止") },
            text = {
                Column {
                    listOf(15, 30, 60, 90).forEach { minutes ->
                        TextButton(
                            onClick = {
                                model.timer(minutes)
                                haptics.confirm()
                                timer = false
                            },
                            Modifier.fillMaxWidth(),
                        ) {
                            Text("$minutes 分钟")
                        }
                    }
                    if (state.timerEnd > 0)
                        TextButton(
                            onClick = {
                                model.timer(0)
                                timer = false
                            },
                        ) {
                            Text("取消定时")
                        }
                }
            },
            confirmButton = { TextButton(onClick = { timer = false }) { Text("关闭") } },
        )
    bookmark?.let { draft ->
        var title by remember(draft) { mutableStateOf(draft.title) }
        AlertDialog(
            onDismissRequest = { bookmark = null },
            containerColor = Surface,
            title = { Text("保存片段") },
            text = { OutlinedTextField(title, { title = it }, label = { Text("名称") }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.saveBookmark(title, draft.end, draft.position, draft.trackId)
                        haptics.confirm()
                        bookmark = null
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { bookmark = null }) { Text("取消") } },
        )
    }
    subtitleAction?.let { cue ->
        WhispSheet(onDismissRequest = { subtitleAction = null }) { dismiss ->
            Text(
                cue.text,
                fontSize = settings.subtitleSize.sp,
                lineHeight = (settings.subtitleSize + 10).sp,
                modifier =
                    Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
            )
            TextButton(
                onClick = {
                    model.playWork(
                        detail,
                        track.id,
                        startPosition = cue.startMs + settings.subtitleOffsetMs,
                    )
                    dismiss()
                },
                enabled = shownTrack != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("从这里播放")
            }
            TextButton(
                onClick = {
                    bookmark = BookmarkDraft.fromCue(track.id, cue, settings.subtitleOffsetMs)
                    dismiss()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text("保存这句")
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if (more)
        WhispSheet(onDismissRequest = { more = false }) { dismiss ->
            listOf(
                    "精确跳转" to { precise = true },
                    "音频输出" to { openAudioOutput(context) },
                    "播放设置" to { preferences = true },
                    "从头播放" to { model.playWork(detail, restart = true) },
                    "导出播放诊断" to { model.exportPlaybackDiagnostics() },
                )
                .forEach { (label, action) ->
                    TextButton(
                        onClick = {
                            action()
                            dismiss()
                        },
                        Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text(label, fontSize = 17.sp)
                    }
                }
            Spacer(Modifier.navigationBarsPadding())
        }
    if (precise) {
        val duration = state.durationMs.takeIf { it > 0 } ?: track.durationMs
        val initial =
            remember(track.id) { model.currentPosition() }.coerceIn(0, duration.coerceAtLeast(0))
        var target by remember(track.id) { mutableLongStateOf(initial) }
        val start = remember(track.id) { (initial - 30_000).coerceAtLeast(0) }
        val end =
            remember(track.id) {
                (initial + 30_000).coerceAtMost(duration).coerceAtLeast(start + 1)
            }
        AlertDialog(
            onDismissRequest = { precise = false },
            containerColor = Surface,
            title = { Text("精确跳转") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(clock(target), fontSize = 28.sp, color = Rose)
                    SyncedSlider(
                        target.toFloat(),
                        start.toFloat()..end.toFloat(),
                        onValueChange = { target = it.toLong() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(
                            onClick = { target = (target - 1000).coerceAtLeast(start) },
                        ) {
                            Text("−1 秒")
                        }
                        OutlinedButton(onClick = { target = (target + 1000).coerceAtMost(end) }) {
                            Text("+1 秒")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.seek(target)
                        precise = false
                    },
                    enabled = shownTrack != null && duration > 0,
                ) {
                    Text("跳转")
                }
            },
            dismissButton = { TextButton(onClick = { precise = false }) { Text("取消") } },
        )
    }
    if (preferences)
        AlertDialog(
            onDismissRequest = { preferences = false },
            containerColor = Surface,
            title = { Text("播放设置") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("字幕字号")
                    SyncedSlider(
                        settings.subtitleSize.toFloat(),
                        16f..30f,
                        steps = 13,
                        onValueChange = {
                            model.action { model.repository.setSettings(size = it.roundToInt()) }
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("字幕偏移")
                        Text("${settings.subtitleOffsetMs} ms", color = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                model.action {
                                    model.repository.setSettings(
                                        offset = settings.subtitleOffsetMs - 250,
                                    )
                                }
                            },
                        ) {
                            Text("−250")
                        }
                        OutlinedButton(
                            onClick = {
                                model.action {
                                    model.repository.setSettings(
                                        offset = settings.subtitleOffsetMs + 250,
                                    )
                                }
                            },
                        ) {
                            Text("+250")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("隐藏通知封面与标题", Modifier.weight(1f))
                        Switch(
                            settings.hideArtwork,
                            modifier = Modifier.testTag("notification-privacy"),
                            onCheckedChange = {
                                model.action { model.repository.setSettings(hide = it) }
                            },
                        )
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("隐藏最近任务预览", Modifier.weight(1f))
                            Switch(
                                settings.hideRecents,
                                modifier = Modifier.testTag("recents-privacy"),
                                onCheckedChange = {
                                    model.action { model.repository.setSettings(hideRecents = it) }
                                },
                            )
                        }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("触觉反馈", Modifier.weight(1f))
                        Switch(
                            settings.touchFeedback,
                            modifier = Modifier.testTag("touch-feedback"),
                            onCheckedChange = {
                                model.action { model.repository.setSettings(touchFeedback = it) }
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("减少动态效果", Modifier.weight(1f))
                        Switch(
                            settings.reduceMotion,
                            modifier = Modifier.testTag("reduce-motion"),
                            onCheckedChange = {
                                model.action { model.repository.setSettings(reduceMotion = it) }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preferences = false }) { Text("完成") } },
        )
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
                itemsIndexed(cues) { index, cue ->
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
