// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import app.whisp.playback.PlaybackService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class SystemMediaIntegrationTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private val graph
        get() = (context.applicationContext as WhispApp).graph

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun browser(): MediaBrowser = main {
        MediaBrowser.Builder(
                context,
                SessionToken(context, ComponentName(context, PlaybackService::class.java)),
            )
            .buildAsync()
    }
        .get(20, TimeUnit.SECONDS)

    private fun seed(remote: Boolean): List<TrackEntity> {
        assertTrue(context.packageName.endsWith(".smoketest"))
        val wave = silentWave(context.filesDir)
        val tracks =
            (1..2).map {
                val relativePath = "audio/$it.wav"
                TrackEntity(
                    "system-$it",
                    "system-work",
                    "Track $it",
                    "$it.wav",
                    relativePath,
                    it,
                    40000,
                    uri =
                        if (remote) SmbAddress.assetUri("system-source", relativePath)
                        else wave.toURI().toString(),
                    size = wave.length(),
                )
            }
        val cover =
            File(context.filesDir, "custom-artwork/system-cover").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.rgb(24, 228, 71))
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        runBlocking {
            graph.settings.update {
                it.copy(hideArtwork = false, hideRecents = false, touchFeedback = true)
            }
            graph.library.dao.putSource(
                SourceEntity(
                    "system-source",
                    "System",
                    if (remote) "smb" else "local",
                    if (remote) "smb://203.0.113.1/data/" else wave.parentFile!!.toURI().toString(),
                ),
            )
            graph.library.dao.putWorks(
                listOf(
                    WorkEntity(
                        "system-work",
                        null,
                        "System test",
                        "System test",
                        coverId = "system-cover",
                        sourceId = "system-source",
                    ),
                ),
            )
            graph.library.dao.putTracks(tracks)
            graph.library.dao.saveProgress(ProgressEntity("system-work", tracks[1].id, 12345))
        }
        return tracks
    }

    @Test
    fun media3AndLegacyBrowsersReadLocalPrivateResumeMetadataWithoutOpeningNas() {
        val tracks = seed(remote = true)
        val browser = browser()
        try {
            val params = MediaLibraryService.LibraryParams.Builder().setRecent(true).build()
            val root = main { browser.getLibraryRoot(params) }.get(10, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            assertTrue(root.value!!.mediaMetadata.isBrowsable == true)
            val children = main {
                browser.getChildren(root.value!!.mediaId, 0, 10, params)
            }
                .get(10, TimeUnit.SECONDS)
            assertEquals(
                "Recent browsing must succeed",
                LibraryResult.RESULT_SUCCESS,
                children.resultCode,
            )
            val item = children.value!!.single()
            assertEquals(tracks[1].id, item.mediaId)
            assertTrue(item.mediaMetadata.isPlayable == true)
            assertNotNull(item.mediaMetadata.artworkData)
            assertTrue(item.mediaMetadata.artworkData!!.size < 65536)
            assertNull("Browsing must not resolve an audio URI", item.localConfiguration)
            main {
                assertEquals(Player.STATE_IDLE, browser.playbackState)
                assertFalse(browser.playWhenReady)
                assertEquals(0, browser.mediaItemCount)
            }

            val connected = CompletableFuture<String>()
            lateinit var legacy: android.media.browse.MediaBrowser
            main {
                legacy =
                    android.media.browse.MediaBrowser(
                        context,
                        ComponentName(context, PlaybackService::class.java),
                        object : android.media.browse.MediaBrowser.ConnectionCallback() {
                            override fun onConnected() {
                                connected.complete(legacy.root)
                            }

                            override fun onConnectionFailed() {
                                connected.completeExceptionally(
                                    AssertionError("Legacy browser refused"),
                                )
                            }
                        },
                        Bundle().apply { putBoolean("android.service.media.extra.RECENT", true) },
                    )
                legacy.connect()
            }
            try {
                val rootId = connected.get(10, TimeUnit.SECONDS)
                val loaded = CompletableFuture<List<android.media.browse.MediaBrowser.MediaItem>>()
                val hiddenLegacy = CompletableFuture<android.media.browse.MediaBrowser.MediaItem>()
                main {
                    legacy.subscribe(
                        rootId,
                        object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                            override fun onChildrenLoaded(
                                parentId: String,
                                children: MutableList<android.media.browse.MediaBrowser.MediaItem>,
                            ) {
                                loaded.complete(children)
                                children
                                    .firstOrNull()
                                    ?.takeIf { it.description.title.toString() == "正在收听" }
                                    ?.let { hiddenLegacy.complete(it) }
                            }

                            override fun onError(parentId: String) {
                                loaded.completeExceptionally(
                                    AssertionError("Legacy children failed"),
                                )
                            }
                        },
                    )
                }
                val legacyItem = loaded.get(10, TimeUnit.SECONDS).single()
                assertTrue(legacyItem.isPlayable)
                assertEquals(tracks[1].id, legacyItem.mediaId)
                assertNotNull(
                    "SystemUI must receive readable artwork",
                    legacyItem.description.iconBitmap,
                )
                main { legacy.unsubscribe(rootId) }
                runBlocking { graph.settings.update { it.copy(hideArtwork = true) } }
                main {
                    legacy.subscribe(
                        rootId,
                        object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                            override fun onChildrenLoaded(
                                parentId: String,
                                children: MutableList<android.media.browse.MediaBrowser.MediaItem>,
                            ) {
                                children.firstOrNull()?.let { hiddenLegacy.complete(it) }
                            }

                            override fun onError(parentId: String) {
                                hiddenLegacy.completeExceptionally(
                                    AssertionError("Legacy private reload failed"),
                                )
                            }
                        },
                    )
                }
                val privateLegacy = hiddenLegacy.get(10, TimeUnit.SECONDS)
                assertEquals("正在收听", privateLegacy.description.title.toString())
                assertNull(
                    "Reloaded system cards must respect privacy",
                    privateLegacy.description.iconBitmap,
                )
            } finally {
                main { legacy.disconnect() }
            }

            runBlocking { graph.settings.update { it.copy(hideArtwork = true) } }
            val hidden =
                main { browser.getChildren(root.value!!.mediaId, 0, 10, params) }
                    .get(10, TimeUnit.SECONDS)
                    .value!!
                    .single()
            assertEquals("正在收听", hidden.mediaMetadata.title.toString())
            assertNull(hidden.mediaMetadata.artworkData)
            assertNull(hidden.mediaMetadata.artworkUri)
            assertNull(hidden.localConfiguration)
            val page = main {
                browser.getChildren(root.value!!.mediaId, 1, 10, params)
            }
                .get(10, TimeUnit.SECONDS)
            assertTrue(page.value!!.isEmpty())
        } finally {
            main { browser.release() }
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    @Test
    fun mediaButtonsRestoreQueueAndHeadlessPrivacyKeepsPositionAndLoop() {
        val tracks = seed(remote = false)
        Thread.sleep(200)
        @Suppress("DEPRECATION")
        val running =
            context
                .getSystemService(android.app.ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
        assertFalse(
            "The media button must start an absent playback service",
            running.any { it.service.className == PlaybackService::class.java.name },
        )
        context.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(
                    ComponentName(
                        context,
                        "androidx.media3.session.MediaButtonReceiver",
                    ),
                )
                .putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
                ),
        )
        Thread.sleep(200)
        val browser = browser()
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < end) {
                if (main(condition)) return
                Thread.sleep(100)
            }
            fail(message)
        }
        try {
            main { browser.volume = 0f }
            waitFor("Media button must restore the saved queue") {
                browser.mediaItemCount == 2 &&
                    browser.currentMediaItem?.mediaId == tracks[1].id &&
                    browser.playbackState == Player.STATE_READY
            }
            main { browser.pause() }
            waitFor("Saved position must be restored") { browser.currentPosition in 12345..20000 }
            main {
                browser.seekTo(14000)
                browser.sendCustomCommand(
                    SessionCommand(PlaybackService.LOOP, Bundle.EMPTY),
                    Bundle().apply {
                        putLong("start", 10000)
                        putLong("end", 30000)
                    },
                )
            }
            waitFor("Loop must be enabled") {
                browser.sessionExtras.getLong("loopStart", -1) == 10000L
            }
            runBlocking { graph.settings.update { it.copy(hideArtwork = true) } }
            waitFor("Privacy must update with no Activity") {
                browser.mediaMetadata.title.toString() == "正在收听"
            }
            main {
                assertEquals(14000L, browser.currentPosition)
                assertEquals(10000L, browser.sessionExtras.getLong("loopStart", -1))
                assertFalse(browser.playWhenReady)
                assertEquals(2, browser.mediaItemCount)
                assertNull(browser.mediaMetadata.artworkUri)
            }
            runBlocking { graph.settings.update { it.copy(hideArtwork = false) } }
            waitFor("Visible metadata must be restored") {
                browser.mediaMetadata.title.toString() == "Track 2"
            }
            main {
                browser.setMediaItem(MediaItem.Builder().setMediaId(tracks[0].id).build())
                browser.prepare()
            }
            waitFor("An ID-only item must resolve to indexed audio") {
                browser.currentMediaItem?.mediaId == tracks[0].id &&
                    browser.playbackState == Player.STATE_READY
            }
        } finally {
            main {
                browser.pause()
                browser.clearMediaItems()
                browser.release()
            }
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    companion object {
        /** Zero PCM samples guarantee silence, including media-button playback. */
        internal fun silentWave(directory: File, channels: Int = 1): File {
            val samples = 16000 * 40
            val bytes = samples * channels * 2
            val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer
                .put("RIFF".toByteArray())
                .putInt(36 + bytes)
                .put("WAVEfmt ".toByteArray())
                .putInt(16)
                .putShort(1)
                .putShort(channels.toShort())
                .putInt(16000)
                .putInt(16000 * channels * 2)
                .putShort((channels * 2).toShort())
                .putShort(16)
                .put("data".toByteArray())
                .putInt(bytes)
            return File(directory, "system-silence.wav").apply { writeBytes(buffer.array()) }
        }
    }
}
