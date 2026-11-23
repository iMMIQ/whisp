// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.DlsiteLoginActivity
import app.whisp.data.DlsiteNoPlayableAudioException
import app.whisp.data.DlsiteOwnedWork
import app.whisp.data.WhispImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DlsiteLibraryPage(
    graph: AppGraph,
    sourceId: String,
    back: () -> Unit,
    open: (String) -> Unit,
) {
    val flow = remember(sourceId) { graph.library.dlsitePurchases(sourceId) }
    val works by flow.collectAsStateWithLifecycle(emptyList())
    val sources by graph.library.sourceList.collectAsStateWithLifecycle()
    val busy by graph.sync.busy.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == sourceId }
    val synchronizing = sourceId in busy || source?.lastScan == 0L && source.error == null
    var query by rememberSaveable(sourceId) { mutableStateOf("") }
    var sortByTitle by rememberSaveable(sourceId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<String?>(null) }
    var officialPlayer by remember { mutableStateOf<DlsiteOwnedWork?>(null) }
    val scope = rememberCoroutineScope()
    val login =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK) graph.sync.scheduleScan(sourceId)
        }
    val visible =
        remember(works, query, sortByTitle) {
            val text = query.trim()
            val filtered = works.filter {
                text.isBlank() ||
                    listOf(it.title, it.artist, it.circle, it.workno).any { field ->
                        field.contains(text, true)
                    }
            }
            if (sortByTitle) filtered.sortedBy { it.title } else filtered
        }
    Column(Modifier.fillMaxSize().testTag("dlsite-library")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", action = back)
            Text("已购作品", Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 28.sp)
            Box {
                IconAction(Icons.Outlined.MoreVert, "管理 DLsite 账号") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("同步") },
                        onClick = {
                            menu = false
                            graph.sync.scheduleScan(sourceId)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (sortByTitle) "按购买时间" else "按作品名") },
                        onClick = {
                            menu = false
                            sortByTitle = !sortByTitle
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重新登录") },
                        onClick = {
                            menu = false
                            login.launch(
                                Intent(graph.context, DlsiteLoginActivity::class.java)
                                    .putExtra(DlsiteLoginActivity.SOURCE, sourceId),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出诊断") },
                        onClick = {
                            menu = false
                            scope.launch {
                                runCatching {
                                    val share =
                                        withContext(Dispatchers.IO) {
                                            graph.dlsiteDiagnostics.shareIntent()
                                        }
                                    graph.context.startActivity(
                                        Intent.createChooser(share, "导出诊断")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                    .onFailure {
                                        graph.messages.tryEmit(it.message ?: "导出失败")
                                    }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("退出账号") },
                        onClick = {
                            menu = false
                            scope.launch { graph.sync.removeSource(sourceId) }
                            back()
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("dlsite-search"),
            placeholder = { Text("搜索作品、声优、社团或编号") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
        )
        if (synchronizing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        source?.error?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, Modifier.weight(1f), color = Rose)
                TextButton(
                    onClick = {
                        if (message.contains("登录"))
                            login.launch(
                                Intent(graph.context, DlsiteLoginActivity::class.java)
                                    .putExtra(DlsiteLoginActivity.SOURCE, sourceId),
                            )
                        else graph.sync.scheduleScan(sourceId)
                    },
                ) {
                    Text(if (message.contains("登录")) "登录" else "重试")
                }
            }
        }
        Box(Modifier.weight(1f)) {
            WorkGrid(
                items = visible,
                key = { it.workno },
                workOf = { it.entity(sourceId) },
                imageOf = { work ->
                    val entity = work.entity(sourceId)
                    val coverId = entity.coverId
                    val coverUrl = work.coverUrl
                    if (coverId != null && coverUrl != null)
                        WhispImage.Catalog(coverId, coverUrl, sourceId)
                    else null
                },
                onClick = { work ->
                    if (opening == null) {
                        opening = work.workno
                        scope.launch {
                            try {
                                open(graph.sync.importDlsiteWork(sourceId, work.workno).work.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: DlsiteNoPlayableAudioException) {
                                officialPlayer = work
                            } catch (e: Exception) {
                                graph.messages.tryEmit(e.message ?: "作品读取失败")
                            } finally {
                                opening = null
                            }
                        }
                    }
                },
            )
            if (opening != null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (visible.isEmpty() && !synchronizing) {
                Text(
                    if (query.isBlank()) "暂无已购音声" else "没有匹配的作品",
                    Modifier.align(Alignment.Center),
                    color = Muted,
                )
            }
        }
    }
    officialPlayer?.let { work ->
        AlertDialog(
            onDismissRequest = { officialPlayer = null },
            title = { Text("使用官方播放器") },
            text = { Text("该作品可在 DLsite Play 中播放。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        officialPlayer = null
                        if (graph.playback.state.value.playWhenReady) graph.playback.togglePlay()
                        graph.context.startActivity(
                            Intent(
                                    Intent.ACTION_VIEW,
                                    "https://play.dlsite.com/work/${work.workno}".toUri(),
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) {
                    Text("打开 DLsite Play")
                }
            },
            dismissButton = { TextButton(onClick = { officialPlayer = null }) { Text("返回") } },
        )
    }
}
