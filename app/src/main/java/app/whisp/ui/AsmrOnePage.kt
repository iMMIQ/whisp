// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
            val currentJob = coroutineContext[Job]
            try {
                val page = withContext(Dispatchers.IO) { fetchPage(active, 1) }
                if (refreshJob !== currentJob) return@launch
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
                if (refreshJob !== currentJob) return@launch
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
            val currentJob = coroutineContext[Job]
            try {
                val page = withContext(Dispatchers.IO) { fetchPage(active, nextPage) }
                if (appendJob !== currentJob) return@launch
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
                if (appendJob !== currentJob) return@launch
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

private data class AsmrGridItem(
    val work: AsmrWork,
    val entity: WorkEntity,
    val image: WhispImage?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AsmrOnePage(
    graph: AppGraph,
    sourceId: String,
    initialFacet: AsmrFacet? = null,
    catalogScopeKey: String? = null,
    back: () -> Unit,
    open: (AsmrWork) -> Unit,
) {
    val catalog = remember(graph, catalogScopeKey) { graph.asmrCatalogFor(catalogScopeKey) }
    val active by catalog.query.collectAsStateWithLifecycle()
    val state by catalog.state.collectAsStateWithLifecycle()
    val query = active?.takeIf { it.sourceId == sourceId } ?: AsmrQuery(sourceId)
    var text by rememberSaveable(sourceId, catalogScopeKey) { mutableStateOf(query.text) }
    var editing by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var listViewOverride by
        rememberSaveable(sourceId, catalogScopeKey) { mutableStateOf<Boolean?>(null) }
    val useListView = listViewOverride ?: query.searching
    val history by graph.settings.asmrSearchHistory.collectAsStateWithLifecycle()
    val facets by catalog.facets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val haptics = rememberWhispHaptics()
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

    var displayedQuery by
        rememberSaveable(sourceId, catalogScopeKey) { mutableStateOf(encodeAsmrQuery(query)) }
    LaunchedEffect(query) {
        val encoded = encodeAsmrQuery(query)
        if (displayedQuery != encoded) {
            text = query.text
            displayedQuery = encoded
            grid.scrollToItem(0)
            list.scrollToItem(0)
        }
    }
    LaunchedEffect(sourceId, initialFacet, catalog) {
        if (initialFacet != null && catalog.query.value == null) {
            catalog.submit(AsmrQuery(sourceId, facets = listOf(initialFacet)))
        } else if (catalog.query.value?.sourceId != sourceId) {
            catalog.submit(AsmrQuery(sourceId))
        }
    }

    // Trigger next-page pagination when scrolling near the end of either list or grid.
    val nearEnd by
        remember(useListView) {
            derivedStateOf {
                if (useListView) {
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

    val gridItems =
        remember(state.items, sourceId) {
            state.items.map { work ->
                val entity = work.entity(sourceId)
                val coverId = entity.coverId
                val coverUrl = work.coverUrl
                val image =
                    if (coverId != null && coverUrl != null)
                        WhispImage.Catalog(coverId, coverUrl, sourceId)
                    else null
                AsmrGridItem(work, entity, image)
            }
        }
    val suggestedHistory =
        if (editing)
            history.filter { text.isBlank() || it.contains(text, ignoreCase = true) }.take(4)
        else emptyList()
    val suggestedFacets =
        if (editing && text.isNotBlank())
            facets.filter { it.name.contains(text, true) && it !in query.facets }.take(4)
        else emptyList()
    val showSuggestions = editing && (suggestedHistory.isNotEmpty() || suggestedFacets.isNotEmpty())

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(WhispIcons.AutoMirrored.Outlined.ArrowBack, "返回来源", action = back)
            Text(
                initialFacet?.name ?: "ASMR.ONE",
                Modifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
                color = Cream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconAction(
                if (useListView) WhispIcons.Outlined.GridView
                else WhispIcons.AutoMirrored.Outlined.ViewList,
                if (useListView) "切换为网格视图" else "切换为列表视图",
                Muted,
                haptic = HapticKind.Tick,
            ) {
                listViewOverride = !useListView
            }
            IconAction(
                WhispIcons.Outlined.Tune,
                "筛选作品",
                if (query.facets.isNotEmpty() || query.subtitlesOnly) Rose else Muted,
                haptic = HapticKind.Tick,
            ) {
                focus.clearFocus()
                filters = true
            }
            IconAction(
                WhispIcons.Outlined.Refresh,
                "刷新在线作品",
                Muted,
                haptic = HapticKind.Tick,
            ) {
                catalog.refresh(clearItems = false)
            }
        }
        WhispSearchBar(
            value = text,
            onValueChange = { text = it.take(300) },
            placeholder = "作品、声优、RJ",
            modifier =
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("asmr-search")
                    .onFocusChanged { editing = it.isFocused },
            clearLabel = "清空检索",
            submitLabel = "提交在线搜索",
            onClear = {
                text = ""
                submit(query.copy(text = ""))
            },
            onSubmit = { submit(query.copy(text = text.trim())) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = query.facets.isNotEmpty() || query.subtitlesOnly,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(query.facets, key = { "${it.kind}|${it.name}" }) { facet ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    haptics.tick()
                                    submit(
                                        query.copy(
                                            text = text.trim(),
                                            facets = query.facets - facet,
                                        ),
                                    )
                                },
                                label = { Text(facet.name) },
                                trailingIcon = {
                                    Icon(
                                        WhispIcons.Outlined.Close,
                                        "移除${facet.name}",
                                        Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                        if (query.subtitlesOnly) {
                            item {
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        haptics.tick()
                                        submit(
                                            query.copy(text = text.trim(), subtitlesOnly = false),
                                        )
                                    },
                                    label = { Text("有字幕") },
                                    trailingIcon = {
                                        Icon(
                                            WhispIcons.Outlined.Close,
                                            "移除字幕筛选",
                                            Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                WhispTabBar(
                    options = listOf("create_date" to "最新", "dl_count" to "热门"),
                    selected = query.order,
                    onSelect = { order ->
                        haptics.tick()
                        submit(query.copy(text = text.trim(), order = order))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (state.refreshError != null && state.items.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            )
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "刷新失败",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                        )
                        TextButton(onClick = { catalog.refresh(clearItems = false) }) { Text("重试") }
                    }
                }
                PullToRefreshBox(
                    isRefreshing = state.refreshing && state.items.isNotEmpty(),
                    onRefresh = { catalog.refresh(clearItems = false) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (useListView) {
                        LazyColumn(
                            Modifier.fillMaxSize().testTag("asmr-results"),
                            state = list,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(gridItems, key = { it.entity.id }) { item ->
                                val work = item.work
                                val image = item.image
                                val rowShape = RoundedCornerShape(16.dp)
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(rowShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            rowShape,
                                        )
                                        .clickable { open(work) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Artwork(
                                        image = image,
                                        description = work.title,
                                        modifier =
                                            Modifier.size(width = 116.dp, height = 87.dp)
                                                .clip(RoundedCornerShape(11.dp)),
                                    )
                                    Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
                                        Text(
                                            work.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = Cream,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (work.artist.isNotBlank()) {
                                            Text(
                                                work.artist,
                                                Modifier.padding(top = 6.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (work.rj.isNotBlank()) {
                                            Text(
                                                work.rj,
                                                style =
                                                    MaterialTheme.typography.labelSmall.merge(
                                                        TabularNumeralStyle,
                                                    ),
                                                color = Muted.copy(alpha = 0.75f),
                                                modifier = Modifier.padding(top = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                CatalogAppend(state.appending, state.appendError) {
                                    catalog.retryAppend()
                                }
                            }
                        }
                    } else {
                        WorkGrid(
                            items = gridItems,
                            key = { it.entity.id },
                            workOf = { it.entity },
                            imageOf = { it.image },
                            onClick = { open(it.work) },
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
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                state.refreshing -> CircularProgressIndicator(color = Rose)
                                state.refreshError != null ->
                                    WhispEmptyState(
                                        icon = WhispIcons.Outlined.CloudOff,
                                        title = "无法加载目录",
                                        subtitle = state.refreshError,
                                    ) {
                                        FilledTonalButton(
                                            onClick = { catalog.refresh(clearItems = true) },
                                        ) {
                                            Text("重试")
                                        }
                                    }
                                else ->
                                    WhispEmptyState(
                                        icon =
                                            if (query.searching) WhispIcons.Outlined.Search
                                            else WhispIcons.Outlined.Headphones,
                                        title = if (query.searching) "没有匹配的作品" else "暂无作品",
                                    )
                            }
                        }
                    }
                }
            }
            // Float suggestions as an overlay so focusing the search bar never causes layout shift.
            androidx.compose.animation.AnimatedVisibility(
                visible = showSuggestions,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(ScrimDark.copy(alpha = 0.42f)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        focus.clearFocus()
                        editing = false
                    },
                ) {
                    val cardShape = RoundedCornerShape(16.dp)
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(cardShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {}
                            .padding(vertical = 4.dp),
                    ) {
                        suggestedHistory.forEach { value ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { submit(query.copy(text = value)) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    WhispIcons.Outlined.History,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = Muted,
                                )
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Cream,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        suggestedFacets.forEach { facet ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        submit(
                                            query.copy(
                                                text = "",
                                                facets = (query.facets + facet).distinct(),
                                            ),
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    WhispIcons.Outlined.FilterList,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = Rose,
                                )
                                Text(
                                    "${facet.label} · ${facet.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Rose,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (suggestedHistory.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    scope.launch { graph.settings.clearAsmrSearchHistory() }
                                },
                                Modifier.align(Alignment.End).padding(end = 8.dp),
                            ) {
                                Text("清除记录", fontSize = 13.sp)
                            }
                        }
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
            appending ->
                CircularProgressIndicator(Modifier.size(24.dp), color = Rose, strokeWidth = 2.dp)
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
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("筛选", style = MaterialTheme.typography.headlineSmall, color = Cream)
            WhispTabBar(
                options = listOf("va" to "声优", "circle" to "社团", "tagw" to "标签"),
                selected = kind,
                onSelect = { value ->
                    kind = value
                    name = ""
                },
            )
            OutlinedTextField(
                name,
                { name = it.take(160) },
                Modifier.fillMaxWidth().testTag("asmr-facet-input"),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        when (kind) {
                            "va" -> "输入声优名"
                            "circle" -> "输入社团名"
                            else -> "输入标签"
                        },
                    )
                },
                trailingIcon = {
                    IconAction(
                        WhispIcons.Outlined.Add,
                        "添加筛选",
                        Rose,
                        enabled = name.isNotBlank() && '$' !in name,
                        haptic = HapticKind.Tick,
                    ) {
                        selected = (selected + AsmrFacet(kind, name.trim())).distinct()
                        name = ""
                    }
                },
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 200.dp)) {
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
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selected = (selected + facet).distinct()
                                name = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            facet.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            WhispIcons.Outlined.Add,
                            null,
                            tint = Muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (selected.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selected, key = { "${it.kind}|${it.name}" }) { facet ->
                        InputChip(
                            selected = true,
                            onClick = { selected = selected - facet },
                            label = { Text(facet.name) },
                            trailingIcon = {
                                Icon(WhispIcons.Outlined.Close, null, Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
            WhispToggleRow(
                label = "有字幕",
                checked = subtitles,
                onCheckedChange = { subtitles = it },
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        selected = emptyList()
                        subtitles = false
                    },
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("应用", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
