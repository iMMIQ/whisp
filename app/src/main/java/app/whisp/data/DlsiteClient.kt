// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal fun dlsiteShop(number: String) =
    when {
        number.startsWith("VJ") -> "pro"
        number.startsWith("BJ") -> "books"
        else -> "maniax"
    }

private val dlsiteWorkNumber = Regex("(?:RJ|VJ|BJ)[0-9]{6,8}")

internal fun validDlsiteWorkno(number: String) = dlsiteWorkNumber.matches(number)

fun dlsiteWorkUrl(number: String) =
    "https://www.dlsite.com/${dlsiteShop(number)}/work/=/product_id/$number.html"

data class DlsiteMetadata(
    val rj: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val series: String?,
    val details: DlsiteDetails = DlsiteDetails(rj),
) {
    val coverPath
        get() = "_dlsite-cover/${stableId(coverUrl)}.jpg"

    fun withPrevious(previous: DlsiteDetails?): DlsiteMetadata {
        val merged = details.preserving(previous)
        val voices = merged.credits.filter { it.role == "voice_by" }.joinToString(" / ") { it.name }
        return copy(details = merged, artist = voices.ifBlank { artist })
    }

    fun applyTo(work: AsmrWork) =
        work.copy(
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            coverPath = coverPath,
            series = series,
        )
}

class DlsiteClient(
    baseHttp: OkHttpClient,
    private val root: HttpUrl = "https://www.dlsite.com/".toHttpUrl(),
) {
    private val http: OkHttpClient =
        baseHttp
            .newBuilder()
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (!isAllowedUrl(url)) throw IOException("DLsite 地址无效")
                chain.proceed(chain.request())
            }
            .build()

    private fun isAllowedUrl(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val loopback = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        val localTest =
            loopback &&
                (url.host == root.host ||
                    runCatching {
                            android.security.NetworkSecurityPolicy.getInstance()
                                .isCleartextTrafficPermitted(url.host)
                        }
                        .getOrDefault(false))
        if (!(url.scheme == "https" || localTest)) return false
        return localTest ||
            listOf("dlsite.com", "dlsite.jp").any {
                url.host == it || url.host.endsWith(".$it")
            }
    }

    suspend fun fetchBytes(url: String, limit: Int = 16 * 1024 * 1024): ByteArray {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("DLsite 地址无效")
        if (!isAllowedUrl(parsed)) throw IOException("DLsite 地址无效")
        return http.onlineBytes(
            Request.Builder().url(parsed).header("Referer", "https://www.dlsite.com/").build(),
            limit,
        )
    }

    suspend fun copyToSink(
        url: String,
        sink: okio.BufferedSink,
        limit: Int = 16 * 1024 * 1024,
    ): Long {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("DLsite 地址无效")
        if (!isAllowedUrl(parsed)) throw IOException("DLsite 地址无效")
        return http.onlineCopyToSink(
            Request.Builder().url(parsed).header("Referer", "https://www.dlsite.com/").build(),
            sink,
            limit,
        )
    }

    suspend fun work(number: String): DlsiteMetadata {
        val rj = number.uppercase()
        require(validDlsiteWorkno(rj)) { "作品编号无效" }
        val full =
            root
                .newBuilder()
                .addPathSegments("${dlsiteShop(rj)}/api/=/product.json")
                .addQueryParameter("workno", rj)
                .addQueryParameter("locale", "ja_JP")
                .build()
        try {
            val data = read(full)
            return withContext(Dispatchers.Default) { parseProduct(rj, data) }
        } catch (e: CancellationException) {
            throw e
        } catch (primary: Exception) {
            val fallback =
                root
                    .newBuilder()
                    .addPathSegments("${dlsiteShop(rj)}/product/info/ajax")
                    .addQueryParameter("product_id", rj)
                    .addQueryParameter("locale", "ja_JP")
                    .build()
            try {
                val data = read(fallback)
                return withContext(Dispatchers.Default) { parseInfo(rj, data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.addSuppressed(primary)
                val message =
                    when (e) {
                        is OnlineHttpException -> "DLsite 连接失败（${e.code}）"
                        is java.net.SocketTimeoutException -> "DLsite 连接超时"
                        else -> e.message?.takeIf { it.startsWith("DLsite ") } ?: "DLsite 元数据读取失败"
                    }
                throw IOException(message, e)
            }
        }
    }

    private suspend fun read(url: HttpUrl) =
        http
            .onlineBytes(
                Request.Builder().url(url).header("Accept-Language", "ja-JP").build(),
                4 * 1024 * 1024,
            )
            .toString(Charsets.UTF_8)

    internal fun parseProduct(rj: String, data: String): DlsiteMetadata {
        val items =
            Json.parseToJsonElement(data) as? JsonArray ?: throw IOException("DLsite 作品数据格式错误")
        val item =
            items
                .mapNotNull { it as? JsonObject }
                .firstOrNull {
                    it.text("product_id") == rj || it.text("workno") == rj
                } ?: throw IOException("DLsite 未找到该作品")
        require(item.text("product_id").ifBlank { item.text("workno") } == rj) {
            "DLsite 作品编号不匹配"
        }
        return metadata(rj, item, (item["image_main"] as? JsonObject)?.text("url").orEmpty(), true)
    }

    internal fun parseInfo(rj: String, data: String): DlsiteMetadata {
        val item =
            (Json.parseToJsonElement(data) as? JsonObject)?.get(rj) as? JsonObject
                ?: throw IOException("DLsite 未找到该作品")
        // The official keyed response can omit product_id; an explicit conflicting ID is invalid.
        require(item.text("product_id").ifBlank { rj } == rj) { "DLsite 作品编号不匹配" }
        return metadata(rj, item, item.text("work_image"))
    }

    private fun metadata(
        rj: String,
        item: JsonObject,
        image: String,
        complete: Boolean = false,
    ): DlsiteMetadata {
        val title = Parser.unescapeEntities(item.text("work_name"), false).trim()
        require(title.isNotBlank()) { "DLsite 作品名为空" }
        val details = parseDetails(rj, item, complete)
        val artist =
            details.credits
                .filter { it.role == "voice_by" }
                .joinToString(" / ") { it.name }
                .ifBlank { details.circle.orEmpty() }
        val cover = imageUrl(image) ?: throw IOException("DLsite 封面地址无效")
        return DlsiteMetadata(
            rj,
            title,
            artist,
            cover,
            details.series,
            details,
        )
    }

    private fun imageUrl(image: String): String? {
        val cover =
            (if (image.startsWith("//")) "https:$image" else image).toHttpUrlOrNull() ?: return null
        val localTest =
            root.host in setOf("localhost", "127.0.0.1", "10.0.2.2") && cover.host == root.host
        require(
            (cover.scheme == "https" || localTest) &&
                cover.username.isEmpty() &&
                cover.password.isEmpty(),
        ) {
            "DLsite 封面地址无效"
        }
        require(
            localTest ||
                listOf("dlsite.com", "dlsite.jp").any {
                    cover.host == it || cover.host.endsWith(".$it")
                },
        ) {
            "DLsite 封面来源无效"
        }
        return cover.toString()
    }

    private fun optionalImage(value: String): String? = runCatching { imageUrl(value) }.getOrNull()

    private fun cleanText(value: String): String =
        Parser.unescapeEntities(value, false).trim().take(1000)

    private fun JsonObject.optional(key: String): String? =
        cleanText(text(key)).takeIf(String::isNotBlank)

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

    private fun rj(value: String?): String? = value?.uppercase()?.takeIf(::validDlsiteWorkno)

    private fun languages(value: String): List<String> =
        value.split(',').map(String::trim).filter(String::isNotBlank).distinct().take(20)

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun relations(item: JsonObject, key: String, self: String): List<DlsiteRelatedWork> =
        item
            .objects(key)
            .mapNotNull { value ->
                val id = rj(value.optional("workno")) ?: return@mapNotNull null
                if (id == self) return@mapNotNull null
                DlsiteRelatedWork(
                    id,
                    value.optional("label") ?: value.optional("work_name").orEmpty(),
                    languages(value.text("lang")),
                )
            }
            .distinctBy { it.rj }
            .take(100)

    private fun parseDetails(number: String, item: JsonObject, complete: Boolean): DlsiteDetails {
        val creators = item["creaters"] as? JsonObject
        val credits =
            creators
                ?.entries
                .orEmpty()
                .flatMap { (role, values) ->
                    (values as? JsonArray).orEmpty().mapNotNull { value ->
                        val creator = value as? JsonObject ?: return@mapNotNull null
                        val name = creator.optional("name") ?: return@mapNotNull null
                        DlsiteCredit(role.take(80), name, creator.optional("id"))
                    }
                }
                .distinct()
                .take(100)
        val translation = item["translation_info"] as? JsonObject
        val original = rj(translation?.optional("original_workno"))?.takeUnless { it == number }
        val parent = rj(translation?.optional("parent_workno"))?.takeUnless { it == number }
        val editions = relations(item, "language_editions", number).toMutableList()
        (translation?.get("child_worknos") as? JsonArray).orEmpty().forEach { value ->
            rj((value as? JsonPrimitive)?.contentOrNull)
                ?.takeUnless { it == number }
                ?.let { editions += DlsiteRelatedWork(it) }
        }
        return DlsiteDetails(
            rj = number,
            circleId = item.optional("maker_id") ?: item.optional("brand_id"),
            circle = item.optional("maker_name") ?: item.optional("brand_name"),
            released =
                item.optional("regist_date")?.take(10)?.takeIf {
                    runCatching { java.time.LocalDate.parse(it) }.isSuccess
                },
            ageRating = item.optional("age_category_string"),
            workType = item.optional("work_type_string"),
            formats =
                listOfNotNull(item.optional("file_type_string"), item.optional("file_type_special"))
                    .distinct(),
            packageBytes = item.long("contents_file_size"),
            credits = credits,
            genres =
                item
                    .objects("genres")
                    .mapNotNull { value ->
                        value.optional("name")?.let {
                            DlsiteGenre(value.optional("id").orEmpty(), it)
                        }
                    }
                    .distinct()
                    .take(80),
            seriesId = item.optional("series_id") ?: item.optional("title_id"),
            series = item.optional("series_name") ?: item.optional("title_name"),
            seriesWorks =
                if (item.optional("series_id") != null || item.optional("title_id") != null)
                    relations(item, "editions", number)
                else emptyList(),
            relatedEditions =
                if (item.optional("series_id") == null && item.optional("title_id") == null)
                    relations(item, "editions", number)
                else emptyList(),
            originalRj = original,
            translationParentRj = parent,
            translated = original != null,
            translationLanguage = translation?.optional("lang"),
            languageEditions = editions.distinctBy { it.rj }.take(100),
            summary = item.optional("intro_s")?.let { Jsoup.parseBodyFragment(it).text() },
            images =
                item
                    .objects("image_samples")
                    .mapNotNull { optionalImage(it.text("url")) }
                    .distinct()
                    .take(40),
            productFetchedAt = System.currentTimeMillis(),
            completeProduct = complete,
            availableFields =
                item.entries
                    .filter { (key, value) ->
                        when (key) {
                            "creaters",
                            "translation_info" -> value is JsonObject
                            "genres",
                            "image_samples",
                            "editions",
                            "language_editions" -> value is JsonArray
                            else -> false
                        }
                    }
                    .map { it.key }
                    .toSet(),
        )
    }

    suspend fun statistics(number: String): DlsiteStatistics {
        val id = rj(number) ?: error("作品编号无效")
        val url =
            root
                .newBuilder()
                .addPathSegments("${dlsiteShop(id)}/product/info/ajax")
                .addQueryParameter("product_id", id)
                .addQueryParameter("locale", "ja_JP")
                .build()
        return withContext(Dispatchers.Default) { parseStatistics(id, read(url)) }
    }

    internal fun parseStatistics(number: String, data: String): DlsiteStatistics {
        val item =
            (Json.parseToJsonElement(data) as? JsonObject)?.get(number) as? JsonObject
                ?: throw IOException("DLsite 未找到该作品")
        require(item.text("product_id").ifBlank { number } == number) { "DLsite 作品编号不匹配" }
        return DlsiteStatistics(
            (item["rate_average_2dp"] as? JsonPrimitive)?.doubleOrNull?.takeIf {
                it.isFinite() && it in 0.0..5.0
            },
            item.long("rate_count"),
            item.long("review_count"),
            item.long("dl_count"),
            item.long("price"),
            System.currentTimeMillis(),
        )
    }

    suspend fun description(number: String): List<DlsiteBlock> {
        val id = rj(number) ?: error("作品编号无效")
        val url =
            root
                .newBuilder()
                .addPathSegments("${dlsiteShop(id)}/work/=/product_id")
                .addPathSegment("$id.html")
                .addQueryParameter("locale", "ja_JP")
                .build()
        val page = read(url)
        return withContext(Dispatchers.Default) { parseDescription(id, page) }
    }

    private val horizontalSpace = Regex("[ \t]+")

    internal fun parseDescription(number: String, page: String): List<DlsiteBlock> {
        val document = Jsoup.parse(page, root.toString())
        require(document.selectFirst("#work_name") != null) { "DLsite 作品页不可用" }
        document.selectFirst("link[rel=canonical]")?.attr("href")?.let { canonical ->
            dlsiteWorkNumber.find(canonical)?.value?.let {
                require(it == number) { "DLsite 作品编号不匹配" }
            }
        }
        val container =
            document.selectFirst(".work_parts_container") ?: throw IOException("DLsite 作品介绍暂不可用")
        val blocks = mutableListOf<DlsiteBlock>()
        val buffer = StringBuilder()
        var characters = 0
        fun flush() {
            val content =
                buffer.toString().trim().replace(horizontalSpace, " ").take(100000 - characters)
            buffer.clear()
            if (content.isNotBlank()) {
                blocks += DlsiteBlock("text", content)
                characters += content.length
            }
        }
        fun walk(node: Node) {
            if (blocks.size >= 512 || characters >= 100000) return
            if (node is TextNode) {
                buffer.append(node.text())
                return
            }
            if (node !is Element) return
            val tag = node.normalName()
            if (tag in setOf("script", "style", "iframe", "form", "noscript")) return
            if (tag == "img") {
                flush()
                val raw = node.attr("data-src").ifBlank { node.attr("src") }
                val absolute = root.resolve(raw)?.toString().orEmpty()
                optionalImage(absolute)?.let {
                    blocks += DlsiteBlock("image", cleanText(node.attr("alt")), it)
                }
                return
            }
            if (
                tag in setOf("h1", "h2", "h3", "h4", "h5", "h6") ||
                    node.hasClass("work_parts_heading")
            ) {
                flush()
                cleanText(node.text()).takeIf(String::isNotBlank)?.let {
                    blocks += DlsiteBlock("heading", it)
                }
                return
            }
            if (tag == "br") {
                buffer.append('\n')
                return
            }
            val paragraph = tag in setOf("div", "p", "li", "section", "table", "tr")
            if (paragraph) flush()
            node.childNodes().forEach(::walk)
            if (paragraph) flush()
        }
        walk(container)
        flush()
        val images = mutableSetOf<String>()
        return blocks.filter { it.kind != "image" || images.add(it.url.orEmpty()) }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
