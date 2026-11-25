// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.AppGraph
import app.whisp.DlsiteLoginActivity
import app.whisp.R
import app.whisp.data.SourceEntity
import app.whisp.data.SourceKind
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import app.whisp.data.kindType
import app.whisp.system.LanDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sourceIcon(kind: SourceKind?): ImageVector =
    when (kind) {
        SourceKind.LOCAL -> WhispIcons.Outlined.FolderOpen
        SourceKind.SMB -> WhispIcons.Outlined.Dns
        SourceKind.NFS -> WhispIcons.Outlined.Lan
        SourceKind.WEBDAV -> WhispIcons.Outlined.CloudQueue
        SourceKind.ASMRONE -> WhispIcons.Outlined.Headphones
        SourceKind.DLSITE -> WhispIcons.Outlined.AccountCircle
        null -> WhispIcons.Outlined.CloudQueue
    }

private fun sourceLabel(kind: SourceKind?): String =
    when (kind) {
        SourceKind.LOCAL -> "本地目录"
        SourceKind.SMB -> "SMB 共享"
        SourceKind.NFS -> "NFS 网络文件"
        SourceKind.WEBDAV -> "WebDAV"
        SourceKind.ASMRONE -> "在线目录"
        SourceKind.DLSITE -> "官方已购"
        null -> "来源"
    }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SourcesPage(
    graph: AppGraph,
    reselectTick: Int = 0,
    online: (String) -> Unit,
    dlsite: (String) -> Unit,
    storage: () -> Unit,
    openWork: (String) -> Unit = {},
) {
    val sources by graph.library.sourceList.collectAsStateWithLifecycle()
    val busy by graph.sync.busy.collectAsStateWithLifecycle()
    val downloads by graph.media.downloads.collectAsStateWithLifecycle()
    val works by graph.library.works.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var adding by rememberSaveable { mutableStateOf(false) }
    var remoteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingRemoveSource by remember { mutableStateOf<SourceEntity?>(null) }
    var confirmingRemoveOffline by remember { mutableStateOf<WorkEntity?>(null) }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) listState.animateScrollToItem(0)
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.messages.tryEmit(e.message ?: "操作失败")
            }
        }
    }

    val login =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == android.app.Activity.RESULT_OK)
                result.data?.getStringExtra(DlsiteLoginActivity.SOURCE)?.let(dlsite)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runAction {
                    graph.context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val docId = DocumentsContract.getTreeDocumentId(uri)
                    val sourceId =
                        graph.library.addLocal(
                            uri.toString(),
                            docId.substringAfterLast('/').substringAfter(':').ifBlank { "本地作品" },
                        )
                    graph.sync.scheduleScan(sourceId, force = true)
                }
            }
        }

    val trackIdsByWork by
        produceState(emptyMap<String, List<String>>(), graph, downloads.keys) {
            val ids = downloads.keys.toList()
            value =
                if (ids.isEmpty()) emptyMap()
                else
                    withContext(Dispatchers.IO) {
                        val workIds =
                            ids.chunked(500)
                                .flatMap { graph.library.dao.tracksByIds(it) }
                                .mapTo(LinkedHashSet()) { it.workId }
                                .toList()
                        if (workIds.isEmpty()) emptyMap()
                        else
                            workIds
                                .chunked(500)
                                .flatMap { graph.library.dao.tracksForWorks(it) }
                                .groupBy({ it.workId }, { it.id })
                    }
        }
    val workCountsBySource =
        remember(works) { works.mapNotNull { it.sourceId }.groupingBy { it }.eachCount() }
    val offlineWorks =
        remember(works, trackIdsByWork, downloads) {
            works.filter { work -> trackIdsByWork[work.id].orEmpty().any { it in downloads } }
        }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.title_sources),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                WhispIcons.Outlined.Storage,
                "存储管理",
                Muted,
                haptic = HapticKind.Tick,
                action = storage,
            )
            IconAction(WhispIcons.Outlined.Add, "添加来源", Cream, haptic = HapticKind.Tick) {
                adding = true
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sources, key = { it.id }) { source ->
                val shape = RoundedCornerShape(18.dp)
                val count = workCountsBySource[source.id] ?: 0
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable {
                            when (source.kindType) {
                                SourceKind.ASMRONE -> online(source.id)
                                SourceKind.DLSITE -> dlsite(source.id)
                                else -> selectedId = source.id
                            }
                        }
                        .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Rose.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            sourceIcon(source.kindType),
                            null,
                            tint = Rose,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val statusText =
                            when {
                                source.id in busy ->
                                    if (source.kindType == SourceKind.DLSITE) "同步中" else "扫描中"
                                source.error != null ->
                                    if (source.kindType == SourceKind.DLSITE) "同步失败" else "扫描失败"
                                count > 0 -> "${sourceLabel(source.kindType)} · $count 部作品"
                                else -> sourceLabel(source.kindType)
                            }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                            color =
                                if (source.error != null && source.id !in busy)
                                    MaterialTheme.colorScheme.error
                                else Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (source.id in busy) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 10.dp).size(22.dp),
                            color = Rose,
                            strokeWidth = 2.dp,
                        )
                    } else if (source.kindType in setOf(SourceKind.ASMRONE, SourceKind.DLSITE)) {
                        IconAction(WhispIcons.Outlined.MoreVert, "管理${source.name}", Muted) {
                            selectedId = source.id
                        }
                    } else {
                        IconAction(
                            WhispIcons.Outlined.Refresh,
                            "刷新${source.name}",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            graph.sync.scheduleScan(source.id)
                        }
                    }
                }
            }
            if (sources.isEmpty()) {
                item {
                    WhispEmptyState(
                        icon = WhispIcons.Outlined.CreateNewFolder,
                        title = "尚未配置媒体来源",
                        subtitle = "支持本地目录、SMB、NFS、WebDAV、ASMR.ONE 与 DLsite",
                        modifier = Modifier.padding(top = 36.dp),
                    ) {
                        FilledTonalButton(onClick = { adding = true }) {
                            Icon(WhispIcons.Outlined.CreateNewFolder, null)
                            Spacer(Modifier.width(10.dp))
                            Text("添加作品")
                        }
                    }
                }
            }
            if (offlineWorks.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "离线",
                            style = MaterialTheme.typography.titleLarge,
                            color = Cream,
                            modifier = Modifier.weight(1f),
                        )
                        IconAction(
                            WhispIcons.Outlined.PlayArrow,
                            "继续下载",
                            Rose,
                            haptic = HapticKind.Tick,
                        ) {
                            graph.offline.resumeDownloads()
                        }
                        IconAction(
                            WhispIcons.Outlined.Pause,
                            "暂停下载",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            graph.offline.pauseDownloads()
                        }
                    }
                }
            }
            items(offlineWorks, key = { it.id }) { work ->
                val trackIds = trackIdsByWork[work.id].orEmpty()
                val states = trackIds.mapNotNull { downloads[it] }
                val complete =
                    work.offlineAttachments &&
                        trackIds.all { downloads[it]?.state == Download.STATE_COMPLETED }
                val failed = states.any { it.state == Download.STATE_FAILED }
                val active = states.any {
                    it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
                }
                val paused =
                    !complete && !active && states.any { it.state == Download.STATE_STOPPED }
                val percentage =
                    states.map { it.percentDownloaded.takeIf { value -> value >= 0 } ?: 0f }.sum() /
                        trackIds.size.coerceAtLeast(1)
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable { openWork(work.id) }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(
                            image = work.coverId?.let { WhispImage.Asset(it) },
                            description = null,
                            modifier =
                                Modifier.size(width = 50.dp, height = 40.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                shortTitle(work.title).ifBlank { work.title },
                                style = MaterialTheme.typography.titleSmall,
                                color = Cream,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    complete -> "已离线保存"
                                    failed -> "下载失败"
                                    paused -> "已暂停 · ${percentage.toInt()}%"
                                    else -> "下载中 · ${percentage.toInt()}%"
                                },
                                style =
                                    MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                                color =
                                    when {
                                        failed -> MaterialTheme.colorScheme.error
                                        complete -> Rose.copy(alpha = 0.85f)
                                        else -> Muted
                                    },
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        IconAction(
                            if (complete) WhispIcons.Outlined.DeleteOutline
                            else WhispIcons.Outlined.Refresh,
                            if (complete) "移除离线副本" else "重试下载",
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            if (complete) confirmingRemoveOffline = work
                            else runAction { graph.offline.downloadWork(work.id) }
                        }
                    }
                    if (!complete) {
                        LinearProgressIndicator(
                            progress = { (percentage / 100).coerceIn(0f, 1f) },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                            color = if (failed) MaterialTheme.colorScheme.error else Rose,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        WhispSheet(onDismissRequest = { adding = false }) { dismiss ->
            Text(
                "添加来源",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                WhispSheetRow(
                    icon = WhispIcons.Outlined.AccountCircle,
                    title = "DLsite",
                    subtitle = "登录 viviON ID 同步已购音声",
                    onClick = {
                        dismiss()
                        login.launch(Intent(graph.context, DlsiteLoginActivity::class.java))
                    },
                )
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Headphones,
                    title = "ASMR.ONE",
                    subtitle = "浏览公开作品目录与在线字幕",
                    onClick = {
                        dismiss()
                        runAction { online(graph.library.addAsmrOne()) }
                    },
                )
                WhispSheetRow(
                    icon = WhispIcons.Outlined.FolderOpen,
                    title = "本地目录",
                    subtitle = "授权扫描设备中的音频与视频文件夹",
                    onClick = {
                        dismiss()
                        folder.launch(null)
                    },
                )
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Dns,
                    title = "SMB",
                    subtitle = "连接局域网 SMB 2/3 文件共享",
                    onClick = {
                        dismiss()
                        remoteKind = SourceKind.SMB.raw
                    },
                )
                WhispSheetRow(
                    icon = WhispIcons.Outlined.CloudQueue,
                    title = "WebDAV",
                    subtitle = "通过 HTTPS 挂载远程媒体库",
                    onClick = {
                        dismiss()
                        remoteKind = SourceKind.WEBDAV.raw
                    },
                )
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Lan,
                    title = "NFS",
                    subtitle = "直连 NAS 导出目录（NFS 3 / 4.0 / 4.2）",
                    onClick = {
                        dismiss()
                        remoteKind = SourceKind.NFS.raw
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    sources
        .firstOrNull { it.id == selectedId }
        ?.let { source ->
            WhispSheet(onDismissRequest = { selectedId = null }) { dismiss ->
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(source.name, style = MaterialTheme.typography.headlineSmall, color = Cream)
                    if (source.kindType !in setOf(SourceKind.ASMRONE, SourceKind.DLSITE)) {
                        Text(source.root, color = Muted, fontSize = 13.sp)
                    }
                    source.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (source.kindType in setOf(SourceKind.ASMRONE, SourceKind.DLSITE)) {
                    WhispSheetRow(
                        icon = WhispIcons.Outlined.Explore,
                        title = "浏览",
                        onClick = {
                            dismiss()
                            when (source.kindType) {
                                SourceKind.ASMRONE -> online(source.id)
                                SourceKind.DLSITE -> dlsite(source.id)
                                else -> Unit
                            }
                        },
                    )
                } else {
                    WhispSheetRow(
                        icon = WhispIcons.Outlined.Refresh,
                        title = "刷新",
                        subtitle = "重新扫描此来源的音频与字幕",
                        onClick = {
                            graph.sync.scheduleScan(source.id)
                            dismiss()
                        },
                    )
                }
                if (source.kindType == SourceKind.ASMRONE) {
                    WhispSheetRow(
                        icon = WhispIcons.Outlined.Refresh,
                        title = "更新已加入作品",
                        onClick = {
                            graph.sync.scheduleScan(source.id)
                            dismiss()
                        },
                    )
                }
                if (source.kindType == SourceKind.DLSITE) {
                    WhispSheetRow(
                        icon = WhispIcons.Outlined.Refresh,
                        title = "同步已购作品",
                        onClick = {
                            graph.sync.scheduleScan(source.id)
                            dismiss()
                        },
                    )
                    WhispSheetRow(
                        icon = WhispIcons.AutoMirrored.Outlined.Login,
                        title = "重新登录",
                        onClick = {
                            dismiss()
                            login.launch(
                                Intent(graph.context, DlsiteLoginActivity::class.java)
                                    .putExtra(DlsiteLoginActivity.SOURCE, source.id),
                            )
                        },
                    )
                }
                WhispSheetRow(
                    icon = WhispIcons.Outlined.DeleteOutline,
                    title = if (source.kindType == SourceKind.DLSITE) "退出账号" else "移除来源",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        dismiss()
                        confirmingRemoveSource = source
                    },
                )
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }

    confirmingRemoveSource?.let { source ->
        val isDlsite = source.kindType == SourceKind.DLSITE
        WhispConfirmDialog(
            title = if (isDlsite) "退出 DLsite 账号" else "移除来源",
            message =
                if (isDlsite) "退出账号「${source.name}」并清理已购索引？已离线保存的本地副本会保留。"
                else "移除来源「${source.name}」？未离线保存的作品索引将一并移除。",
            confirmLabel = if (isDlsite) "退出账号" else "移除",
            onConfirm = {
                confirmingRemoveSource = null
                runAction { graph.sync.removeSource(source.id) }
            },
            onDismiss = { confirmingRemoveSource = null },
        )
    }

    confirmingRemoveOffline?.let { work ->
        WhispConfirmDialog(
            title = "移除离线副本",
            message = "移除「${shortTitle(work.title).ifBlank { work.title }}」的离线文件？",
            confirmLabel = "移除",
            onConfirm = {
                confirmingRemoveOffline = null
                runAction { graph.offline.removeOffline(work.id) }
            },
            onDismiss = { confirmingRemoveOffline = null },
        )
    }

    remoteKind?.let { raw ->
        SourceKind.of(raw)?.let { kind -> RemoteDialog(graph, kind) { remoteKind = null } }
    }
}

@Composable
private fun RemoteDialog(graph: AppGraph, kind: SourceKind, dismiss: () -> Unit) {
    var name by rememberSaveable(kind) { mutableStateOf("") }
    var address by rememberSaveable(kind) { mutableStateOf("") }
    var username by rememberSaveable(kind) { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowUnencryptedSmb by rememberSaveable(kind) { mutableStateOf(false) }
    var version by rememberSaveable(kind) { mutableIntStateOf(4) }
    var uid by rememberSaveable(kind) { mutableStateOf("65534") }
    var gid by rememberSaveable(kind) { mutableStateOf("65534") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discoveredServices by
        remember(context, kind) { LanDiscovery.discover(context, kind) }
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val title =
        when (kind) {
            SourceKind.SMB -> "SMB"
            SourceKind.NFS -> "NFS"
            else -> "WebDAV"
        }
    WhispSheet(onDismissRequest = { if (!connecting) dismiss() }, dismissible = !connecting) { close
        ->
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Cream)
            if (discoveredServices.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "发现的局域网设备",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(discoveredServices, key = { "${it.name}|${it.host}:${it.port}" }) {
                            svc ->
                            AssistChip(
                                onClick = {
                                    if (name.isBlank()) name = svc.name
                                    address = svc.suggestedUrl()
                                },
                                label = { Text("${svc.name} (${svc.host})") },
                                leadingIcon = {
                                    Icon(WhispIcons.Outlined.Lan, null, Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth(),
                label = { Text("名称") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                address,
                { address = it },
                Modifier.fillMaxWidth(),
                label = { Text("地址") },
                placeholder = {
                    Text(
                        when (kind) {
                            SourceKind.SMB -> "smb://nas.example/media/Audio/"
                            SourceKind.NFS -> "nfs://nas.example/media/Audio"
                            else -> "https://nas.example/Audio/"
                        },
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            if (kind == SourceKind.NFS) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(3 to "3", 4 to "4.0", 42 to "4.2").forEachIndexed { index, (value, text)
                        ->
                        SegmentedButton(
                            selected = version == value,
                            onClick = { version = value },
                            enabled = !connecting,
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                        ) {
                            Text("NFS $text")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        uid,
                        { uid = it },
                        Modifier.weight(1f),
                        label = { Text("UID") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        gid,
                        { gid = it },
                        Modifier.weight(1f),
                        label = { Text("GID") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            } else {
                OutlinedTextField(
                    username,
                    { username = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("账号") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (kind == SourceKind.SMB) {
                    WhispToggleRow(
                        label = stringResource(R.string.label_smb_compat),
                        checked = allowUnencryptedSmb,
                        enabled = !connecting,
                        onCheckedChange = { allowUnencryptedSmb = it },
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    enabled = !connecting,
                    onClick = close,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    enabled = !connecting,
                    onClick = {
                        connecting = true
                        error = null
                        scope.launch {
                            try {
                                val id =
                                    when (kind) {
                                        SourceKind.SMB ->
                                            graph.library.addSmb(
                                                name,
                                                address,
                                                username,
                                                password,
                                                encrypt = if (allowUnencryptedSmb) false else null,
                                            )
                                        SourceKind.NFS ->
                                            graph.library.addNfs(
                                                name,
                                                address,
                                                version,
                                                uid.trim().toIntOrNull() ?: error("UID 应为非负整数"),
                                                gid.trim().toIntOrNull() ?: error("GID 应为非负整数"),
                                            )
                                        else ->
                                            graph.library.addWebDav(
                                                name,
                                                address,
                                                username,
                                                password,
                                            )
                                    }
                                graph.sync.scheduleScan(id, force = true)
                                close()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "连接失败"
                            } finally {
                                connecting = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("连接", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
