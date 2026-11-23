// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mockwebserver3.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DlsiteAccountClientTest {
    @Before
    fun initializeHttp() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
    }

    private val owned =
        """{"workno":"RJ00000001","work_type":"SOU","name":{"ja_JP":"示例作品"},"authors":[{"type":"voice","name":{"ja_JP":"示例声优"}}],"maker":{"name":{"ja_JP":"示例社团"}},"image":"//img.dlsite.jp/example.jpg","sales_date":"2026-01-01T00:00:00Z"}"""

    @Test
    fun sessionsRespectOriginCookieScopeExpiryAndRoundTrip() {
        val play = "https://play.dlsite.com/".toHttpUrl()
        val session = DlsiteSession()
        session.saveFromResponse(
            play,
            listOf(
                Cookie.Builder()
                    .name("session")
                    .value("example")
                    .domain("dlsite.com")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build(),
                Cookie.Builder()
                    .name("expired")
                    .value("example")
                    .hostOnlyDomain(play.host)
                    .expiresAt(1)
                    .build(),
            ),
        )
        val restored = DlsiteSession(session.encode())
        assertEquals(listOf("session"), restored.loadForRequest(play).map { it.name })
        assertEquals(1, restored.loadForRequest("https://play.dl.dlsite.com/".toHttpUrl()).size)
        assertTrue(restored.loadForRequest("https://api.asmr.one/".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("https://www.dlsite.com/".toHttpUrl()).isEmpty())
        assertTrue(restored.loadForRequest("http://play.dlsite.com/".toHttpUrl()).isEmpty())
        restored.clear()
        assertTrue(restored.loadForRequest(play).isEmpty())
    }

    @Test
    fun signingEndpointCanSetFutureResourcePathCookies() {
        val session = DlsiteSession()
        val sign =
            "https://play.dl.dlsite.com/api/v3/download/sign/cookie?workno=RJ00000001".toHttpUrl()
        session.saveFromResponse(
            sign,
            listOf(
                Cookie.Builder()
                    .name("media-policy")
                    .value("example")
                    .hostOnlyDomain(sign.host)
                    .path("/content/work/doujin/RJ00001000/RJ00000001/")
                    .secure()
                    .build(),
            ),
        )
        val restored = DlsiteSession(session.encode())
        assertEquals(
            1,
            restored
                .loadForRequest(
                    "https://play.dl.dlsite.com/content/work/doujin/RJ00001000/RJ00000001/optimized/example.mp3"
                        .toHttpUrl(),
                )
                .size,
        )
        assertTrue(restored.loadForRequest(sign).isEmpty())
        assertFalse(session.encode().contains("workno="))
    }

    @Test
    fun officialBrowserSessionDropsAnalyticsAndRejectsOtherOrigins() {
        val snapshot =
            DlsiteSession.fromBrowser(
                mapOf(
                    "https://play.dlsite.com/" to
                        "session=example; _ga=analytics; _vwo_id=analytics",
                ),
            )
        assertEquals(
            listOf("session"),
            DlsiteSession(snapshot).loadForRequest("https://play.dlsite.com/".toHttpUrl()).map {
                it.name
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            DlsiteSession.fromBrowser(mapOf("https://unrelated.example/" to "session=example"))
        }
    }

    @Test
    fun purchasedAudioIsBatchedLocalizedAndIncrementallyRequested() = runBlocking {
        withMockServer { server ->
            server.enqueue(body("""{"customer_id":7}"""))
            server.enqueue(body("""{"user":2,"production":0,"page_limit":100}"""))
            server.enqueue(
                body(
                    """[{"workno":"RJ00000001","sales_date":"2026-01-02T00:00:00Z"},{"workno":"RJ00000002","sales_date":"2026-01-01T00:00:00Z"}]""",
                ),
            )
            server.enqueue(
                body(
                    """{"works":[$owned,{"workno":"RJ00000002","work_type":"MNG","name":{"ja_JP":"示例漫画"}}]}""",
                ),
            )
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            val result = client.purchases(100_000)
            assertEquals("示例作品", result.works.single().title)
            assertEquals("示例声优", result.works.single().artist)
            assertEquals("示例社团", result.works.single().circle)
            assertEquals("https://img.dlsite.jp/example.jpg", result.works.single().coverUrl)
            assertEquals(1767312000000L, result.works.single().purchasedAt)
            server.takeRequest()
            assertEquals("100", server.takeRequest().url.queryParameter("last"))
            server.takeRequest()
            val batch = server.takeRequest()
            assertEquals("POST", batch.method)
            assertEquals(
                listOf("RJ00000001", "RJ00000002"),
                Json.parseToJsonElement(batch.body!!.utf8()).jsonObject["worknos"]!!.jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
        }
    }

    @Test
    fun malformedCommercialPageCannotAdvanceThePurchaseSync() = runBlocking {
        withMockServer { server ->
            server.enqueue(body("""{"customer_id":7}"""))
            server.enqueue(body("""{"user":0,"production":1,"page_limit":100}"""))
            server.enqueue(body("""{"error":"temporary failure"}"""))
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            try {
                client.purchases()
                fail("Incomplete purchase library accepted")
            } catch (e: java.io.IOException) {
                assertEquals("DLsite 购买库格式错误", e.message)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun workResponseMustBelongToTheRequestedPurchase() = runBlocking {
        withMockServer { server ->
            server.enqueue(body(owned.replace("RJ00000001", "RJ00000002")))
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            try {
                client.work("RJ00000001")
                fail("Different work accepted")
            } catch (e: IllegalArgumentException) {
                assertEquals("DLsite 作品编号不匹配", e.message)
            }
        }
    }

    @Test
    fun missingAuthenticationAndUnownedWorkHaveActionableFailures() = runBlocking {
        withMockServer { server ->
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            server.enqueue(body("[]"))
            try {
                client.authorize()
                fail("Anonymous session accepted")
            } catch (_: DlsiteLoginRequired) {}
            server.enqueue(
                body(owned.dropLast(1) + ",\"sales_date\":null,\"entitlement_type\":\"none\"}"),
            )
            try {
                client.work("RJ00000001")
                fail("Unowned work accepted")
            } catch (e: java.io.IOException) {
                assertEquals("账号尚未购买该作品", e.message)
            }
        }
    }

    @Test
    fun expiredMediaAuthorizationIsRefreshedOnceAndRedirectsStayPrivate() = runBlocking {
        withMockServer { server ->
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            server.enqueue(body("""{"expires":"2099-01-01T00:00:00Z"}"""))
            server.enqueue(MockResponse.Builder().code(403).build())
            server.enqueue(body("""{"expires":"2099-01-01T00:00:00Z"}"""))
            server.enqueue(body("WEBVTT\n"))
            assertEquals(
                "WEBVTT\n",
                client
                    .resourceBytes("RJ00000001", "optimized/example.vtt", 1024)
                    .toString(Charsets.UTF_8),
            )
            assertEquals(4, server.requestCount)
            assertEquals("/api/v3/download/sign/cookie", server.takeRequest().url.encodedPath)
            assertTrue(server.takeRequest().url.encodedPath.endsWith("/optimized/example.vtt"))
            server.takeRequest()
            server.takeRequest()
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "https://unrelated.example/")
                    .build(),
            )
            try {
                client.resourceBytes("RJ00000001", "optimized/example.vtt", 1024)
                fail("Redirect followed")
            } catch (e: OnlineHttpException) {
                assertEquals(302, e.code)
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun resourcePathTraversalIsRejectedBeforeNetworkAccess() = runBlocking {
        val client = DlsiteAccountClient(DlsiteSession())
        for (path in
            listOf(
                "../example.mp3",
                "optimized/../example.mp3",
                "optimized/example.mp3?secret=example",
            )) {
            try {
                client.signedUrl("RJ00000001", path)
                fail("Unsafe path accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }

    private fun body(value: String) = MockResponse.Builder().body(value).build()

    @Test
    fun serverRotationReplacesBrowserCookieShadowsAndLogoutStaysClosed() {
        val play = "https://play.dlsite.com/".toHttpUrl()
        val cdn = "https://play.dl.dlsite.com/".toHttpUrl()
        val session =
            DlsiteSession(
                DlsiteSession.fromBrowser(
                    mapOf(
                        play.toString() to "session=original",
                        cdn.toString() to "session=original",
                    ),
                ),
            )
        val rotated =
            Cookie.Builder()
                .name("session")
                .value("rotated")
                .domain("dlsite.com")
                .path("/")
                .secure()
                .build()
        session.saveFromResponse(play, listOf(rotated))
        assertEquals(listOf("rotated"), session.loadForRequest(play).map { it.value })
        assertEquals(listOf("rotated"), session.loadForRequest(cdn).map { it.value })
        session.clear()
        session.saveFromResponse(play, listOf(rotated))
        assertEquals("[]", session.encode())
        assertTrue(session.loadForRequest(play).isEmpty())
    }

    @Test
    fun commercialAudioRetainsPurchasedIdentityAndResourceNamespace() = runBlocking {
        assertNotNull(
            DlsiteAccountClient.parseOwned(
                Json.parseToJsonElement(owned.replace("RJ00000001", "VJ00000001")).jsonObject,
                emptyMap(),
            ),
        )
        withMockServer { server ->
            server.enqueue(body("""{"expires":"2099-01-01T00:00:00Z"}"""))
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            assertTrue(
                client
                    .signedUrl("VJ00000001", "optimized/example.mp3")
                    .encodedPath
                    .startsWith("/content/work/professional/"),
            )
            client.close()
            assertTrue(client.http.dispatcher.executorService.isShutdown)
        }
    }

    @Test
    fun serverDateHeaderCapsCompletedAtAgainstFastDeviceClock() = runBlocking {
        withMockServer { server ->
            val pastHeader = "Wed, 01 Jan 2020 00:00:00 GMT"
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"customer_id":7}""")
                    .addHeader("Date", pastHeader)
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"user":0,"production":0,"page_limit":100}""")
                    .addHeader("Date", pastHeader)
                    .build(),
            )
            val client =
                DlsiteAccountClient(
                    DlsiteSession(),
                    root = server.url("/"),
                    contentRoot = server.url("/"),
                    testTransport = true,
                )
            try {
                val sync = client.purchases(0)
                assertEquals(1577836800000L, sync.completedAt)
            } finally {
                client.close()
            }
        }
    }
}
