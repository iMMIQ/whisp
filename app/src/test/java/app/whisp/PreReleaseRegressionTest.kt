// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.room.Room
import app.whisp.data.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreReleaseRegressionTest {
    @Test
    fun onlineCopyToSinkStopsWritingBeforeCancellationReturns() = runBlocking {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder()
                        .body("a".repeat(256 * 1024))
                        .throttleBody(4096, 20, TimeUnit.MILLISECONDS)
                        .build()
            }
        server.start()
        try {
            val closed = AtomicBoolean(false)
            val wroteAfterClose = AtomicBoolean(false)
            val firstChunkWritten = CompletableDeferred<Unit>()
            val guardedSink =
                object : Sink {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (closed.get()) wroteAfterClose.set(true)
                        source.skip(byteCount)
                        firstChunkWritten.complete(Unit)
                    }

                    override fun flush() {
                        if (closed.get()) wroteAfterClose.set(true)
                    }

                    override fun timeout(): Timeout = Timeout.NONE

                    override fun close() {
                        closed.set(true)
                    }
                }
            val client = OkHttpClient()
            val job =
                launch(Dispatchers.IO) {
                    guardedSink.buffer().use { buffered ->
                        client.onlineCopyToSink(
                            Request.Builder().url(server.url("/stream")).build(),
                            buffered,
                            1024 * 1024,
                        )
                    }
                }
            withTimeout(5_000) { firstChunkWritten.await() }
            job.cancelAndJoin()
            delay(100)
            assertTrue(closed.get())
            assertFalse("Sink was written after close/cancellation", wroteAfterClose.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun webDavRejectsOutsideRootRedirectAndDepthCycles() = runBlocking {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/dav/escape.mp3" ->
                            MockResponse.Builder()
                                .code(302)
                                .setHeader("Location", "/other/secret.mp3")
                                .build()
                        path == "/dav/dir" ->
                            MockResponse.Builder()
                                .code(301)
                                .setHeader("Location", "/dav/dir/")
                                .build()
                        path == "/dav/dir/" ->
                            MockResponse.Builder()
                                .code(207)
                                .body(
                                    """
                                    <d:multistatus xmlns:d="DAV:">
                                      <d:response>
                                        <d:href>/dav/dir/</d:href>
                                        <d:propstat>
                                          <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                                          <d:status>HTTP/1.1 200 OK</d:status>
                                        </d:propstat>
                                      </d:response>
                                      <d:response>
                                        <d:href>/dav/dir/01.mp3</d:href>
                                        <d:propstat>
                                          <d:prop><d:getcontentlength>12</d:getcontentlength></d:prop>
                                          <d:status>HTTP/1.1 200 OK</d:status>
                                        </d:propstat>
                                      </d:response>
                                    </d:multistatus>
                                    """
                                        .trimIndent(),
                                )
                                .build()
                        path.startsWith("/loop/") -> {
                            val child = path.trimEnd('/') + "/sub/"
                            MockResponse.Builder()
                                .code(207)
                                .body(
                                    """
                                    <d:multistatus xmlns:d="DAV:">
                                      <d:response>
                                        <d:href>$path</d:href>
                                        <d:propstat>
                                          <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                                          <d:status>HTTP/1.1 200 OK</d:status>
                                        </d:propstat>
                                      </d:response>
                                      <d:response>
                                        <d:href>$child</d:href>
                                        <d:propstat>
                                          <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                                          <d:status>HTTP/1.1 200 OK</d:status>
                                        </d:propstat>
                                      </d:response>
                                    </d:multistatus>
                                    """
                                        .trimIndent(),
                                )
                                .build()
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        server.start()
        try {
            val dav = WebDavClient(OkHttpClient())
            val source =
                SourceEntity("s1", "NAS", SourceKind.WEBDAV.raw, server.url("/dav/").toString())

            // Outside-root redirect must be blocked.
            try {
                dav.bytes(server.url("/dav/escape.mp3").toString(), source, "")
                fail("Expected outside-root redirect to fail")
            } catch (_: IOException) {}

            // Inside-root trailing-slash redirect must succeed.
            val listed = dav.list(server.url("/dav/dir"), source, "")
            assertTrue(listed.any { it.name == "01.mp3" })

            // Symlink loop must terminate cleanly at depth 32 instead of failing with 目录过大.
            val loopSource =
                SourceEntity("s2", "Loop", SourceKind.WEBDAV.raw, server.url("/loop/").toString())
            val walked = dav.walk(loopSource, "")
            assertTrue(walked.size in 30..35)
        } finally {
            server.close()
        }
    }

    @Test
    fun dlsiteAndAsmrClientsBlockDisallowedRedirects() = runBlocking {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder()
                        .code(302)
                        .setHeader("Location", "http://evil.example/payload")
                        .build()
            }
        server.start()
        try {
            val dlsite = DlsiteClient(OkHttpClient(), server.url("/"))
            try {
                dlsite.fetchBytes(server.url("/cover.jpg").toString())
                fail("Expected DlsiteClient to block off-origin redirect")
            } catch (_: IOException) {}

            val asmr = AsmrOneClient(OkHttpClient(), server.url("/"))
            try {
                asmr.bytes(server.url("/media.mp3").toString(), 1024)
                fail("Expected AsmrOneClient to block cleartext off-origin redirect")
            } catch (_: IOException) {}
        } finally {
            server.close()
        }
    }

    @Test
    fun dlsiteSessionFromBrowserDeduplicatesAndCapsCookies() {
        val hugeHeader = (1..250).joinToString("; ") { "k$it=v$it" }
        val encoded =
            DlsiteSession.fromBrowser(
                mapOf(
                    "https://login.dlsite.com/" to "sid=one; sid=two; _ga=ignore",
                    "https://play.dlsite.com/" to hugeHeader,
                ),
            )
        val session = DlsiteSession(encoded)
        assertNotNull(session)
    }

    @Test
    fun libraryIndexerPrioritizesSameDirectoryScriptAtRootLevel() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    FileEntry("01_intro.mp3", "content://root/01_intro.mp3", false),
                    FileEntry("01_intro.txt", "content://root/01_intro.txt", false),
                    FileEntry("sub/01_intro.txt", "content://root/sub/01_intro.txt", false),
                ),
                sourceId = "s1",
            )
        val track = indexed.tracks.single()
        val matchedScript = indexed.attachments.first { it.id == track.scriptId }
        assertEquals("01_intro.txt", matchedScript.relativePath)
    }

    @Test
    fun latestProgressSkipsGhostWorksWithoutTracks() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putSource(SourceEntity("src1", "Local", "local", "content://root"))
            dao.putWorks(
                listOf(
                    WorkEntity("w1", "RJ000001", "Work 1", "Work 1", "VA", sourceId = "src1"),
                    WorkEntity("ghost", null, "Ghost", "Ghost", "", sourceId = "src1"),
                ),
            )
            dao.putTracks(
                listOf(
                    TrackEntity(
                        "t1",
                        "w1",
                        "Track 1",
                        "01.mp3",
                        "01.mp3",
                        1,
                        60_000L,
                        uri = "content://root/01.mp3",
                    ),
                ),
            )
            dao.saveProgress(ProgressEntity("w1", "t1", 5_000L, 100L))
            dao.saveProgress(ProgressEntity("ghost", "missing", 9_000L, 999L))

            val latest = dao.latestProgress()
            assertNotNull(latest)
            assertEquals("w1", latest!!.workId)
        } finally {
            db.close()
        }
    }

    @Test
    fun progressWriterSaveImmediateInterruptsPendingDebounce() = runBlocking {
        val written = CompletableDeferred<ProgressEntity>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writer =
                ProgressWriter(
                    scope = scope,
                    write = { written.complete(it) },
                )
            val start = System.nanoTime()
            writer.save(ProgressEntity("w1", "t1", 1000L, 1L))
            delay(10)
            writer.saveImmediate(ProgressEntity("w1", "t1", 2000L, 2L))
            val result = withTimeout(100) { written.await() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            assertEquals(2000L, result.positionMs)
            assertTrue("Expected immediate flush (<120ms), took ${elapsedMs}ms", elapsedMs < 120)
        } finally {
            scope.cancel()
        }
    }
}
