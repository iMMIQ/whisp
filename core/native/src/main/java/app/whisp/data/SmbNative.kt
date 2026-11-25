// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.nio.ByteBuffer

class SmbNativeEntry(
    val name: ByteArray,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

object SmbNative {
    init {
        System.loadLibrary("whisp_smb")
    }

    external fun connect(
        hostPort: ByteArray,
        share: ByteArray,
        user: ByteArray,
        domain: ByteArray,
        password: ByteArray,
        encrypt: Boolean,
    ): Long

    external fun entries(connection: Long, path: ByteArray): Array<SmbNativeEntry>

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
