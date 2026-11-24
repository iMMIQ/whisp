// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.data.*
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun OfficialCircle(details: DlsiteDetails?, filter: ((AsmrFacet) -> Unit)? = null) {
    details?.circle?.let { circle ->
        val clickable = filter != null && circle.length <= 160 && '$' !in circle
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (clickable) Modifier.clickable { filter(AsmrFacet("circle", circle)) }
                    else Modifier,
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                circle,
                style = MaterialTheme.typography.titleSmall,
                color = if (clickable) Rose else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (clickable) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = Rose.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun WorkContentTabs(
    tab: String,
    select: (String) -> Unit,
    onRefresh: (() -> Unit)? = null,
) {
    WhispTabBar(
        options = listOf("曲目" to "曲目", "介绍" to "介绍", "资料" to "资料"),
        selected = tab,
        onSelect = select,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        trailing =
            onRefresh?.let { refresh ->
                {
                    IconAction(
                        Icons.Outlined.Refresh,
                        if (tab == "介绍") "刷新作品介绍" else "刷新作品资料",
                        Muted,
                        action = refresh,
                    )
                }
            },
    )
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.dlsiteContent(
    tab: String,
    state: DlsiteUiState,
    rj: String?,
    onLoad: (part: String, force: Boolean) -> Unit,
    seed: DlsiteDetails? = null,
    filter: ((AsmrFacet) -> Unit)? = null,
) {
    val details = state.details ?: seed
    val part = if (tab == "介绍") "description" else "statistics"
    val hasStatus =
        listOf("product", part).any { section ->
            section in state.loading || state.errors[section] != null
        }
    if (hasStatus) {
        item(key = "dlsite-status:$tab") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                for (section in listOf("product", part)) {
                    if (section in state.loading) {
                        Box(
                            Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                color = Rose,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    state.errors[section]?.let { error ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                error,
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 14.sp,
                            )
                            TextButton(onClick = { if (rj != null) onLoad(section, true) }) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
    if (tab == "介绍") {
        if (details?.description?.isNotEmpty() == true) {
            items(details.description.size, key = { "description:$it" }) { index ->
                val block = details.description[index]
                when (block.kind) {
                    "image" -> block.url?.let { OfficialImage(it, block.text) }
                    "heading" ->
                        Text(
                            block.text,
                            Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 18.dp,
                                bottom = 8.dp,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            color = Cream,
                        )
                    else ->
                        Text(
                            block.text,
                            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Cream.copy(alpha = 0.92f),
                            lineHeight = 27.sp,
                        )
                }
            }
        } else {
            details?.summary?.takeIf(String::isNotBlank)?.let { summary ->
                item {
                    Text(
                        summary,
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Cream.copy(alpha = 0.92f),
                        lineHeight = 27.sp,
                    )
                }
            }
            details?.images.orEmpty().forEachIndexed { index, url ->
                item(key = "sample:$index") { OfficialImage(url, "作品预览图") }
            }
            if (
                details?.descriptionFetchedAt?.let { it > 0 } == true &&
                    details.description.isEmpty()
            ) {
                item {
                    Text(
                        "暂无介绍",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
        }
    } else if (details != null) {
        details.statistics?.let { stats ->
            val hasAnyStat =
                stats.rating != null ||
                    stats.downloads != null ||
                    stats.reviews != null ||
                    stats.priceYen != null
            if (hasAnyStat) {
                item(key = "dlsite-stats-card") {
                    StatsSummaryCard(stats)
                }
            }
        }
        item(key = "dlsite-metadata-card") {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("dlsite-information"),
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
                val uri = LocalUriHandler.current
                TextButton(
                    onClick = { uri.openUri(dlsiteWorkUrl(details.rj)) },
                    Modifier.align(Alignment.End),
                ) {
                    Text("DLsite")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
                }
            }
        }
        if (details.genres.isNotEmpty()) {
            item(key = "dlsite-genres") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    details.genres.forEach { genre ->
                        SuggestionChip(
                            onClick = {
                                if (
                                    filter != null && genre.name.length <= 160 && '$' !in genre.name
                                )
                                    filter(AsmrFacet("tagw", genre.name))
                            },
                            enabled = true,
                            shape = RoundedCornerShape(10.dp),
                            label = {
                                Text(genre.name, style = MaterialTheme.typography.labelMedium)
                            },
                        )
                    }
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

@Composable
private fun StatsSummaryCard(stats: DlsiteStatistics) {
    val nf = remember { NumberFormat.getIntegerInstance(Locale.US) }
    val shape = RoundedCornerShape(16.dp)
    val cells = buildList {
        stats.rating?.let {
            val sub = stats.ratings?.let { count -> "${nf.format(count)} 评" }
            add(Triple("评分", String.format(Locale.ROOT, "%.2f", it), sub))
        }
        stats.downloads?.let { add(Triple("销量", nf.format(it), null)) }
        stats.reviews?.let { add(Triple("评论", nf.format(it), null)) }
        stats.priceYen?.let { add(Triple("售价", "¥${nf.format(it)}", null)) }
    }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { (label, value, sub) ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumeralStyle),
                    color = if (label == "评分") Rose else Cream,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (sub != null) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall.merge(TabularNumeralStyle),
                        color = Muted,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataField(label: String, value: String, click: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(84.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        if (click != null) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Rose.copy(alpha = 0.12f))
                    .border(0.5.dp, Rose.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .clickable(onClick = click)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Rose,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = Rose.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Text(
                value,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNumeralStyle),
                color = Cream,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun OfficialImage(url: String, label: String) {
    var enlarged by remember { mutableStateOf(false) }
    var ratio by remember(url) { mutableFloatStateOf(1.45f) }
    val image = remember(url) { WhispImage.Official(stableId("dlsite-image|$url"), url) }
    val shape = RoundedCornerShape(14.dp)
    Artwork(
        image = image,
        description = label.ifBlank { "作品介绍图" },
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .aspectRatio(ratio.coerceIn(0.68f, 2.4f))
                .heightIn(max = 420.dp)
                .clip(shape)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable { enlarged = true },
        scale = ContentScale.Fit,
        onAspectRatio = { ratio = it },
    )
    if (enlarged) {
        WhispImageLightbox(
            image = image,
            title = label.ifBlank { "作品介绍图" },
            onDismiss = { enlarged = false },
        )
    }
}

@Composable
private fun RelatedWorks(label: String, works: List<DlsiteRelatedWork>) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Cream,
        )
        works.take(if (expanded) 100 else 4).forEach { RelatedLink(null, it) }
        if (works.size > 4) {
            TextButton(
                onClick = { expanded = !expanded },
                Modifier.align(Alignment.End).padding(end = 16.dp),
            ) {
                Text(if (expanded) "收起" else "查看全部 (${works.size})")
            }
        }
    }
}

@Composable
private fun RelatedLink(label: String?, work: DlsiteRelatedWork) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth()
            .clickable { uri.openUri(dlsiteWorkUrl(work.rj)) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Text(
                work.title.ifBlank { work.rj },
                style = MaterialTheme.typography.bodyMedium,
                color = Cream,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            if (work.languages.isNotEmpty()) {
                Text(
                    work.languages.joinToString(" / ", transform = ::dlsiteLanguage),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            "查看 DLsite 作品",
            Modifier.size(18.dp),
            tint = Muted,
        )
    }
}
