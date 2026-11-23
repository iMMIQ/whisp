// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class DlsiteLoginRequired : IOException("请重新登录 DLsite")

@Serializable private data class SavedCookie(val origin: String, val cookie: String)

private val sessionJson = Json { ignoreUnknownKeys = true }

/** Session cookies are kept separate from public metadata and other sources. */
class DlsiteSession(snapshot: String = "", private val changed: (String) -> Unit = {}) : CookieJar {
    companion object {
        val origins =
            listOf(
                "https://login.dlsite.com/",
                "https://play.dlsite.com/",
                "https://play.dl.dlsite.com/",
            )
        private val hosts = setOf("login.dlsite.com", "play.dlsite.com", "play.dl.dlsite.com")

        fun trusted(url: HttpUrl) = url.scheme == "https" && url.host in hosts && url.port == 443

        fun fromBrowser(headers: Map<String, String>): String {
            val entries = buildList {
                headers.forEach { (origin, header) ->
                    val url = origin.toHttpUrl()
                    require(trusted(url))
                    header.split(';').forEach { item ->
                        val name = item.substringBefore('=').trim()
                        val value = item.substringAfter('=', "").trim()
                        if (
                            name.isNotBlank() &&
                                !name.startsWith("_ga") &&
                                !name.startsWith("_vwo") &&
                                !name.startsWith("_vis")
                        ) {
                            Cookie.parse(url, "$name=$value; Path=/; Secure; HttpOnly")?.let {
                                cookie ->
                                add(SavedCookie(origin, cookie.toString()))
                            }
                        }
                    }
                }
            }
            return sessionJson.encodeToString(entries)
        }
    }

    private val cookies = mutableListOf<Pair<HttpUrl, Cookie>>()
    private var closed = false

    init {
        if (snapshot.isNotBlank()) {
            val entries = sessionJson.decodeFromString<List<SavedCookie>>(snapshot)
            require(entries.size <= 200) { "登录会话过大" }
            entries.forEach { entry ->
                val origin = entry.origin.toHttpUrl()
                require(trusted(origin))
                Cookie.parse(origin, entry.cookie)?.let {
                    if (matchesOrigin(it, origin)) cookies += origin to it
                }
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (closed || !trusted(url)) return emptyList()
        val now = System.currentTimeMillis()
        return cookies
            .map { it.second }
            .filter { it.expiresAt > now && it.matches(url) }
            .sortedByDescending { it.path.length }
            .distinctBy { Triple(it.name, it.domain, it.path) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!trusted(url)) return
        val snapshot =
            synchronized(this) {
                if (closed) return
                cookies
                    .filter { matchesOrigin(it, url) }
                    .forEach { cookie ->
                        this.cookies.removeAll { (origin, old) ->
                            old.name == cookie.name &&
                                old.path == cookie.path &&
                                (old.domain == cookie.domain ||
                                    old.hostOnly &&
                                        !old.persistent &&
                                        cookie.domain == "dlsite.com" &&
                                        matchesOrigin(cookie, origin))
                        }
                        if (cookie.expiresAt > System.currentTimeMillis())
                            this.cookies +=
                                url.newBuilder()
                                    .encodedPath("/")
                                    .query(null)
                                    .fragment(null)
                                    .build() to cookie
                    }
                this.cookies.removeAll { it.second.expiresAt <= System.currentTimeMillis() }
                while (this.cookies.size > 200) this.cookies.removeAt(0)
                encode()
            }
        changed(snapshot)
    }

    @Synchronized
    fun encode(): String =
        sessionJson.encodeToString(
            cookies.map { (origin, cookie) -> SavedCookie(origin.toString(), cookie.toString()) },
        )

    @Synchronized
    fun clear() {
        closed = true
        cookies.clear()
    }

    private fun matchesOrigin(cookie: Cookie, url: HttpUrl) =
        cookie.domain == url.host || !cookie.hostOnly && url.host.endsWith(".${cookie.domain}")
}
