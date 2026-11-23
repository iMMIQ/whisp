// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.*
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Each operation owns its session; playback, scans and downloads cannot close each other's handles.
 */
class SmbClient(private val report: (String) -> Unit = {}) {
    private val encryptedConfig = buildConfig(encrypt = true)
    private val compatibleConfig = buildConfig(encrypt = false)

    private fun buildConfig(encrypt: Boolean): SmbConfig =
        SmbConfig.builder()
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(25000)
            .withBufferSize(256 * 1024)
            .withDfsEnabled(false)
            .withEncryptData(encrypt)
            .build()

    private inner class Disk(source: SourceEntity, password: String) : Closeable {
        val address = SmbAddress.parse(source.root)
        val client = SMBClient(if (address.encrypt) encryptedConfig else compatibleConfig)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null

        init {
            try {
                val account = SmbAccount.parse(source.username)
                require(account.user.isNotEmpty() || password.isEmpty()) { "请填写账号" }
                connection = client.connect(address.host, address.port)
                val auth =
                    AuthenticationContext(account.user, password.toCharArray(), account.domain)
                try {
                    session = connection!!.authenticate(auth)
                } finally {
                    auth.password.fill('\u0000')
                }
                share =
                    session!!.connectShare(address.share) as? DiskShare
                        ?: throw IOException("共享不是文件目录")
            } catch (e: Exception) {
                close()
                throw failure(e)
            }
        }

        override fun close() {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }

    fun check(source: SourceEntity, password: String) {
        Disk(source, password).use { disk ->
            try {
                disk.share!!.list(disk.address.filePath(""))
            } catch (e: Exception) {
                throw failure(e)
            }
        }
    }

    suspend fun walk(source: SourceEntity, password: String): List<FileEntry> =
        Disk(source, password).use { disk ->
            report("SMB session ready")
            val files = mutableListOf<FileEntry>()
            var pending = listOf("")
            var directories = 0
            while (pending.isNotEmpty()) {
                val next = mutableListOf<String>()
                // SMBJ multiplexes independent directory handles on the same connection.
                // Four requests cap server load and retain deterministic traversal order.
                for (batch in pending.chunked(4)) {
                    currentCoroutineContext().ensureActive()
                    val listings = coroutineScope {
                        batch
                            .map { parent ->
                                async(Dispatchers.IO) {
                                    try {
                                        disk.share!!.list(disk.address.filePath(parent))
                                    } catch (e: Exception) {
                                        throw failure(e)
                                    }
                                }
                            }
                            .awaitAll()
                    }
                    listings.forEachIndexed { index, entries ->
                        val parent = batch[index]
                        directories++
                        for (entry in entries) {
                            currentCoroutineContext().ensureActive()
                            val name = entry.fileName
                            if (
                                name == "." || name == ".." || entry.fileAttributes and 0x400L != 0L
                            )
                                continue
                            require(SmbAddress.safeSegments(name).size == 1) { "目录项名称无效" }
                            val path = if (parent.isEmpty()) name else "$parent/$name"
                            val directory = entry.fileAttributes and 0x10L != 0L
                            files +=
                                FileEntry(
                                    path,
                                    SmbAddress.assetUri(source.id, path),
                                    directory,
                                    entry.endOfFile,
                                    entry.lastWriteTime.toEpochMillis(),
                                )
                            require(files.size <= 100000) { "目录文件过多，请选择 ASMR 子目录" }
                            if (directory) next.add(path)
                        }
                    }
                }
                report("SMB listed $directories directories; ${files.size} entries")
                pending = next
            }
            files
        }

    private fun openFile(disk: Disk, relative: String): com.hierynomus.smbj.share.File =
        disk.share!!.openFile(
            disk.address.filePath(relative),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.noneOf(FileAttributes::class.java),
            EnumSet.allOf(SMB2ShareAccess::class.java),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )

    /**
     * A scan lane reuses one authenticated session; playback retains its own reconnecting reader.
     */
    fun assets(source: SourceEntity, password: String): AssetSession =
        AssetSession(source, password)

    inner class AssetSession
    internal constructor(private val source: SourceEntity, password: String) : RemoteAssetSession {
        private val disk = Disk(source, password)

        override fun bytes(uri: String, limit: Int): ByteArray =
            openFile(disk, SmbAddress.assetPath(uri, source.id)).use { file ->
                require(file.length <= limit) { "文件过大" }
                read(file, 0, file.length.toInt(), requireFull = true)
            }

        override fun duration(uri: String): Long {
            val relative = SmbAddress.assetPath(uri, source.id)
            return openFile(disk, relative).use { file ->
                AudioDurationParser.duration(relative, file.length) { offset, count ->
                    read(file, offset, count)
                }
            }
        }

        private fun read(
            file: com.hierynomus.smbj.share.File,
            offset: Long,
            count: Int,
            requireFull: Boolean = false,
        ): ByteArray {
            val bytes = ByteArray(count)
            var used = 0
            while (used < count) {
                val n = file.read(bytes, offset + used, used, count - used)
                if (n <= 0) break
                used += n
            }
            if (requireFull && used != count) throw IOException("文件读取中断")
            return bytes.copyOf(used)
        }

        override fun close() = disk.close()
    }

    fun open(source: SourceEntity, password: String, uri: String): Reader =
        Reader(source, password, uri)

    inner class Reader(
        private val source: SourceEntity,
        private val password: String,
        uri: String,
    ) : RemoteReadHandle {
        private val relative = SmbAddress.assetPath(uri, source.id)
        private var disk: Disk? = null
        private var file: com.hierynomus.smbj.share.File? = null
        // 256 KiB sliding read-ahead window eliminates small-chunk SMB2 READ RTT amplification.
        private val window = ByteArray(256 * 1024)
        private var windowStart = -1L
        private var windowAvailable = 0
        override var length: Long = 0
            private set

        init {
            connect()
        }

        private fun connect() {
            try {
                disk = Disk(source, password)
                file = openFile(disk!!, relative)
                length = file!!.length
                windowStart = -1L
                windowAvailable = 0
            } catch (e: Exception) {
                close()
                throw failure(e)
            }
        }

        private fun readRemote(dest: ByteArray, offset: Long, destOffset: Int, count: Int): Int {
            try {
                return file!!.read(dest, offset, destOffset, count)
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw failure(e)
                // A bounded reconnect resumes at the exact 64-bit offset, including downloads.
                val previousLength = length
                close()
                connect()
                if (length != previousLength) throw IOException("SMB：文件已改变，请重新扫描")
                return try {
                    file!!.read(dest, offset, destOffset, count)
                } catch (retry: Exception) {
                    throw failure(retry)
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int {
            require(
                offset >= 0 &&
                    bufferOffset >= 0 &&
                    count >= 0 &&
                    bufferOffset <= buffer.size - count,
            )
            if (count == 0) return 0
            if (offset >= length) return -1
            val maxReadable = minOf(count.toLong(), length - offset).toInt()
            if (
                windowAvailable > 0 &&
                    offset >= windowStart &&
                    offset - windowStart < windowAvailable
            ) {
                val cachedOffset = (offset - windowStart).toInt()
                val copied = minOf(maxReadable, windowAvailable - cachedOffset)
                window.copyInto(buffer, bufferOffset, cachedOffset, cachedOffset + copied)
                return copied
            }
            // Large caller buffers can bypass the sliding window to avoid an extra copy.
            if (maxReadable >= window.size) {
                return readRemote(buffer, offset, bufferOffset, maxReadable)
            }
            val fetchSize = minOf(window.size.toLong(), length - offset).toInt()
            val n = readRemote(window, offset, 0, fetchSize)
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
            runCatching { file?.close() }
            file = null
            disk?.close()
            disk = null
        }
    }

    fun range(
        uri: String,
        source: SourceEntity,
        password: String,
        offset: Long,
        count: Int,
    ): ByteArray =
        open(source, password, uri).use { reader ->
            val bytes =
                ByteArray(minOf(count.toLong(), (reader.length - offset).coerceAtLeast(0)).toInt())
            var used = 0
            while (used < bytes.size) {
                val n = reader.read(bytes, offset + used, used, bytes.size - used)
                if (n <= 0) break
                used += n
            }
            bytes.copyOf(used)
        }

    fun bytes(uri: String, source: SourceEntity, password: String, limit: Int): ByteArray =
        open(source, password, uri).use { reader ->
            require(reader.length <= limit) { "文件过大" }
            val bytes = ByteArray(reader.length.toInt())
            var used = 0
            while (used < bytes.size) {
                val n = reader.read(bytes, used.toLong(), used, bytes.size - used)
                if (n <= 0) throw IOException("文件读取中断")
                used += n
            }
            bytes
        }

    private fun failure(error: Exception): IOException {
        report(
            "SMB error types: " +
                generateSequence<Throwable>(error) { it.cause }
                    .take(8)
                    .joinToString(" / ") { it.javaClass.simpleName },
        )
        if (error is IOException && error.message?.startsWith("SMB：") == true) return error
        val status = (error as? SMBApiException)?.statusCode
        if (status != null) report("SMB status: 0x" + status.toString(16))
        val detail =
            when {
                error.toString().contains("STATUS_LOGON_FAILURE") -> "账号或密码不正确"
                error.toString().contains("STATUS_ACCESS_DENIED") -> "没有共享或目录的读取权限"
                error.toString().contains("STATUS_BAD_NETWORK_NAME") -> "共享名不存在"
                error.toString().contains("STATUS_OBJECT_PATH_NOT_FOUND") ||
                    error.toString().contains("STATUS_OBJECT_NAME_NOT_FOUND") -> "目录或文件不存在"
                error is java.net.SocketTimeoutException -> "连接超时，请检查 NAS 和网络"
                else -> "连接或读取失败，请检查地址、账号、加密设置和网络"
            }
        return IOException("SMB：$detail", error)
    }
}
