// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LocalDirectory(private val context: Context) {
    suspend fun walk(root: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val tree = root.toUri()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val files = mutableListOf<FileEntry>()
            val pending = ArrayDeque<Pair<String, String>>()
            pending.add(rootId to "")
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                val (id, prefix) = pending.removeFirst()
                check(seen.size < 100_000) { "目录过大" }
                if (!seen.add(id)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                val projection =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                val cursor =
                    context.contentResolver.query(children, projection, null, null, null)
                        ?: throw IOException("无法读取目录")
                cursor.use {
                    while (it.moveToNext()) {
                        val docId = it.getString(0)
                        val name = it.getString(1)
                        val path = if (prefix.isBlank()) name else "$prefix/$name"
                        val directory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        files +=
                            FileEntry(
                                path,
                                DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString(),
                                directory,
                                if (it.isNull(3)) 0 else it.getLong(3),
                                if (it.isNull(4)) 0 else it.getLong(4),
                            )
                        if (directory) pending.add(docId to path)
                    }
                }
            }
            files
        }
}

data class DavEntry(
    val url: HttpUrl,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
)

class WebDavClient(private val http: OkHttpClient) {
    companion object {
        fun sameOrigin(a: HttpUrl, b: HttpUrl) =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port

        fun insideRoot(url: HttpUrl, root: HttpUrl): Boolean {
            val path = root.encodedPath.trimEnd('/')
            return sameOrigin(url, root) &&
                (url.encodedPath == path || url.encodedPath.startsWith("$path/"))
        }

        fun parse(bytes: ByteArray, requested: HttpUrl): List<DavEntry> {
            require(
                !bytes
                    .toString(Charsets.UTF_8)
                    .replace("\u0000", "")
                    .contains("<!DOCTYPE", ignoreCase = true),
            ) {
                "不支持 DTD"
            }
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            return (0 until responses.length).mapNotNull { index ->
                val element = responses.item(index) as org.w3c.dom.Element
                val url =
                    requested.resolve(
                        element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim()
                            ?: return@mapNotNull null,
                    ) ?: return@mapNotNull null
                if (!sameOrigin(url, requested)) return@mapNotNull null
                val propstats = element.getElementsByTagNameNS("DAV:", "propstat")
                val validProps =
                    (0 until propstats.length)
                        .map { propstats.item(it) as org.w3c.dom.Element }
                        .filter {
                            val status =
                                it.getElementsByTagNameNS("DAV:", "status")
                                    .item(0)
                                    ?.textContent
                                    .orEmpty()
                            Regex("\\s2\\d\\d\\s").containsMatchIn("$status ")
                        }
                if (validProps.isEmpty()) return@mapNotNull null
                fun field(name: String) = validProps.firstNotNullOfOrNull {
                    it.getElementsByTagNameNS("DAV:", name).item(0)?.textContent?.trim()
                }
                DavEntry(
                    url,
                    field("displayname")
                        ?: url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty(),
                    validProps.any { it.getElementsByTagNameNS("DAV:", "collection").length > 0 },
                    field("getcontentlength")?.toLongOrNull() ?: 0,
                    runCatching {
                            ZonedDateTime.parse(
                                    field("getlastmodified"),
                                    DateTimeFormatter.RFC_1123_DATE_TIME,
                                )
                                .toInstant()
                                .toEpochMilli()
                        }
                        .getOrDefault(0),
                )
            }
        }
    }

    private fun authorize(
        builder: Request.Builder,
        source: SourceEntity,
        password: String,
    ): Request.Builder {
        if (source.username.isNotEmpty())
            builder.header(
                "Authorization",
                Credentials.basic(source.username, password, Charsets.UTF_8),
            )
        return builder
    }

    private fun listRequest(url: HttpUrl, source: SourceEntity, password: String): Request {
        val body =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        return authorize(
                Request.Builder()
                    .url(url)
                    .header("Depth", "1")
                    .method(
                        "PROPFIND",
                        body.toRequestBody("application/xml; charset=utf-8".toMediaType()),
                    ),
                source,
                password,
            )
            .build()
    }

    fun list(url: HttpUrl, source: SourceEntity, password: String): List<DavEntry> {
        http.newCall(listRequest(url, source, password)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("目录访问失败（${response.code}）")
            val bytes = response.body.byteStream().use { boundedRead(it, 8 * 1024 * 1024) }
            return parse(bytes, url)
        }
    }

    suspend fun walk(source: SourceEntity, password: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val root = (source.root.trimEnd('/') + "/").toHttpUrl()
            var pending = listOf(root)
            val seen = mutableSetOf<String>()
            val files = mutableListOf<FileEntry>()
            val rootDepth = root.pathSegments.dropLastWhile { it.isEmpty() }.size
            while (pending.isNotEmpty()) {
                val nextLevel = mutableListOf<HttpUrl>()
                val uniqueBatch =
                    pending
                        .filter { seen.add(it.toString().trimEnd('/')) }
                        .also {
                            check(seen.size < 10_000 && files.size < 100_000) { "目录过大" }
                        }
                // Match SMB/NFS concurrency: up to 4 parallel PROPFIND calls preserve order and cap
                // load.
                for (batch in uniqueBatch.chunked(4)) {
                    coroutineContext.ensureActive()
                    val responses = coroutineScope {
                        batch
                            .map { directory ->
                                async(Dispatchers.IO) {
                                    directory to
                                        parse(
                                            http.onlineBytes(
                                                listRequest(directory, source, password),
                                                8 * 1024 * 1024,
                                            ),
                                            directory,
                                        )
                                }
                            }
                            .awaitAll()
                    }
                    for ((directory, entries) in responses) {
                        coroutineContext.ensureActive()
                        entries.forEach { entry ->
                            if (!insideRoot(entry.url, root)) return@forEach
                            if (
                                entry.url.encodedPath.trimEnd('/') ==
                                    directory.encodedPath.trimEnd('/')
                            )
                                return@forEach
                            val relativeSegments = entry.url.pathSegments.drop(rootDepth)
                            val path = relativeSegments.filter { it.isNotEmpty() }.joinToString("/")
                            if (path.isBlank() || path.split('/').any { it == ".." }) return@forEach
                            files +=
                                FileEntry(
                                    path,
                                    entry.url.toString(),
                                    entry.directory,
                                    entry.size,
                                    entry.modified,
                                )
                            check(files.size < 100_000) { "目录过大" }
                            if (entry.directory)
                                nextLevel.add(
                                    entry.url
                                        .newBuilder()
                                        .apply {
                                            if (!entry.url.encodedPath.endsWith('/'))
                                                addPathSegment("")
                                        }
                                        .build(),
                                )
                        }
                    }
                }
                pending = nextLevel
            }
            files.distinctBy { it.uri }
        }

    fun bytes(
        url: String,
        source: SourceEntity,
        password: String,
        limit: Int = 8 * 1024 * 1024,
    ): ByteArray {
        val root = (source.root.trimEnd('/') + "/").toHttpUrl()
        require(insideRoot(url.toHttpUrl(), root)) { "文件地址不属于此来源" }
        val request = authorize(Request.Builder().url(url), source, password).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("文件读取失败（${response.code}）")
            val stream = response.body.byteStream()
            return boundedRead(stream, limit)
        }
    }

    fun range(
        url: String,
        source: SourceEntity,
        password: String,
        offset: Long,
        length: Int,
    ): ByteArray {
        val root = (source.root.trimEnd('/') + "/").toHttpUrl()
        require(insideRoot(url.toHttpUrl(), root))
        val request =
            authorize(
                    Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$offset-${offset + length - 1}"),
                    source,
                    password,
                )
                .build()
        http.newCall(request).execute().use { response ->
            require(response.code == 206 || response.code == 200 && offset == 0L) { "服务器不支持分段读取" }
            if (response.code == 206)
                require(response.header("Content-Range")?.startsWith("bytes $offset-") == true) {
                    "分段响应不正确"
                }
            val stream = response.body.byteStream()
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(minOf(8192, length))
            while (output.size() < length) {
                val count = stream.read(buffer, 0, minOf(buffer.size, length - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
