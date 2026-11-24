// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
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
class PlaybackFlowIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val graph
        get() = (ui.activity.application as WhispApp).graph

    private var diagnostics: () -> String = { "controller not ready" }

    private fun waitFor(timeout: Long = 30000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Condition timed out: " + diagnostics())
    }

    @Test
    fun backgroundScanPersistentSubtitlesAndPlaybackControls() {
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val args = InstrumentationRegistry.getArguments()
        val source = runBlocking {
            graph.library
                .addSmb(
                    "Flow",
                    requireNotNull(args.getString("smbRoot")),
                    requireNotNull(args.getString("smbUser")),
                    requireNotNull(args.getString("smbPassword")),
                )
                .also { graph.sync.scheduleScan(it, force = true) }
        }
        waitFor { source in graph.sync.busy.value }
        waitFor {
            graph.context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .any {
                    it.notification.channelId == "library-scan"
                }
        }
        instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
        waitFor(60000) {
            runBlocking { graph.library.dao.source(source)!!.lastScan > 0 } &&
                source !in graph.sync.busy.value
        }
        val detail = runBlocking {
            graph.library.dao.allWorks().single().let { graph.library.dao.details(it.id)!! }
        }
        assertEquals(2, detail.tracks.size)
        val tracks = detail.tracks.sortedBy { it.order }
        val future =
            MediaController.Builder(
                    graph.context,
                    SessionToken(
                        graph.context,
                        ComponentName(graph.context, PlaybackService::class.java),
                    ),
                )
                .buildAsync()
        val c = future.get(20, TimeUnit.SECONDS)
        val playback = graph.playback
        instrumentation.runOnMainSync {
            assertTrue(c.isCommandAvailable(Player.COMMAND_SET_VOLUME))
            c.volume = 0f
            ui.activity.startActivity(
                Intent(ui.activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        diagnostics = {
            var description = ""
            instrumentation.runOnMainSync {
                description =
                    "state=${playback.state.value}; pState=${c.playbackState}; suppression=${c.playbackSuppressionReason}; play=${c.playWhenReady}; position=${c.currentPosition}; error=${c.playerError?.errorCodeName}"
            }
            description
        }
        try {
            ui.onNodeWithContentDescription("测试作品").performClick()
            ui.onNodeWithText("播放作品").performClick()
            waitFor {
                playback.state.value.playWhenReady && playback.state.value.trackId == tracks[0].id
            }
            waitFor { playback.positionMs.value > 0 }
            val beforeClear = playback.positionMs.value
            runBlocking {
                graph.images.clearCaches(graph.media::clearStreamingCache)
                graph.settings.update { it.copy(streamingCacheMiB = 64) }
            }
            waitFor { playback.positionMs.value > beforeClear + 500 }
            assertEquals(tracks[0].id, playback.state.value.trackId)
            assertTrue(playback.state.value.playWhenReady)
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !playback.state.value.playWhenReady }
            waitFor { playback.state.value.durationMs > 0 }
            instrumentation.runOnMainSync { playback.seek(4000) }
            waitFor { playback.positionMs.value == 4000L }
            waitFor { playback.subtitles.cues.value.isNotEmpty() }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 1 first").assertIsDisplayed()
            val pane = ui.onNodeWithTag("subtitle-pane").fetchSemanticsNode().boundsInRoot
            val lineHeight = ui.onNodeWithTag("subtitle-0").fetchSemanticsNode().boundsInRoot.height
            ui.onNodeWithTag("subtitle-pane").performTouchInput { swipeUp() }
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()
            val readingBounds = ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot
            instrumentation.runOnMainSync { playback.seek(6000) }
            waitFor { playback.positionMs.value == 6000L }
            ui.onNodeWithContentDescription("跟随字幕").assertIsDisplayed()
            assertEquals(
                "Seeking during manual reading must not scroll the script",
                readingBounds,
                ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot,
            )
            instrumentation.runOnMainSync { playback.seek(4000) }
            waitFor { playback.positionMs.value == 4000L }
            ui.onNodeWithContentDescription("跟随字幕").performClick()
            ui.onNodeWithContentDescription("播放").assertIsDisplayed()
            ui.onNodeWithTag("subtitle-0").performTouchInput { longClick() }
            ui.onNodeWithText("从这里播放").assertIsDisplayed()
            assertFalse(
                "Inspecting a subtitle menu must not start audio",
                playback.state.value.playWhenReady,
            )
            assertEquals(
                "Inspecting a subtitle menu must not seek",
                4000L,
                playback.positionMs.value,
            )
            ui.onNodeWithText("从这里播放").performClick()
            waitFor { playback.state.value.playWhenReady && playback.positionMs.value < 3000 }
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !playback.state.value.playWhenReady }
            ui.onNodeWithContentDescription("播放队列").performClick()
            ui.onNodeWithText("播放队列").assertExists()
            ui.onNodeWithText("02").performClick()
            waitFor {
                playback.state.value.trackId == tracks[1].id && playback.state.value.playWhenReady
            }
            waitFor { playback.subtitles.cues.value.firstOrNull()?.text == "Track 2 first" }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            assertEquals(pane, ui.onNodeWithTag("subtitle-pane").fetchSemanticsNode().boundsInRoot)
            ui.onNodeWithContentDescription("暂停").performClick()
            instrumentation.runOnMainSync {
                playback.seek(6000)
                playback.playWork(detail, tracks[1].id)
            }
            waitFor { playback.state.value.playWhenReady && playback.positionMs.value >= 6000 }
            assertTrue(
                "Selecting the current track must preserve its position",
                playback.positionMs.value < 10000,
            )
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertEquals(
                "Changing the active cue must not change the subtitle font or line height",
                lineHeight,
                ui.onNodeWithTag("subtitle-1").fetchSemanticsNode().boundsInRoot.height,
                .5f,
            )
            ui.onNodeWithContentDescription("暂停").performClick()
            instrumentation.runOnMainSync { playback.seek(1000) }
            ui.onNodeWithContentDescription("片段").performClick()
            ui.onNodeWithText("设起点").performClick()
            instrumentation.runOnMainSync { playback.seek(3000) }
            ui.onNodeWithText("设终点").performClick()
            waitFor {
                playback.state.value.loopStart == 1000L && playback.state.value.loopEnd == 3000L
            }
            ui.onNodeWithText("取消循环").assertIsDisplayed()
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("播放设置").performClick()
            ui.onNodeWithTag("reduce-motion").performClick()
            waitFor { graph.settings.settings.value.reduceMotion }
            ui.onNodeWithText("完成").performClick()
            runBlocking {
                graph.settings.update { it.copy(subtitleSize = 24, hideArtwork = true) }
            }
            waitFor {
                graph.settings.settings.value.subtitleSize == 24 &&
                    graph.settings.settings.value.hideArtwork
            }
            assertTrue(
                "Other preferences must retain reduced motion",
                graph.settings.settings.value.reduceMotion,
            )
            Thread.sleep(600)
            assertEquals(
                "Preferences must retain an active loop",
                1000L,
                playback.state.value.loopStart,
            )
            instrumentation.runOnMainSync { playback.setLoop(1000, 30000) }
            waitFor { playback.state.value.loopEnd == 30000L }
            instrumentation.runOnMainSync { c.seekTo(15000) }
            waitFor { playback.positionMs.value == 15000L }
            instrumentation.runOnMainSync { c.seekBack() }
            waitFor { playback.positionMs.value == 5000L }
            assertEquals(1000L, playback.state.value.loopStart)
            instrumentation.runOnMainSync { c.seekTo(40000) }
            waitFor { playback.state.value.loopStart == -1L }
            waitFor { playback.positionMs.value == 40000L }
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("精确跳转").performClick()
            ui.onNodeWithText("+1 秒").performClick()
            ui.onNodeWithText("跳转").performClick()
            waitFor { playback.positionMs.value == 41000L }
            assertFalse("Precise seeking must retain a pause", playback.state.value.playWhenReady)
            ui.onNodeWithTag("seek-bar").performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(20f, 0f))
                moveBy(androidx.compose.ui.geometry.Offset(0f, -160f))
                moveBy(androidx.compose.ui.geometry.Offset(15f, 0f))
                up()
            }
            assertFalse(playback.state.value.playWhenReady)
            val track1Duration = playback.state.value.durationMs
            instrumentation.runOnMainSync {
                c.seekTo(1, track1Duration - 200)
                c.play()
            }
            waitFor { playback.state.value.ended }
            assertFalse(playback.state.value.playWhenReady)
            instrumentation.runOnMainSync { c.play() }
            waitFor {
                playback.state.value.trackId == tracks[0].id && playback.state.value.playWhenReady
            }
            instrumentation.runOnMainSync {
                c.seekTo(1, track1Duration - 200)
                c.play()
            }
            waitFor { playback.state.value.ended }
            ui.onNodeWithContentDescription("重新播放作品").performClick()
            waitFor {
                playback.state.value.trackId == tracks[0].id && playback.state.value.playWhenReady
            }
            ui.onNodeWithContentDescription("暂停").performClick()
            waitFor { !playback.state.value.playWhenReady }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(graph.context.getExternalFilesDir(null), "ux-player.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { ui.activity.moveTaskToBack(true) }
            Thread.sleep(1200)
            assertFalse(playback.state.value.playWhenReady)
            instrumentation.runOnMainSync {
                ui.activity.startActivity(
                    Intent(ui.activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            instrumentation.runOnMainSync {
                playback.playWork(detail, tracks[1].id, startPosition = 5000)
                playback.togglePlay()
            }
            waitFor {
                playback.openingTrackId.value == null &&
                    playback.state.value.trackId == tracks[1].id
            }
            Thread.sleep(1500)
            assertFalse(
                "Pausing while opening must retain the target and stay paused after buffering",
                playback.state.value.playWhenReady,
            )
            assertTrue(playback.positionMs.value >= 5000)
            waitFor { graph.library.progress.value.firstOrNull()?.trackId == tracks[1].id }
            instrumentation.runOnMainSync { c.clearMediaItems() }
            waitFor { playback.state.value.trackId == null }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertFalse(
                "Opening the remembered player must not autoplay",
                playback.state.value.playWhenReady,
            )
            ui.onNodeWithContentDescription("收起播放器").performClick()
            ui.onNodeWithContentDescription("返回作品库").performClick()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithContentDescription("测试作品").performClick()
            assertFalse(
                "Navigating to a work must not autoplay",
                playback.state.value.playWhenReady,
            )
            runBlocking { graph.settings.update { it.copy(reduceMotion = false) } }
            waitFor { !graph.settings.settings.value.reduceMotion }
            ui.onNodeWithContentDescription("返回作品库").performClick()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithTag("mini-player").performClick()
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            ui.onNodeWithContentDescription("测试作品").assertIsDisplayed()
            ui.onNodeWithTag("mini-player").performClick()
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            ui.onNodeWithText("Track 2 second").assertIsDisplayed()
            assertFalse(
                "Mini-player expansion must preserve paused playback",
                playback.state.value.playWhenReady,
            )
        } finally {
            instrumentation.runOnMainSync {
                c.pause()
                MediaController.releaseFuture(future)
            }
        }
    }
}
