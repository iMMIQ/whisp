// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.encodeUtf8

data class SmbEntry(val name: String, val directory: Boolean, val size: Long, val modifiedMs: Long)

interface SmbSession : Closeable {
    fun list(path: String): List<SmbEntry>

    fun open(path: String): RemoteReadHandle
}

internal class NativeSmbSession(
    private val address: SmbAddress,
    account: SmbAccount,
    password: String,
) : SmbSession {
    companion object {
        private const val WINDOW_SIZE = 256 * 1024
        private const val MAX_NATIVE_READ = 1024 * 1024
    }

    private var connection: Long = run {
        require(account.user.isNotEmpty() || password.isEmpty()) { "请填写账号" }
        val host = address.host
        val hostPort =
            if (address.port == 445) host
            else if (':' in host && !host.startsWith('[')) "[$host]:${address.port}"
            else "$host:${address.port}"
        val passBytes = password.toByteArray(Charsets.UTF_8)
        try {
            SmbNative.connect(
                hostPort.toByteArray(Charsets.UTF_8),
                address.share.toByteArray(Charsets.UTF_8),
                account.user.toByteArray(Charsets.UTF_8),
                account.domain.toByteArray(Charsets.UTF_8),
                passBytes,
                address.encrypt,
            )
        } finally {
            passBytes.fill(0)
        }
    }

    private fun current(): Long = connection.takeIf { it != 0L } ?: throw IOException("SMB：连接已关闭")

    private fun encodedPath(relative: String): ByteArray =
        address.filePath(relative).toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun list(path: String): List<SmbEntry> =
        SmbNative.entries(current(), encodedPath(path)).map {
            SmbEntry(it.name.toString(Charsets.UTF_8), it.directory, it.size, it.modified * 1000L)
        }

    @Synchronized
    override fun open(path: String): RemoteReadHandle {
        val opened = SmbNative.open(current(), encodedPath(path))
        return DirectWindowReader(opened[0], opened[1])
    }

    @Synchronized
    override fun close() {
        val handle = connection
        connection = 0
        if (handle != 0L) SmbNative.close(handle)
    }

    /**
     * 256 KiB DirectByteBuffer sliding window so libsmb2 smb2_pread writes directly into the native
     * buffer without an extra native-to-heap copy.
     */
    private inner class DirectWindowReader(private var file: Long, override val length: Long) :
        RemoteReadHandle {
        private val window: ByteBuffer = ByteBuffer.allocateDirect(WINDOW_SIZE)
        private var windowStart = -1L
        private var windowAvailable = 0

        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int =
            synchronized(this@NativeSmbSession) {
                require(
                    offset >= 0 &&
                        bufferOffset >= 0 &&
                        count >= 0 &&
                        bufferOffset <= buffer.size - count,
                )
                if (file == 0L) throw IOException("SMB：文件已关闭")
                if (count == 0) return@synchronized 0
                if (offset >= length) return@synchronized -1
                val maxReadable = minOf(count.toLong(), length - offset).toInt()
                if (
                    windowAvailable > 0 &&
                        offset >= windowStart &&
                        offset - windowStart < windowAvailable
                ) {
                    val cachedOffset = (offset - windowStart).toInt()
                    val copied = minOf(maxReadable, windowAvailable - cachedOffset)
                    window.position(cachedOffset)
                    window.get(buffer, bufferOffset, copied)
                    return@synchronized copied
                }
                if (maxReadable >= WINDOW_SIZE) {
                    val boundedCount = minOf(maxReadable, MAX_NATIVE_READ)
                    val n =
                        SmbNative.read(current(), file, offset, buffer, bufferOffset, boundedCount)
                    if (n <= 0) throw IOException("SMB：文件已改变，请重新扫描")
                    return@synchronized n
                }
                val fetchSize = minOf(WINDOW_SIZE.toLong(), length - offset).toInt()
                val n = SmbNative.readDirect(current(), file, offset, window, 0, fetchSize)
                if (n <= 0) throw IOException("SMB：文件已改变，请重新扫描")
                windowStart = offset
                windowAvailable = n
                val copied = minOf(maxReadable, n)
                window.position(0)
                window.get(buffer, bufferOffset, copied)
                copied
            }

        override fun close() =
            synchronized(this@NativeSmbSession) {
                windowStart = -1L
                windowAvailable = 0
                val handle = file
                file = 0
                if (handle != 0L && connection != 0L) SmbNative.closeFile(connection, handle)
            }
    }
}

/**
 * Pool-backed native libsmb2 7.0.0 client (replacing SMBJ + BouncyCastle): reuses authenticated SMB
 * 2/3 sessions across concurrent reads and cache fragments with automatic idle eviction and
 * transparent reconnect-on-reset.
 */
class SmbClient(
    private val connect: (SmbAddress, SmbAccount, String) -> SmbSession = { addr, account, pass ->
        NativeSmbSession(addr, account, pass)
    },
    // Exceeds WhispLoadControl's 180s burst window so quiet intervals keep sessions warm.
    private val idleTimeoutMs: Long = 210_000L,
    private val maxIdlePerSource: Int = 4,
    private val report: (String) -> Unit = {},
) {
    constructor(report: (String) -> Unit) : this(idleTimeoutMs = 210_000L, report = report)

    private data class PoolKey(
        val sourceId: String,
        val root: String,
        val username: String,
        val secretDigest: String,
    )

    private class IdleSlot(val session: SmbSession, var expiryTask: ScheduledFuture<*>? = null)

    private val poolLock = Any()
    private val idlePools = mutableMapOf<PoolKey, ArrayDeque<IdleSlot>>()
    private val generations = mutableMapOf<String, Long>()
    private val cleaner by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "whisp-smb-pool-idle").apply { isDaemon = true }
        }
    }

    private fun poolKey(source: SourceEntity, password: String): PoolKey =
        PoolKey(
            source.id,
            source.root,
            source.username,
            password.encodeUtf8().sha256().hex(),
        )

    private fun borrow(
        key: PoolKey,
        address: SmbAddress,
        account: SmbAccount,
        password: String,
    ): Triple<SmbSession, Long, Boolean> {
        val generation: Long
        if (idleTimeoutMs > 0L) {
            synchronized(poolLock) {
                generation = generations[key.sourceId] ?: 0L
                val slot = idlePools[key]?.pollLast()
                if (slot != null) {
                    slot.expiryTask?.cancel(false)
                    slot.expiryTask = null
                    if (idlePools[key]?.isEmpty() == true) idlePools.remove(key)
                    return Triple(slot.session, generation, true)
                }
            }
        } else {
            generation = 0L
        }
        return Triple(connect(address, account, password), generation, false)
    }

    private fun release(key: PoolKey, session: SmbSession, generation: Long, broken: Boolean) {
        if (idleTimeoutMs <= 0L || broken) {
            runCatching { session.close() }
            return
        }
        var shouldClose = false
        synchronized(poolLock) {
            val currentGen = generations[key.sourceId] ?: 0L
            if (currentGen != generation) {
                shouldClose = true
            } else {
                val deque = idlePools.getOrPut(key) { ArrayDeque() }
                if (deque.size >= maxIdlePerSource) {
                    shouldClose = true
                } else {
                    val slot = IdleSlot(session)
                    slot.expiryTask =
                        cleaner.schedule(
                            { expireSlot(key, slot) },
                            idleTimeoutMs,
                            TimeUnit.MILLISECONDS,
                        )
                    deque.addLast(slot)
                }
            }
        }
        if (shouldClose) runCatching { session.close() }
    }

    private fun expireSlot(key: PoolKey, slot: IdleSlot) {
        val removed =
            synchronized(poolLock) {
                val deque = idlePools[key]
                val ok = deque?.remove(slot) == true
                if (deque != null && deque.isEmpty()) idlePools.remove(key)
                ok
            }
        if (removed) runCatching { slot.session.close() }
    }

    fun evict(sourceId: String) {
        val toClose = mutableListOf<SmbSession>()
        synchronized(poolLock) {
            generations[sourceId] = (generations[sourceId] ?: 0L) + 1L
            val iterator = idlePools.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.sourceId == sourceId) {
                    for (slot in entry.value) {
                        slot.expiryTask?.cancel(false)
                        toClose += slot.session
                    }
                    iterator.remove()
                }
            }
        }
        toClose.forEach { runCatching { it.close() } }
    }

    fun closeIdle() {
        val toClose = mutableListOf<SmbSession>()
        synchronized(poolLock) {
            for (deque in idlePools.values) {
                for (slot in deque) {
                    slot.expiryTask?.cancel(false)
                    toClose += slot.session
                }
            }
            idlePools.clear()
        }
        toClose.forEach { runCatching { it.close() } }
    }

    fun check(source: SourceEntity, password: String) {
        val address = SmbAddress.parse(source.root)
        val account = SmbAccount.parse(source.username)
        connect(address, account, password).use {
            it.list("")
            Unit
        }
    }

    suspend fun walk(source: SourceEntity, password: String): List<FileEntry> {
        val address = SmbAddress.parse(source.root)
        val account = SmbAccount.parse(source.username)
        val workerSessions = arrayOfNulls<SmbSession>(4)
        val sessionLock = Any()
        var closed = false
        try {
            val files = mutableListOf<FileEntry>()
            walkTreeConcurrent(
                root = "",
                concurrency = 4,
                listOne = { workerIndex, parent ->
                    val existing = synchronized(sessionLock) { workerSessions[workerIndex] }
                    val session =
                        existing
                            ?: connect(address, account, password).also { fresh ->
                                val keep =
                                    synchronized(sessionLock) {
                                        if (closed) false
                                        else {
                                            workerSessions[workerIndex] = fresh
                                            true
                                        }
                                    }
                                if (!keep) {
                                    runCatching { fresh.close() }
                                    throw kotlinx.coroutines.CancellationException("SMB 扫描已取消")
                                }
                            }
                    session.list(parent)
                },
                onDirectory = { parent, entries, enqueue ->
                    for (entry in entries) {
                        if (entry.name == "." || entry.name == "..") continue
                        require(SmbAddress.safeSegments(entry.name).size == 1) { "目录项名称无效" }
                        val path = if (parent.isEmpty()) entry.name else "$parent/${entry.name}"
                        files +=
                            FileEntry(
                                path,
                                SmbAddress.assetUri(source.id, path),
                                entry.directory,
                                entry.size,
                                entry.modifiedMs,
                            )
                        require(files.size <= 100_000) { "目录文件过多，请选择 ASMR 子目录" }
                        val depth = path.count { it == '/' } + 1
                        if (entry.directory && depth <= 32) enqueue(path)
                    }
                },
                onProgress = { dirs ->
                    report("SMB listed $dirs directories; ${files.size} entries")
                },
            )
            return files
        } finally {
            val toClose =
                synchronized(sessionLock) {
                    closed = true
                    workerSessions.filterNotNull()
                }
            toClose.forEach { runCatching { it.close() } }
        }
    }

    fun open(source: SourceEntity, password: String, uri: String): RemoteReadHandle {
        val relative = SmbAddress.assetPath(uri, source.id)
        val address = SmbAddress.parse(source.root)
        val account = SmbAccount.parse(source.username)
        val key = poolKey(source, password)
        var borrowed = borrow(key, address, account, password)
        val reader =
            try {
                borrowed.first.open(relative)
            } catch (firstError: Exception) {
                runCatching { borrowed.first.close() }
                if (!borrowed.third) throw firstError
                val freshGen = synchronized(poolLock) { generations[key.sourceId] ?: 0L }
                val fresh = connect(address, account, password)
                borrowed = Triple(fresh, freshGen, false)
                try {
                    fresh.open(relative)
                } catch (retryError: Exception) {
                    runCatching { fresh.close() }
                    throw retryError
                }
            }
        val initialSession = borrowed.first
        val initialGeneration = borrowed.second
        val expectedLength = reader.length
        return object : RemoteReadHandle {
            private var currentSession = initialSession
            private var currentGeneration = initialGeneration
            private var currentReader = reader
            private var broken = false
            private var closed = false

            override val length: Long
                get() = expectedLength

            override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int {
                try {
                    return currentReader.read(buffer, offset, bufferOffset, count)
                } catch (firstError: Exception) {
                    if (closed || Thread.currentThread().isInterrupted) {
                        broken = true
                        throw firstError
                    }
                    runCatching { currentReader.close() }
                    runCatching { currentSession.close() }
                    val freshGen = synchronized(poolLock) { generations[key.sourceId] ?: 0L }
                    val freshSession =
                        try {
                            connect(address, account, password)
                        } catch (_: Exception) {
                            broken = true
                            throw firstError
                        }
                    val freshReader =
                        try {
                            freshSession.open(relative)
                        } catch (openError: Exception) {
                            runCatching { freshSession.close() }
                            broken = true
                            throw openError
                        }
                    currentSession = freshSession
                    currentGeneration = freshGen
                    currentReader = freshReader
                    if (freshReader.length != expectedLength) {
                        broken = true
                        throw IOException("SMB：文件已改变，请重新扫描")
                    }
                    return try {
                        freshReader.read(buffer, offset, bufferOffset, count)
                    } catch (retryError: Exception) {
                        broken = true
                        throw retryError
                    }
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    currentReader.close()
                } catch (_: Exception) {
                    broken = true
                } finally {
                    release(key, currentSession, currentGeneration, broken)
                }
            }
        }
    }

    fun range(
        uri: String,
        source: SourceEntity,
        password: String,
        offset: Long,
        count: Int,
    ): ByteArray = open(source, password, uri).use { it.readRange(offset, count) }

    fun bytes(uri: String, source: SourceEntity, password: String, limit: Int): ByteArray =
        open(source, password, uri).use { it.readFull(limit) }
}
