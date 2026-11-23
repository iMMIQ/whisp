// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DlsiteClientTest {
    @Test
    fun commercialVoiceUsesItsOfficialStoreAndBrandMetadata() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .body(
                        """[{"product_id":"VJ00000001","work_name":"示例音声","brand_name":"示例品牌","image_main":{"url":"//img.dlsite.jp/example.jpg"}}]""",
                    )
                    .build(),
            )
            val result = DlsiteClient(OkHttpClient(), server.url("/")).work("VJ00000001")
            assertEquals("示例品牌", result.details.circle)
            assertEquals("/pro/api/=/product.json", server.takeRequest().url.encodedPath)
            assertEquals(
                "https://www.dlsite.com/pro/work/=/product_id/VJ00000001.html",
                dlsiteWorkUrl(result.rj),
            )
        }
    }

    private val info =
        """{"RJ00000001":{"product_id":"RJ00000001","work_name":"官方示例 &amp; 标题","maker_name":"示例社团","work_image":"//img.dlsite.jp/example.jpg","title_name":"示例系列"}}"""
    private val product =
        """[{"product_id":"RJ00000001","work_name":"官方示例 &amp; 标题","maker_name":"示例社团","creaters":{"voice_by":[{"name":"示例甲"},{"name":"示例乙"},{"name":"示例甲"}]},"image_main":{"url":"//img.dlsite.jp/example.jpg"},"title_name":"示例系列"}]"""

    @Test
    fun officialFieldsReplaceCatalogMetadataWithoutChangingAudioIdentity() = runBlocking {
        withMockServer { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse.Builder().body(product).build()
                }
            val metadata = DlsiteClient(OkHttpClient(), server.url("/")).work("rj00000001")
            assertEquals("官方示例 & 标题", metadata.title)
            assertEquals("示例甲 / 示例乙", metadata.artist)
            assertEquals("https://img.dlsite.jp/example.jpg", metadata.coverUrl)
            assertEquals("示例系列", metadata.series)
            val old = AsmrWork(1, "目录标题", "RJ00000001", "目录声优", "https://media.example/cover.jpg")
            val new = metadata.applyTo(old)
            assertEquals(old.workId, new.workId)
            assertNotEquals(old.coverId, new.coverId)
            assertEquals(metadata.artist, new.entity().originalArtist)
            assertEquals(SourceKind.ASMRONE, new.entity().originKind)
            val requests = listOf(server.takeRequest())
            assertEquals("/maniax/api/=/product.json", requests.single().url.encodedPath)
            assertEquals("RJ00000001", requests.single().url.queryParameter("workno"))
            requests.forEach {
                assertEquals("ja_JP", it.url.queryParameter("locale"))
                assertEquals("ja-JP", it.headers["Accept-Language"])
                assertNull(it.headers["Authorization"])
                assertNull(it.headers["Origin"])
            }
        }
    }

    @Test
    fun keyedInfoAcceptsAnOmittedIdAndRetainsOfficialCircle() {
        val client = DlsiteClient(OkHttpClient())
        val result =
            client.parseInfo("RJ00000001", info.replace("\"product_id\":\"RJ00000001\",", ""))
        assertEquals("示例社团", result.artist)
        assertEquals("官方示例 & 标题", result.title)
    }

    @Test
    fun unavailableFullEndpointFallsBackToOfficialKeyedInfo() = runBlocking {
        withMockServer { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(MockResponse.Builder().body(info).build())
            assertEquals(
                "示例社团",
                DlsiteClient(OkHttpClient(), server.url("/")).work("RJ00000001").artist,
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun mismatchedIdentifiersAndUntrustedCoversAreRejected() {
        val client = DlsiteClient(OkHttpClient())
        assertThrows(IllegalArgumentException::class.java) {
            client.parseInfo(
                "RJ00000001",
                info.replace("\"product_id\":\"RJ00000001\"", "\"product_id\":\"RJ00000002\""),
            )
        }
        for (url in
            listOf(
                "http://img.dlsite.jp/cover.jpg",
                "https://listener:test@img.dlsite.jp/cover.jpg",
                "https://media.example/cover.jpg",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                client.parseInfo("RJ00000001", info.replace("//img.dlsite.jp/example.jpg", url))
            }
        }
        assertThrows(IOException::class.java) { client.parseInfo("RJ00000001", "{}") }
    }

    @Test
    fun cancellationStopsTheOfficialRequest() = runBlocking {
        withMockServer { server ->
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder().body(info).bodyDelay(3, TimeUnit.SECONDS).build(),
                )
            }
            val job =
                launch(Dispatchers.Default) {
                    DlsiteClient(OkHttpClient(), server.url("/")).work("RJ00000001")
                }
            repeat(1) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
