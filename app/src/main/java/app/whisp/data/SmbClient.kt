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
            .withSoTimeout(25_000)
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
            walkTree(
                root = "",
                concurrency = 4,
                listBatch = { batch ->
                    parallelList(batch) { _, parent ->
                        try {
                            disk.share!!.list(disk.address.filePath(parent))
                        } catch (e: Exception) {
                            throw failure(e)
                        }
                    }
                },
                onDirectory = { parent, entries, enqueue ->
                    for (entry in entries) {
                        val name = entry.fileName
                        if (name == "." || name == ".." || entry.fileAttributes and 0x400L != 0L)
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
                        require(files.size <= 100_000) { "目录文件过多，请选择 ASMR 子目录" }
                        if (directory) enqueue(path)
                    }
                },
                onLevel = { dirs -> report("SMB listed $dirs directories; ${files.size} entries") },
            )
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

    fun open(source: SourceEntity, password: String, uri: String): RemoteReadHandle =
        BufferedReadHandle(RawReader(source, password, uri))

    private inner class RawReader(
        private val source: SourceEntity,
        private val password: String,
        uri: String,
    ) : RemoteReadHandle {
        private val relative = SmbAddress.assetPath(uri, source.id)
        private var disk: Disk? = null
        private var file: com.hierynomus.smbj.share.File? = null
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
            } catch (e: Exception) {
                close()
                throw failure(e)
            }
        }

        override fun read(buffer: ByteArray, offset: Long, bufferOffset: Int, count: Int): Int {
            try {
                return file!!.read(buffer, offset, bufferOffset, count)
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw failure(e)
                val previousLength = length
                close()
                connect()
                if (length != previousLength) throw IOException("SMB：文件已改变，请重新扫描")
                return try {
                    file!!.read(buffer, offset, bufferOffset, count)
                } catch (retry: Exception) {
                    throw failure(retry)
                }
            }
        }

        override fun close() {
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
    ): ByteArray = open(source, password, uri).use { it.readRange(offset, count) }

    fun bytes(uri: String, source: SourceEntity, password: String, limit: Int): ByteArray =
        open(source, password, uri).use { it.readFull(limit) }

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
