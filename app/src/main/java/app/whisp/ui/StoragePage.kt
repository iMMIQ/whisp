// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(FlowPreview::class)
@Composable
internal fun StoragePage(graph: AppGraph, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
    LazyColumn(
        Modifier.fillMaxSize().testTag("storage-page"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.action_back_sources),
                    Muted,
                    action = back,
                )
                Text(
                    stringResource(R.string.title_storage),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(22.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("缓存", fontSize = 21.sp, modifier = Modifier.weight(1f))
                    Text(
                        usage?.let { storageSize(it.cache) } ?: "—",
                        color = Rose,
                        fontSize = 21.sp,
                    )
                }
                StorageRow("在线播放", usage?.streaming)
                StorageRow("图片缓存", usage?.images)
                FilledTonalButton(
                    enabled = usage != null && !clearing,
                    modifier = Modifier.fillMaxWidth().testTag("clear-cache"),
                    onClick = {
                        clearing = true
                        scope.launch {
                            try {
                                graph.images.clearCaches(graph.media::clearStreamingCache)
                                usage = graph.images.storageUsage()
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在线播放上限（MiB）", fontSize = 18.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(64, 128, 256, 512).forEach { limit ->
                        FilterChip(
                            selected = settings.streamingCacheMiB == limit,
                            onClick = {
                                scope.launch {
                                    graph.settings.update { it.copy(streamingCacheMiB = limit) }
                                }
                            },
                            label = { Text("$limit", fontSize = 14.sp, maxLines = 1) },
                            modifier = Modifier.weight(1f).testTag("cache-limit-$limit"),
                        )
                    }
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(22.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StorageRow("离线下载", usage?.offline)
                StorageRow("自定义封面", usage?.customArtwork)
                StorageRow("作品资料与设置", usage?.library)
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(bytes?.let { storageSize(it) } ?: "—", fontSize = 16.sp, color = Muted)
    }
}
