// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.whisp.data.WorkEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionNavigationIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun libraryScrollSurvivesCoverNavigationAndReducedMotion() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        fun foreground() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ui.activity
                    .getSystemService(android.app.ActivityManager::class.java)
                    .appTasks
                    .first { it.taskInfo?.taskId == ui.activity.taskId }
                    .moveToFront()
            }
            assertTrue(
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .wait(Until.hasObject(By.pkg(graph.context.packageName)), 5000),
            )
        }
        foreground()
        runBlocking {
            graph.library.dao.putWorks(
                (1..24).map {
                    WorkEntity(
                        "motion-$it",
                        null,
                        "Motion %02d".format(it),
                        "Motion %02d".format(it),
                        addedAt = it.toLong(),
                        offlineAttachments = true,
                    )
                },
            )
        }
        ui.waitUntil(15000) { graph.library.works.value.size == 24 }
        ui.onNodeWithTag("library-grid").performScrollToNode(hasContentDescription("Motion 06"))
        val original =
            ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot
        val gridBefore = ui.onNodeWithTag("library-grid").fetchSemanticsNode()
        val gridBoundsBefore = gridBefore.boundsInRoot
        val scrollBefore =
            gridBefore.config[
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value()
        ui.onNodeWithContentDescription("Motion 06").performClick()
        ui.onNodeWithText("播放作品").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回作品库").performClick()
        assertEquals(
            original,
            ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot,
        )

        ui.onNodeWithContentDescription("Motion 06").performClick()
        foreground()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(
                "Details must register a system back callback",
                ui.activity.onBackPressedDispatcher.hasEnabledCallbacks(),
            )
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.onNodeWithTag("library-grid").assertIsDisplayed()
        assertEquals(
            original,
            ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot,
        )

        runBlocking { graph.settings.update { it.copy(reduceMotion = true) } }
        ui.waitUntil(5000) { graph.settings.settings.value.reduceMotion }
        ui.onNodeWithContentDescription("Motion 06").performClick()
        ui.onNodeWithContentDescription("返回作品库").performClick()
        assertEquals(
            original,
            ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot,
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ui.activity.recreate() }
        foreground()
        ui.onNodeWithTag("library-grid").assertIsDisplayed()
        val gridAfter = ui.onNodeWithTag("library-grid").fetchSemanticsNode()
        assertEquals(
            scrollBefore,
            gridAfter.config[
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                .value(),
            0f,
        )
        val restored =
            ui.onNodeWithContentDescription("Motion 06").fetchSemanticsNode().boundsInRoot
        assertEquals(
            original.translate(-gridBoundsBefore.left, -gridBoundsBefore.top),
            restored.translate(-gridAfter.boundsInRoot.left, -gridAfter.boundsInRoot.top),
        )
        assertTrue(graph.settings.settings.value.reduceMotion)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(graph.context.getExternalFilesDir(null), "motion-library.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
