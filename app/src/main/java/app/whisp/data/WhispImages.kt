// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath

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
    private val settings: SettingsStore,
    private val sources: SourceRegistry,
    private val offlineDir: File,
    private val customArtworkDir: File,
    private val readAsset: suspend (String, Int) -> ByteArray,
    private val storageChanged: () -> Unit,
) {
    val cacheEpoch = MutableStateFlow(0)

    val diskCache: DiskCache =
        DiskCache.Builder()
            .directory(File(context.cacheDir, "images").toOkioPath())
            .maxSizeBytes(88L * MIB)
            .build()

    val imageLoader: ImageLoader =
        ImageLoader.Builder(context)
            .diskCache(diskCache)
            .components {
                add(Keyer<WhispImage> { data, _ -> "${data.diskKey}#${data.epoch}" })
                add(WhispImageFetcherFactory())
            }
            .build()

    init {
        scope.launch(Dispatchers.IO) {
            offlineDir.mkdirs()
            customArtworkDir.mkdirs()
            runCatching { migrateLegacyOnce() }
                .onFailure {
                    Log.w(
                        "WhispStorage",
                        "Legacy image migration skipped: ${it.javaClass.simpleName}",
                    )
                }
        }
    }

    private suspend fun migrateLegacyOnce() {
        if (settings.settingsUpdates.first().legacyImagesMigrated) return
        val legacyDir = File(context.filesDir, "artwork")
        if (legacyDir.isDirectory) {
            for (work in dao.allWorks()) {
                val id = work.coverId ?: continue
                val old = File(legacyDir, id)
                if (old.isFile && id == stableId("${work.id}|custom-cover")) {
                    val target = File(customArtworkDir, id)
                    if (!target.isFile) atomicFile(target, old.readBytes())
                }
            }
            legacyDir.deleteRecursively()
        }
        File(context.cacheDir, "artwork").deleteRecursively()
        File(context.cacheDir, "previews").deleteRecursively()
        settings.update { it.copy(legacyImagesMigrated = true) }
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
            storageChanged()
        } catch (_: Exception) {
            editor.abort()
        }
    }

    fun removeFromMemoryAndDisk(id: String) {
        diskCache.remove("img:$id")
        imageLoader.memoryCache?.clear()
    }

    suspend fun purgeWorkAssets(workId: String, assets: Set<String>) =
        withContext(Dispatchers.IO) {
            for (asset in assets) {
                if (!dao.otherOfflineCover(asset, workId)) File(offlineDir, asset).delete()
                if (!dao.otherCover(asset, workId)) {
                    File(customArtworkDir, asset).delete()
                    diskCache.remove("img:$asset")
                }
            }
            imageLoader.memoryCache?.clear()
            storageChanged()
        }

    suspend fun clearCaches(clearStreaming: () -> Unit) =
        withContext(Dispatchers.IO) {
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
                streaming = size(File(context.cacheDir, "streaming")),
                offline = size(File(context.filesDir, "offline/audio")) + size(offlineDir),
                customArtwork = size(customArtworkDir),
                library =
                    size(File(context.dataDir, "databases")) +
                        size(File(context.filesDir, "datastore")) +
                        size(File(context.dataDir, "shared_prefs")) +
                        size(context.noBackupFilesDir),
            )
        }

    /** Generates a <=64 KiB 320px WebP thumbnail for SystemUI notifications & MediaBrowser. */
    fun thumbnailBytes(id: String?): ByteArray? {
        if (id == null) return null
        durableFile(id)?.let {
            return decodeThumbnail(it.path)
        }
        return diskCache.openSnapshot("img:$id")?.use { snapshot ->
            decodeThumbnail(snapshot.data.toFile().path)
        }
    }

    private fun decodeThumbnail(path: String): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 320) {
            options.inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
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
            diskCache.openSnapshot(data.diskKey)?.let { snapshot ->
                return SourceFetchResult(
                    source =
                        ImageSource(
                            file = snapshot.data,
                            fileSystem = diskCache.fileSystem,
                            diskCacheKey = data.diskKey,
                            closeable = snapshot,
                        ),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
            val bytes =
                when (data) {
                    is WhispImage.Official -> sources.fetchDlsiteBytes(data.url, 16 * 1024 * 1024)
                    is WhispImage.Catalog ->
                        sources.fetchCatalogBytes(data.url, data.sourceId, 16 * 1024 * 1024)
                    is WhispImage.Asset -> readAsset(data.id, 64 * 1024 * 1024)
                }
            if (bytes.isEmpty()) throw IOException("图片不可读")
            val editor = diskCache.openEditor(data.diskKey)
            if (editor != null) {
                val snapshot =
                    try {
                        diskCache.fileSystem.write(editor.data) { write(bytes) }
                        editor.commitAndOpenSnapshot()
                    } catch (e: Exception) {
                        editor.abort()
                        throw e
                    }
                if (snapshot != null) {
                    storageChanged()
                    return SourceFetchResult(
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
            return SourceFetchResult(
                source =
                    ImageSource(source = Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
    }
}
