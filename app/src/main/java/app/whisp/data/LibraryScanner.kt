// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.room.withTransaction
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Coordinates source scanning (Local/SMB/NFS/WebDAV/ASMR.ONE/DLsite), DLsite catalog enrichment
 * with failure backoff and batched DB writes, and multi-format remote duration indexing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LibraryScanner(private val repository: LibraryRepository) {
    companion object {
        // Overlap incremental DLsite purchase queries by 5 minutes to protect against clock skew.
        private const val DLSITE_SYNC_OVERLAP_MS = 5 * 60_000L
        // Back off failed DLsite metadata enrichment lookups for 24 hours per work.
        private const val DLSITE_ENRICH_BACKOFF_MS = 24 * 60 * 60 * 1000L
    }

    val busy = MutableStateFlow<Set<String>>(emptySet())
    private val scanOwners = ConcurrentHashMap<String, Any>()

    suspend fun syncDlsitePurchases(
        sourceId: String,
        full: Boolean,
        client: DlsiteAccountClient,
    ) {
        val source =
            repository.dlsiteCookieWrites.withLock {
                repository.requireDlsiteClient(sourceId, client)
                repository.dao.source(sourceId) ?: throw CancellationException("来源已移除")
            }
        val querySince =
            if (full || source.lastScan <= 0L) 0L
            else (source.lastScan - DLSITE_SYNC_OVERLAP_MS).coerceAtLeast(0L)
        val synced =
            try {
                client.purchases(querySince)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.dlsiteCookieWrites.withLock {
                    repository.requireDlsiteClient(sourceId, client)
                }
                throw e
            }
        repository.dlsiteCookieWrites.withLock {
            repository.requireDlsiteClient(sourceId, client)
            repository.database.withTransaction {
                val latest = repository.dao.source(sourceId) ?: throw CancellationException("来源已移除")
                repository.dao.putDlsitePurchases(
                    synced.works.map {
                        val old =
                            decodeOwnedWork(
                                repository.dao
                                    .dlsitePurchase(sourceId, it.workno)
                                    ?.snapshot
                                    .orEmpty(),
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
                repository.dao.putSource(latest.copy(lastScan = synced.completedAt, error = null))
            }
        }
        enrichDlsiteCatalog(sourceId, client)
    }

    private suspend fun enrichDlsiteCatalog(sourceId: String, client: DlsiteAccountClient) =
        coroutineScope {
            val now = System.currentTimeMillis()
            val pending =
                repository.dlsitePurchases(sourceId).first().filter {
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
                                            repository.dlsite.work(owned.workno)
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
                repository.dlsiteCookieWrites.withLock {
                    repository.requireDlsiteClient(sourceId, client)
                    repository.database.withTransaction {
                        if (repository.dao.source(sourceId) == null) return@withTransaction
                        val updates = mutableListOf<DlsitePurchaseEntity>()
                        for ((workno, metadata, attemptTime) in results) {
                            val current =
                                decodeOwnedWork(
                                    repository.dao
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
                        if (updates.isNotEmpty()) repository.dao.putDlsitePurchases(updates)
                    }
                }
            }
        }

    suspend fun scan(id: String) {
        val source = repository.dao.source(id) ?: return
        val owner = Any()
        synchronized(scanOwners) {
            scanOwners[id] = owner
            busy.update { it + id }
        }
        var dlsiteClient: DlsiteAccountClient? = null
        val startTime = android.os.SystemClock.elapsedRealtime()
        try {
            Log.i("WhispScan", "Started ${source.kind} scan")
            if (source.kindType == SourceKind.DLSITE) {
                val client = repository.dlsiteAccount(source.id)
                dlsiteClient = client
                syncDlsitePurchases(source.id, false, client)
                return
            }
            if (source.kindType == SourceKind.ASMRONE) {
                for (workId in repository.dao.sourceWorkIds(source.id)) {
                    currentCoroutineContext().ensureActive()
                    val asmrId =
                        repository.dao.work(workId)?.rj?.removePrefix("RJ")?.toLongOrNull()
                            ?: continue
                    repository.importAsmrWork(source.id, asmrId, existingOnly = true)
                }
                repository.database.withTransaction {
                    repository.dao.source(source.id)?.let {
                        repository.dao.putSource(
                            it.copy(lastScan = System.currentTimeMillis(), error = null),
                        )
                    }
                }
                return
            }
            val entries =
                when (source.kindType) {
                    SourceKind.LOCAL -> LocalDirectory(repository.context).walk(source.root)
                    SourceKind.SMB -> repository.smb.walk(source, repository.sourceSecret(source))
                    SourceKind.NFS -> repository.nfs.walk(source)
                    SourceKind.WEBDAV ->
                        repository.dav.walk(source, repository.sourceSecret(source))
                    else -> error("不支持的来源类型")
                }
            val generation = System.currentTimeMillis()
            var indexed = LibraryIndexer.index(entries, id, generation)
            Log.i(
                "WhispScan",
                "Listed ${entries.size} files; indexed ${indexed.works.size} works, ${indexed.tracks.size} tracks",
            )
            require(
                indexed.tracks.isNotEmpty() ||
                    source.lastScan > 0 ||
                    repository.dao.sourceWorkIds(id).isNotEmpty(),
            ) {
                "目录中未找到音频，请检查来源目录"
            }
            currentCoroutineContext().ensureActive()
            var savedTracks: List<TrackEntity> = emptyList()
            var obsoleteTracks = emptyList<String>()
            repository.database.withTransaction {
                val latestSource = repository.dao.source(id) ?: throw IOException("来源已移除")
                val oldWorks = repository.dao.allWorks().associateBy { it.id }
                val oldTracks = repository.dao.allTracks().associateBy { it.id }
                val previousLocations = repository.dao.sourceLocations(id)
                val previousAssets = previousLocations.map { it.assetId }.toSet()
                val affectedWorks =
                    oldTracks.values.filter { it.id in previousAssets }.map { it.workId }
                indexed =
                    preserveScanIdentity(
                        indexed,
                        oldWorks.values.toList(),
                        oldTracks.values.toList(),
                        previousLocations,
                        id,
                    )
                val indexedAttachments = indexed.attachments.map { it.id }.toSet()
                val attachmentsByWork = indexed.attachments.groupBy { it.workId }
                repository.dao.putWorks(
                    indexed.works.map { new ->
                        oldWorks[new.id]
                            ?.let { old ->
                                new.copy(
                                    title = old.title,
                                    artist = old.artist,
                                    originalTitle =
                                        if (old.coverUrl != null) old.originalTitle
                                        else new.originalTitle,
                                    series = if (old.coverUrl != null) old.series else new.series,
                                    favorite = old.favorite,
                                    coverId =
                                        old.coverId?.takeIf { cover ->
                                            old.coverUrl != null ||
                                                cover == stableId("${old.id}|custom-cover") ||
                                                cover in indexedAttachments ||
                                                repository.offlineFile(cover) != null
                                        } ?: new.coverId,
                                    coverUrl = old.coverUrl,
                                    originalArtist = old.originalArtist,
                                    addedAt = old.addedAt,
                                    offlineAttachments = old.offlineAttachments,
                                )
                            }
                            ?.let { saved ->
                                saved.copy(
                                    offlineAttachments =
                                        saved.offlineAttachments &&
                                            repository.offlineFilesPresent(
                                                saved,
                                                attachmentsByWork[saved.id].orEmpty(),
                                            ),
                                )
                            } ?: new
                    },
                )
                savedTracks =
                    indexed.tracks.map { new ->
                        oldTracks[new.id]?.let { new.copy(durationMs = it.durationMs) } ?: new
                    }
                repository.dao.putTracks(savedTracks)
                repository.dao.putAttachments(indexed.attachments)
                repository.dao.putLocations(indexed.locations)
                repository.dao.removeStaleLocations(id, generation)
                val changedWorks = (affectedWorks + indexed.works.map { it.id }).distinct()
                obsoleteTracks =
                    changedWorks
                        .chunked(500)
                        .flatMap { repository.dao.unlocatedTracks(it) }
                        .filter {
                            repository.downloadManager.downloadIndex.getDownload(it.id)?.state !=
                                Download.STATE_COMPLETED && repository.offlineFile(it.id) == null
                        }
                        .map { it.id }
                obsoleteTracks.chunked(500).forEach { repository.dao.deleteTracks(it) }
                val obsoleteAttachments =
                    changedWorks
                        .chunked(500)
                        .flatMap { repository.dao.unlocatedAttachments(it) }
                        .filter { repository.offlineFile(it.id) == null }
                        .map { it.id }
                obsoleteAttachments.chunked(500).forEach { repository.dao.deleteAttachments(it) }
                repository.dao.putSource(latestSource.copy(lastScan = generation, error = null))
            }
            repository.removeObsoleteDownloads(obsoleteTracks)
            Log.i(
                "WhispScan",
                "Library saved in ${android.os.SystemClock.elapsedRealtime() - startTime} ms",
            )
            enrich(source, indexed, savedTracks)
            Log.i(
                "WhispScan",
                "Scan completed in ${android.os.SystemClock.elapsedRealtime() - startTime} ms",
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (scanOwners[id] !== owner)
                throw CancellationException("扫描已被替换").apply { initCause(error) }
            suspend fun reportError() {
                repository.database.withTransaction {
                    if (scanOwners[id] !== owner)
                        throw CancellationException("扫描已被替换").apply { initCause(error) }
                    repository.dao.source(id)?.let { latest ->
                        if (
                            latest.kind == source.kind &&
                                latest.root == source.root &&
                                latest.username == source.username &&
                                (dlsiteClient != null || latest.secret == source.secret)
                        ) {
                            repository.dao.updateSourceError(id, error.message ?: "来源不可用")
                            repository.messages.tryEmit(
                                "${latest.name}：${error.message ?: "扫描失败"}",
                            )
                        }
                    }
                }
            }
            if (source.kindType == SourceKind.DLSITE) {
                repository.dlsiteCookieWrites.withLock {
                    dlsiteClient?.let { repository.requireDlsiteClient(id, it) }
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

    private suspend fun enrich(
        source: SourceEntity,
        indexed: IndexedLibrary,
        tracks: List<TrackEntity>,
    ) = coroutineScope {
        data class Asset(val id: String, val fileName: String, val cover: Boolean)
        val tasks = mutableListOf<Asset>()
        val byWork = tracks.groupBy { it.workId }
        indexed.works.forEach { work ->
            work.coverId
                ?.takeUnless { repository.artwork(it) != null }
                ?.let { tasks += Asset(it, "", true) }
            byWork[work.id]
                .orEmpty()
                .filter {
                    it.durationMs == 0L &&
                        (source.kindType == SourceKind.LOCAL ||
                            AudioDurationParser.supports(it.fileName))
                }
                .forEach { tasks += Asset(it.id, it.fileName, false) }
        }
        if (tasks.isEmpty()) return@coroutineScope
        val locations = indexed.locations.associateBy { it.assetId }
        val password =
            if (source.kindType != SourceKind.LOCAL) repository.sourceSecret(source) else ""
        val lanes = minOf(4, tasks.size)
        val processed = AtomicInteger()
        val skipped = AtomicInteger()
        (0 until lanes)
            .map { lane ->
                async(Dispatchers.IO) {
                    var session: RemoteAssetSession? = null
                    val durations = mutableListOf<Pair<String, Long>>()
                    suspend fun flush() {
                        if (durations.isEmpty()) return
                        repository.database.withTransaction {
                            durations.forEach { (id, duration) ->
                                repository.dao.updateDuration(id, duration)
                            }
                        }
                        durations.clear()
                    }
                    try {
                        if (source.kindType == SourceKind.SMB)
                            session = repository.smb.assets(source, password)
                        if (source.kindType == SourceKind.NFS)
                            session = repository.nfs.assets(source)
                        tasks
                            .filterIndexed { index, _ -> index % lanes == lane }
                            .forEachIndexed { index, asset ->
                                ensureActive()
                                try {
                                    if (asset.cover) {
                                        if (session != null) {
                                            repository.artworkStore.storageReady.await()
                                            repository.artworkStore.cacheArtwork(
                                                asset.id,
                                                session.bytes(
                                                    locations.getValue(asset.id).uri,
                                                    16 * 1024 * 1024,
                                                ),
                                            )
                                            repository.artworkStore.notifyAssetChanged(asset.id)
                                        } else repository.ensureArtwork(asset.id)
                                    } else {
                                        val location = locations.getValue(asset.id)
                                        val duration =
                                            when (source.kindType) {
                                                SourceKind.SMB,
                                                SourceKind.NFS -> session!!.duration(location.uri)
                                                SourceKind.LOCAL -> localDuration(asset.id)
                                                else ->
                                                    AudioDurationParser.duration(
                                                        asset.fileName,
                                                        location.size,
                                                    ) { offset, length ->
                                                        repository.dav.range(
                                                            location.uri,
                                                            source,
                                                            password,
                                                            offset,
                                                            length,
                                                        )
                                                    }
                                            }
                                        if (duration > 0) durations += asset.id to duration
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    skipped.incrementAndGet()
                                }
                                val completed = processed.incrementAndGet()
                                if (completed % 200 == 0)
                                    Log.i(
                                        "WhispScan",
                                        "Metadata $completed/${tasks.size}; skipped ${skipped.get()}",
                                    )
                                if ((index + 1) % 50 == 0) flush()
                            }
                        flush()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Log.w(
                            "WhispScan",
                            "Metadata lane unavailable; durations can be learned during playback",
                        )
                    } finally {
                        session?.close()
                    }
                }
            }
            .awaitAll()
        Log.i(
            "WhispScan",
            "Metadata completed ${processed.get()}/${tasks.size}; skipped ${skipped.get()}",
        )
    }

    private suspend fun localDuration(id: String): Long {
        val uri = repository.resolve(id).toUri()
        if (uri.scheme !in setOf("content", "file")) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(repository.context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0
        } finally {
            retriever.release()
        }
    }
}
