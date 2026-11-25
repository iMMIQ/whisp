// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.LibrarySort
import app.whisp.data.ProgressEntity
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val librarySortOptions =
    listOf(
        LibrarySort.ADDED_DESC to "最近添加",
        LibrarySort.ADDED_ASC to "最早添加",
        LibrarySort.RECENT to "最近收听",
        LibrarySort.TITLE_ASC to "作品名 A–Z",
        LibrarySort.TITLE_DESC to "作品名 Z–A",
        LibrarySort.OFFLINE_FIRST to "离线优先",
    )

@Composable
internal fun LibraryPage(
    graph: AppGraph,
    activeMiniWorkId: String? = null,
    reselectTick: Int = 0,
    open: (String) -> Unit,
    resumePlay: (String) -> Unit = open,
    sources: () -> Unit,
) {
    val works by graph.library.works.collectAsStateWithLifecycle()
    val progressList by graph.library.progress.collectAsStateWithLifecycle()
    val progressSummaries by graph.library.workProgressSummaries.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("全部") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortSheet by rememberSaveable { mutableStateOf(false) }
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }
    val grid = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val haptics = rememberWhispHaptics()

    // Intercept system back for in-page series drilldown and search bar expansion.
    BackHandler(enabled = series != null) { series = null }
    BackHandler(enabled = searching && series == null) {
        searching = false
        query = ""
    }

    LaunchedEffect(reselectTick) {
        if (reselectTick > 0) {
            series = null
            grid.animateScrollToItem(0)
        }
    }

    LaunchedEffect(searching) {
        if (searching) {
            runCatching { searchFocus.requestFocus() }
        }
    }

    LaunchedEffect(query, works) {
        if (query.isBlank()) {
            matchedIds = null
        } else {
            delay(250L)
            matchedIds = graph.library.searchWorkIds(query)
        }
    }

    val pagedWorksFlow =
        remember(tab, series, matchedIds, settings.librarySort) {
            graph.library.pagedWorks(
                favoriteOnly = tab == "收藏",
                offlineOnly = tab == "离线",
                series = series,
                matchedIds = matchedIds,
                sort = settings.librarySort,
            )
        }
    val pagedWorks = pagedWorksFlow.collectAsLazyPagingItems()
    val seriesGroups =
        remember(works, query, matchedIds) {
            val trimmed = query.trim().lowercase()
            val matched = matchedIds
            works
                .filter { !it.series.isNullOrBlank() }
                .groupBy { it.series!! }
                .entries
                .filter { (seriesName, members) ->
                    trimmed.isEmpty() ||
                        matched == null ||
                        seriesName.lowercase().contains(trimmed) ||
                        members.any { it.id in matched }
                }
                .sortedBy { it.key }
                .map { it.key to it.value }
        }
    // Avoid duplicating the exact same item already pinned in MiniPlayer when only 1 recent item
    // exists.
    val latestResume =
        remember(works, progressList, tab, series, query, activeMiniWorkId) {
            if (tab != "全部" || series != null || query.isNotBlank()) null
            else {
                val candidate =
                    progressList.firstOrNull { it.workId != activeMiniWorkId }
                        ?: progressList.firstOrNull()?.takeIf { activeMiniWorkId == null }
                        ?: return@remember null
                val work = works.firstOrNull { it.id == candidate.workId } ?: return@remember null
                work to candidate
            }
        }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    series ?: stringResource(R.string.title_library),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Cream,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(
                WhispIcons.Outlined.Search,
                stringResource(R.string.action_search_works),
                tint = if (searching || query.isNotBlank()) Rose else Cream,
                haptic = HapticKind.Tick,
            ) {
                searching = !searching
                if (!searching) query = ""
            }
            IconAction(
                WhispIcons.Outlined.SwapVert,
                stringResource(R.string.action_toggle_order),
                tint = if (settings.librarySort != LibrarySort.ADDED_DESC) Rose else Cream,
                haptic = HapticKind.Tick,
            ) {
                sortSheet = true
            }
        }
        AnimatedVisibility(
            visible = searching,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            WhispSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "作品、声优、RJ",
                modifier =
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        .focusRequester(searchFocus),
            )
        }
        WhispTabBar(
            options = listOf("全部" to "全部", "收藏" to "收藏", "系列" to "系列", "离线" to "离线"),
            selected = tab,
            onSelect = { item ->
                haptics.tick()
                tab = item
                series = null
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            trailing =
                if (series != null) {
                    {
                        IconAction(
                            WhispIcons.Outlined.Close,
                            stringResource(R.string.action_exit_series),
                            Muted,
                            haptic = HapticKind.Tick,
                        ) {
                            series = null
                        }
                    }
                } else null,
        )
        when {
            works.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WhispEmptyState(
                        icon = WhispIcons.Outlined.LibraryMusic,
                        title = "作品库还是空的",
                        subtitle = "添加本地目录、NAS 或在线来源即可开始收听",
                    ) {
                        FilledTonalButton(onClick = sources) {
                            Icon(WhispIcons.Outlined.CreateNewFolder, null)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.action_add_works))
                        }
                    }
                }
            tab == "系列" && series == null ->
                if (seriesGroups.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        WhispEmptyState(
                            icon = WhispIcons.Outlined.FolderOpen,
                            title = "暂无系列分类",
                            subtitle = "带有系列信息的作品会自动归类到这里",
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(seriesGroups, key = { it.first }) { (name, members) ->
                            SeriesCard(name = name, works = members, onClick = { series = name })
                        }
                    }
                }
            pagedWorks.itemCount == 0 && pagedWorks.loadState.refresh is LoadState.NotLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        query.isNotBlank() ->
                            WhispEmptyState(
                                icon = WhispIcons.Outlined.Search,
                                title = "没有找到匹配的作品",
                                subtitle = "尝试更换关键词、声优名或 RJ 编号",
                            )
                        tab == "收藏" ->
                            WhispEmptyState(
                                icon = WhispIcons.Outlined.FavoriteBorder,
                                title = "暂无收藏作品",
                                subtitle = "在作品页或播放器点击爱心即可收藏",
                            )
                        tab == "离线" ->
                            WhispEmptyState(
                                icon = WhispIcons.Outlined.DownloadDone,
                                title = "暂无已离线作品",
                                subtitle = "在作品详情页点击下载按钮即可离线保存",
                            )
                        else ->
                            WhispEmptyState(
                                icon = WhispIcons.Outlined.Headphones,
                                title = "暂无作品",
                            )
                    }
                }
            else ->
                PagedWorkGrid(
                    items = pagedWorks,
                    imageOf = { it.coverId?.let { id -> WhispImage.Asset(id) } },
                    onClick = { open(it.id) },
                    state = grid,
                    tag = "library-grid",
                    progressOf = { work -> progressSummaries[work.id]?.fraction() },
                    offlineOf = { it.offlineAttachments },
                    header =
                        latestResume?.let { (work, progress) ->
                            {
                                item(
                                    key = "continue-listening",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    ContinueListeningBanner(
                                        work = work,
                                        progress = progress,
                                        onOpenDetail = { open(work.id) },
                                        onResumePlay = { resumePlay(work.id) },
                                    )
                                }
                            }
                        },
                )
        }
    }
    if (sortSheet) {
        WhispSheet(onDismissRequest = { sortSheet = false }) { dismiss ->
            Text(
                "排序方式",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                librarySortOptions.forEach { (key, label) ->
                    val selected = settings.librarySort == key
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                haptics.tick()
                                scope.launch {
                                    graph.settings.update { it.copy(librarySort = key) }
                                }
                                dismiss()
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) Rose else Cream,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                WhispIcons.Outlined.Check,
                                null,
                                tint = Rose,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ContinueListeningBanner(
    work: WorkEntity,
    progress: ProgressEntity,
    onOpenDetail: () -> Unit,
    onResumePlay: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val haptics = rememberWhispHaptics()
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, Rose.copy(alpha = 0.24f), shape)
            .clickable(onClick = onOpenDetail)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            image = work.coverId?.let { WhispImage.Asset(it) },
            // Keep null so onNodeWithContentDescription(work.title) uniquely targets the grid card.
            description = null,
            modifier = Modifier.size(width = 72.dp, height = 56.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "继续收听",
                    style = MaterialTheme.typography.labelSmall,
                    color = Rose,
                    fontWeight = FontWeight.SemiBold,
                )
                if (progress.positionMs > 0L) {
                    Text(
                        " · ${clock(progress.positionMs)}",
                        style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
                        color = Muted,
                    )
                }
            }
            Text(
                shortTitle(work.title).ifBlank { work.title },
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (work.artist.isNotBlank()) {
                Text(
                    work.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Rose.copy(alpha = 0.18f)).clickable {
                haptics.confirm()
                onResumePlay()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WhispIcons.Filled.PlayArrow,
                "立即继续收听",
                tint = Rose,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SeriesCard(name: String, works: List<WorkEntity>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val covers = remember(works) { works.mapNotNull { it.coverId }.distinct().take(2) }
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 52.dp)) {
            if (covers.size >= 2) {
                Artwork(
                    image = WhispImage.Asset(covers[1]),
                    description = null,
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .size(width = 48.dp, height = 40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(9.dp),
                            ),
                )
            }
            Artwork(
                image = covers.firstOrNull()?.let { WhispImage.Asset(it) },
                description = null,
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .size(width = 52.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${works.size} 部作品",
                style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Icon(WhispIcons.Outlined.ChevronRight, null, tint = Muted)
    }
}
