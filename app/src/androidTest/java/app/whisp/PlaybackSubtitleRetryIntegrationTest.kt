// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import app.whisp.playback.PlaybackService
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackSubtitleRetryIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
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

    private fun seed(count: Int = 1): Pair<WorkDetails, File> {
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val wave = SystemMediaIntegrationTest.silentWave(graph.context.filesDir)
        val subtitle = File(graph.context.filesDir, "subtitle-retry.vtt").apply { delete() }
        val work =
            WorkEntity(
                "subtitle-retry-work",
                null,
                "Subtitle retry",
                "Subtitle retry",
                sourceId = "subtitle-retry-source",
            )
        val attachment =
            AttachmentEntity(
                "subtitle-retry-vtt",
                work.id,
                "Script",
                "test.vtt",
                "audio/test.vtt",
                "subtitle",
                uri = subtitle.toURI().toString(),
                size = 100,
            )
        val tracks =
            (1..count).map {
                TrackEntity(
                    "subtitle-retry-$it",
                    work.id,
                    "Track $it",
                    "$it.wav",
                    "audio/$it.wav",
                    it,
                    40000,
                    subtitleId = attachment.id,
                    uri = wave.toURI().toString(),
                    size = wave.length(),
                )
            }
        runBlocking {
            graph.settings.update { it.copy(reduceMotion = true) }
            graph.library.dao.putSource(
                SourceEntity(
                    "subtitle-retry-source",
                    "Test",
                    "local",
                    wave.parentFile!!.toURI().toString(),
                ),
            )
            graph.library.dao.putWorks(listOf(work))
            graph.library.dao.putTracks(tracks)
            graph.library.dao.putAttachments(listOf(attachment))
        }
        return WorkDetails(work, tracks, listOf(attachment)) to subtitle
    }

    private fun missingSubtitle(
        playback: PlaybackCoordinator,
        c: MediaController,
        detail: WorkDetails,
    ) {
        main {
            c.volume = 0f
            c.pause()
            c.clearMediaItems()
            playback.playWork(detail, detail.tracks.first().id)
            playback.togglePlay()
        }
        waitFor("A missing subtitle must expose its retry state while audio remains ready") {
            playback.subtitles.error.value != null &&
                !playback.subtitles.loading.value &&
                playback.openingTrackId.value == null &&
                c.playbackState == Player.STATE_READY
        }
        main {
            assertTrue(playback.subtitles.cues.value.isEmpty())
            assertFalse(c.playWhenReady)
        }
    }

    private fun restoreSubtitle(file: File) {
        file.writeText("WEBVTT\n\n00:00:00.000 --> 00:00:30.000\nRecovered script\n")
    }

    @Test
    fun playerEventsKeepFailureUntilContinuingTheSameTrackRetries() {
        val (detail, subtitle) = seed()
        val c = controller()
        val playback = graph.playback
        try {
            missingSubtitle(playback, c, detail)
            restoreSubtitle(subtitle)
            repeat(5) { index ->
                main { c.seekTo(5000L + index) }
                Thread.sleep(50)
            }
            main {
                assertTrue(
                    "Player events must not retry subtitle IO",
                    playback.subtitles.cues.value.isEmpty(),
                )
                assertNotNull(playback.subtitles.error.value)
                playback.playWork(detail, detail.tracks.first().id)
                playback.togglePlay()
            }
            waitFor("Continuing the same track must recover its subtitle") {
                playback.subtitles.cues.value.singleOrNull()?.text == "Recovered script" &&
                    playback.subtitles.error.value == null &&
                    !playback.subtitles.loading.value
            }
            main {
                assertEquals(5004L, c.currentPosition)
                assertFalse(c.playWhenReady)
            }
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            subtitle.delete()
        }
    }

    @Test
    fun theSubtitleRetryButtonRecoversWithoutStartingOrSeekingAudio() {
        val (detail, subtitle) = seed()
        val c = controller()
        val playback = graph.playback
        val launchIntent = Intent(ui.activity.intent)
        try {
            missingSubtitle(playback, c, detail)
            main {
                c.seekTo(6000)
                requireNotNull(c.sessionActivity).send()
            }
            ui.onNodeWithText("重试字幕").assertIsDisplayed()
            main { ui.activity.intent = launchIntent }
            restoreSubtitle(subtitle)
            ui.onNodeWithText("重试字幕").performClick()
            waitFor("The subtitle button must recover the current script") {
                playback.subtitles.cues.value.singleOrNull()?.text == "Recovered script" &&
                    playback.subtitles.error.value == null
            }
            main {
                assertEquals(6000L, c.currentPosition)
                assertFalse(c.playWhenReady)
            }
        } finally {
            main {
                ui.activity.intent = launchIntent
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            subtitle.delete()
        }
    }

    @Test
    fun shuffledQueueKeepsSnapshotsOfRemovedTracksAndSelectsTheActualMediaItem() {
        val (detail, subtitle) = seed(3)
        restoreSubtitle(subtitle)
        val c = controller()
        val playback = graph.playback
        val items = runBlocking { detail.tracks.map { playback.mediaItem(it, detail.work) } }
        try {
            main {
                c.volume = 0f
                c.setMediaItems(items)
                c.shuffleModeEnabled = true
                c.prepare()
            }
            fun expectedQueue(): List<String> {
                val timeline = c.currentTimeline
                val window = Timeline.Window()
                return buildList {
                    var index = timeline.getFirstWindowIndex(true)
                    while (index != C.INDEX_UNSET && size < timeline.windowCount) {
                        add(timeline.getWindow(index, window).mediaItem.mediaId)
                        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
                    }
                }
            }
            waitFor("Queue IDs and records must follow the actual shuffled timeline") {
                c.playbackState == Player.STATE_READY &&
                    playback.state.value.queueIds == expectedQueue() &&
                    playback.queueTracks.value.map { it.id } == expectedQueue() &&
                    playback.queueTracks.value.all { it.durationMs == 40000L }
            }
            val removed = detail.tracks.last()
            runBlocking {
                graph.database.withTransaction {
                    graph.library.dao.deleteWorkTracks(detail.work.id)
                    graph.library.dao.putTracks(detail.tracks.dropLast(1))
                }
            }
            main { playback.selectQueuedTrack(removed.id) }
            waitFor("Selecting a queued track must survive its removal from the new index") {
                c.currentMediaItem?.mediaId == removed.id &&
                    c.playWhenReady &&
                    playback.queueTracks.value.any {
                        it.id == removed.id && it.title == removed.title
                    }
            }
            main {
                assertEquals(3, c.mediaItemCount)
                assertTrue(c.shuffleModeEnabled)
                assertEquals(expectedQueue(), playback.state.value.queueIds)
            }
        } finally {
            main {
                c.pause()
                c.clearMediaItems()
                c.release()
            }
            subtitle.delete()
        }
    }
}
