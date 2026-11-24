// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val playbackSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

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
    }
}

@Composable
internal fun PlayerQueueSheet(
    queueTracks: List<TrackEntity>,
    activeTrackId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
            items(queueTracks, key = { it.id }) { item ->
                TrackRow(item, item.id == activeTrackId) {
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
    onDeleteBookmark: (String) -> Unit,
    haptics: WhispHaptics,
    onCreateBookmark: (BookmarkDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val saved =
        remember(bookmarks, detail.work.id) { bookmarks.filter { it.workId == detail.work.id } }
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Text(
            stringResource(R.string.action_fragments),
            style = MaterialTheme.typography.headlineSmall,
            color = Cream,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
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
                            playback.playBookmark(detail, item)
                            dismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        clock(item.positionMs),
                        color = Rose,
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                    )
                    Text(
                        item.title,
                        Modifier.weight(1f).padding(horizontal = 14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cream,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.endMs != null) {
                        IconAction(Icons.Outlined.Repeat, "循环播放片段", Muted) {
                            playback.playBookmark(detail, item, repeat = true)
                            dismiss()
                        }
                    }
                    IconAction(Icons.Outlined.Close, "删除片段", Muted) {
                        onDeleteBookmark(item.id)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SleepTimerDialog(
    timerEnd: Long,
    haptics: WhispHaptics,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val remainingMin =
        if (timerEnd > 0L)
            ceil(((timerEnd - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000.0).toInt()
        else 0
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
                }
            }
            val presets = listOf(15, 30, 60, 90)
            presets.chunked(2).forEach { rowItems ->
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
            if (timerEnd > 0) {
                OutlinedButton(
                    onClick = {
                        onSelectMinutes(0)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("保存片段") },
        text = { OutlinedTextField(title, { title = it }, label = { Text("名称") }) },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(title)
                    haptics.confirm()
                    onDismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SubtitleActionSheet(
    cue: Cue,
    subtitleSize: Int,
    canSeek: Boolean,
    onPlayFromHere: () -> Unit,
    onSaveCue: () -> Unit,
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
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
internal fun PlayerMoreSheet(
    context: Context,
    onPreciseSeek: () -> Unit,
    onPreferences: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        WhispSheetRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.label_playback_settings),
            subtitle = "倍速、字幕、外观与隐私",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("精确跳转") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { target = (target - 1000).coerceAtLeast(start) }) {
                        Text("−1 秒")
                    }
                    FilledTonalButton(onClick = { target = (target + 1000).coerceAtMost(end) }) {
                        Text("+1 秒")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSeek(target)
                    onDismiss()
                },
                enabled = canSeek && duration > 0,
            ) {
                Text("跳转")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun PlaybackPreferencesDialog(
    settings: Settings,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    val label =
                        if (speed == 1.0f) "1x" else if (speed == 2.0f) "2x" else "${speed}x"
                    FilterChip(
                        selected = abs(currentSpeed - speed) < 0.01f,
                        onClick = { onSpeedChange(speed) },
                        label = {
                            Text(
                                label,
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            )
                        },
                    )
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
                        onClick = { onUpdateSettings { it.copy(subtitleOffsetMs = 0L) } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("归零")
                    }
                }
                OutlinedButton(
                    onClick = {
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
                onSelect = { encoding -> onUpdateSettings { it.copy(textEncoding = encoding) } },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            SettingToggleRow("隐藏通知封面与标题", settings.hideArtwork, "notification-privacy") {
                onUpdateSettings { current -> current.copy(hideArtwork = it) }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                SettingToggleRow("隐藏最近任务预览", settings.hideRecents, "recents-privacy") {
                    onUpdateSettings { current -> current.copy(hideRecents = it) }
                }
            }
            SettingToggleRow("触觉反馈", settings.touchFeedback, "touch-feedback") {
                onUpdateSettings { current -> current.copy(touchFeedback = it) }
            }
            SettingToggleRow("减少动态效果", settings.reduceMotion, "reduce-motion") {
                onUpdateSettings { current -> current.copy(reduceMotion = it) }
            }
            SettingToggleRow("深夜纯黑背景", settings.amoledBlack, "amoled-black") {
                onUpdateSettings { current -> current.copy(amoledBlack = it) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Cream,
            modifier = Modifier.weight(1f),
        )
        Switch(checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}
