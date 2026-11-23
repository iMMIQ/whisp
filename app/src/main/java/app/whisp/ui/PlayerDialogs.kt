// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
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
        Text(
            stringResource(R.string.action_queue),
            fontFamily = FontFamily.Serif,
            fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
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
            fontFamily = FontFamily.Serif,
            fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).navigationBarsPadding()) {
            item(key = "loop-controls") {
                Column(
                    Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.loopStart >= 0 || markA >= 0) {
                        Text(
                            if (state.loopStart >= 0)
                                "${clock(state.loopStart)} — ${clock(state.loopEnd)}"
                            else "起点 ${clock(markA)}",
                            color = Rose,
                            fontSize = 18.sp,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                playback.markLoopStart()
                                haptics.tick()
                            },
                            enabled = shownTrack != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("设起点")
                        }
                        OutlinedButton(
                            onClick = { if (playback.markLoopEnd()) haptics.confirm() },
                            enabled = shownTrack != null && markA >= 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("设终点")
                        }
                    }
                    if (state.loopStart >= 0 || markA >= 0) {
                        TextButton(onClick = playback::clearLoop) { Text("取消循环") }
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
                    ) {
                        Text("保存当前位置")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (saved.isEmpty()) {
                item(key = "empty-fragments") {
                    Text("暂无片段", color = Muted, modifier = Modifier.padding(24.dp))
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
                    Text(clock(item.positionMs), color = Rose)
                    Text(
                        item.title,
                        Modifier.weight(1f).padding(horizontal = 14.dp),
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.action_sleep_timer)) },
        text = {
            Column {
                listOf(15, 30, 60, 90).forEach { minutes ->
                    TextButton(
                        onClick = {
                            onSelectMinutes(minutes)
                            haptics.confirm()
                            onDismiss()
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text("$minutes 分钟")
                    }
                }
                if (timerEnd > 0) {
                    TextButton(
                        onClick = {
                            onSelectMinutes(0)
                            onDismiss()
                        },
                    ) {
                        Text("取消定时")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
        containerColor = Surface,
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
        Text(
            cue.text,
            fontSize = subtitleSize.sp,
            lineHeight = (subtitleSize + 10).sp,
            modifier =
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
        )
        TextButton(
            onClick = {
                onPlayFromHere()
                dismiss()
            },
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("从这里播放")
        }
        TextButton(
            onClick = {
                onSaveCue()
                dismiss()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("保存这句")
        }
        Spacer(Modifier.navigationBarsPadding())
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
        listOf(
                "精确跳转" to onPreciseSeek,
                "音频输出" to { openAudioOutput(context) },
                stringResource(R.string.label_playback_settings) to onPreferences,
                "从头播放" to onRestart,
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
                    OutlinedButton(onClick = { target = (target - 1000).coerceAtLeast(start) }) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.label_playback_settings)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.label_playback_speed))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(playbackSpeeds, key = { it }) { speed ->
                        val label =
                            if (speed == 1.0f) "1x" else if (speed == 2.0f) "2x" else "${speed}x"
                        FilterChip(
                            selected = abs(currentSpeed - speed) < 0.01f,
                            onClick = { onSpeedChange(speed) },
                            label = { Text(label, fontSize = 13.sp) },
                        )
                    }
                }
                Text("字幕字号")
                SyncedSlider(
                    settings.subtitleSize.toFloat(),
                    16f..30f,
                    steps = 13,
                    onValueChange = { value ->
                        onUpdateSettings { it.copy(subtitleSize = value.roundToInt()) }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("字幕偏移")
                    Text("${settings.subtitleOffsetMs} ms", color = Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            onUpdateSettings {
                                it.copy(subtitleOffsetMs = it.subtitleOffsetMs - 250)
                            }
                        },
                    ) {
                        Text("−250")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdateSettings {
                                it.copy(subtitleOffsetMs = it.subtitleOffsetMs + 250)
                            }
                        },
                    ) {
                        Text("+250")
                    }
                }
                Text(stringResource(R.string.label_text_encoding))
                EncodingChips(
                    selected = settings.textEncoding,
                    onSelect = { encoding ->
                        onUpdateSettings { it.copy(textEncoding = encoding) }
                    },
                )
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}
