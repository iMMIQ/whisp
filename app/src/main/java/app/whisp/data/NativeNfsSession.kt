// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.nio.ByteBuffer

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

    external fun readDirect(
        connection: Long,
        file: Long,
        position: Long,
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
    ): Int

    external fun closeFile(connection: Long, file: Long)

    external fun close(connection: Long)
}

internal class NativeNfsSession(address: NfsAddress) : NfsSession {
    companion object {
        private const val WINDOW_SIZE = 256 * 1024
        private const val MAX_NATIVE_READ = 1024 * 1024
    }

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
        return DirectWindowReader(opened[0], opened[1])
    }

    @Synchronized
    override fun close() {
        val handle = connection
        connection = 0
        if (handle != 0L) NfsNative.close(handle)
    }

    /**
     * 256 KiB DirectByteBuffer sliding window so nfs_pread writes straight into the JVM-visible
     * native buffer without an extra native-to-heap SetByteArrayRegion copy.
     */
    private inner class DirectWindowReader(private var file: Long, override val length: Long) :
        RemoteReadHandle {
        private val window: ByteBuffer = ByteBuffer.allocateDirect(WINDOW_SIZE)
        private var windowStart = -1L
        private var windowAvailable = 0

        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int =
            synchronized(this@NativeNfsSession) {
                require(
                    offset >= 0 &&
                        bufferOffset >= 0 &&
                        count >= 0 &&
                        bufferOffset <= buffer.size - count,
                )
                if (file == 0L) throw IOException("NFS 文件已关闭")
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
                        NfsNative.read(current(), file, offset, buffer, bufferOffset, boundedCount)
                    if (n <= 0) throw IOException("NFS 文件已改变，请重新扫描")
                    return@synchronized n
                }
                val fetchSize = minOf(WINDOW_SIZE.toLong(), length - offset).toInt()
                val n = NfsNative.readDirect(current(), file, offset, window, 0, fetchSize)
                if (n <= 0) throw IOException("NFS 文件已改变，请重新扫描")
                windowStart = offset
                windowAvailable = n
                val copied = minOf(maxReadable, n)
                window.position(0)
                window.get(buffer, bufferOffset, copied)
                copied
            }

        override fun close() =
            synchronized(this@NativeNfsSession) {
                windowStart = -1L
                windowAvailable = 0
                val handle = file
                file = 0
                if (handle != 0L && connection != 0L) NfsNative.closeFile(connection, handle)
            }
    }
}
