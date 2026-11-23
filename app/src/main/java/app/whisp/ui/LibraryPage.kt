// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.WorkDetails

@Composable
internal fun LibraryPage(
    library: List<WorkDetails>,
    favoritesOnly: Boolean,
    model: AppViewModel,
    open: (String) -> Unit,
    sources: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("全部") }
    var series by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val filtered =
        remember(library, favoritesOnly, tab, series, query, reversed) {
            library
                .filter {
                    (!favoritesOnly && tab != "收藏" || it.work.favorite) &&
                        (series == null || it.work.series == series) &&
                        (query.isBlank() ||
                            listOf(
                                    it.work.title,
                                    it.work.rj.orEmpty(),
                                    it.work.artist,
                                    it.work.originalTitle,
                                )
                                .any { value -> value.contains(query, true) } ||
                            it.tracks.any { t ->
                                t.fileName.contains(query, true) || t.title.contains(query, true)
                            } ||
                            it.attachments.any { a -> a.fileName.contains(query, true) })
                }
                .let { if (reversed) it.reversed() else it }
        }
    val seriesList = remember(library) { library.mapNotNull { it.work.series }.distinct().sorted() }
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
        if (searching)
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true,
                placeholder = { Text("作品、声优、RJ") },
                shape = RoundedCornerShape(16.dp),
            )
        if (!favoritesOnly)
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
                if (series != null)
                    IconAction(Icons.Outlined.Close, stringResource(R.string.action_exit_series)) {
                        series = null
                    }
            }
        else Spacer(Modifier.height(12.dp))
        when {
            library.isEmpty() ->
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
                LazyVerticalGrid(
                    GridCells.Adaptive(minSize = 160.dp),
                    Modifier.fillMaxSize().testTag("library-grid"),
                    state = grid,
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(
                        filtered,
                        key = { _, it -> it.work.id },
                        span = { index, _ -> GridItemSpan(if (index == 0) maxLineSpan else 1) },
                    ) { index, detail ->
                        WorkCard(detail.work, model, index == 0) { open(detail.work.id) }
                    }
                }
        }
    }
}
