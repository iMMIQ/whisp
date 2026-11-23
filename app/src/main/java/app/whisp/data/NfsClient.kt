// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class NfsEntry(val name: String, val type: Int, val size: Long, val modifiedMs: Long)

interface NfsSession : Closeable {
    fun list(path: String): List<NfsEntry>

    fun open(path: String): RemoteReadHandle
}

/** An operation owns its connection; file handles and RPC contexts never cross sources. */
class NfsClient(
    private val connect: (NfsAddress) -> NfsSession = { NativeNfsSession(it) },
    private val report: (String) -> Unit = {},
) {
    fun check(source: SourceEntity) =
        connect(NfsAddress.parse(source.root)).use {
            it.list("")
            Unit
        }

    suspend fun walk(source: SourceEntity): List<FileEntry> = coroutineScope {
        val address = NfsAddress.parse(source.root)
        val sessions = mutableListOf<NfsSession>()
        try {
            sessions += connect(address)
            val files = mutableListOf<FileEntry>()
            var pending = listOf("")
            var directories = 0
            while (pending.isNotEmpty()) {
                val nextLevel = mutableListOf<String>()
                for (batch in pending.chunked(4)) {
                    currentCoroutineContext().ensureActive()
                    while (sessions.size < batch.size) sessions += connect(address)
                    val listings =
                        batch
                            .mapIndexed { index, path ->
                                async(Dispatchers.IO) { sessions[index].list(path) }
                            }
                            .awaitAll()
                    val next = mutableListOf<String>()
                    listings.forEachIndexed { index, entries ->
                        directories++
                        val parent = batch[index]
                        for (entry in entries) {
                            currentCoroutineContext().ensureActive()
                            if (entry.name in listOf(".", "..") || entry.type !in listOf(1, 2))
                                continue
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
                            require(files.size <= 100000) { "目录文件过多，请选择 ASMR 子目录" }
                            if (directory) next += path
                        }
                    }
                    nextLevel += next
                }
                pending = nextLevel.toList()
                nextLevel.clear()
                report("NFS listed $directories directories; ${files.size} entries")
            }
            files
        } finally {
            sessions.forEach { it.close() }
        }
    }

    fun open(source: SourceEntity, uri: String): RemoteReadHandle {
        val path = NfsAddress.assetPath(uri, source.id)
        val session = connect(NfsAddress.parse(source.root))
        val reader =
            try {
                session.open(path)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        return object : RemoteReadHandle by reader {
            override fun close() {
                try {
                    reader.close()
                } finally {
                    session.close()
                }
            }
        }
    }

    fun assets(source: SourceEntity): RemoteAssetSession {
        val session = connect(NfsAddress.parse(source.root))
        fun open(uri: String) = session.open(NfsAddress.assetPath(uri, source.id))
        return object : RemoteAssetSession {
            override fun bytes(uri: String, limit: Int) = open(uri).use { boundedBytes(it, limit) }

            override fun duration(uri: String): Long {
                val relative = NfsAddress.assetPath(uri, source.id)
                return session.open(relative).use { reader ->
                    AudioDurationParser.duration(relative, reader.length) { offset, length ->
                        val bytes = ByteArray(length)
                        var used = 0
                        while (used < length) {
                            val read = reader.read(bytes, offset + used, used, length - used)
                            if (read <= 0) break
                            used += read
                        }
                        bytes.copyOf(used)
                    }
                }
            }

            override fun close() = session.close()
        }
    }

    fun bytes(source: SourceEntity, uri: String, limit: Int) =
        open(source, uri).use { boundedBytes(it, limit) }

    private fun boundedBytes(reader: RemoteReadHandle, limit: Int): ByteArray {
        require(limit >= 0 && reader.length in 0..limit.toLong()) { "文件过大" }
        val bytes = ByteArray(reader.length.toInt())
        var used = 0
        while (used < bytes.size) {
            val read = reader.read(bytes, used.toLong(), used, bytes.size - used)
            if (read <= 0) throw IOException("NFS 文件读取中断")
            used += read
        }
        return bytes
    }
}
