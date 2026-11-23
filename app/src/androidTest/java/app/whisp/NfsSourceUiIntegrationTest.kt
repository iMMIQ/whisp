// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfsSourceUiIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun nfsDialogConnectsWithProtocolAndUnixIdentity() {
        val root = InstrumentationRegistry.getArguments().getString("nfsRoot")
        assumeTrue("Provide a dedicated synthetic NFS fixture", root != null)
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithContentDescription("添加来源").performClick()
        ui.onNodeWithText("NFS", useUnmergedTree = true).performClick()
        ui.onNodeWithText("UID").assertIsDisplayed()
        ui.onNodeWithText("GID").assertIsDisplayed()
        ui.onNodeWithText("密码").assertDoesNotExist()
        ui.onNodeWithText("名称").performTextInput("NFS 界面示例")
        ui.onNodeWithText("地址").performTextInput(root!!)
        ui.onNodeWithText("NFS 4.2").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("NFS 界面示例").assertIsDisplayed()
        ui.onNodeWithText(root).assertIsDisplayed()
        ui.onNodeWithText("连接").performClick()
        ui.waitUntil(30_000) { graph.library.sourceList.value.any { it.name == "NFS 界面示例" } }
        val source = graph.library.sourceList.value.single { it.name == "NFS 界面示例" }
        assertEquals("nfs", source.kind)
        assertTrue(source.root.contains("version=4.2"))
        assertTrue(source.secret.isEmpty())
        ui.onNodeWithText("NFS 界面示例").assertIsDisplayed()
        ui.waitUntil(30_000) { graph.library.works.value.isNotEmpty() }
        val work = graph.library.works.value.single()
        assertEquals(work.id, runBlocking { graph.library.dao.sourceWorkIds(source.id).single() })
        ui.onNodeWithText("作品").performClick()
        ui.onNodeWithContentDescription(work.title).performClick()
        ui.onNodeWithText("播放作品").performClick()
        ui.waitUntil(20_000) { ui.onNodeWithText("示例字幕").isDisplayed() }
        ui.onNodeWithContentDescription("暂停").performClick()
        runBlocking { graph.sync.removeSource(source.id) }
    }
}
