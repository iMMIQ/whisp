// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Per-work mutex registry so slow operations on one work never stall unrelated works. */
internal class WorkLocks {
    private class Entry {
        val mutex = Mutex()
        var waiters = 0
    }

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(workId: String, block: suspend () -> T): T {
        val entry =
            synchronized(entries) {
                entries.getOrPut(workId) { Entry() }.also { it.waiters++ }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) {
                entry.waiters--
                if (entry.waiters == 0 && entries[workId] === entry) entries.remove(workId)
            }
        }
    }
}

/**
 * Owns Room queries/mutations, per-work locking, progress persistence, source registration, and
 * asset resolution/reading.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LibraryStore(
    val context: Context,
    val scope: CoroutineScope,
    val database: LibraryDatabase,
    val sources: SourceRegistry,
    val media: MediaSourceRouting,
    val offlineDir: File,
    val customArtworkDir: File,
    val storageChanged: () -> Unit,
) {
    val dao: LibraryDao = database.library()
    private val workLocks = WorkLocks()

    val sourceList: StateFlow<List<SourceEntity>> by lazy {
        dao.observeSources().stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    val works: StateFlow<List<WorkEntity>> by lazy {
        combine(
                dao.observeWorks(),
                dao.observeOrphanTrackWorkPairs(),
                media.completedDownloads,
            ) { allWorks, orphanTrackPairs, completed ->
                val offlineWorkIds =
                    if (orphanTrackPairs.isEmpty() || completed.isEmpty()) emptySet()
                    else orphanTrackPairs.filter { it.id in completed }.map { it.workId }.toSet()
                allWorks.filter { work ->
                    work.sourceId != null || work.offlineAttachments || work.id in offlineWorkIds
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    val progress: StateFlow<List<ProgressEntity>> by lazy {
        dao.observeProgress().stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    val workProgressSummaries: StateFlow<Map<String, WorkProgressSummary>> by lazy {
        dao.observeWorkProgressSummaries()
            .map { list -> list.associateBy { it.workId } }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyMap())
    }

    val trackStates: StateFlow<Map<String, TrackStateEntity>> by lazy {
        dao.observeTrackStates()
            .map { list -> list.associateBy { it.trackId } }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    val bookmarks: StateFlow<List<BookmarkEntity>> by lazy {
        dao.observeBookmarks().stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    private val progressWriter =
        ProgressWriter(
            scope,
            {
                dao.saveProgressIfValid(it.workId, it.trackId, it.positionMs, it.updatedAt)
            },
            { Log.w("WhispProgress", "Progress write deferred: ${it.javaClass.simpleName}") },
        )

    suspend fun <T> withWorkLock(workId: String, block: suspend () -> T): T =
        workLocks.withLock(workId, block)

    fun offlineFile(id: String): File? = File(offlineDir, id).takeIf { it.isFile }

    fun offlineFilesPresent(work: WorkEntity, attachments: List<AttachmentEntity>): Boolean =
        attachments.all { offlineFile(it.id) != null } &&
            (work.coverId == null || offlineFile(work.coverId) != null)

    fun savePlaybackProgress(entity: ProgressEntity) = progressWriter.save(entity)

    fun savePlaybackProgressImmediate(entity: ProgressEntity) = progressWriter.saveImmediate(entity)

    suspend fun flushPlaybackProgress() = progressWriter.flush()

    fun observeDetails(workId: String): Flow<WorkDetails?> = dao.observeDetails(workId)

    fun observeLatestProgress(): Flow<ProgressEntity?> = dao.observeLatestProgress()

    fun observeProgress(workId: String): Flow<ProgressEntity?> = dao.observeProgressForWork(workId)

    fun observeBookmarks(workId: String): Flow<List<BookmarkEntity>> =
        dao.observeBookmarksForWork(workId)

    suspend fun workDetails(id: String): WorkDetails? = dao.details(id)

    suspend fun track(id: String): TrackEntity? = dao.track(id)

    suspend fun progress(workId: String): ProgressEntity? = dao.progress(workId)

    suspend fun latestDetails(): WorkDetails? {
        val latest = dao.latestProgress() ?: return null
        return dao.details(latest.workId)
    }

    suspend fun searchWorkIds(query: String): Set<String> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptySet()
            val ftsQuery = buildFtsQueryOrNull(trimmed)
            if (ftsQuery != null) {
                runCatching { dao.searchWorkIdsFts(ftsQuery).toSet() }
                    .getOrElse { dao.searchWorkIds("%$trimmed%").toSet() }
            } else {
                dao.searchWorkIds("%$trimmed%").toSet()
            }
        }

    fun subtitleOffsetFor(trackId: String?): Long =
        trackId?.let { trackStates.value[it]?.subtitleOffsetMs } ?: 0L

    fun subtitleSelectionFor(trackId: String?): TrackSubtitleSelection? = trackId?.let {
        trackStates.value[it]?.subtitleSelectionOrNull()
    }

    fun savedTrackPosition(trackId: String?): Long =
        trackId?.let { trackStates.value[it]?.positionMs } ?: 0L

    fun observeTrackState(trackId: String): Flow<TrackStateEntity?> = dao.observeTrackState(trackId)

    suspend fun setTrackSubtitleOffset(trackId: String, offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-10_000L, 10_000L)
        dao.upsertTrackSubtitleOffset(trackId, clamped, System.currentTimeMillis())
    }

    suspend fun setTrackSubtitleSelection(trackId: String, selection: TrackSubtitleSelection?) {
        val primary = selection?.primaryId
        val secondary = selection?.secondaryId?.takeIf { it != primary }
        dao.upsertTrackSubtitleSelection(trackId, primary, secondary, System.currentTimeMillis())
    }

    suspend fun rememberTrackPosition(trackId: String, positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        dao.upsertTrackPosition(trackId, clamped, System.currentTimeMillis())
    }

    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id)

    suspend fun editWorkIdentity(id: String, title: String, artist: String) =
        dao.updateWorkIdentity(id, title, artist)

    suspend fun saveBookmark(bookmark: BookmarkEntity) = dao.saveBookmark(bookmark)

    suspend fun deleteBookmark(id: String) = dao.deleteBookmark(id)

    fun dlsiteDetails(id: String): Flow<DlsiteDetails?> =
        dao.observeDlsiteMetadata(id)
            .map { decodeDlsiteDetails(it?.snapshot) }
            .flowOn(Dispatchers.Default)

    suspend fun readDlsiteDetails(id: String): DlsiteDetails? =
        withContext(Dispatchers.Default) { decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot) }

    suspend fun saveDlsiteDetails(id: String, details: DlsiteDetails) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val work = dao.work(id)
                if (work != null && work.rj == details.rj) {
                    val previous = decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot)
                    dao.putDlsiteMetadata(
                        DlsiteMetadataEntity(id, details.preserving(previous).encode()),
                    )
                }
            }
        }

    fun dlsitePurchases(sourceId: String): Flow<List<DlsiteOwnedWork>> {
        var previousDecoded = emptyMap<String, Pair<String, DlsiteOwnedWork>>()
        return dao.observeDlsitePurchases(sourceId)
            .map { rows ->
                val nextDecoded = HashMap<String, Pair<String, DlsiteOwnedWork>>(rows.size)
                val list = rows.mapNotNull { row ->
                    val cached = previousDecoded[row.workno]
                    val decoded =
                        if (cached != null && cached.first == row.snapshot) cached.second
                        else decodeOwnedWork(row.snapshot)
                    if (decoded != null) {
                        nextDecoded[row.workno] = row.snapshot to decoded
                    }
                    decoded
                }
                previousDecoded = nextDecoded
                list
            }
            .flowOn(Dispatchers.Default)
    }

    suspend fun availableTracks(work: WorkEntity, tracks: List<TrackEntity>): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            val sourceOnline = work.sourceId != null && dao.source(work.sourceId) != null
            if (sourceOnline) tracks
            else tracks.filter { offlineFile(it.id) != null || media.isCompleted(it.id) }
        }

    suspend fun resolveTrackUri(track: TrackEntity, work: WorkEntity): String {
        offlineFile(track.id)?.let {
            return Uri.fromFile(it).toString()
        }
        media.completedUri(track.id)?.let {
            return it
        }
        return sources.routeUri(work.sourceId, track.id, track.uri)
    }

    /**
     * Batch-resolves track URIs for a queue in O(1) database/index queries instead of N+1 per-track
     * Room & DownloadIndex lookups.
     */
    suspend fun resolveTrackUris(tracks: List<TrackEntity>, work: WorkEntity): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext emptyMap()
            val completed = media.completedUrisSnapshot()
            val offlineNames = offlineDir.list()?.toHashSet().orEmpty()
            var cachedKind: SourceKind? = null
            var kindLoaded = false
            buildMap(tracks.size) {
                for (track in tracks) {
                    if (track.id in offlineNames) {
                        val file = File(offlineDir, track.id)
                        if (file.isFile) {
                            put(track.id, Uri.fromFile(file).toString())
                            continue
                        }
                    }
                    completed[track.id]?.let {
                        put(track.id, it)
                        continue
                    }
                    val sourceId = work.sourceId
                    if (sourceId == null || track.uri.isBlank()) {
                        throw IOException("文件已移动或来源已移除")
                    }
                    if (!kindLoaded) {
                        cachedKind = dao.source(sourceId)?.kindType
                        kindLoaded = true
                    }
                    val kind = cachedKind ?: throw IOException("来源已移除")
                    put(track.id, sources.routeUriForKind(kind, sourceId, track.id, track.uri))
                }
            }
        }

    suspend fun readAsset(id: String, limit: Int = 8 * 1024 * 1024): ByteArray =
        withContext(Dispatchers.IO) {
            offlineFile(id)?.let {
                return@withContext it.inputStream().use { stream -> boundedRead(stream, limit) }
            }
            File(customArtworkDir, id)
                .takeIf { it.isFile }
                ?.let {
                    return@withContext it.inputStream().use { stream -> boundedRead(stream, limit) }
                }
            dao.workByCover(id)?.coverUrl?.let { url ->
                return@withContext sources.fetchDlsiteBytes(url, limit)
            }
            val attachment = dao.attachment(id)
            if (attachment != null) {
                val work = dao.work(attachment.workId) ?: throw IOException("作品已移除")
                return@withContext sources.readRemoteBytes(work.sourceId, id, attachment.uri, limit)
            }
            val track = dao.track(id) ?: throw IOException("文件已移动或来源已移除")
            val work = dao.work(track.workId) ?: throw IOException("作品已移除")
            sources.readRemoteBytes(work.sourceId, id, track.uri, limit)
        }

    suspend fun streamAssetToSink(
        id: String,
        sink: okio.BufferedSink,
        limit: Int = 64 * 1024 * 1024,
    ): Long =
        withContext(Dispatchers.IO) {
            offlineFile(id)?.let {
                return@withContext it.inputStream().use { stream ->
                    copyBounded(stream, sink, limit)
                }
            }
            File(customArtworkDir, id)
                .takeIf { it.isFile }
                ?.let {
                    return@withContext it.inputStream().use { stream ->
                        copyBounded(stream, sink, limit)
                    }
                }
            dao.workByCover(id)?.coverUrl?.let { url ->
                return@withContext sources.streamDlsiteToSink(url, sink, limit)
            }
            val attachment = dao.attachment(id)
            if (attachment != null) {
                val work = dao.work(attachment.workId) ?: throw IOException("作品已移除")
                return@withContext sources.streamRemoteToSink(
                    work.sourceId,
                    id,
                    attachment.uri,
                    sink,
                    limit,
                )
            }
            val track = dao.track(id) ?: throw IOException("文件已移动或来源已移除")
            val work = dao.work(track.workId) ?: throw IOException("作品已移除")
            sources.streamRemoteToSink(work.sourceId, id, track.uri, sink, limit)
        }

    suspend fun addLocal(root: String, name: String): String {
        val id = stableId(root)
        dao.putSource(SourceEntity(id, name, SourceKind.LOCAL.raw, root))
        return id
    }

    suspend fun addWebDav(name: String, root: String, username: String, password: String): String {
        val url = root.trim().toHttpUrlOrNull() ?: error("请输入有效的 HTTPS 地址")
        require(url.scheme == "https" || url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
            "请使用 HTTPS 连接 NAS"
        }
        require(
            url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.query == null &&
                url.fragment == null,
        ) {
            "账号请填写在账号栏"
        }
        val normalized =
            url.newBuilder()
                .apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }
                .build()
                .toString()
        val id = stableId("$normalized|$username")
        val source =
            SourceEntity(
                id,
                name.ifBlank { url.host },
                SourceKind.WEBDAV.raw,
                normalized,
                username.trim(),
                sources.secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) {
            sources.dav.list(normalized.toHttpUrlOrNull()!!, source, password)
        }
        dao.putSource(source)
        return id
    }

    suspend fun addSmb(
        name: String,
        root: String,
        username: String,
        password: String,
        encrypt: Boolean? = null,
    ): String {
        val address = SmbAddress.parse(root, encrypt)
        val account = username.trim()
        SmbAccount.parse(account)
        val id = stableId("smb|${address.normalized}|$account")
        val source =
            SourceEntity(
                id,
                name.ifBlank { address.host },
                SourceKind.SMB.raw,
                address.normalized,
                account,
                sources.secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) { sources.smb.check(source, password) }
        dao.putSource(source)
        return id
    }

    suspend fun addNfs(
        name: String,
        root: String,
        version: Int = 4,
        uid: Int = 65534,
        gid: Int = 65534,
    ): String {
        val address = NfsAddress.parse(root, version, uid, gid)
        val id = stableId("nfs|${address.normalized}")
        val source =
            SourceEntity(id, name.ifBlank { address.host }, SourceKind.NFS.raw, address.normalized)
        withContext(Dispatchers.IO) { sources.nfs.check(source) }
        dao.putSource(source)
        return id
    }

    suspend fun addAsmrOne(): String {
        val id = stableId("asmr.one")
        if (dao.source(id) == null)
            dao.putSource(
                SourceEntity(id, "ASMR.ONE", SourceKind.ASMRONE.raw, AsmrOneClient.API_ROOT),
            )
        return id
    }
}

private val ftsAsciiWordQuery = Regex("^[A-Za-z0-9\\s_-]+$")
private val ftsTokenSplit = Regex("[\\s_-]+")

internal fun buildFtsQueryOrNull(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || !ftsAsciiWordQuery.matches(trimmed)) return null
    val tokens = ftsTokenSplit.split(trimmed).filter { it.length >= 2 }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}
