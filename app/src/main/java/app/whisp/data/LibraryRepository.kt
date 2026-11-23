// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

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
    internal fun sourceSecret(source: SourceEntity): String =
        synchronized(secretCache) {
            secretCache.getOrPut(source.id + "|" + source.secret) {
                secrets.decrypt(source.secret)
            }
        }

    internal fun evictSecret(sourceId: String) {
        val prefix = "$sourceId|"
        synchronized(secretCache) { secretCache.keys.removeAll { it.startsWith(prefix) } }
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
    private val workLocks = WorkLocks()
    val dlsite = DlsiteClient(onlineHttp)
    val dlsiteDiagnostics = DlsiteDiagnostics(context)
    val playbackDiagnostics = PlaybackDiagnostics(context)
    private val dlsiteClients = ConcurrentHashMap<String, DlsiteAccountClient>()
    internal val dlsiteCookieWrites = Mutex()
    val smb = SmbClient { Log.i("WhispScan", it) }
    val nfs = NfsClient(report = { Log.i("WhispScan", it) })
    val storageChanges = MutableStateFlow(0L)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    internal val offlineDir = File(context.filesDir, "offline/attachments")

    internal fun offlineFile(id: String): File? = File(offlineDir, id).takeIf { it.isFile }

    internal fun storageChanged() {
        storageChanges.update { it + 1 }
    }

    private val settingsStore = SettingsStore(context, scope)
    val settingsUpdates: Flow<Settings> = settingsStore.settingsUpdates
    val settings: StateFlow<Settings> = settingsStore.settings
    val asmrSearchHistory: StateFlow<List<String>> = settingsStore.asmrSearchHistory

    internal val artworkStore =
        ArtworkStore(
            context = context,
            scope = scope,
            dao = dao,
            offlineDir = offlineDir,
            storageChanged = ::storageChanged,
            readAsset = ::readAsset,
            fetchDlsiteImage = { url ->
                onlineHttp.onlineBytes(
                    okhttp3.Request.Builder()
                        .url(url)
                        .header("Referer", "https://www.dlsite.com/")
                        .build(),
                    16 * 1024 * 1024,
                )
            },
            fetchCatalogImage = { url, sourceId ->
                val source = dao.source(sourceId) ?: error("来源已移除")
                if (source.kindType == SourceKind.DLSITE)
                    onlineHttp.onlineBytes(
                        okhttp3.Request.Builder()
                            .url(url)
                            .header("Referer", "https://www.dlsite.com/")
                            .build(),
                        16 * 1024 * 1024,
                    )
                else asmrClient(source).bytes(url, 16 * 1024 * 1024)
            },
        )
    internal val artworkDir: File
        get() = artworkStore.artworkDir

    internal val customArtworkDir: File
        get() = artworkStore.customArtworkDir

    val artworkRevision: MutableStateFlow<Int>
        get() = artworkStore.artworkRevision

    val artworkReload: MutableStateFlow<Int>
        get() = artworkStore.artworkReload

    fun artworkAssetRevision(id: String): StateFlow<Int> = artworkStore.assetRevision(id)

    private val mediaRouting =
        MediaSourceRouting(
            context = context,
            scope = scope,
            dao = dao,
            http = http,
            onlineHttp = onlineHttp,
            smb = smb,
            nfs = nfs,
            headersFor = ::headers,
            sourceSecret = ::sourceSecret,
            dlsiteResource = ::dlsiteResource,
            onDlsiteLoginRequired = { sourceId, client ->
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
            },
            storageChanged = ::storageChanged,
            streamingCacheMiBUpdates = settingsStore.streamingCacheMiBUpdates,
            messages = messages,
        )

    val downloadCache: SimpleCache
        get() = mediaRouting.downloadCache

    val playbackFactory
        get() = mediaRouting.playbackFactory

    val downloadManager: DownloadManager
        get() = mediaRouting.downloadManager

    val downloads: StateFlow<Map<String, Download>>
        get() = mediaRouting.downloads

    val library =
        combine(dao.observeLibrary(), dao.observeLocatedWorks(), mediaRouting.completedDownloads) {
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
    val bookmarks = dao.observeBookmarks().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val scanner = LibraryScanner(this)
    private val merger = OnlineWorkMerger(this)
    val busy: StateFlow<Set<String>>
        get() = scanner.busy

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

    internal suspend fun <T> withWorkLock(workId: String, block: suspend () -> T): T =
        workLocks.withLock(workId, block)

    fun savePlaybackProgress(progress: ProgressEntity) {
        progressWriter.save(progress)
    }

    fun savePlaybackProgressImmediate(progress: ProgressEntity) {
        progressWriter.saveImmediate(progress)
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

    internal suspend fun dlsiteAccount(sourceId: String): DlsiteAccountClient =
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

    internal fun requireDlsiteClient(sourceId: String, client: DlsiteAccountClient) {
        if (dlsiteClients[sourceId] !== client) throw CancellationException("DLsite 会话已更新")
    }

    suspend fun acceptDlsiteLogin(snapshot: String, expectedSource: String? = null): String {
        val session = DlsiteSession(snapshot)
        val verifier = DlsiteAccountClient(session, http, diagnostic = dlsiteDiagnostics::record)
        val account =
            try {
                verifier.authorize()
            } finally {
                verifier.http.dispatcher.executorService.shutdown()
            }
        val id = account.id
        require(expectedSource == null || expectedSource == id) { "请登录该来源对应的账号" }
        dlsiteCookieWrites.withLock {
            val previous = dao.source(id)
            dlsiteClients.remove(id)?.close()
            evictSecret(id)
            val number = dao.sources().count { it.kindType == SourceKind.DLSITE }
            dao.putSource(
                (previous
                        ?: SourceEntity(
                            id,
                            if (number == 0) "DLsite" else "DLsite ${number + 1}",
                            SourceKind.DLSITE.raw,
                            "https://play.dlsite.com/",
                        ))
                    .copy(secret = secrets.encrypt(session.encode()), error = null),
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
        scanner.syncDlsitePurchases(sourceId, full, dlsiteAccount(sourceId))
    }

    suspend fun importDlsiteWork(
        sourceId: String,
        workno: String,
        refresh: Boolean = false,
    ): WorkDetails =
        withContext(Dispatchers.IO) {
            val workId = stableId("dlsite|$sourceId|$workno")
            withWorkLock(workId) {
                val cached =
                    decodeOwnedWork(dao.dlsitePurchase(sourceId, workno)?.snapshot.orEmpty())
                val client = dlsiteAccount(sourceId)
                val work = cached ?: client.work(workno)
                if (!refresh)
                    dao.details(workId)
                        ?.takeIf { it.tracks.isNotEmpty() && workId in dao.sourceWorkIds(sourceId) }
                        ?.let {
                            return@withWorkLock it
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
                val obsoleteTracks =
                    merger.mergeOnlineWork(
                        sourceId = sourceId,
                        expectedKind = SourceKind.DLSITE,
                        workId = workId,
                        freshWork = fresh,
                        indexed = indexed,
                        metadata = metadata?.details,
                    ) { _, _ ->
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
                                            metadataAttemptedAt = System.currentTimeMillis(),
                                        )
                                        .encode(),
                                ),
                            ),
                        )
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
        textEncoding: String? = null,
        playbackSpeed: Float? = null,
    ) =
        settingsStore.setSettings(
            size = size,
            offset = offset,
            hide = hide,
            reduceMotion = reduceMotion,
            streamingCacheMiB = streamingCacheMiB,
            hideRecents = hideRecents,
            touchFeedback = touchFeedback,
            textEncoding = textEncoding,
            playbackSpeed = playbackSpeed,
        )

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

    suspend fun rememberAsmrSearch(text: String) = settingsStore.rememberAsmrSearch(text)

    suspend fun clearAsmrSearchHistory() = settingsStore.clearAsmrSearchHistory()

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
            withWorkLock(preview.work.workId) { commitAsmrPreview(preview) }
        }

    suspend fun importAsmrWork(
        sourceId: String,
        id: Long,
        metadataClient: DlsiteClient = dlsite,
        existingOnly: Boolean = false,
    ): String =
        withContext(Dispatchers.IO) {
            val workId = stableId("asmr.one|work|$id")
            withWorkLock(workId) {
                val source = dao.source(sourceId) ?: error("来源已移除")
                if (existingOnly && dao.work(workId) == null) return@withWorkLock workId
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
        val obsoleteTracks =
            merger.mergeOnlineWork(
                sourceId = sourceId,
                expectedKind = SourceKind.ASMRONE,
                workId = work.workId,
                freshWork = indexed.works.single(),
                indexed = indexed,
                catalogArtistFallback = preview.catalogArtist,
                metadata = preview.metadata,
            ) { _, latestSource ->
                dao.putSource(latestSource.copy(lastScan = generation, error = null))
            }
        removeObsoleteDownloads(obsoleteTracks)
        storageChanged()
        return work.workId
    }

    internal fun removeObsoleteDownloads(ids: List<String>) {
        for (id in ids) {
            if (downloadManager.downloadIndex.getDownload(id) != null)
                offlineStore.removeTrackDownload(id)
            mediaRouting.streamingCache.removeResource(id)
        }
    }

    internal fun offlineFilesPresent(work: WorkEntity, attachments: List<AttachmentEntity>) =
        attachments.all { offlineFile(it.id) != null } &&
            (work.coverId == null || offlineFile(work.coverId) != null)

    suspend fun updateDlsiteMetadata(id: String, client: DlsiteClient = dlsite) =
        withContext(Dispatchers.IO) {
            withWorkLock(id) {
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
                artworkStore.cacheArtwork(coverId, bytes)
                if (old.offlineAttachments) atomicFile(File(offlineDir, coverId), bytes)
                // Keep works & dlsite_metadata updates inside a single atomic transaction.
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
                    val previous = decodeDlsiteDetails(dao.dlsiteMetadata(id)?.snapshot)
                    dao.putDlsiteMetadata(
                        DlsiteMetadataEntity(id, metadata.details.preserving(previous).encode()),
                    )
                }
                artworkStore.notifyAssetChanged(coverId)
                old.coverId?.let { artworkStore.notifyAssetChanged(it) }
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
        artworkStore.ensureDlsiteImage(id, url)

    suspend fun removeAsmrWork(id: String) {
        // Cancel the attachment worker before waiting for its mutation lock.
        WorkManager.getInstance(context).cancelUniqueWork("attachments-$id")
        withWorkLock(id) {
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
                detail.tracks.forEach {
                    offlineStore.removeTrackDownload(it.id)
                    mediaRouting.streamingCache.removeResource(it.id)
                }
                artworkStore.cancelAndPurgeWorkAssets(id, assets)
                storageChanged()
            }
        }
    }

    suspend fun ensureCatalogArtwork(id: String, url: String, sourceId: String): File? =
        artworkStore.ensureCatalogArtwork(id, url, sourceId)

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

    suspend fun scan(id: String) = scanner.scan(id)

    suspend fun removeSource(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("scan-$id")
        val affectedWorks = dao.sourceWorkIds(id)
        for (workId in affectedWorks) {
            WorkManager.getInstance(context).cancelUniqueWork("attachments-$workId")
            dao.details(workId)
                ?.tracks
                .orEmpty()
                .onEach { mediaRouting.streamingCache.removeResource(it.id) }
                .filter {
                    downloadManager.downloadIndex.getDownload(it.id)?.state !=
                        Download.STATE_COMPLETED
                }
                .forEach { offlineStore.removeTrackDownload(it.id) }
        }
        val purgedWorkAssets = mutableListOf<Pair<String, Set<String>>>()
        dlsiteCookieWrites.withLock {
            dlsiteClients.remove(id)?.close()
            evictSecret(id)
            database.withTransaction {
                dao.deleteSourceLocations(id)
                dao.deleteSource(id)
                // Reclaim works that now have no remaining locations and no offline
                // audio/attachments.
                val candidates = affectedWorks.chunked(500).flatMap { dao.unlocatedWorkIds(it) }
                for (workId in candidates) {
                    val detail = dao.details(workId) ?: continue
                    val hasPinnedAudio =
                        detail.tracks.any {
                            downloadManager.downloadIndex.getDownload(it.id)?.state ==
                                Download.STATE_COMPLETED || offlineFile(it.id) != null
                        }
                    if (!hasPinnedAudio) {
                        val assets =
                            (detail.tracks.map { it.id } +
                                    detail.attachments.map { it.id } +
                                    listOfNotNull(detail.work.coverId))
                                .toSet()
                        dao.deleteWorkLocations(workId)
                        dao.deleteWorkTracks(workId)
                        dao.deleteWorkAttachments(workId)
                        dao.deleteWorkProgress(workId)
                        dao.deleteWorkBookmarks(workId)
                        dao.deleteWork(workId)
                        purgedWorkAssets += workId to assets
                    }
                }
            }
        }
        for ((workId, assets) in purgedWorkAssets) {
            artworkStore.cancelAndPurgeWorkAssets(workId, assets)
        }
        if (purgedWorkAssets.isNotEmpty()) storageChanged()
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

    fun artwork(id: String?): File? = artworkStore.artwork(id)

    suspend fun ensureArtwork(id: String): File? = artworkStore.ensureArtwork(id)

    suspend fun previewImage(id: String): File = artworkStore.previewImage(id)

    internal suspend fun migrateImages() = artworkStore.migrateImages()

    suspend fun storageUsage(): StorageUsage = artworkStore.storageUsage()

    suspend fun clearTemporaryCache() = artworkStore.clearCaches(mediaRouting::clearStreamingCache)

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
