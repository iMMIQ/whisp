// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.R
import app.whisp.data.*
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun DetailPage(
    detail: WorkDetails,
    model: AppViewModel,
    back: () -> Unit,
    player: () -> Unit,
    filter: ((AsmrFacet) -> Unit)? = null,
) {
    var tab by rememberSaveable(detail.work.id) { mutableStateOf("曲目") }
    val official = rememberDlsiteWork(model, detail.work.id, detail.work.rj, tab)
    var information by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<AttachmentEntity?>(null) }
    val playback by model.player.collectAsStateWithLifecycle()
    val history by model.progress.collectAsStateWithLifecycle()
    // Observe only this work's offline readiness instead of recomposing on every unrelated download
    // tick.
    val offline by
        remember(detail.work.id, detail.work.offlineAttachments, detail.tracks) {
                model.downloads
                    .map { states ->
                        detail.work.offlineAttachments &&
                            detail.tracks.all { states[it.id]?.state == Download.STATE_COMPLETED }
                    }
                    .distinctUntilChanged()
            }
            .collectAsStateWithLifecycle(
                initialValue = model.repository.offlineStore.isOffline(detail),
            )
    val sections = remember(detail.tracks) { TrackSections.groups(detail.tracks) }
    val remembered =
        remember(history, detail.work.id) { history.firstOrNull { it.workId == detail.work.id } }
    val resume = remembered != null
    val finished =
        if (detail.tracks.any { it.id == playback.trackId }) playback.ended
        else
            PlaybackQueue.finished(
                TrackSections.queue(detail.tracks, remembered?.trackId),
                remembered,
            )
    val scripts =
        remember(detail.attachments) {
            detail.attachments.filter { it.kind == "script" }.sortedBy { it.relativePath }
        }
    val extras =
        remember(detail.attachments) {
            detail.attachments
                .filter { it.kind !in setOf("script", "subtitle", "cover") }
                .sortedBy { it.relativePath }
        }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(.92f).background(Surface)) {
                Artwork(
                    model,
                    detail.work.coverId,
                    detail.work.title,
                    Modifier.matchParentSize(),
                    sharedKey = detail.work.id,
                )
                Box(
                    Modifier.matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.copy(alpha = .2f), Color.Transparent, Ink),
                            ),
                        ),
                )
                CompositionLocalProvider(LocalCoverControls provides true) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconAction(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(R.string.action_back_library),
                            action = back,
                        )
                        Row {
                            IconAction(
                                if (detail.work.favorite) Icons.Outlined.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                stringResource(R.string.action_favorite),
                                if (detail.work.favorite) Rose else Cream,
                                enabled = true,
                                feedback = true,
                            ) {
                                model.favorite(detail.work.id)
                            }
                            IconAction(
                                if (offline) Icons.Outlined.DownloadDone
                                else Icons.Outlined.Download,
                                stringResource(R.string.action_download),
                                if (offline) Rose else Cream,
                            ) {
                                model.download(detail.work.id)
                            }
                            IconAction(
                                Icons.Outlined.MoreHoriz,
                                stringResource(R.string.action_work_info),
                            ) {
                                information = true
                            }
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Text(
                        detail.work.title,
                        fontFamily = FontFamily.Serif,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                    )
                    if (detail.work.artist.isNotBlank())
                        Text(
                            detail.work.artist,
                            color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                        )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        model.playWork(detail, restart = finished)
                        player()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            finished -> stringResource(R.string.action_replay_work)
                            resume -> stringResource(R.string.action_resume_work)
                            else -> stringResource(R.string.action_play_work)
                        },
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                IconAction(Icons.Outlined.Shuffle, stringResource(R.string.action_shuffle)) {
                    model.playWork(detail, shuffle = true)
                    player()
                }
            }
            OfficialCircle(official.details, filter)
            WorkContentTabs(tab) { tab = it }
        }
        when (tab) {
            "曲目" ->
                sections.forEach { section ->
                    if (sections.size > 1)
                        item(key = "section:${section.key}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 28.dp, end = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    section.title,
                                    color = Muted,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconAction(Icons.Outlined.PlayArrow, "播放${section.title}", Rose) {
                                    model.playWork(
                                        detail,
                                        section.tracks.first().id,
                                        startPosition = 0,
                                    )
                                    player()
                                }
                            }
                        }
                    items(section.tracks, key = { it.id }) { track ->
                        TrackRow(track, playback.trackId == track.id) {
                            model.playWork(detail, track.id)
                            player()
                        }
                    }
                }
            else -> {
                dlsiteContent(tab, official, model, detail.work.id, detail.work.rj, filter = filter)
                if (tab == "资料") {
                    if (scripts.isNotEmpty())
                        item(key = "header-scripts") {
                            Text(
                                "台本",
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                    items(scripts, key = { it.id }) {
                        ResourceRow(it.title, Icons.Outlined.Description) { attachment = it }
                    }
                    if (extras.isNotEmpty())
                        item(key = "header-extras") {
                            Text(
                                "附赠",
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
                            )
                        }
                    items(extras, key = { it.id }) {
                        ResourceRow(
                            it.title,
                            if (it.kind == "image") Icons.Outlined.Image
                            else Icons.AutoMirrored.Outlined.InsertDriveFile,
                        ) {
                            attachment = it
                        }
                    }
                }
            }
        }
        item(key = "detail-footer") { Spacer(Modifier.height(24.dp)) }
    }
    if (information) InfoDialog(detail, model, onRemoved = back) { information = false }
    attachment?.let { AttachmentDialog(it, model) { attachment = null } }
}

@Composable
private fun ResourceRow(title: String, icon: ImageVector, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 15.sp)
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun AttachmentDialog(
    attachment: AttachmentEntity,
    model: AppViewModel,
    dismiss: () -> Unit,
) {
    val settings by model.settings.collectAsStateWithLifecycle()
    var encoding by remember(attachment.id) { mutableStateOf(settings.textEncoding) }
    var rawBytes by remember(attachment.id) { mutableStateOf<ByteArray?>(null) }
    var text by remember(attachment.id) { mutableStateOf<String?>(null) }
    var image by remember(attachment.id) { mutableStateOf<File?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    LaunchedEffect(attachment.id) {
        try {
            if (attachment.kind == "image") image = model.repository.previewImage(attachment.id)
            else rawBytes = model.repository.readAsset(attachment.id, 64 * 1024 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            model.error.value = e.message
            dismiss()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(rawBytes, encoding) {
        val bytes = rawBytes ?: return@LaunchedEffect
        text = withContext(Dispatchers.Default) { TextEncoding.decode(bytes, encoding) }
    }
    WhispSheet(onDismissRequest = dismiss) {
        Text(
            attachment.title,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (rawBytes != null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TextEncoding.supported, key = { it }) { candidate ->
                    FilterChip(
                        selected = encoding == candidate,
                        onClick = { encoding = candidate },
                        label = {
                            Text(
                                if (candidate == TextEncoding.AUTO)
                                    stringResource(R.string.label_encoding_auto)
                                else candidate,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.padding(32.dp))
        image?.let {
            AsyncImage(
                it,
                attachment.title,
                Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
        text?.let {
            Text(
                it,
                fontSize = 17.sp,
                lineHeight = 28.sp,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 550.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun InfoDialog(
    detail: WorkDetails,
    model: AppViewModel,
    onRemoved: () -> Unit,
    dismiss: () -> Unit,
) {
    var title by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.title) }
    var artist by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.artist) }
    var confirmingRemoval by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { model.action { model.repository.offlineStore.setCover(detail.work, it) } }
        }
    if (confirmingRemoval) {
        AlertDialog(
            onDismissRequest = { if (!loading) confirmingRemoval = false },
            containerColor = Surface,
            title = { Text("删除作品") },
            text = { Text("删除该作品及其下载、书签和播放进度。") },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            try {
                                model.removeAsmrWork(detail)
                                dismiss()
                                onRemoved()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.error.value = e.message ?: "删除失败"
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) {
                    Text(if (loading) "删除中" else "删除", color = Rose)
                }
            },
            dismissButton = {
                TextButton(enabled = !loading, onClick = { confirmingRemoval = false }) {
                    Text("取消")
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.action_work_info)) },
        containerColor = Surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("作品名") })
                OutlinedTextField(artist, { artist = it }, label = { Text("声优") })
                TextButton(onClick = { cover.launch(arrayOf("image/*")) }) { Text("更换封面") }
                if (detail.work.rj != null)
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    model.repository.updateDlsiteMetadata(detail.work.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    model.error.value = e.message ?: "元数据更新失败"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        Text(if (loading) "读取中" else "更新 DLsite 元数据")
                    }
                if (isAsmrOneWork(detail.work) || detail.work.origin == "dlsite")
                    TextButton(enabled = !loading, onClick = { confirmingRemoval = true }) {
                        Text("删除作品", color = Rose)
                    }
                detail.work.rj?.let { Text(it, color = Muted) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    model.action {
                        model.repository.editWorkIdentity(
                            detail.work.id,
                            title.ifBlank { detail.work.title },
                            artist,
                        )
                    }
                    dismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
