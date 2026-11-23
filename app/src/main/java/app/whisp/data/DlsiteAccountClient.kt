// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class DlsiteNoPlayableAudioException(message: String = "作品暂无可播放音轨") : IOException(message)

@Serializable
data class DlsiteOwnedWork(
    val workno: String,
    val title: String,
    val artist: String = "",
    val circle: String = "",
    val coverUrl: String? = null,
    val purchasedAt: Long = 0,
    val metadataReady: Boolean = false,
    val metadataAttemptedAt: Long = 0,
) {
    fun entity(sourceId: String) =
        WorkEntity(
            id = stableId("dlsite|$sourceId|$workno"),
            rj = workno,
            title = title,
            originalTitle = title,
            artist = artist,
            originalArtist = artist,
            coverId = coverUrl?.let { stableId("dlsite-cover|$it") },
            coverUrl = coverUrl,
            origin = "dlsite",
        )

    fun encode() = Json.encodeToString(this)
}

internal fun decodeOwnedWork(snapshot: String): DlsiteOwnedWork? = runCatching {
    Json.decodeFromString<DlsiteOwnedWork>(snapshot)
}
    .getOrNull()

data class DlsiteAccount(val id: String)

data class DlsitePurchaseSync(val works: List<DlsiteOwnedWork>, val completedAt: Long)

class DlsiteAccountClient(
    val session: DlsiteSession,
    baseHttp: OkHttpClient = OkHttpClient(),
    private val root: HttpUrl = "https://play.dlsite.com/".toHttpUrl(),
    private val contentRoot: HttpUrl = "https://play.dl.dlsite.com/".toHttpUrl(),
    private val testTransport: Boolean = false,
    private val diagnostic: (DlsiteRequestEvent) -> Unit = {},
) {
    private val latestServerDateMs = AtomicLong(0L)

    val http: OkHttpClient =
        baseHttp
            .newBuilder()
            .dispatcher(Dispatcher())
            .cookieJar(session)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val url = chain.request().url
                require(
                    DlsiteSession.trusted(url) ||
                        testTransport &&
                            (WebDavClient.sameOrigin(url, root) ||
                                WebDavClient.sameOrigin(url, contentRoot)),
                ) {
                    "DLsite 地址无效"
                }
                val path = url.encodedPath
                val phase =
                    when {
                        path.endsWith("/authorize") -> DlsitePhase.AUTHORIZE
                        path.contains("/content/") && !path.contains("/content/work/") ||
                            path.contains("/api/v4/work/") -> DlsitePhase.PURCHASES
                        path.endsWith("/ziptree.json") -> DlsitePhase.FILES
                        path.contains("/download/sign/") -> DlsitePhase.SIGNATURE
                        path.contains("/optimized/") -> DlsitePhase.MEDIA
                        else -> DlsitePhase.OTHER
                    }
                val started = System.nanoTime()
                var status = 0
                try {
                    chain.proceed(chain.request()).also { response ->
                        status = response.code
                        response.header("Date")?.let { rawDate ->
                            runCatching {
                                ZonedDateTime.parse(
                                        rawDate,
                                        DateTimeFormatter.RFC_1123_DATE_TIME,
                                    )
                                    .toInstant()
                                    .toEpochMilli()
                            }
                                .getOrNull()
                                ?.takeIf { it > 0 }
                                ?.let { latestServerDateMs.set(it) }
                        }
                    }
                } finally {
                    diagnostic(
                        DlsiteRequestEvent(
                            phase,
                            status,
                            (System.nanoTime() - started) / 1_000_000,
                        ),
                    )
                }
            }
            .build()
    private val signatureLock = Mutex()
    private val expires = mutableMapOf<String, Long>()

    fun close() {
        session.clear()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
    }

    private suspend fun json(path: String, body: JsonElement? = null): JsonElement {
        val url = root.resolve(path) ?: error("DLsite 地址无效")
        val request =
            Request.Builder()
                .url(url)
                .header("Referer", root.toString())
                .header("Origin", root.toString().trimEnd('/'))
        if (body != null) {
            session
                .loadForRequest(url)
                .firstOrNull { it.name == "XSRF-TOKEN" }
                ?.let {
                    request.header("X-XSRF-TOKEN", java.net.URLDecoder.decode(it.value, "UTF-8"))
                }
            request.post(body.toString().toRequestBody("application/json".toMediaType()))
        }
        return try {
            Json.parseToJsonElement(
                http.onlineBytes(request.build(), 8 * 1024 * 1024).toString(Charsets.UTF_8),
            )
        } catch (e: OnlineHttpException) {
            if (e.code in setOf(401, 403)) throw DlsiteLoginRequired() else throw e
        }
    }

    suspend fun authorize(): DlsiteAccount {
        val value = json("api/authorize") as? JsonObject ?: throw DlsiteLoginRequired()
        val identity =
            listOf("customer_id", "user_id", "id", "uid", "user", "login_id")
                .firstNotNullOfOrNull { name ->
                    (value[name] as? JsonPrimitive)?.contentOrNull?.takeIf {
                        it.isNotBlank() && it != "0"
                    }
                } ?: throw IOException("DLsite 账号信息格式错误")
        return DlsiteAccount(stableId("dlsite-account|$identity"))
    }

    suspend fun purchases(lastScan: Long = 0): DlsitePurchaseSync {
        latestServerDateMs.set(0L)
        authorize()
        val started = System.currentTimeMillis()
        val last = (lastScan / 1000).coerceAtLeast(0)
        val count = json("api/v4/content/count?last=$last").jsonObject
        val pageSize = (count.int("page_limit").takeIf { it > 0 } ?: 100).coerceIn(1, 200)
        val sales =
            if (count.int("user") > 0) json("api/v4/content/sales?last=$last").jsonArray
            else JsonArray(emptyList())
        require(sales.size <= 100_000) { "购买库过大" }
        val dates =
            sales
                .mapNotNull { element ->
                    val item = element.jsonObject
                    item.string("workno").takeIf(::validWorkno)?.let {
                        it to date(item["sales_date"])
                    }
                }
                .toMap()
        val result = mutableListOf<DlsiteOwnedWork>()
        for (ids in dates.keys.chunked(pageSize)) {
            val works =
                json(
                        "api/v4/content/works",
                        buildJsonObject { put("worknos", JsonArray(ids.map(::JsonPrimitive))) },
                    )
                    .jsonObject["works"]
                    ?.jsonArray ?: throw IOException("DLsite 购买库格式错误")
            works.forEach { parseOwned(it.jsonObject, dates)?.let(result::add) }
        }
        val production = count.int("production")
        require(production <= 100_000) { "购买库过大" }
        for (page in 1..((production + pageSize - 1) / pageSize)) {
            val works =
                json("api/v4/content/products?page=$page&last=$last").jsonObject["works"]
                    as? JsonArray ?: throw IOException("DLsite 购买库格式错误")
            works.forEach { parseOwned(it.jsonObject, dates)?.let(result::add) }
        }
        // Guard against a fast device clock skipping newly bought works on the next incremental
        // sync.
        val serverTime = latestServerDateMs.get()
        val completedAt = if (serverTime > 0) minOf(started, serverTime) else started
        return DlsitePurchaseSync(result.distinctBy { it.workno }, completedAt)
    }

    suspend fun work(workno: String): DlsiteOwnedWork {
        require(validWorkno(workno))
        val item = json("api/v4/work/$workno").jsonObject
        require(item.string("workno") == workno) { "DLsite 作品编号不匹配" }
        if (item["sales_date"] == JsonNull && item.string("entitlement_type") == "none")
            throw IOException("账号尚未购买该作品")
        return parseOwned(item, emptyMap()) ?: throw DlsiteNoPlayableAudioException("作品暂无可播放音声")
    }

    suspend fun index(work: DlsiteOwnedWork, sourceId: String, generation: Long): IndexedLibrary {
        val tree = resourceBytes(work.workno, "ziptree.json", 8 * 1024 * 1024)
        return DlsiteFileTree.index(
            work,
            sourceId,
            generation,
            Json.parseToJsonElement(tree.toString(Charsets.UTF_8)).jsonObject,
        )
    }

    suspend fun signedUrl(workno: String, path: String, force: Boolean = false): HttpUrl {
        val url = resourceUrl(workno, path)
        signatureLock.withLock {
            if (force || (expires[workno] ?: 0) <= System.currentTimeMillis() + 60_000) {
                val endpoint =
                    contentRoot
                        .newBuilder()
                        .addPathSegments("api/v3/download/sign/cookie")
                        .addQueryParameter("workno", workno)
                        .build()
                try {
                    val data =
                        Json.parseToJsonElement(
                                http
                                    .onlineBytes(
                                        Request.Builder()
                                            .url(endpoint)
                                            .header("Referer", root.toString())
                                            .build(),
                                        128 * 1024,
                                    )
                                    .toString(Charsets.UTF_8),
                            )
                            .jsonObject
                    expires[workno] =
                        date(data["expires"]).takeIf { it > System.currentTimeMillis() }
                            ?: (System.currentTimeMillis() + 5 * 60_000)
                    if (expires.size > 32)
                        expires.keys.firstOrNull { it != workno }?.let(expires::remove)
                } catch (e: OnlineHttpException) {
                    if (e.code in setOf(401, 403)) throw DlsiteLoginRequired() else throw e
                }
            }
        }
        return url
    }

    suspend fun resourceBytes(workno: String, path: String, limit: Int): ByteArray {
        for (attempt in 0..1) {
            val url = signedUrl(workno, path, force = attempt > 0)
            try {
                return http.onlineBytes(
                    Request.Builder().url(url).header("Referer", root.toString()).build(),
                    limit,
                )
            } catch (e: OnlineHttpException) {
                if (e.code !in setOf(401, 403) || attempt == 1) {
                    if (e.code in setOf(401, 403)) throw DlsiteLoginRequired() else throw e
                }
            }
        }
        throw DlsiteLoginRequired()
    }

    private fun resourceUrl(workno: String, path: String): HttpUrl {
        require(
            validWorkno(workno) &&
                (path == "ziptree.json" ||
                    optimizedPathPattern.matches(path) && !path.contains("..")),
        ) {
            "DLsite 文件标识无效"
        }
        val prefix = workno.take(2)
        val group =
            prefix +
                (((workno.drop(2).toLong() + 999) / 1000) * 1000)
                    .toString()
                    .padStart(workno.length - 2, '0')
        return contentRoot
            .newBuilder()
            .addPathSegments(
                "content/work/" +
                    when (workno.first()) {
                        'V' -> "professional"
                        'B' -> "books"
                        else -> "doujin"
                    },
            )
            .addPathSegment(group)
            .addPathSegment(workno)
            .addPathSegments(path)
            .build()
    }

    companion object {
        private val optimizedPathPattern = Regex("optimized/[A-Za-z0-9_.-]{1,200}")

        fun validWorkno(value: String) = validDlsiteWorkno(value)

        internal fun date(element: JsonElement?): Long {
            val text = (element as? JsonPrimitive)?.contentOrNull ?: return 0
            text.toLongOrNull()?.let {
                return if (it < 10_000_000_000L) it * 1000 else it
            }
            return runCatching { Instant.parse(text).toEpochMilli() }
                .getOrElse {
                    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                        .getOrDefault(0)
                }
        }

        internal fun localized(element: JsonElement?): String =
            when (element) {
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                is JsonObject ->
                    listOf("ja_JP", "zh_CN", "en_US")
                        .firstNotNullOfOrNull {
                            (element[it] as? JsonPrimitive)
                                ?.contentOrNull
                                ?.takeIf(String::isNotBlank)
                        }
                        .orEmpty()
                else -> ""
            }

        internal fun parseOwned(item: JsonObject, dates: Map<String, Long>): DlsiteOwnedWork? {
            val workno = item.string("workno")
            if (!validWorkno(workno) || item.string("work_type") !in setOf("SOU", "MUS"))
                return null
            val title = localized(item["name"]).ifBlank { localized(item["work_name"]) }
            if (title.isBlank()) return null
            val maker = (item["original_maker"] as? JsonObject) ?: (item["maker"] as? JsonObject)
            val artist =
                (item["authors"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .filter {
                        it.string("type") in setOf("voice", "voice_by") ||
                            it.string("role") == "voice_by"
                    }
                    .map { localized(it["name"]) }
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString(" / ")
            val rawCover =
                localized((item["images"] as? JsonObject)?.get("main"))
                    .ifBlank { localized(item["image"]) }
                    .ifBlank { localized((item["image"] as? JsonObject)?.get("main")) }
                    .ifBlank { localized(item["image_url"]) }
                    .ifBlank { localized(item["work_image"]) }
            val cover =
                rawCover
                    .takeIf { it.isNotBlank() }
                    ?.let { if (it.startsWith("//")) "https:$it" else it }
                    ?.takeIf { value ->
                        runCatching { value.toHttpUrl() }
                            .getOrNull()
                            ?.let {
                                it.scheme == "https" &&
                                    (it.host == "img.dlsite.jp" ||
                                        it.host.endsWith(".dlsite.jp") ||
                                        it.host.endsWith(".dlsite.com"))
                            } == true
                    }
            return DlsiteOwnedWork(
                workno,
                title,
                artist,
                localized(maker?.get("name")),
                cover,
                dates[workno] ?: date(item["sales_date"]),
            )
        }
    }
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
