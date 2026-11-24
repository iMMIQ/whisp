// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.whisp.data.*
import app.whisp.playback.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackExperienceOptimizationTest {
    private fun pcm16Buffer(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach(::putShort)
            flip()
        }

    @Test
    fun whispAudioProcessorSwapsChannelsDownmixesMonoAndSoftensLoudPeaks() {
        val processor = WhispAudioProcessor()
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        // 1. Passthrough leaves samples bit-exact.
        processor.queueInput(pcm16Buffer(1000, -2000, 3000, -4000))
        val pass = processor.output
        assertEquals(1000.toShort(), pass.short)
        assertEquals((-2000).toShort(), pass.short)
        assertEquals(3000.toShort(), pass.short)
        assertEquals((-4000).toShort(), pass.short)

        // 2. Stereo channel swap reverses L and R.
        processor.swapChannels = true
        processor.queueInput(pcm16Buffer(1200, -3400))
        val swapped = processor.output
        assertEquals((-3400).toShort(), swapped.short)
        assertEquals(1200.toShort(), swapped.short)

        // 3. Mono downmix averages both channels equally.
        processor.swapChannels = false
        processor.monoAudio = true
        processor.queueInput(pcm16Buffer(2000, -600))
        val mono = processor.output
        val mL = mono.short
        val mR = mono.short
        assertEquals(mL, mR)
        assertTrue("Expected mono average ~700, got $mL", abs(mL - 700) <= 2)

        // 4. Night dynamics lifts quiet whispers while restraining full-scale sudden peaks.
        processor.monoAudio = false
        processor.nightDynamics = true
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm16Buffer(1000, 1000))
        val whisperOut = processor.output.short.toInt()
        assertTrue("Whisper should be gently boosted (>1200), got $whisperOut", whisperOut > 1200)

        // Feed a burst of loud full-scale samples so envelope attacks quickly and compresses.
        val loudSamples = ShortArray(200) { 32000 }
        processor.queueInput(pcm16Buffer(*loudSamples))
        val loudOut = processor.output
        var lastSample = 0
        while (loudOut.remaining() >= 2) {
            lastSample = abs(loudOut.short.toInt())
        }
        assertTrue("Loud peaks should be restrained (<29000), got $lastSample", lastSample < 29000)
    }

    @Test
    fun audioOffloadDisablesWhenDspEffectsActiveAndSleepFadeUsesQuadraticCurve() {
        assertTrue(AudioOffload.needsPreciseDeadline(-1, -1, 0, dspEffectsActive = true))
        assertFalse(AudioOffload.needsPreciseDeadline(-1, -1, 0, dspEffectsActive = false))
        assertEquals(0f, SleepTimerController.perceptualFadeFactor(0f), 0.0001f)
        assertEquals(0.25f, SleepTimerController.perceptualFadeFactor(0.5f), 0.0001f)
        assertEquals(1f, SleepTimerController.perceptualFadeFactor(1f), 0.0001f)
    }

    @Test
    fun playbackErrorFormatterClassifiesCommonFailureModes() {
        val dlsiteError =
            PlaybackException(
                "expired",
                DlsiteLoginRequired(),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val dlsiteInfo = PlaybackErrorFormatter.describe(dlsiteError, "whisp-dlsite")
        assertTrue(dlsiteInfo.requiresDlsiteLogin)
        assertFalse(dlsiteInfo.canRetry)

        val asmrHttp403 =
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                null,
                emptyMap(),
                DataSpec(android.net.Uri.parse("https://asmr.one/test.mp3")),
                ByteArray(0),
            )
        val asmrInfo =
            PlaybackErrorFormatter.describe(
                PlaybackException(
                    "403",
                    asmrHttp403,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                ),
                "whisp-asmr",
            )
        assertTrue(asmrInfo.canRefreshSource)
        assertTrue(asmrInfo.canRetry)

        val codecError =
            PlaybackException("decode", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        val codecInfo = PlaybackErrorFormatter.describe(codecError, "file")
        assertFalse(codecInfo.canRetry)
        assertTrue(codecInfo.message.contains("编码"))
    }

    @Test
    fun subtitleTimelineHandlesLrcClearMarkersEnhancedTagsNestedOverlapsAndBilingualMerge() {
        // Empty timestamp line ends previous cue cleanly instead of keeping it highlighted in
        // silence.
        val lrc =
            SubtitleTimeline.lrc(
                "[00:01.00]<00:01.20>轻声<00:01.80>细语\n[00:03.00]\n[00:10.00]下一段",
            )
        assertEquals(2, lrc.size)
        assertEquals(Cue(1000L, 3000L, "轻声细语"), lrc[0])
        assertEquals(-1, SubtitleTimeline.current(lrc, 5000L))
        assertEquals(1, SubtitleTimeline.current(lrc, 11000L))

        // Nested overlap: a shorter inner cue ends while the enclosing longer cue is still active.
        val nested =
            listOf(
                Cue(1_000L, 10_000L, "Long line"),
                Cue(3_000L, 5_000L, "Short gasp"),
            )
        assertEquals(1, SubtitleTimeline.current(nested, 4_000L))
        assertEquals(0, SubtitleTimeline.current(nested, 6_000L))

        // Bilingual merge aligns secondary language text onto primary cue timeline.
        val ja = listOf(Cue(1_000L, 4_000L, "おやすみ"), Cue(6_000L, 9_000L, "また明日"))
        val zh = listOf(Cue(1_100L, 3_900L, "晚安"), Cue(6_200L, 8_800L, "明天见"))
        val bilingual = SubtitleTimeline.mergeBilingual(ja, zh)
        assertEquals(2, bilingual.size)
        assertEquals("おやすみ", bilingual[0].text)
        assertEquals("晚安", bilingual[0].secondaryText)
        assertEquals("また明日", bilingual[1].text)
        assertEquals("明天见", bilingual[1].secondaryText)
    }

    @Test
    fun libraryIndexerMatchesLanguageSuffixedSubtitlesAndListsCandidates() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    FileEntry("RJ00000010 Demo/01_耳かき.wav", "content://1", false, 100),
                    FileEntry("RJ00000010 Demo/01_耳かき.zh.vtt", "content://2", false, 50),
                    FileEntry("RJ00000010 Demo/01_耳かき.ja.srt", "content://3", false, 50),
                    FileEntry("RJ00000010 Demo/02_结束.wav", "content://4", false, 100),
                ),
                "local",
            )
        val track1 = indexed.tracks.first { it.order == 1 }
        assertNotNull("Language-suffixed subtitle should match track", track1.subtitleId)
        val candidates = LibraryIndexer.subtitleCandidates(track1, indexed.attachments)
        assertEquals(2, candidates.size)
        assertEquals(track1.subtitleId, candidates.first().id)
    }

    @Test
    fun nfsClientRetriesReadOnBrokenConnectionAndDetectsFileLengthChange() {
        val source = SourceEntity("nfs-retry", "NAS", "nfs", "nfs://nas.example/media")
        val uri = NfsAddress.assetUri(source.id, "01.wav")
        var sessionCount = 0
        var failFirstRead = true
        var secondLength = 4L
        val client =
            NfsClient(
                connect = {
                    val id = ++sessionCount
                    object : NfsSession {
                        override fun list(path: String) = emptyList<NfsEntry>()

                        override fun open(path: String) =
                            object : RemoteReadHandle {
                                override val length: Long = if (id == 1) 4L else secondLength

                                override fun read(
                                    buffer: ByteArray,
                                    offset: Long,
                                    bufferOffset: Int,
                                    count: Int,
                                ): Int {
                                    if (id == 1 && failFirstRead) {
                                        failFirstRead = false
                                        throw IOException("transient reset")
                                    }
                                    val data = byteArrayOf(7, 8, 9, 10)
                                    val n = minOf(count, (4L - offset).toInt())
                                    System.arraycopy(data, offset.toInt(), buffer, bufferOffset, n)
                                    return n
                                }

                                override fun close() {}
                            }

                        override fun close() {}
                    }
                },
                poolIdleTimeoutMs = 30_000L,
            )

        assertArrayEquals(byteArrayOf(7, 8, 9, 10), client.bytes(source, uri, 1024))
        assertEquals(2, sessionCount)
        client.closeIdle()

        // Length mismatch on reconnect fails clearly instead of corrupting the stream.
        sessionCount = 0
        failFirstRead = true
        secondLength = 8L
        val handle = client.open(source, uri)
        try {
            handle.read(ByteArray(4), 0L, 0, 4)
            fail("Expected length mismatch IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("文件已改变") == true)
        } finally {
            handle.close()
        }
    }

    @Test
    fun settingsStorePersistsPerTrackSubtitleOffsetSelectionAndPosition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsStore(RuntimeEnvironment.getApplication(), scope)
            store.setTrackSubtitleOffset("track-a", 1250L)
            store.setTrackSubtitleOffset("track-b", -500L)
            store.setTrackSubtitleSelection("track-a", TrackSubtitleSelection("sub-zh", "sub-ja"))
            store.rememberTrackPosition("track-a", 42_000L)

            val offsets = store.trackSubtitleOffsets.first { it["track-a"] == 1250L }
            assertEquals(1250L, offsets["track-a"])
            assertEquals(-500L, offsets["track-b"])

            val selections = store.trackSubtitleSelections.first { it["track-a"] != null }
            assertEquals("sub-zh", selections["track-a"]?.primaryId)
            assertEquals("sub-ja", selections["track-a"]?.secondaryId)

            val positions = store.trackPositions.first { it["track-a"] == 42_000L }
            assertEquals(42_000L, positions["track-a"])

            // Normalization clamps audio parameters cleanly.
            val normalized = Settings(volumeGain = 0.01f, sleepFadeSeconds = 999).normalized()
            assertEquals(0.15f, normalized.volumeGain, 0.001f)
            assertEquals(15, normalized.sleepFadeSeconds)
        } finally {
            scope.cancel()
        }
    }
}
