// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface WorkDetailTarget {
    data class Library(val workId: String) : WorkDetailTarget

    data class AsmrPreview(val sourceId: String, val asmrId: Long) : WorkDetailTarget

    data class DlsiteOwned(val sourceId: String, val workno: String) : WorkDetailTarget
}

/**
 * Unified detail screen handling library works, ASMR.ONE previews, and non-blocking DLsite loads.
 */
@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun WorkDetailScreen(
    graph: AppGraph,
    target: WorkDetailTarget,
    back: () -> Unit,
    player: () -> Unit,
    filter: ((AsmrFacet) -> Unit)? = null,
    openWork: ((String) -> Unit)? = null,
) {
    val bg = MaterialTheme.colorScheme.background
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val isPreview = target is WorkDetailTarget.AsmrPreview
    val isDlsiteOwned = target is WorkDetailTarget.DlsiteOwned
    val libraryWorkId =
        when (target) {
            is WorkDetailTarget.Library -> target.workId
            is WorkDetailTarget.AsmrPreview -> stableId("asmr.one|work|${target.asmrId}")
            is WorkDetailTarget.DlsiteOwned ->
                stableId("dlsite|${target.sourceId}|${target.workno}")
        }
    val storedDetail by
        remember(libraryWorkId) { graph.library.observeDetails(libraryWorkId) }
            .collectAsStateWithLifecycle(initialValue = null)

    var preview by remember(target) { mutableStateOf<AsmrPreview?>(null) }
    var previewLoading by remember(target) { mutableStateOf(isPreview || isDlsiteOwned) }
    var previewError by remember(target) { mutableStateOf<String?>(null) }
    var officialPlayerWorkno by remember(target) { mutableStateOf<String?>(null) }
    var savingPreview by remember(target) { mutableStateOf(false) }
    var previewReload by remember(target) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target, previewReload) {
        when (target) {
            is WorkDetailTarget.AsmrPreview -> {
                previewLoading = true
                previewError = null
                try {
                    preview = graph.sync.previewAsmrWork(target.sourceId, target.asmrId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    previewError = e.message ?: "读取作品失败"
                } finally {
                    previewLoading = false
                }
            }
            is WorkDetailTarget.DlsiteOwned -> {
                previewLoading = true
                previewError = null
                try {
                    graph.sync.importDlsiteWork(target.sourceId, target.workno)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DlsiteNoPlayableAudioException) {
                    officialPlayerWorkno = target.workno
                } catch (e: Exception) {
                    previewError = e.message ?: "作品读取失败"
                } finally {
                    previewLoading = false
                }
            }
            is WorkDetailTarget.Library -> Unit
        }
    }

    val effectiveDetail: WorkDetails? =
        storedDetail
            ?: preview?.let {
                WorkDetails(
                    it.indexed.works.single(),
                    it.indexed.tracks,
                    it.indexed.attachments,
                )
            }

    if (effectiveDetail == null) {
        Box(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .then(if (isPreview) Modifier.testTag("asmr-preview") else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                IconAction(
                    WhispIcons.AutoMirrored.Outlined.ArrowBack,
                    if (isPreview) "返回检索" else stringResource(R.string.action_back_library),
                    action = back,
                )
            }
            when {
                officialPlayerWorkno != null ->
                    WhispEmptyState(
                        icon = WhispIcons.AutoMirrored.Outlined.OpenInNew,
                        title = "使用官方播放器",
                        subtitle = "该作品可在 DLsite Play 中播放。",
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (graph.playback.state.value.playWhenReady) {
                                    graph.playback.togglePlay()
                                }
                                runCatching {
                                    graph.context.startActivity(
                                        Intent(
                                                Intent.ACTION_VIEW,
                                                "https://play.dlsite.com/work/${officialPlayerWorkno}"
                                                    .toUri(),
                                            )
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                    .onFailure { graph.messages.tryEmit("未找到可打开链接的应用") }
                            },
                        ) {
                            Text("打开 DLsite Play")
                        }
                    }
                previewLoading ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Rose)
                        Text(
                            if (isDlsiteOwned) "正在读取曲目目录" else "正在加载作品",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted,
                        )
                    }
                previewError != null ->
                    WhispEmptyState(
                        icon = WhispIcons.Outlined.CloudOff,
                        title = "加载失败",
                        subtitle = previewError,
                    ) {
                        FilledTonalButton(onClick = { previewReload++ }) { Text("重试") }
                    }
            }
        }
        return
    }

    val detail = effectiveDetail
    val inLibrary = storedDetail != null
    var tab by rememberSaveable(detail.work.id) { mutableStateOf("曲目") }
    var information by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var downloadManage by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var downloadSectionPicker by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var confirmRemoveOffline by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<AttachmentEntity?>(null) }
    var trackMenuTarget by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistAddTarget by remember { mutableStateOf<PlaylistAddRequest?>(null) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val allDownloads by graph.media.downloads.collectAsStateWithLifecycle()
    val sourceList by graph.library.sourceList.collectAsStateWithLifecycle()
    val siblingWorks by
        remember(detail.work.rj, detail.work.id) {
                detail.work.rj?.let { rj ->
                    graph.library.observeWorksByRj(rj).map { list ->
                        list.filter { it.id != detail.work.id }
                    }
                } ?: flowOf(emptyList())
            }
            .collectAsStateWithLifecycle(initialValue = emptyList())

    val dlsiteStates by graph.dlsiteLoader.states.collectAsStateWithLifecycle()
    val storedDlsite by
        remember(detail.work.id) { graph.library.dlsiteDetails(detail.work.id) }
            .collectAsStateWithLifecycle(initialValue = null)
    val seedDetails = preview?.metadata
    LaunchedEffect(detail.work.id, storedDlsite) {
        storedDlsite?.let { graph.dlsiteLoader.accept(detail.work.id, it) }
    }
    LaunchedEffect(detail.work.id, detail.work.rj, seedDetails) {
        detail.work.rj?.let { rj ->
            graph.dlsiteLoader.load(detail.work.id, rj, seed = seedDetails)
        }
    }
    LaunchedEffect(detail.work.id, detail.work.rj, tab, seedDetails) {
        val rj = detail.work.rj ?: return@LaunchedEffect
        if (tab == "介绍") graph.dlsiteLoader.load(detail.work.id, rj, "description", seedDetails)
        if (tab == "资料") graph.dlsiteLoader.load(detail.work.id, rj, "statistics", seedDetails)
    }
    val official = dlsiteStates[detail.work.id] ?: DlsiteUiState(seedDetails)

    val playback by graph.playback.state.collectAsStateWithLifecycle()
    val remembered by
        remember(detail.work.id) { graph.library.observeProgress(detail.work.id) }
            .collectAsStateWithLifecycle(initialValue = null)
    val downloadSnapshot by
        remember(detail.work.id, detail.work.offlineAttachments, detail.tracks) {
                graph.media.downloads
                    .map { states ->
                        val trackStates = detail.tracks.mapNotNull { states[it.id] }
                        val complete =
                            detail.work.offlineAttachments &&
                                detail.tracks.isNotEmpty() &&
                                detail.tracks.all {
                                    states[it.id]?.state == Download.STATE_COMPLETED
                                }
                        val active = trackStates.any {
                            it.state == Download.STATE_DOWNLOADING ||
                                it.state == Download.STATE_QUEUED
                        }
                        val paused =
                            !complete &&
                                !active &&
                                trackStates.any {
                                    it.state == Download.STATE_STOPPED
                                }
                        val pct =
                            if (detail.tracks.isEmpty()) 0f
                            else
                                trackStates
                                    .map { it.percentDownloaded.takeIf { v -> v >= 0f } ?: 0f }
                                    .sum() / (detail.tracks.size * 100f)
                        listOf(complete, active, paused, pct.coerceIn(0f, 1f))
                    }
                    .distinctUntilChanged()
            }
            .collectAsStateWithLifecycle(
                initialValue = listOf(graph.offline.isOffline(detail), false, false, 0f),
            )
    val offline = downloadSnapshot[0] as Boolean
    val downloading = downloadSnapshot[1] as Boolean
    val downloadPaused = downloadSnapshot[2] as Boolean
    val downloadProgress = downloadSnapshot[3] as Float

    val sections =
        remember(detail.tracks, settings.editionPreference) {
            TrackSections.groups(detail.tracks, settings.editionPreference)
        }
    val totalDurationMs =
        remember(detail.tracks) { detail.tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    val resume = inLibrary && remembered != null
    val finished =
        if (detail.tracks.any { it.id == playback.trackId }) playback.ended
        else
            PlaybackQueue.finished(
                TrackSections.queue(detail.tracks, remembered?.trackId, settings.editionPreference),
                remembered,
            )
    val scripts =
        remember(detail.attachments) {
            detail.attachments.filter { it.kind == "script" }.sortedBy { it.relativePath }
        }
    val extras =
        remember(detail.attachments) {
            detail.attachments
                .filter { it.kind !in setOf("script", "subtitle", "cover") }
                .sortedBy { it.relativePath }
        }

    val coverImage: WhispImage? =
        remember(detail.work.coverId, detail.work.coverUrl, target, inLibrary) {
            val coverId = detail.work.coverId ?: return@remember null
            if (
                !inLibrary && detail.work.coverUrl != null && target is WorkDetailTarget.AsmrPreview
            ) {
                WhispImage.Official(coverId, detail.work.coverUrl)
            } else {
                WhispImage.Asset(coverId)
            }
        }

    fun commitPreview(playAfter: Boolean = false, trackId: String? = null) {
        val currentPreview = preview ?: return
        if (savingPreview) return
        savingPreview = true
        scope.launch {
            try {
                val mergedMetadata =
                    official.details?.preserving(currentPreview.metadata) ?: currentPreview.metadata
                val savedId =
                    graph.sync.addAsmrPreview(currentPreview.copy(metadata = mergedMetadata))
                if (playAfter) {
                    graph.library.workDetails(savedId)?.let { saved ->
                        graph.playback.playWork(saved, trackId)
                        player()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.messages.tryEmit(e.message ?: "保存作品失败")
            } finally {
                savingPreview = false
            }
        }
    }

    val listState = rememberLazyListState()
    val collapseThresholdPx =
        with(androidx.compose.ui.platform.LocalDensity.current) { 180.dp.roundToPx() }
    val headerCollapsed by
        remember(collapseThresholdPx) {
            derivedStateOf {
                listState.firstVisibleItemIndex >= 1 ||
                    listState.firstVisibleItemScrollOffset > collapseThresholdPx
            }
        }
    val stickyClearance by
        animateDpAsState(
            targetValue = if (headerCollapsed) 52.dp else 0.dp,
            animationSpec = tween(if (reduced) 0 else 160),
            label = "sticky-clearance",
        )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier.fillMaxSize()
                    .then(if (isPreview) Modifier.testTag("asmr-preview") else Modifier),
        ) {
            item(key = "detail-header") {
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 260.dp, max = 420.dp)
                        .aspectRatio(1.16f)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Artwork(
                        image = coverImage,
                        description = detail.work.title,
                        modifier = Modifier.matchParentSize(),
                        sharedKey = detail.work.id,
                    )
                    Box(
                        Modifier.matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to bg.copy(alpha = .50f),
                                    0.28f to Color.Transparent,
                                    0.54f to bg.copy(alpha = .52f),
                                    0.84f to bg.copy(alpha = .94f),
                                    1.0f to bg,
                                ),
                            ),
                    )
                    CompositionLocalProvider(LocalCoverControls provides true) {
                        Row(
                            Modifier.fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            IconAction(
                                WhispIcons.AutoMirrored.Outlined.ArrowBack,
                                if (isPreview) "返回检索"
                                else stringResource(R.string.action_back_library),
                                action = back,
                            )
                            if (inLibrary) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconAction(
                                        if (detail.work.favorite) WhispIcons.Filled.Favorite
                                        else WhispIcons.Outlined.FavoriteBorder,
                                        stringResource(R.string.action_favorite),
                                        if (detail.work.favorite) Rose else Cream,
                                        enabled = true,
                                        feedback = true,
                                    ) {
                                        scope.launch {
                                            graph.library.toggleFavorite(detail.work.id)
                                        }
                                    }
                                    Box(contentAlignment = Alignment.Center) {
                                        IconAction(
                                            when {
                                                offline -> WhispIcons.Outlined.DownloadDone
                                                downloadPaused ->
                                                    WhispIcons.Outlined.PauseCircleOutline
                                                else -> WhispIcons.Outlined.Download
                                            },
                                            stringResource(R.string.action_download),
                                            if (offline || downloading || downloadPaused) Rose
                                            else Cream,
                                            haptic = HapticKind.Tick,
                                        ) {
                                            if (offline || downloading || downloadPaused) {
                                                downloadManage = true
                                            } else if (sections.size > 1) {
                                                downloadSectionPicker = true
                                            } else {
                                                scope.launch {
                                                    runCatching {
                                                        graph.offline.downloadWork(detail.work.id)
                                                    }
                                                        .onSuccess {
                                                            graph.messages.tryEmit("已开始离线下载")
                                                        }
                                                        .onFailure {
                                                            graph.messages.tryEmit(
                                                                it.message ?: "下载失败",
                                                            )
                                                        }
                                                }
                                            }
                                        }
                                        if (downloading) {
                                            CircularProgressIndicator(
                                                progress = {
                                                    downloadProgress.coerceAtLeast(0.08f)
                                                },
                                                modifier = Modifier.size(34.dp),
                                                color = Rose,
                                                trackColor = Rose.copy(alpha = 0.22f),
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                    }
                                    IconAction(
                                        WhispIcons.Outlined.MoreHoriz,
                                        stringResource(R.string.action_work_info),
                                        haptic = HapticKind.Tick,
                                    ) {
                                        information = true
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            detail.work.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Cream,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (detail.work.artist.isNotBlank()) {
                            Text(
                                detail.work.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Cream.copy(alpha = 0.80f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                val metaParts =
                    remember(
                        detail.tracks.size,
                        totalDurationMs,
                        detail.work.rj,
                        official.details,
                    ) {
                        buildList {
                            if (detail.tracks.isNotEmpty()) add("${detail.tracks.size} 曲")
                            if (totalDurationMs > 0L) add(clock(totalDurationMs))
                            detail.work.rj?.let { add(it) }
                            official.details?.statistics?.rating?.let {
                                add("★ " + String.format(Locale.ROOT, "%.2f", it))
                            }
                        }
                    }
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                        color = Muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
                OfficialCircle(official.details, filter)
                val userTagList =
                    remember(detail.work.userTags) { parseUserTags(detail.work.userTags) }
                if (userTagList.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(userTagList, key = { it }) { tag ->
                            SuggestionChip(
                                onClick = { information = true },
                                label = { Text("#$tag") },
                            )
                        }
                    }
                }
                if (inLibrary && siblingWorks.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(siblingWorks, key = { it.id }) { sibling ->
                            val srcName =
                                sourceList.firstOrNull { it.id == sibling.sourceId }?.name
                                    ?: sibling.origin.uppercase()
                            AssistChip(
                                onClick = {
                                    if (openWork != null) openWork(sibling.id)
                                    else {
                                        scope.launch {
                                            runCatching {
                                                graph.offline.linkSiblingSubtitles(
                                                    detail.work.id,
                                                    sibling.id,
                                                )
                                            }
                                                .onSuccess { count ->
                                                    graph.messages.tryEmit(
                                                        if (count > 0) "已从 $srcName 关联 $count 条字幕"
                                                        else "$srcName 暂无可关联字幕",
                                                    )
                                                }
                                                .onFailure {
                                                    graph.messages.tryEmit(it.message ?: "关联字幕失败")
                                                }
                                        }
                                    }
                                },
                                label = { Text("同号来源：$srcName") },
                                leadingIcon = {
                                    Icon(WhispIcons.Outlined.Lan, null, Modifier.size(16.dp))
                                },
                            )
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            graph.offline.linkSiblingSubtitles(
                                                detail.work.id,
                                                sibling.id,
                                            )
                                        }
                                            .onSuccess { count ->
                                                graph.messages.tryEmit(
                                                    if (count > 0) "已从 $srcName 关联 $count 条字幕"
                                                    else "$srcName 暂无可关联字幕",
                                                )
                                            }
                                            .onFailure {
                                                graph.messages.tryEmit(it.message ?: "关联字幕失败")
                                            }
                                    }
                                },
                                label = { Text("复用 $srcName 字幕") },
                                leadingIcon = {
                                    Icon(WhispIcons.Outlined.Subtitles, null, Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (inLibrary) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                graph.playback.playWork(detail, restart = finished)
                                player()
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(WhispIcons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    finished -> stringResource(R.string.action_replay_work)
                                    resume -> stringResource(R.string.action_resume_work)
                                    else -> stringResource(R.string.action_play_work)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Box(
                            Modifier.size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(16.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconAction(
                                WhispIcons.Outlined.Shuffle,
                                stringResource(R.string.action_shuffle),
                                Cream,
                                haptic = HapticKind.Tick,
                            ) {
                                graph.playback.playWork(detail, shuffle = true)
                                player()
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                haptics.confirm()
                                commitPreview(playAfter = true)
                            },
                            enabled = !savingPreview && detail.tracks.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(WhispIcons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (savingPreview) "准备中" else "立即试听",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                haptics.confirm()
                                commitPreview(playAfter = false)
                            },
                            enabled = !savingPreview,
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(WhispIcons.Outlined.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (savingPreview) "加入中" else "加入作品库",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            stickyHeader(key = "detail-tabs") {
                Column(
                    Modifier.fillMaxWidth()
                        .background(bg)
                        .then(if (headerCollapsed) Modifier.statusBarsPadding() else Modifier)
                        .padding(top = stickyClearance),
                ) {
                    WorkContentTabs(
                        tab = tab,
                        select = {
                            haptics.tick()
                            tab = it
                        },
                        onRefresh =
                            if (tab != "曲目" && detail.work.rj != null) {
                                {
                                    val rj = detail.work.rj
                                    val part = if (tab == "介绍") "description" else "statistics"
                                    graph.dlsiteLoader.load(
                                        detail.work.id,
                                        rj,
                                        "product",
                                        seedDetails,
                                        true,
                                    )
                                    graph.dlsiteLoader.load(
                                        detail.work.id,
                                        rj,
                                        part,
                                        seedDetails,
                                        true,
                                    )
                                }
                            } else null,
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            when (tab) {
                "曲目" ->
                    sections.forEach { section ->
                        if (sections.size > 1) {
                            item(key = "section:${section.key}") {
                                val sectionDuration =
                                    remember(section.tracks) {
                                        section.tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
                                    }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(
                                            start = 20.dp,
                                            end = 12.dp,
                                            top = 14.dp,
                                            bottom = 4.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            section.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Cream,
                                        )
                                        val sub =
                                            if (sectionDuration > 0L)
                                                "${section.tracks.size} 曲 · ${clock(sectionDuration)}"
                                            else "${section.tracks.size} 曲"
                                        Text(
                                            sub,
                                            style =
                                                MaterialTheme.typography.bodySmall.merge(
                                                    TabularNumeralStyle,
                                                ),
                                            color = Muted,
                                        )
                                    }
                                    if (inLibrary) {
                                        IconAction(
                                            WhispIcons.AutoMirrored.Outlined.QueueMusic,
                                            "将${section.title}加入歌单",
                                            Muted,
                                            haptic = HapticKind.Tick,
                                        ) {
                                            playlistAddTarget =
                                                PlaylistAddRequest(
                                                    workId = detail.work.id,
                                                    trackIds = section.tracks.map { it.id },
                                                )
                                        }
                                    }
                                    IconAction(
                                        WhispIcons.Outlined.PlayArrow,
                                        "播放${section.title}",
                                        Rose,
                                        haptic = HapticKind.Confirm,
                                    ) {
                                        if (inLibrary) {
                                            graph.playback.playWork(
                                                detail,
                                                section.tracks.first().id,
                                                startPosition = 0,
                                            )
                                            player()
                                        } else {
                                            commitPreview(
                                                playAfter = true,
                                                trackId = section.tracks.first().id,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(section.tracks, key = { it.id }) { track ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    TrackRow(track, playback.trackId == track.id) {
                                        haptics.tick()
                                        if (inLibrary) {
                                            graph.playback.playWork(detail, track.id)
                                            player()
                                        } else {
                                            commitPreview(playAfter = true, trackId = track.id)
                                        }
                                    }
                                }
                                if (inLibrary) {
                                    IconAction(
                                        WhispIcons.Outlined.MoreVert,
                                        "曲目操作",
                                        Muted,
                                        haptic = HapticKind.Tick,
                                    ) {
                                        trackMenuTarget = track
                                    }
                                }
                            }
                        }
                    }
                else -> {
                    dlsiteContent(
                        tab = tab,
                        state = official,
                        rj = detail.work.rj,
                        onLoad = { part, force ->
                            detail.work.rj?.let { rj ->
                                graph.dlsiteLoader.load(
                                    detail.work.id,
                                    rj,
                                    part,
                                    seedDetails,
                                    force,
                                )
                            }
                        },
                        seed = seedDetails,
                        filter = filter,
                    )
                    if (tab == "资料" && inLibrary) {
                        if (scripts.isNotEmpty()) {
                            item(key = "header-scripts") {
                                Text(
                                    "台本",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Cream,
                                    modifier =
                                        Modifier.padding(
                                            start = 20.dp,
                                            end = 20.dp,
                                            top = 20.dp,
                                            bottom = 8.dp,
                                        ),
                                )
                            }
                        }
                        items(scripts, key = { it.id }) {
                            ResourceRow(it.title, WhispIcons.Outlined.Description) {
                                attachment = it
                            }
                        }
                        if (extras.isNotEmpty()) {
                            item(key = "header-extras") {
                                Text(
                                    "附赠",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Cream,
                                    modifier =
                                        Modifier.padding(
                                            start = 20.dp,
                                            end = 20.dp,
                                            top = 20.dp,
                                            bottom = 8.dp,
                                        ),
                                )
                            }
                        }
                        items(extras, key = { it.id }) {
                            ResourceRow(
                                it.title,
                                if (it.kind == "image") WhispIcons.Outlined.Image
                                else WhispIcons.AutoMirrored.Outlined.InsertDriveFile,
                            ) {
                                attachment = it
                            }
                        }
                    }
                }
            }
            item(key = "detail-footer") { Spacer(Modifier.height(28.dp)) }
        }
        // Pinned top bar appears cleanly once scrolled past cover, keeping navigation & play
        // reachable.
        AnimatedVisibility(
            visible = headerCollapsed,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(if (reduced) 0 else 160)),
            exit = fadeOut(tween(if (reduced) 0 else 120)),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(bg.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    WhispIcons.AutoMirrored.Outlined.ArrowBack,
                    if (isPreview) "返回检索" else stringResource(R.string.action_back_library),
                    action = back,
                )
                Text(
                    shortTitle(detail.work.title).ifBlank { detail.work.title },
                    style = MaterialTheme.typography.titleMedium,
                    color = Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                IconAction(
                    WhispIcons.Filled.PlayArrow,
                    stringResource(R.string.action_play_work),
                    Rose,
                    haptic = HapticKind.Confirm,
                ) {
                    if (inLibrary) {
                        graph.playback.playWork(detail, restart = finished)
                        player()
                    } else {
                        commitPreview(playAfter = true)
                    }
                }
            }
        }
    }

    if (downloadSectionPicker && inLibrary) {
        WhispSheet(onDismissRequest = { downloadSectionPicker = false }) { dismiss ->
            Text(
                "选择离线下载版本",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            Text(
                "多版本作品可只下载指定格式以节省存储空间",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
            sections.forEach { section ->
                val sizeLabel = formatSectionBytes(section.totalBytes)
                val sub =
                    if (sizeLabel != null) "${section.tracks.size} 曲 · $sizeLabel"
                    else "${section.tracks.size} 曲"
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Download,
                    title = section.title,
                    subtitle = sub,
                    onClick = {
                        dismiss()
                        scope.launch {
                            runCatching {
                                graph.offline.downloadTracks(
                                    detail.work.id,
                                    section.tracks.map { it.id },
                                )
                            }
                                .onSuccess {
                                    graph.messages.tryEmit("已开始下载「${section.title}」")
                                }
                                .onFailure {
                                    graph.messages.tryEmit(it.message ?: "下载失败")
                                }
                        }
                    },
                )
            }
            val allSize = formatSectionBytes(detail.tracks.sumOf { it.size.coerceAtLeast(0L) })
            WhispSheetRow(
                icon = WhispIcons.Outlined.FileDownload,
                title = "下载全部版本",
                subtitle =
                    if (allSize != null) "${detail.tracks.size} 曲 · $allSize"
                    else "${detail.tracks.size} 曲",
                onClick = {
                    dismiss()
                    scope.launch {
                        runCatching { graph.offline.downloadWork(detail.work.id) }
                            .onSuccess { graph.messages.tryEmit("已开始离线下载") }
                            .onFailure { graph.messages.tryEmit(it.message ?: "下载失败") }
                    }
                },
            )
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    trackMenuTarget?.let { targetTrack ->
        val isTrackOffline = allDownloads[targetTrack.id]?.state == Download.STATE_COMPLETED
        WhispSheet(onDismissRequest = { trackMenuTarget = null }) { dismiss ->
            Text(
                targetTrack.title,
                style = MaterialTheme.typography.titleMedium,
                color = Cream,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            WhispSheetRow(
                icon = WhispIcons.Outlined.PlayArrow,
                title = "下一首播放",
                subtitle = "插入到当前播放曲目之后",
                onClick = {
                    dismiss()
                    graph.playback.appendToQueue(detail.work, listOf(targetTrack), playNext = true)
                },
            )
            WhispSheetRow(
                icon = WhispIcons.AutoMirrored.Outlined.QueueMusic,
                title = "加入播放队列末尾",
                onClick = {
                    dismiss()
                    graph.playback.appendToQueue(detail.work, listOf(targetTrack), playNext = false)
                },
            )
            WhispSheetRow(
                icon = WhispIcons.Outlined.BookmarkAdd,
                title = "加入自定义歌单",
                onClick = {
                    dismiss()
                    playlistAddTarget =
                        PlaylistAddRequest(
                            workId = detail.work.id,
                            trackIds = listOf(targetTrack.id),
                        )
                },
            )
            if (isTrackOffline) {
                WhispSheetRow(
                    icon = WhispIcons.Outlined.DeleteOutline,
                    title = "移除此曲离线副本",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        dismiss()
                        scope.launch {
                            runCatching {
                                graph.offline.removeTrackOffline(detail.work.id, targetTrack.id)
                            }
                                .onSuccess { graph.messages.tryEmit("已移除此曲离线副本") }
                                .onFailure { graph.messages.tryEmit(it.message ?: "移除失败") }
                        }
                    },
                )
            } else {
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Download,
                    title = "仅离线下载此曲",
                    subtitle = formatSectionBytes(targetTrack.size),
                    onClick = {
                        dismiss()
                        scope.launch {
                            runCatching {
                                graph.offline.downloadTracks(detail.work.id, listOf(targetTrack.id))
                            }
                                .onSuccess { graph.messages.tryEmit("已开始离线下载此曲") }
                                .onFailure { graph.messages.tryEmit(it.message ?: "下载失败") }
                        }
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    playlistAddTarget?.let { req ->
        AddToPlaylistSheet(
            graph = graph,
            request = req,
            onDismiss = { playlistAddTarget = null },
        )
    }
    if (downloadManage && inLibrary) {
        WhispSheet(onDismissRequest = { downloadManage = false }) { dismiss ->
            Text(
                when {
                    offline -> "已离线保存"
                    downloadPaused -> "下载已暂停 · ${(downloadProgress * 100).toInt()}%"
                    else -> "离线下载中 · ${(downloadProgress * 100).toInt()}%"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            if (downloading || downloadPaused) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp)),
                    color = Rose,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            if (downloading) {
                WhispSheetRow(
                    icon = WhispIcons.Outlined.Pause,
                    title = "暂停下载",
                    onClick = {
                        graph.offline.pauseDownloads()
                        dismiss()
                    },
                )
            } else if (downloadPaused) {
                WhispSheetRow(
                    icon = WhispIcons.Outlined.PlayArrow,
                    title = "继续下载",
                    onClick = {
                        graph.offline.resumeDownloads()
                        dismiss()
                    },
                )
            }
            WhispSheetRow(
                icon = WhispIcons.Outlined.DeleteOutline,
                title = "移除离线副本",
                subtitle = "清理此作品已下载的本地音频与附件",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    dismiss()
                    confirmRemoveOffline = true
                },
            )
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
    if (confirmRemoveOffline) {
        WhispConfirmDialog(
            title = "移除离线副本",
            message = "移除「${shortTitle(detail.work.title).ifBlank { detail.work.title }}」的本地离线文件？",
            confirmLabel = "移除",
            onConfirm = {
                confirmRemoveOffline = false
                scope.launch {
                    runCatching { graph.offline.removeOffline(detail.work.id) }
                        .onFailure { graph.messages.tryEmit(it.message ?: "移除失败") }
                }
            },
            onDismiss = { confirmRemoveOffline = false },
        )
    }
    if (information && inLibrary) {
        InfoDialog(detail, graph, onRemoved = back) { information = false }
    }
    attachment?.let { item ->
        when {
            item.kind == "image" ->
                WhispImageLightbox(
                    image = WhispImage.Asset(item.id),
                    title = item.title,
                    onDismiss = { attachment = null },
                )
            item.kind == "document" || item.fileName.endsWith(".pdf", ignoreCase = true) ->
                PdfViewerDialog(item, graph) { attachment = null }
            else -> AttachmentDialog(item, graph) { attachment = null }
        }
    }
}

private fun formatSectionBytes(bytes: Long): String? =
    when {
        bytes >= 1024L * 1024 * 1024 ->
            String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes > 0L -> "${(bytes + 1023) / 1024} KiB"
        else -> null
    }
