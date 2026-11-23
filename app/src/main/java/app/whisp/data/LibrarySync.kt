// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Unified library synchronization & online catalog ingestion:
 * - Scans Local / SMB / NFS / WebDAV / ASMR.ONE / DLsite sources without scan-time duration I/O
 * - Single atomic merge helper shared by local scans and online imports
 * - Handles source removal, online work deletion, and DLsite metadata refreshes
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LibrarySync(
    private val context: Context,
    private val library: LibraryStore,
    private val sources: SourceRegistry,
    private val media: MediaSourceRouting,
    private val offline: OfflineStore,
    private val images: WhispImages,
    private val messages: MutableSharedFlow<String>,
    private val detachTracks: (Set<String>) -> Unit,
) {
    companion object {
        private const val DLSITE_SYNC_OVERLAP_MS = 5 * 60_000L
        private const val DLSITE_ENRICH_BACKOFF_MS = 24 * 60 * 60 * 1000L
    }

    val busy = MutableStateFlow<Set<String>>(emptySet())
    internal val scanOwners = ConcurrentHashMap<String, Any>()

    fun scheduleScan(id: String, force: Boolean = false) {
        if (!force && id in busy.value) return
        val request =
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf("sourceId" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("scan-$id", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun scan(id: String) {
        val source = library.dao.source(id) ?: return
        val owner = Any()
        synchronized(scanOwners) {
            scanOwners[id] = owner
            busy.update { it + id }
        }
        var dlsiteClient: DlsiteAccountClient? = null
        val startTime = SystemClock.elapsedRealtime()
        try {
            Log.i("WhispScan", "Started ${source.kind} scan")
            when (source.kindType) {
                SourceKind.DLSITE -> {
                    val client = sources.dlsiteAccount(source.id)
                    dlsiteClient = client
                    syncDlsitePurchases(source.id, false, client)
                    return
                }
                SourceKind.ASMRONE -> {
                    for (workId in library.dao.sourceWorkIds(source.id)) {
                        currentCoroutineContext().ensureActive()
                        val asmrId =
                            library.dao.work(workId)?.rj?.removePrefix("RJ")?.toLongOrNull()
                                ?: continue
                        importAsmrWork(source.id, asmrId, existingOnly = true)
                    }
                    library.database.withTransaction {
                        library.dao.source(source.id)?.let {
                            library.dao.putSource(
                                it.copy(lastScan = System.currentTimeMillis(), error = null),
                            )
                        }
                    }
                    return
                }
                else -> Unit
            }
            val entries = sources.walk(source)
            val indexed = LibraryIndexer.index(entries, id)
            Log.i(
                "WhispScan",
                "Listed ${entries.size} files; indexed ${indexed.works.size} works, ${indexed.tracks.size} tracks",
            )
            require(
                indexed.tracks.isNotEmpty() ||
                    source.lastScan > 0 ||
                    library.dao.sourceWorkIds(id).isNotEmpty(),
            ) {
                "目录中未找到音频，请检查来源目录"
            }
            currentCoroutineContext().ensureActive()
            val obsoleteTracks = mutableListOf<String>()
            val generation = System.currentTimeMillis()
            library.database.withTransaction {
                val latestSource = library.dao.source(id) ?: throw IOException("来源已移除")
                val existingSourceWorks = library.dao.sourceWorks(id)
                val incomingIds = indexed.works.map { it.id }.toSet()
                val tracksByWork = indexed.tracks.groupBy { it.workId }
                val attachmentsByWork = indexed.attachments.groupBy { it.workId }
                for (work in indexed.works) {
                    obsoleteTracks +=
                        mergeWorkInTransaction(
                            freshWork = work,
                            tracks = tracksByWork[work.id].orEmpty(),
                            attachments = attachmentsByWork[work.id].orEmpty(),
                            preserveAnyLocalTitle = true,
                        )
                }
                // Reclaim tracks/works removed from this source unless pinned offline.
                for (missing in existingSourceWorks) {
                    if (missing.id in incomingIds) continue
                    val detail = library.dao.details(missing.id) ?: continue
                    val keepTracks =
                        detail.tracks.filter {
                            media.isCompleted(it.id) || library.offlineFile(it.id) != null
                        }
                    val removedTracks =
                        detail.tracks.map { it.id } - keepTracks.map { it.id }.toSet()
                    removedTracks.chunked(500).forEach { library.dao.deleteTracks(it) }
                    obsoleteTracks += removedTracks
                    val removedAttachments =
                        detail.attachments
                            .filter { library.offlineFile(it.id) == null }
                            .map { it.id }
                    removedAttachments.chunked(500).forEach { library.dao.deleteAttachments(it) }
                    if (keepTracks.isEmpty() && !detail.work.offlineAttachments) {
                        // Detach sourceId so work hides until re-added, keeping favorite/progress
                        // if desired?
                        // Detaching sourceId hides it from visible library cleanly.
                        library.dao.putWorks(listOf(detail.work.copy(sourceId = null)))
                    }
                }
                library.dao.putSource(latestSource.copy(lastScan = generation, error = null))
            }
            removeObsoleteDownloads(obsoleteTracks)
            library.storageChanged()
            Log.i("WhispScan", "Scan completed in ${SystemClock.elapsedRealtime() - startTime} ms")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (scanOwners[id] !== owner)
                throw CancellationException("扫描已被替换").apply { initCause(error) }
            suspend fun reportError() {
                library.database.withTransaction {
                    if (scanOwners[id] !== owner)
                        throw CancellationException("扫描已被替换").apply { initCause(error) }
                    library.dao.source(id)?.let { latest ->
                        if (
                            latest.kind == source.kind &&
                                latest.root == source.root &&
                                latest.username == source.username &&
                                (dlsiteClient != null || latest.secret == source.secret)
                        ) {
                            library.dao.updateSourceError(id, error.message ?: "来源不可用")
                            messages.tryEmit("${latest.name}：${error.message ?: "扫描失败"}")
                        }
                    }
                }
            }
            if (source.kindType == SourceKind.DLSITE) {
                sources.dlsiteCookieWrites.withLock {
                    dlsiteClient?.let { sources.requireDlsiteClient(id, it) }
                    reportError()
                }
            } else reportError()
            Log.w("WhispScan", "Scan failed: ${error.javaClass.simpleName}")
            throw error
        } finally {
            synchronized(scanOwners) {
                if (scanOwners[id] === owner) {
                    scanOwners.remove(id)
                    busy.update { it - id }
                }
            }
        }
    }

    suspend fun syncDlsitePurchases(
        sourceId: String,
        full: Boolean = false,
        client: DlsiteAccountClient? = null,
    ) {
        val activeClient = client ?: sources.dlsiteAccount(sourceId)
        val source =
            sources.dlsiteCookieWrites.withLock {
                sources.requireDlsiteClient(sourceId, activeClient)
                library.dao.source(sourceId) ?: throw CancellationException("来源已移除")
            }
        val querySince =
            if (full || source.lastScan <= 0L) 0L
            else (source.lastScan - DLSITE_SYNC_OVERLAP_MS).coerceAtLeast(0L)
        val synced =
            try {
                activeClient.purchases(querySince)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sources.dlsiteCookieWrites.withLock {
                    sources.requireDlsiteClient(sourceId, activeClient)
                }
                throw e
            }
        sources.dlsiteCookieWrites.withLock {
            sources.requireDlsiteClient(sourceId, activeClient)
            library.database.withTransaction {
                val latest = library.dao.source(sourceId) ?: throw CancellationException("来源已移除")
                library.dao.putDlsitePurchases(
                    synced.works.map {
                        val old =
                            decodeOwnedWork(
                                library.dao.dlsitePurchase(sourceId, it.workno)?.snapshot.orEmpty(),
                            )
                        val merged =
                            it.copy(
                                artist = it.artist.ifBlank { old?.artist.orEmpty() },
                                circle = it.circle.ifBlank { old?.circle.orEmpty() },
                                coverUrl = it.coverUrl ?: old?.coverUrl,
                                purchasedAt =
                                    it.purchasedAt.takeIf { date -> date > 0 }
                                        ?: old?.purchasedAt
                                        ?: 0,
                                metadataReady = old?.metadataReady == true,
                                metadataAttemptedAt = old?.metadataAttemptedAt ?: 0,
                            )
                        DlsitePurchaseEntity(
                            sourceId,
                            merged.workno,
                            merged.purchasedAt,
                            merged.encode(),
                        )
                    },
                )
                library.dao.putSource(latest.copy(lastScan = synced.completedAt, error = null))
            }
        }
        enrichDlsiteCatalog(sourceId, activeClient)
    }

    private suspend fun enrichDlsiteCatalog(sourceId: String, client: DlsiteAccountClient) =
        coroutineScope {
            val now = System.currentTimeMillis()
            val pending =
                library.dlsitePurchases(sourceId).first().filter {
                    !it.metadataReady && now - it.metadataAttemptedAt >= DLSITE_ENRICH_BACKOFF_MS
                }
            if (pending.isEmpty()) return@coroutineScope
            val lanes = Semaphore(2)
            for (batch in pending.chunked(20)) {
                val results =
                    batch
                        .map { owned ->
                            async {
                                lanes.withPermit {
                                    val attemptTime = System.currentTimeMillis()
                                    val metadata =
                                        try {
                                            sources.dlsite.work(owned.workno)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            null
                                        }
                                    Triple(owned.workno, metadata, attemptTime)
                                }
                            }
                        }
                        .awaitAll()
                sources.dlsiteCookieWrites.withLock {
                    sources.requireDlsiteClient(sourceId, client)
                    library.database.withTransaction {
                        if (library.dao.source(sourceId) == null) return@withTransaction
                        val updates = mutableListOf<DlsitePurchaseEntity>()
                        for ((workno, metadata, attemptTime) in results) {
                            val current =
                                decodeOwnedWork(
                                    library.dao
                                        .dlsitePurchase(sourceId, workno)
                                        ?.snapshot
                                        .orEmpty(),
                                ) ?: continue
                            val enriched =
                                if (metadata != null) {
                                    val voices =
                                        metadata.details.credits
                                            .filter { it.role == "voice_by" }
                                            .joinToString(" / ") { it.name }
                                    current.copy(
                                        title = metadata.title,
                                        artist = voices.ifBlank { current.artist },
                                        circle = metadata.details.circle ?: current.circle,
                                        coverUrl = metadata.coverUrl,
                                        metadataReady = metadata.details.completeProduct,
                                        metadataAttemptedAt = attemptTime,
                                    )
                                } else {
                                    current.copy(metadataAttemptedAt = attemptTime)
                                }
                            updates +=
                                DlsitePurchaseEntity(
                                    sourceId,
                                    enriched.workno,
                                    enriched.purchasedAt,
                                    enriched.encode(),
                                )
                        }
                        if (updates.isNotEmpty()) library.dao.putDlsitePurchases(updates)
                    }
                }
            }
        }

    suspend fun importDlsiteWork(
        sourceId: String,
        workno: String,
        refresh: Boolean = false,
    ): WorkDetails =
        withContext(Dispatchers.IO) {
            val workId = stableId("dlsite|$sourceId|$workno")
            library.withWorkLock(workId) {
                val cached =
                    decodeOwnedWork(
                        library.dao.dlsitePurchase(sourceId, workno)?.snapshot.orEmpty(),
                    )
                val client = sources.dlsiteAccount(sourceId)
                val work = cached ?: client.work(workno)
                if (!refresh)
                    library.dao
                        .details(workId)
                        ?.takeIf { it.tracks.isNotEmpty() && it.work.sourceId == sourceId }
                        ?.let {
                            return@withWorkLock it
                        }
                val indexed = client.index(work, sourceId)
                val metadata =
                    try {
                        sources.dlsite.work(workno).withPrevious(library.readDlsiteDetails(workId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                val base = indexed.works.single()
                val fresh =
                    metadata?.let {
                        base.copy(
                            title = it.title,
                            originalTitle = it.title,
                            artist = it.artist,
                            originalArtist = it.artist,
                            coverId = stableId("dlsite-cover|${it.coverUrl}"),
                            coverUrl = it.coverUrl,
                            series = it.series,
                        )
                    } ?: base
                var obsoleteTracks = emptyList<String>()
                library.database.withTransaction {
                    val latestSource = library.dao.source(sourceId) ?: error("来源已移除")
                    require(latestSource.kindType == SourceKind.DLSITE) { "来源已移除" }
                    obsoleteTracks =
                        mergeWorkInTransaction(
                            freshWork = fresh,
                            tracks = indexed.tracks,
                            attachments = indexed.attachments,
                            metadata = metadata?.details,
                        )
                    library.dao.putDlsitePurchases(
                        listOf(
                            DlsitePurchaseEntity(
                                sourceId,
                                workno,
                                work.purchasedAt,
                                work
                                    .copy(
                                        title = fresh.originalTitle,
                                        artist = fresh.originalArtist,
                                        coverUrl = fresh.coverUrl,
                                        metadataReady =
                                            metadata?.details?.completeProduct == true ||
                                                work.metadataReady,
                                        metadataAttemptedAt = System.currentTimeMillis(),
                                    )
                                    .encode(),
                            ),
                        ),
                    )
                }
                removeObsoleteDownloads(obsoleteTracks)
                library.storageChanged()
                library.dao.details(workId) ?: error("作品保存失败")
            }
        }

    suspend fun asmrPage(query: AsmrQuery, page: Int): AsmrPage {
        val source = library.dao.source(query.sourceId) ?: error("来源已移除")
        val client = sources.asmrClient(source)
        val direct = asmrWorkNumber(query.text)
        if (direct != null && query.facets.isEmpty() && !query.subtitlesOnly) {
            if (page != 1) return AsmrPage(emptyList(), false)
            return try {
                val work = client.work(direct)
                require(work.id == direct) { "作品编号不匹配" }
                AsmrPage(listOf(work), false)
            } catch (e: IOException) {
                if ((e.cause as? OnlineHttpException)?.code == 404) AsmrPage(emptyList(), false)
                else throw e
            }
        }
        return client.catalog(page, query.serverText, query.order, query.subtitlesOnly)
    }

    suspend fun asmrWork(sourceId: String, id: Long): AsmrWork =
        sources.asmrClient(library.dao.source(sourceId) ?: error("来源已移除")).work(id)

    suspend fun previewAsmrWork(
        sourceId: String,
        id: Long,
        metadataClient: DlsiteClient = sources.dlsite,
    ): AsmrPreview =
        withContext(Dispatchers.IO) {
            val source = library.dao.source(sourceId) ?: error("来源已移除")
            val client = sources.asmrClient(source)
            val catalog = client.work(id)
            require(catalog.id == id) { "作品编号不匹配" }
            val official =
                metadataClient
                    .work(catalog.rj)
                    .withPrevious(library.readDlsiteDetails(catalog.workId))
            val work = official.applyTo(catalog)
            AsmrPreview(
                sourceId,
                catalog.artist,
                work,
                client.index(work, sourceId),
                official.details,
            )
        }

    suspend fun addAsmrPreview(preview: AsmrPreview): String =
        withContext(Dispatchers.IO) {
            library.withWorkLock(preview.work.workId) { commitAsmrPreview(preview) }
        }

    suspend fun importAsmrWork(
        sourceId: String,
        id: Long,
        metadataClient: DlsiteClient = sources.dlsite,
        existingOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            val workId = stableId("asmr.one|work|$id")
            library.withWorkLock(workId) {
                val source = library.dao.source(sourceId) ?: error("来源已移除")
                if (existingOnly && library.dao.work(workId) == null) return@withWorkLock workId
                require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
                commitAsmrPreview(previewAsmrWork(sourceId, id, metadataClient))
            }
        }

    private suspend fun commitAsmrPreview(preview: AsmrPreview): String {
        val sourceId = preview.sourceId
        val work = preview.work
        val indexed = preview.indexed
        val generation = System.currentTimeMillis()
        val source = library.dao.source(sourceId) ?: error("来源已移除")
        val client = sources.asmrClient(source)
        val previous = library.dao.work(work.workId)
        if (previous?.offlineAttachments == true) {
            work.coverId?.let { cover ->
                val target = File(library.offlineDir, cover)
                if (!target.isFile)
                    atomicFile(target, client.bytes(work.coverUrl!!, 16 * 1024 * 1024))
            }
        }
        currentCoroutineContext().ensureActive()
        var obsoleteTracks = emptyList<String>()
        library.database.withTransaction {
            val latestSource = library.dao.source(sourceId) ?: error("来源已移除")
            require(latestSource.kindType == SourceKind.ASMRONE) { "来源已移除" }
            obsoleteTracks =
                mergeWorkInTransaction(
                    freshWork = indexed.works.single(),
                    tracks = indexed.tracks,
                    attachments = indexed.attachments,
                    catalogArtistFallback = preview.catalogArtist,
                    metadata = preview.metadata,
                )
            library.dao.putSource(latestSource.copy(lastScan = generation, error = null))
        }
        removeObsoleteDownloads(obsoleteTracks)
        library.storageChanged()
        return work.workId
    }

    suspend fun updateDlsiteMetadata(id: String, client: DlsiteClient = sources.dlsite) =
        withContext(Dispatchers.IO) {
            library.withWorkLock(id) {
                val old = library.dao.work(id) ?: error("作品已删除")
                val metadata =
                    client
                        .work(old.rj ?: error("作品没有 RJ 编号"))
                        .withPrevious(library.readDlsiteDetails(id))
                val coverId = stableId("$id|${metadata.coverPath}")
                val coverUrl = metadata.coverUrl.toHttpUrlOrNull() ?: error("封面地址无效")
                val bytes = sources.fetchDlsiteBytes(coverUrl.toString())
                images.putCachedBytes(coverId, bytes)
                if (old.offlineAttachments) atomicFile(File(library.offlineDir, coverId), bytes)
                library.database.withTransaction {
                    val latest = library.dao.work(id) ?: error("作品已删除")
                    val keepCustom =
                        latest.coverId?.let { File(library.customArtworkDir, it).isFile } == true
                    library.dao.putWorks(
                        listOf(
                            latest.copy(
                                title = metadata.title,
                                originalTitle = metadata.title,
                                artist = metadata.artist,
                                originalArtist = metadata.artist,
                                series = metadata.series,
                                coverUrl = metadata.coverUrl,
                                coverId = if (keepCustom) latest.coverId else coverId,
                            ),
                        ),
                    )
                    val previous = decodeDlsiteDetails(library.dao.dlsiteMetadata(id)?.snapshot)
                    library.dao.putDlsiteMetadata(
                        DlsiteMetadataEntity(id, metadata.details.preserving(previous).encode()),
                    )
                }
                library.storageChanged()
            }
        }

    suspend fun removeOnlineWork(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("attachments-$id")
        library.withWorkLock(id) {
            withContext(Dispatchers.IO + NonCancellable) {
                val detail = library.dao.details(id) ?: return@withContext
                require(detail.work.isRemovableOnline) { "作品类型不支持此操作" }
                val trackIds = detail.tracks.map { it.id }.toSet()
                withContext(Dispatchers.Main.immediate) { detachTracks(trackIds) }
                library.flushPlaybackProgress()
                val assets =
                    (trackIds +
                            detail.attachments.map { it.id } +
                            listOfNotNull(detail.work.coverId))
                        .toSet()
                // Room ForeignKey CASCADE cleans tracks, attachments, progress, bookmarks,
                // dlsite_metadata.
                library.dao.deleteWork(id)
                detail.tracks.forEach {
                    offline.removeTrackDownload(it.id)
                    media.streamingCache.removeResource(it.id)
                }
                images.purgeWorkAssets(id, assets)
                library.storageChanged()
            }
        }
    }

    suspend fun removeSource(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("scan-$id")
        val affectedWorks = library.dao.sourceWorkIds(id)
        val tracksToDetach = mutableSetOf<String>()
        for (workId in affectedWorks) {
            WorkManager.getInstance(context).cancelUniqueWork("attachments-$workId")
            val detail = library.dao.details(workId) ?: continue
            for (track in detail.tracks) {
                media.streamingCache.removeResource(track.id)
                if (!media.isCompleted(track.id)) {
                    offline.removeTrackDownload(track.id)
                    tracksToDetach += track.id
                }
            }
        }
        if (tracksToDetach.isNotEmpty()) {
            withContext(Dispatchers.Main.immediate) { detachTracks(tracksToDetach) }
        }
        val purgedWorkAssets = mutableListOf<Pair<String, Set<String>>>()
        sources.dlsiteCookieWrites.withLock {
            sources.dlsiteClients.remove(id)?.close()
            sources.secrets.evict(id)
            library.database.withTransaction {
                for (workId in affectedWorks) {
                    val detail = library.dao.details(workId) ?: continue
                    val hasPinnedAudio =
                        detail.tracks.any {
                            media.isCompleted(it.id) || library.offlineFile(it.id) != null
                        }
                    if (!hasPinnedAudio && !detail.work.offlineAttachments) {
                        val assets =
                            (detail.tracks.map { it.id } +
                                    detail.attachments.map { it.id } +
                                    listOfNotNull(detail.work.coverId))
                                .toSet()
                        library.dao.deleteWork(workId)
                        purgedWorkAssets += workId to assets
                    }
                }
                // ForeignKey SET_NULL automatically detaches surviving offline works; CASCADE drops
                // purchases.
                library.dao.deleteSource(id)
            }
        }
        for ((workId, assets) in purgedWorkAssets) {
            images.purgeWorkAssets(workId, assets)
        }
        if (purgedWorkAssets.isNotEmpty()) library.storageChanged()
    }

    /** Single unified merge helper shared by local/NAS scans and ASMR.ONE/DLsite imports. */
    private suspend fun mergeWorkInTransaction(
        freshWork: WorkEntity,
        tracks: List<TrackEntity>,
        attachments: List<AttachmentEntity>,
        preserveAnyLocalTitle: Boolean = false,
        catalogArtistFallback: String = "",
        metadata: DlsiteDetails? = null,
    ): List<String> {
        val old = library.dao.details(freshWork.id)
        val attachmentIds = attachments.map { it.id }.toSet()
        val mergedWork =
            old?.work?.let { prev ->
                val hasCustomCover =
                    prev.coverId?.let { File(library.customArtworkDir, it).isFile } == true
                val keptCover =
                    prev.coverId?.takeIf { cover ->
                        hasCustomCover ||
                            (preserveAnyLocalTitle &&
                                (prev.coverUrl != null ||
                                    cover in attachmentIds ||
                                    library.offlineFile(cover) != null))
                    } ?: freshWork.coverId
                freshWork.copy(
                    title =
                        if (preserveAnyLocalTitle || prev.title != prev.originalTitle) prev.title
                        else freshWork.title,
                    originalTitle =
                        if (preserveAnyLocalTitle && prev.coverUrl != null) prev.originalTitle
                        else freshWork.originalTitle,
                    artist =
                        if (
                            preserveAnyLocalTitle ||
                                prev.artist != prev.originalArtist.ifBlank { catalogArtistFallback }
                        )
                            prev.artist
                        else freshWork.artist,
                    originalArtist =
                        if (preserveAnyLocalTitle) prev.originalArtist
                        else freshWork.originalArtist,
                    series =
                        if (preserveAnyLocalTitle && prev.coverUrl != null) prev.series
                        else freshWork.series,
                    favorite = prev.favorite,
                    coverId = keptCover,
                    coverUrl =
                        if (preserveAnyLocalTitle) prev.coverUrl ?: freshWork.coverUrl
                        else freshWork.coverUrl,
                    addedAt = prev.addedAt,
                    offlineAttachments = prev.offlineAttachments,
                )
            } ?: freshWork
        val retainedWork =
            mergedWork.copy(
                offlineAttachments =
                    mergedWork.offlineAttachments &&
                        library.offlineFilesPresent(mergedWork, attachments),
            )
        library.dao.putWorks(listOf(retainedWork))
        val oldTracks = old?.tracks.orEmpty().associateBy { it.id }
        val pinnedTracks =
            oldTracks.values
                .filter { media.isCompleted(it.id) || library.offlineFile(it.id) != null }
                .map { it.id }
        val keepTracks = (tracks.map { it.id } + pinnedTracks).toSet()
        val obsoleteTracks = oldTracks.keys.filter { it !in keepTracks }
        obsoleteTracks.chunked(500).forEach { library.dao.deleteTracks(it) }
        library.dao.putTracks(
            tracks.map { track ->
                track.copy(
                    durationMs =
                        track.durationMs.takeIf { it > 0 } ?: oldTracks[track.id]?.durationMs ?: 0,
                )
            },
        )
        val pinnedAttachments =
            old?.attachments.orEmpty().filter { library.offlineFile(it.id) != null }.map { it.id }
        val keepAttachments = (attachments.map { it.id } + pinnedAttachments).toSet()
        val staleAttachments =
            library.dao.attachmentIdsForWork(freshWork.id).filter { it !in keepAttachments }
        staleAttachments.chunked(500).forEach { library.dao.deleteAttachments(it) }
        library.dao.putAttachments(attachments)
        metadata?.let { fresh ->
            val previous = decodeDlsiteDetails(library.dao.dlsiteMetadata(freshWork.id)?.snapshot)
            library.dao.putDlsiteMetadata(
                DlsiteMetadataEntity(freshWork.id, fresh.preserving(previous).encode()),
            )
        }
        return obsoleteTracks
    }

    private fun removeObsoleteDownloads(ids: List<String>) {
        for (id in ids) {
            if (media.downloadManager.downloadIndex.getDownload(id) != null)
                offline.removeTrackDownload(id)
            media.streamingCache.removeResource(id)
        }
    }
}
