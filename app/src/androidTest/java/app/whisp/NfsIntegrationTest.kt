// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class NfsIntegrationTest {
    @get:Rule
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph
        get() = (instrumentation.targetContext.applicationContext as WhispApp).graph

    private fun root(): String {
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val root = InstrumentationRegistry.getArguments().getString("nfsRoot")
        assumeTrue("Provide a dedicated synthetic NFS fixture", root != null)
        return root!!
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private suspend fun waitFor(stage: String, block: () -> Boolean) {
        println("NFS stage: $stage")
        try {
            withTimeout(30_000) { while (!block()) delay(50) }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("NFS stage timed out: $stage", error)
        }
    }

    @Test
    fun nativeUnicodeRandomReadAndEofWorkAcrossProtocols() = runBlocking {
        val root = root()
        val pattern = byteArrayOf(17, 25, 125, 126, 253.toByte(), 0, 65, 97)
        val position = 4L * 1024 * 1024 * 1024 + 37
        for (version in listOf(3, 4, 42)) {
            println("NFS protocol: $version")
            val source =
                SourceEntity(
                    "nfs-contract-$version",
                    "NFS 示例",
                    "nfs",
                    NfsAddress.parse(root, version).normalized,
                )
            graph.sources.nfs.check(source)
            val files = graph.sources.nfs.walk(source)
            assertTrue(files.none { it.path.endsWith("循环链接") })
            val audio = files.single { it.path.endsWith(".wav") }
            assertTrue(audio.path.contains("😀"))
            assertEquals(audio.path, NfsAddress.assetPath(audio.uri, source.id))
            graph.sources.nfs.open(source, audio.uri).use { reader ->
                val prefix = ByteArray(4)
                assertEquals(4, reader.read(prefix, 0, 0, 4))
                assertEquals("RIFF", prefix.toString(Charsets.US_ASCII))
            }
            val data = RemoteFileDataSource("NFS") { graph.sources.nfs.open(source, it) }
            val uri = NfsAddress.assetUri(source.id, "large.bin").toUri()
            assertEquals(
                8L,
                data.open(
                    DataSpec.Builder().setUri(uri).setPosition(position).setLength(8).build(),
                ),
            )
            val bytes = ByteArray(8)
            assertEquals(8, data.read(bytes, 0, 8))
            assertArrayEquals(pattern, bytes)
            assertEquals(C.RESULT_END_OF_INPUT, data.read(bytes, 0, 8))
            data.close()
            val size = 4L * 1024 * 1024 * 1024 + 4096
            assertEquals(0L, data.open(DataSpec.Builder().setUri(uri).setPosition(size).build()))
            assertEquals(C.RESULT_END_OF_INPUT, data.read(bytes, 0, 8))
            data.close()
            try {
                data.open(DataSpec.Builder().setUri(uri).setPosition(size + 1).build())
                fail("Expected out-of-range error")
            } catch (error: DataSourceException) {
                assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
            }
            data.close()
        }
    }

    @Test
    fun scanSubtitlesPlaybackDownloadAndOfflineUseTheSharedLibrary() = runBlocking {
        val source =
            SourceEntity(
                "nfs-library-test",
                "NFS 示例",
                "nfs",
                NfsAddress.parse(root(), 42).normalized,
            )
        graph.library.dao.putSource(source)
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        try {
            graph.sync.scan(source.id)
            val workId = graph.library.dao.sourceWorkIds(source.id).single()
            val detail = graph.library.dao.details(workId)!!
            assertEquals(1, detail.tracks.size)
            val track = detail.tracks.single()
            val subtitleId = requireNotNull(track.subtitleId)
            assertEquals(
                "示例字幕",
                SubtitleTimeline.parse(
                        graph.library.readAsset(subtitleId).toString(Charsets.UTF_8),
                        "vtt",
                    )
                    .single()
                    .text,
            )
            val item = graph.playback.mediaItem(track, detail.work)
            assertEquals("whisp-nfs", item.localConfiguration!!.uri.scheme)
            future = main {
                MediaController.Builder(
                        graph.context,
                        SessionToken(
                            graph.context,
                            ComponentName(graph.context, PlaybackService::class.java),
                        ),
                    )
                    .buildAsync()
            }
            val controller = future.get(20, TimeUnit.SECONDS)
            main {
                controller.volume = 0f
                controller.setMediaItem(item)
                controller.prepare()
                controller.play()
            }
            waitFor("online-playback") {
                main { controller.isPlaying && controller.playbackState == Player.STATE_READY }
            }
            main {
                controller.pause()
                controller.seekTo(15_000)
            }
            waitFor("seek") {
                main {
                    controller.playbackState == Player.STATE_READY &&
                        controller.currentPosition >= 15_000
                }
            }
            assertTrue(main { controller.duration in 29_000..31_000 })
            graph.offline.downloadWork(workId)
            withTimeout(45_000) {
                graph.media.downloads.first { it[track.id]?.state == Download.STATE_COMPLETED }
            }
            waitFor("offline-attachments") {
                graph.library.works.value.firstOrNull { it.id == workId }?.offlineAttachments ==
                    true
            }
            main { controller.clearMediaItems() }
            graph.sync.removeSource(source.id)
            val saved = graph.library.dao.details(workId)!!
            assertTrue(graph.offline.isOffline(saved))
            assertEquals(
                "示例字幕",
                SubtitleTimeline.parse(
                        graph.library.readAsset(subtitleId).toString(Charsets.UTF_8),
                        "vtt",
                    )
                    .single()
                    .text,
            )
            val offline = graph.playback.mediaItem(track, saved.work)
            main {
                controller.setMediaItem(offline)
                controller.prepare()
                controller.play()
            }
            waitFor("offline-playback") { main { controller.isPlaying } }
            main {
                controller.pause()
                controller.clearMediaItems()
            }
            graph.offline.removeOffline(workId)
        } finally {
            future?.let { main { MediaController.releaseFuture(it) } }
            graph.sync.removeSource(source.id)
        }
    }
}
