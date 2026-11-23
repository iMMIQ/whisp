// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import app.whisp.ui.AsmrCatalogController
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Test

class AsmrOneClientTest {
    private val work =
        """{"id":1,"source_id":"RJ00000001","title":"示例作品","vas":[{"name":"示例声优"}],"mainCoverUrl":"https://media.example/cover.jpg"}"""

    private fun response(body: String) = MockResponse.Builder().body(body).build()

    @Test
    fun videoNodesUseActualContainerDurationAndSharedSubtitleMatching() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                response(
                    """[
                {"type":"video","title":"01. 示例画面","mediaStreamUrl":"https://media.example/clip.mkv?revision=1","duration":40.5},
                {"type":"text","title":"01. 示例画面.srt","mediaStreamUrl":"https://media.example/caption.srt"},
                {"type":"audio","title":"02. 示例声音.wav","mediaStreamUrl":"https://media.example/audio.wav"}
            ]""",
                ),
            )
            val result =
                AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
                    .index(AsmrWork(1, "示例作品", "RJ00000001", "", null), "source")
            assertEquals(2, result.tracks.size)
            val video = result.tracks.first()
            assertTrue(video.isVideo)
            assertEquals("01. 示例画面.mkv", video.fileName)
            assertEquals(40500L, video.durationMs)
            assertNotNull(video.subtitleId)
            assertFalse(result.tracks.last().isVideo)
        }
    }

    @Test
    fun sharedUrlsKeepDistinctTrackIdsAndEmbeddedUrisAcrossWorks() {
        val result =
            LibraryIndexer.index(
                listOf(
                    FileEntry(
                        "RJ00000001/01_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                    FileEntry(
                        "RJ00000001/02_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                    FileEntry(
                        "RJ00000002/01_示例.wav",
                        "https://media.example/shared.wav",
                        false,
                        100,
                    ),
                ),
                "source",
            )
        assertEquals(3, result.tracks.map { it.id }.distinct().size)
        assertTrue(result.tracks.all { it.uri == "https://media.example/shared.wav" })
    }

    @Test
    fun catalogUsesEncodedSearchFixedPagesFiltersAndPublicHeaders() = runBlocking {
        withMockServer { server ->
            server.enqueue(response("""{"works":[$work],"pagination":{"totalCount":41}}"""))
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val page = client.catalog(2, "示例/声优", "dl_count", true)
            val request = server.takeRequest()
            assertEquals("示例/声优", request.url.pathSegments.last())
            assertTrue(request.url.encodedPath.contains("%2F"))
            assertEquals("2", request.url.queryParameter("page"))
            assertEquals("20", request.url.queryParameter("pageSize"))
            assertEquals("dl_count", request.url.queryParameter("order"))
            assertEquals("1", request.url.queryParameter("subtitle"))
            assertEquals("https://asmr.one/", request.headers["Referer"])
            assertNull(request.headers["Authorization"])
            assertTrue(page.hasMore)
            assertEquals("RJ00000001", page.works.single().rj)
            assertEquals("示例声优", page.works.single().artist)
        }
    }

    @Test
    fun metadataUsesCaseSensitiveEndpointAndCanonicalEightDigitCode() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                response("""{"id":1000001,"title":"示例作品","circle":{"name":"示例社团"},"vas":null}"""),
            )
            val result = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).work(1000001)
            assertEquals("/api/workInfo/1000001", server.takeRequest().url.encodedPath)
            assertEquals("RJ01000001", result.rj)
            assertEquals("示例社团", result.artist)
            assertNull(result.coverId)
        }
    }

    @Test
    fun indexingPreservesVariantsSubtitlesBonusDurationsAndStableIdentity() = runBlocking {
        withMockServer { server ->
            val tree =
                """[
                {"type":"folder","title":"MP3","children":[
                    {"type":"audio","title":"01_示例.mp3","mediaStreamUrl":"https://media.example/a?revision=1","duration":1.234,"size":100},
                    {"type":"text","title":"01_示例.mp3.vtt","mediaStreamUrl":"https://media.example/sub.vtt"}]},
                {"type":"folder","title":"WAV","children":[{"type":"audio","title":"01_示例.wav","mediaStreamUrl":"https://media.example/b","duration":2,"size":200}]},
                {"type":"folder","title":"特典 RJ00000002","children":[{"type":"audio","title":"02_附赠.mp3","mediaStreamUrl":"https://media.example/c","size":300}]}
            ]"""
            server.enqueue(response(tree))
            server.enqueue(response(tree.replace("revision=1", "revision=2")))
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val info = AsmrWork(1, "示例作品", "RJ00000001", "示例声优", "https://media.example/cover.jpg")
            val first = client.index(info, "source")
            val refreshed = client.index(info.copy(title = "更新标题"), "source")
            assertEquals(1, first.works.size)
            assertEquals(info.workId, first.works.single().id)
            assertEquals(3, first.tracks.size)
            val mp3 = first.tracks.first { it.relativePath == "MP3/01_示例.mp3" }
            assertEquals(1234L, mp3.durationMs)
            assertNotNull(mp3.subtitleId)
            assertTrue(first.tracks.single { it.relativePath.startsWith("特典") }.bonus)
            assertEquals(first.tracks.map { it.id }, refreshed.tracks.map { it.id })
            assertEquals(info.coverId, first.works.single().coverId)
            assertEquals(
                info.coverId,
                first.attachments.single { it.relativePath == "_asmr-cover/main.jpg" }.id,
            )
            assertTrue(refreshed.tracks.any { it.uri.endsWith("revision=2") })
        }
    }

    @Test
    fun audioTitlesWithPunctuationKeepTheActualMediaFormat() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                response(
                    """[
                    {"type":"audio","title":"01. 示例","mediaStreamUrl":"https://media.example/audio.wav?download=1"},
                    {"type":"audio","title":"02_示例.ver2","mediaStreamUrl":"https://media.example/opaque"},
                    {"type":"audio","title":"03_示例.FLAC","mediaStreamUrl":"https://media.example/opaque"}
                    ]""",
                ),
            )
            val result =
                AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
                    .index(AsmrWork(1, "示例作品", "RJ00000001", "", null), "source")
            assertEquals(
                listOf("01. 示例.wav", "02_示例.ver2.mp3", "03_示例.FLAC"),
                result.tracks.map { it.relativePath },
            )
            assertEquals(3, result.tracks.count { it.uri.isNotBlank() })
        }
    }

    @Test
    fun resourceUrlsRequireHttpsAndSeparateCredentials() = runBlocking {
        withMockServer { server ->
            for (url in
                listOf(
                    "http://media.example/cover.jpg",
                    "https://listener:test@media.example/cover.jpg",
                )) {
                server.enqueue(response(work.replace("https://media.example/cover.jpg", url)))
                try {
                    AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).work(1)
                    fail("Unsafe media URL accepted")
                } catch (_: IOException) {}
            }
        }
    }

    @Test
    fun catalogControllerRetriesErrorsAppendsPagesAndDeduplicates() = runBlocking {
        withMockServer { server ->
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            server.enqueue(response("""{"works":[$work],"pagination":{"totalCount":21}}"""))
            server.enqueue(
                response(
                    """{"works":[${work.replace("\"id\":1", "\"id\":2").replace("RJ00000001", "RJ00000002")}],"pagination":{"totalCount":21}}""",
                ),
            )
            val client = AsmrOneClient(okhttp3.OkHttpClient(), server.url("/"))
            val controller =
                AsmrCatalogController(
                    scope = this,
                    fetchPage = { _, page -> client.catalog(page) },
                )
            controller.submit(AsmrQuery("source"))
            val failed = controller.state.first { !it.refreshing && it.refreshError != null }
            assertTrue(failed.items.isEmpty())
            controller.refresh()
            val page1 = controller.state.first { !it.refreshing && it.items.size == 1 }
            assertTrue(page1.hasMore)
            controller.loadNextPage()
            val page2 = controller.state.first { !it.appending && it.items.size == 2 }
            assertFalse(page2.hasMore)
            assertEquals(listOf(1L, 2L), page2.items.map { it.id })
        }
    }

    @Test
    fun cancellationStopsTheNetworkRequest() = runBlocking {
        withMockServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"works":[]}""")
                    .bodyDelay(3, TimeUnit.SECONDS)
                    .build(),
            )
            val job =
                launch(Dispatchers.Default) {
                    AsmrOneClient(okhttp3.OkHttpClient(), server.url("/")).catalog(1)
                }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
