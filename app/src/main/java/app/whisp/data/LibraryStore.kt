// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.util.UUID
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

data class LibraryQueryFilter(
    val favoriteOnly: Boolean = false,
    val offlineOnly: Boolean = false,
    val series: String? = null,
    val sourceId: String? = null,
    val artist: String? = null,
    val tag: String? = null,
    val hasSubtitlesOnly: Boolean = false,
    val listenFilter: String = ListenFilter.ALL,
    val query: String = "",
)

/**
 * Owns Room queries/mutations, per-work locking, progress persistence, source registration,
 * playlists, and asset resolution/reading.
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
                orphanOfflineWorkIds,
            ) { allWorks, offlineWorkIds ->
                allWorks.filter { work ->
                    work.sourceId != null || work.offlineAttachments || work.id in offlineWorkIds
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    val orphanOfflineWorkIds: StateFlow<Set<String>> by lazy {
        combine(dao.observeOrphanTrackWorkPairs(), media.completedDownloads) {
                orphanTrackPairs,
                completed ->
                if (orphanTrackPairs.isEmpty() || completed.isEmpty()) emptySet()
                else orphanTrackPairs.filter { it.id in completed }.map { it.workId }.toSet()
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptySet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val visibleWorkCount: StateFlow<Int> by lazy {
        orphanOfflineWorkIds
            .flatMapLatest { orphanIds ->
                val list = orphanIds.take(400)
                dao.observeVisibleWorkCount(list.isNotEmpty(), list.ifEmpty { listOf("") })
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesSummaries: StateFlow<List<SeriesSummary>> by lazy {
        orphanOfflineWorkIds
            .flatMapLatest { orphanIds ->
                val list = orphanIds.take(400)
                dao.observeSeriesSummaries(list.isNotEmpty(), list.ifEmpty { listOf("") })
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    val playlists: StateFlow<List<PlaylistSummary>> by lazy {
        dao.observePlaylistSummaries()
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun pagedWorks(filter: LibraryQueryFilter, sort: String): Flow<PagingData<WorkEntity>> =
        orphanOfflineWorkIds.flatMapLatest { orphanIds ->
            val orphanList = orphanIds.take(400)
            val trimmed = filter.query.trim()
            val fts = if (trimmed.isNotEmpty()) buildFtsQueryOrNull(trimmed) else null
            val like = if (trimmed.isNotEmpty() && fts == null) "%$trimmed%" else null
            val artistLike = filter.artist?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
            val tagLike = filter.tag?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
            Pager(
                    config =
                        PagingConfig(
                            pageSize = 40,
                            initialLoadSize = 60,
                            prefetchDistance = 20,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = {
                        dao.pagingWorks(
                            hasOrphanOffline = orphanList.isNotEmpty(),
                            orphanOfflineIds = orphanList.ifEmpty { listOf("") },
                            favoriteOnly = filter.favoriteOnly,
                            offlineOnly = filter.offlineOnly,
                            series = filter.series,
                            sourceId = filter.sourceId,
                            artistFilter = artistLike,
                            tagFilter = tagLike,
                            hasSubtitlesOnly = filter.hasSubtitlesOnly,
                            listenFilter = filter.listenFilter,
                            ftsQuery = fts,
                            likePattern = like,
                            sort = sort,
                        )
                    },
                )
                .flow
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

    // Subscribed only while UI/subtitles actively observe track state rather than eagerly at cold
    // start.
    val trackStates: StateFlow<Map<String, TrackStateEntity>> by lazy {
        dao.observeTrackStates()
            .map { list -> list.associateBy { it.trackId } }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyMap())
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

    fun observeWorksByRj(rj: String): Flow<List<WorkEntity>> = dao.observeWorksByRj(rj)

    fun observeLatestProgress(): Flow<ProgressEntity?> = dao.observeLatestProgress()

    fun observeProgress(workId: String): Flow<ProgressEntity?> = dao.observeProgressForWork(workId)

    fun observeBookmarks(workId: String): Flow<List<BookmarkEntity>> =
        dao.observeBookmarksForWork(workId)

    fun observePlaylistItems(playlistId: String): Flow<List<PlaylistItemEntity>> =
        dao.observePlaylistItems(playlistId)

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

    suspend fun loadSubtitleSelection(trackId: String): TrackSubtitleSelection? =
        withContext(Dispatchers.IO) { dao.trackState(trackId)?.subtitleSelectionOrNull() }

    suspend fun loadSavedTrackPosition(trackId: String): Long =
        withContext(Dispatchers.IO) { dao.trackState(trackId)?.positionMs ?: 0L }

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

    suspend fun updateWorkTags(id: String, rawTags: String) {
        val normalized = normalizeUserTags(rawTags)
        dao.updateWorkTags(id, normalized)
    }

    suspend fun updateWorkVolumeOffset(id: String, db: Float) {
        dao.updateWorkVolumeOffset(id, db.coerceIn(-12f, 12f))
    }

    suspend fun saveBookmark(bookmark: BookmarkEntity) = dao.saveBookmark(bookmark)

    suspend fun deleteBookmark(id: String) = dao.deleteBookmark(id)

    // Playlists CRUD
    suspend fun createPlaylist(name: String): String =
        withContext(Dispatchers.IO) {
            val clean = name.trim().take(80)
            require(clean.isNotBlank()) { "请输入歌单名称" }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            dao.putPlaylist(PlaylistEntity(id = id, name = clean, createdAt = now, updatedAt = now))
            id
        }

    suspend fun renamePlaylist(id: String, name: String) =
        withContext(Dispatchers.IO) {
            val clean = name.trim().take(80)
            require(clean.isNotBlank()) { "请输入歌单名称" }
            dao.renamePlaylist(id, clean, System.currentTimeMillis())
        }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) { dao.deletePlaylist(id) }

    suspend fun addToPlaylist(
        playlistId: String,
        workId: String,
        trackId: String,
        startMs: Long? = null,
        endMs: Long? = null,
        label: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val now = System.currentTimeMillis()
                val nextPos = dao.maxPlaylistPosition(playlistId) + 1
                dao.putPlaylistItems(
                    listOf(
                        PlaylistItemEntity(
                            id = UUID.randomUUID().toString(),
                            playlistId = playlistId,
                            workId = workId,
                            trackId = trackId,
                            position = nextPos,
                            startMs = startMs,
                            endMs = endMs,
                            label = label,
                            addedAt = now,
                        ),
                    ),
                )
                dao.touchPlaylist(playlistId, now)
            }
        }

    suspend fun addTracksToPlaylist(playlistId: String, workId: String, trackIds: List<String>) =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) return@withContext
            database.withTransaction {
                val now = System.currentTimeMillis()
                var pos = dao.maxPlaylistPosition(playlistId) + 1
                val entities = trackIds.map { trackId ->
                    PlaylistItemEntity(
                        id = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        workId = workId,
                        trackId = trackId,
                        position = pos++,
                        addedAt = now,
                    )
                }
                dao.putPlaylistItems(entities)
                dao.touchPlaylist(playlistId, now)
            }
        }

    suspend fun removePlaylistItem(playlistId: String, itemId: String) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.deletePlaylistItem(itemId)
                dao.touchPlaylist(playlistId, System.currentTimeMillis())
            }
        }

    suspend fun movePlaylistItem(playlistId: String, itemId: String, delta: Int) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val items = dao.playlistItems(playlistId).toMutableList()
                val from = items.indexOfFirst { it.id == itemId }
                if (from < 0) return@withTransaction
                val to = (from + delta).coerceIn(0, items.lastIndex)
                if (from == to) return@withTransaction
                val moved = items.removeAt(from)
                items.add(to, moved)
                val now = System.currentTimeMillis()
                dao.putPlaylistItems(items.mapIndexed { idx, item -> item.copy(position = idx) })
                dao.touchPlaylist(playlistId, now)
            }
        }

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
                if (work != null && work.rj.equals(details.rj, ignoreCase = true)) {
                    val previous = decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot)
                    val merged = details.preserving(previous)
                    dao.putDlsiteMetadata(DlsiteMetadataEntity(id, merged.encode()))
                    val meta = buildSearchMetadata(merged)
                    if (meta != work.searchMetadata) {
                        dao.updateWorkSearchMetadata(id, meta)
                    }
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

    suspend fun addAsmrOne(
        name: String = "ASMR.ONE",
        root: String = AsmrOneClient.API_ROOT,
        token: String = "",
    ): String {
        val url = root.trim().toHttpUrlOrNull() ?: error("请输入有效的 Kikoeru / ASMR.ONE 地址")
        val isLocal = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(url.scheme == "https" || isLocal) { "在线目录地址须使用 HTTPS" }
        require(
            url.username.isEmpty() &&
                url.password.isEmpty() &&
                url.query == null &&
                url.fragment == null,
        ) {
            "地址不能包含凭据或查询参数"
        }
        val normalized =
            url.newBuilder()
                .apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }
                .build()
                .toString()
        val isDefault = normalized == AsmrOneClient.API_ROOT && token.isBlank()
        val id = if (isDefault) stableId("asmr.one") else stableId("asmr.one|$normalized")
        val encrypted = if (token.isNotBlank()) sources.secrets.encrypt(token.trim()) else ""
        val existing = dao.source(id)
        if (existing == null || existing.root != normalized || existing.secret != encrypted) {
            dao.putSource(
                SourceEntity(
                    id = id,
                    name = name.trim().ifBlank { url.host },
                    kind = SourceKind.ASMRONE.raw,
                    root = normalized,
                    secret = encrypted,
                ),
            )
        }
        return id
    }

    suspend fun updateAsmrOneConfig(
        sourceId: String,
        name: String,
        root: String,
        token: String? = null,
    ) {
        val existing = dao.source(sourceId) ?: error("来源已移除")
        require(existing.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
        val url = root.trim().toHttpUrlOrNull() ?: error("请输入有效的 HTTPS 地址")
        val isLocal = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        require(url.scheme == "https" || isLocal) { "在线目录地址须使用 HTTPS" }
        val normalized =
            url.newBuilder()
                .apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }
                .build()
                .toString()
        val nextSecret =
            when {
                token == null -> existing.secret
                token.isBlank() -> ""
                else -> sources.secrets.encrypt(token.trim())
            }
        dao.putSource(
            existing.copy(
                name = name.trim().ifBlank { url.host },
                root = normalized,
                secret = nextSecret,
                error = null,
            ),
        )
        sources.secrets.evict(sourceId)
    }
}

internal fun buildSearchMetadata(details: DlsiteDetails?): String {
    if (details == null) return ""
    val parts = buildList {
        details.circle?.takeIf(String::isNotBlank)?.let(::add)
        details.series?.takeIf(String::isNotBlank)?.let(::add)
        details.credits.forEach { if (it.name.isNotBlank()) add(it.name) }
        details.genres.forEach { if (it.name.isNotBlank()) add(it.name) }
    }
    return parts.distinct().joinToString(" ")
}

internal fun normalizeUserTags(raw: String): String =
    raw.split(',', '，', '、', '\n', '#')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(24)
        .joinToString(", ")

internal fun parseUserTags(raw: String): List<String> =
    if (raw.isBlank()) emptyList()
    else raw.split(',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private val ftsAsciiWordQuery = Regex("^[A-Za-z0-9\\s_-]+$")
private val ftsTokenSplit = Regex("[\\s_-]+")

internal fun buildFtsQueryOrNull(query: String): String? {
    val trimmed = query.trim()
    if (trimmed.isEmpty() || !ftsAsciiWordQuery.matches(trimmed)) return null
    val tokens = ftsTokenSplit.split(trimmed).filter { it.length >= 2 }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}
