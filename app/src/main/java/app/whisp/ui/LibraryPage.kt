// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.WhispImage

@Composable
internal fun LibraryPage(
    graph: AppGraph,
    favoritesOnly: Boolean,
    open: (String) -> Unit,
    sources: () -> Unit,
) {
    val works by graph.library.works.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("全部") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var matchedIds by remember { mutableStateOf<Set<String>?>(null) }
    val grid = rememberLazyGridState()

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
    val seriesList = remember(works) { works.mapNotNull { it.series }.distinct().sorted() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (favoritesOnly) stringResource(R.string.title_favorites)
                else series ?: stringResource(R.string.title_library),
                fontFamily = FontFamily.Serif,
                fontSize = 30.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            IconAction(Icons.Outlined.Search, stringResource(R.string.action_search_works)) {
                searching = !searching
                if (!searching) query = ""
            }
            IconAction(Icons.Outlined.SwapVert, stringResource(R.string.action_toggle_order)) {
                reversed = !reversed
            }
        }
        if (searching) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true,
                placeholder = { Text("作品、声优、RJ") },
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (!favoritesOnly) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                listOf("全部", "系列", "收藏").forEach { item ->
                    TextButton(
                        onClick = {
                            tab = item
                            series = null
                        },
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = if (tab == item) Rose else Muted,
                            ),
                    ) {
                        Text(item, fontSize = 15.sp)
                    }
                }
                if (series != null) {
                    IconAction(Icons.Outlined.Close, stringResource(R.string.action_exit_series)) {
                        series = null
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
        when {
            works.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = sources) {
                        Icon(Icons.Outlined.CreateNewFolder, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.action_add_works))
                    }
                }
            tab == "系列" && series == null && !favoritesOnly ->
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    items(seriesList, key = { it }) { name ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { series = name }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null, tint = Rose)
                            Text(
                                name,
                                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                                fontSize = 18.sp,
                            )
                            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                        }
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
                )
        }
    }
}
