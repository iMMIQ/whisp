// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Single registry owning protocol clients (Local/SMB/NFS/WebDAV/ASMR.ONE/DLsite), DLsite account
 * sessions, credential-aware HTTP headers, asset byte reading, and lazy Media3 DataSource creation.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class SourceRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: LibraryDao,
    val secrets: SecretStore,
    val http: OkHttpClient,
    val onlineHttp: OkHttpClient,
    val dlsiteDiagnostics: DlsiteDiagnostics,
) {
    val dav = WebDavClient(http)
    val smb = SmbClient { Log.i("WhispScan", it) }
    val nfs = NfsClient(poolIdleTimeoutMs = 45_000L, report = { Log.i("WhispScan", it) })
    val dlsite = DlsiteClient(onlineHttp)
    internal val dlsiteClients = ConcurrentHashMap<String, DlsiteAccountClient>()
    internal val dlsiteCookieWrites = Mutex()

    fun asmrClient(source: SourceEntity): AsmrOneClient {
        require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
        return AsmrOneClient(onlineHttp, source.root.toHttpUrlOrNull() ?: error("来源地址无效"))
    }

    suspend fun dlsiteAccount(sourceId: String): DlsiteAccountClient = dlsiteCookieWrites.withLock {
        dlsiteClients[sourceId]?.let {
            return@withLock it
        }
        val source = dao.source(sourceId) ?: throw IOException("来源已移除")
        require(source.kindType == SourceKind.DLSITE) { "来源类型不匹配" }
        if (source.secret.isBlank()) throw DlsiteLoginRequired()
        lateinit var session: DlsiteSession
        session =
            DlsiteSession(secrets.decryptCached(source)) {
                scope.launch {
                    dlsiteCookieWrites.withLock {
                        dao.source(sourceId)
                            ?.takeIf {
                                it.kindType == SourceKind.DLSITE &&
                                    dlsiteClients[sourceId]?.session === session
                            }
                            ?.let {
                                dao.updateSourceSecret(it.id, secrets.encrypt(session.encode()))
                            }
                    }
                }
            }
        val created = DlsiteAccountClient(session, http, diagnostic = dlsiteDiagnostics::record)
        dlsiteClients.putIfAbsent(sourceId, created) ?: created
    }

    fun requireDlsiteClient(sourceId: String, client: DlsiteAccountClient) {
        if (dlsiteClients[sourceId] !== client) throw CancellationException("DLsite 会话已更新")
    }

    suspend fun evictSource(sourceId: String) {
        smb.evict(sourceId)
        nfs.evict(sourceId)
        dlsiteCookieWrites.withLock {
            dlsiteClients.remove(sourceId)?.close()
            secrets.evict(sourceId)
        }
    }

    suspend fun acceptDlsiteLogin(snapshot: String, expectedSource: String? = null): String {
        val session = DlsiteSession(snapshot)
        val verifier = DlsiteAccountClient(session, http, diagnostic = dlsiteDiagnostics::record)
        val account =
            try {
                verifier.authorize()
            } finally {
                verifier.http.dispatcher.executorService.shutdown()
            }
        val id = account.id
        require(expectedSource == null || expectedSource == id) { "请登录该来源对应的账号" }
        dlsiteCookieWrites.withLock {
            val previous = dao.source(id)
            dlsiteClients.remove(id)?.close()
            secrets.evict(id)
            val number = dao.sources().count { it.kindType == SourceKind.DLSITE }
            dao.putSource(
                (previous
                        ?: SourceEntity(
                            id,
                            if (number == 0) "DLsite" else "DLsite ${number + 1}",
                            SourceKind.DLSITE.raw,
                            "https://play.dlsite.com/",
                        ))
                    .copy(secret = secrets.encrypt(session.encode()), error = null),
            )
        }
        return id
    }

    private suspend fun dlsiteResource(sourceId: String, assetId: String): DlsiteMediaResource {
        val rawUri =
            dao.track(assetId)?.uri ?: dao.attachment(assetId)?.uri ?: throw IOException("文件已移除")
        val uri = rawUri.toUri()
        require(uri.scheme == "dlsite-resource") { "DLsite 文件标识无效" }
        return DlsiteMediaResource(
            dlsiteAccount(sourceId),
            uri.authority ?: error("作品编号无效"),
            uri.path.orEmpty().removePrefix("/"),
        )
    }

    suspend fun walk(source: SourceEntity): List<FileEntry> =
        when (source.kindType) {
            SourceKind.LOCAL -> LocalDirectory(context).walk(source.root)
            SourceKind.SMB -> smb.walk(source, secrets.decryptCached(source))
            SourceKind.NFS -> nfs.walk(source)
            SourceKind.WEBDAV -> dav.walk(source, secrets.decryptCached(source))
            else -> error("不支持的来源类型")
        }

    fun headersFor(uri: String, knownSources: List<SourceEntity>): Map<String, String> {
        val url = uri.toHttpUrlOrNull() ?: return emptyMap()
        if (listOf("dlsite.com", "dlsite.jp").any { url.host == it || url.host.endsWith(".$it") })
            return mapOf("Referer" to "https://www.dlsite.com/")
        val sources = knownSources.ifEmpty { runBlocking(Dispatchers.IO) { dao.sources() } }
        val source =
            sources.firstOrNull { source ->
                source.kindType == SourceKind.WEBDAV &&
                    (source.root.trimEnd('/') + "/").toHttpUrlOrNull()?.let {
                        WebDavClient.insideRoot(url, it)
                    } == true
            } ?: return emptyMap()
        if (source.username.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to
                Credentials.basic(source.username, secrets.decryptCached(source), Charsets.UTF_8),
        )
    }

    suspend fun fetchDlsiteBytes(url: String, limit: Int = 16 * 1024 * 1024): ByteArray =
        onlineHttp.onlineBytes(
            Request.Builder().url(url).header("Referer", "https://www.dlsite.com/").build(),
            limit,
        )

    suspend fun streamDlsiteToSink(
        url: String,
        sink: okio.BufferedSink,
        limit: Int = 16 * 1024 * 1024,
    ): Long =
        onlineHttp.onlineCopyToSink(
            Request.Builder().url(url).header("Referer", "https://www.dlsite.com/").build(),
            sink,
            limit,
        )

    suspend fun fetchCatalogBytes(
        url: String,
        sourceId: String,
        limit: Int = 16 * 1024 * 1024,
    ): ByteArray {
        val source = dao.source(sourceId) ?: error("来源已移除")
        return if (source.kindType == SourceKind.DLSITE) fetchDlsiteBytes(url, limit)
        else asmrClient(source).bytes(url, limit)
    }

    suspend fun streamCatalogToSink(
        url: String,
        sourceId: String,
        sink: okio.BufferedSink,
        limit: Int = 16 * 1024 * 1024,
    ): Long {
        val source = dao.source(sourceId) ?: error("来源已移除")
        return if (source.kindType == SourceKind.DLSITE) streamDlsiteToSink(url, sink, limit)
        else asmrClient(source).copyToSink(url, sink, limit)
    }

    /**
     * Resolves an online asset URI into playback scheme routing (whisp-asmr / whisp-dlsite / raw).
     */
    suspend fun routeUri(sourceId: String?, assetId: String, rawUri: String): String {
        if (sourceId == null || rawUri.isBlank()) throw IOException("文件已移动或来源已移除")
        return when (dao.source(sourceId)?.kindType) {
            SourceKind.ASMRONE -> "whisp-asmr://$sourceId/$assetId"
            SourceKind.DLSITE -> "whisp-dlsite://$sourceId/$assetId"
            null -> throw IOException("来源已移除")
            else -> rawUri
        }
    }

    suspend fun readRemoteBytes(
        sourceId: String?,
        assetId: String,
        rawUri: String,
        limit: Int,
    ): ByteArray {
        val parsed = rawUri.toUri()
        if (parsed.scheme == "content" || parsed.scheme == "file") {
            return context.contentResolver.openInputStream(parsed)?.use { boundedRead(it, limit) }
                ?: throw IOException("文件不可读")
        }
        val source = sourceId?.let { dao.source(it) } ?: throw IOException("来源已移除")
        return when (source.kindType) {
            SourceKind.ASMRONE -> asmrClient(source).bytes(rawUri, limit)
            SourceKind.DLSITE -> {
                val res = dlsiteResource(source.id, assetId)
                res.client.resourceBytes(res.workno, res.path, limit)
            }
            SourceKind.SMB -> smb.bytes(rawUri, source, secrets.decryptCached(source), limit)
            SourceKind.NFS -> nfs.bytes(source, rawUri, limit)
            SourceKind.WEBDAV -> dav.bytes(rawUri, source, secrets.decryptCached(source), limit)
            else -> throw IOException("不支持的来源类型")
        }
    }

    suspend fun streamRemoteToSink(
        sourceId: String?,
        assetId: String,
        rawUri: String,
        sink: okio.BufferedSink,
        limit: Int,
    ): Long {
        val parsed = rawUri.toUri()
        if (parsed.scheme == "content" || parsed.scheme == "file") {
            return context.contentResolver.openInputStream(parsed)?.use {
                copyBounded(it, sink, limit)
            } ?: throw IOException("文件不可读")
        }
        val source = sourceId?.let { dao.source(it) } ?: throw IOException("来源已移除")
        return when (source.kindType) {
            SourceKind.ASMRONE -> asmrClient(source).copyToSink(rawUri, sink, limit)
            SourceKind.DLSITE -> {
                val res = dlsiteResource(source.id, assetId)
                val bytes = res.client.resourceBytes(res.workno, res.path, limit)
                sink.write(bytes)
                bytes.size.toLong()
            }
            SourceKind.SMB ->
                smb.open(source, secrets.decryptCached(source), rawUri).use {
                    it.copyToSink(sink, limit)
                }
            SourceKind.NFS ->
                nfs.open(source, rawUri).use { it.copyToSink(sink, limit, "NFS 文件读取中断") }
            SourceKind.WEBDAV ->
                dav.copyToSink(rawUri, source, secrets.decryptCached(source), sink, limit)
            else -> throw IOException("不支持的来源类型")
        }
    }

    /** Lazily instantiates only the single DataSource matching [scheme] on DataSource.open(). */
    fun createDataSourceForScheme(
        scheme: String?,
        headersProvider: (String) -> Map<String, String>,
    ): DataSource =
        when (scheme) {
            "whisp-smb" ->
                RemoteFileDataSource("SMB") { uri ->
                    val source = loadSource(uri, SourceKind.SMB)
                    smb.open(source, secrets.decryptCached(source), uri)
                }
            "whisp-nfs" ->
                RemoteFileDataSource("NFS") { uri ->
                    val source = loadSource(uri, SourceKind.NFS)
                    nfs.open(source, uri)
                }
            "whisp-asmr" ->
                ResolvingDataSource.Factory(
                        OkHttpDataSource.Factory(onlineHttp)
                            .setDefaultRequestProperties(AsmrOneClient.MEDIA_HEADERS),
                    ) { spec ->
                        val asset = spec.uri.lastPathSegment ?: throw IOException("文件标识无效")
                        val sourceId = spec.uri.authority ?: throw IOException("来源未关联")
                        val targetUri =
                            runBlocking(Dispatchers.IO) {
                                val source = dao.source(sourceId) ?: throw IOException("来源已移除")
                                require(source.kindType == SourceKind.ASMRONE) { "来源类型不匹配" }
                                dao.track(asset)?.uri
                                    ?: dao.attachment(asset)?.uri
                                    ?: throw IOException("文件已移除")
                            }
                        spec.withUri(targetUri.toUri())
                    }
                    .createDataSource()
            "whisp-dlsite" ->
                DlsiteDataSource(::dlsiteResource) { sourceId, client ->
                    scope.launch {
                        dlsiteCookieWrites.withLock {
                            val current = dlsiteClients[sourceId]
                            val source = dao.source(sourceId)
                            if (
                                source?.kindType == SourceKind.DLSITE &&
                                    (client != null && current === client ||
                                        client == null &&
                                            current == null &&
                                            source.secret.isBlank())
                            ) {
                                dao.updateSourceError(sourceId, "请重新登录 DLsite")
                            }
                        }
                    }
                }
            else ->
                ResolvingDataSource.Factory(
                        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)),
                    ) { spec ->
                        spec.withRequestHeaders(
                            spec.httpRequestHeaders + headersProvider(spec.uri.toString()),
                        )
                    }
                    .createDataSource()
        }

    private fun loadSource(uri: String, expected: SourceKind): SourceEntity {
        val sourceId = uri.toUri().authority ?: throw IOException("来源未关联")
        val source =
            runBlocking(Dispatchers.IO) { dao.source(sourceId) } ?: throw IOException("来源已移除")
        require(source.kindType == expected) { "来源类型不匹配" }
        return source
    }
}
