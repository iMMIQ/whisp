// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okio.BufferedSink

const val MIB = 1024L * 1024

interface RemoteReadHandle : Closeable {
    val length: Long

    fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int
}

/** 256 KiB sliding read-ahead window shared by SMB and NFS handles to eliminate small-chunk RTT. */
class BufferedReadHandle(
    private val delegate: RemoteReadHandle,
    windowSize: Int = 256 * 1024,
) : RemoteReadHandle {
    private val window = ByteArray(windowSize)
    private var windowStart = -1L
    private var windowAvailable = 0

    override val length: Long
        get() = delegate.length

    override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int {
        require(
            offset >= 0 && bufferOffset >= 0 && count >= 0 && bufferOffset <= buffer.size - count,
        )
        if (count == 0) return 0
        val total = length
        if (offset >= total) return -1
        val maxReadable = minOf(count.toLong(), total - offset).toInt()
        if (
            windowAvailable > 0 && offset >= windowStart && offset - windowStart < windowAvailable
        ) {
            val cachedOffset = (offset - windowStart).toInt()
            val copied = minOf(maxReadable, windowAvailable - cachedOffset)
            window.copyInto(buffer, bufferOffset, cachedOffset, cachedOffset + copied)
            return copied
        }
        // Large caller buffers bypass the sliding window to avoid an extra copy.
        if (maxReadable >= window.size) {
            return delegate.read(buffer, offset, bufferOffset, maxReadable)
        }
        val fetchSize = minOf(window.size.toLong(), total - offset).toInt()
        val n = delegate.read(window, offset, 0, fetchSize)
        if (n <= 0) return n
        windowStart = offset
        windowAvailable = n
        val copied = minOf(maxReadable, n)
        window.copyInto(buffer, bufferOffset, 0, copied)
        return copied
    }

    override fun close() {
        windowStart = -1L
        windowAvailable = 0
        delegate.close()
    }
}

fun RemoteReadHandle.readFull(limit: Int, errorMessage: String = "文件读取中断"): ByteArray {
    require(limit >= 0 && length in 0..limit.toLong()) { "文件过大" }
    val bytes = ByteArray(length.toInt())
    var used = 0
    while (used < bytes.size) {
        val n = read(bytes, used.toLong(), used, bytes.size - used)
        if (n <= 0) throw IOException(errorMessage)
        used += n
    }
    return bytes
}

fun RemoteReadHandle.copyToSink(
    sink: BufferedSink,
    limit: Int,
    errorMessage: String = "文件读取中断",
): Long {
    require(limit >= 0 && length in 0..limit.toLong()) { "文件过大" }
    val buffer = ByteArray(64 * 1024)
    var offset = 0L
    val total = length
    while (offset < total) {
        val toRead = minOf(buffer.size.toLong(), total - offset).toInt()
        val n = read(buffer, offset, 0, toRead)
        if (n <= 0) throw IOException(errorMessage)
        sink.write(buffer, 0, n)
        offset += n
    }
    return offset
}

fun RemoteReadHandle.readRange(offset: Long, count: Int): ByteArray {
    val size = minOf(count.toLong(), (length - offset).coerceAtLeast(0)).toInt()
    val bytes = ByteArray(size)
    var used = 0
    while (used < bytes.size) {
        val n = read(bytes, offset + used, used, bytes.size - used)
        if (n <= 0) break
        used += n
    }
    return bytes.copyOf(used)
}

/** Generic level-order BFS directory walker with bounded concurrency shared across protocols. */
suspend fun <D, E> walkTree(
    root: D,
    concurrency: Int = 4,
    listBatch: suspend (List<D>) -> List<Pair<D, List<E>>>,
    onDirectory: (parent: D, entries: List<E>, enqueue: (D) -> Unit) -> Unit,
    onLevel: (directories: Int) -> Unit = {},
) {
    var pending = listOf(root)
    var directories = 0
    while (pending.isNotEmpty()) {
        val nextLevel = mutableListOf<D>()
        for (batch in pending.chunked(concurrency.coerceAtLeast(1))) {
            currentCoroutineContext().ensureActive()
            val listings =
                if (concurrency <= 1 || batch.size == 1) listBatch(batch)
                else
                    coroutineScope {
                        listBatch(batch)
                    }
            for ((parent, entries) in listings) {
                currentCoroutineContext().ensureActive()
                directories++
                onDirectory(parent, entries) { nextLevel += it }
            }
        }
        onLevel(directories)
        pending = nextLevel
    }
}

/**
 * Work-stealing concurrent directory walker with fixed [concurrency] worker coroutines. Eliminates
 * level-batch head-of-line blocking where fast directories wait on a single slow directory.
 */
suspend fun <D, E> walkTreeConcurrent(
    root: D,
    concurrency: Int = 4,
    listOne: suspend (workerIndex: Int, dir: D) -> List<E>,
    onDirectory: (parent: D, entries: List<E>, enqueue: (D) -> Unit) -> Unit,
    onProgress: (directories: Int) -> Unit = {},
) = coroutineScope {
    val workers = concurrency.coerceAtLeast(1)
    val queue = Channel<D>(Channel.UNLIMITED)
    val inFlight = AtomicInteger(1)
    val directories = AtomicInteger(0)
    val lock = Any()
    queue.trySend(root)
    try {
        val jobs =
            List(workers) { workerIndex ->
                launch(Dispatchers.IO) {
                    for (dir in queue) {
                        currentCoroutineContext().ensureActive()
                        val entries = listOne(workerIndex, dir)
                        currentCoroutineContext().ensureActive()
                        val count =
                            synchronized(lock) {
                                onDirectory(dir, entries) { child ->
                                    inFlight.incrementAndGet()
                                    queue.trySend(child)
                                }
                                directories.incrementAndGet()
                            }
                        if (count % 16 == 0) onProgress(count)
                        if (inFlight.decrementAndGet() == 0) queue.close()
                    }
                }
            }
        jobs.joinAll()
        onProgress(directories.get())
    } finally {
        queue.close()
    }
}

suspend fun <D, E> parallelList(
    batch: List<D>,
    listOne: suspend (index: Int, dir: D) -> List<E>,
): List<Pair<D, List<E>>> = coroutineScope {
    batch
        .mapIndexed { index, dir -> async(Dispatchers.IO) { dir to listOne(index, dir) } }
        .awaitAll()
}

@androidx.annotation.OptIn(UnstableApi::class)
class RemoteFileDataSource(
    private val protocol: String = "网络",
    private val readerFor: (String) -> RemoteReadHandle,
) : BaseDataSource(true) {
    private var reader: RemoteReadHandle? = null
    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            reader = readerFor(dataSpec.uri.toString())
            if (dataSpec.position > reader!!.length)
                throw DataSourceException(
                    "播放位置超出文件长度",
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                )
            spec = dataSpec
            position = dataSpec.position
            remaining = reader!!.length - position
            if (dataSpec.length != C.LENGTH_UNSET.toLong())
                remaining = minOf(remaining, dataSpec.length)
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count =
            reader!!.read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
        if (count <= 0) throw IOException("$protocol 文件读取中断")
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            spec = null
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}

fun boundedRead(stream: InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    while (true) {
        val count = stream.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        output.write(buffer, 0, count)
        require(output.size() <= limit) { "文件过大" }
    }
    return output.toByteArray()
}

fun copyBounded(stream: InputStream, sink: BufferedSink, limit: Int): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val remaining = (limit.toLong() - total + 1L).coerceAtMost(buffer.size.toLong()).toInt()
        val count = stream.read(buffer, 0, remaining)
        if (count < 0) break
        total += count
        require(total <= limit) { "文件过大" }
        sink.write(buffer, 0, count)
    }
    return total
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
