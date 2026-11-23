// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DlsiteLibraryIntegrationTest {
    @get:Rule(order = 0)
    val notifications =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun purchasedCatalogSearchOpeningDeletionAndAccountRemovalShareTheLibrary() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val source = "dlsite-catalog-test"
        val examples =
            listOf(
                DlsiteOwnedWork("RJ00000001", "雨夜的耳语", "示例声优甲", "夜色社团", purchasedAt = 300),
                DlsiteOwnedWork("RJ00000002", "海边夜话", "示例声优乙", "海风社团", purchasedAt = 200),
                DlsiteOwnedWork("RJ00000003", "午后的约定", "示例声优丙", "日光社团", purchasedAt = 100),
            )
        runBlocking {
            graph.library.dao.putSource(
                SourceEntity(
                    source,
                    "DLsite",
                    "dlsite",
                    "https://play.dlsite.com/",
                    secret = graph.secrets.encrypt("[]"),
                    lastScan = System.currentTimeMillis(),
                ),
            )
            graph.library.dao.putDlsitePurchases(
                examples.map {
                    DlsitePurchaseEntity(source, it.workno, it.purchasedAt, it.encode())
                },
            )
            examples.forEach { example ->
                val work = example.entity(source)
                val track =
                    TrackEntity(
                        stableId("${work.id}|sample"),
                        work.id,
                        "示例曲目",
                        "01.mp3",
                        "本編/01.mp3",
                        1,
                        60000,
                        uri = "dlsite-resource://${example.workno}/optimized/example.mp3",
                        size = 100,
                    )
                graph.library.dao.putWorks(listOf(work))
                graph.library.dao.putTracks(listOf(track))
                graph.library.saveDlsiteDetails(
                    work.id,
                    DlsiteDetails(example.workno, productFetchedAt = System.currentTimeMillis()),
                )
            }
        }
        ui.onNodeWithText("来源").performClick()
        ui.onNodeWithText("DLsite").performClick()
        ui.onNodeWithTag("dlsite-library").assertExists()
        ui.onNodeWithText("雨夜的耳语").assertExists()
        ui.onNodeWithTag("dlsite-search").performTextInput("海风")
        ui.onNodeWithText("海边夜话").assertExists()
        ui.onNodeWithText("雨夜的耳语").assertDoesNotExist()
        ui.onNodeWithTag("dlsite-search").performTextClearance()
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(graph.context.getExternalFilesDir(null), "dlsite-owned-library.png")
                .outputStream()
                .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        ui.onNodeWithText("雨夜的耳语").performClick()
        ui.waitUntil(15_000) {
            ui.onAllNodesWithContentDescription("作品信息").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("作品信息").performClick()
        ui.onNodeWithText("删除作品").performClick()
        ui.onNodeWithText("删除").performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithTag("dlsite-library").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { assertNull(graph.library.dao.work(examples.first().entity(source).id)) }
        ui.onNodeWithTag("dlsite-search").performTextInput("示例声优乙")
        ui.onNodeWithText("海边夜话").assertExists()
        ui.onNodeWithContentDescription("管理 DLsite 账号").performClick()
        ui.onNodeWithText("退出账号").performClick()
        ui.waitUntil(10_000) { runBlocking { graph.library.dao.source(source) == null } }
        runBlocking { assertTrue(graph.library.dlsitePurchases(source).first().isEmpty()) }
    }

    @Test
    fun diagnosticExportContainsOnlyFixedRequestFieldsAndStaysBounded() {
        val graph = (ui.activity.application as WhispApp).graph
        graph.dlsiteDiagnostics.clear()
        repeat(80) {
            graph.dlsiteDiagnostics.record(DlsiteRequestEvent(DlsitePhase.MEDIA, 206, it.toLong()))
        }
        val intent = graph.dlsiteDiagnostics.shareIntent()
        val uri =
            androidx.core.content.IntentCompat.getParcelableExtra(
                intent,
                android.content.Intent.EXTRA_STREAM,
                android.net.Uri::class.java,
            )!!
        val report =
            graph.context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                it.readText()
            }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(report)
        val fields = (json as kotlinx.serialization.json.JsonObject)
        val requests = fields["requests"] as kotlinx.serialization.json.JsonArray
        assertEquals(64, requests.size)
        assertEquals(
            setOf("phase", "status", "elapsedMs"),
            (requests.first() as kotlinx.serialization.json.JsonObject).keys,
        )
        assertFalse(report.contains("http"))
        assertFalse(report.contains("Cookie"))
        assertFalse(report.contains("RJ"))
        graph.dlsiteDiagnostics.clear()
    }
}
