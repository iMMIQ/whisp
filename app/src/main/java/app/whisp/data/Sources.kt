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
import kotlinx.coroutines.Dispatchers
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
            val seen = mutableSetOf<String>()
            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
            walkTree(
                root = rootId to "",
                concurrency = 1,
                listBatch = { batch ->
                    batch.mapNotNull { node ->
                        val (id, _) = node
                        check(seen.size < 100_000) { "目录过大" }
                        if (!seen.add(id)) return@mapNotNull null
                        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                        val rows = mutableListOf<Pair<String, FileEntry>>()
                        val cursor =
                            context.contentResolver.query(children, projection, null, null, null)
                                ?: throw IOException("无法读取目录")
                        cursor.use {
                            while (it.moveToNext()) {
                                val docId = it.getString(0)
                                val name = it.getString(1)
                                val path =
                                    if (node.second.isBlank()) name else "${node.second}/$name"
                                val directory =
                                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                                rows +=
                                    docId to
                                        FileEntry(
                                            path,
                                            DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                                                .toString(),
                                            directory,
                                            if (it.isNull(3)) 0 else it.getLong(3),
                                            if (it.isNull(4)) 0 else it.getLong(4),
                                        )
                            }
                        }
                        node to rows
                    }
                },
                onDirectory = { _, rows, enqueue ->
                    for ((docId, entry) in rows) {
                        files += entry
                        if (entry.directory) enqueue(docId to entry.path)
                    }
                },
            )
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
            val seen = mutableSetOf<String>()
            val files = mutableListOf<FileEntry>()
            val rootDepth = root.pathSegments.dropLastWhile { it.isEmpty() }.size
            walkTree(
                root = root,
                concurrency = 4,
                listBatch = { batch ->
                    val unique =
                        batch
                            .filter { seen.add(it.toString().trimEnd('/')) }
                            .also {
                                check(seen.size < 10_000 && files.size < 100_000) { "目录过大" }
                            }
                    parallelList(unique) { _, directory ->
                        parse(
                            http.onlineBytes(
                                listRequest(directory, source, password),
                                8 * 1024 * 1024,
                            ),
                            directory,
                        )
                    }
                },
                onDirectory = { directory, entries, enqueue ->
                    entries.forEach { entry ->
                        if (!insideRoot(entry.url, root)) return@forEach
                        if (
                            entry.url.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/')
                        )
                            return@forEach
                        val path =
                            entry.url.pathSegments
                                .drop(rootDepth)
                                .filter { it.isNotEmpty() }
                                .joinToString("/")
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
                            enqueue(
                                entry.url
                                    .newBuilder()
                                    .apply {
                                        if (!entry.url.encodedPath.endsWith('/')) addPathSegment("")
                                    }
                                    .build(),
                            )
                    }
                },
            )
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
            return boundedRead(response.body.byteStream(), limit)
        }
    }
}
