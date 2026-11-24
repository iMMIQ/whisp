// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.TreeSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Same LRU policy as Media3, using only Cache's public API and an internal span index lock so
 * callers never depend on SimpleCache's private monitor object.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AdjustableCacheEvictor(
    limit: Long,
    private val changed: () -> Unit = {},
) : CacheEvictor {
    private val lock = Any()
    @Volatile private var limit: Long = limit.coerceAtLeast(0)
    private val spans =
        TreeSet<CacheSpan>(
            compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position },
        )
    private var bytes = 0L

    fun resize(cache: Cache, newLimit: Long) {
        limit = newLimit.coerceAtLeast(0)
        evict(cache, 0)
    }

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        evict(cache, length.coerceAtLeast(0))
        changed()
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lock) { if (spans.add(span)) bytes += span.length }
        evict(cache, 0)
        changed()
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        val removed =
            synchronized(lock) { spans.remove(span).also { if (it) bytes -= span.length } }
        if (removed) changed()
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        synchronized(lock) {
            spans.remove(oldSpan)
            spans.add(newSpan)
        }
    }

    private fun evict(cache: Cache, incoming: Long) {
        val skipped = mutableSetOf<CacheSpan>()
        while (true) {
            val candidate =
                synchronized(lock) {
                    if (bytes + incoming <= limit) null else spans.firstOrNull { it !in skipped }
                } ?: break
            cache.removeSpan(candidate)
            val stillPresent = synchronized(lock) { candidate in spans }
            if (stillPresent) skipped += candidate
        }
    }
}

/**
 * Owns lazy Media3 DataSource routing, streaming/download SimpleCaches, and DownloadManager state.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaSourceRouting(
    context: Context,
    scope: CoroutineScope,
    private val sources: SourceRegistry,
    private val headersFor: (String) -> Map<String, String>,
    private val storageChanged: () -> Unit,
    streamingCacheMiBUpdates: Flow<Int>,
    wifiOnlyDownloadsUpdates: Flow<Boolean> = flowOf(true),
    private val messages: MutableSharedFlow<String>,
) {
    companion object {
        internal fun downloadRequirements(wifiOnly: Boolean): Requirements =
            Requirements(
                if (wifiOnly) Requirements.DEVICE_STORAGE_NOT_LOW or Requirements.NETWORK_UNMETERED
                else Requirements.DEVICE_STORAGE_NOT_LOW,
            )
    }

    @Volatile private var currentWifiOnlyDownloads: Boolean = true
    private val streamingEvictor = AdjustableCacheEvictor(Long.MAX_VALUE, storageChanged)
    private val mediaDatabase by lazy { StandaloneDatabaseProvider(context) }
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(context.filesDir, "offline/audio"), NoOpCacheEvictor(), mediaDatabase)
    }
    val streamingCache: SimpleCache by lazy {
        SimpleCache(File(context.cacheDir, "streaming"), streamingEvictor, mediaDatabase)
    }

    // Lazy scheme-dispatched DataSource: instantiates only the single backend needed for open().
    private val networkFactory = DataSource.Factory {
        object : DataSource {
            private val listeners = mutableListOf<TransferListener>()
            private var active: DataSource? = null

            override fun addTransferListener(listener: TransferListener) {
                listeners += listener
                active?.addTransferListener(listener)
            }

            override fun open(spec: DataSpec): Long {
                val created = sources.createDataSourceForScheme(spec.uri.scheme, headersFor)
                listeners.forEach(created::addTransferListener)
                active = created
                return created.open(spec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int) =
                active?.read(buffer, offset, length) ?: -1

            override fun getUri() = active?.uri

            override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()

            override fun close() {
                val current = active
                active = null
                current?.close()
            }
        }
    }

    private val localDirectFactory: DataSource.Factory by lazy {
        androidx.media3.datasource.DefaultDataSource.Factory(context)
    }

    private val streamingFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(streamingCache)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamingCache).setFragmentSize(16L * MIB),
            )
            .setUpstreamDataSourceFactory(networkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val cachedPlaybackFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(downloadCache)
            // Only DownloadManager writes pinned copies so paused playback never blocks downloads.
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(streamingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Routes local content:// and file:// media directly to DefaultDataSource so local and
     * offline-asset playback never writes redundant copies into the online streaming cache.
     */
    val playbackFactory: DataSource.Factory by lazy {
        DataSource.Factory {
            object : DataSource {
                private val listeners = mutableListOf<TransferListener>()
                private var active: DataSource? = null

                override fun addTransferListener(listener: TransferListener) {
                    listeners += listener
                    active?.addTransferListener(listener)
                }

                override fun open(spec: DataSpec): Long {
                    val scheme = spec.uri.scheme?.lowercase()
                    val delegate =
                        if (scheme == "file" || scheme == "content")
                            localDirectFactory.createDataSource()
                        else cachedPlaybackFactory.createDataSource()
                    listeners.forEach(delegate::addTransferListener)
                    active = delegate
                    return delegate.open(spec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int) =
                    active?.read(buffer, offset, length) ?: -1

                override fun getUri() = active?.uri

                override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()

                override fun close() {
                    val current = active
                    active = null
                    current?.close()
                }
            }
        }
    }

    /**
     * Reuses already-cached streaming fragments during offline downloads before hitting the network
     * upstream, avoiding duplicate data transfer after previewing a work.
     */
    private val downloadUpstreamFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(streamingCache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(networkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val completedDownloads = MutableStateFlow<Set<String>>(emptySet())
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    private val downloadRefresh = Channel<Unit>(Channel.CONFLATED)
    private val activeDownloads = MutableStateFlow(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    val downloadManager: DownloadManager by lazy {
        val executor =
            ThreadPoolExecutor(
                    2,
                    2,
                    30L,
                    TimeUnit.SECONDS,
                    LinkedBlockingQueue(),
                )
                .apply { allowCoreThreadTimeOut(true) }
        val manager =
            DownloadManager(
                context,
                mediaDatabase,
                downloadCache,
                downloadUpstreamFactory,
                executor,
            )
        val configure = {
            manager.maxParallelDownloads = 2
            manager.requirements = downloadRequirements(currentWifiOnlyDownloads)
            manager.addListener(
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

                    override fun onDownloadRemoved(
                        manager: DownloadManager,
                        download: Download,
                    ) {
                        downloadRefresh.trySend(Unit)
                        storageChanged()
                    }
                },
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) configure()
        else mainHandler.post(configure)
        manager
    }

    init {
        scope.launch {
            streamingCacheMiBUpdates.collect { streamingEvictor.resize(streamingCache, it * MIB) }
        }
        scope.launch(Dispatchers.Main.immediate) {
            wifiOnlyDownloadsUpdates.distinctUntilChanged().collect { wifiOnly ->
                currentWifiOnlyDownloads = wifiOnly
                downloadManager.requirements = downloadRequirements(wifiOnly)
            }
        }
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
        // Warm SimpleCaches off the main thread, then instantiate DownloadManager on Main Looper.
        scope.launch(Dispatchers.IO) {
            downloadCache
            streamingCache
            withContext(Dispatchers.Main.immediate) { downloadManager }
            downloadRefresh.trySend(Unit)
        }
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

    fun isCompleted(trackId: String): Boolean =
        downloadManager.downloadIndex.getDownload(trackId)?.state == Download.STATE_COMPLETED

    fun snapshotCompletedDownloads(): Set<String> {
        val result = HashSet<String>()
        downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) result += cursor.download.request.id
        }
        return result
    }

    fun completedUri(trackId: String): String? =
        downloadManager.downloadIndex
            .getDownload(trackId)
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.request
            ?.uri
            ?.toString()

    fun completedUrisSnapshot(): Map<String, String> {
        val memory = downloads.value
        if (memory.isNotEmpty()) {
            return buildMap {
                for ((id, download) in memory) {
                    if (download.state == Download.STATE_COMPLETED) {
                        put(id, download.request.uri.toString())
                    }
                }
            }
        }
        val result = HashMap<String, String>()
        downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                result[d.request.id] = d.request.uri.toString()
            }
        }
        return result
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
