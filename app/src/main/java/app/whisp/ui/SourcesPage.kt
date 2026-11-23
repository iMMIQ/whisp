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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import app.whisp.DlsiteLoginActivity
import app.whisp.data.SourceKind
import app.whisp.data.kindType
import app.whisp.playback.OfflineService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SourcesPage(
    model: AppViewModel,
    online: (String) -> Unit,
    dlsite: (String) -> Unit,
    storage: () -> Unit,
) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var remote by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val login =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == android.app.Activity.RESULT_OK)
                result.data?.getStringExtra(DlsiteLoginActivity.SOURCE)?.let(dlsite)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null)
                model.action {
                    model.repository.context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val id = DocumentsContract.getTreeDocumentId(uri)
                    model.repository.addLocal(
                        uri.toString(),
                        id.substringAfterLast('/').substringAfter(':').ifBlank { "本地作品" },
                    )
                }
        }
    val offlineWorks =
        remember(library, downloads) {
            library.filter { detail -> detail.tracks.any { downloads.containsKey(it.id) } }
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    androidx.compose.ui.res.stringResource(app.whisp.R.string.title_sources),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    modifier = Modifier.weight(1f),
                )
                IconAction(Icons.Outlined.Storage, "存储管理", Muted, storage)
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
                    if (source.id in busy)
                        Text(
                            if (source.kindType == SourceKind.DLSITE) "同步中" else "扫描中",
                            color = Muted,
                            fontSize = 13.sp,
                        )
                    else if (source.error != null)
                        Text(
                            if (source.kindType == SourceKind.DLSITE) "同步失败" else "扫描失败",
                            color = Rose,
                            fontSize = 13.sp,
                        )
                }
                if (source.id in busy)
                    CircularProgressIndicator(
                        Modifier.size(22.dp).padding(3.dp),
                        strokeWidth = 2.dp,
                    )
                else if (source.kindType in setOf(SourceKind.ASMRONE, SourceKind.DLSITE))
                    IconAction(Icons.Outlined.MoreVert, "管理${source.name}", Muted) {
                        selectedId = source.id
                    }
                else
                    IconAction(Icons.Outlined.Refresh, "刷新${source.name}", Muted) {
                        model.repository.scheduleScan(source.id)
                    }
            }
        }
        if (sources.isEmpty())
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
        if (offlineWorks.isNotEmpty())
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("离线", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconAction(Icons.Outlined.PlayArrow, "继续下载", Rose) {
                        DownloadService.sendResumeDownloads(
                            model.repository.context,
                            OfflineService::class.java,
                            false,
                        )
                    }
                    IconAction(Icons.Outlined.Pause, "暂停下载", Muted) {
                        DownloadService.sendPauseDownloads(
                            model.repository.context,
                            OfflineService::class.java,
                            false,
                        )
                    }
                }
            }
        items(offlineWorks, key = { it.work.id }) { detail ->
            val states = detail.tracks.mapNotNull { downloads[it.id] }
            val complete = model.repository.offlineStore.isOffline(detail)
            val failed = states.any { it.state == Download.STATE_FAILED }
            val percentage =
                states.map { it.percentDownloaded.takeIf { value -> value >= 0 } ?: 0f }.sum() /
                    detail.tracks.size.coerceAtLeast(1)
            Column(
                Modifier.fillMaxWidth()
                    .background(Surface, RoundedCornerShape(18.dp))
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(detail.work.title, Modifier.weight(1f), maxLines = 2, fontSize = 15.sp)
                    IconAction(
                        if (complete) Icons.Outlined.DeleteOutline else Icons.Outlined.Refresh,
                        if (complete) "移除离线副本" else "重试下载",
                        Muted,
                    ) {
                        if (complete)
                            model.action {
                                model.repository.offlineStore.removeOffline(detail.work.id)
                            }
                        else model.download(detail.work.id)
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
    if (adding)
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = Surface,
            title = { Text("添加来源") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            adding = false
                            login.launch(
                                Intent(model.repository.context, DlsiteLoginActivity::class.java),
                            )
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.AccountCircle, null)
                        Spacer(Modifier.width(12.dp))
                        Text("DLsite")
                    }
                    TextButton(
                        onClick = {
                            adding = false
                            model.action { online(model.repository.addAsmrOne()) }
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Headphones, null)
                        Spacer(Modifier.width(12.dp))
                        Text("ASMR.ONE")
                    }
                    TextButton(
                        onClick = {
                            adding = false
                            folder.launch(null)
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Spacer(Modifier.width(12.dp))
                        Text("本地目录")
                    }
                    TextButton(
                        onClick = {
                            adding = false
                            remote = "SMB"
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Storage, null)
                        Spacer(Modifier.width(12.dp))
                        Text("SMB")
                    }
                    TextButton(
                        onClick = {
                            adding = false
                            remote = "WebDAV"
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.CloudQueue, null)
                        Spacer(Modifier.width(12.dp))
                        Text("WebDAV")
                    }
                    TextButton(
                        onClick = {
                            adding = false
                            remote = "NFS"
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Storage, null)
                        Spacer(Modifier.width(12.dp))
                        Text("NFS")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
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
                        if (source.kindType == SourceKind.ASMRONE)
                            TextButton(
                                onClick = {
                                    model.repository.scheduleScan(source.id)
                                    selectedId = null
                                },
                            ) {
                                Text("更新已加入作品")
                            }
                        if (source.kindType == SourceKind.DLSITE) {
                            TextButton(
                                onClick = {
                                    selectedId = null
                                    login.launch(
                                        Intent(
                                                model.repository.context,
                                                DlsiteLoginActivity::class.java,
                                            )
                                            .putExtra(DlsiteLoginActivity.SOURCE, source.id),
                                    )
                                },
                            ) {
                                Text("重新登录")
                            }
                            TextButton(
                                onClick = {
                                    model.repository.scheduleScan(source.id)
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
                            if (source.kindType == SourceKind.ASMRONE) online(source.id)
                            else if (source.kindType == SourceKind.DLSITE) dlsite(source.id)
                            else model.repository.scheduleScan(source.id)
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
                            model.removeSource(source.id)
                            selectedId = null
                        },
                    ) {
                        Text(if (source.kindType == SourceKind.DLSITE) "退出账号" else "移除来源")
                    }
                },
            )
        }
    remote?.let { kind -> RemoteDialog(model, kind) { remote = null } }
}

@Composable
private fun RemoteDialog(model: AppViewModel, kind: String, dismiss: () -> Unit) {
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
    AlertDialog(
        onDismissRequest = { if (!connecting) dismiss() },
        containerColor = Surface,
        title = { Text(kind) },
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
                                "SMB" -> "smb://nas.example/media/Audio/"
                                "NFS" -> "nfs://nas.example/media/Audio"
                                else -> "https://nas.example/Audio/"
                            },
                        )
                    },
                    singleLine = true,
                )
                if (kind == "NFS") {
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
                    if (kind == "SMB") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    app.whisp.R.string.label_smb_compat,
                                ),
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
                            when (kind) {
                                "SMB" ->
                                    model.repository.addSmb(
                                        name,
                                        address,
                                        username,
                                        password,
                                        encrypt = if (allowUnencryptedSmb) false else null,
                                    )
                                "NFS" ->
                                    model.repository.addNfs(
                                        name,
                                        address,
                                        version,
                                        uid.trim().toIntOrNull() ?: error("UID 应为非负整数"),
                                        gid.trim().toIntOrNull() ?: error("GID 应为非负整数"),
                                    )
                                else ->
                                    model.repository.addWebDav(name, address, username, password)
                            }
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
