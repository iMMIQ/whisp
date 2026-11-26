// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class AsmrFacet(val kind: String, val name: String) {
    init {
        require(kind in setOf("va", "circle", "tagw"))
        require(name.isNotBlank() && name.length <= 160 && '$' !in name)
    }

    val label
        get() =
            when (kind) {
                "va" -> "声优"
                "circle" -> "社团"
                else -> "标签"
            }

    val token
        get() = "$$kind:$name$"
}

@Serializable
data class AsmrQuery(
    val sourceId: String,
    val text: String = "",
    val order: String = "create_date",
    val subtitlesOnly: Boolean = false,
    val facets: List<AsmrFacet> = emptyList(),
) {
    val searching
        get() = text.isNotBlank() || facets.isNotEmpty() || subtitlesOnly

    val serverText
        get() =
            (listOf(normalizedAsmrInput(text)) + facets.distinct().map { it.token })
                .filter(String::isNotBlank)
                .joinToString(" ")
}

fun normalizedAsmrInput(input: String): String =
    Normalizer.normalize(input, Normalizer.Form.NFKC).trim()

fun asmrWorkNumber(input: String): Long? {
    val text = normalizedAsmrInput(input)
    val rj = Regex("(?i)RJ([0-9]{6,8})")
    rj.matchEntire(text)?.let {
        return it.groupValues[1].toLongOrNull()?.takeIf { id -> id > 0 }
    }
    val url = text.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val host = url.host
    if (host == "asmr.one" || host.endsWith(".asmr.one")) {
        val segments = url.pathSegments
        if (segments.size == 2 && segments[0] == "work")
            return segments[1].removePrefix("RJ").toLongOrNull()?.takeIf { it > 0 }
    }
    if (host == "dlsite.com" || host.endsWith(".dlsite.com")) {
        val match = Regex("(?i)/product_id/(RJ[0-9]{6,8})\\.html/?$").find(url.encodedPath)
        return match?.groupValues?.get(1)?.let(::asmrWorkNumber)
    }
    return null
}

private val asmrJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun encodeAsmrQuery(query: AsmrQuery): String = asmrJson.encodeToString(query)

internal fun decodeAsmrQuery(data: String?): AsmrQuery? = data?.let {
    runCatching { asmrJson.decodeFromString<AsmrQuery>(it) }.getOrNull()
}

data class AsmrWork(
    val id: Long,
    val title: String,
    val rj: String,
    val artist: String,
    val coverUrl: String?,
    val coverPath: String = "_asmr-cover/main.jpg",
    val series: String? = null,
    val facets: List<AsmrFacet> = emptyList(),
) {
    val workId
        get() = stableId("asmr.one|work|$id")

    val coverId
        get() = coverUrl?.let { stableId("$workId|$coverPath") }

    fun entity(sourceId: String? = null) =
        WorkEntity(
            id = workId,
            rj = rj,
            title = title,
            originalTitle = title,
            artist = artist,
            originalArtist = artist,
            coverUrl = coverUrl.takeIf { coverPath.startsWith("_dlsite-cover/") },
            series = series,
            coverId = coverId,
            origin = SourceKind.ASMRONE.raw,
            sourceId = sourceId,
        )
}

data class AsmrPage(val works: List<AsmrWork>, val hasMore: Boolean)

data class AsmrPreview(
    val sourceId: String,
    val catalogArtist: String,
    val work: AsmrWork,
    val indexed: IndexedLibrary,
    val metadata: DlsiteDetails? = null,
) {
    val details
        get() = WorkDetails(indexed.works.single(), indexed.tracks, indexed.attachments)
}

@Serializable private data class AsmrNamedDto(val name: String = "")

@Serializable private data class AsmrPaginationDto(val totalCount: Long? = null)

@Serializable
private data class AsmrWorkDto(
    val id: Long? = null,
    @SerialName("source_id") val sourceId: String = "",
    val title: String = "",
    val vas: List<AsmrNamedDto>? = null,
    val circle: AsmrNamedDto? = null,
    val tags: List<AsmrNamedDto>? = null,
    val mainCoverUrl: String = "",
    val samCoverUrl: String = "",
)

@Serializable
private data class AsmrCatalogDto(
    val works: List<AsmrWorkDto>? = null,
    val pagination: AsmrPaginationDto? = null,
)

@Serializable
private data class AsmrTreeNodeDto(
    val type: String = "",
    val title: String = "",
    val mediaStreamUrl: String = "",
    val mediaDownloadUrl: String = "",
    val size: Long? = null,
    val duration: Double? = null,
    val children: List<AsmrTreeNodeDto>? = null,
)

class AsmrOneClient(
    baseHttp: OkHttpClient,
    private val root: HttpUrl = API_ROOT.toHttpUrl(),
    private val bearerToken: String = "",
) {
    private val http: OkHttpClient =
        baseHttp
            .newBuilder()
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                resourceUrl(chain.request().url.toString())
                chain.proceed(chain.request())
            }
            .build()

    companion object {
        const val API_ROOT = "https://api.asmr.one/"
        val MIRROR_PRESETS =
            listOf(
                "https://api.asmr.one/",
                "https://api.asmr-100.com/",
                "https://api.asmr-200.com/",
                "https://api.asmr-300.com/",
            )
        val MEDIA_HEADERS = mapOf("Origin" to "https://asmr.one", "Referer" to "https://asmr.one/")
        private val illegalPathChars = Regex("[\\\\/\\u0000]")
    }

    suspend fun catalog(
        page: Int,
        query: String = "",
        order: String = "create_date",
        subtitlesOnly: Boolean = false,
    ): AsmrPage {
        require(page > 0 && order in setOf("create_date", "dl_count"))
        val url = root.newBuilder().addPathSegment("api")
        if (query.isBlank()) url.addPathSegment("works")
        else url.addPathSegment("search").addPathSegment(query.trim())
        url.addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("order", order)
            .addQueryParameter("sort", "desc")
            .addQueryParameter("subtitle", if (subtitlesOnly) "1" else "0")
        val body = decode<AsmrCatalogDto>(url.build())
        val works = body.works ?: throw IOException("作品列表格式错误")
        val items = works.map(::toWork).distinctBy { it.id }
        val total = body.pagination?.totalCount
        return AsmrPage(
            items,
            works.isNotEmpty() && (total?.let { page * 20L < it } ?: (works.size == 20)),
        )
    }

    suspend fun work(id: Long): AsmrWork {
        require(id > 0)
        val url =
            root.newBuilder().addPathSegments("api/workInfo").addPathSegment(id.toString()).build()
        return toWork(decode<AsmrWorkDto>(url))
    }

    suspend fun index(work: AsmrWork, sourceId: String): IndexedLibrary {
        val url =
            root
                .newBuilder()
                .addPathSegments("api/tracks")
                .addPathSegment(work.id.toString())
                .build()
        val tree = decode<List<AsmrTreeNodeDto>>(url)
        val files = mutableListOf<FileEntry>()
        val durations = mutableMapOf<String, Long>()
        val paths = mutableSetOf<String>()
        var visited = 0
        fun walk(nodes: List<AsmrTreeNodeDto>, parent: String, depth: Int) {
            require(depth <= 32) { "作品目录层级过多" }
            for (node in nodes) {
                require(++visited <= 20_000) { "作品文件数量过多" }
                val name = node.title.replace(illegalPathChars, "_").trim()
                if (name.isEmpty() || name in setOf(".", "..")) continue
                val path = "$parent/$name"
                if (node.type == "folder") {
                    node.children?.let { walk(it, path, depth + 1) }
                    continue
                }
                val raw = node.mediaStreamUrl.ifBlank { node.mediaDownloadUrl }
                if (raw.isBlank()) continue
                val resource = resourceUrl(raw)
                val media = node.type in setOf("audio", "video")
                val extensions =
                    if (node.type == "video") MediaFormats.video else MediaFormats.audio
                val filePath =
                    if (media && name.substringAfterLast('.', "").lowercase() !in extensions) {
                        val ext =
                            resource.pathSegments
                                .lastOrNull()
                                ?.substringAfterLast('.', "")
                                ?.lowercase()
                                ?.takeIf { it in extensions }
                                ?: if (node.type == "video") "mp4" else "mp3"
                        "$path.$ext"
                    } else path
                require(paths.add(filePath)) { "作品目录存在重复文件名" }
                files +=
                    FileEntry(
                        filePath,
                        resource.toString(),
                        false,
                        (node.size ?: 0L).coerceAtLeast(0),
                    )
                if (media) {
                    val seconds = node.duration ?: 0.0
                    if (seconds.isFinite() && seconds > 0)
                        durations[filePath.removePrefix("${work.rj}/")] = (seconds * 1000).toLong()
                }
            }
        }
        walk(tree, work.rj, 0)
        work.coverUrl?.let {
            files += FileEntry("${work.rj}/${work.coverPath}", resourceUrl(it).toString(), false)
        }
        val indexed = LibraryIndexer.index(files, sourceId, work.entity(sourceId))
        require(indexed.tracks.isNotEmpty()) { "作品暂无可播放音轨" }
        return indexed.copy(
            attachments =
                indexed.attachments.map {
                    if (it.relativePath == work.coverPath) it.copy(kind = "cover") else it
                },
            tracks = indexed.tracks.map { it.copy(durationMs = durations[it.relativePath] ?: 0) },
        )
    }

    suspend fun bytes(url: String, limit: Int): ByteArray =
        http.onlineBytes(request(resourceUrl(url)), limit)

    suspend fun copyToSink(url: String, sink: okio.BufferedSink, limit: Int): Long =
        http.onlineCopyToSink(request(resourceUrl(url)), sink, limit)

    private suspend inline fun <reified T> decode(url: HttpUrl): T =
        try {
            val bytes = http.onlineBytes(request(url), 4 * 1024 * 1024)
            withContext(Dispatchers.Default) {
                asmrJson.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("ASMR.ONE 读取失败", e)
        }

    private fun resourceUrl(value: String): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw IOException("文件地址无效")
        val localTest =
            root.host in setOf("localhost", "127.0.0.1", "10.0.2.2") && url.host == root.host
        if (
            !((url.scheme == "https" || localTest) &&
                url.username.isEmpty() &&
                url.password.isEmpty())
        )
            throw IOException("文件地址须使用 HTTPS")
        return url
    }

    private fun request(url: HttpUrl) =
        Request.Builder()
            .url(url)
            .apply {
                val dlsite =
                    listOf("dlsite.com", "dlsite.jp").any {
                        url.host == it || url.host.endsWith(".$it")
                    }
                if (dlsite) header("Referer", "https://www.dlsite.com/")
                else {
                    MEDIA_HEADERS.forEach { (k, v) -> header(k, v) }
                    if (bearerToken.isNotBlank() && url.host == root.host) {
                        header("Authorization", "Bearer $bearerToken")
                    }
                }
            }
            .build()

    private fun toWork(dto: AsmrWorkDto): AsmrWork {
        val id = dto.id ?: throw IOException("作品编号无效")
        require(id > 0) { "作品编号无效" }
        val rawSourceId = dto.sourceId.uppercase()
        val rj =
            rawSourceId.takeIf { it.startsWith("RJ") && it.drop(2).toLongOrNull() == id }
                ?: "RJ" + id.toString().padStart(if (id >= 1_000_000) 8 else 6, '0')
        val voices = dto.vas.orEmpty().mapNotNull { it.name.takeIf(String::isNotBlank) }
        val circleName = dto.circle?.name.orEmpty()
        val artist = voices.joinToString(" / ").ifBlank { circleName }
        val cover =
            dto.mainCoverUrl
                .ifBlank { dto.samCoverUrl }
                .takeIf(String::isNotBlank)
                ?.let { resourceUrl(it).toString() }
        val facets = mutableListOf<AsmrFacet>()
        fun add(kind: String, name: String) {
            if (name.isNotBlank() && name.length <= 160 && '$' !in name)
                facets += AsmrFacet(kind, name)
        }
        voices.forEach { add("va", it) }
        add("circle", circleName)
        dto.tags.orEmpty().forEach { add("tagw", it.name) }
        return AsmrWork(id, dto.title.ifBlank { rj }, rj, artist, cover, facets = facets.distinct())
    }
}
