// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

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
        private const val DAV_NS = "DAV:"
        private val status2xxPattern = Regex("\\s2\\d\\d\\s")
        private val propfindBodyBytes =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                .toByteArray(Charsets.UTF_8)
        private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()

        fun sameOrigin(a: HttpUrl, b: HttpUrl) =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port

        fun insideRoot(url: HttpUrl, root: HttpUrl): Boolean {
            val path = root.encodedPath.trimEnd('/')
            return sameOrigin(url, root) &&
                (url.encodedPath == path || url.encodedPath.startsWith("$path/"))
        }

        private class DoctypeRejectedException : SAXException("不支持 DTD")

        private fun hasDoctypeMarker(bytes: ByteArray): Boolean {
            // Fast byte-level scan ignoring NUL padding without allocating full-response Strings.
            val target = "<!doctype"
            var matched = 0
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c == 0) continue
                val lower = if (c in 'A'.code..'Z'.code) c + 32 else c
                if (lower == target[matched].code) {
                    matched++
                    if (matched == target.length) return true
                } else {
                    matched = if (lower == '<'.code) 1 else 0
                }
            }
            return false
        }

        fun parse(bytes: ByteArray, requested: HttpUrl): List<DavEntry> {
            require(!hasDoctypeMarker(bytes)) { "不支持 DTD" }
            val factory =
                SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                }
            val disallowDoctype = runCatching {
                factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true,
                )
            }
                .isSuccess
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false,
                )
            }
            val parser = factory.newSAXParser()
            val entries = mutableListOf<DavEntry>()
            val handler =
                object : DefaultHandler2() {
                    private var inResponse = false
                    private var inPropstat = false
                    private var inProp = false
                    private var inResourceType = false
                    private var captureField: String? = null
                    private val text = StringBuilder()

                    private var href: String? = null
                    private var validPropstat = false
                    private var propDisplayName: String? = null
                    private var propLength: String? = null
                    private var propModified: String? = null
                    private var propCollection = false

                    private var curStatus: String? = null
                    private var curDisplayName: String? = null
                    private var curLength: String? = null
                    private var curModified: String? = null
                    private var curCollection = false

                    override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                        throw DoctypeRejectedException()
                    }

                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: Attributes?,
                    ) {
                        if (uri != DAV_NS) return
                        when (localName) {
                            "response" -> {
                                inResponse = true
                                href = null
                                validPropstat = false
                                propDisplayName = null
                                propLength = null
                                propModified = null
                                propCollection = false
                            }
                            "href" ->
                                if (inResponse && !inPropstat) {
                                    text.setLength(0)
                                    captureField = "href"
                                }
                            "propstat" ->
                                if (inResponse) {
                                    inPropstat = true
                                    curStatus = null
                                    curDisplayName = null
                                    curLength = null
                                    curModified = null
                                    curCollection = false
                                }
                            "prop" -> if (inPropstat) inProp = true
                            "status" ->
                                if (inPropstat && !inProp) {
                                    text.setLength(0)
                                    captureField = "status"
                                }
                            "resourcetype" -> if (inProp) inResourceType = true
                            "collection" -> if (inResourceType) curCollection = true
                            "displayname",
                            "getcontentlength",
                            "getlastmodified" ->
                                if (inProp) {
                                    text.setLength(0)
                                    captureField = localName
                                }
                        }
                    }

                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (captureField != null) text.append(ch, start, length)
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        if (uri != DAV_NS) return
                        when (localName) {
                            "href" ->
                                if (captureField == "href") {
                                    if (href == null) href = text.toString().trim()
                                    captureField = null
                                }
                            "status" ->
                                if (captureField == "status") {
                                    if (curStatus == null) curStatus = text.toString()
                                    captureField = null
                                }
                            "displayname" ->
                                if (captureField == "displayname") {
                                    if (curDisplayName == null)
                                        curDisplayName = text.toString().trim()
                                    captureField = null
                                }
                            "getcontentlength" ->
                                if (captureField == "getcontentlength") {
                                    if (curLength == null) curLength = text.toString().trim()
                                    captureField = null
                                }
                            "getlastmodified" ->
                                if (captureField == "getlastmodified") {
                                    if (curModified == null) curModified = text.toString().trim()
                                    captureField = null
                                }
                            "resourcetype" -> inResourceType = false
                            "prop" -> inProp = false
                            "propstat" -> {
                                if (inPropstat) {
                                    val status = curStatus.orEmpty()
                                    if (status2xxPattern.containsMatchIn("$status ")) {
                                        validPropstat = true
                                        if (propDisplayName == null && curDisplayName != null)
                                            propDisplayName = curDisplayName
                                        if (propLength == null && curLength != null)
                                            propLength = curLength
                                        if (propModified == null && curModified != null)
                                            propModified = curModified
                                        if (curCollection) propCollection = true
                                    }
                                    inPropstat = false
                                }
                            }
                            "response" -> {
                                if (inResponse && validPropstat) {
                                    val rawHref = href
                                    val url = rawHref?.let { requested.resolve(it) }
                                    if (url != null && sameOrigin(url, requested)) {
                                        entries +=
                                            DavEntry(
                                                url,
                                                propDisplayName
                                                    ?: url.pathSegments
                                                        .lastOrNull { it.isNotEmpty() }
                                                        .orEmpty(),
                                                propCollection,
                                                propLength?.toLongOrNull() ?: 0L,
                                                propModified?.let { raw ->
                                                    runCatching {
                                                            ZonedDateTime.parse(
                                                                    raw,
                                                                    DateTimeFormatter
                                                                        .RFC_1123_DATE_TIME,
                                                                )
                                                                .toInstant()
                                                                .toEpochMilli()
                                                        }
                                                        .getOrDefault(0L)
                                                } ?: 0L,
                                            )
                                    }
                                }
                                inResponse = false
                            }
                        }
                    }
                }
            val reader = parser.xmlReader
            val lexicalRegistered = runCatching {
                reader.setProperty(
                    "http://xml.org/sax/properties/lexical-handler",
                    handler,
                )
            }
                .isSuccess
            if (!disallowDoctype && !lexicalRegistered) {
                throw IOException("XML 解析器不支持安全配置")
            }
            reader.entityResolver = EntityResolver { _, _ -> throw DoctypeRejectedException() }
            reader.dtdHandler = handler
            reader.contentHandler = handler
            try {
                reader.parse(InputSource(ByteArrayInputStream(bytes)))
            } catch (e: DoctypeRejectedException) {
                throw IllegalArgumentException("不支持 DTD", e)
            } catch (e: SAXException) {
                if (e.message?.contains("DOCTYPE", ignoreCase = true) == true)
                    throw IllegalArgumentException("不支持 DTD", e)
                throw IOException("WebDAV 目录格式错误", e)
            }
            return entries
        }
    }

    private val davHttp: OkHttpClient =
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun executeInsideRoot(initial: Request, root: HttpUrl): Response {
        var current = initial
        repeat(6) {
            val response = davHttp.newCall(current).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            val nextUrl = location?.let { current.url.resolve(it) }
            val code = response.code
            response.close()
            if (nextUrl == null || !insideRoot(nextUrl, root)) {
                throw IOException("文件地址不属于此来源")
            }
            val nextBuilder = current.newBuilder().url(nextUrl)
            if (code == 303 && current.method != "GET" && current.method != "HEAD") {
                nextBuilder.get()
            }
            current = nextBuilder.build()
        }
        throw IOException("WebDAV 重定向次数过多")
    }

    private suspend fun bytesInsideRoot(
        initial: Request,
        root: HttpUrl,
        limit: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            var current = initial
            repeat(6) {
                coroutineContext.ensureActive()
                val next = coroutineScope {
                    val call = davHttp.newCall(current)
                    val done = AtomicBoolean(false)
                    val cancelWatcher =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                if (!done.get()) call.cancel()
                            }
                        }
                    try {
                        call.execute().use { response ->
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                val location = response.header("Location")
                                val nextUrl = location?.let { current.url.resolve(it) }
                                val code = response.code
                                if (nextUrl == null || !insideRoot(nextUrl, root)) {
                                    throw IOException("文件地址不属于此来源")
                                }
                                val nextBuilder = current.newBuilder().url(nextUrl)
                                if (
                                    code == 303 &&
                                        current.method != "GET" &&
                                        current.method != "HEAD"
                                ) {
                                    nextBuilder.get()
                                }
                                nextBuilder.build() to null
                            } else {
                                if (!response.isSuccessful) throw OnlineHttpException(response.code)
                                null to boundedRead(response.body.byteStream(), limit)
                            }
                        }
                    } catch (e: Exception) {
                        coroutineContext.ensureActive()
                        throw e
                    } finally {
                        done.set(true)
                        cancelWatcher.cancel()
                    }
                }
                val body = next.second
                if (body != null) return@withContext body
                current = next.first!!
            }
            throw IOException("WebDAV 重定向次数过多")
        }

    private fun authHeader(source: SourceEntity, password: String): String? =
        if (source.username.isNotEmpty())
            Credentials.basic(source.username, password, Charsets.UTF_8)
        else null

    private fun listRequest(url: HttpUrl, authorization: String?): Request =
        Request.Builder()
            .url(url)
            .header("Depth", "1")
            .apply { if (authorization != null) header("Authorization", authorization) }
            .method("PROPFIND", propfindBodyBytes.toRequestBody(xmlMediaType))
            .build()

    fun list(url: HttpUrl, source: SourceEntity, password: String): List<DavEntry> {
        val root = (source.root.trimEnd('/') + "/").toHttpUrl()
        require(insideRoot(url, root)) { "文件地址不属于此来源" }
        val authorization = authHeader(source, password)
        executeInsideRoot(listRequest(url, authorization), root).use { response ->
            if (!response.isSuccessful) throw IOException("目录访问失败（${response.code}）")
            val bytes = response.body.byteStream().use { boundedRead(it, 8 * 1024 * 1024) }
            return parse(bytes, url)
        }
    }

    suspend fun walk(source: SourceEntity, password: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val root = (source.root.trimEnd('/') + "/").toHttpUrl()
            val authorization = authHeader(source, password)
            val seen = mutableSetOf<String>()
            val files = mutableListOf<FileEntry>()
            val rootDepth = root.pathSegments.dropLastWhile { it.isEmpty() }.size
            seen.add(root.toString().trimEnd('/'))
            walkTreeConcurrent(
                root = root,
                concurrency = 4,
                listOne = { _, directory ->
                    parse(
                        bytesInsideRoot(
                            listRequest(directory, authorization),
                            root,
                            8 * 1024 * 1024,
                        ),
                        directory,
                    )
                },
                onDirectory = { directory, entries, enqueue ->
                    entries.forEach { entry ->
                        if (!insideRoot(entry.url, root)) return@forEach
                        if (
                            entry.url.encodedPath.trimEnd('/') == directory.encodedPath.trimEnd('/')
                        )
                            return@forEach
                        val segments =
                            entry.url.pathSegments.drop(rootDepth).filter { it.isNotEmpty() }
                        val path = segments.joinToString("/")
                        if (path.isBlank() || segments.any { it == ".." }) return@forEach
                        files +=
                            FileEntry(
                                path,
                                entry.url.toString(),
                                entry.directory,
                                entry.size,
                                entry.modified,
                            )
                        check(files.size < 100_000) { "目录过大" }
                        if (entry.directory && segments.size <= 32) {
                            val nextDir =
                                entry.url
                                    .newBuilder()
                                    .apply {
                                        if (!entry.url.encodedPath.endsWith('/')) addPathSegment("")
                                    }
                                    .build()
                            if (seen.add(nextDir.toString().trimEnd('/'))) {
                                check(seen.size < 10_000) { "目录过大" }
                                enqueue(nextDir)
                            }
                        }
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
        val authorization = authHeader(source, password)
        val request =
            Request.Builder()
                .url(url)
                .apply { if (authorization != null) header("Authorization", authorization) }
                .build()
        executeInsideRoot(request, root).use { response ->
            if (!response.isSuccessful) throw IOException("文件读取失败（${response.code}）")
            return boundedRead(response.body.byteStream(), limit)
        }
    }

    fun copyToSink(
        url: String,
        source: SourceEntity,
        password: String,
        sink: okio.BufferedSink,
        limit: Int = 8 * 1024 * 1024,
    ): Long {
        val root = (source.root.trimEnd('/') + "/").toHttpUrl()
        require(insideRoot(url.toHttpUrl(), root)) { "文件地址不属于此来源" }
        val authorization = authHeader(source, password)
        val request =
            Request.Builder()
                .url(url)
                .apply { if (authorization != null) header("Authorization", authorization) }
                .build()
        executeInsideRoot(request, root).use { response ->
            if (!response.isSuccessful) throw IOException("文件读取失败（${response.code}）")
            return copyBounded(response.body.byteStream(), sink, limit)
        }
    }
}
