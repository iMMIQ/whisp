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
import app.whisp.AppGraph
import app.whisp.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AsmrCatalogState(
    val items: List<AsmrWork> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val refreshing: Boolean = false,
    val refreshError: String? = null,
    val appending: Boolean = false,
    val appendError: String? = null,
)

/** Lightweight stateful pagination replacing Paging 3 + AsmrPagingSource. */
class AsmrCatalogController(
    private val scope: CoroutineScope,
    private val fetchPage: suspend (AsmrQuery, Int) -> AsmrPage,
    private val rememberSearch: suspend (String) -> Unit = {},
) {
    val query = MutableStateFlow<AsmrQuery?>(null)
    val state = MutableStateFlow(AsmrCatalogState())
    val facets = MutableStateFlow<List<AsmrFacet>>(emptyList())

    private var refreshJob: Job? = null
    private var appendJob: Job? = null
    private val knownFacets = LinkedHashSet<AsmrFacet>()

    fun submit(next: AsmrQuery) {
        val previous = query.value
        query.value = next
        if (next.text.isNotBlank()) scope.launch { rememberSearch(next.text) }
        refresh(clearItems = previous != next)
    }

    fun refresh(clearItems: Boolean = false) {
        val active = query.value ?: return
        refreshJob?.cancel()
        appendJob?.cancel()
        state.update {
            if (clearItems) AsmrCatalogState(refreshing = true)
            else
                it.copy(
                    refreshing = true,
                    refreshError = null,
                    appending = false,
                    appendError = null,
                )
        }
        refreshJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { fetchPage(active, 1) }
                recordFacets(page.works)
                state.value =
                    AsmrCatalogState(
                        items = page.works,
                        page = 1,
                        hasMore = page.hasMore,
                        refreshing = false,
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(refreshing = false, refreshError = e.message ?: "连接失败")
                }
            }
        }
    }

    fun loadNextPage() {
        val active = query.value ?: return
        val current = state.value
        if (
            current.refreshing ||
                current.appending ||
                !current.hasMore ||
                current.appendError != null
        ) {
            return
        }
        val nextPage = current.page + 1
        state.update { it.copy(appending = true, appendError = null) }
        appendJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { fetchPage(active, nextPage) }
                recordFacets(page.works)
                state.update { prev ->
                    val seen = prev.items.mapTo(HashSet()) { it.workId }
                    prev.copy(
                        items = prev.items + page.works.filter { it.workId !in seen },
                        page = nextPage,
                        hasMore = page.hasMore,
                        appending = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(appending = false, appendError = e.message ?: "加载失败")
                }
            }
        }
    }

    fun retryAppend() {
        state.update { it.copy(appendError = null) }
        loadNextPage()
    }

    private fun recordFacets(works: List<AsmrWork>) {
        var changed = false
        for (work in works) {
            for (facet in work.facets) {
                if (knownFacets.size < 200 && knownFacets.add(facet)) changed = true
            }
        }
        if (changed) facets.value = knownFacets.toList()
    }
}

@Composable
internal fun AsmrOnePage(
    graph: AppGraph,
    sourceId: String,
    initialFacet: AsmrFacet? = null,
    back: () -> Unit,
    open: (AsmrWork) -> Unit,
) {
    val catalog = graph.asmrCatalog
    val active by catalog.query.collectAsStateWithLifecycle()
    val state by catalog.state.collectAsStateWithLifecycle()
    val query = active?.takeIf { it.sourceId == sourceId } ?: AsmrQuery(sourceId)
    var text by rememberSaveable(sourceId) { mutableStateOf(query.text) }
    var editing by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    val history by graph.settings.asmrSearchHistory.collectAsStateWithLifecycle()
    val facets by catalog.facets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()

    fun submit(next: AsmrQuery) {
        text = next.text
        focus.clearFocus()
        editing = false
        if (query == next) catalog.refresh(clearItems = false) else catalog.submit(next)
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
    LaunchedEffect(sourceId, initialFacet) {
        if (initialFacet != null) {
            catalog.submit(AsmrQuery(sourceId, facets = listOf(initialFacet)))
        } else if (catalog.query.value?.sourceId != sourceId) {
            catalog.submit(AsmrQuery(sourceId))
        }
    }

    // Trigger next-page pagination when scrolling near the end of either list or grid.
    val nearEnd by
        remember(query.searching) {
            derivedStateOf {
                if (query.searching) {
                    val info = list.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
                } else {
                    val info = grid.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
                }
            }
        }
    LaunchedEffect(nearEnd, state.items.size, state.hasMore) {
        if (nearEnd && state.items.isNotEmpty() && state.hasMore) catalog.loadNextPage()
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
            IconAction(Icons.Outlined.Refresh, "刷新在线作品", Muted) {
                catalog.refresh(clearItems = false)
            }
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
                    if (text.isNotEmpty()) {
                        IconAction(Icons.Outlined.Close, "清空检索") {
                            text = ""
                            submit(query.copy(text = ""))
                        }
                    }
                    IconAction(Icons.Outlined.Search, "提交在线搜索") {
                        submit(query.copy(text = text.trim()))
                    }
                }
            },
        )
        if (query.facets.isNotEmpty() || query.subtitlesOnly) {
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
                if (query.subtitlesOnly) {
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
            }
        }
        if (editing) {
            val suggestedHistory =
                history.filter { text.isBlank() || it.contains(text, ignoreCase = true) }.take(4)
            val suggestedFacets =
                if (text.isBlank()) emptyList()
                else facets.filter { it.name.contains(text, true) && it !in query.facets }.take(4)
            if (suggestedHistory.isNotEmpty() || suggestedFacets.isNotEmpty()) {
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
                    if (suggestedHistory.isNotEmpty()) {
                        TextButton(
                            onClick = { scope.launch { graph.settings.clearAsmrSearchHistory() } },
                            Modifier.align(Alignment.End),
                        ) {
                            Text("清除记录")
                        }
                    }
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
        if (state.refreshError != null && state.items.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("刷新失败", Modifier.weight(1f), color = Muted)
                TextButton(onClick = { catalog.refresh(clearItems = false) }) { Text("重试") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.searching) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("asmr-results"),
                    state = list,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.items, key = { it.workId }) { work ->
                        val image =
                            remember(work.coverId, work.coverUrl, sourceId) {
                                val id = work.coverId ?: return@remember null
                                val url = work.coverUrl ?: return@remember null
                                WhispImage.Catalog(id, url, sourceId)
                            }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                                open(work)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                image = image,
                                description = work.title,
                                modifier =
                                    Modifier.size(width = 100.dp, height = 112.dp)
                                        .clip(RoundedCornerShape(14.dp)),
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
                                if (work.artist.isNotBlank()) {
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
                    item {
                        CatalogAppend(state.appending, state.appendError) { catalog.retryAppend() }
                    }
                }
            } else {
                WorkGrid(
                    items = state.items,
                    key = { it.workId },
                    workOf = { it.entity(sourceId) },
                    imageOf = { work ->
                        val id = work.coverId
                        val url = work.coverUrl
                        if (id != null && url != null) WhispImage.Catalog(id, url, sourceId)
                        else null
                    },
                    onClick = open,
                    state = grid,
                    tag = "asmr-grid",
                    footer = {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CatalogAppend(state.appending, state.appendError) {
                                catalog.retryAppend()
                            }
                        }
                    },
                )
            }
            if (state.items.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        state.refreshing -> CircularProgressIndicator()
                        state.refreshError != null -> {
                            Text(state.refreshError!!, color = Muted)
                            TextButton(onClick = { catalog.refresh(clearItems = true) }) {
                                Text("重试")
                            }
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
    }
    if (filters) {
        AsmrFilters(query, facets, { filters = false }) { next ->
            submit(next.copy(text = text.trim()))
        }
    }
}

@Composable
private fun CatalogAppend(appending: Boolean, error: String?, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        when {
            appending -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            error != null -> TextButton(onClick = retry) { Text("重试") }
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
