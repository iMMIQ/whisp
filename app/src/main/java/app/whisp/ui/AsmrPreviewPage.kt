// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AsmrPreviewPage(
    model: AppViewModel,
    sourceId: String,
    id: Long,
    back: () -> Unit,
    player: () -> Unit,
    search: () -> Unit,
) {
    val state by model.asmrPreview.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val current = state.takeIf { it.sourceId == sourceId && it.seed?.id == id }
    val work = current?.preview?.work ?: current?.seed
    val preview = current?.preview
    var tab by rememberSaveable(id) { mutableStateOf("曲目") }
    val official =
        rememberDlsiteWork(
            model,
            work?.workId.orEmpty(),
            preview?.metadata?.rj,
            tab,
            preview?.metadata,
        )
    val filter: (AsmrFacet) -> Unit = { facet ->
        model.submitAsmrQuery(AsmrQuery(sourceId, facets = listOf(facet)))
        search()
    }
    var saving by remember(id) { mutableStateOf(false) }
    var saveError by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val added = library.any { it.work.id == work?.workId }
    fun save(trackId: String? = null, play: Boolean = false) {
        val ready = preview ?: return
        if (saving) return
        saving = true
        saveError = null
        scope.launch {
            try {
                val detail = model.saveAsmrPreview(ready)
                if (play) {
                    model.playWork(detail, trackId)
                    player()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = e.message ?: "作品保存失败"
            } finally {
                saving = false
            }
        }
    }
    LaunchedEffect(sourceId, id) { if (current == null) model.restoreAsmrPreview(sourceId, id) }
    val sections =
        remember(preview) { preview?.details?.let { TrackSections.groups(it.tracks) }.orEmpty() }
    LazyColumn(Modifier.fillMaxSize().testTag("asmr-preview")) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(.92f).background(Surface)) {
                Artwork(
                    model,
                    work?.coverId,
                    work?.title,
                    Modifier.matchParentSize(),
                    sharedKey = work?.workId,
                    catalogUrl = work?.coverUrl,
                    catalogSourceId = sourceId,
                )
                Box(
                    Modifier.matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.copy(alpha = .2f), Color.Transparent, Ink),
                            ),
                        ),
                )
                CompositionLocalProvider(LocalCoverControls provides true) {
                    Box(Modifier.padding(8.dp)) {
                        IconAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回检索", action = back)
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                    Text(
                        work?.title.orEmpty(),
                        fontFamily = FontFamily.Serif,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                    )
                    if (!work?.artist.isNullOrBlank())
                        Text(
                            work.artist,
                            Modifier.padding(top = 8.dp),
                            color = Muted,
                            fontSize = 15.sp,
                        )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { save() },
                    Modifier.weight(1f).height(52.dp),
                    enabled = preview != null && !saving && !added,
                    shape = RoundedCornerShape(17.dp),
                ) {
                    if (added) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Text(if (added) "已加入" else "加入作品库")
                }
                Button(
                    onClick = { save(play = true) },
                    Modifier.weight(1f).height(52.dp),
                    enabled = preview != null && !saving,
                    shape = RoundedCornerShape(17.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("播放作品")
                }
            }
            if ((current == null && state.error == null) || current?.loading == true || saving)
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
            (current?.error ?: state.error.takeIf { state.sourceId == sourceId } ?: saveError)
                ?.let { message ->
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(message, color = Muted)
                        TextButton(
                            onClick = {
                                if (preview == null)
                                    current?.seed?.let {
                                        model.prepareAsmrPreview(sourceId, it, retry = true)
                                    } ?: model.restoreAsmrPreview(sourceId, id)
                                else save()
                            },
                        ) {
                            Text("重试")
                        }
                    }
                }
            if (preview != null) {
                OfficialCircle(official.details ?: preview.metadata, filter)
                WorkContentTabs(tab) { tab = it }
            }
        }
        if (tab != "曲目")
            dlsiteContent(
                tab,
                official,
                model,
                work?.workId.orEmpty(),
                preview?.metadata?.rj,
                preview?.metadata,
                filter,
            )
        if (tab == "曲目")
            sections.forEach { section ->
                if (sections.size > 1)
                    item(key = "section:${section.key}") {
                        Text(
                            section.title,
                            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = Rose,
                            fontSize = 18.sp,
                        )
                    }
                items(section.tracks, key = { it.id }) { track ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !saving) { save(track.id, true) }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(track.title, Modifier.weight(1f), fontSize = 16.sp)
                        Icon(Icons.Filled.PlayArrow, "播放${track.title}", tint = Rose)
                    }
                }
            }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
