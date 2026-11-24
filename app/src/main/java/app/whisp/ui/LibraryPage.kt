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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
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
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.ProgressEntity
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity

@Composable
internal fun LibraryPage(
    graph: AppGraph,
    favoritesOnly: Boolean,
    open: (String) -> Unit,
    sources: () -> Unit,
) {
    val works by graph.library.works.collectAsStateWithLifecycle()
    val progressList by graph.library.progress.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("全部") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }
    val grid = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(searching) {
        if (searching) {
            runCatching { searchFocus.requestFocus() }
        }
    }

    LaunchedEffect(query, works) {
        matchedIds = if (query.isBlank()) null else graph.library.searchWorkIds(query)
    }

    val filtered =
        remember(works, favoritesOnly, tab, series, matchedIds, reversed) {
            works
                .filter { work ->
                    (!favoritesOnly && tab != "收藏" || work.favorite) &&
                        (series == null || work.series == series) &&
                        (matchedIds == null || work.id in matchedIds!!)
                }
                .let { if (reversed) it.reversed() else it }
        }
    val seriesGroups =
        remember(works) {
            works
                .filter { !it.series.isNullOrBlank() }
                .groupBy { it.series!! }
                .entries
                .sortedBy { it.key }
                .map { it.key to it.value }
        }
    val progressByWork = remember(progressList) { progressList.associateBy { it.workId } }
    val latestResume =
        remember(works, progressList, favoritesOnly, tab, series, query) {
            if (favoritesOnly || tab != "全部" || series != null || query.isNotBlank()) null
            else {
                val latest = progressList.firstOrNull() ?: return@remember null
                val work = works.firstOrNull { it.id == latest.workId } ?: return@remember null
                work to latest
            }
        }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (favoritesOnly) stringResource(R.string.title_favorites)
                    else series ?: stringResource(R.string.title_library),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Cream,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(
                Icons.Outlined.Search,
                stringResource(R.string.action_search_works),
                tint = if (searching || query.isNotBlank()) Rose else Cream,
            ) {
                searching = !searching
                if (!searching) query = ""
            }
            IconAction(
                Icons.Outlined.SwapVert,
                stringResource(R.string.action_toggle_order),
                tint = if (reversed) Rose else Cream,
            ) {
                reversed = !reversed
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
        if (!favoritesOnly) {
            WhispTabBar(
                options = listOf("全部" to "全部", "系列" to "系列", "收藏" to "收藏"),
                selected = tab,
                onSelect = { item ->
                    tab = item
                    series = null
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                trailing =
                    if (series != null) {
                        {
                            IconAction(
                                Icons.Outlined.Close,
                                stringResource(R.string.action_exit_series),
                                Muted,
                            ) {
                                series = null
                            }
                        }
                    } else null,
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }
        when {
            works.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    WhispEmptyState(
                        icon = Icons.Outlined.LibraryMusic,
                        title = "作品库还是空的",
                        subtitle = "添加本地目录、NAS 或在线来源即可开始收听",
                    ) {
                        FilledTonalButton(onClick = sources) {
                            Icon(Icons.Outlined.CreateNewFolder, null)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.action_add_works))
                        }
                    }
                }
            tab == "系列" && series == null && !favoritesOnly ->
                if (seriesGroups.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        WhispEmptyState(
                            icon = Icons.Outlined.FolderOpen,
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
            filtered.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        query.isNotBlank() ->
                            WhispEmptyState(
                                icon = Icons.Outlined.Search,
                                title = "没有找到匹配的作品",
                                subtitle = "尝试更换关键词、声优名或 RJ 编号",
                            )
                        favoritesOnly || tab == "收藏" ->
                            WhispEmptyState(
                                icon = Icons.Outlined.FavoriteBorder,
                                title = "暂无收藏作品",
                                subtitle = "在作品页或播放器点击爱心即可收藏",
                            )
                        else ->
                            WhispEmptyState(
                                icon = Icons.Outlined.Headphones,
                                title = "暂无作品",
                            )
                    }
                }
            else ->
                WorkGrid(
                    items = filtered,
                    key = { it.id },
                    workOf = { it },
                    imageOf = { it.coverId?.let { id -> WhispImage.Asset(id) } },
                    onClick = { open(it.id) },
                    state = grid,
                    tag = "library-grid",
                    progressOf = { work ->
                        val p = progressByWork[work.id]
                        if (p != null && p.positionMs > 0L) 1f else null
                    },
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
                                        onClick = { open(work.id) },
                                    )
                                }
                            }
                        },
                )
        }
    }
}

@Composable
private fun ContinueListeningBanner(
    work: WorkEntity,
    progress: ProgressEntity,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, Rose.copy(alpha = 0.24f), shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
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
            Modifier.size(38.dp).clip(CircleShape).background(Rose.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = Rose, modifier = Modifier.size(22.dp))
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
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
    }
}
