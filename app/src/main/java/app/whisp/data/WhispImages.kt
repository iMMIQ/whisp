// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer

/** Typed Coil 3 image model replacing the custom ArtworkStore / ImageFileCache / Singleflight. */
sealed interface WhispImage {
    val id: String
    val epoch: Int
    val diskKey: String
        get() = "img:$id"

    data class Asset(override val id: String, override val epoch: Int = 0) : WhispImage

    data class Catalog(
        override val id: String,
        val url: String,
        val sourceId: String,
        override val epoch: Int = 0,
    ) : WhispImage

    data class Official(
        override val id: String,
        val url: String,
        override val epoch: Int = 0,
    ) : WhispImage
}

data class StorageUsage(
    val images: Long = 0,
    val streaming: Long = 0,
    val offline: Long = 0,
    val customArtwork: Long = 0,
    val library: Long = 0,
) {
    val cache
        get() = images + streaming
}

/**
 * Owns Coil 3 ImageLoader + DiskCache (88 MiB), durable custom/offline artwork lookup, cache
 * clearing, storage accounting, and notification thumbnail generation.
 */
class WhispImages(
    private val context: Context,
    scope: CoroutineScope,
    private val dao: LibraryDao,
    private val sources: SourceRegistry,
    private val offlineDir: File,
    private val customArtworkDir: File,
    private val readAsset: suspend (String, Int) -> ByteArray,
    private val streamAsset: suspend (String, BufferedSink, Int) -> Long,
    private val streamingCacheBytes: () -> Long,
    private val downloadCacheBytes: () -> Long,
    private val storageChanged: () -> Unit,
) {
    val cacheEpoch = MutableStateFlow(0)

    private val thumbnailCache = LruCache<String, ByteArray>(32)

    private class FetchLockEntry {
        val mutex = Mutex()
        var waiters = 0
    }

    private val fetchLocks = mutableMapOf<String, FetchLockEntry>()

    private suspend fun <T> withFetchLock(key: String, block: suspend () -> T): T {
        val entry =
            synchronized(fetchLocks) {
                fetchLocks.getOrPut(key) { FetchLockEntry() }.also { it.waiters++ }
            }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            synchronized(fetchLocks) {
                entry.waiters--
                if (entry.waiters == 0 && fetchLocks[key] === entry) fetchLocks.remove(key)
            }
        }
    }

    val diskCache: DiskCache by lazy {
        DiskCache.Builder()
            .directory(File(context.cacheDir, "images").toOkioPath())
            .maxSizeBytes(88L * MIB)
            .build()
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .diskCache { diskCache }
            .components {
                add(Keyer<WhispImage> { data, _ -> "${data.diskKey}#${data.epoch}" })
                add(WhispImageFetcherFactory())
            }
            .build()
    }

    init {
        scope.launch(Dispatchers.IO) {
            offlineDir.mkdirs()
            customArtworkDir.mkdirs()
        }
    }

    fun durableFile(id: String?): File? = id?.let { asset ->
        File(offlineDir, asset).takeIf { it.isFile }
            ?: File(customArtworkDir, asset).takeIf { it.isFile }
    }

    fun putCachedBytes(id: String, bytes: ByteArray) {
        val editor = diskCache.openEditor("img:$id") ?: return
        try {
            diskCache.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()
            thumbnailCache.remove(id)
            storageChanged()
        } catch (_: Exception) {
            editor.abort()
        }
    }

    private fun evictMemoryKey(id: String) {
        thumbnailCache.remove(id)
        val mem = imageLoader.memoryCache ?: return
        val prefix = "img:$id#"
        val exact = MemoryCache.Key("$prefix${cacheEpoch.value}")
        mem.remove(exact)
        mem.keys.filter { it.key.startsWith(prefix) }.forEach(mem::remove)
    }

    fun removeFromMemoryAndDisk(id: String) {
        diskCache.remove("img:$id")
        evictMemoryKey(id)
    }

    suspend fun purgeWorkAssets(workId: String, assets: Set<String>) =
        purgeBatch(listOf(workId to assets))

    suspend fun purgeBatch(workAssets: List<Pair<String, Set<String>>>) =
        withContext(Dispatchers.IO) {
            if (workAssets.isEmpty()) return@withContext
            val removedIds = mutableSetOf<String>()
            for ((workId, assets) in workAssets) {
                for (asset in assets) {
                    if (!dao.otherOfflineCover(asset, workId)) File(offlineDir, asset).delete()
                    if (!dao.otherCover(asset, workId)) {
                        File(customArtworkDir, asset).delete()
                        diskCache.remove("img:$asset")
                        removedIds += asset
                    }
                }
            }
            removedIds.forEach(::evictMemoryKey)
            storageChanged()
        }

    suspend fun clearCaches(clearStreaming: () -> Unit) =
        withContext(Dispatchers.IO) {
            thumbnailCache.evictAll()
            imageLoader.memoryCache?.clear()
            diskCache.clear()
            File(context.cacheDir, "diagnostics").listFiles()?.forEach { it.delete() }
            clearStreaming()
            cacheEpoch.update { it + 1 }
            storageChanged()
        }

    suspend fun storageUsage(): StorageUsage =
        withContext(Dispatchers.IO) {
            fun size(file: File): Long =
                if (file.isFile) file.length() else file.listFiles()?.sumOf { size(it) } ?: 0L
            StorageUsage(
                images = diskCache.size,
                streaming = streamingCacheBytes(),
                offline = downloadCacheBytes() + size(offlineDir),
                customArtwork = size(customArtworkDir),
                library =
                    size(File(context.dataDir, "databases")) +
                        size(File(context.filesDir, "datastore")) +
                        size(File(context.dataDir, "shared_prefs")) +
                        size(context.noBackupFilesDir),
            )
        }

    /**
     * Generates a cached <=64 KiB 320px WebP thumbnail for SystemUI notifications & MediaBrowser.
     */
    fun thumbnailBytes(id: String?): ByteArray? {
        if (id == null) return null
        thumbnailCache[id]?.let {
            return it
        }
        val encoded =
            durableFile(id)?.let { decodeThumbnail(it.path) }
                ?: diskCache.openSnapshot("img:$id")?.use { snapshot ->
                    decodeThumbnail(snapshot.data.toFile().path)
                }
        if (encoded != null) thumbnailCache.put(id, encoded)
        return encoded
    }

    private fun decodeThumbnail(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        val bitmap =
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > 320 && w > 0 && h > 0) {
                        val scale = 320f / longest.toFloat()
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }
                .getOrNull() ?: return null
        return try {
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format =
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(format, 80, output)) null
            else output.toByteArray().takeIf { it.size <= 64 * 1024 }
        } finally {
            bitmap.recycle()
        }
    }

    private inner class WhispImageFetcherFactory : Fetcher.Factory<WhispImage> {
        override fun create(data: WhispImage, options: Options, imageLoader: ImageLoader): Fetcher =
            Fetcher {
                fetch(data)
            }

        private suspend fun fetch(data: WhispImage): FetchResult {
            durableFile(data.id)?.let { file ->
                return SourceFetchResult(
                    source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
            openCached(data.diskKey)?.let {
                return it
            }
            return withFetchLock(data.diskKey) {
                durableFile(data.id)?.let { file ->
                    return@withFetchLock SourceFetchResult(
                        source =
                            ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                        mimeType = null,
                        dataSource = DataSource.DISK,
                    )
                }
                openCached(data.diskKey)?.let {
                    return@withFetchLock it
                }
                val editor = diskCache.openEditor(data.diskKey)
                if (editor != null) {
                    val snapshot =
                        try {
                            val written =
                                withContext(Dispatchers.IO) {
                                    diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
                                        streamDataToSink(data, sink)
                                    }
                                }
                            if (written <= 0L) throw IOException("图片不可读")
                            editor.commitAndOpenSnapshot()
                        } catch (e: Exception) {
                            editor.abort()
                            throw e
                        }
                    if (snapshot != null) {
                        storageChanged()
                        return@withFetchLock SourceFetchResult(
                            source =
                                ImageSource(
                                    file = snapshot.data,
                                    fileSystem = diskCache.fileSystem,
                                    diskCacheKey = data.diskKey,
                                    closeable = snapshot,
                                ),
                            mimeType = null,
                            dataSource = DataSource.NETWORK,
                        )
                    }
                }
                val bytes =
                    when (data) {
                        is WhispImage.Official ->
                            sources.fetchDlsiteBytes(data.url, 16 * 1024 * 1024)
                        is WhispImage.Catalog ->
                            sources.fetchCatalogBytes(data.url, data.sourceId, 16 * 1024 * 1024)
                        is WhispImage.Asset -> readAsset(data.id, 64 * 1024 * 1024)
                    }
                if (bytes.isEmpty()) throw IOException("图片不可读")
                SourceFetchResult(
                    source =
                        ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
                    mimeType = null,
                    dataSource = DataSource.NETWORK,
                )
            }
        }

        private fun openCached(diskKey: String): SourceFetchResult? =
            diskCache.openSnapshot(diskKey)?.let { snapshot ->
                SourceFetchResult(
                    source =
                        ImageSource(
                            file = snapshot.data,
                            fileSystem = diskCache.fileSystem,
                            diskCacheKey = diskKey,
                            closeable = snapshot,
                        ),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }

        private suspend fun streamDataToSink(data: WhispImage, sink: BufferedSink): Long =
            when (data) {
                is WhispImage.Official ->
                    sources.streamDlsiteToSink(data.url, sink, 16 * 1024 * 1024)
                is WhispImage.Catalog ->
                    sources.streamCatalogToSink(data.url, data.sourceId, sink, 16 * 1024 * 1024)
                is WhispImage.Asset -> streamAsset(data.id, sink, 64 * 1024 * 1024)
            }
    }
}
