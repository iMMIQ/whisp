// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.StorageUsage
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private fun storageSize(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes > 0 -> "${(bytes + 1023) / 1024} KiB"
        else -> "0"
    }

private val SegmentOffline = Rose
private val SegmentStreaming = Color(0xFFC59CE6)
private val SegmentImages = Color(0xFF8AAEE0)
private val SegmentLibrary = Color(0xFF756885)

@OptIn(FlowPreview::class)
@Composable
internal fun StoragePage(
    graph: AppGraph,
    backLabel: String = stringResource(R.string.action_back_sources),
    back: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.AutoMirrored.Outlined.ArrowBack,
                backLabel,
                Cream,
                action = back,
            )
            Text(
                stringResource(R.string.title_storage),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("storage-page"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "storage-overview") {
                StorageOverviewCard(usage)
            }
            item(key = "storage-cache") {
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "缓存",
                            style = MaterialTheme.typography.titleLarge,
                            color = Cream,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            usage?.let { storageSize(it.cache) } ?: "—",
                            color = Rose,
                            style = MaterialTheme.typography.titleLarge.merge(TabularNumeralStyle),
                        )
                    }
                    StorageRow("在线播放", usage?.streaming, SegmentStreaming)
                    StorageRow("图片缓存", usage?.images, SegmentImages)
                    FilledTonalButton(
                        enabled = usage != null && !clearing,
                        modifier = Modifier.fillMaxWidth().testTag("clear-cache"),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            clearing = true
                            scope.launch {
                                try {
                                    graph.images.clearCaches(graph.media::clearStreamingCache)
                                    usage = graph.images.storageUsage()
                                    haptics.confirm()
                                    graph.messages.tryEmit("缓存已清理")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    graph.messages.tryEmit(e.message ?: "清理失败")
                                } finally {
                                    clearing = false
                                }
                            }
                        },
                    ) {
                        Text(if (clearing) "清理中" else "清理缓存")
                    }
                }
            }
            item(key = "storage-limit") {
                val limits = listOf(64, 128, 256, 512)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "在线播放上限（MiB）",
                        style = MaterialTheme.typography.titleMedium,
                        color = Cream,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        limits.forEachIndexed { index, limit ->
                            SegmentedButton(
                                selected = settings.streamingCacheMiB == limit,
                                onClick = {
                                    scope.launch {
                                        graph.settings.update {
                                            it.copy(streamingCacheMiB = limit)
                                        }
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, limits.size),
                                modifier = Modifier.testTag("cache-limit-$limit"),
                            ) {
                                Text(
                                    "$limit",
                                    style =
                                        MaterialTheme.typography.labelLarge.merge(
                                            TabularNumeralStyle,
                                        ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "storage-durable") {
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    StorageRow("离线下载", usage?.offline, SegmentOffline)
                    StorageRow("自定义封面", usage?.customArtwork, SegmentLibrary)
                    StorageRow("作品资料与设置", usage?.library, SegmentLibrary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageOverviewCard(usage: StorageUsage?) {
    val shape = RoundedCornerShape(20.dp)
    val total = usage?.let { it.offline + it.streaming + it.images + it.customArtwork + it.library }
    val segments =
        if (usage != null && total != null && total > 0L) {
            listOf(
                    Triple("离线", usage.offline, SegmentOffline),
                    Triple("播放缓存", usage.streaming, SegmentStreaming),
                    Triple("图片", usage.images, SegmentImages),
                    Triple("资料", usage.customArtwork + usage.library, SegmentLibrary),
                )
                .filter { it.second > 0L }
        } else emptyList()

    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "已占用空间",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                total?.let(::storageSize) ?: "—",
                style = MaterialTheme.typography.headlineSmall.merge(TabularNumeralStyle),
                color = Cream,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.forEach { (_, bytes, color) ->
                val weight = (bytes.toFloat() / total!!.toFloat()).coerceAtLeast(0.03f)
                Box(Modifier.weight(weight).fillMaxHeight().background(color))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                    "离线" to SegmentOffline,
                    "播放缓存" to SegmentStreaming,
                    "图片" to SegmentImages,
                    "资料" to SegmentLibrary,
                )
                .forEach { (name, color) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long?, dotColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (dotColor != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = Cream,
            modifier = Modifier.weight(1f),
        )
        Text(
            bytes?.let { storageSize(it) } ?: "—",
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumeralStyle),
            color = Muted,
        )
    }
}
