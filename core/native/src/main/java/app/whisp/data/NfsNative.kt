// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.nio.ByteBuffer

class NfsNativeEntry(
    val name: ByteArray,
    val type: Int,
    val size: Long,
    val modified: Long,
)

object NfsNative {
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
