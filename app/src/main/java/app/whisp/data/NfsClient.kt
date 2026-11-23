// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.Closeable

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

    suspend fun walk(source: SourceEntity): List<FileEntry> {
        val address = NfsAddress.parse(source.root)
        val sessions = mutableListOf<NfsSession>()
        try {
            sessions += connect(address)
            val files = mutableListOf<FileEntry>()
            walkTree(
                root = "",
                concurrency = 4,
                listBatch = { batch ->
                    while (sessions.size < batch.size) sessions += connect(address)
                    parallelList(batch) { index, path -> sessions[index].list(path) }
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
                        if (directory) enqueue(path)
                    }
                },
                onLevel = { dirs -> report("NFS listed $dirs directories; ${files.size} entries") },
            )
            return files
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

    fun bytes(source: SourceEntity, uri: String, limit: Int): ByteArray =
        open(source, uri).use { it.readFull(limit, "NFS 文件读取中断") }
}
