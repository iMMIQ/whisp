// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.StorageUsage
import app.whisp.data.SupportedSkipSeconds
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private val settingsSpeeds = listOf(0.75f, 0.85f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 2.0f)

private fun formatBytesCompact(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes > 0 -> "${(bytes + 1023) / 1024} KiB"
        else -> "0"
    }

@OptIn(FlowPreview::class)
@Composable
internal fun SettingsPage(
    graph: AppGraph,
    openStorage: () -> Unit,
    reselectTick: Int = 0,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var usage by remember { mutableStateOf<StorageUsage?>(null) }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) listState.animateScrollToItem(0)
    }

    LaunchedEffect(graph, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            merge(
                    flowOf(Unit),
                    graph.storageChanges,
                    graph.media.downloads.map { Unit },
                    graph.settings.streamingCacheMiBUpdates.map { Unit },
                )
                .debounce(300)
                .collect { usage = graph.images.storageUsage() }
        }
    }

    val totalBytes = usage?.let {
        it.offline + it.streaming + it.images + it.customArtwork + it.library
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("settings-page")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.title_settings),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "section-comfort") {
                SettingsGroupCard("外观与交互") {
                    WhispToggleRow(
                        label = "深夜纯黑背景",
                        subtitle = "OLED 纯黑底色并柔化夜间播放按钮亮度",
                        checked = settings.amoledBlack,
                        tag = "settings-amoled-black",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(amoledBlack = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "触觉反馈",
                        subtitle = "播放控制、精细拖动与手势越阈时的轻微震动",
                        checked = settings.touchFeedback,
                        tag = "settings-touch-feedback",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(touchFeedback = checked) }
                            }
                        },
                    )
                    WhispToggleRow(
                        label = "减少动态效果",
                        subtitle = "关闭过渡动画与弹性缩放",
                        checked = settings.reduceMotion,
                        tag = "settings-reduce-motion",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(reduceMotion = checked) }
                            }
                        },
                    )
                }
            }
            item(key = "section-playback") {
                SettingsGroupCard("播放与字幕") {
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
                                    scope.launch {
                                        graph.settings.update { it.copy(skipSeconds = sec) }
                                    }
                                },
                                shape =
                                    SegmentedButtonDefaults.itemShape(
                                        index,
                                        SupportedSkipSeconds.size,
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.label_playback_speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(settingsSpeeds, key = { it }) { speed ->
                            val label =
                                when (speed) {
                                    1.0f -> "1x"
                                    2.0f -> "2x"
                                    else -> "${speed}x"
                                }
                            FilterChip(
                                selected = abs(settings.playbackSpeed - speed) < 0.01f,
                                onClick = {
                                    haptics.tick()
                                    graph.playback.setPlaybackSpeed(speed)
                                },
                                label = {
                                    Text(
                                        label,
                                        style =
                                            MaterialTheme.typography.labelMedium.merge(
                                                TabularNumeralStyle,
                                            ),
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
                            scope.launch {
                                graph.settings.update {
                                    it.copy(subtitleSize = value.roundToInt())
                                }
                            }
                        },
                    )
                    Text(
                        stringResource(R.string.label_text_encoding),
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    EncodingChips(
                        selected = settings.textEncoding,
                        onSelect = { encoding ->
                            haptics.tick()
                            scope.launch {
                                graph.settings.update { it.copy(textEncoding = encoding) }
                            }
                        },
                    )
                }
            }
            item(key = "section-privacy") {
                SettingsGroupCard("隐私保护") {
                    WhispToggleRow(
                        label = "隐藏通知封面与标题",
                        subtitle = "系统媒体通知仅显示「正在收听」",
                        checked = settings.hideArtwork,
                        tag = "settings-notification-privacy",
                        onCheckedChange = { checked ->
                            scope.launch {
                                graph.settings.update { it.copy(hideArtwork = checked) }
                            }
                        },
                    )
                    if (Build.VERSION.SDK_INT >= 33) {
                        WhispToggleRow(
                            label = "隐藏最近任务预览",
                            subtitle = "切换多任务卡片时隐藏应用画面",
                            checked = settings.hideRecents,
                            tag = "settings-recents-privacy",
                            onCheckedChange = { checked ->
                                scope.launch {
                                    graph.settings.update { it.copy(hideRecents = checked) }
                                }
                            },
                        )
                    }
                }
            }
            item(key = "section-storage") {
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable(onClick = openStorage)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Rose.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Storage,
                            null,
                            tint = Rose,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            "存储与缓存",
                            style = MaterialTheme.typography.titleMedium,
                            color = Cream,
                        )
                        Text(
                            totalBytes?.let {
                                "已占用 ${formatBytesCompact(it)} · 缓存上限 ${settings.streamingCacheMiB} MiB"
                            } ?: "查看离线下载与在线播放缓存",
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                            color = Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        tint = Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Rose,
        )
        content()
    }
}
