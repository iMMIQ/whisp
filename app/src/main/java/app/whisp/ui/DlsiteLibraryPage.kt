// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.DlsiteLoginActivity
import app.whisp.data.DlsiteOwnedWork
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DlsiteGridItem(
    val owned: DlsiteOwnedWork,
    val entity: WorkEntity,
    val image: WhispImage?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DlsiteLibraryPage(
    graph: AppGraph,
    sourceId: String,
    back: () -> Unit,
    openOwned: (String) -> Unit,
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
    var confirmingLogout by rememberSaveable(sourceId) { mutableStateOf(false) }
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
    val gridItems =
        remember(visible, sourceId) {
            visible.map { work ->
                val entity = work.entity(sourceId)
                val coverId = entity.coverId
                val coverUrl = work.coverUrl
                val image =
                    if (coverId != null && coverUrl != null)
                        WhispImage.Catalog(coverId, coverUrl, sourceId)
                    else null
                DlsiteGridItem(work, entity, image)
            }
        }
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("dlsite-library")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", action = back)
            Text(
                "已购作品",
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
            )
            Box {
                IconAction(Icons.Outlined.MoreVert, "管理 DLsite 账号") { menu = true }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
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
                        text = { Text("退出账号", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            confirmingLogout = true
                        },
                    )
                }
            }
        }
        WhispSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索作品、声优、社团或编号",
            modifier =
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("dlsite-search"),
        )
        if (synchronizing && works.isEmpty()) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Rose,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        source?.error?.let { message ->
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
        PullToRefreshBox(
            isRefreshing = synchronizing && works.isNotEmpty(),
            onRefresh = { graph.sync.scheduleScan(sourceId) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            WorkGrid(
                items = gridItems,
                key = { it.owned.workno },
                workOf = { it.entity },
                imageOf = { it.image },
                onClick = { item -> openOwned(item.owned.workno) },
            )
            if (visible.isEmpty() && !synchronizing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WhispEmptyState(
                        icon =
                            if (query.isBlank()) Icons.Outlined.AccountCircle
                            else Icons.Outlined.Search,
                        title = if (query.isBlank()) "暂无已购音声" else "没有匹配的作品",
                    )
                }
            }
        }
    }
    if (confirmingLogout) {
        WhispConfirmDialog(
            title = "退出 DLsite 账号",
            message = "退出账号并清理已购作品索引？已离线保存的本地副本会保留。",
            confirmLabel = "确认退出",
            onConfirm = {
                confirmingLogout = false
                graph.scope.launch { graph.sync.removeSource(sourceId) }
                back()
            },
            onDismiss = { confirmingLogout = false },
        )
    }
}
