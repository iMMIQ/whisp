// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import android.os.Bundle
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import app.whisp.playback.OfflineService
import app.whisp.playback.PlaybackService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackRegressionIntegrationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val graph
        get() = (ui.activity.application as WhispApp).graph

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            if (main(condition)) return
            Thread.sleep(50)
        }
        fail(message)
    }

    private fun seed(): WorkDetails {
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val wave = SystemMediaIntegrationTest.silentWave(graph.context.filesDir)
        val work =
            WorkEntity(
                "regression-work",
                null,
                "Playback test",
                "Playback test",
                sourceId = "regression-source",
            )
        val tracks =
            (1..2).map {
                TrackEntity(
                    "regression-$it",
                    work.id,
                    "Track $it",
                    "$it.wav",
                    "audio/$it.wav",
                    it,
                    40000,
                    uri = wave.toURI().toString(),
                    size = wave.length(),
                )
            }
        runBlocking {
            graph.library.dao.putSource(
                SourceEntity(
                    "regression-source",
                    "Test",
                    "local",
                    wave.parentFile!!.toURI().toString(),
                ),
            )
            graph.library.dao.putWorks(listOf(work))
            graph.library.dao.putTracks(tracks)
        }
        return WorkDetails(work, tracks, emptyList())
    }

    private fun controller(): MediaController = main {
        MediaController.Builder(
                graph.context,
                SessionToken(
                    graph.context,
                    ComponentName(graph.context, PlaybackService::class.java),
                ),
            )
            .buildAsync()
    }
        .get(20, TimeUnit.SECONDS)

    @Test
    fun replacingAnEvictedMetadataRequestRetainsLoadingAndItsInFlightRequest() {
        val loader = graph.dlsiteLoader
        val firstRequests = AtomicInteger()
        MockWebServer().use { server ->
            server.start()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val number = request.url.queryParameter("workno") ?: "RJ00000001"
                        if (number == "RJ00000001") firstRequests.incrementAndGet()
                        return MockResponse.Builder()
                            .body(
                                """[{"product_id":"$number","work_name":"Test","maker_name":"Test","image_main":{"url":"//img.dlsite.jp/example.jpg"}}]""",
                            )
                            .bodyDelay(2, TimeUnit.SECONDS)
                            .build()
                    }
                }
            val client = DlsiteClient(OkHttpClient(), server.url("/"))
            fun load(index: Int) {
                val number = "RJ" + (index + 1).toString().padStart(8, '0')
                loader.load(
                    "request-$index",
                    number,
                    seed = DlsiteDetails(number),
                    force = true,
                    client = client,
                )
            }
            main { load(0) }
            assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            main {
                (1..6).forEach(::load)
                load(0)
            }
            waitFor("The replacement must start its network request") { firstRequests.get() >= 2 }
            Thread.sleep(200)
            main {
                assertTrue(
                    "Finishing an evicted request must not clear the replacement's loading state",
                    "product" in loader.states.value.getValue("request-0").loading,
                )
                load(0)
            }
            Thread.sleep(200)
            assertEquals(
                "An in-flight replacement must prevent another request for the same part",
                2,
                firstRequests.get(),
            )
            waitFor("The replacement must complete normally") {
                loader.states.value["request-0"]?.details?.completeProduct == true &&
                    loader.states.value.getValue("request-0").loading.isEmpty()
            }
        }
    }

    @Test
    fun continuingTheCurrentTrackUpdatesTheQueueAndClearsShuffleWithoutRewinding() {
        val detail = seed()
        val items = runBlocking { detail.tracks.map { graph.playback.mediaItem(it, detail.work) } }
        val c = controller()
        val playback = graph.playback
        try {
            main {
                c.volume = 0f
                c.setMediaItem(items.first(), 12000)
                c.prepare()
            }
            waitFor("Audio must prepare") { c.playbackState == Player.STATE_READY }
            main {
                c.shuffleModeEnabled = true
                playback.playWork(detail)
                playback.togglePlay()
            }
            waitFor("Continuing must install the complete current queue") {
                playback.openingTrackId.value == null &&
                    c.mediaItemCount == 2 &&
                    c.playbackState == Player.STATE_READY
            }
            main {
                assertEquals(detail.tracks.first().id, c.currentMediaItem!!.mediaId)
                assertEquals(12000L, c.currentPosition)
                assertFalse(c.playWhenReady)
                assertFalse(c.shuffleModeEnabled)
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_ONE
                playback.playWork(detail, detail.tracks.first().id)
                playback.togglePlay()
            }
            waitFor("Normal playback must clear shuffle even when the queue already matches") {
                playback.openingTrackId.value == null && !c.shuffleModeEnabled
            }
            main {
                assertEquals(12000L, c.currentPosition)
                assertFalse(c.playWhenReady)
                assertEquals(Player.REPEAT_MODE_OFF, c.repeatMode)
            }
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
        }
    }

    @Test
    fun aPartiallySavedWorkPlaysAfterItsSourceIsRemovedAndUnavailableSelectionFailsClearly() {
        val detail = seed()
        val first = detail.tracks.first()
        val original = SystemMediaIntegrationTest.silentWave(graph.context.filesDir)
        runBlocking {
            val request =
                DownloadRequest.Builder(first.id, android.net.Uri.fromFile(original))
                    .setCustomCacheKey(first.id)
                    .build()
            DownloadService.sendAddDownload(
                graph.context,
                OfflineService::class.java,
                request,
                false,
            )
            withTimeout(30_000) {
                graph.media.downloads.first { it[first.id]?.state == Download.STATE_COMPLETED }
            }
            assertFalse(graph.library.dao.work(detail.work.id)!!.offlineAttachments)
            graph.sync.removeSource("regression-source")
        }
        original.delete()
        waitFor("Completed audio must keep a work visible even without saved attachments") {
            graph.library.works.value.any { it.id == detail.work.id }
        }
        val playback = graph.playback
        val c = controller()
        val updatedDetail = runBlocking { graph.library.dao.details(detail.work.id)!! }
        try {
            main {
                c.volume = 0f
                c.pause()
                c.clearMediaItems()
                playback.playWork(updatedDetail, first.id)
                playback.togglePlay()
            }
            waitFor("A saved track must prepare despite unavailable sibling tracks") {
                playback.openingTrackId.value == null &&
                    c.currentMediaItem?.mediaId == first.id &&
                    c.playbackState == Player.STATE_READY
            }
            main {
                assertEquals(1, c.mediaItemCount)
                assertFalse(c.playWhenReady)
                c.clearMediaItems()
                playback.error.value = null
                playback.playWork(updatedDetail, detail.tracks.last().id)
            }
            waitFor("Selecting an unavailable track must expose a useful error") {
                playback.error.value?.startsWith("音轨文件不可用") == true
            }
            main {
                assertEquals(
                    "An unavailable selection must not play another track",
                    0,
                    c.mediaItemCount,
                )
                assertFalse(c.playWhenReady)
            }
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            runBlocking {
                graph.offline.removeOffline(detail.work.id)
                withTimeout(15_000) { graph.media.downloads.first { first.id !in it } }
            }
        }
    }

    @Test
    fun anAbLoopAtEofKeepsItsTrackAndAnOutOfBoundsRangeIsCleared() {
        val detail = seed()
        val items = runBlocking { detail.tracks.map { graph.playback.mediaItem(it, detail.work) } }
        val c = controller()
        try {
            main {
                c.volume = 0f
                c.setMediaItems(items, 0, 39900)
                c.prepare()
            }
            waitFor("Audio must prepare") { c.playbackState == Player.STATE_READY }
            main {
                    c.sendCustomCommand(
                        SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
                        Bundle().apply {
                            putLong("start", 39500)
                            putLong("end", 40000)
                        },
                    )
                }
                .get(10, TimeUnit.SECONDS)
            main { c.play() }
            waitFor("An EOF loop must return to A before advancing the queue") {
                c.currentMediaItem?.mediaId == detail.tracks.first().id &&
                    c.currentPosition in 39500..39800 &&
                    c.isPlaying
            }
            Thread.sleep(1200)
            main {
                assertEquals(detail.tracks.first().id, c.currentMediaItem!!.mediaId)
                assertTrue(c.isPlaying)
                assertEquals(39500L, c.sessionExtras.getLong("loopStart", -1))
                c.pause()
            }
            main {
                    c.sendCustomCommand(
                        SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
                        Bundle().apply {
                            putLong("start", 40000)
                            putLong("end", 50000)
                        },
                    )
                }
                .get(10, TimeUnit.SECONDS)
            waitFor("A loop starting beyond EOF must be cleared") {
                c.sessionExtras.getLong("loopStart", -1) == -1L &&
                    c.sessionExtras.getLong("loopEnd", -1) == -1L
            }
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
        }
    }
}
