// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.data.*
import java.util.Locale

@Composable
internal fun rememberDlsiteWork(
    model: AppViewModel,
    workId: String,
    rj: String?,
    tab: String,
    seed: DlsiteDetails? = null,
): DlsiteUiState {
    val states by model.dlsiteStates.collectAsStateWithLifecycle()
    val stored by
        remember(workId) { model.repository.dlsiteDetails(workId) }
            .collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(workId, stored) { stored?.let { model.acceptDlsiteDetails(workId, it) } }
    LaunchedEffect(workId, rj, seed) {
        if (rj != null) model.loadDlsiteDetails(workId, rj, seed = seed)
    }
    LaunchedEffect(workId, rj, tab) {
        if (rj != null && tab == "介绍") model.loadDlsiteDetails(workId, rj, "description", seed)
        if (rj != null && tab == "资料") model.loadDlsiteDetails(workId, rj, "statistics", seed)
    }
    return states[workId] ?: DlsiteUiState(seed)
}

@Composable
internal fun OfficialCircle(details: DlsiteDetails?, filter: ((AsmrFacet) -> Unit)? = null) {
    details?.circle?.let { circle ->
        Text(
            circle,
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                .then(
                    if (filter != null && circle.length <= 160 && '$' !in circle)
                        Modifier.clickable { filter(AsmrFacet("circle", circle)) }
                    else Modifier,
                ),
            color = Muted,
            fontSize = 16.sp,
        )
    }
}

@Composable
internal fun WorkContentTabs(tab: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf("曲目", "介绍", "资料").forEach { value ->
            TextButton(
                onClick = { select(value) },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = if (tab == value) Rose else Muted,
                    ),
            ) {
                Text(value, fontSize = 16.sp)
            }
        }
    }
}

internal fun LazyListScope.dlsiteContent(
    tab: String,
    state: DlsiteUiState,
    model: AppViewModel,
    workId: String,
    rj: String?,
    seed: DlsiteDetails? = null,
    filter: ((AsmrFacet) -> Unit)? = null,
) {
    val details = state.details ?: seed
    val part = if (tab == "介绍") "description" else "statistics"
    item(key = "dlsite-status:$tab") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            if (rj != null)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    IconAction(
                        Icons.Outlined.Refresh,
                        if (tab == "介绍") "刷新作品介绍" else "刷新作品资料",
                        Muted,
                    ) {
                        model.loadDlsiteDetails(workId, rj, "product", seed, force = true)
                        model.loadDlsiteDetails(workId, rj, part, seed, force = true)
                    }
                }
            for (section in listOf("product", part)) {
                if (section in state.loading)
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                state.errors[section]?.let { error ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = Muted, fontSize = 15.sp)
                        TextButton(
                            onClick = {
                                rj?.let {
                                    model.loadDlsiteDetails(workId, it, section, seed, force = true)
                                }
                            },
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
    if (tab == "介绍") {
        if (details?.description?.isNotEmpty() == true)
            items(details.description.size, key = { "description:$it" }) { index ->
                val block = details.description[index]
                when (block.kind) {
                    "image" -> block.url?.let { OfficialImage(model, it, block.text) }
                    "heading" ->
                        Text(
                            block.text,
                            Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                            fontFamily = FontFamily.Serif,
                            fontSize = 23.sp,
                            lineHeight = 31.sp,
                        )
                    else ->
                        Text(
                            block.text,
                            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            fontSize = 16.sp,
                            lineHeight = 27.sp,
                        )
                }
            }
        else {
            details?.summary?.takeIf(String::isNotBlank)?.let { summary ->
                item {
                    Text(summary, Modifier.padding(24.dp), fontSize = 16.sp, lineHeight = 27.sp)
                }
            }
            details?.images.orEmpty().forEachIndexed { index, url ->
                item(key = "sample:$index") { OfficialImage(model, url, "作品预览图") }
            }
            if (
                details?.descriptionFetchedAt?.let { it > 0 } == true &&
                    details.description.isEmpty()
            )
                item { Text("暂无介绍", Modifier.padding(24.dp), color = Muted) }
        }
    } else if (details != null) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("dlsite-information"),
            ) {
                details.circle?.let {
                    MetadataField(
                        if (details.rj.startsWith("VJ")) "品牌" else "社团",
                        it,
                        filter?.let { apply ->
                            { if (it.length <= 160 && '$' !in it) apply(AsmrFacet("circle", it)) }
                        },
                    )
                }
                details.credits
                    .groupBy { it.role }
                    .forEach { (_, credits) ->
                        credits.forEachIndexed { index, credit ->
                            MetadataField(
                                if (index == 0) credit.label else "",
                                credit.name,
                                if (
                                    credit.role == "voice_by" &&
                                        filter != null &&
                                        credit.name.length <= 160 &&
                                        '$' !in credit.name
                                ) {
                                    { filter(AsmrFacet("va", credit.name)) }
                                } else null,
                            )
                        }
                    }
                details.released?.let { MetadataField("发售", it) }
                details.ageRating?.let { MetadataField("分级", it) }
                details.workType?.let { MetadataField("形式", it) }
                details.series?.let { MetadataField("系列", it) }
                if (details.formats.isNotEmpty())
                    MetadataField("格式", details.formats.joinToString(" / "))
                details.packageBytes?.let {
                    MetadataField(
                        "作品容量",
                        android.text.format.Formatter.formatShortFileSize(LocalContext.current, it),
                    )
                }
                details.translationLanguage?.let { MetadataField("译本语言", dlsiteLanguage(it)) }
                MetadataField("作品编号", details.rj)
                details.statistics?.let { statistics ->
                    statistics.rating?.let {
                        MetadataField("评分", String.format(Locale.ROOT, "%.2f", it))
                    }
                    statistics.ratings?.let { MetadataField("评分人数", it.toString()) }
                    statistics.reviews?.let { MetadataField("评论", it.toString()) }
                    statistics.downloads?.let { MetadataField("销量", it.toString()) }
                    statistics.priceYen?.let { MetadataField("售价", "¥$it") }
                }
                val uri = LocalUriHandler.current
                TextButton(
                    onClick = { uri.openUri(officialWorkUrl(details.rj)) },
                    Modifier.align(Alignment.End),
                ) {
                    Text("DLsite")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                }
            }
        }
        if (details.genres.isNotEmpty())
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(details.genres, key = { "${it.id}|${it.name}" }) { genre ->
                        SuggestionChip(
                            onClick = {
                                if (
                                    filter != null && genre.name.length <= 160 && '$' !in genre.name
                                )
                                    filter(AsmrFacet("tagw", genre.name))
                            },
                            enabled = true,
                            label = { Text(genre.name) },
                        )
                    }
                }
            }
        details.originalRj?.let { id -> item { RelatedLink("原作", DlsiteRelatedWork(id)) } }
        details.translationParentRj
            ?.takeUnless { it == details.originalRj }
            ?.let { id -> item { RelatedLink("翻译主版本", DlsiteRelatedWork(id)) } }
        if (details.languageEditions.isNotEmpty())
            item { RelatedWorks("语言版本", details.languageEditions) }
        if (details.relatedEditions.isNotEmpty())
            item { RelatedWorks("相关版本", details.relatedEditions) }
        if (details.seriesWorks.isNotEmpty()) item { RelatedWorks("同系列", details.seriesWorks) }
    }
}

private fun officialWorkUrl(rj: String) = app.whisp.data.dlsiteWorkUrl(rj)

@Composable
private fun MetadataField(label: String, value: String, click: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(86.dp), color = Muted, fontSize = 15.sp)
        Text(
            value,
            Modifier.weight(1f)
                .then(if (click != null) Modifier.clickable(onClick = click) else Modifier),
            fontSize = 16.sp,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun OfficialImage(model: AppViewModel, url: String, label: String) {
    var enlarged by remember { mutableStateOf(false) }
    Artwork(
        model,
        stableId("dlsite-image|$url"),
        label.ifBlank { "作品介绍图" },
        Modifier.fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .heightIn(min = 180.dp)
            .aspectRatio(1.4f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { enlarged = true },
        scale = androidx.compose.ui.layout.ContentScale.Fit,
        officialUrl = url,
    )
    if (enlarged)
        WhispSheet({ enlarged = false }) { _ ->
            Artwork(
                model,
                stableId("dlsite-image|$url"),
                label.ifBlank { "作品介绍图" },
                Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 620.dp),
                scale = androidx.compose.ui.layout.ContentScale.Fit,
                officialUrl = url,
            )
        }
}

@Composable
private fun RelatedWorks(label: String, works: List<DlsiteRelatedWork>) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            fontFamily = FontFamily.Serif,
            fontSize = 22.sp,
        )
        works.take(if (expanded) 100 else 4).forEach { RelatedLink(null, it) }
        if (works.size > 4)
            TextButton(
                onClick = { expanded = !expanded },
                Modifier.align(Alignment.End).padding(end = 16.dp),
            ) {
                Text(if (expanded) "收起" else "查看全部")
            }
    }
}

@Composable
private fun RelatedLink(label: String?, work: DlsiteRelatedWork) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth()
            .clickable { uri.openUri(officialWorkUrl(work.rj)) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, color = Muted, fontSize = 15.sp)
            Text(work.title.ifBlank { work.rj }, fontSize = 16.sp, lineHeight = 24.sp)
            if (work.languages.isNotEmpty())
                Text(
                    work.languages.joinToString(" / ", transform = ::dlsiteLanguage),
                    color = Muted,
                    fontSize = 14.sp,
                )
        }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            "查看 DLsite 作品",
            Modifier.size(18.dp),
            tint = Muted,
        )
    }
}
