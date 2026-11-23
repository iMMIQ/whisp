// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.whisp.data.*
import kotlinx.coroutines.launch

@Composable
internal fun AsmrOnePage(
    model: AppViewModel,
    sourceId: String,
    back: () -> Unit,
    open: (AsmrWork) -> Unit,
) {
    val active by model.asmrQuery.collectAsStateWithLifecycle()
    val query = active?.takeIf { it.sourceId == sourceId } ?: AsmrQuery(sourceId)
    var text by rememberSaveable(sourceId) { mutableStateOf(query.text) }
    var editing by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    val history by model.asmrHistory.collectAsStateWithLifecycle()
    val facets by model.asmrFacets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    val works = model.asmrCatalog.collectAsLazyPagingItems()
    fun submit(next: AsmrQuery) {
        text = next.text
        focus.clearFocus()
        editing = false
        if (query == next) works.refresh() else model.submitAsmrQuery(next)
        scope.launch {
            grid.scrollToItem(0)
            list.scrollToItem(0)
        }
    }
    var displayedQuery by rememberSaveable(sourceId) { mutableStateOf(encodeAsmrQuery(query)) }
    LaunchedEffect(query) {
        val encoded = encodeAsmrQuery(query)
        if (displayedQuery != encoded) {
            text = query.text
            displayedQuery = encoded
            grid.scrollToItem(0)
            list.scrollToItem(0)
        }
    }
    LaunchedEffect(sourceId) {
        if (model.asmrQuery.value?.sourceId != sourceId) model.submitAsmrQuery(AsmrQuery(sourceId))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", action = back)
            Text("ASMR.ONE", Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 28.sp)
            IconAction(
                Icons.Outlined.Tune,
                "筛选作品",
                if (query.facets.isNotEmpty() || query.subtitlesOnly) Rose else Muted,
            ) {
                focus.clearFocus()
                filters = true
            }
            IconAction(Icons.Outlined.Refresh, "刷新在线作品", Muted) { works.refresh() }
        }
        OutlinedTextField(
            text,
            { text = it.take(300) },
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("asmr-search")
                .onFocusChanged { editing = it.isFocused },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text("作品、声优、RJ") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(onSearch = { submit(query.copy(text = text.trim())) }),
            trailingIcon = {
                Row {
                    if (text.isNotEmpty())
                        IconAction(Icons.Outlined.Close, "清空检索") {
                            text = ""
                            submit(query.copy(text = ""))
                        }
                    IconAction(Icons.Outlined.Search, "提交在线搜索") {
                        submit(query.copy(text = text.trim()))
                    }
                }
            },
        )
        if (query.facets.isNotEmpty() || query.subtitlesOnly)
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(query.facets, key = { "${it.kind}|${it.name}" }) { facet ->
                    InputChip(
                        selected = true,
                        onClick = {
                            submit(query.copy(text = text.trim(), facets = query.facets - facet))
                        },
                        label = { Text(facet.name) },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, "移除${facet.name}", Modifier.size(16.dp))
                        },
                    )
                }
                if (query.subtitlesOnly)
                    item {
                        InputChip(
                            selected = true,
                            onClick = {
                                submit(query.copy(text = text.trim(), subtitlesOnly = false))
                            },
                            label = { Text("有字幕") },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, "移除字幕筛选", Modifier.size(16.dp))
                            },
                        )
                    }
            }
        if (editing) {
            val suggestedHistory =
                history.filter { text.isBlank() || it.contains(text, ignoreCase = true) }.take(4)
            val suggestedFacets =
                if (text.isBlank()) emptyList()
                else facets.filter { it.name.contains(text, true) && it !in query.facets }.take(4)
            if (suggestedHistory.isNotEmpty() || suggestedFacets.isNotEmpty())
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    suggestedHistory.forEach { value ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { submit(query.copy(text = value)) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.History, null, Modifier.size(20.dp), tint = Muted)
                            Text(
                                value,
                                Modifier.weight(1f).padding(start = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    suggestedFacets.forEach { facet ->
                        TextButton(
                            onClick = {
                                submit(
                                    query.copy(
                                        text = "",
                                        facets = (query.facets + facet).distinct(),
                                    ),
                                )
                            },
                        ) {
                            Text(
                                "${facet.label} · ${facet.name}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (suggestedHistory.isNotEmpty())
                        TextButton(
                            onClick = {
                                model.action { model.repository.clearAsmrSearchHistory() }
                            },
                            Modifier.align(Alignment.End),
                        ) {
                            Text("清除记录")
                        }
                }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
            listOf("create_date" to "最新", "dl_count" to "热门").forEach { (order, title) ->
                TextButton(
                    onClick = { submit(query.copy(text = text.trim(), order = order)) },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = if (query.order == order) Rose else Muted,
                        ),
                ) {
                    Text(title, fontSize = 15.sp)
                }
            }
        }
        val refreshing = works.loadState.refresh
        if (refreshing is LoadState.Error && works.itemCount > 0)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("刷新失败", Modifier.weight(1f), color = Muted)
                TextButton(onClick = { works.retry() }) { Text("重试") }
            }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.searching)
                LazyColumn(
                    Modifier.fillMaxSize().testTag("asmr-results"),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(works.itemCount, key = works.itemKey { it.workId }) { index ->
                        works[index]?.let { work ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                    open(work)
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(
                                    model,
                                    work.coverId,
                                    work.title,
                                    Modifier.size(width = 100.dp, height = 112.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    catalogUrl = work.coverUrl,
                                    catalogSourceId = sourceId,
                                )
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(
                                        work.title,
                                        fontSize = 17.sp,
                                        lineHeight = 24.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val voice = work.facets.firstOrNull { it.kind == "va" }
                                    if (work.artist.isNotBlank())
                                        Text(
                                            work.artist,
                                            Modifier.padding(top = 8.dp)
                                                .then(
                                                    if (voice != null)
                                                        Modifier.clickable {
                                                            submit(
                                                                query.copy(
                                                                    text = "",
                                                                    facets =
                                                                        (query.facets + voice)
                                                                            .distinct(),
                                                                ),
                                                            )
                                                        }
                                                    else Modifier,
                                                ),
                                            color = Muted,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                }
                            }
                        }
                    }
                    item { CatalogAppend(works.loadState.append) { works.retry() } }
                }
            else
                LazyVerticalGrid(
                    GridCells.Adaptive(minSize = 160.dp),
                    Modifier.fillMaxSize().testTag("asmr-grid"),
                    state = grid,
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(
                        works.itemCount,
                        key = works.itemKey { it.workId },
                        span = { GridItemSpan(if (it == 0) maxLineSpan else 1) },
                    ) { index ->
                        works[index]?.let {
                            WorkCard(it.entity(), model, index == 0, it.coverUrl, sourceId) {
                                open(it)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatalogAppend(works.loadState.append) { works.retry() }
                    }
                }
            if (works.itemCount == 0)
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (refreshing) {
                        is LoadState.Loading -> CircularProgressIndicator()
                        is LoadState.Error -> {
                            Text(refreshing.error.message ?: "连接失败", color = Muted)
                            TextButton(onClick = { works.retry() }) { Text("重试") }
                        }
                        else ->
                            Text(
                                if (query.searching) "没有匹配的作品" else "暂无作品",
                                color = Muted,
                                fontSize = 17.sp,
                            )
                    }
                }
        }
    }
    if (filters)
        AsmrFilters(query, facets, { filters = false }) { next ->
            submit(next.copy(text = text.trim()))
        }
}

@Composable
private fun CatalogAppend(state: LoadState, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is LoadState.Loading ->
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            is LoadState.Error -> TextButton(onClick = retry) { Text("重试") }
            else -> Unit
        }
    }
}

@Composable
private fun AsmrFilters(
    query: AsmrQuery,
    choices: List<AsmrFacet>,
    close: () -> Unit,
    apply: (AsmrQuery) -> Unit,
) {
    var selected by remember { mutableStateOf(query.facets) }
    var subtitles by remember { mutableStateOf(query.subtitlesOnly) }
    var kind by remember { mutableStateOf("va") }
    var name by rememberSaveable { mutableStateOf("") }
    WhispSheet(close) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("筛选", fontFamily = FontFamily.Serif, fontSize = 26.sp)
            Row {
                listOf("va" to "声优", "circle" to "社团", "tagw" to "标签").forEach { (value, title) ->
                    TextButton(
                        onClick = {
                            kind = value
                            name = ""
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = if (kind == value) Rose else Muted,
                            ),
                    ) {
                        Text(title)
                    }
                }
            }
            OutlinedTextField(
                name,
                { name = it.take(160) },
                Modifier.fillMaxWidth().testTag("asmr-facet-input"),
                singleLine = true,
                placeholder = {
                    Text(
                        when (kind) {
                            "va" -> "声优"
                            "circle" -> "社团"
                            else -> "标签"
                        },
                    )
                },
                trailingIcon = {
                    IconAction(
                        Icons.Outlined.Add,
                        "添加筛选",
                        enabled = name.isNotBlank() && '$' !in name,
                    ) {
                        selected = (selected + AsmrFacet(kind, name.trim())).distinct()
                        name = ""
                    }
                },
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 220.dp)) {
                items(
                    choices
                        .filter {
                            it.kind == kind &&
                                (name.isBlank() || it.name.contains(name, true)) &&
                                it !in selected
                        }
                        .take(20),
                    key = { "${it.kind}|${it.name}" },
                ) { facet ->
                    TextButton(
                        onClick = {
                            selected = (selected + facet).distinct()
                            name = ""
                        },
                        Modifier.fillMaxWidth(),
                    ) {
                        Text(facet.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selected, key = { "${it.kind}|${it.name}" }) { facet ->
                    InputChip(
                        selected = true,
                        onClick = { selected = selected - facet },
                        label = { Text(facet.name) },
                        trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("有字幕", Modifier.weight(1f))
                Switch(subtitles, { subtitles = it })
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        selected = emptyList()
                        subtitles = false
                    },
                    Modifier.weight(1f),
                ) {
                    Text("重置")
                }
                Button(
                    onClick = {
                        val pending =
                            name
                                .trim()
                                .takeIf { it.isNotBlank() && '$' !in it }
                                ?.let { AsmrFacet(kind, it) }
                        apply(
                            query.copy(
                                facets = (selected + listOfNotNull(pending)).distinct(),
                                subtitlesOnly = subtitles,
                            ),
                        )
                        dismiss()
                    },
                    Modifier.weight(1f),
                ) {
                    Text("应用")
                }
            }
        }
    }
}
