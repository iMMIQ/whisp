// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

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

    fun entity() =
        WorkEntity(
            workId,
            rj,
            title,
            title,
            artist = artist,
            originalArtist = artist,
            coverUrl = coverUrl.takeIf { coverPath.startsWith("_dlsite-cover/") },
            series = series,
            coverId = coverId,
        )
}

fun isAsmrOneWork(work: WorkEntity): Boolean {
    val id = work.rj?.removePrefix("RJ")?.toLongOrNull() ?: return false
    return work.id == stableId("asmr.one|work|$id")
}

data class AsmrPage(val works: List<AsmrWork>, val hasMore: Boolean)

class AsmrOneClient(
    private val http: OkHttpClient,
    private val root: HttpUrl = API_ROOT.toHttpUrl(),
) {
    companion object {
        const val API_ROOT = "https://api.asmr.one/"
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
        val body = json(url.build()).jsonObject
        val works = body["works"]?.jsonArray ?: throw IOException("作品列表格式错误")
        val items = works.map { parseWork(it.jsonObject) }.distinctBy { it.id }
        val total = body["pagination"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.longOrNull
        return AsmrPage(
            items,
            works.isNotEmpty() && (total?.let { page * 20L < it } ?: (works.size == 20)),
        )
    }

    suspend fun work(id: Long): AsmrWork {
        require(id > 0)
        return parseWork(
            json(
                    root
                        .newBuilder()
                        .addPathSegments("api/workInfo")
                        .addPathSegment(id.toString())
                        .build(),
                )
                .jsonObject,
        )
    }

    suspend fun index(work: AsmrWork, sourceId: String, generation: Long): IndexedLibrary {
        val tree =
            json(
                    root
                        .newBuilder()
                        .addPathSegments("api/tracks")
                        .addPathSegment(work.id.toString())
                        .build(),
                )
                .jsonArray
        val files = mutableListOf<FileEntry>()
        val durations = mutableMapOf<String, Long>()
        val paths = mutableSetOf<String>()
        var visited = 0
        fun walk(nodes: JsonArray, parent: String, depth: Int) {
            require(depth <= 32) { "作品目录层级过多" }
            for (element in nodes) {
                require(++visited <= 20000) { "作品文件数量过多" }
                val node = element.jsonObject
                val name = node.text("title").replace(illegalPathChars, "_").trim()
                if (name.isEmpty() || name in setOf(".", "..")) continue
                val path = "$parent/$name"
                val children = node["children"] as? JsonArray
                if (node.text("type") == "folder") {
                    if (children != null) walk(children, path, depth + 1)
                    continue
                }
                val raw = node.text("mediaStreamUrl").ifBlank { node.text("mediaDownloadUrl") }
                if (raw.isBlank()) continue
                val url = resourceUrl(raw)
                val type = node.text("type")
                val media = type in setOf("audio", "video")
                val extensions = if (type == "video") MediaFormats.video else MediaFormats.audio
                val filePath =
                    if (media && name.substringAfterLast('.', "").lowercase() !in extensions) {
                        val extension =
                            url.pathSegments
                                .lastOrNull()
                                ?.substringAfterLast('.', "")
                                ?.lowercase()
                                ?.takeIf { it in extensions }
                                ?: if (type == "video") "mp4" else "mp3"
                        "$path.$extension"
                    } else path
                require(paths.add(filePath)) { "作品目录存在重复文件名" }
                files +=
                    FileEntry(
                        filePath,
                        url.toString(),
                        false,
                        node["size"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0,
                    )
                if (media) {
                    val seconds = node["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    if (seconds.isFinite() && seconds > 0)
                        durations[filePath.removePrefix("${work.rj}/")] = (seconds * 1000).toLong()
                }
            }
        }
        walk(tree, work.rj, 0)
        work.coverUrl?.let {
            files += FileEntry("${work.rj}/${work.coverPath}", resourceUrl(it).toString(), false)
        }
        val indexed = LibraryIndexer.index(files, sourceId, generation, work.entity())
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

    private suspend fun json(url: HttpUrl): JsonElement =
        try {
            val bytes = http.onlineBytes(request(url), 4 * 1024 * 1024)
            withContext(Dispatchers.Default) {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
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
                else MEDIA_HEADERS.forEach { (k, v) -> header(k, v) }
            }
            .build()

    private fun parseWork(body: JsonObject): AsmrWork {
        val id = body["id"]?.jsonPrimitive?.longOrNull ?: throw IOException("作品编号无效")
        require(id > 0) { "作品编号无效" }
        val sourceId = body.text("source_id").uppercase()
        val rj =
            sourceId.takeIf { it.startsWith("RJ") && it.drop(2).toLongOrNull() == id }
                ?: "RJ" + id.toString().padStart(if (id >= 1000000) 8 else 6, '0')
        val voices =
            (body["vas"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank)
            }
        val artist =
            voices.joinToString(" / ").ifBlank {
                (body["circle"] as? JsonObject)?.text("name").orEmpty()
            }
        val cover =
            body
                .text("mainCoverUrl")
                .ifBlank { body.text("samCoverUrl") }
                .takeIf(String::isNotBlank)
                ?.let { resourceUrl(it).toString() }
        val facets = mutableListOf<AsmrFacet>()
        fun add(kind: String, name: String) {
            if (name.isNotBlank() && name.length <= 160 && '$' !in name)
                facets += AsmrFacet(kind, name)
        }
        voices.forEach { add("va", it) }
        add("circle", (body["circle"] as? JsonObject)?.text("name").orEmpty())
        (body["tags"] as? JsonArray).orEmpty().forEach {
            add("tagw", (it as? JsonObject)?.text("name").orEmpty())
        }
        return AsmrWork(
            id,
            body.text("title").ifBlank { rj },
            rj,
            artist,
            cover,
            facets = facets.distinct(),
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
