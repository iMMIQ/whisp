// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImportIntegrationTest {
    @get:Rule(order = 0)
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun importsThroughSystemDirectoryPickerAndPersistsReadGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/WhispFixture")
        device.executeShellCommand(
            "cp -r /data/local/tmp/whisp-fixture/. /sdcard/Download/WhispFixture/",
        )
        ui.onNodeWithText("来源", useUnmergedTree = true).performClick()
        ui.onNodeWithContentDescription("添加来源").performClick()
        ui.onNodeWithText("本地目录").performClick()
        device.wait(Until.findObject(By.text("Download")), 10000)?.click()
            ?: error("Download folder not shown in directory picker")
        device.wait(Until.findObject(By.text("WhispFixture")), 10000)?.click()
            ?: error("Fixture folder not shown")
        device.wait(Until.findObject(By.text("USE THIS FOLDER")), 10000)?.click()
            ?: error("Directory permission button not shown")
        device.wait(Until.findObject(By.text("ALLOW")), 10000)?.click()
            ?: error("Directory confirmation not shown")
        val graph = (ui.activity.application as WhispApp).graph
        ui.waitUntil(30000) {
            graph.library.sourceList.value.any { it.kind == "local" && it.lastScan > 0 } &&
                graph.sync.busy.value.isEmpty()
        }
        val source = graph.library.sourceList.value.first { it.kind == "local" }
        assertNull(source.error)
        assertTrue(
            graph.context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == source.root && it.isReadPermission
            },
        )
        runBlocking {
            val work = graph.library.works.value.first { it.rj == "RJ00000001" }
            val detail = graph.library.dao.details(work.id)!!
            val bytes =
                graph.context.contentResolver
                    .openInputStream(detail.tracks.single().uri.toUri())!!
                    .use { stream ->
                        ByteArray(10).also { assertTrue(stream.read(it) > 0) }
                    }
            assertEquals(10, bytes.size)
            assertEquals(1, graph.library.works.value.count { it.rj == "RJ00000001" })
        }
    }
}
