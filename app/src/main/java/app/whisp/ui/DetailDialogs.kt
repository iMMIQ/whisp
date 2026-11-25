// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
internal fun ResourceRow(title: String, icon: ImageVector, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Rose.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = Cream,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
        )
        Icon(WhispIcons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun AttachmentDialog(
    attachment: AttachmentEntity,
    graph: AppGraph,
    dismiss: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var encoding by remember(attachment.id) { mutableStateOf(settings.textEncoding) }
    var rawBytes by remember(attachment.id) { mutableStateOf<ByteArray?>(null) }
    var chunks by remember(attachment.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    LaunchedEffect(attachment.id) {
        try {
            rawBytes = graph.library.readAsset(attachment.id, 64 * 1024 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.messages.tryEmit(e.message ?: "附件不可读")
            dismiss()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(rawBytes, encoding) {
        val bytes = rawBytes ?: return@LaunchedEffect
        chunks =
            withContext(Dispatchers.Default) {
                chunkLongText(TextEncoding.decode(bytes, encoding))
            }
    }
    WhispSheet(onDismissRequest = dismiss) { _ ->
        Text(
            attachment.title,
            style = MaterialTheme.typography.titleLarge,
            color = Cream,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (rawBytes != null) {
            EncodingChips(
                selected = encoding,
                onSelect = { encoding = it },
                horizontalPadding = 24.dp,
            )
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Rose)
            }
        }
        chunks?.let { parts ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 550.dp),
                contentPadding = PaddingValues(24.dp),
            ) {
                itemsIndexed(parts, key = { idx, _ -> idx }) { _, chunk ->
                    Text(
                        chunk,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Cream,
                        lineHeight = 28.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun InfoDialog(
    detail: WorkDetails,
    graph: AppGraph,
    onRemoved: () -> Unit,
    dismiss: () -> Unit,
) {
    var title by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.title) }
    var artist by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.artist) }
    var confirmingRemoval by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                scope.launch {
                    runCatching { graph.offline.setCover(detail.work, it) }
                        .onFailure { err -> graph.messages.tryEmit(err.message ?: "更换封面失败") }
                }
            }
        }
    if (confirmingRemoval) {
        WhispConfirmDialog(
            title = "删除作品",
            message = "删除该作品及其下载、书签和播放进度。",
            confirmLabel = if (loading) "删除中" else "删除",
            busy = loading,
            onConfirm = {
                loading = true
                scope.launch {
                    try {
                        graph.sync.removeOnlineWork(detail.work.id)
                        dismiss()
                        onRemoved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        graph.messages.tryEmit(e.message ?: "删除失败")
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = { confirmingRemoval = false },
        )
        return
    }
    WhispSheet(onDismissRequest = dismiss) { close ->
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.action_work_info),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    modifier = Modifier.weight(1f),
                )
                detail.work.rj?.let {
                    Text(
                        it,
                        color = Muted,
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("作品名") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("声优") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        cover.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("更换封面")
                }
                if (detail.work.rj != null) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    graph.sync.updateDlsiteMetadata(detail.work.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    graph.messages.tryEmit(e.message ?: "元数据更新失败")
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (loading) "读取中" else "更新 DLsite 元数据", maxLines = 1)
                    }
                }
            }
            if (detail.work.isRemovableOnline) {
                OutlinedButton(
                    enabled = !loading,
                    onClick = { confirmingRemoval = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("删除作品", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = close,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val nextTitle = title.ifBlank { detail.work.title }
                        val nextArtist = artist
                        graph.scope.launch {
                            runCatching {
                                graph.library.editWorkIdentity(
                                    detail.work.id,
                                    nextTitle,
                                    nextArtist,
                                )
                            }
                        }
                        close()
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
