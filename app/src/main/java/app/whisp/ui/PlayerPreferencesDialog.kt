// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.*
import kotlin.math.abs
import kotlin.math.roundToInt

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
