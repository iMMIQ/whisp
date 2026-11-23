// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import java.util.LinkedHashMap
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
}

/** Unified detail screen handling both indexed library works and unsaved ASMR.ONE previews. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun WorkDetailScreen(
    graph: AppGraph,
    target: WorkDetailTarget,
    back: () -> Unit,
    player: () -> Unit,
    filter: ((AsmrFacet) -> Unit)? = null,
) {
    val isPreview = target is WorkDetailTarget.AsmrPreview
    val libraryWorkId =
        when (target) {
            is WorkDetailTarget.Library -> target.workId
            is WorkDetailTarget.AsmrPreview -> stableId("asmr.one|work|${target.asmrId}")
        }
    val storedDetail by
        remember(libraryWorkId) { graph.library.observeDetails(libraryWorkId) }
            .collectAsStateWithLifecycle(initialValue = null)

    var preview by remember(target) { mutableStateOf<AsmrPreview?>(null) }
    var previewLoading by remember(target) { mutableStateOf(isPreview) }
    var previewError by remember(target) { mutableStateOf<String?>(null) }
    var savingPreview by remember(target) { mutableStateOf(false) }
    var previewReload by remember(target) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target, previewReload) {
        if (target is WorkDetailTarget.AsmrPreview) {
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
                previewLoading -> CircularProgressIndicator()
                previewError != null ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(previewError!!, color = Muted)
                        TextButton(onClick = { previewReload++ }) { Text("重试") }
                    }
            }
        }
        return
    }

    val detail = effectiveDetail
    val inLibrary = storedDetail != null
    var tab by rememberSaveable(detail.work.id) { mutableStateOf("曲目") }
    var information by rememberSaveable(detail.work.id) { mutableStateOf(false) }
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
    val offline by
        remember(detail.work.id, detail.work.offlineAttachments, detail.tracks) {
                graph.media.downloads
                    .map { states ->
                        detail.work.offlineAttachments &&
                            detail.tracks.all { states[it.id]?.state == Download.STATE_COMPLETED }
                    }
                    .distinctUntilChanged()
            }
            .collectAsStateWithLifecycle(initialValue = graph.offline.isOffline(detail))

    val sections = remember(detail.tracks) { TrackSections.groups(detail.tracks) }
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

    LazyColumn(
        Modifier.fillMaxSize().then(if (isPreview) Modifier.testTag("asmr-preview") else Modifier),
    ) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(.92f).background(Surface)) {
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
                                listOf(Ink.copy(alpha = .2f), Color.Transparent, Ink),
                            ),
                        ),
                )
                CompositionLocalProvider(LocalCoverControls provides true) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconAction(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            if (isPreview) "返回检索" else stringResource(R.string.action_back_library),
                            action = back,
                        )
                        if (inLibrary) {
                            Row {
                                IconAction(
                                    if (detail.work.favorite) Icons.Outlined.Favorite
                                    else Icons.Outlined.FavoriteBorder,
                                    stringResource(R.string.action_favorite),
                                    if (detail.work.favorite) Rose else Cream,
                                    enabled = true,
                                    feedback = true,
                                ) {
                                    scope.launch { graph.library.toggleFavorite(detail.work.id) }
                                }
                                IconAction(
                                    if (offline) Icons.Outlined.DownloadDone
                                    else Icons.Outlined.Download,
                                    stringResource(R.string.action_download),
                                    if (offline) Rose else Cream,
                                ) {
                                    scope.launch {
                                        runCatching { graph.offline.downloadWork(detail.work.id) }
                                            .onFailure {
                                                graph.messages.tryEmit(it.message ?: "下载失败")
                                            }
                                    }
                                }
                                IconAction(
                                    Icons.Outlined.MoreHoriz,
                                    stringResource(R.string.action_work_info),
                                ) {
                                    information = true
                                }
                            }
                        }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Text(
                        detail.work.title,
                        fontFamily = FontFamily.Serif,
                        fontSize = 32.sp,
                        lineHeight = 40.sp,
                    )
                    if (detail.work.artist.isNotBlank()) {
                        Text(
                            detail.work.artist,
                            color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                            fontSize = 14.sp,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inLibrary) {
                    Button(
                        onClick = {
                            graph.playback.playWork(detail, restart = finished)
                            player()
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                finished -> stringResource(R.string.action_replay_work)
                                resume -> stringResource(R.string.action_resume_work)
                                else -> stringResource(R.string.action_play_work)
                            },
                            fontSize = 16.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconAction(Icons.Outlined.Shuffle, stringResource(R.string.action_shuffle)) {
                        graph.playback.playWork(detail, shuffle = true)
                        player()
                    }
                } else {
                    Button(
                        onClick = { commitPreview(playAfter = false) },
                        enabled = !savingPreview,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (savingPreview) "加入中" else "加入作品库", fontSize = 16.sp)
                    }
                }
            }
            OfficialCircle(official.details, filter)
            WorkContentTabs(tab) { tab = it }
        }
        when (tab) {
            "曲目" ->
                sections.forEach { section ->
                    if (sections.size > 1) {
                        item(key = "section:${section.key}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .padding(start = 28.dp, end = 16.dp, top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    section.title,
                                    color = Muted,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconAction(Icons.Outlined.PlayArrow, "播放${section.title}", Rose) {
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
                            graph.dlsiteLoader.load(detail.work.id, rj, part, seedDetails, force)
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
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
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
                                Modifier.padding(24.dp),
                                fontSize = 22.sp,
                                fontFamily = FontFamily.Serif,
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
        item(key = "detail-footer") { Spacer(Modifier.height(24.dp)) }
    }
    if (information && inLibrary) {
        InfoDialog(detail, graph, onRemoved = back) { information = false }
    }
    attachment?.let { AttachmentDialog(it, graph) { attachment = null } }
}

@Composable
private fun ResourceRow(title: String, icon: ImageVector, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(21.dp))
        Text(title, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 15.sp)
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
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
    var loading by remember(attachment.id) { mutableStateOf(attachment.kind != "image") }
    LaunchedEffect(attachment.id) {
        if (attachment.kind == "image") return@LaunchedEffect
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
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (rawBytes != null) {
            EncodingChips(
                selected = encoding,
                onSelect = { encoding = it },
                horizontalPadding = 24.dp,
            )
        }
        if (loading) CircularProgressIndicator(Modifier.padding(32.dp))
        if (attachment.kind == "image") {
            Artwork(
                image = WhispImage.Asset(attachment.id),
                description = attachment.title,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 600.dp).padding(16.dp),
                scale = ContentScale.Fit,
            )
        }
        text?.let {
            Text(
                it,
                fontSize = 17.sp,
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
        AlertDialog(
            onDismissRequest = { if (!loading) confirmingRemoval = false },
            containerColor = Surface,
            title = { Text("删除作品") },
            text = { Text("删除该作品及其下载、书签和播放进度。") },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
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
                ) {
                    Text(if (loading) "删除中" else "删除", color = Rose)
                }
            },
            dismissButton = {
                TextButton(enabled = !loading, onClick = { confirmingRemoval = false }) {
                    Text("取消")
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.action_work_info)) },
        containerColor = Surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("作品名") })
                OutlinedTextField(artist, { artist = it }, label = { Text("声优") })
                TextButton(onClick = { cover.launch(arrayOf("image/*")) }) { Text("更换封面") }
                if (detail.work.rj != null) {
                    TextButton(
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
                    ) {
                        Text(if (loading) "读取中" else "更新 DLsite 元数据")
                    }
                }
                if (detail.work.isRemovableOnline) {
                    TextButton(enabled = !loading, onClick = { confirmingRemoval = true }) {
                        Text("删除作品", color = Rose)
                    }
                }
                detail.work.rj?.let { Text(it, color = Muted) }
            }
        },
        confirmButton = {
            TextButton(
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
                    dismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
