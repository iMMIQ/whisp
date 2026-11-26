// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import app.whisp.playback.SleepTimer
import app.whisp.playback.TimerStartAnchor
import kotlin.math.abs

internal val playbackSpeeds = listOf(0.75f, 0.85f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f)

internal fun formatSpeedLabel(speed: Float): String =
    when (speed) {
        1.0f -> "1x"
        2.0f -> "2x"
        else -> "${speed}x"
    }

internal data class BookmarkDraft(
    val trackId: String,
    val position: Long,
    val end: Long?,
    val title: String,
) {
    companion object {
        fun fromCue(trackId: String, cue: Cue, offsetMs: Long): BookmarkDraft =
            BookmarkDraft(
                trackId = trackId,
                position = (cue.startMs + offsetMs).coerceAtLeast(0L),
                end =
                    cue.endMs
                        .takeIf { it < Long.MAX_VALUE }
                        ?.let { (it + offsetMs).coerceAtLeast(0L) },
                title = cue.text,
            )

        fun atPosition(trackId: String, position: Long, title: String): BookmarkDraft =
            BookmarkDraft(trackId, position.coerceAtLeast(0L), null, title)

        fun forLoop(trackId: String, start: Long, end: Long, title: String): BookmarkDraft =
            BookmarkDraft(
                trackId = trackId,
                position = start.coerceAtLeast(0L),
                end = end.coerceAtLeast(start),
                title = title,
            )
    }
}

@Composable
internal fun PlayerQueueSheet(
    queueTracks: List<TrackEntity>,
    activeTrackId: String?,
    shuffleEnabled: Boolean = false,
    onToggleShuffle: (() -> Unit)? = null,
    onMoveTrack: ((String, Int) -> Unit)? = null,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    detail: WorkDetails? = null,
    onSwitchSectionTrack: ((String) -> Unit)? = null,
) {
    val sections = remember(detail?.tracks) { detail?.tracks?.let(TrackSections::groups).orEmpty() }
    val listState = rememberLazyListState()
    val haptics = rememberWhispHaptics()
    var reorderMode by remember { mutableStateOf(false) }
    val activeSectionKey =
        remember(sections, activeTrackId) {
            sections.firstOrNull { section -> section.tracks.any { it.id == activeTrackId } }?.key
        }
    val remainingDurationMs =
        remember(queueTracks, activeTrackId) {
            val activeIdx = queueTracks.indexOfFirst { it.id == activeTrackId }.coerceAtLeast(0)
            queueTracks.drop(activeIdx).sumOf { it.durationMs.coerceAtLeast(0L) }
        }
    LaunchedEffect(activeTrackId, queueTracks) {
        val index = queueTracks.indexOfFirst { it.id == activeTrackId }
        if (index > 1) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.action_queue),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                )
                val summary =
                    if (remainingDurationMs > 0L)
                        "${queueTracks.size} 曲 · 剩余 ${clock(remainingDurationMs)}"
                    else "${queueTracks.size} 曲"
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                    color = Muted,
                )
            }
            if (onToggleShuffle != null && queueTracks.size > 1) {
                IconAction(
                    WhispIcons.Outlined.Shuffle,
                    if (shuffleEnabled) "关闭随机播放" else "开启随机播放",
                    tint = if (shuffleEnabled) Rose else Muted,
                    haptic = HapticKind.Tick,
                ) {
                    if (!shuffleEnabled) reorderMode = false
                    onToggleShuffle()
                }
            }
            if (onMoveTrack != null && !shuffleEnabled && queueTracks.size > 1) {
                IconAction(
                    WhispIcons.Outlined.SwapVert,
                    if (reorderMode) "完成排序" else "调整队列顺序",
                    tint = if (reorderMode) Rose else Muted,
                    haptic = HapticKind.Tick,
                ) {
                    reorderMode = !reorderMode
                }
            }
        }
        if (sections.size > 1 && onSwitchSectionTrack != null) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sections, key = { it.key }) { section ->
                    FilterChip(
                        selected = section.key == activeSectionKey,
                        onClick = {
                            if (section.key != activeSectionKey) {
                                haptics.tick()
                                onSwitchSectionTrack(section.tracks.first().id)
                                dismiss()
                            }
                        },
                        label = {
                            Text(
                                "${section.title} (${section.tracks.size})",
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            )
                        },
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding(),
        ) {
            items(queueTracks.size, key = { queueTracks[it].id }) { index ->
                val item = queueTracks[index]
                if (reorderMode && onMoveTrack != null && !shuffleEnabled) {
                    Row(
                        Modifier.fillMaxWidth().padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            TrackRow(item, item.id == activeTrackId) {
                                haptics.tick()
                                onSelect(item.id)
                                dismiss()
                            }
                        }
                        IconAction(
                            WhispIcons.Outlined.KeyboardArrowUp,
                            "上移曲目",
                            Muted,
                            enabled = index > 0,
                            haptic = HapticKind.Tick,
                        ) {
                            onMoveTrack(item.id, -1)
                        }
                        IconAction(
                            WhispIcons.Outlined.KeyboardArrowDown,
                            "下移曲目",
                            Muted,
                            enabled = index < queueTracks.lastIndex,
                            haptic = HapticKind.Tick,
                        ) {
                            onMoveTrack(item.id, 1)
                        }
                    }
                } else {
                    TrackRow(item, item.id == activeTrackId) {
                        haptics.tick()
                        onSelect(item.id)
                        dismiss()
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerFragmentsSheet(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    markA: Long,
    shownTrack: TrackEntity?,
    cues: List<Cue>,
    trackOffsetMs: Long = 0L,
    bookmarks: List<BookmarkEntity>,
    playback: PlaybackCoordinator,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onAddBookmarkToPlaylist: ((BookmarkEntity) -> Unit)? = null,
    haptics: WhispHaptics,
    onCreateBookmark: (BookmarkDraft) -> Unit,
    onOpenInlineAb: () -> Unit,
    onDismiss: () -> Unit,
) {
    val saved =
        remember(bookmarks, detail.work.id) { bookmarks.filter { it.workId == detail.work.id } }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.action_fragments),
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            if (shownTrack != null) {
                TextButton(
                    onClick = {
                        onOpenInlineAb()
                        dismiss()
                    },
                ) {
                    Text("页内 A–B 条")
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
            item(key = "loop-controls") {
                Column(
                    Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.loopStart >= 0 || markA >= 0) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Rose.copy(alpha = 0.12f))
                                .border(0.5.dp, Rose.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.loopStart >= 0)
                                    "${clock(state.loopStart)} — ${clock(state.loopEnd)}"
                                else "起点 ${clock(markA)}",
                                color = Rose,
                                style =
                                    MaterialTheme.typography.titleMedium.merge(TabularNumeralStyle),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = playback::clearLoop) { Text("取消循环") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                playback.markLoopStart()
                                haptics.tick()
                            },
                            enabled = shownTrack != null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("设起点")
                        }
                        OutlinedButton(
                            onClick = { if (playback.markLoopEnd()) haptics.confirm() },
                            enabled = shownTrack != null && markA >= 0,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("设终点")
                        }
                    }
                    if (state.loopStart >= 0L && state.loopEnd > state.loopStart) {
                        FilledTonalButton(
                            onClick = {
                                val cueIndex =
                                    SubtitleTimeline.anchor(
                                        cues,
                                        (state.loopStart - trackOffsetMs).coerceAtLeast(
                                            0,
                                        ),
                                    )
                                onCreateBookmark(
                                    BookmarkDraft.forLoop(
                                        trackId = track.id,
                                        start = state.loopStart,
                                        end = state.loopEnd,
                                        title =
                                            cues.getOrNull(cueIndex)?.text
                                                ?: "${clock(state.loopStart)}–${clock(state.loopEnd)}",
                                    ),
                                )
                                dismiss()
                            },
                            enabled = shownTrack != null,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(WhispIcons.Outlined.Repeat, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("将当前 A–B 存为循环片段")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            val position = playback.currentPosition()
                            val cueIndex =
                                SubtitleTimeline.anchor(
                                    cues,
                                    (position - trackOffsetMs).coerceAtLeast(0),
                                )
                            onCreateBookmark(
                                BookmarkDraft.atPosition(
                                    track.id,
                                    position,
                                    cues.getOrNull(cueIndex)?.text ?: track.title,
                                ),
                            )
                            dismiss()
                        },
                        enabled = shownTrack != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(WhispIcons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("保存当前位置")
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (saved.isEmpty()) {
                item(key = "empty-fragments") {
                    Text(
                        "暂无片段",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                    )
                }
            }
            items(saved, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            playback.playBookmark(detail, item)
                            dismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            clock(item.positionMs),
                            color = Rose,
                            style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                        )
                        if (item.endMs != null) {
                            Text(
                                "→ ${clock(item.endMs)}",
                                color = Muted,
                                style =
                                    MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
                            )
                        }
                    }
                    Text(
                        item.title,
                        Modifier.weight(1f).padding(horizontal = 14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cream,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.endMs != null) {
                        IconAction(
                            WhispIcons.Outlined.Repeat,
                            "循环播放片段",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            playback.playBookmark(detail, item, repeat = true)
                            dismiss()
                        }
                    }
                    if (onAddBookmarkToPlaylist != null) {
                        IconAction(
                            WhispIcons.AutoMirrored.Outlined.QueueMusic,
                            "加入歌单",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            dismiss()
                            onAddBookmarkToPlaylist(item)
                        }
                    }
                    IconAction(
                        WhispIcons.Outlined.DeleteOutline,
                        "删除片段",
                        Muted,
                        haptic = HapticKind.Tick,
                    ) {
                        onDeleteBookmark(item)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SleepTimerDialog(
    timerEnd: Long,
    stopAfterTrack: Boolean,
    sleepFadeSeconds: Int = 15,
    shakeExtendTimer: Boolean = false,
    timerStartAnchor: TimerStartAnchor? = null,
    haptics: WhispHaptics,
    onSelectMinutes: (Int) -> Unit,
    onExtendMinutes: (Int) -> Unit,
    onStopAfterTrack: (Boolean) -> Unit,
    onSelectFadeSeconds: ((Int) -> Unit)? = null,
    onToggleShakeExtend: ((Boolean) -> Unit)? = null,
    onJumpToStartAnchor: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var nowElapsed by remember(timerEnd) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(timerEnd) {
        if (timerEnd <= 0L) return@LaunchedEffect
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            val left = timerEnd - nowElapsed
            if (left <= 0L) break
            kotlinx.coroutines.delay(minOf(15_000L, left.coerceAtLeast(500L)))
        }
    }
    val remainingMin = SleepTimer.remainingMinutes(timerEnd, nowElapsed)
    val hasActiveTimer = remainingMin > 0 || stopAfterTrack
    var submitted by remember { mutableStateOf(false) }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.action_sleep_timer),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    modifier = Modifier.weight(1f),
                )
                if (remainingMin > 0) {
                    Text(
                        "剩余约 $remainingMin 分钟",
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                        color = Rose,
                    )
                } else if (stopAfterTrack) {
                    Text(
                        "播完当前曲目后停",
                        style = MaterialTheme.typography.labelLarge,
                        color = Rose,
                    )
                }
            }
            if (remainingMin > 0) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onExtendMinutes(5)
                            haptics.tick()
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            "+5 分钟",
                            style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            onExtendMinutes(10)
                            haptics.tick()
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            "+10 分钟",
                            style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                        )
                    }
                }
            }
            val presets = listOf(10, 15, 30, 45, 60, 90)
            presets.chunked(3).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { minutes ->
                        FilledTonalButton(
                            onClick = {
                                onSelectMinutes(minutes)
                                haptics.confirm()
                                dismiss()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                "$minutes 分钟",
                                style =
                                    MaterialTheme.typography.titleSmall.merge(TabularNumeralStyle),
                            )
                        }
                    }
                }
            }
            val trackStopShape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth()
                    .clip(trackStopShape)
                    .background(
                        if (stopAfterTrack) Rose.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .border(
                        0.5.dp,
                        if (stopAfterTrack) Rose.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        trackStopShape,
                    )
                    .clickable {
                        if (submitted) return@clickable
                        submitted = true
                        onStopAfterTrack(!stopAfterTrack)
                        haptics.confirm()
                        dismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    WhispIcons.Outlined.MusicOff,
                    null,
                    tint = if (stopAfterTrack) Rose else Muted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "播完当前曲目后停止",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (stopAfterTrack) Rose else Cream,
                    modifier = Modifier.weight(1f),
                )
                if (stopAfterTrack) {
                    Icon(
                        WhispIcons.Outlined.Check,
                        null,
                        tint = Rose,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onSelectFadeSeconds != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "淡出时长",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        SupportedSleepFadeSeconds.forEachIndexed { index, sec ->
                            SegmentedButton(
                                selected = sleepFadeSeconds == sec,
                                onClick = {
                                    haptics.tick()
                                    onSelectFadeSeconds(sec)
                                },
                                shape =
                                    SegmentedButtonDefaults.itemShape(
                                        index,
                                        SupportedSleepFadeSeconds.size,
                                    ),
                            ) {
                                Text(
                                    "${sec}秒",
                                    style =
                                        MaterialTheme.typography.labelMedium.merge(
                                            TabularNumeralStyle,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
            if (timerStartAnchor != null && onJumpToStartAnchor != null) {
                OutlinedButton(
                    onClick = {
                        haptics.confirm()
                        onJumpToStartAnchor()
                        dismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(WhispIcons.Outlined.History, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "回到定时起点（${clock(timerStartAnchor.positionMs)}）",
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                    )
                }
            }
            if (onToggleShakeExtend != null) {
                WhispToggleRow(
                    label = "摇一摇延长 15 分钟",
                    subtitle = "仅定时期间开启低功耗检测，黑暗中闭眼轻晃延长",
                    checked = shakeExtendTimer,
                    onCheckedChange = onToggleShakeExtend,
                )
            }
            Text(
                "定时到期前按听觉曲线平滑淡出音量，避免突然中断",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            if (hasActiveTimer) {
                OutlinedButton(
                    onClick = {
                        onSelectMinutes(0)
                        haptics.tick()
                        dismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消定时", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun SaveBookmarkDialog(
    draft: BookmarkDraft,
    haptics: WhispHaptics,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    var submitted by remember(draft) { mutableStateOf(false) }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "保存片段",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (draft.end != null) "${clock(draft.position)}–${clock(draft.end)}"
                    else clock(draft.position),
                    style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                    color = Rose,
                )
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = dismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        if (submitted) return@Button
                        submitted = true
                        onSave(title)
                        haptics.confirm()
                        dismiss()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("保存", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun PlayerMoreSheet(
    context: Context,
    currentSpeed: Float,
    audioOutputLabel: String = "切换蓝牙耳机或系统输出设备",
    bedsideDimMode: Boolean = false,
    hasSubtitles: Boolean,
    hasSubtitleCandidates: Boolean = false,
    onSpeedChange: (Float) -> Unit,
    onToggleBedsideDim: (() -> Unit)? = null,
    onOpenDetail: () -> Unit,
    onSubtitleTune: () -> Unit,
    onSubtitlePicker: () -> Unit = {},
    onSubtitleSearch: () -> Unit = {},
    onPreciseSeek: () -> Unit,
    onPreferences: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.label_playback_speed),
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playbackSpeeds, key = { it }) { speed ->
                    FilterChip(
                        selected = abs(currentSpeed - speed) < 0.01f,
                        onClick = {
                            haptics.tick()
                            onSpeedChange(speed)
                        },
                        label = {
                            Text(
                                formatSpeedLabel(speed),
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            )
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        WhispSheetRow(
            icon = WhispIcons.Outlined.Info,
            title = "查看作品详情",
            subtitle = "简介、声优、标签、台本与特典",
            onClick = {
                dismiss()
                onOpenDetail()
            },
        )
        if (hasSubtitles) {
            WhispSheetRow(
                icon = WhispIcons.Outlined.Search,
                title = "搜索台词",
                subtitle = "按关键词定位当前曲目字幕",
                onClick = {
                    onSubtitleSearch()
                    dismiss()
                },
            )
            WhispSheetRow(
                icon = WhispIcons.Outlined.Subtitles,
                title = "字幕微调",
                subtitle = "边看实时字幕边调本曲偏移、字号与编码",
                onClick = {
                    onSubtitleTune()
                    dismiss()
                },
            )
        }
        if (hasSubtitleCandidates) {
            WhispSheetRow(
                icon = WhispIcons.Outlined.Translate,
                title = "字幕轨与双语对照",
                subtitle = "切换字幕文件或叠加第二语言字幕",
                onClick = {
                    onSubtitlePicker()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = WhispIcons.Outlined.Tune,
            title = stringResource(R.string.label_playback_settings),
            subtitle = "音效增强、倍速、步长、字幕与隐私",
            onClick = {
                onPreferences()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = WhispIcons.Outlined.AvTimer,
            title = "精确跳转",
            subtitle = "在当前位置前后 30 秒内按秒微调",
            onClick = {
                onPreciseSeek()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = WhispIcons.Outlined.Headphones,
            title = "音频输出",
            subtitle = "当前：$audioOutputLabel · 点击切换输出设备",
            onClick = {
                openAudioOutput(context)
                dismiss()
            },
        )
        if (onToggleBedsideDim != null) {
            WhispSheetRow(
                icon = WhispIcons.Outlined.Bedtime,
                title = if (bedsideDimMode) "退出床头微光模式" else "床头微光模式",
                subtitle = "压低屏幕亮度并保持字幕常亮，退出自动恢复",
                onClick = {
                    onToggleBedsideDim()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = WhispIcons.Outlined.Replay,
            title = "从头播放",
            subtitle = "回到作品首曲开头重新收听",
            onClick = {
                onRestart()
                dismiss()
            },
        )
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
internal fun PreciseSeekDialog(
    trackId: String,
    duration: Long,
    canSeek: Boolean,
    initialPosition: () -> Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial =
        remember(trackId, duration) { initialPosition() }.coerceIn(0, duration.coerceAtLeast(0))
    var target by remember(trackId, duration) { mutableLongStateOf(initial) }
    val start = remember(trackId, duration) { (initial - 30_000).coerceAtLeast(0) }
    val end =
        remember(trackId, duration) {
            (initial + 30_000).coerceAtMost(duration).coerceAtLeast(start + 1)
        }
    val haptics = rememberWhispHaptics()
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "精确跳转",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${clock(start)} – ${clock(end)}",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                    color = Muted,
                )
            }
            Text(
                clock(target),
                style = MaterialTheme.typography.headlineLarge.merge(TabularNumeralStyle),
                color = Rose,
            )
            SyncedSlider(
                target.toFloat(),
                start.toFloat()..end.toFloat(),
                onValueChange = { target = it.toLong() },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(-5_000L to "−5 秒", -1_000L to "−1 秒", 1_000L to "+1 秒", 5_000L to "+5 秒")
                    .forEach { (delta, label) ->
                        FilledTonalButton(
                            onClick = {
                                target = (target + delta).coerceIn(start, end)
                                haptics.tick()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                label,
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            )
                        }
                    }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = dismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        onSeek(target)
                        haptics.confirm()
                        dismiss()
                    },
                    enabled = canSeek && duration > 0,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("跳转", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
