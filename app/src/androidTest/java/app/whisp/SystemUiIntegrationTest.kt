// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.whisp.data.*
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
class SystemUiIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun predictiveCancelOutputPickerAndPrivateRecentsSurviveRecreation() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val device = UiDevice.getInstance(instrumentation)
        fun foreground() {
            instrumentation.runOnMainSync {
                val tasks =
                    ui.activity.getSystemService(android.app.ActivityManager::class.java).appTasks
                tasks.first { it.taskInfo?.taskId == ui.activity.taskId }.moveToFront()
            }
            assertTrue(device.wait(Until.hasObject(By.pkg(graph.context.packageName)), 5000))
        }
        instrumentation.runOnMainSync {
            ui.activity
                .getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .filter { it.taskInfo?.taskId != ui.activity.taskId }
                .forEach { it.finishAndRemoveTask() }
        }
        foreground()
        val green = Color.rgb(24, 228, 71)
        val cover =
            File(graph.context.filesDir, "custom-artwork/ui-cover").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(green)
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val wave = SystemMediaIntegrationTest.silentWave(graph.context.filesDir)
        val work =
            WorkEntity(
                "ui-work",
                null,
                "UI test",
                "UI test",
                coverId = "ui-cover",
                sourceId = "ui-source",
            )
        val track =
            TrackEntity(
                "ui-track",
                work.id,
                "UI track",
                "1.wav",
                "1.wav",
                1,
                40000,
                uri = wave.toURI().toString(),
                size = wave.length(),
            )
        runBlocking {
            graph.settings.update {
                it.copy(
                    hideArtwork = false,
                    hideRecents = false,
                    touchFeedback = true,
                    reduceMotion = false,
                )
            }
            graph.library.dao.putSource(
                SourceEntity("ui-source", "UI", "local", wave.parentFile!!.toURI().toString()),
            )
            graph.library.dao.putWorks(listOf(work))
            graph.library.dao.putTracks(listOf(track))
        }
        ui.waitUntil(15000) { graph.library.works.value.any { it.id == work.id } }
        ui.onNodeWithContentDescription("UI test").performClick()
        val original = ui.onNodeWithContentDescription("UI test").fetchSemanticsNode().boundsInRoot
        instrumentation.runOnMainSync {
            ui.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT),
            )
            ui.activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(100f, 200f, .5f, BackEventCompat.EDGE_LEFT),
            )
        }
        Thread.sleep(200)
        val during =
            ui.onAllNodesWithContentDescription("UI test")
                .fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.width * it.boundsInRoot.height }
                .boundsInRoot
        assertNotEquals("The exiting cover must follow gesture progress", original, during)
        instrumentation.runOnMainSync {
            ui.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        ui.onNodeWithText("播放作品").assertIsDisplayed()
        assertEquals(
            original,
            ui.onNodeWithContentDescription("UI test").fetchSemanticsNode().boundsInRoot,
        )
        instrumentation.runOnMainSync {
            ui.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                BackEventCompat(1000f, 200f, 0f, BackEventCompat.EDGE_RIGHT),
            )
            ui.activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                BackEventCompat(800f, 200f, .7f, BackEventCompat.EDGE_RIGHT),
            )
            ui.activity.onBackPressedDispatcher.onBackPressed()
        }
        ui.onNodeWithTag("library-grid").assertIsDisplayed()
        instrumentation.runOnMainSync {
            assertFalse(ui.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }

        val future =
            MediaController.Builder(
                    graph.context,
                    SessionToken(
                        graph.context,
                        ComponentName(graph.context, PlaybackService::class.java),
                    ),
                )
                .buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            val item = runBlocking { graph.playback.mediaItem(track, work) }
            instrumentation.runOnMainSync {
                controller.volume = 0f
                controller.setMediaItem(item)
                controller.prepare()
            }
            val deadline = System.currentTimeMillis() + 15000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    ready = controller.playbackState == Player.STATE_READY
                }
                Thread.sleep(100)
            }
            assertTrue(ready)
            foreground()
            val testLaunchIntent = Intent(ui.activity.intent)
            instrumentation.runOnMainSync { requireNotNull(controller.sessionActivity).send() }
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            instrumentation.runOnMainSync { ui.activity.intent = testLaunchIntent }
            ui.onNodeWithContentDescription("更多播放操作").performClick()
            ui.onNodeWithText("音频输出").performClick()
            assertTrue(
                "The native output picker must appear",
                device.wait(
                    Until.hasObject(
                        By.text(
                                java.util.regex.Pattern.compile(
                                    ".*(媒体输出|Media output|此手机|本机|This phone).*",
                                ),
                            )
                            .pkg("com.android.systemui"),
                    ),
                    5000,
                ),
            )
            device.pressBack()
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()

            fun greenPixelsInRecents(): Int {
                foreground()
                device.pressRecentApps()
                device.waitForIdle()
                Thread.sleep(700)
                val cardSelector =
                    By.res("com.sec.android.app.launcher", "taskView").descContains("Whisp")
                repeat(3) {
                    val card = device.findObject(cardSelector)
                    if (card != null && card.visibleBounds.width() < device.displayWidth / 2) {
                        device.swipe(
                            device.displayWidth * 4 / 5,
                            device.displayHeight / 2,
                            device.displayWidth / 5,
                            device.displayHeight / 2,
                            20,
                        )
                        device.waitForIdle()
                        Thread.sleep(300)
                    }
                }
                val card =
                    device.findObject(cardSelector)
                        ?: error("The current app task must be visible in Overview")
                val bounds =
                    card
                        .findObject(By.res("com.sec.android.app.launcher", "task_scene"))
                        .visibleBounds
                assertTrue(
                    "The task card must be centered",
                    bounds.width() > device.displayWidth / 2,
                )
                val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
                val width = bounds.width()
                val height = bounds.height()
                val pixels = IntArray(width * height)
                screenshot.getPixels(pixels, 0, width, bounds.left, bounds.top, width, height)
                screenshot.recycle()
                val count = pixels.count {
                    Color.green(it) > 100 &&
                        Color.green(it) > 3 * Color.red(it) &&
                        Color.green(it) > 2 * Color.blue(it)
                }
                device.pressBack()
                foreground()
                return count
            }
            val visiblePixels = greenPixelsInRecents()
            assertTrue(
                "Visible task preview must contain the neutral cover ($visiblePixels)",
                visiblePixels > 5000,
            )

            fun openPreferences() {
                ui.onNodeWithContentDescription("更多播放操作").performClick()
                ui.onNodeWithText("播放设置").performClick()
            }
            openPreferences()
            ui.onNodeWithTag("touch-feedback").performScrollTo().performClick()
            ui.onNodeWithTag("recents-privacy").performScrollTo().performClick()
            ui.waitUntil(5000) {
                !graph.settings.settings.value.touchFeedback &&
                    graph.settings.settings.value.hideRecents
            }
            ui.onNodeWithText("完成").performClick()
            instrumentation.runOnMainSync { ui.activity.recreate() }
            openPreferences()
            ui.onNodeWithTag("touch-feedback").performScrollTo().assertIsOff()
            ui.onNodeWithTag("recents-privacy").performScrollTo().assertIsOn()
            ui.onNodeWithText("完成").performClick()

            instrumentation.runOnMainSync {
                assertEquals(
                    "Foreground screenshots remain enabled",
                    0,
                    ui.activity.window.attributes.flags and
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
            val hiddenPixels = greenPixelsInRecents()
            assertTrue(
                "Private task preview must hide the neutral cover ($visiblePixels / $hiddenPixels)",
                hiddenPixels < 100,
            )
            ui.onNodeWithTag("subtitle-pane").assertIsDisplayed()
            instrumentation.runOnMainSync { assertFalse(controller.playWhenReady) }
        } finally {
            foreground()
            instrumentation.runOnMainSync {
                controller.pause()
                controller.clearMediaItems()
                controller.release()
                ui.activity.finish()
            }
        }
    }
}
