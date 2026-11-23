// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import okhttp3.Credentials
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val graph
        get() = (ui.activity.application as WhispApp).graph

    private lateinit var server: MockWebServer
    private val ranged = AtomicInteger()
    private val badAuth = AtomicInteger()
    private val fixture
        get() = File(graph.context.filesDir, "fixture")

    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")

    @Before
    fun prepare() {
        assertTrue("Push the fixture before running integration tests", fixture.isDirectory)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.headers["Authorization"] != Credentials.basic("listener", "test")) {
                        badAuth.incrementAndGet()
                        return MockResponse.Builder().code(401).build()
                    }
                    val relative =
                        request.url.pathSegments
                            .drop(1)
                            .filter { it.isNotBlank() }
                            .joinToString("/")
                    val file = File(fixture, relative)
                    if (request.method == "PROPFIND") {
                        val files = listOf(file) + file.listFiles().orEmpty().toList()
                        val body =
                            files.joinToString("") { f ->
                                val path =
                                    f.relativeTo(fixture).path.replace(File.separatorChar, '/')
                                val url =
                                    server
                                        .url("/ASMR/")
                                        .newBuilder()
                                        .addPathSegments(path)
                                        .apply { if (f.isDirectory) addPathSegment("") }
                                        .build()
                                """<d:response><d:href>${xml(url.encodedPath)}</d:href><d:propstat><d:prop><d:displayname>${xml(f.name)}</d:displayname><d:resourcetype>${if (f.isDirectory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>${f.length()}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                            }
                        return MockResponse.Builder()
                            .code(207)
                            .body("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                            .build()
                    }
                    if (!file.isFile) return MockResponse.Builder().code(404).build()
                    val range =
                        request.headers["Range"]
                            ?.removePrefix("bytes=")
                            ?.substringBefore('-')
                            ?.toLongOrNull() ?: 0
                    if (range > 0) ranged.incrementAndGet()
                    val bytes = file.readBytes()
                    val response = MockResponse.Builder().setHeader("Accept-Ranges", "bytes")
                    if (range >= bytes.size) return response.code(416).build()
                    val end =
                        request.headers["Range"]
                            ?.removePrefix("bytes=")
                            ?.substringAfter('-')
                            ?.toLongOrNull()
                            ?.coerceAtMost(bytes.size - 1L) ?: (bytes.size - 1L)
                    if (request.headers["Range"] != null)
                        response
                            .code(206)
                            .setHeader("Content-Range", "bytes $range-$end/${bytes.size}")
                    return response
                        .body(
                            Buffer().write(bytes, range.toInt(), (end - range + 1).toInt()),
                        )
                        .build()
                }
            }
        server.start()
        runBlocking {
            val source =
                SourceEntity(
                    "integration-nas",
                    "测试 NAS",
                    "webdav",
                    server.url("/ASMR/").toString(),
                    "listener",
                    graph.secrets.encrypt("test"),
                )
            graph.library.dao.putSource(source)
            graph.sync.scan(source.id)
        }
        ui.waitUntil(20000) { graph.library.works.value.isNotEmpty() }
    }

    private fun waitFor(timeoutMs: Long = 20000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Condition timed out")
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        Thread.sleep(700)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(graph.context.filesDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun generatedAudioBackgroundSeekSubtitlesAndOfflinePlayback() {
        val work = graph.library.works.value.first { it.rj == "RJ00000001" }
        val detail = runBlocking { graph.library.dao.details(work.id)!! }
        assertEquals(1, detail.tracks.size)
        assertNotNull(detail.tracks.single().subtitleId)
        screenshot("library")
        ui.onNodeWithText("测试作品").performClick()
        ui.onNodeWithText("播放作品").assertExists()
        screenshot("detail")
        ui.onNodeWithText("播放作品").performClick()
        val future =
            MediaController.Builder(
                    graph.context,
                    SessionToken(
                        graph.context,
                        ComponentName(graph.context, PlaybackService::class.java),
                    ),
                )
                .buildAsync()
        val controller = future.get(20, TimeUnit.SECONDS)
        fun playing(): Boolean {
            var value = false
            instrumentation.runOnMainSync { value = controller.isPlaying }
            return value
        }
        waitFor { playing() }
        val rangeBeforeSeek = ranged.get()
        instrumentation.runOnMainSync { controller.seekTo(15 * 60_000L) }
        waitFor {
            var position = 0L
            instrumentation.runOnMainSync { position = controller.currentPosition }
            position >= 15 * 60_000L && playing()
        }
        waitFor { ranged.get() > rangeBeforeSeek }
        assertEquals(0, badAuth.get())
        ui.onNodeWithContentDescription("定时停止").assertExists()
        Thread.sleep(1200)
        screenshot("player")
        ui.onNodeWithContentDescription("定时停止").performScrollTo().performClick()
        ui.onNodeWithText("15 分钟").performClick()
        waitFor {
            var deadline = 0L
            instrumentation.runOnMainSync {
                deadline = controller.sessionExtras.getLong("timerEnd")
            }
            deadline > 0
        }
        instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
        Thread.sleep(1500)
        assertTrue("Playback must continue with the activity in background", playing())
        instrumentation.runOnMainSync { controller.pause() }
        waitFor { !playing() }
        instrumentation.runOnMainSync {
            ui.activity.startActivity(
                android.content
                    .Intent(ui.activity, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        Thread.sleep(1000)
        runBlocking { graph.offline.downloadWork(detail.work.id) }
        waitFor(90000) {
            graph.media.downloads.value[detail.tracks.single().id]?.state ==
                androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
        }
        waitFor(20000) {
            runBlocking { graph.library.dao.details(detail.work.id)!!.work.offlineAttachments }
        }
        val position = runBlocking { graph.library.dao.progress(detail.work.id)!! }
        assertTrue(position.positionMs >= 15 * 60_000L)
        server.close()
        runBlocking {
            graph.sync.removeSource("integration-nas")
            assertTrue(graph.library.dao.details(detail.work.id)!!.work.offlineAttachments)
        }
        val item = runBlocking { graph.playback.mediaItem(detail.tracks.single(), detail.work) }
        instrumentation.runOnMainSync {
            controller.sendCustomCommand(
                SessionCommand(PlaybackService.TIMER, Bundle.EMPTY),
                Bundle().apply { putInt("minutes", 0) },
            )
            controller.setMediaItem(item, 20 * 60_000L)
            controller.prepare()
            controller.play()
        }
        waitFor { playing() }
        runBlocking {
            assertFalse(graph.library.readAsset(detail.tracks.single().subtitleId!!).isEmpty())
        }
        instrumentation.runOnMainSync { controller.pause() }
        instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
    }

    @After
    fun cleanup() {
        runCatching { server.close() }
    }
}
