// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.SubtitleTimeline
import app.whisp.playback.OfflineService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in probe uses a session entered on the official login screen; logs stay anonymous. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DlsiteAccountLiveIntegrationTest {
    @get:Rule val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun purchasedWorkSupportsNativePlaybackDownloadsAndOfflineLogout() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeDlsiteAccount") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        val graph = (context.applicationContext as WhispApp).graph
        val source = graph.library.dao.sources().firstOrNull { it.kind == "dlsite" }
        assertNotNull("Complete official login before running the account probe", source)
        graph.sync.syncDlsitePurchases(source!!.id, full = true)
        val owned = graph.library.dlsitePurchases(source.id).first()
        assertTrue(owned.isNotEmpty())
        var selected: app.whisp.data.WorkDetails? = null
        var fallback: app.whisp.data.WorkDetails? = null
        for (candidate in owned.sortedByDescending { it.workno.startsWith("VJ") }) {
            val detail =
                try {
                    graph.sync.importDlsiteWork(source.id, candidate.workno)
                } catch (e: IllegalArgumentException) {
                    val latest = graph.library.dao.source(source.id)!!
                    val client =
                        app.whisp.data.DlsiteAccountClient(
                            app.whisp.data.DlsiteSession(graph.secrets.decrypt(latest.secret)),
                        )
                    val tree =
                        kotlinx.serialization.json.Json.parseToJsonElement(
                            client
                                .resourceBytes(candidate.workno, "ziptree.json", 8 * 1024 * 1024)
                                .toString(Charsets.UTF_8),
                        ) as kotlinx.serialization.json.JsonObject
                    val files = tree["playfile"] as kotlinx.serialization.json.JsonObject
                    val audioCount =
                        files.values.count {
                            ((it as? kotlinx.serialization.json.JsonObject)?.get("type")
                                    as? kotlinx.serialization.json.JsonPrimitive)
                                ?.content == "audio"
                        }
                    if (audioCount == 0) continue
                    throw e
                }
            if (fallback == null) fallback = detail
            if (detail.tracks.any { it.subtitleId != null }) {
                selected = detail
                break
            }
        }
        assertNotNull("Account needs a purchased work with playable audio", selected ?: fallback)
        val detail = selected ?: fallback!!
        val track =
            detail.tracks.firstOrNull { it.subtitleId != null && it.durationMs in 60_000..900_000 }
                ?: detail.tracks.firstOrNull { it.subtitleId != null }
                ?: detail.tracks.firstOrNull { it.durationMs in 60_000..900_000 }
                ?: detail.tracks.first()
        var cueCount = 0
        track.subtitleId?.let { id ->
            val attachment = detail.attachments.first { it.id == id }
            val text = graph.library.readAsset(id).toString(Charsets.UTF_8)
            cueCount =
                SubtitleTimeline.parse(text, attachment.fileName.substringAfterLast('.')).size
            assertTrue("Caption association returned no timed cues", cueCount > 0)
        }
        val item = graph.playback.mediaItem(track, detail.work)
        var player: ExoPlayer? = null
        fun onMain(action: (ExoPlayer) -> Unit) {
            instrumentation.runOnMainSync { action(player!!) }
        }
        fun await(label: String, predicate: (ExoPlayer) -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 40_000
            var complete = false
            while (!complete && SystemClock.elapsedRealtime() < until) {
                onMain {
                    assertNull("Player failed during $label", it.playerError)
                    complete = predicate(it)
                }
                if (!complete) SystemClock.sleep(100)
            }
            assertTrue("Timed out during $label", complete)
        }
        try {
            instrumentation.runOnMainSync {
                player =
                    ExoPlayer.Builder(context)
                        .setMediaSourceFactory(
                            DefaultMediaSourceFactory(graph.media.playbackFactory),
                        )
                        .build()
                        .apply {
                            volume = 0f
                            setMediaItem(item)
                            prepare()
                            play()
                        }
            }
            await("online playback") { it.isPlaying && it.currentPosition > 1200 }
            var position = 0L
            onMain {
                it.pause()
                position = it.currentPosition
            }
            SystemClock.sleep(500)
            onMain { assertTrue(kotlin.math.abs(it.currentPosition - position) < 100) }
            var target = 0L
            onMain {
                target = it.duration / 2
                it.seekTo(target)
                it.play()
            }
            await("online seek") {
                it.playbackState == Player.STATE_READY && it.currentPosition > target + 1200
            }
            onMain { it.stop() }
            graph.offline.downloadAttachments(detail.work.id)
            val request =
                DownloadRequest.Builder(track.id, item.localConfiguration!!.uri)
                    .setCustomCacheKey(track.id)
                    .build()
            DownloadService.sendAddDownload(context, OfflineService::class.java, request, false)
            withTimeout(90_000) {
                graph.media.downloads.first { states ->
                    states[track.id]?.state in
                        setOf(Download.STATE_COMPLETED, Download.STATE_FAILED)
                }
            }
            assertEquals(Download.STATE_COMPLETED, graph.media.downloads.value[track.id]!!.state)
            graph.sync.removeSource(source.id)
            assertTrue(graph.library.dlsitePurchases(source.id).first().isEmpty())
            assertNull(graph.library.dao.source(source.id))
            val offlineItem = graph.playback.mediaItem(track, detail.work)
            onMain {
                it.setMediaItem(offlineItem)
                it.prepare()
                it.play()
            }
            await("offline playback after logout") { it.isPlaying && it.currentPosition > 1200 }
            onMain { assertEquals(2, it.audioFormat?.channelCount) }
            println(
                "DLSITE_ACCOUNT_PROBE PASS ownedAudio=${owned.size}; tracks=${detail.tracks.size}; captionCues=$cueCount; online=true; pause=true; seek=true; downloaded=true; offlineLogout=true",
            )
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            graph.offline.removeOffline(detail.work.id)
            graph.sync.removeOnlineWork(detail.work.id)
        }
    }
}
