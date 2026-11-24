// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Bounded LRU cache and per-work-part request deduplication for DLsite detail tabs. */
class DlsiteDetailsLoader(
    private val scope: CoroutineScope,
    private val library: LibraryStore,
    private val defaultClient: DlsiteClient,
) {
    companion object {
        private const val MAX_ENTRIES = 6
        private const val PRODUCT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val STATS_TTL_MS = 24L * 60 * 60 * 1000

        fun isFresh(
            cached: DlsiteDetails?,
            part: String,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            if (cached == null) return false
            return when (part) {
                "product" ->
                    cached.completeProduct && now - cached.productFetchedAt < PRODUCT_TTL_MS
                "description" -> cached.descriptionFetchedAt > 0
                "statistics" ->
                    cached.statistics != null && now - cached.statistics.fetchedAt < STATS_TTL_MS
                else -> false
            }
        }
    }

    val states = MutableStateFlow<Map<String, DlsiteUiState>>(emptyMap())
    private val cache = LinkedHashMap<String, DlsiteUiState>(MAX_ENTRIES + 1, 0.75f, true)
    private val activeJobs = mutableMapOf<Pair<String, String>, Job>()

    fun accept(workId: String, details: DlsiteDetails) {
        val current = cache[workId]
        val merged = details.preserving(current?.details)
        put(workId, (current ?: DlsiteUiState()).copy(details = merged))
    }

    fun load(
        workId: String,
        rj: String,
        part: String = "product",
        seed: DlsiteDetails? = null,
        force: Boolean = false,
        client: DlsiteClient = defaultClient,
    ) {
        val key = workId to part
        if (activeJobs[key]?.isActive == true) return
        val existing = cache[workId]
        val baseline = existing?.details ?: seed
        if (seed != null && existing?.details == null) {
            put(workId, (existing ?: DlsiteUiState()).copy(details = seed))
        }
        if (!force && isFresh(baseline, part)) return
        put(
            workId,
            (cache[workId] ?: DlsiteUiState(baseline)).let {
                it.copy(loading = it.loading + part, errors = it.errors - part)
            },
        )
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val stored = library.readDlsiteDetails(workId)
                    val mergedSeed =
                        (cache[workId]?.details ?: seed).let { current ->
                            stored?.preserving(current) ?: current
                        }
                    if (!force && isFresh(mergedSeed, part)) {
                        if (mergedSeed != null && cache.containsKey(workId)) {
                            put(workId, cache[workId]!!.copy(details = mergedSeed))
                        }
                        return@launch
                    }
                    val fetched: DlsiteDetails =
                        withContext(Dispatchers.IO) {
                            when (part) {
                                "description" ->
                                    DlsiteDetails(
                                        rj = rj,
                                        description = client.description(rj),
                                        descriptionFetchedAt = System.currentTimeMillis(),
                                    )
                                "statistics" ->
                                    DlsiteDetails(rj = rj, statistics = client.statistics(rj))
                                else -> client.work(rj).details
                            }
                        }
                    val combined = fetched.preserving(cache[workId]?.details ?: mergedSeed)
                    library.saveDlsiteDetails(workId, combined)
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me && cache.containsKey(workId)) {
                        put(workId, cache[workId]!!.copy(details = combined))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me && cache.containsKey(workId)) {
                        put(
                            workId,
                            cache[workId]!!.let {
                                it.copy(errors = it.errors + (part to (e.message ?: "读取失败")))
                            },
                        )
                    }
                } finally {
                    val me = currentCoroutineContext()[Job]
                    if (activeJobs[key] === me) {
                        activeJobs.remove(key)
                        cache[workId]?.let { put(workId, it.copy(loading = it.loading - part)) }
                    }
                }
            }
        activeJobs[key] = job
        job.start()
    }

    private fun put(workId: String, state: DlsiteUiState) {
        cache[workId] = state
        while (cache.size > MAX_ENTRIES) {
            val evicted = cache.entries.iterator().next().key
            cache.remove(evicted)
            activeJobs.keys
                .filter { it.first == evicted }
                .forEach { key ->
                    activeJobs.remove(key)?.cancel()
                }
        }
        states.value = cache.toMap()
    }
}

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
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    if (isPreview) "返回检索" else stringResource(R.string.action_back_library),
                    action = back,
                )
            }
            when {
                officialPlayerWorkno != null ->
                    WhispEmptyState(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        title = "使用官方播放器",
                        subtitle = "该作品可在 DLsite Play 中播放。",
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (graph.playback.state.value.playWhenReady) {
                                    graph.playback.togglePlay()
                                }
                                graph.context.startActivity(
                                    Intent(
                                            Intent.ACTION_VIEW,
                                            "https://play.dlsite.com/work/${officialPlayerWorkno}"
                                                .toUri(),
                                        )
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
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
                        icon = Icons.Outlined.CloudOff,
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
    var confirmRemoveOffline by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var attachment by remember { mutableStateOf<AttachmentEntity?>(null) }

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
    val history by graph.library.progress.collectAsStateWithLifecycle()
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
                        val pct =
                            if (detail.tracks.isEmpty()) 0f
                            else
                                trackStates
                                    .map { it.percentDownloaded.takeIf { v -> v >= 0f } ?: 0f }
                                    .sum() / (detail.tracks.size * 100f)
                        Triple(complete, active, pct.coerceIn(0f, 1f))
                    }
                    .distinctUntilChanged()
            }
            .collectAsStateWithLifecycle(
                initialValue = Triple(graph.offline.isOffline(detail), false, 0f),
            )
    val offline = downloadSnapshot.first
    val downloading = downloadSnapshot.second
    val downloadProgress = downloadSnapshot.third

    val sections = remember(detail.tracks) { TrackSections.groups(detail.tracks) }
    val totalDurationMs =
        remember(detail.tracks) { detail.tracks.sumOf { it.durationMs.coerceAtLeast(0L) } }
    val remembered =
        remember(history, detail.work.id) { history.firstOrNull { it.workId == detail.work.id } }
    val resume = inLibrary && remembered != null
    val finished =
        if (detail.tracks.any { it.id == playback.trackId }) playback.ended
        else
            PlaybackQueue.finished(
                TrackSections.queue(detail.tracks, remembered?.trackId),
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
    val headerCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex >= 1 || listState.firstVisibleItemScrollOffset > 520
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
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                if (isPreview) "返回检索"
                                else stringResource(R.string.action_back_library),
                                action = back,
                            )
                            if (inLibrary) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconAction(
                                        if (detail.work.favorite) Icons.Filled.Favorite
                                        else Icons.Outlined.FavoriteBorder,
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
                                            if (offline) Icons.Outlined.DownloadDone
                                            else Icons.Outlined.Download,
                                            stringResource(R.string.action_download),
                                            if (offline || downloading) Rose else Cream,
                                            haptic = HapticKind.Tick,
                                        ) {
                                            if (offline || downloading) {
                                                downloadManage = true
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
                                        Icons.Outlined.MoreHoriz,
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
                            Icon(Icons.Filled.PlayArrow, null)
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
                                Icons.Outlined.Shuffle,
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
                            Icon(Icons.Filled.PlayArrow, null)
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
                            Icon(Icons.Outlined.Add, null)
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
                                    IconAction(
                                        Icons.Outlined.PlayArrow,
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
                            ResourceRow(it.title, Icons.Outlined.Description) { attachment = it }
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
                                if (it.kind == "image") Icons.Outlined.Image
                                else Icons.AutoMirrored.Outlined.InsertDriveFile,
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
                    Icons.AutoMirrored.Outlined.ArrowBack,
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
                    Icons.Filled.PlayArrow,
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

    if (downloadManage && inLibrary) {
        WhispSheet(onDismissRequest = { downloadManage = false }) { dismiss ->
            Text(
                if (offline) "已离线保存" else "离线下载中 · ${(downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                color = Cream,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            if (downloading) {
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
                WhispSheetRow(
                    icon = Icons.Outlined.Pause,
                    title = "暂停下载",
                    onClick = {
                        graph.offline.pauseDownloads()
                        dismiss()
                    },
                )
                WhispSheetRow(
                    icon = Icons.Outlined.PlayArrow,
                    title = "继续下载",
                    onClick = {
                        graph.offline.resumeDownloads()
                        dismiss()
                    },
                )
            }
            WhispSheetRow(
                icon = Icons.Outlined.DeleteOutline,
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
        if (item.kind == "image") {
            WhispImageLightbox(
                image = WhispImage.Asset(item.id),
                title = item.title,
                onDismiss = { attachment = null },
            )
        } else {
            AttachmentDialog(item, graph) { attachment = null }
        }
    }
}

@Composable
private fun ResourceRow(title: String, icon: ImageVector, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = click)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Rose.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = Cream,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
        )
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun AttachmentDialog(
    attachment: AttachmentEntity,
    graph: AppGraph,
    dismiss: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var encoding by remember(attachment.id) { mutableStateOf(settings.textEncoding) }
    var rawBytes by remember(attachment.id) { mutableStateOf<ByteArray?>(null) }
    var text by remember(attachment.id) { mutableStateOf<String?>(null) }
    var loading by remember(attachment.id) { mutableStateOf(true) }
    LaunchedEffect(attachment.id) {
        try {
            rawBytes = graph.library.readAsset(attachment.id, 64 * 1024 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.messages.tryEmit(e.message ?: "附件不可读")
            dismiss()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(rawBytes, encoding) {
        val bytes = rawBytes ?: return@LaunchedEffect
        text = withContext(Dispatchers.Default) { TextEncoding.decode(bytes, encoding) }
    }
    WhispSheet(onDismissRequest = dismiss) { _ ->
        Text(
            attachment.title,
            style = MaterialTheme.typography.titleLarge,
            color = Cream,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (rawBytes != null) {
            EncodingChips(
                selected = encoding,
                onSelect = { encoding = it },
                horizontalPadding = 24.dp,
            )
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Rose)
            }
        }
        text?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = Cream,
                lineHeight = 28.sp,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 550.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun InfoDialog(
    detail: WorkDetails,
    graph: AppGraph,
    onRemoved: () -> Unit,
    dismiss: () -> Unit,
) {
    var title by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.title) }
    var artist by rememberSaveable(detail.work.id) { mutableStateOf(detail.work.artist) }
    var confirmingRemoval by rememberSaveable(detail.work.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                scope.launch {
                    runCatching { graph.offline.setCover(detail.work, it) }
                        .onFailure { err -> graph.messages.tryEmit(err.message ?: "更换封面失败") }
                }
            }
        }
    if (confirmingRemoval) {
        WhispConfirmDialog(
            title = "删除作品",
            message = "删除该作品及其下载、书签和播放进度。",
            confirmLabel = if (loading) "删除中" else "删除",
            busy = loading,
            onConfirm = {
                loading = true
                scope.launch {
                    try {
                        graph.sync.removeOnlineWork(detail.work.id)
                        dismiss()
                        onRemoved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        graph.messages.tryEmit(e.message ?: "删除失败")
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = { confirmingRemoval = false },
        )
        return
    }
    WhispSheet(onDismissRequest = dismiss) { close ->
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.action_work_info),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                    modifier = Modifier.weight(1f),
                )
                detail.work.rj?.let {
                    Text(
                        it,
                        color = Muted,
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("作品名") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text("声优") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { cover.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("更换封面")
                }
                if (detail.work.rj != null) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                try {
                                    graph.sync.updateDlsiteMetadata(detail.work.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    graph.messages.tryEmit(e.message ?: "元数据更新失败")
                                } finally {
                                    loading = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (loading) "读取中" else "更新 DLsite 元数据", maxLines = 1)
                    }
                }
            }
            if (detail.work.isRemovableOnline) {
                OutlinedButton(
                    enabled = !loading,
                    onClick = { confirmingRemoval = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("删除作品", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = close,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching {
                                graph.library.editWorkIdentity(
                                    detail.work.id,
                                    title.ifBlank { detail.work.title },
                                    artist,
                                )
                            }
                        }
                        close()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("保存", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
