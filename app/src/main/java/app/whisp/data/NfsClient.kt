// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class NfsEntry(val name: String, val type: Int, val size: Long, val modifiedMs: Long)

interface NfsSession : Closeable {
    fun list(path: String): List<NfsEntry>

    fun open(path: String): RemoteReadHandle
}

/**
 * NFS client with optional per-source idle session pooling so consecutive file/fragment reads reuse
 * mounted RPC contexts while keeping connections isolated across sources and reclaiming idle
 * mounts.
 */
class NfsClient(
    private val connect: (NfsAddress) -> NfsSession = { NativeNfsSession(it) },
    private val poolIdleTimeoutMs: Long = 0L,
    private val maxIdlePerSource: Int = 4,
    private val report: (String) -> Unit = {},
) {
    private data class PoolKey(val sourceId: String, val root: String)

    private class IdleSlot(val session: NfsSession, var expiryTask: ScheduledFuture<*>? = null)

    private val poolLock = Any()
    private val idlePools = mutableMapOf<PoolKey, ArrayDeque<IdleSlot>>()
    private val generations = mutableMapOf<String, Long>()
    private val cleaner by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "whisp-nfs-pool-idle").apply { isDaemon = true }
        }
    }

    private fun borrow(key: PoolKey, address: NfsAddress): Triple<NfsSession, Long, Boolean> {
        val generation: Long
        if (poolIdleTimeoutMs > 0L) {
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
        return Triple(connect(address), generation, false)
    }

    private fun release(key: PoolKey, session: NfsSession, generation: Long, broken: Boolean) {
        if (poolIdleTimeoutMs <= 0L || broken) {
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
                            poolIdleTimeoutMs,
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
        val toClose = mutableListOf<NfsSession>()
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
        val toClose = mutableListOf<NfsSession>()
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

    fun check(source: SourceEntity) =
        connect(NfsAddress.parse(source.root)).use {
            it.list("")
            Unit
        }

    suspend fun walk(source: SourceEntity): List<FileEntry> {
        val address = NfsAddress.parse(source.root)
        val workerSessions = arrayOfNulls<NfsSession>(4)
        val sessionLock = Any()
        var closed = false
        try {
            val files = mutableListOf<FileEntry>()
            walkTreeConcurrent(
                root = "",
                concurrency = 4,
                listOne = { workerIndex, path ->
                    val existing = synchronized(sessionLock) { workerSessions[workerIndex] }
                    val session =
                        existing
                            ?: connect(address).also { fresh ->
                                val keep =
                                    synchronized(sessionLock) {
                                        if (closed) {
                                            false
                                        } else {
                                            workerSessions[workerIndex] = fresh
                                            true
                                        }
                                    }
                                if (!keep) {
                                    runCatching { fresh.close() }
                                    throw kotlinx.coroutines.CancellationException("NFS 扫描已取消")
                                }
                            }
                    session.list(path)
                },
                onDirectory = { parent, entries, enqueue ->
                    for (entry in entries) {
                        if (entry.name in listOf(".", "..") || entry.type !in listOf(1, 2)) continue
                        require(NfsAddress.safeSegments(entry.name).size == 1) { "目录项名称无效" }
                        val path = if (parent.isEmpty()) entry.name else "$parent/${entry.name}"
                        val directory = entry.type == 2
                        files +=
                            FileEntry(
                                path,
                                NfsAddress.assetUri(source.id, path),
                                directory,
                                entry.size,
                                entry.modifiedMs,
                            )
                        require(files.size <= 100_000) { "目录文件过多，请选择 ASMR 子目录" }
                        val depth = path.count { it == '/' } + 1
                        if (directory && depth <= 32) enqueue(path)
                    }
                },
                onProgress = { dirs ->
                    report("NFS listed $dirs directories; ${files.size} entries")
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

    fun open(source: SourceEntity, uri: String): RemoteReadHandle {
        val path = NfsAddress.assetPath(uri, source.id)
        val address = NfsAddress.parse(source.root)
        val key = PoolKey(source.id, source.root)
        var borrowed = borrow(key, address)
        val reader =
            try {
                borrowed.first.open(path)
            } catch (firstError: Exception) {
                runCatching { borrowed.first.close() }
                if (!borrowed.third) throw firstError
                // Reused mount may have expired server-side; retry once on a fresh mount.
                val freshGen = synchronized(poolLock) { generations[key.sourceId] ?: 0L }
                val fresh = connect(address)
                borrowed = Triple(fresh, freshGen, false)
                try {
                    fresh.open(path)
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
                    // Discard the broken mount and retry once on a fresh connection, matching SMB
                    // resiliency.
                    runCatching { currentReader.close() }
                    runCatching { currentSession.close() }
                    val freshGen = synchronized(poolLock) { generations[key.sourceId] ?: 0L }
                    val freshSession =
                        try {
                            connect(address)
                        } catch (connectError: Exception) {
                            broken = true
                            throw firstError
                        }
                    val freshReader =
                        try {
                            freshSession.open(path)
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
                        throw java.io.IOException("NFS：文件已改变，请重新扫描")
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

    fun bytes(source: SourceEntity, uri: String, limit: Int): ByteArray =
        open(source, uri).use { it.readFull(limit, "NFS 文件读取中断") }
}
