// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.TreeSet

const val MIB = 1024L * 1024

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
        synchronized(lock) {
            if (spans.add(span)) bytes += span.length
        }
        evict(cache, 0)
        changed()
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        val removed =
            synchronized(lock) {
                spans.remove(span).also { if (it) bytes -= span.length }
            }
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

internal fun atomicFile(file: File, bytes: ByteArray) {
    val parent = file.absoluteFile.parentFile!!
    Files.createDirectories(parent.toPath())
    val temp = Files.createTempFile(parent.toPath(), file.name + ".", ".tmp")
    try {
        Files.write(temp, bytes)
        Files.move(temp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/**
 * Cache owns only its dedicated directory, never settings, custom art or downloads. Maintains an
 * in-memory LRU index so put/size/peek avoid full-directory scans and UI reads never write mtimes
 * synchronously on the main thread.
 */
internal class ImageFileCache(
    val directory: File,
    private val limit: Long,
    private val changed: () -> Unit = {},
    eagerInit: Boolean = true,
) {
    private val entries = LinkedHashMap<String, Long>(64, 0.75f, true)
    private var totalBytes = 0L
    @Volatile private var initialized = false

    init {
        if (eagerInit) initialize()
    }

    @Synchronized
    fun initialize() {
        if (initialized) return
        directory.mkdirs()
        entries.clear()
        totalBytes = 0L
        val existing = directory.listFiles().orEmpty()
        val regular = mutableListOf<File>()
        for (file in existing) {
            if (file.name.endsWith(".tmp")) file.delete() else if (file.isFile) regular += file
        }
        regular.sortBy { it.lastModified() }
        for (file in regular) {
            val length = file.length()
            entries[file.name] = length
            totalBytes += length
        }
        initialized = true
        trim()
    }

    /** Fast lookup without synchronous disk mtime writes (safe for main-thread reads if needed). */
    fun peek(id: String): File? {
        val file = File(directory, id)
        if (!file.isFile) {
            if (initialized) {
                synchronized(this) {
                    entries.remove(id)?.let { totalBytes = (totalBytes - it).coerceAtLeast(0L) }
                }
            }
            return null
        }
        if (initialized) {
            synchronized(this) {
                if (entries.containsKey(id)) {
                    // Accessing under accessOrder=true promotes the entry in memory.
                    entries[id]
                } else {
                    val length = file.length()
                    entries[id] = length
                    totalBytes += length
                }
            }
        }
        return file
    }

    @Synchronized
    fun get(id: String): File? {
        if (!initialized) initialize()
        val file = File(directory, id)
        if (!file.isFile) {
            entries.remove(id)?.let { totalBytes = (totalBytes - it).coerceAtLeast(0L) }
            return null
        }
        val previous = entries[id]
        if (previous == null) {
            val length = file.length()
            entries[id] = length
            totalBytes += length
        }
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > 60_000) file.setLastModified(now)
        return file
    }

    fun touch(id: String) {
        val file = File(directory, id)
        if (!file.isFile) return
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > 60_000) file.setLastModified(now)
    }

    @Synchronized
    fun put(id: String, bytes: ByteArray): File {
        if (!initialized) initialize()
        require(bytes.size <= limit) { "图片过大" }
        val file = File(directory, id)
        atomicFile(file, bytes)
        file.setLastModified(System.currentTimeMillis())
        val previous = entries.remove(id)
        if (previous != null) totalBytes = (totalBytes - previous).coerceAtLeast(0L)
        val length = bytes.size.toLong()
        entries[id] = length
        totalBytes += length
        trim(id)
        changed()
        return file
    }

    @Synchronized
    fun clear() {
        if (!initialized) directory.mkdirs()
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        entries.clear()
        totalBytes = 0L
        initialized = true
        changed()
    }

    @Synchronized
    fun remove(id: String) {
        val removed = File(directory, id).delete()
        entries.remove(id)?.let { totalBytes = (totalBytes - it).coerceAtLeast(0L) }
        if (removed) changed()
    }

    @Synchronized
    fun size(): Long {
        if (!initialized) initialize()
        return totalBytes
    }

    @Synchronized
    private fun trim(keepId: String? = null) {
        val iterator = entries.entries.iterator()
        while (totalBytes > limit && iterator.hasNext()) {
            val (id, length) = iterator.next()
            if (id == keepId) continue
            val file = File(directory, id)
            if (!file.exists() || file.delete()) {
                iterator.remove()
                totalBytes = (totalBytes - length).coerceAtLeast(0L)
            }
        }
    }
}

internal object ImageOptimizer {
    @Synchronized
    fun optimize(original: ByteArray, longEdge: Int = 1600): ByteArray {
        // Preserve small covers byte-for-byte; never recompress an optimized cache entry.
        if (original.size <= 256 * 1024) return original
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return original
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / sample > longEdge * 2) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(
                original,
                0,
                original.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return original
        var scaled: Bitmap? = null
        try {
            val ratio = minOf(1f, longEdge.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap =
                if (ratio < 1f)
                    decoded
                        .scale(
                            (decoded.width * ratio).toInt().coerceAtLeast(1),
                            (decoded.height * ratio).toInt().coerceAtLeast(1),
                            true,
                        )
                        .also { scaled = it }
                else decoded
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format =
                if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(format, 88, output)) return original
            return output.toByteArray().takeIf { it.size < original.size } ?: original
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded.recycle()
        }
    }
}

data class StorageUsage(
    val artwork: Long = 0,
    val previews: Long = 0,
    val streaming: Long = 0,
    val offline: Long = 0,
    val customArtwork: Long = 0,
    val library: Long = 0,
) {
    val cache
        get() = artwork + previews + streaming
}
