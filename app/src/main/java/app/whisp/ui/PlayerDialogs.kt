// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import app.whisp.playback.SleepTimer
import app.whisp.playback.TimerStartAnchor
import kotlin.math.abs
import kotlin.math.roundToInt

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
                    Icons.Outlined.Shuffle,
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
                    Icons.Outlined.SwapVert,
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
                            Icons.Outlined.KeyboardArrowUp,
                            "上移曲目",
                            Muted,
                            enabled = index > 0,
                            haptic = HapticKind.Tick,
                        ) {
                            onMoveTrack(item.id, -1)
                        }
                        IconAction(
                            Icons.Outlined.KeyboardArrowDown,
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
    settings: Settings,
    bookmarks: List<BookmarkEntity>,
    playback: PlaybackCoordinator,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
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
                                        (state.loopStart - settings.subtitleOffsetMs).coerceAtLeast(
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
                            Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp))
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
                                    (position - settings.subtitleOffsetMs).coerceAtLeast(0),
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
                        Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp))
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
                            Icons.Outlined.Repeat,
                            "循环播放片段",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            playback.playBookmark(detail, item, repeat = true)
                            dismiss()
                        }
                    }
                    IconAction(
                        Icons.Outlined.DeleteOutline,
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
                    Icons.Outlined.MusicOff,
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
                    Icon(Icons.Outlined.Check, null, tint = Rose, modifier = Modifier.size(18.dp))
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
                    Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
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
internal fun SubtitleActionSheet(
    cue: Cue,
    subtitleSize: Int,
    canSeek: Boolean,
    onPlayFromHere: () -> Unit,
    onSaveCue: () -> Unit,
    onMarkLoopStart: () -> Unit,
    onLoopCue: (() -> Unit)?,
    onCopyCue: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                clock(cue.startMs),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                color = Rose,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                cue.text,
                color = Cream,
                fontSize = subtitleSize.sp,
                lineHeight = (subtitleSize + 10).sp,
                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(6.dp))
        WhispSheetRow(
            icon = Icons.Outlined.PlayArrow,
            title = "从这里播放",
            enabled = canSeek,
            onClick = {
                onPlayFromHere()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = Icons.Outlined.BookmarkAdd,
            title = "保存这句",
            onClick = {
                onSaveCue()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = Icons.Outlined.Flag,
            title = "设为 A–B 起点",
            enabled = canSeek,
            onClick = {
                onMarkLoopStart()
                dismiss()
            },
        )
        if (onLoopCue != null) {
            WhispSheetRow(
                icon = Icons.Outlined.RepeatOne,
                title = "单句循环",
                enabled = canSeek,
                onClick = {
                    onLoopCue()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = Icons.Outlined.ContentCopy,
            title = "复制台词",
            onClick = {
                onCopyCue()
                dismiss()
            },
        )
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
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
            icon = Icons.Outlined.Info,
            title = "查看作品详情",
            subtitle = "简介、声优、标签、台本与特典",
            onClick = {
                dismiss()
                onOpenDetail()
            },
        )
        if (hasSubtitles) {
            WhispSheetRow(
                icon = Icons.Outlined.Search,
                title = "搜索台词",
                subtitle = "按关键词定位当前曲目字幕",
                onClick = {
                    onSubtitleSearch()
                    dismiss()
                },
            )
            WhispSheetRow(
                icon = Icons.Outlined.Subtitles,
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
                icon = Icons.Outlined.Translate,
                title = "字幕轨与双语对照",
                subtitle = "切换字幕文件或叠加第二语言字幕",
                onClick = {
                    onSubtitlePicker()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.label_playback_settings),
            subtitle = "音效增强、倍速、步长、字幕与隐私",
            onClick = {
                onPreferences()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = Icons.Outlined.AvTimer,
            title = "精确跳转",
            subtitle = "在当前位置前后 30 秒内按秒微调",
            onClick = {
                onPreciseSeek()
                dismiss()
            },
        )
        WhispSheetRow(
            icon = Icons.Outlined.Headphones,
            title = "音频输出",
            subtitle = "当前：$audioOutputLabel · 点击切换输出设备",
            onClick = {
                openAudioOutput(context)
                dismiss()
            },
        )
        if (onToggleBedsideDim != null) {
            WhispSheetRow(
                icon = Icons.Outlined.Bedtime,
                title = if (bedsideDimMode) "退出床头微光模式" else "床头微光模式",
                subtitle = "压低屏幕亮度并保持字幕常亮，退出自动恢复",
                onClick = {
                    onToggleBedsideDim()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = Icons.Outlined.Replay,
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

@Composable
internal fun LiveSubtitleTuneOverlay(
    settings: Settings,
    trackOffsetMs: Long,
    onTrackOffsetChange: (Long) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    hasCandidates: Boolean = false,
    onOpenPicker: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val haptics = rememberWhispHaptics()
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
            .border(0.5.dp, Rose.copy(alpha = 0.25f), shape)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "字幕实时微调",
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            IconAction(Icons.Outlined.Search, "搜索台词", Muted, haptic = HapticKind.Tick) {
                onClose()
                onOpenSearch()
            }
            if (hasCandidates) {
                IconAction(Icons.Outlined.Translate, "切换字幕与双语", Muted, haptic = HapticKind.Tick) {
                    onClose()
                    onOpenPicker()
                }
            }
            TextButton(onClick = onClose) { Text("完成", fontWeight = FontWeight.SemiBold) }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("本曲偏移", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text(
                    "${trackOffsetMs} ms",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                    color = if (trackOffsetMs != 0L) Rose else Muted,
                )
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onTrackOffsetChange(trackOffsetMs - 250L)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "−250",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                )
            }
            if (trackOffsetMs != 0L) {
                TextButton(
                    onClick = {
                        haptics.tick()
                        onTrackOffsetChange(0L)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("归零")
                }
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onTrackOffsetChange(trackOffsetMs + 250L)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "+250",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "字号",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.width(44.dp),
            )
            Box(Modifier.weight(1f)) {
                SyncedSlider(
                    settings.subtitleSize.toFloat(),
                    16f..30f,
                    steps = 13,
                    onValueChange = { value ->
                        onUpdateSettings { it.copy(subtitleSize = value.roundToInt()) }
                    },
                )
            }
            Text(
                "${settings.subtitleSize}sp",
                style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                color = Rose,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        EncodingChips(
            selected = settings.textEncoding,
            onSelect = { encoding ->
                haptics.tick()
                onUpdateSettings { it.copy(textEncoding = encoding) }
            },
        )
    }
}

@Composable
internal fun SubtitlePickerSheet(
    candidates: List<AttachmentEntity>,
    activePrimaryId: String?,
    activeSecondaryId: String?,
    hasOverride: Boolean,
    onSelect: (primaryId: String?, secondaryId: String?) -> Unit,
    onResetAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "字幕轨与双语",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            if (hasOverride) {
                TextButton(
                    onClick = {
                        haptics.tick()
                        onResetAuto()
                        dismiss()
                    },
                ) {
                    Text("恢复自动")
                }
            }
        }
        Text(
            "点选设为主字幕；点右侧「双语」可叠加第二语言对照",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).navigationBarsPadding()) {
            items(candidates, key = { it.id }) { item ->
                val isPrimary = item.id == activePrimaryId
                val isSecondary = item.id == activeSecondaryId
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            val nextSecondary =
                                if (activeSecondaryId == item.id) null else activeSecondaryId
                            onSelect(item.id, nextSecondary)
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            item.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isPrimary) Rose else Cream,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val folder = item.relativePath.substringBeforeLast('/', "")
                        if (folder.isNotBlank()) {
                            Text(
                                folder,
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isPrimary) {
                        Text(
                            "主字幕",
                            style = MaterialTheme.typography.labelMedium,
                            color = Rose,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    } else if (activePrimaryId != null) {
                        FilterChip(
                            selected = isSecondary,
                            onClick = {
                                haptics.tick()
                                onSelect(activePrimaryId, if (isSecondary) null else item.id)
                            },
                            label = {
                                Text(
                                    if (isSecondary) "副字幕 ✓" else "双语",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SubtitleSearchSheet(
    cues: List<DisplayCue>,
    offsetMs: Long,
    onJump: (Cue) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val haptics = rememberWhispHaptics()
    val matches =
        remember(cues, query) {
            val q = query.trim()
            if (q.isEmpty()) cues.take(50)
            else
                cues.filter { cue ->
                    cue.text.contains(q, ignoreCase = true) ||
                        cue.secondaryText?.contains(q, ignoreCase = true) == true
                }
        }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Text(
                "搜索台词",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            WhispSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "输入台词关键词",
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (matches.isEmpty()) {
                Text(
                    "未找到匹配台词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(matches.size, key = { "${matches[it].startMs}_$it" }) { idx ->
                        val item = matches[idx]
                        val displayTime = (item.startMs + offsetMs).coerceAtLeast(0L)
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptics.tick()
                                    onJump(item.toCue())
                                    dismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                clock(displayTime),
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                                color = Rose,
                                modifier = Modifier.width(58.dp).padding(top = 2.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Cream,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                item.secondaryText?.let { sub ->
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
internal fun PlaybackPreferencesDialog(
    settings: Settings,
    currentSpeed: Float,
    trackOffsetMs: Long = 0L,
    onTrackOffsetChange: ((Long) -> Unit)? = null,
    onSpeedChange: (Float) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.label_playback_settings),
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = dismiss) {
                Text("完成", fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "音声与听感",
                style = MaterialTheme.typography.labelLarge,
                color = Rose,
            )
            WhispToggleRow(
                label = "左右声道互换",
                subtitle = "修正声道录反或睡眠耳机反戴",
                checked = settings.swapChannels,
                tag = "audio-swap-channels",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(swapChannels = it) } },
            )
            WhispToggleRow(
                label = "夜间防炸耳柔化",
                subtitle = "柔化突发高音峰值并轻度提亮耳语",
                checked = settings.nightDynamics,
                tag = "audio-night-dynamics",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(nightDynamics = it) } },
            )
            WhispToggleRow(
                label = "单声道合并",
                subtitle = "侧睡单耳佩戴时听全双耳声音",
                checked = settings.monoAudio,
                tag = "audio-mono",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(monoAudio = it) } },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "夜间精细音量",
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(settings.volumeGain * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                    color = if (settings.volumeGain < 0.99f) Rose else Muted,
                )
            }
            SyncedSlider(
                settings.volumeGain,
                0.15f..1.0f,
                onValueChange = { value ->
                    onUpdateSettings { it.copy(volumeGain = value) }
                },
            )
            WhispToggleRow(
                label = "连续音轨无缝衔接",
                subtitle = "减少章节切曲停顿（不兼容硬件卸载时自动软件拼接）",
                checked = settings.gaplessPriority,
                tag = "audio-gapless",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(gaplessPriority = it) } },
            )
            WhispToggleRow(
                label = "恢复播放微淡入",
                subtitle = "避免佩戴耳机恢复播放时的生硬切入",
                checked = settings.softFadePause,
                tag = "audio-soft-fade",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(softFadePause = it) } },
            )
            WhispToggleRow(
                label = "长暂停后自动回退 3 秒",
                subtitle = "暂停超过 5 分钟再听时自动回溯衔接",
                checked = settings.smartResumeRewind,
                tag = "audio-smart-rewind",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(smartResumeRewind = it) } },
            )
            WhispToggleRow(
                label = "提示音时压低音量而非暂停",
                subtitle = "导航或通知语音播报时自动闪避声音",
                checked = settings.duckOnInterrupt,
                tag = "audio-duck-interrupt",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(duckOnInterrupt = it) } },
            )
            if (Build.VERSION.SDK_INT >= 32) {
                WhispToggleRow(
                    label = "保持双耳原声（禁用空间音频重混）",
                    subtitle = "防止系统空间音频破坏双耳录音定位",
                    checked = settings.preserveBinaural,
                    tag = "audio-preserve-binaural",
                    onCheckedChange = {
                        onUpdateSettings { cur -> cur.copy(preserveBinaural = it) }
                    },
                )
            }
            WhispToggleRow(
                label = "外放防社死拦截",
                subtitle = "未接耳机使用手机外放前二次确认",
                checked = settings.speakerGuard,
                tag = "audio-speaker-guard",
                onCheckedChange = { onUpdateSettings { cur -> cur.copy(speakerGuard = it) } },
            )
            WhispToggleRow(
                label = "按耳机自动切换听感",
                subtitle = "自动保存并恢复每副耳机的声道/柔化/音量衰减",
                checked = settings.autoDeviceAudioProfile,
                tag = "audio-device-profile",
                onCheckedChange = {
                    onUpdateSettings { cur -> cur.copy(autoDeviceAudioProfile = it) }
                },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
            Text(
                "快进与后退步长",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SupportedSkipSeconds.forEachIndexed { index, sec ->
                    SegmentedButton(
                        selected = settings.skipSeconds == sec,
                        onClick = {
                            haptics.tick()
                            onUpdateSettings { it.copy(skipSeconds = sec) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, SupportedSkipSeconds.size),
                    ) {
                        Text(
                            "${sec}秒",
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "字幕字号",
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "样例 · ${settings.subtitleSize}sp",
                    color = Cream,
                    fontSize = settings.subtitleSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SyncedSlider(
                settings.subtitleSize.toFloat(),
                16f..30f,
                steps = 13,
                onValueChange = { value ->
                    onUpdateSettings { it.copy(subtitleSize = value.roundToInt()) }
                },
            )
            if (onTrackOffsetChange != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("本曲字幕偏移", style = MaterialTheme.typography.labelLarge, color = Muted)
                        Text(
                            "${trackOffsetMs} ms",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                            color = if (trackOffsetMs != 0L) Rose else Muted,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.tick()
                            onTrackOffsetChange(trackOffsetMs - 250L)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "−250",
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                        )
                    }
                    if (trackOffsetMs != 0L) {
                        TextButton(
                            onClick = {
                                haptics.tick()
                                onTrackOffsetChange(0L)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text("归零")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.tick()
                            onTrackOffsetChange(trackOffsetMs + 250L)
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "+250",
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.label_text_encoding),
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
            )
            EncodingChips(
                selected = settings.textEncoding,
                onSelect = { encoding ->
                    haptics.tick()
                    onUpdateSettings { it.copy(textEncoding = encoding) }
                },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            WhispToggleRow(
                label = "隐藏通知封面与标题",
                checked = settings.hideArtwork,
                tag = "notification-privacy",
                onCheckedChange = {
                    onUpdateSettings { current -> current.copy(hideArtwork = it) }
                },
            )
            if (Build.VERSION.SDK_INT >= 33) {
                WhispToggleRow(
                    label = "隐藏最近任务预览",
                    checked = settings.hideRecents,
                    tag = "recents-privacy",
                    onCheckedChange = {
                        onUpdateSettings { current -> current.copy(hideRecents = it) }
                    },
                )
            }
            WhispToggleRow(
                label = "触觉反馈",
                checked = settings.touchFeedback,
                tag = "touch-feedback",
                onCheckedChange = {
                    onUpdateSettings { current -> current.copy(touchFeedback = it) }
                },
            )
            WhispToggleRow(
                label = "减少动态效果",
                checked = settings.reduceMotion,
                tag = "reduce-motion",
                onCheckedChange = {
                    onUpdateSettings { current -> current.copy(reduceMotion = it) }
                },
            )
            WhispToggleRow(
                label = "深夜纯黑背景",
                checked = settings.amoledBlack,
                tag = "amoled-black",
                onCheckedChange = {
                    onUpdateSettings { current -> current.copy(amoledBlack = it) }
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}
