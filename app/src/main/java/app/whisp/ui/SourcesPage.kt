// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.AppGraph
import app.whisp.DlsiteLoginActivity
import app.whisp.R
import app.whisp.data.SourceKind
import app.whisp.data.kindType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SourcesPage(
    graph: AppGraph,
    online: (String) -> Unit,
    dlsite: (String) -> Unit,
    storage: () -> Unit,
) {
    val sources by graph.library.sourceList.collectAsStateWithLifecycle()
    val busy by graph.sync.busy.collectAsStateWithLifecycle()
    val downloads by graph.media.downloads.collectAsStateWithLifecycle()
    val works by graph.library.works.collectAsStateWithLifecycle()
    val trackPairs by
        remember(graph) { graph.library.dao.observeTrackWorkPairs() }
            .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    var adding by rememberSaveable { mutableStateOf(false) }
    var remoteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

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

    val trackIdsByWork = remember(trackPairs) { trackPairs.groupBy({ it.workId }, { it.id }) }
    val offlineWorks =
        remember(works, trackIdsByWork, downloads) {
            works.filter { work -> trackIdsByWork[work.id].orEmpty().any { it in downloads } }
        }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.title_sources),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    modifier = Modifier.weight(1f),
                )
                IconAction(Icons.Outlined.Storage, "存储管理", Muted, action = storage)
                IconAction(Icons.Outlined.Add, "添加来源") { adding = true }
            }
        }
        items(sources, key = { it.id }) { source ->
            Row(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(18.dp))
                    .clickable {
                        when (source.kindType) {
                            SourceKind.ASMRONE -> online(source.id)
                            SourceKind.DLSITE -> dlsite(source.id)
                            else -> selectedId = source.id
                        }
                    }
                    .padding(start = 18.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (source.kindType == SourceKind.LOCAL) Icons.Outlined.FolderOpen
                    else Icons.Outlined.CloudQueue,
                    null,
                    tint = Rose,
                )
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(source.name, fontSize = 17.sp)
                    if (source.id in busy) {
                        Text(
                            if (source.kindType == SourceKind.DLSITE) "同步中" else "扫描中",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    } else if (source.error != null) {
                        Text(
                            if (source.kindType == SourceKind.DLSITE) "同步失败" else "扫描失败",
                            color = Rose,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (source.id in busy) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp).padding(3.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (source.kindType in setOf(SourceKind.ASMRONE, SourceKind.DLSITE)) {
                    IconAction(Icons.Outlined.MoreVert, "管理${source.name}", Muted) {
                        selectedId = source.id
                    }
                } else {
                    IconAction(Icons.Outlined.Refresh, "刷新${source.name}", Muted) {
                        graph.sync.scheduleScan(source.id)
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            item {
                FilledTonalButton(
                    onClick = { adding = true },
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null)
                    Spacer(Modifier.width(10.dp))
                    Text("添加作品")
                }
            }
        }
        if (offlineWorks.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("离线", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconAction(Icons.Outlined.PlayArrow, "继续下载", Rose) {
                        graph.offline.resumeDownloads()
                    }
                    IconAction(Icons.Outlined.Pause, "暂停下载", Muted) {
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
            val percentage =
                states.map { it.percentDownloaded.takeIf { value -> value >= 0 } ?: 0f }.sum() /
                    trackIds.size.coerceAtLeast(1)
            Column(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(18.dp))
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(work.title, Modifier.weight(1f), maxLines = 2, fontSize = 15.sp)
                    IconAction(
                        if (complete) Icons.Outlined.DeleteOutline else Icons.Outlined.Refresh,
                        if (complete) "移除离线副本" else "重试下载",
                        Muted,
                    ) {
                        if (complete) runAction { graph.offline.removeOffline(work.id) }
                        else runAction { graph.offline.downloadWork(work.id) }
                    }
                }
                if (!complete) {
                    LinearProgressIndicator(
                        progress = { (percentage / 100).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        trackColor = Raised,
                    )
                    Text(
                        if (failed) "下载失败" else "${percentage.toInt()}%",
                        color = Muted,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = Surface,
            title = { Text("添加来源") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AddSourceRow(Icons.Outlined.AccountCircle, "DLsite") {
                        adding = false
                        login.launch(Intent(graph.context, DlsiteLoginActivity::class.java))
                    }
                    AddSourceRow(Icons.Outlined.Headphones, "ASMR.ONE") {
                        adding = false
                        runAction { online(graph.library.addAsmrOne()) }
                    }
                    AddSourceRow(Icons.Outlined.FolderOpen, "本地目录") {
                        adding = false
                        folder.launch(null)
                    }
                    AddSourceRow(Icons.Outlined.Storage, "SMB") {
                        adding = false
                        remoteKind = SourceKind.SMB.raw
                    }
                    AddSourceRow(Icons.Outlined.CloudQueue, "WebDAV") {
                        adding = false
                        remoteKind = SourceKind.WEBDAV.raw
                    }
                    AddSourceRow(Icons.Outlined.Storage, "NFS") {
                        adding = false
                        remoteKind = SourceKind.NFS.raw
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }

    sources
        .firstOrNull { it.id == selectedId }
        ?.let { source ->
            AlertDialog(
                onDismissRequest = { selectedId = null },
                containerColor = Surface,
                title = { Text(source.name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (source.kindType !in setOf(SourceKind.ASMRONE, SourceKind.DLSITE))
                            Text(source.root, color = Muted, fontSize = 13.sp)
                        source.error?.let { Text(it, color = Rose) }
                        if (source.kindType == SourceKind.ASMRONE) {
                            TextButton(
                                onClick = {
                                    graph.sync.scheduleScan(source.id)
                                    selectedId = null
                                },
                            ) {
                                Text("更新已加入作品")
                            }
                        }
                        if (source.kindType == SourceKind.DLSITE) {
                            TextButton(
                                onClick = {
                                    selectedId = null
                                    login.launch(
                                        Intent(graph.context, DlsiteLoginActivity::class.java)
                                            .putExtra(DlsiteLoginActivity.SOURCE, source.id),
                                    )
                                },
                            ) {
                                Text("重新登录")
                            }
                            TextButton(
                                onClick = {
                                    graph.sync.scheduleScan(source.id)
                                    selectedId = null
                                },
                            ) {
                                Text("同步已购作品")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            when (source.kindType) {
                                SourceKind.ASMRONE -> online(source.id)
                                SourceKind.DLSITE -> dlsite(source.id)
                                else -> graph.sync.scheduleScan(source.id)
                            }
                            selectedId = null
                        },
                    ) {
                        Text(
                            if (source.kindType in setOf(SourceKind.ASMRONE, SourceKind.DLSITE))
                                "浏览"
                            else "刷新",
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            runAction { graph.sync.removeSource(source.id) }
                            selectedId = null
                        },
                    ) {
                        Text(if (source.kindType == SourceKind.DLSITE) "退出账号" else "移除来源")
                    }
                },
            )
        }

    remoteKind?.let { raw ->
        SourceKind.of(raw)?.let { kind -> RemoteDialog(graph, kind) { remoteKind = null } }
    }
}

@Composable
private fun AddSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label)
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
    val scope = rememberCoroutineScope()
    val title =
        when (kind) {
            SourceKind.SMB -> "SMB"
            SourceKind.NFS -> "NFS"
            else -> "WebDAV"
        }
    AlertDialog(
        onDismissRequest = { if (!connecting) dismiss() },
        containerColor = Surface,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(
                    address,
                    { address = it },
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
                )
                if (kind == SourceKind.NFS) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(3 to "3", 4 to "4.0", 42 to "4.2").forEachIndexed {
                            index,
                            (value, text) ->
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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            gid,
                            { gid = it },
                            Modifier.weight(1f),
                            label = { Text("GID") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                } else {
                    OutlinedTextField(
                        username,
                        { username = it },
                        label = { Text("账号") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (kind == SourceKind.SMB) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.label_smb_compat),
                                Modifier.weight(1f),
                                fontSize = 14.sp,
                                color = Muted,
                            )
                            Switch(
                                checked = allowUnencryptedSmb,
                                onCheckedChange = { allowUnencryptedSmb = it },
                                enabled = !connecting,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = Rose) }
            }
        },
        confirmButton = {
            TextButton(
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
                                        graph.library.addWebDav(name, address, username, password)
                                }
                            graph.sync.scheduleScan(id, force = true)
                            dismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "连接失败"
                        } finally {
                            connecting = false
                        }
                    }
                },
            ) {
                if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("连接")
            }
        },
        dismissButton = { TextButton(enabled = !connecting, onClick = dismiss) { Text("取消") } },
    )
}
