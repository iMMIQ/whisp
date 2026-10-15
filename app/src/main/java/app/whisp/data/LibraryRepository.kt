// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

private val Context.preferences by preferencesDataStore("settings")

data class Settings(
    val subtitleSize: Int = 21,
    val subtitleOffsetMs: Long = 0,
    val hideArtwork: Boolean = false,
    val reduceMotion: Boolean = false,
    val streamingCacheMiB: Int = 128,
    val hideRecents: Boolean = false,
    val touchFeedback: Boolean = true,
)

@androidx.annotation.OptIn(UnstableApi::class)
class LibraryRepository(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    val dao = database.library()
    val secrets = SecretStore()
    private val secretCache =
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 32
        }

    // Keystore decryption runs on every media open and HTTP request; the key
    // carries the ciphertext so a password change never reads stale.
    private fun sourceSecret(source: SourceEntity): String =
        synchronized(secretCache) {
            secretCache.getOrPut(source.id + "|" + source.secret) {
                secrets.decrypt(source.secret)
            }
        }

    // Redirects are disabled so credentials never travel to a different server.
    val http =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val dav = WebDavClient(http)
    private val onlineHttp = http.newBuilder().followRedirects(true).build()
    internal val onlineWorkChanges = Mutex()
    val dlsite = DlsiteClient(onlineHttp)
    val dlsiteDiagnostics = DlsiteDiagnostics(context)
    val playbackDiagnostics = PlaybackDiagnostics(context)
    private val dlsiteClients =
        java.util.concurrent.ConcurrentHashMap<String, DlsiteAccountClient>()
    private val dlsiteCookieWrites = Mutex()

    private suspend fun dlsiteAccount(sourceId: String): DlsiteAccountClient =
        dlsiteCookieWrites.withLock {
            dlsiteClients[sourceId]?.let {
                return@withLock it
            }
            val source = dao.source(sourceId) ?: throw IOException("来源已移除")
            require(source.kindType == SourceKind.DLSITE) { "来源类型不匹配" }
            if (source.secret.isBlank()) throw DlsiteLoginRequired()
            lateinit var session: DlsiteSession
            session =
                DlsiteSession(sourceSecret(source)) {
                    scope.launch {
                        dlsiteCookieWrites.withLock {
                            dao.source(sourceId)
                                ?.takeIf {
                                    it.kindType == SourceKind.DLSITE &&
                                        dlsiteClients[sourceId]?.session === session
                                }
                                ?.let {
                                    dao.updateSourceSecret(it.id, secrets.encrypt(session.encode()))
                                }
                        }
                    }
                }
            val created = DlsiteAccountClient(session, http, diagnostic = dlsiteDiagnostics::record)
            dlsiteClients.putIfAbsent(sourceId, created) ?: created
        }

    suspend fun acceptDlsiteLogin(snapshot: String, expectedSource: String? = null): String {
        val session = DlsiteSession(snapshot)
        val account =
            DlsiteAccountClient(session, http, diagnostic = dlsiteDiagnostics::record).authorize()
        val id = account.id
        require(expectedSource == null || expectedSource == id) { "请登录该来源对应的账号" }
        dlsiteCookieWrites.withLock {
            val previous = dao.source(id)
            dlsiteClients.remove(id)?.let {
                it.session.clear()
                it.http.dispatcher.cancelAll()
            }
            val number = dao.sources().count { it.kindType == SourceKind.DLSITE }
            dao.putSource(
                (previous
                        ?: SourceEntity(
                            id,
                            if (number == 0) "DLsite" else "DLsite ${number + 1}",
                            SourceKind.DLSITE.raw,
                            "https://play.dlsite.com/",
                        ))
                    .copy(
                        secret = secrets.encrypt(session.encode()),
                        error = null,
                    ),
            )
        }
        scheduleScan(id, force = true)
        return id
    }

    fun dlsitePurchases(sourceId: String): Flow<List<DlsiteOwnedWork>> =
        dao.observeDlsitePurchases(sourceId)
            .map { rows -> rows.mapNotNull { decodeOwnedWork(it.snapshot) } }
            .flowOn(Dispatchers.Default)

    suspend fun syncDlsitePurchases(sourceId: String, full: Boolean = false) {
        syncDlsitePurchases(sourceId, full, dlsiteAccount(sourceId))
    }

    private fun requireDlsiteClient(sourceId: String, client: DlsiteAccountClient) {
        if (dlsiteClients[sourceId] !== client) throw CancellationException("DLsite 会话已更新")
    }

    private suspend fun syncDlsitePurchases(
        sourceId: String,
        full: Boolean,
        client: DlsiteAccountClient,
    ) {
        val source = dlsiteCookieWrites.withLock {
            requireDlsiteClient(sourceId, client)
            dao.source(sourceId) ?: throw CancellationException("来源已移除")
        }
        val synced =
            try {
                client.purchases(if (full) 0 else source.lastScan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dlsiteCookieWrites.withLock { requireDlsiteClient(sourceId, client) }
                throw e
            }
        dlsiteCookieWrites.withLock {
            requireDlsiteClient(sourceId, client)
            database.withTransaction {
                val latest = dao.source(sourceId) ?: throw CancellationException("来源已移除")
                dao.putDlsitePurchases(
                    synced.works.map {
                        val old =
                            decodeOwnedWork(
                                dao.dlsitePurchase(sourceId, it.workno)?.snapshot.orEmpty(),
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
                            )
                        DlsitePurchaseEntity(
                            sourceId,
                            merged.workno,
                            merged.purchasedAt,
                            merged.encode(),
                        )
                    },
                )
                dao.putSource(latest.copy(lastScan = synced.completedAt, error = null))
            }
        }
        enrichDlsiteCatalog(sourceId, client)
    }

    private suspend fun enrichDlsiteCatalog(sourceId: String, client: DlsiteAccountClient) =
        coroutineScope {
            val pending = dlsitePurchases(sourceId).first().filter { !it.metadataReady }
            val lanes = Semaphore(2)
            pending
                .map { owned ->
                    async {
                        lanes.withPermit {
                            val metadata =
                                try {
                                    dlsite.work(owned.workno)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    return@withPermit
                                }
                            val voices =
                                metadata.details.credits
                                    .filter { it.role == "voice_by" }
                                    .joinToString(" / ") { it.name }
                            dlsiteCookieWrites.withLock {
                                requireDlsiteClient(sourceId, client)
                                database.withTransaction {
                                    if (dao.source(sourceId) == null) return@withTransaction
                                    val current =
                                        decodeOwnedWork(
                                            dao.dlsitePurchase(sourceId, owned.workno)
                                                ?.snapshot
                                                .orEmpty(),
                                        ) ?: return@withTransaction
                                    val enriched =
                                        current.copy(
                                            title = metadata.title,
                                            artist = voices.ifBlank { current.artist },
                                            circle = metadata.details.circle ?: current.circle,
                                            coverUrl = metadata.coverUrl,
                                            metadataReady = metadata.details.completeProduct,
                                        )
                                    dao.putDlsitePurchases(
                                        listOf(
                                            DlsitePurchaseEntity(
                                                sourceId,
                                                enriched.workno,
                                                enriched.purchasedAt,
                                                enriched.encode(),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                .awaitAll()
        }

    suspend fun importDlsiteWork(
        sourceId: String,
        workno: String,
        refresh: Boolean = false,
    ): WorkDetails =
        withContext(Dispatchers.IO) {
            onlineWorkChanges.withLock {
                val cached =
                    decodeOwnedWork(dao.dlsitePurchase(sourceId, workno)?.snapshot.orEmpty())
                val client = dlsiteAccount(sourceId)
                val work = cached ?: client.work(workno)
                val workId = work.entity(sourceId).id
                if (!refresh)
                    dao.details(workId)
                        ?.takeIf { it.tracks.isNotEmpty() && workId in dao.sourceWorkIds(sourceId) }
                        ?.let {
                            return@withLock it
                        }
                val indexed = client.index(work, sourceId, System.currentTimeMillis())
                val metadata =
                    try {
                        dlsite.work(workno).withPrevious(readDlsiteDetails(workId))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                var obsoleteTracks = emptyList<String>()
                database.withTransaction {
                    require(dao.source(sourceId)?.kindType == SourceKind.DLSITE) { "来源已移除" }
                    val old = dao.details(workId)
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
                    val saved =
                        old?.work?.let {
                            fresh.copy(
                                title = if (it.title != it.originalTitle) it.title else fresh.title,
                                artist =
                                    if (it.artist != it.originalArtist) it.artist else fresh.artist,
                                favorite = it.favorite,
                                coverId =
                                    it.coverId?.takeIf { cover ->
                                        cover == stableId("${it.id}|custom-cover")
                                    } ?: fresh.coverId,
                                addedAt = it.addedAt,
                                offlineAttachments = it.offlineAttachments,
                            )
                        } ?: fresh
                    val retained =
                        saved.copy(
                            offlineAttachments =
                                saved.offlineAttachments &&
                                    offlineFilesPresent(saved, indexed.attachments),
                        )
                    dao.putWorks(listOf(retained))
                    dao.putDlsitePurchases(
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
                                    )
                                    .encode(),
                            ),
                        ),
                    )
                    dao.deleteWorkSourceLocations(workId, sourceId)
                    val pinned =
                        old?.tracks
                            .orEmpty()
                            .filter {
                                downloadManager.downloadIndex.getDownload(it.id)?.state ==
                                    Download.STATE_COMPLETED
                            }
                            .map { it.id }
                    dao.deleteStaleTracks(workId, indexed.tracks.map { it.id } + pinned)
                    val oldTracks = old?.tracks.orEmpty().associateBy { it.id }
                    obsoleteTracks =
                        (oldTracks.keys - (indexed.tracks.map { it.id } + pinned).toSet()).toList()
                    dao.putTracks(
                        indexed.tracks.map { track ->
                            track.copy(
                                durationMs =
                                    track.durationMs.takeIf { it > 0 }
                                        ?: oldTracks[track.id]?.durationMs
                                        ?: 0,
                            )
                        },
                    )
                    val pinnedAttachments =
                        old?.attachments
                            .orEmpty()
                            .filter { offlineFile(it.id) != null }
                            .map { it.id }
                    dao.deleteStaleAttachments(
                        workId,
                        indexed.attachments.map { it.id } + pinnedAttachments,
                    )
                    dao.putAttachments(indexed.attachments)
                    dao.putLocations(indexed.locations)
                    metadata?.details?.let {
                        dao.putDlsiteMetadata(
                            DlsiteMetadataEntity(
                                workId,
                                it.preserving(readDlsiteDetails(workId)).encode(),
                            ),
                        )
                    }
                }
                removeObsoleteDownloads(obsoleteTracks)
                storageChanged()
                dao.details(workId) ?: error("作品保存失败")
            }
        }

    private suspend fun dlsiteResource(sourceId: String, assetId: String): DlsiteMediaResource {
        val location =
            dao.locations(assetId).firstOrNull { it.sourceId == sourceId }
                ?: throw IOException("文件已移除")
        val uri = location.uri.toUri()
        require(uri.scheme == "dlsite-resource") { "DLsite 文件标识无效" }
        return DlsiteMediaResource(
            dlsiteAccount(sourceId),
            uri.authority ?: error("作品编号无效"),
            uri.path.orEmpty().removePrefix("/"),
        )
    }

    private fun asmrClient(source: SourceEntity): AsmrOneClient {
        require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
        return AsmrOneClient(onlineHttp, source.root.toHttpUrlOrNull() ?: error("来源地址无效"))
    }

    val smb = SmbClient { Log.i("WhispScan", it) }
    val nfs = NfsClient(report = { Log.i("WhispScan", it) })
    private val completedDownloads = MutableStateFlow<Set<String>>(emptySet())
    val library =
        combine(dao.observeLibrary(), dao.observeLocatedWorks(), completedDownloads) {
                works,
                located,
                completed ->
                val online = located.toSet()
                works.filter { detail ->
                    detail.work.offlineAttachments ||
                        detail.work.id in online ||
                        detail.tracks.any { it.id in completed }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sources = dao.observeSources().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val progress = dao.observeProgress().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val storageChanges = MutableStateFlow(0L)

    internal fun storageChanged() {
        storageChanges.update { it + 1 }
    }

    private val progressWriter =
        ProgressWriter(
            scope,
            {
                database.withTransaction {
                    if (dao.work(it.workId) != null && dao.track(it.trackId)?.workId == it.workId)
                        dao.saveProgress(it)
                }
                storageChanged()
            },
            { Log.w("WhispProgress", "Progress write deferred: ${it.javaClass.simpleName}") },
        )

    fun savePlaybackProgress(progress: ProgressEntity) {
        progressWriter.save(progress)
    }

    suspend fun flushPlaybackProgress() {
        progressWriter.flush()
    }

    // UI-facing reads and writes; screens go through these instead of the DAO.
    suspend fun track(id: String): TrackEntity? = dao.track(id)

    suspend fun workDetails(id: String): WorkDetails? = dao.details(id)

    suspend fun progress(workId: String): ProgressEntity? = dao.progress(workId)

    suspend fun latestProgress(): ProgressEntity? = dao.latestProgress()

    suspend fun latestDetails(): WorkDetails? {
        val progress = dao.latestProgress() ?: return null
        return dao.details(progress.workId)
    }

    suspend fun source(id: String): SourceEntity? = dao.source(id)

    suspend fun sourceWorkIds(id: String): List<String> = dao.sourceWorkIds(id)

    suspend fun sourceWorkTrackIds(sourceId: String): Set<String> =
        dao.sourceWorkIds(sourceId)
            .flatMap { dao.details(it)?.tracks.orEmpty() }
            .map { it.id }
            .toSet()

    suspend fun toggleFavorite(id: String) {
        dao.toggleFavorite(id)
    }

    suspend fun saveBookmark(bookmark: BookmarkEntity) {
        dao.saveBookmark(bookmark)
    }

    suspend fun deleteBookmark(id: String) {
        dao.deleteBookmark(id)
    }

    val bookmarks = dao.observeBookmarks().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    private val scanOwners = java.util.concurrent.ConcurrentHashMap<String, Any>()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val artworkRevision = MutableStateFlow(0)
    private val sizeKey = intPreferencesKey("subtitle-size")
    private val offsetKey = longPreferencesKey("subtitle-offset")
    private val privateKey = booleanPreferencesKey("hide-artwork")
    private val motionKey = booleanPreferencesKey("reduce-motion")
    private val cacheKey = intPreferencesKey("streaming-cache-mib")
    private val recentsKey = booleanPreferencesKey("hide-recents")
    private val feedbackKey = booleanPreferencesKey("touch-feedback")
    // This flow has no placeholder value: background resumption must read real privacy preferences.
    val settingsUpdates =
        context.preferences.data.map {
            Settings(
                it[sizeKey] ?: 21,
                it[offsetKey] ?: 0,
                it[privateKey] ?: false,
                it[motionKey] ?: false,
                it[cacheKey] ?: 128,
                it[recentsKey] ?: false,
                it[feedbackKey] ?: true,
            )
        }
    val settings = settingsUpdates.stateIn(scope, SharingStarted.Eagerly, Settings())
    internal val offlineDir = File(context.filesDir, "offline/attachments").apply { mkdirs() }

    internal fun offlineFile(id: String): File? = File(offlineDir, id).takeIf { it.isFile }

    internal val artworkDir = File(context.filesDir, "artwork").apply { mkdirs() }
    internal val customArtworkDir = File(context.filesDir, "custom-artwork").apply { mkdirs() }
    private val artworkCache =
        ImageFileCache(File(context.cacheDir, "artwork"), 64 * MIB, ::storageChanged)
    private val previewCache =
        ImageFileCache(File(context.cacheDir, "previews"), 24 * MIB, ::storageChanged)
    private val imageWrites = Mutex()
    private val imageFlights = Singleflight<String, File?>(scope, 4)
    private val storageReady = CompletableDeferred<Unit>()
    val artworkReload = MutableStateFlow(0)
    private val streamingEvictor = AdjustableCacheEvictor(Long.MAX_VALUE, ::storageChanged)
    private val mediaDatabase = StandaloneDatabaseProvider(context)
    val downloadCache =
        SimpleCache(File(context.filesDir, "offline/audio"), NoOpCacheEvictor(), mediaDatabase)
    private val streamingCache =
        SimpleCache(
            File(context.cacheDir, "streaming"),
            streamingEvictor,
            mediaDatabase,
        )
    private val httpFactory =
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)),
        ) { spec ->
            spec.withRequestHeaders(spec.httpRequestHeaders + headers(spec.uri.toString()))
        }
    private val networkFactory = DataSource.Factory {
        val fallback = httpFactory.createDataSource()
        val online =
            ResolvingDataSource.Factory(
                    OkHttpDataSource.Factory(onlineHttp)
                        .setDefaultRequestProperties(AsmrOneClient.MEDIA_HEADERS),
                ) { spec ->
                    val asset = spec.uri.lastPathSegment ?: throw IOException("文件标识无效")
                    val sourceId = spec.uri.authority ?: throw IOException("来源未关联")
                    val location =
                        runBlocking(Dispatchers.IO) {
                            val source = dao.source(sourceId) ?: throw IOException("来源已移除")
                            require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
                            dao.locations(asset).firstOrNull { it.sourceId == sourceId }
                                ?: throw IOException("文件已移除")
                        }
                    spec.withUri(location.uri.toUri())
                }
                .createDataSource()
        val smbData =
            RemoteFileDataSource("SMB") { uri ->
                val sourceId = uri.toUri().authority ?: throw IOException("来源未关联")
                val source =
                    runBlocking(Dispatchers.IO) { dao.source(sourceId) }
                        ?: throw IOException("来源已移除")
                require(source.kindType == SourceKind.SMB) { "来源类型不匹配" }
                smb.open(source, sourceSecret(source), uri)
            }
        val nfsData =
            RemoteFileDataSource("NFS") { uri ->
                val sourceId = uri.toUri().authority ?: throw IOException("来源未关联")
                val source =
                    runBlocking(Dispatchers.IO) { dao.source(sourceId) }
                        ?: throw IOException("来源已移除")
                require(source.kindType == SourceKind.NFS) { "来源类型不匹配" }
                nfs.open(source, uri)
            }
        val dlsiteData =
            DlsiteDataSource(::dlsiteResource) { sourceId, client ->
                scope.launch {
                    dlsiteCookieWrites.withLock {
                        val current = dlsiteClients[sourceId]
                        val source = dao.source(sourceId)
                        if (
                            source?.kindType == SourceKind.DLSITE &&
                                (client != null && current === client ||
                                    client == null && current == null && source.secret.isBlank())
                        ) {
                            dao.updateSourceError(sourceId, "请重新登录 DLsite")
                        }
                    }
                }
            }
        object : DataSource {
            private var active: DataSource = fallback

            override fun addTransferListener(listener: TransferListener) {
                fallback.addTransferListener(listener)
                smbData.addTransferListener(listener)
                nfsData.addTransferListener(listener)
                online.addTransferListener(listener)
                dlsiteData.addTransferListener(listener)
            }

            override fun open(spec: DataSpec): Long {
                active =
                    when (spec.uri.scheme) {
                        "whisp-smb" -> smbData
                        "whisp-nfs" -> nfsData
                        "whisp-asmr" -> online
                        "whisp-dlsite" -> dlsiteData
                        else -> fallback
                    }
                return active.open(spec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                active.read(buffer, offset, length)

            override fun getUri() = active.uri

            override fun getResponseHeaders() = active.responseHeaders

            override fun close() = active.close()
        }
    }
    private val streamingFactory =
        CacheDataSource.Factory()
            .setCache(streamingCache)
            .setUpstreamDataSourceFactory(networkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val playbackFactory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            // Only DownloadManager writes pinned copies. Playback must not hold a cache
            // hole while paused, which would block a concurrent download of that track.
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val downloadManager =
        DownloadManager(
                context,
                mediaDatabase,
                downloadCache,
                networkFactory,
                Executors.newFixedThreadPool(2),
            )
            .apply {
                maxParallelDownloads = 2
                // Local copies and isolated NAS networks do not require validated Internet.
                requirements = Requirements(Requirements.DEVICE_STORAGE_NOT_LOW)
            }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val downloadRefresh = Channel<Unit>(Channel.CONFLATED)
    private val activeDownloads = MutableStateFlow(false)

    init {
        scope.launch {
            try {
                migrateImages()
            } catch (e: Exception) {
                Log.w("WhispStorage", "Image migration deferred: ${e.javaClass.simpleName}")
            } finally {
                storageReady.complete(Unit)
                artworkRevision.update { it + 1 }
            }
        }
        scope.launch {
            context.preferences.data
                .map { it[cacheKey] ?: 128 }
                .distinctUntilChanged()
                .collect {
                    streamingEvictor.resize(streamingCache, it * MIB)
                }
        }
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onInitialized(manager: DownloadManager) {
                    downloadRefresh.trySend(Unit)
                }

                override fun onDownloadChanged(
                    manager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    downloadRefresh.trySend(Unit)
                    storageChanged()
                    finalException?.let {
                        messages.tryEmit(
                            if (
                                generateSequence<Throwable>(it) { cause -> cause.cause }
                                    .any { cause -> cause is DlsiteLoginRequired }
                            )
                                "DLsite 登录已过期，请在来源页重新登录"
                            else "下载失败，可在来源页重试",
                        )
                    }
                }

                override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                    downloadRefresh.trySend(Unit)
                    storageChanged()
                }
            },
        )
        scope.launch(Dispatchers.Main.immediate) {
            for (ignored in downloadRefresh) {
                val stored = withContext(Dispatchers.IO) { readDownloads() }
                completedDownloads.value =
                    stored.values
                        .filter { it.state == Download.STATE_COMPLETED }
                        .map { it.request.id }
                        .toSet()
                downloads.value = stored
                sampleDownloadProgress()
            }
        }
        downloadRefresh.trySend(Unit)
        scope.launch(Dispatchers.Main.immediate) {
            combine(downloads.subscriptionCount, activeDownloads) { observers, active ->
                    observers > 0 && active
                }
                .distinctUntilChanged()
                .collectLatest { visibleActive ->
                    if (visibleActive)
                        while (isActive) {
                            sampleDownloadProgress()
                            delay(1500)
                        }
                }
        }
    }

    private fun readDownloads(): Map<String, Download> {
        val result = mutableMapOf<String, Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) cursor.download.let { result[it.request.id] = it }
        }
        return result
    }

    // DownloadManager mutates its in-memory byte counters. Copy them for StateFlow
    // delivery; progress sampling never traverses the download database.
    private fun sampleDownloadProgress() {
        val current = downloadManager.currentDownloads
        activeDownloads.value = current.any { it.state == Download.STATE_DOWNLOADING }
        val changed = current.filter { live ->
            val old = downloads.value[live.request.id]
            old == null ||
                old.state != live.state ||
                old.bytesDownloaded != live.bytesDownloaded ||
                old.percentDownloaded != live.percentDownloaded ||
                old.contentLength != live.contentLength
        }
        if (changed.isEmpty()) return
        downloads.update { previous ->
            previous +
                changed.associate { live ->
                    live.request.id to
                        Download(
                            live.request,
                            live.state,
                            live.startTimeMs,
                            live.updateTimeMs,
                            live.contentLength,
                            live.stopReason,
                            live.failureReason,
                            DownloadProgress().apply {
                                bytesDownloaded = live.bytesDownloaded
                                percentDownloaded = live.percentDownloaded
                            },
                        )
                }
        }
    }

    fun headers(uri: String): Map<String, String> {
        val url = uri.toHttpUrlOrNull() ?: return emptyMap()
        if (listOf("dlsite.com", "dlsite.jp").any { url.host == it || url.host.endsWith(".$it") })
            return mapOf("Referer" to "https://www.dlsite.com/")
        val knownSources = sources.value.ifEmpty { runBlocking(Dispatchers.IO) { dao.sources() } }
        val source =
            knownSources.firstOrNull { source ->
                source.kindType == SourceKind.WEBDAV &&
                    (source.root.trimEnd('/') + "/").toHttpUrlOrNull()?.let {
                        WebDavClient.insideRoot(url, it)
                    } == true
            } ?: return emptyMap()
        if (source.username.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to
                Credentials.basic(source.username, sourceSecret(source), Charsets.UTF_8),
        )
    }

    suspend fun setSettings(
        size: Int? = null,
        offset: Long? = null,
        hide: Boolean? = null,
        reduceMotion: Boolean? = null,
        streamingCacheMiB: Int? = null,
        hideRecents: Boolean? = null,
        touchFeedback: Boolean? = null,
    ) {
        context.preferences.edit { prefs ->
            size?.let { prefs[sizeKey] = it.coerceIn(16, 30) }
            offset?.let { prefs[offsetKey] = it.coerceIn(-10000, 10000) }
            hide?.let { prefs[privateKey] = it }
            reduceMotion?.let { prefs[motionKey] = it }
            hideRecents?.let { prefs[recentsKey] = it }
            touchFeedback?.let { prefs[feedbackKey] = it }
            streamingCacheMiB?.let {
                require(it in listOf(64, 128, 256, 512))
                prefs[cacheKey] = it
            }
        }
    }

    suspend fun addLocal(root: String, name: String): String {
        val id = stableId(root)
        dao.putSource(SourceEntity(id, name, SourceKind.LOCAL.raw, root))
        scheduleScan(id)
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
        val id = stableId(normalized + "|" + username)
        val source =
            SourceEntity(
                id,
                name.ifBlank { url.host },
                SourceKind.WEBDAV.raw,
                normalized,
                username.trim(),
                secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) { dav.list(normalized.toHttpUrlOrNull()!!, source, password) }
        dao.putSource(source)
        scheduleScan(id)
        return id
    }

    suspend fun addSmb(name: String, root: String, username: String, password: String): String {
        val address = SmbAddress.parse(root)
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
                secrets.encrypt(password),
            )
        withContext(Dispatchers.IO) { smb.check(source, password) }
        dao.putSource(source)
        scheduleScan(id)
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
        withContext(Dispatchers.IO) { nfs.check(source) }
        dao.putSource(source)
        scheduleScan(id)
        return id
    }

    private val searchHistoryKey = stringPreferencesKey("asmr-search-history")
    val asmrSearchHistory =
        context.preferences.data
            .map { prefs ->
                runCatching {
                        Json.parseToJsonElement(prefs[searchHistoryKey] ?: "[]")
                            .jsonArray
                            .map { it.jsonPrimitive.content }
                            .take(20)
                    }
                    .getOrDefault(emptyList())
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun rememberAsmrSearch(text: String) {
        val clean = normalizedAsmrInput(text).take(300)
        if (clean.isBlank()) return
        context.preferences.edit { prefs ->
            val previous = runCatching {
                Json.parseToJsonElement(prefs[searchHistoryKey] ?: "[]").jsonArray.map {
                    it.jsonPrimitive.content
                }
            }
                .getOrDefault(emptyList())
            prefs[searchHistoryKey] =
                JsonArray((listOf(clean) + previous).distinct().take(20).map(::JsonPrimitive))
                    .toString()
        }
    }

    suspend fun clearAsmrSearchHistory() {
        context.preferences.edit { it.remove(searchHistoryKey) }
    }

    suspend fun asmrPage(query: AsmrQuery, page: Int): AsmrPage {
        val source = dao.source(query.sourceId) ?: error("来源已移除")
        val client = asmrClient(source)
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
        asmrClient(dao.source(sourceId) ?: error("来源已移除")).work(id)

    suspend fun previewAsmrWork(
        sourceId: String,
        id: Long,
        metadataClient: DlsiteClient = dlsite,
    ): AsmrPreview =
        withContext(Dispatchers.IO) {
            val source = dao.source(sourceId) ?: error("来源已移除")
            val client = asmrClient(source)
            val catalog = client.work(id)
            require(catalog.id == id) { "作品编号不匹配" }
            val official =
                metadataClient.work(catalog.rj).withPrevious(readDlsiteDetails(catalog.workId))
            val work = official.applyTo(catalog)
            AsmrPreview(
                sourceId,
                catalog.artist,
                work,
                client.index(work, sourceId, System.currentTimeMillis()),
                official.details,
            )
        }

    suspend fun addAsmrPreview(preview: AsmrPreview): String =
        withContext(Dispatchers.IO) {
            onlineWorkChanges.withLock { commitAsmrPreview(preview) }
        }

    suspend fun importAsmrWork(
        sourceId: String,
        id: Long,
        metadataClient: DlsiteClient = dlsite,
        existingOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            onlineWorkChanges.withLock {
                val source = dao.source(sourceId) ?: error("来源已移除")
                if (existingOnly && dao.work(stableId("asmr.one|work|$id")) == null)
                    return@withLock stableId("asmr.one|work|$id")
                require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
                commitAsmrPreview(previewAsmrWork(sourceId, id, metadataClient))
            }
        }

    private suspend fun commitAsmrPreview(preview: AsmrPreview): String {
        val sourceId = preview.sourceId
        val work = preview.work
        val indexed = preview.indexed
        val generation = System.currentTimeMillis()
        val source = dao.source(sourceId) ?: error("来源已移除")
        val client = asmrClient(source)
        val previous = dao.work(work.workId)
        if (previous?.offlineAttachments == true)
            work.coverId?.let { cover ->
                val target = File(offlineDir, cover)
                if (!target.isFile)
                    atomicFile(target, client.bytes(work.coverUrl!!, 16 * 1024 * 1024))
            }
        currentCoroutineContext().ensureActive()
        var obsoleteTracks = emptyList<String>()
        database.withTransaction {
            val latestSource = dao.source(sourceId) ?: error("来源已移除")
            val old = dao.details(work.workId)
            val newWork = indexed.works.single()
            val savedWork =
                old?.work?.let {
                    newWork.copy(
                        title = if (it.title != it.originalTitle) it.title else newWork.title,
                        artist =
                            if (it.artist != it.originalArtist.ifBlank { preview.catalogArtist })
                                it.artist
                            else newWork.artist,
                        favorite = it.favorite,
                        coverId =
                            it.coverId?.takeIf { cover ->
                                cover == stableId("${it.id}|custom-cover")
                            } ?: newWork.coverId,
                        addedAt = it.addedAt,
                        offlineAttachments = it.offlineAttachments,
                    )
                } ?: newWork
            val oldTracks = old?.tracks.orEmpty().associateBy { it.id }
            val pinned =
                oldTracks.values
                    .filter {
                        downloadManager.downloadIndex.getDownload(it.id)?.state ==
                            Download.STATE_COMPLETED
                    }
                    .map { it.id }
            dao.deleteWorkSourceLocations(work.workId, sourceId)
            dao.putWorks(
                listOf(
                    savedWork.copy(
                        offlineAttachments =
                            savedWork.offlineAttachments &&
                                offlineFilesPresent(savedWork, indexed.attachments),
                    ),
                ),
            )
            preview.metadata?.let { fresh ->
                val previous = decodeDlsiteDetails(dao.dlsiteMetadata(work.workId)?.snapshot)
                dao.putDlsiteMetadata(
                    DlsiteMetadataEntity(work.workId, fresh.preserving(previous).encode()),
                )
            }
            dao.deleteStaleTracks(work.workId, indexed.tracks.map { it.id } + pinned)
            obsoleteTracks =
                (oldTracks.keys - (indexed.tracks.map { it.id } + pinned).toSet()).toList()
            val pinnedAttachments =
                old?.attachments.orEmpty().filter { offlineFile(it.id) != null }.map { it.id }
            dao.deleteStaleAttachments(
                work.workId,
                indexed.attachments.map { it.id } + pinnedAttachments,
            )
            dao.putTracks(
                indexed.tracks.map { track ->
                    track.copy(
                        durationMs =
                            track.durationMs.takeIf { it > 0 }
                                ?: oldTracks[track.id]?.durationMs
                                ?: 0,
                    )
                },
            )
            dao.putAttachments(indexed.attachments)
            dao.putLocations(indexed.locations)
            dao.putSource(latestSource.copy(lastScan = generation, error = null))
        }
        removeObsoleteDownloads(obsoleteTracks)
        storageChanged()
        return work.workId
    }

    private fun removeObsoleteDownloads(ids: List<String>) {
        for (id in ids) {
            if (downloadManager.downloadIndex.getDownload(id) != null)
                offlineStore.removeTrackDownload(id)
            streamingCache.removeResource(id)
        }
    }

    private fun offlineFilesPresent(work: WorkEntity, attachments: List<AttachmentEntity>) =
        attachments.all { offlineFile(it.id) != null } &&
            (work.coverId == null || offlineFile(work.coverId) != null)

    suspend fun updateDlsiteMetadata(id: String, client: DlsiteClient = dlsite) =
        withContext(Dispatchers.IO) {
            onlineWorkChanges.withLock {
                val old = dao.work(id) ?: error("作品已删除")
                val metadata =
                    client.work(old.rj ?: error("作品没有 RJ 编号")).withPrevious(readDlsiteDetails(id))
                val coverId = stableId("$id|${metadata.coverPath}")
                val coverUrl = metadata.coverUrl.toHttpUrlOrNull() ?: error("封面地址无效")
                val bytes =
                    onlineHttp.onlineBytes(
                        okhttp3.Request.Builder()
                            .url(coverUrl)
                            .header("Referer", "https://www.dlsite.com/")
                            .build(),
                        16 * 1024 * 1024,
                    )
                cacheArtwork(coverId, bytes)
                if (old.offlineAttachments) atomicFile(File(offlineDir, coverId), bytes)
                database.withTransaction {
                    val latest = dao.work(id) ?: error("作品已删除")
                    dao.putWorks(
                        listOf(
                            latest.copy(
                                title = metadata.title,
                                originalTitle = metadata.title,
                                artist = metadata.artist,
                                originalArtist = metadata.artist,
                                series = metadata.series,
                                coverUrl = metadata.coverUrl,
                                coverId =
                                    latest.coverId?.takeIf { it == stableId("$id|custom-cover") }
                                        ?: coverId,
                            ),
                        ),
                    )
                }
                database.withTransaction {
                    if (dao.work(id) != null) {
                        val previous = decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot)
                        dao.putDlsiteMetadata(
                            DlsiteMetadataEntity(
                                id,
                                metadata.details.preserving(previous).encode(),
                            ),
                        )
                    }
                }
                artworkRevision.update { it + 1 }
                storageChanged()
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
                if (work != null && work.rj == details.rj) {
                    val previous = decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot)
                    dao.putDlsiteMetadata(
                        DlsiteMetadataEntity(id, details.preserving(previous).encode()),
                    )
                }
            }
        }

    suspend fun ensureDlsiteImage(id: String, url: String): File? =
        imageFlights.run(id) {
            artworkCache.get(id)
                ?: cacheArtwork(
                        id,
                        onlineHttp.onlineBytes(
                            okhttp3.Request.Builder()
                                .url(url)
                                .header("Referer", "https://www.dlsite.com/")
                                .build(),
                            16 * 1024 * 1024,
                        ),
                    )
                    .also { artworkRevision.update { it + 1 } }
        }

    suspend fun removeAsmrWork(id: String) {
        // Cancel the attachment worker before waiting for its mutation lock.
        WorkManager.getInstance(context).cancelUniqueWork("attachments-$id")
        onlineWorkChanges.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                val detail = dao.details(id) ?: return@withContext
                require(isAsmrOneWork(detail.work) || detail.work.origin == "dlsite") {
                    "作品类型不支持此操作"
                }
                flushPlaybackProgress()
                val assets =
                    (detail.tracks.map { it.id } +
                            detail.attachments.map { it.id } +
                            listOfNotNull(detail.work.coverId))
                        .toSet()
                database.withTransaction {
                    dao.deleteWorkLocations(id)
                    dao.deleteWorkTracks(id)
                    dao.deleteWorkAttachments(id)
                    dao.deleteWorkProgress(id)
                    dao.deleteWorkBookmarks(id)
                    dao.deleteWork(id)
                }
                assets.forEach { asset ->
                    imageFlights.cancelAndJoin("cover:$asset")
                    imageFlights.cancelAndJoin("preview:$asset")
                }
                detail.tracks.forEach {
                    offlineStore.removeTrackDownload(it.id)
                    streamingCache.removeResource(it.id)
                }
                imageWrites.withLock {
                    assets.forEach { asset ->
                        if (!dao.otherOfflineCover(asset, id)) File(offlineDir, asset).delete()
                        File(customArtworkDir, asset).delete()
                        File(artworkDir, asset).delete()
                        if (!dao.otherCover(asset, id)) {
                            artworkCache.remove(asset)
                            previewCache.remove(asset)
                        }
                    }
                }
                artworkRevision.update { it + 1 }
                storageChanged()
            }
        }
    }

    suspend fun ensureCatalogArtwork(id: String, url: String, sourceId: String): File? =
        imageFlights.run("cover:$id") {
            storageReady.await()
            val source = dao.source(sourceId) ?: error("来源已移除")
            artwork(id)
                ?: cacheArtwork(
                        id,
                        if (source.kindType == SourceKind.DLSITE)
                            onlineHttp.onlineBytes(
                                okhttp3.Request.Builder()
                                    .url(url)
                                    .header("Referer", "https://www.dlsite.com/")
                                    .build(),
                                16 * 1024 * 1024,
                            )
                        else asmrClient(source).bytes(url, 16 * 1024 * 1024),
                    )
                    .also {
                        artworkRevision.update { it + 1 }
                    }
        }

    fun scheduleScan(id: String, force: Boolean = false) {
        if (!force && id in busy.value) return
        val request =
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf("sourceId" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        // A user refresh must replace an interrupted request awaiting its retry.
        WorkManager.getInstance(context)
            .enqueueUniqueWork("scan-$id", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun scan(id: String) {
        val source = dao.source(id) ?: return
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
                val client = dlsiteAccount(source.id)
                dlsiteClient = client
                syncDlsitePurchases(source.id, false, client)
                return
            }
            if (source.kindType == SourceKind.ASMRONE) {
                for (workId in dao.sourceWorkIds(source.id)) {
                    currentCoroutineContext().ensureActive()
                    val id = dao.work(workId)?.rj?.removePrefix("RJ")?.toLongOrNull() ?: continue
                    importAsmrWork(source.id, id, existingOnly = true)
                }
                database.withTransaction {
                    dao.source(source.id)?.let {
                        dao.putSource(it.copy(lastScan = System.currentTimeMillis(), error = null))
                    }
                }
                return
            }
            val entries =
                when (source.kindType) {
                    SourceKind.LOCAL -> LocalDirectory(context).walk(source.root)
                    SourceKind.SMB -> smb.walk(source, sourceSecret(source))
                    SourceKind.NFS -> nfs.walk(source)
                    SourceKind.WEBDAV -> dav.walk(source, sourceSecret(source))
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
                    dao.sourceWorkIds(id).isNotEmpty(),
            ) {
                "目录中未找到音频，请检查来源目录"
            }
            currentCoroutineContext().ensureActive()
            var savedTracks: List<TrackEntity> = emptyList()
            var obsoleteTracks = emptyList<String>()
            database.withTransaction {
                val latestSource = dao.source(id) ?: throw IOException("来源已移除")
                val oldWorks = dao.allWorks().associateBy { it.id }
                val oldTracks = dao.allTracks().associateBy { it.id }
                val previousLocations = dao.sourceLocations(id)
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
                dao.putWorks(
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
                                                offlineFile(cover) != null
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
                                            offlineFilesPresent(
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
                dao.putTracks(savedTracks)
                dao.putAttachments(indexed.attachments)
                dao.putLocations(indexed.locations)
                dao.removeStaleLocations(id, generation)
                val changedWorks = (affectedWorks + indexed.works.map { it.id }).distinct()
                obsoleteTracks =
                    changedWorks
                        .chunked(500)
                        .flatMap { dao.unlocatedTracks(it) }
                        .filter {
                            downloadManager.downloadIndex.getDownload(it.id)?.state !=
                                Download.STATE_COMPLETED && offlineFile(it.id) == null
                        }
                        .map { it.id }
                obsoleteTracks.chunked(500).forEach { dao.deleteTracks(it) }
                val obsoleteAttachments =
                    changedWorks
                        .chunked(500)
                        .flatMap { dao.unlocatedAttachments(it) }
                        .filter { offlineFile(it.id) == null }
                        .map { it.id }
                obsoleteAttachments.chunked(500).forEach { dao.deleteAttachments(it) }
                dao.putSource(latestSource.copy(lastScan = generation, error = null))
            }
            removeObsoleteDownloads(obsoleteTracks)
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
                database.withTransaction {
                    if (scanOwners[id] !== owner)
                        throw CancellationException("扫描已被替换").apply { initCause(error) }
                    dao.source(id)?.let { latest ->
                        if (
                            latest.kind == source.kind &&
                                latest.root == source.root &&
                                latest.username == source.username &&
                                (dlsiteClient != null || latest.secret == source.secret)
                        ) {
                            dao.updateSourceError(id, error.message ?: "来源不可用")
                            messages.tryEmit("${latest.name}：${error.message ?: "扫描失败"}")
                        }
                    }
                }
            }
            if (source.kindType == SourceKind.DLSITE) {
                dlsiteCookieWrites.withLock {
                    dlsiteClient?.let { requireDlsiteClient(id, it) }
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
        data class Asset(val id: String, val cover: Boolean)
        val tasks = mutableListOf<Asset>()
        val byWork = tracks.groupBy { it.workId }
        indexed.works.forEach { work ->
            work.coverId?.takeUnless { artwork(it) != null }?.let { tasks += Asset(it, true) }
            byWork[work.id]
                .orEmpty()
                .filter {
                    it.durationMs == 0L &&
                        (source.kindType == SourceKind.LOCAL || it.fileName.endsWith(".mp3", true))
                }
                .forEach { tasks += Asset(it.id, false) }
        }
        if (tasks.isEmpty()) return@coroutineScope
        val locations = indexed.locations.associateBy { it.assetId }
        val password = if (source.kindType != SourceKind.LOCAL) sourceSecret(source) else ""
        val lanes = minOf(4, tasks.size)
        val processed = java.util.concurrent.atomic.AtomicInteger()
        val skipped = java.util.concurrent.atomic.AtomicInteger()
        (0 until lanes)
            .map { lane ->
                async(Dispatchers.IO) {
                    var session: RemoteAssetSession? = null
                    val durations = mutableListOf<Pair<String, Long>>()
                    suspend fun flush() {
                        if (durations.isEmpty()) return
                        database.withTransaction {
                            durations.forEach { (id, duration) -> dao.updateDuration(id, duration) }
                        }
                        durations.clear()
                    }
                    try {
                        if (source.kindType == SourceKind.SMB)
                            session = smb.assets(source, password)
                        if (source.kindType == SourceKind.NFS) session = nfs.assets(source)
                        tasks
                            .filterIndexed { index, _ -> index % lanes == lane }
                            .forEachIndexed { index, asset ->
                                ensureActive()
                                try {
                                    if (asset.cover) {
                                        if (session != null) {
                                            storageReady.await()
                                            cacheArtwork(
                                                asset.id,
                                                session.bytes(
                                                    locations.getValue(asset.id).uri,
                                                    16 * 1024 * 1024,
                                                ),
                                            )
                                            artworkRevision.update { it + 1 }
                                        } else ensureArtwork(asset.id)
                                    } else {
                                        val location = locations.getValue(asset.id)
                                        val duration =
                                            when (source.kindType) {
                                                SourceKind.SMB,
                                                SourceKind.NFS -> session!!.duration(location.uri)
                                                SourceKind.LOCAL -> localDuration(asset.id)
                                                else ->
                                                    Mp3Metadata.duration(location.size) {
                                                        offset,
                                                        length ->
                                                        dav.range(
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

    suspend fun removeSource(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("scan-$id")
        if (dao.source(id)?.kindType == SourceKind.DLSITE) {
            for (workId in dao.sourceWorkIds(id)) {
                WorkManager.getInstance(context).cancelUniqueWork("attachments-$workId")
                dao.details(workId)
                    ?.tracks
                    .orEmpty()
                    .onEach { streamingCache.removeResource(it.id) }
                    .filter {
                        downloadManager.downloadIndex.getDownload(it.id)?.state !=
                            Download.STATE_COMPLETED
                    }
                    .forEach {
                        offlineStore.removeTrackDownload(it.id)
                    }
            }
        }
        dlsiteCookieWrites.withLock {
            dlsiteClients.remove(id)?.let {
                it.session.clear()
                it.http.dispatcher.cancelAll()
            }
            database.withTransaction {
                dao.deleteSourceLocations(id)
                dao.deleteSource(id)
            }
        }
    }

    suspend fun resolve(id: String): String {
        offlineFile(id)?.let {
            return Uri.fromFile(it).toString()
        }
        downloadManager.downloadIndex
            .getDownload(id)
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.let {
                return it.request.uri.toString()
            }
        val options = dao.locations(id)
        val location =
            options.firstOrNull { it.uri.startsWith("content:") || it.uri.startsWith("file:") }
                ?: options.firstOrNull()
                ?: return dao.workByCover(id)?.coverUrl ?: throw IOException("文件已移动或来源已移除")
        return when (dao.source(location.sourceId)?.kindType) {
            SourceKind.ASMRONE -> "whisp-asmr://${location.sourceId}/$id"
            SourceKind.DLSITE -> "whisp-dlsite://${location.sourceId}/$id"
            else -> location.uri
        }
    }

    suspend fun availableTracks(tracks: List<TrackEntity>): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            val located =
                tracks.map { it.id }.chunked(500).flatMap { dao.locatedAssets(it) }.toSet()
            tracks.filter {
                it.id in located ||
                    offlineFile(it.id) != null ||
                    downloadManager.downloadIndex.getDownload(it.id)?.state ==
                        Download.STATE_COMPLETED
            }
        }

    fun artwork(id: String?): File? = id?.let { asset ->
        offlineFile(asset)
            ?: File(customArtworkDir, asset).takeIf { it.isFile }
            ?: artworkCache.get(asset)
            ?: File(artworkDir, asset).takeIf { it.isFile }
    }

    private suspend fun cacheArtwork(id: String, bytes: ByteArray): File {
        val optimized = withContext(Dispatchers.IO) { ImageOptimizer.optimize(bytes) }
        return imageWrites.withLock { artworkCache.put(id, optimized) }
    }

    suspend fun ensureArtwork(id: String): File? =
        imageFlights.run("cover:$id") {
            storageReady.await()
            artwork(id)
                ?: if (dao.locations(id).isEmpty() && dao.workByCover(id)?.coverUrl == null) null
                else {
                    cacheArtwork(id, readAsset(id, 16 * 1024 * 1024)).also {
                        artworkRevision.update { it + 1 }
                    }
                }
        }

    suspend fun previewImage(id: String): File =
        imageFlights.run("preview:$id") {
            storageReady.await()
            offlineFile(id)
                ?: previewCache.get(id)
                ?: run {
                    val bytes = ImageOptimizer.optimize(readAsset(id, 64 * 1024 * 1024), 2560)
                    imageWrites.withLock { previewCache.put(id, bytes) }
                }
        } ?: throw IOException("图片不可读")

    /** Only indexed remote originals move to cache; custom and offline files remain durable. */
    internal suspend fun migrateImages() =
        withContext(Dispatchers.IO) {
            imageWrites.withLock {
                val works = dao.allWorks()
                for (work in works) {
                    val id = work.coverId ?: continue
                    val old = File(artworkDir, id)
                    if (!old.isFile) continue
                    if (id == stableId("${work.id}|custom-cover")) {
                        val target = File(customArtworkDir, id)
                        if (!target.isFile) atomicFile(target, old.readBytes())
                        if (target.isFile) old.delete()
                    } else if (dao.locations(id).isNotEmpty()) {
                        if (artworkCache.get(id) == null)
                            artworkCache.put(id, ImageOptimizer.optimize(old.readBytes()))
                        if (artworkCache.get(id) != null) old.delete()
                    }
                }
                // Previous versions put previews directly in cacheDir. Identify them by DB ID.
                dao.imageAttachments().forEach { id ->
                    val old = File(context.cacheDir, id)
                    if (old.isFile) {
                        if (previewCache.get(id) == null)
                            previewCache.put(id, ImageOptimizer.optimize(old.readBytes(), 2560))
                        if (previewCache.get(id) != null) old.delete()
                    }
                }
            }
        }

    suspend fun storageUsage(): StorageUsage =
        withContext(Dispatchers.IO) {
            storageReady.await()
            fun size(file: File): Long =
                if (file.isFile) file.length() else file.listFiles()?.sumOf { size(it) } ?: 0L
            StorageUsage(
                artworkCache.size(),
                previewCache.size(),
                size(File(context.cacheDir, "streaming")),
                size(File(context.filesDir, "offline/audio")) + size(offlineDir),
                size(customArtworkDir),
                size(File(context.dataDir, "databases")) +
                    size(File(context.filesDir, "datastore")) +
                    size(File(context.dataDir, "shared_prefs")) +
                    size(context.noBackupFilesDir) +
                    size(artworkDir),
            )
        }

    suspend fun clearTemporaryCache() =
        withContext(Dispatchers.IO) {
            storageReady.await()
            imageFlights.joinAll()
            imageWrites.withLock {
                artworkCache.clear()
                previewCache.clear()
            }
            synchronized(streamingCache) {
                streamingCache.keys.toList().forEach { streamingCache.removeResource(it) }
            }
            artworkRevision.update { it + 1 }
            artworkReload.update { it + 1 }
        }

    suspend fun readAsset(id: String, limit: Int = 8 * 1024 * 1024): ByteArray =
        withContext(Dispatchers.IO) {
            offlineFile(id)?.let {
                return@withContext it.inputStream().use { stream -> boundedRead(stream, limit) }
            }
            dao.workByCover(id)?.coverUrl?.let { url ->
                return@withContext onlineHttp.onlineBytes(
                    okhttp3.Request.Builder()
                        .url(url)
                        .header("Referer", "https://www.dlsite.com/")
                        .build(),
                    limit,
                )
            }
            val uri = resolve(id).toUri()
            when (uri.scheme) {
                "content",
                "file" ->
                    context.contentResolver.openInputStream(uri)?.use { boundedRead(it, limit) }
                        ?: throw IOException("文件不可读")
                "whisp-asmr" -> {
                    val location =
                        dao.locations(id).firstOrNull { it.sourceId == uri.authority }
                            ?: error("来源已移除")
                    val source = dao.source(location.sourceId) ?: error("来源已移除")
                    asmrClient(source).bytes(location.uri, limit)
                }
                "whisp-dlsite" -> {
                    val resource = dlsiteResource(uri.authority ?: error("来源已移除"), id)
                    resource.client.resourceBytes(resource.workno, resource.path, limit)
                }
                else -> {
                    val location =
                        dao.locations(id).firstOrNull { it.uri == uri.toString() } ?: error("来源未关联")
                    val source = dao.source(location.sourceId) ?: error("来源已移除")
                    when (source.kindType) {
                        SourceKind.SMB ->
                            smb.bytes(uri.toString(), source, sourceSecret(source), limit)
                        SourceKind.NFS -> nfs.bytes(source, uri.toString(), limit)
                        else -> dav.bytes(uri.toString(), source, sourceSecret(source), limit)
                    }
                }
            }
        }

    private suspend fun localDuration(id: String): Long {
        val uri = resolve(id).toUri()
        if (uri.scheme !in setOf("content", "file")) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0
        } finally {
            retriever.release()
        }
    }

    fun mediaMetadata(
        track: TrackEntity,
        work: WorkEntity,
        hideArtwork: Boolean = settings.value.hideArtwork,
    ): MediaMetadata {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(work.artist.ifBlank { work.title })
                .setAlbumTitle(work.title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(
                    if (track.isVideo) MediaMetadata.MEDIA_TYPE_VIDEO
                    else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
                )
                .setExtras(android.os.Bundle().apply { putString("workId", work.id) })
        if (!hideArtwork) artwork(work.coverId)?.let { metadata.setArtworkUri(Uri.fromFile(it)) }
        if (hideArtwork) metadata.setTitle("正在收听").setArtist("Whisp").setAlbumTitle("Whisp")
        return metadata.build()
    }

    suspend fun mediaItem(track: TrackEntity, work: WorkEntity): MediaItem {
        val hide = settingsUpdates.first().hideArtwork
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(resolve(track.id))
            .setMimeType(MediaFormats.videoMimeType(track.fileName))
            .setCustomCacheKey(track.id)
            .setMediaMetadata(mediaMetadata(track, work, hide))
            .build()
    }

    internal val offlineStore: OfflineStore by lazy { OfflineStore(this) }

    suspend fun editWorkIdentity(id: String, title: String, artist: String) {
        dao.updateWorkIdentity(id, title, artist)
    }
}

fun boundedRead(stream: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "文件过大" }
    }
    return output.toByteArray()
}
