// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    private fun response(href: String, collection: Boolean = false) =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>100</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test
    fun decodesUnicodeNamesAndSkipsCrossOriginEntries() {
        val body =
            """<d:multistatus xmlns:d="DAV:">${response("/ASMR/%E7%A4%BA%E4%BE%8B%E9%9F%B3%E8%BD%A8.mp3")}${response("https://other.example/private.mp3")}</d:multistatus>"""
        val entries =
            WebDavClient.parse(body.toByteArray(), "https://nas.example/ASMR/".toHttpUrl())
        assertEquals(1, entries.size)
        assertEquals("示例音轨.mp3", entries.single().name)
        assertEquals(100L, entries.single().size)
    }

    @Test
    fun usesDepthOneAndUtf8Credentials() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(207)
                    .body(
                        """<d:multistatus xmlns:d="DAV:">${response("/ASMR/", true)}</d:multistatus>""",
                    )
                    .build(),
            )
            val source = SourceEntity("a", "NAS", "webdav", server.url("/ASMR/").toString(), "听者")
            WebDavClient(OkHttpClient()).list(server.url("/ASMR/"), source, "密码")
            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("1", request.headers["Depth"])
            assertEquals(
                Credentials.basic("听者", "密码", Charsets.UTF_8),
                request.headers["Authorization"],
            )
        }
    }

    @Test
    fun directoryPrefixIsNotEnoughToMatchSource() {
        val root = "https://nas.example/ASMR/".toHttpUrl()
        assertTrue(WebDavClient.insideRoot("https://nas.example/ASMR/track.mp3".toHttpUrl(), root))
        assertFalse(
            WebDavClient.insideRoot("https://nas.example/ASMR-other/track.mp3".toHttpUrl(), root),
        )
        assertFalse(
            WebDavClient.insideRoot("https://nas.example:8443/ASMR/track.mp3".toHttpUrl(), root),
        )
        assertFalse(
            WebDavClient.insideRoot(
                "https://nas.example/ASMR-other/track.mp3".toHttpUrl(),
                "https://nas.example/ASMR".toHttpUrl(),
            ),
        )
    }

    @Test
    fun cancellingScanStopsTheDirectoryRequestBeforeItsBodyArrives() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(207)
                    .body("""<d:multistatus xmlns:d="DAV:"/>""")
                    .bodyDelay(3, TimeUnit.SECONDS)
                    .build(),
            )
            val source = SourceEntity("source", "示例 NAS", "webdav", server.url("/ASMR/").toString())
            val scan = launch(Dispatchers.Default) { WebDavClient(OkHttpClient()).walk(source, "") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { scan.cancelAndJoin() }
            assertTrue(scan.isCancelled)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDoctype() {
        WebDavClient.parse(
            "<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///secret'>]><foo/>".toByteArray(),
            "https://nas.example/".toHttpUrl(),
        )
    }

    @Test
    fun walksNestedDirectoriesConcurrentlyAndKeepsAllFiles() = runBlocking {
        withMockServer { server ->
            server.dispatcher =
                object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                        val body =
                            when (request.url.encodedPath) {
                                "/ASMR/" ->
                                    """<d:multistatus xmlns:d="DAV:">${response("/ASMR/", true)}${response("/ASMR/a/", true)}${response("/ASMR/b/", true)}</d:multistatus>"""
                                "/ASMR/a/" ->
                                    """<d:multistatus xmlns:d="DAV:">${response("/ASMR/a/", true)}${response("/ASMR/a/01.flac")}</d:multistatus>"""
                                "/ASMR/b/" ->
                                    """<d:multistatus xmlns:d="DAV:">${response("/ASMR/b/", true)}${response("/ASMR/b/02.wav")}</d:multistatus>"""
                                else -> """<d:multistatus xmlns:d="DAV:"/>"""
                            }
                        return MockResponse.Builder().code(207).body(body).build()
                    }
                }
            val source = SourceEntity("src", "NAS", "webdav", server.url("/ASMR/").toString())
            val entries = WebDavClient(OkHttpClient()).walk(source, "")
            val paths = entries.filterNot { it.directory }.map { it.path }.sorted()
            assertEquals(listOf("a/01.flac", "b/02.wav"), paths)
        }
    }
}
