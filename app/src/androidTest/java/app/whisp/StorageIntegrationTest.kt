// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import coil3.request.ImageRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class StorageIntegrationTest {
    @Test
    fun coilCacheClearRecoveryAndDurableDataPreservation() = runBlocking {
        val graph =
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                    as WhispApp)
                .graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val random = Random(18)
        val pixels = IntArray(64 * 64) { 0xff000000.toInt() or random.nextInt(0xffffff) }
        bitmap.setPixels(pixels, 0, 64, 0, 0, 64, 64)
        val original =
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        bitmap.recycle()
        val sourceFile =
            File(graph.context.filesDir, "test-original.png").apply { writeBytes(original) }
        val customId = stableId("custom-work|custom-cover")
        val customFile =
            File(graph.context.filesDir, "custom-artwork/$customId").apply {
                parentFile!!.mkdirs()
                writeBytes(original)
            }
        val offline =
            File(graph.context.filesDir, "offline/assets/pinned").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(11, 12))
            }
        val unknown = File(graph.context.cacheDir, "unrelated").apply { writeText("keep") }
        graph.library.dao.putSource(
            SourceEntity(
                "local-source",
                "Storage",
                "local",
                Uri.fromFile(sourceFile).toString(),
                secret = "unchanged",
            ),
        )
        graph.library.dao.putWorks(
            listOf(
                WorkEntity(
                    "remote-work",
                    null,
                    "Storage remote",
                    "Storage remote",
                    coverId = "remote-cover",
                    favorite = true,
                    offlineAttachments = true,
                    sourceId = "local-source",
                ),
                WorkEntity(
                    "custom-work",
                    null,
                    "Storage custom",
                    "Storage custom",
                    coverId = customId,
                    offlineAttachments = true,
                    sourceId = "local-source",
                ),
            ),
        )
        graph.library.dao.putAttachments(
            listOf(
                AttachmentEntity(
                    "remote-cover",
                    "remote-work",
                    "Cover",
                    "cover.png",
                    "cover.png",
                    "image",
                    uri = Uri.fromFile(sourceFile).toString(),
                    size = original.size.toLong(),
                ),
            ),
        )
        graph.library.dao.saveProgress(ProgressEntity("remote-work", "track", 12345))
        graph.library.dao.saveBookmark(
            BookmarkEntity("bookmark", "remote-work", "track", 54321, "keep"),
        )

        graph.images.imageLoader.execute(
            ImageRequest.Builder(graph.context).data(WhispImage.Asset("remote-cover")).build(),
        )
        assertTrue(graph.images.storageUsage().images > 0)

        graph.images.clearCaches(graph.media::clearStreamingCache)
        assertEquals(0, graph.images.storageUsage().cache)
        assertArrayEquals(original, customFile.readBytes())
        assertArrayEquals(byteArrayOf(11, 12), offline.readBytes())
        assertEquals("keep", unknown.readText())
        assertEquals("unchanged", graph.library.dao.source("local-source")!!.secret)
        assertTrue(graph.library.dao.work("remote-work")!!.favorite)
        assertEquals(12345, graph.library.dao.latestProgress()!!.positionMs)
        assertEquals(1, graph.library.bookmarks.value.size)

        fun put(cache: SimpleCache, key: String, bytes: ByteArray) {
            val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
            try {
                val f = cache.startFile(key, 0, bytes.size.toLong())
                f.writeBytes(bytes)
                cache.commitFile(f, bytes.size.toLong())
            } finally {
                cache.releaseHoleSpan(hole)
            }
        }
        put(graph.media.downloadCache, "pinned-audio", ByteArray(100))
        graph.images.clearCaches(graph.media::clearStreamingCache)
        assertEquals(100, graph.media.downloadCache.cacheSpace)

        val evictor = AdjustableCacheEvictor(250)
        val cache =
            SimpleCache(
                File(graph.context.cacheDir, "lru-test"),
                evictor,
                StandaloneDatabaseProvider(graph.context),
            )
        try {
            put(cache, "a", ByteArray(100))
            put(cache, "b", ByteArray(100))
            Thread.sleep(5)
            cache.startReadWrite("a", 0, 100)
            put(cache, "c", ByteArray(100))
            assertTrue(cache.isCached("a", 0, 100))
            assertFalse(cache.isCached("b", 0, 100))
            evictor.resize(cache, 100)
            assertEquals(100, cache.cacheSpace)
            evictor.resize(cache, 64)
            assertEquals(0, cache.cacheSpace)
        } finally {
            cache.release()
        }
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
class StorageUiIntegrationTest {
    @get:Rule(order = 0)
    val permission =
        androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    @Test
    fun storageControlsPersistAcrossActivityRecreation() {
        val graph = (ui.activity.application as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val device =
            androidx.test.uiautomator.UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation(),
            )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ui.activity
                .getSystemService(android.app.ActivityManager::class.java)
                .appTasks
                .first { it.taskInfo?.taskId == ui.activity.taskId }
                .moveToFront()
        }
        assertTrue(
            device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.pkg(graph.context.packageName),
                ),
                5000,
            ),
        )
        ui.onNodeWithContentDescription("来源").performClick()
        ui.onNodeWithContentDescription("存储管理").performClick()
        ui.onNodeWithTag("storage-page").assertIsDisplayed()
        runBlocking { graph.images.putCachedBytes("storage-ui-preview", ByteArray(21 * 1024)) }
        ui.waitUntil(5000) {
            ui.mainClock.advanceTimeBy(400)
            ui.onAllNodesWithText("21 KiB").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithTag("cache-limit-64").performClick()
        ui.waitUntil(5000) { graph.settings.settings.value.streamingCacheMiB == 64 }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("cache-limit-64").assertIsSelected()
        ui.onNodeWithTag("clear-cache").performClick()
        ui.onNodeWithText("自定义封面").assertExists()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(graph.context.getExternalFilesDir(null), "storage.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }
}
