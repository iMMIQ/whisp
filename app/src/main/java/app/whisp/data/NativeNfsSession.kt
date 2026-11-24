// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException

internal class NfsNativeEntry(
    val name: ByteArray,
    val type: Int,
    val size: Long,
    val modified: Long,
)

internal object NfsNative {
    init {
        System.loadLibrary("whisp_nfs")
    }

    external fun connect(
        host: ByteArray,
        path: ByteArray,
        version: Int,
        uid: Int,
        gid: Int,
        port: Int,
        mountPort: Int,
    ): Long

    external fun entries(connection: Long, path: ByteArray): Array<NfsNativeEntry>

    external fun open(connection: Long, path: ByteArray): LongArray

    external fun read(
        connection: Long,
        file: Long,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    external fun closeFile(connection: Long, file: Long)

    external fun close(connection: Long)
}

internal class NativeNfsSession(address: NfsAddress) : NfsSession {
    private var connection =
        NfsNative.connect(
            address.host.removeSurrounding("[", "]").toByteArray(Charsets.UTF_8),
            address.exportPath.toByteArray(Charsets.UTF_8),
            address.version,
            address.uid,
            address.gid,
            address.port,
            address.mountPort,
        )

    private fun current(): Long = connection.takeIf { it != 0L } ?: throw IOException("NFS 连接已关闭")

    private fun encodedPath(relative: String) =
        ("/" + NfsAddress.safeSegments(relative).joinToString("/")).toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun list(path: String) =
        NfsNative.entries(current(), encodedPath(path)).map {
            NfsEntry(it.name.toString(Charsets.UTF_8), it.type, it.size, it.modified * 1000)
        }

    @Synchronized
    override fun open(path: String): RemoteReadHandle {
        val opened = NfsNative.open(current(), encodedPath(path))
        return BufferedReadHandle(RawReader(opened[0], opened[1]))
    }

    @Synchronized
    override fun close() {
        val handle = connection
        connection = 0
        if (handle != 0L) NfsNative.close(handle)
    }

    private inner class RawReader(private var file: Long, override val length: Long) :
        RemoteReadHandle {
        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int =
            synchronized(this@NativeNfsSession) {
                if (file == 0L) throw IOException("NFS 文件已关闭")
                // Clamp each JNI pread call to the 1 MiB native limit so >1 MiB full reads succeed.
                val boundedCount = minOf(count, 1024 * 1024)
                val n = NfsNative.read(current(), file, offset, buffer, bufferOffset, boundedCount)
                if (n <= 0) throw IOException("NFS 文件已改变，请重新扫描")
                n
            }

        override fun close() =
            synchronized(this@NativeNfsSession) {
                val handle = file
                file = 0
                if (handle != 0L && connection != 0L) NfsNative.closeFile(connection, handle)
            }
    }
}
