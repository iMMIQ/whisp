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
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    detail: WorkDetails? = null,
    onSwitchSectionTrack: ((String) -> Unit)? = null,
) {
    val sections = remember(detail?.tracks) { detail?.tracks?.let(TrackSections::groups).orEmpty() }
    val listState = rememberLazyListState()
    val haptics = rememberWhispHaptics()
    val activeSectionKey =
        remember(sections, activeTrackId) {
            sections.firstOrNull { section -> section.tracks.any { it.id == activeTrackId } }?.key
        }
    LaunchedEffect(activeTrackId, queueTracks) {
        val index = queueTracks.indexOfFirst { it.id == activeTrackId }
        if (index > 1) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.action_queue),
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${queueTracks.size} 曲",
                style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                color = Muted,
            )
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
            items(queueTracks, key = { it.id }) { item ->
                TrackRow(item, item.id == activeTrackId) {
                    haptics.tick()
                    onSelect(item.id)
                    dismiss()
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
    haptics: WhispHaptics,
    onSelectMinutes: (Int) -> Unit,
    onExtendMinutes: (Int) -> Unit,
    onStopAfterTrack: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val remainingMin = SleepTimer.remainingMinutes(timerEnd, SystemClock.elapsedRealtime())
    val hasActiveTimer = remainingMin > 0 || stopAfterTrack
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
            Text(
                "定时到期前将平滑淡出音量，避免突然中断",
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
    hasSubtitles: Boolean,
    onSpeedChange: (Float) -> Unit,
    onOpenDetail: () -> Unit,
    onSubtitleTune: () -> Unit,
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
                icon = Icons.Outlined.Subtitles,
                title = "字幕微调",
                subtitle = "边看实时字幕边调偏移、字号与编码",
                onClick = {
                    onSubtitleTune()
                    dismiss()
                },
            )
        }
        WhispSheetRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.label_playback_settings),
            subtitle = "倍速、步长、字幕、外观与隐私",
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
            subtitle = "切换蓝牙耳机或系统输出设备",
            onClick = {
                openAudioOutput(context)
                dismiss()
            },
        )
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
    val initial = remember(trackId) { initialPosition() }.coerceIn(0, duration.coerceAtLeast(0))
    var target by remember(trackId) { mutableLongStateOf(initial) }
    val start = remember(trackId) { (initial - 30_000).coerceAtLeast(0) }
    val end =
        remember(trackId) { (initial + 30_000).coerceAtMost(duration).coerceAtLeast(start + 1) }
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
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
            TextButton(onClick = onClose) { Text("完成", fontWeight = FontWeight.SemiBold) }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("偏移", style = MaterialTheme.typography.labelLarge, color = Muted)
                Text(
                    "${settings.subtitleOffsetMs} ms",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                    color = if (settings.subtitleOffsetMs != 0L) Rose else Muted,
                )
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onUpdateSettings { it.copy(subtitleOffsetMs = it.subtitleOffsetMs - 250) }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "−250",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                )
            }
            if (settings.subtitleOffsetMs != 0L) {
                TextButton(
                    onClick = {
                        haptics.tick()
                        onUpdateSettings { it.copy(subtitleOffsetMs = 0L) }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("归零")
                }
            }
            OutlinedButton(
                onClick = {
                    haptics.tick()
                    onUpdateSettings { it.copy(subtitleOffsetMs = it.subtitleOffsetMs + 250) }
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
internal fun PlaybackPreferencesDialog(
    settings: Settings,
    currentSpeed: Float,
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("字幕偏移", style = MaterialTheme.typography.labelLarge, color = Muted)
                    Text(
                        "${settings.subtitleOffsetMs} ms",
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                        color = if (settings.subtitleOffsetMs != 0L) Rose else Muted,
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.tick()
                        onUpdateSettings { it.copy(subtitleOffsetMs = it.subtitleOffsetMs - 250) }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "−250",
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                    )
                }
                if (settings.subtitleOffsetMs != 0L) {
                    TextButton(
                        onClick = {
                            haptics.tick()
                            onUpdateSettings { it.copy(subtitleOffsetMs = 0L) }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("归零")
                    }
                }
                OutlinedButton(
                    onClick = {
                        haptics.tick()
                        onUpdateSettings { it.copy(subtitleOffsetMs = it.subtitleOffsetMs + 250) }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "+250",
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                    )
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
