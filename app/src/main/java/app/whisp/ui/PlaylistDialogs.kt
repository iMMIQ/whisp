// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PlaylistAddRequest(
    val workId: String,
    val trackIds: List<String>,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val label: String? = null,
)

@Composable
internal fun AddToPlaylistSheet(
    graph: AppGraph,
    request: PlaylistAddRequest,
    onDismiss: () -> Unit,
) {
    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    fun addTo(playlistId: String, playlistName: String, dismiss: () -> Unit) {
        scope.launch {
            try {
                if (
                    request.trackIds.size == 1 && (request.startMs != null || request.label != null)
                ) {
                    graph.library.addToPlaylist(
                        playlistId = playlistId,
                        workId = request.workId,
                        trackId = request.trackIds.first(),
                        startMs = request.startMs,
                        endMs = request.endMs,
                        label = request.label,
                    )
                } else {
                    graph.library.addTracksToPlaylist(playlistId, request.workId, request.trackIds)
                }
                haptics.confirm()
                graph.messages.tryEmit("已加入「$playlistName」")
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.messages.tryEmit(e.message ?: "加入歌单失败")
            }
        }
    }

    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("加入歌单", style = MaterialTheme.typography.headlineSmall, color = Cream)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(80) },
                    label = { Text("新建歌单（如：睡前助眠）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
                FilledTonalButton(
                    enabled = !creating && newName.isNotBlank(),
                    onClick = {
                        creating = true
                        val title = newName.trim()
                        scope.launch {
                            try {
                                val id = graph.library.createPlaylist(title)
                                newName = ""
                                addTo(id, title, dismiss)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                graph.messages.tryEmit(e.message ?: "创建歌单失败")
                            } finally {
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("新建")
                }
            }
            if (playlists.isEmpty()) {
                Text(
                    "输入名称创建第一个跨作品助眠歌单",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(playlists, key = { _, item -> item.id }) { _, pl ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { addTo(pl.id, pl.name, dismiss) }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                image = pl.coverId?.let { WhispImage.Asset(it) },
                                description = null,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    pl.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Cream,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${pl.itemCount} 条",
                                    style =
                                        MaterialTheme.typography.bodySmall.merge(
                                            TabularNumeralStyle,
                                        ),
                                    color = Muted,
                                )
                            }
                            Icon(WhispIcons.Outlined.Add, null, tint = Rose)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private data class ResolvedPlaylistItem(
    val item: PlaylistItemEntity,
    val track: TrackEntity?,
    val work: WorkEntity?,
)

@Composable
internal fun PlaylistDetailSheet(
    graph: AppGraph,
    playlist: PlaylistSummary,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rawItems by
        remember(playlist.id) { graph.library.observePlaylistItems(playlist.id) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val resolved by
        produceState(emptyList<ResolvedPlaylistItem>(), rawItems) {
            value =
                if (rawItems.isEmpty()) emptyList()
                else
                    withContext(Dispatchers.IO) {
                        val tracks =
                            rawItems
                                .map { it.trackId }
                                .distinct()
                                .chunked(500)
                                .flatMap { graph.library.dao.tracksByIds(it) }
                                .associateBy { it.id }
                        val works =
                            rawItems
                                .map { it.workId }
                                .distinct()
                                .chunked(500)
                                .flatMap { graph.library.dao.worksByIds(it) }
                                .associateBy { it.id }
                        rawItems.map { item ->
                            ResolvedPlaylistItem(item, tracks[item.trackId], works[item.workId])
                        }
                    }
        }
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember(playlist.id) { mutableStateOf(playlist.name) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var reorderMode by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        WhispConfirmDialog(
            title = "删除歌单",
            message = "删除歌单「${playlist.name}」？不会删除原作品与本地文件。",
            confirmLabel = "删除",
            onConfirm = {
                confirmingDelete = false
                scope.launch {
                    graph.library.deletePlaylist(playlist.id)
                    onDismiss()
                }
            },
            onDismiss = { confirmingDelete = false },
        )
        return
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("重命名歌单", color = Cream) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(80) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val next = renameText.trim()
                        renaming = false
                        scope.launch { graph.library.renamePlaylist(playlist.id, next) }
                    },
                ) {
                    Text("保存", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("取消") }
            },
        )
    }

    WhispSheet(onDismissRequest = onDismiss) { dismiss ->
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${resolved.size} 条曲目/片段",
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                    color = Muted,
                )
            }
            if (resolved.size > 1) {
                IconAction(
                    WhispIcons.Outlined.SwapVert,
                    if (reorderMode) "完成排序" else "调整顺序",
                    tint = if (reorderMode) Rose else Muted,
                    haptic = HapticKind.Tick,
                ) {
                    reorderMode = !reorderMode
                }
            }
            IconAction(
                WhispIcons.Outlined.Description,
                "重命名歌单",
                Muted,
                haptic = HapticKind.Tick,
            ) {
                renameText = playlist.name
                renaming = true
            }
            IconAction(
                WhispIcons.Outlined.DeleteOutline,
                "删除歌单",
                MaterialTheme.colorScheme.error,
                haptic = HapticKind.Tick,
            ) {
                confirmingDelete = true
            }
        }
        if (resolved.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        haptics.confirm()
                        graph.playback.playPlaylist(playlist.id)
                        dismiss()
                        onPlay()
                    },
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(WhispIcons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("顺序播放", fontWeight = FontWeight.SemiBold)
                }
                FilledTonalButton(
                    onClick = {
                        haptics.confirm()
                        graph.playback.playPlaylist(playlist.id, shuffle = true)
                        dismiss()
                        onPlay()
                    },
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(WhispIcons.Outlined.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("随机")
                }
            }
        }
        if (resolved.isEmpty()) {
            Text(
                "歌单为空，可在作品曲目或书签中选择「加入歌单」",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(resolved, key = { _, entry -> entry.item.id }) { idx, entry ->
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable {
                                haptics.tick()
                                graph.playback.playPlaylist(
                                    playlist.id,
                                    startItemId = entry.item.id,
                                )
                                dismiss()
                                onPlay()
                            }
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(
                            image = entry.work?.coverId?.let { WhispImage.Asset(it) },
                            description = null,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                entry.item.label?.takeIf(String::isNotBlank)
                                    ?: entry.track?.title
                                    ?: "曲目",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cream,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitleParts = buildList {
                                entry.work?.let { add(shortTitle(it.title).ifBlank { it.title }) }
                                if (entry.item.startMs != null) {
                                    val range =
                                        if (entry.item.endMs != null)
                                            "${clock(entry.item.startMs)}–${clock(entry.item.endMs)}"
                                        else clock(entry.item.startMs)
                                    add(range)
                                }
                            }
                            if (subtitleParts.isNotEmpty()) {
                                Text(
                                    subtitleParts.joinToString(" · "),
                                    style =
                                        MaterialTheme.typography.bodySmall.merge(
                                            TabularNumeralStyle,
                                        ),
                                    color = Muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (reorderMode) {
                            IconAction(
                                WhispIcons.Outlined.KeyboardArrowUp,
                                "上移",
                                Muted,
                                enabled = idx > 0,
                                haptic = HapticKind.Tick,
                            ) {
                                scope.launch {
                                    graph.library.movePlaylistItem(playlist.id, entry.item.id, -1)
                                }
                            }
                            IconAction(
                                WhispIcons.Outlined.KeyboardArrowDown,
                                "下移",
                                Muted,
                                enabled = idx < resolved.lastIndex,
                                haptic = HapticKind.Tick,
                            ) {
                                scope.launch {
                                    graph.library.movePlaylistItem(playlist.id, entry.item.id, 1)
                                }
                            }
                        } else {
                            IconAction(
                                WhispIcons.Outlined.Close,
                                "移出歌单",
                                Muted,
                                haptic = HapticKind.Tick,
                            ) {
                                scope.launch {
                                    graph.library.removePlaylistItem(playlist.id, entry.item.id)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(6.dp))
    }
}
