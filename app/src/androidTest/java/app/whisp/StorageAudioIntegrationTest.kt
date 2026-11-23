// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production SMB/cache stack without competing with other apps' audio focus. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class StorageAudioIntegrationTest {
    @Test
    fun clearingCommittedAudioCachePreservesPlaybackPauseResumeAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = (instrumentation.targetContext.applicationContext as WhispApp).graph
        assertTrue(graph.context.packageName.endsWith(".smoketest"))
        val args = InstrumentationRegistry.getArguments()
        val path =
            String(
                Base64.getUrlDecoder().decode(requireNotNull(args.getString("assetPath64"))),
                Charsets.UTF_8,
            )
        val source =
            SourceEntity(
                "audio-cache-source",
                "Cache",
                "smb",
                SmbAddress.parse(requireNotNull(args.getString("smbRoot"))).normalized,
                requireNotNull(args.getString("smbUser")),
                graph.secrets.encrypt(requireNotNull(args.getString("smbPassword"))),
            )
        runBlocking {
            graph.library.dao.putSource(source)
            graph.images.storageUsage()
        }
        val uri = SmbAddress.assetUri(source.id, path)
        val key = "cache-audio-track"
        val data = graph.media.playbackFactory.createDataSource()
        var cached = 0L
        try {
            data.open(DataSpec.Builder().setUri(uri).setKey(key).setLength(MIB).build())
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = data.read(buffer, 0, buffer.size)
                if (n < 0) break
                cached += n
            }
            assertTrue(
                "Storage includes files still being written",
                runBlocking { graph.images.storageUsage().streaming } >= MIB / 2,
            )
        } finally {
            data.close()
        }
        assertEquals(MIB, cached)
        assertTrue(runBlocking { graph.images.storageUsage().streaming } >= MIB)
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player =
                ExoPlayer.Builder(graph.context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(graph.media.playbackFactory))
                    .build()
            player.setAudioAttributes(AudioAttributes.DEFAULT, false)
            player.volume = 0f
            player.setMediaItem(
                MediaItem.Builder().setUri(uri.toUri()).setCustomCacheKey(key).build(),
            )
            player.prepare()
            player.play()
        }
        fun waitFor(message: String, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < end) {
                var ready = false
                instrumentation.runOnMainSync { ready = condition() }
                if (ready) return
                Thread.sleep(100)
            }
            var diagnostic = ""
            instrumentation.runOnMainSync {
                diagnostic =
                    "state=${player.playbackState}, error=${player.playerError?.errorCodeName}, position=${player.currentPosition}"
            }
            fail("$message: $diagnostic")
        }
        try {
            waitFor("initial playback") { player.isPlaying && player.currentPosition > 0 }
            var before = 0L
            instrumentation.runOnMainSync { before = player.currentPosition }
            runBlocking {
                graph.images.clearCaches(graph.media::clearStreamingCache)
                graph.settings.update { it.copy(streamingCacheMiB = 64) }
            }
            waitFor("playback after clear") {
                player.isPlaying && player.currentPosition > before + 700
            }
            instrumentation.runOnMainSync { player.pause() }
            waitFor("pause") { !player.playWhenReady }
            var paused = 0L
            instrumentation.runOnMainSync { paused = player.currentPosition }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                assertTrue(
                    "Pause must stop advancement",
                    kotlin.math.abs(paused - player.currentPosition) <= 50,
                )
                player.seekTo(6000)
            }
            waitFor("seek after clear") {
                player.playbackState == Player.STATE_READY && player.currentPosition == 6000L
            }
            instrumentation.runOnMainSync { player.play() }
            waitFor("resume after seek") { player.isPlaying && player.currentPosition > 6500 }
            instrumentation.runOnMainSync {
                assertNull(player.playerError)
                assertEquals(0, player.currentMediaItemIndex)
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }
}
