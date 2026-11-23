// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages cover and preview file caches, singleflight deduplication, per-asset revision signals,
 * and background initialization so cold start and UI composition never perform synchronous scans or
 * disk mtime writes on the main thread.
 */
internal class ArtworkStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: LibraryDao,
    private val offlineDir: File,
    private val storageChanged: () -> Unit,
    private val readAsset: suspend (String, Int) -> ByteArray,
    private val fetchDlsiteImage: suspend (String) -> ByteArray,
    private val fetchCatalogImage: suspend (String, String) -> ByteArray,
) {
    val artworkDir = File(context.filesDir, "artwork")
    val customArtworkDir = File(context.filesDir, "custom-artwork")
    private val artworkCache =
        ImageFileCache(
            File(context.cacheDir, "artwork"),
            64 * MIB,
            storageChanged,
            eagerInit = false,
        )
    private val previewCache =
        ImageFileCache(
            File(context.cacheDir, "previews"),
            24 * MIB,
            storageChanged,
            eagerInit = false,
        )
    val imageWrites = Mutex()
    private val imageFlights = Singleflight<String, File?>(scope, 4)
    val storageReady = CompletableDeferred<Unit>()

    // Global revision kept for full-cache reloads and integration test compatibility.
    val artworkRevision = MutableStateFlow(0)
    val artworkReload = MutableStateFlow(0)
    private val assetRevisions = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    init {
        scope.launch {
            try {
                offlineDir.mkdirs()
                artworkDir.mkdirs()
                customArtworkDir.mkdirs()
                artworkCache.initialize()
                previewCache.initialize()
                migrateImages()
            } catch (e: Exception) {
                Log.w("WhispStorage", "Image migration deferred: ${e.javaClass.simpleName}")
            } finally {
                storageReady.complete(Unit)
                artworkRevision.update { it + 1 }
            }
        }
    }

    fun assetRevision(id: String): StateFlow<Int> =
        assetRevisions.computeIfAbsent(id) { MutableStateFlow(0) }

    fun notifyAssetChanged(id: String) {
        assetRevisions[id]?.update { it + 1 }
    }

    fun notifyAllChanged() {
        artworkRevision.update { it + 1 }
    }

    private fun offlineFile(id: String): File? = File(offlineDir, id).takeIf { it.isFile }

    /**
     * Looks up a cached cover without writing disk mtime on the calling thread; disk touch is
     * scheduled asynchronously on Dispatchers.IO.
     */
    fun artwork(id: String?): File? = id?.let { asset ->
        offlineFile(asset)
            ?: File(customArtworkDir, asset).takeIf { it.isFile }
            ?: artworkCache.peek(asset)?.also { scope.launch { artworkCache.touch(asset) } }
            ?: File(artworkDir, asset).takeIf { it.isFile }
    }

    suspend fun cacheArtwork(id: String, bytes: ByteArray): File {
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
                        notifyAssetChanged(id)
                    }
                }
        }

    suspend fun ensureDlsiteImage(id: String, url: String): File? =
        imageFlights.run(id) {
            storageReady.await()
            artworkCache.get(id)
                ?: cacheArtwork(id, fetchDlsiteImage(url)).also { notifyAssetChanged(id) }
        }

    suspend fun ensureCatalogArtwork(id: String, url: String, sourceId: String): File? =
        imageFlights.run("cover:$id") {
            storageReady.await()
            artwork(id)
                ?: cacheArtwork(id, fetchCatalogImage(url, sourceId)).also {
                    notifyAssetChanged(id)
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

    suspend fun cancelAndPurgeWorkAssets(workId: String, assets: Set<String>) {
        assets.forEach { asset ->
            imageFlights.cancelAndJoin("cover:$asset")
            imageFlights.cancelAndJoin("preview:$asset")
        }
        imageWrites.withLock {
            assets.forEach { asset ->
                if (!dao.otherOfflineCover(asset, workId)) File(offlineDir, asset).delete()
                File(customArtworkDir, asset).delete()
                File(artworkDir, asset).delete()
                if (!dao.otherCover(asset, workId)) {
                    artworkCache.remove(asset)
                    previewCache.remove(asset)
                }
                notifyAssetChanged(asset)
            }
        }
    }

    /** Only indexed remote originals move to cache; custom and offline files remain durable. */
    suspend fun migrateImages() =
        withContext(Dispatchers.IO) {
            imageWrites.withLock {
                artworkDir.mkdirs()
                customArtworkDir.mkdirs()
                artworkCache.initialize()
                previewCache.initialize()
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

    suspend fun clearCaches(clearStreaming: () -> Unit) =
        withContext(Dispatchers.IO) {
            storageReady.await()
            imageFlights.joinAll()
            imageWrites.withLock {
                artworkCache.clear()
                previewCache.clear()
                File(context.cacheDir, "diagnostics").listFiles()?.forEach { it.delete() }
            }
            clearStreaming()
            artworkRevision.update { it + 1 }
            artworkReload.update { it + 1 }
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
}
