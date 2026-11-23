// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.whisp.data.SourceEntity
import app.whisp.data.WorkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceNavigationIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun repeatedStorageBackDuringExitKeepsSourcesRoot() {
        assertTrue(ui.activity.packageName.endsWith(".smoketest"))
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithContentDescription("存储管理").performClick()
        ui.onNodeWithTag("storage-page").assertIsDisplayed()
        val center =
            ui.onNodeWithContentDescription("返回来源").fetchSemanticsNode().boundsInRoot.center
        ui.mainClock.autoAdvance = false
        try {
            ui.onRoot().performTouchInput {
                click(center)
                click(center)
            }
        } finally {
            ui.mainClock.autoAdvance = true
        }
        ui.waitForIdle()
        ui.onNodeWithTag("storage-page").assertDoesNotExist()
        ui.onNodeWithContentDescription("存储管理").assertIsDisplayed()
        ui.onNodeWithText("添加作品").assertIsDisplayed()
    }

    @Test
    fun openSourceDialogTracksUpdatesAndSurvivesRecreation() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val source =
            SourceEntity("source-ui-probe", "示例来源", "local", "content://example/tree/audio")
        runBlocking { graph.library.dao.putSource(source) }
        ui.waitUntil(5000) { graph.library.sourceList.value.any { it.id == source.id } }
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithText("示例来源").performClick()
        runBlocking { graph.library.dao.putSource(source.copy(error = "示例扫描失败")) }
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("示例扫描失败").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("示例扫描失败").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ui.activity.recreate() }
        ui.onNodeWithText("示例扫描失败").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.onNodeWithText("示例扫描失败").assertDoesNotExist()
        runBlocking { graph.library.dao.deleteSource(source.id) }
    }

    @Test
    fun workEditsSurviveMetadataUpdatesAndRecreationAndPreserveOtherFields() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val work =
            WorkEntity(
                "work-editor-probe",
                null,
                "示例作品",
                "示例作品",
                artist = "示例声优",
                offlineAttachments = true,
            )
        runBlocking { graph.library.dao.putWorks(listOf(work)) }
        ui.waitUntil(5000) { graph.library.works.value.any { it.id == work.id } }
        ui.onNodeWithContentDescription("示例作品").performClick()
        ui.onNodeWithContentDescription("作品信息").performClick()
        ui.onNode(hasSetTextAction() and hasText("示例作品")).performTextReplacement("我的标题")
        ui.onNode(hasSetTextAction() and hasText("示例声优")).performTextReplacement("我的声优")
        val fresh =
            work.copy(
                title = "更新后的官方标题",
                artist = "更新后的官方声优",
                favorite = true,
                series = "最新系列",
                originalTitle = "更新后的原始标题",
            )
        runBlocking { graph.library.dao.putWorks(listOf(fresh)) }
        ui.waitUntil(5000) { graph.library.works.value.any { it == fresh } }
        ui.waitForIdle()
        ui.onNode(hasSetTextAction() and hasText("我的标题")).assertExists()
        ui.onNode(hasSetTextAction() and hasText("我的声优")).assertExists()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ui.activity.recreate() }
        ui.onNode(hasSetTextAction() and hasText("我的标题")).assertIsDisplayed()
        ui.onNode(hasSetTextAction() and hasText("我的声优")).assertIsDisplayed()
        ui.onNodeWithText("保存").performClick()
        ui.waitUntil(5000) { runBlocking { graph.library.dao.work(work.id)?.title == "我的标题" } }
        runBlocking {
            assertEquals(
                fresh.copy(title = "我的标题", artist = "我的声优"),
                graph.library.dao.work(work.id),
            )
            graph.library.dao.deleteWork(work.id)
        }
    }
}
