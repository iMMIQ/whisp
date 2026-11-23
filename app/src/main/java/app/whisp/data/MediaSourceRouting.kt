// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Owns Media3 DataSource routing (Local/HTTP/WebDAV/SMB/NFS/ASMR.ONE/DLsite), streaming/download
 * SimpleCaches, and DownloadManager observation.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaSourceRouting(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: LibraryDao,
    private val http: OkHttpClient,
    private val onlineHttp: OkHttpClient,
    private val smb: SmbClient,
    private val nfs: NfsClient,
    private val headersFor: (String) -> Map<String, String>,
    private val sourceSecret: (SourceEntity) -> String,
    private val dlsiteResource: suspend (String, String) -> DlsiteMediaResource,
    private val onDlsiteLoginRequired: (String, DlsiteAccountClient?) -> Unit,
    private val storageChanged: () -> Unit,
    private val streamingCacheMiBUpdates: Flow<Int>,
    private val messages: MutableSharedFlow<String>,
) {
    private val streamingEvictor = AdjustableCacheEvictor(Long.MAX_VALUE, storageChanged)
    private val mediaDatabase = StandaloneDatabaseProvider(context)
    val downloadCache =
        SimpleCache(File(context.filesDir, "offline/audio"), NoOpCacheEvictor(), mediaDatabase)
    val streamingCache =
        SimpleCache(File(context.cacheDir, "streaming"), streamingEvictor, mediaDatabase)

    private val httpFactory =
        ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)),
        ) { spec ->
            spec.withRequestHeaders(spec.httpRequestHeaders + headersFor(spec.uri.toString()))
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
        val dlsiteData = DlsiteDataSource(dlsiteResource, onDlsiteLoginRequired)
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

    val completedDownloads = MutableStateFlow<Set<String>>(emptySet())
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val downloadRefresh = Channel<Unit>(Channel.CONFLATED)
    private val activeDownloads = MutableStateFlow(false)

    init {
        scope.launch {
            streamingCacheMiBUpdates.collect { streamingEvictor.resize(streamingCache, it * MIB) }
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

    fun clearStreamingCache() {
        streamingCache.keys.toList().forEach { streamingCache.removeResource(it) }
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
}
