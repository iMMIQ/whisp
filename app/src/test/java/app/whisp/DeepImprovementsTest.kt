// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.paging.PagingSource
import androidx.room.Room
import app.whisp.data.*
import app.whisp.playback.WhispAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class DeepImprovementsTest {
    private fun pcm16Buffer(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach(::putShort)
            flip()
        }

    @Test
    fun subtitleTimelineParsesAssAndStripsOverrideAndDrawingTags() {
        val raw =
            """
            [Script Info]
            Title: Sample ASS

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Comment: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,Ignored comment
            Dialogue: 0,0:00:01.50,0:00:04.25,Default,,0,0,0,,{\pos(320,240)\i1}轻声细语\N第二行\h空格
            Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\p1}m 0 0 l 100 100
            Dialogue: 0,0:00:06.00,0:00:08.00,Default,,0,0,0,,你好, 世界
            """
                .trimIndent()
        val cues = SubtitleTimeline.parse(raw, "ass")
        assertEquals(2, cues.size)
        assertEquals(Cue(1_500L, 4_250L, "轻声细语\n第二行 空格"), cues[0])
        assertEquals(Cue(6_000L, 8_000L, "你好, 世界"), cues[1])
    }

    @Test
    fun libraryIndexerRecognizesVjBjCodesAndPicksAutoBilingualSubtitles() {
        val indexed =
            LibraryIndexer.index(
                listOf(
                    FileEntry("VJ012345 商业音声/WAV/01_开场.wav", "content://1", false, 500_000),
                    FileEntry("VJ012345 商业音声/MP3/01_开场.mp3", "content://2", false, 50_000),
                    FileEntry("VJ012345 商业音声/字幕/01_开场.zh.ass", "content://3", false, 1_000),
                    FileEntry("VJ012345 商业音声/字幕/01_开场.ja.vtt", "content://4", false, 1_000),
                ),
                "nas",
            )
        assertEquals(1, indexed.works.size)
        assertEquals("VJ012345", indexed.works.single().rj)
        assertEquals(2, indexed.attachments.count { it.kind == "subtitle" })

        val mp3Track = indexed.tracks.first { it.fileName.endsWith(".mp3") }
        val (primary, secondary) =
            LibraryIndexer.pickAutoSubtitles(
                track = mp3Track,
                attachments = indexed.attachments,
                primaryLang = SubtitleLang.ZH,
                secondaryLang = SubtitleLang.JA,
            )
        assertNotNull(primary)
        assertNotNull(secondary)
        assertNotEquals(primary, secondary)
        assertTrue(indexed.attachments.first { it.id == primary }.fileName.endsWith(".zh.ass"))
        assertTrue(indexed.attachments.first { it.id == secondary }.fileName.endsWith(".ja.vtt"))

        // TrackSections respects EditionPreference (MP3 vs lossless).
        val mp3First = TrackSections.groups(indexed.tracks, EditionPreference.COMPACT_MP3)
        assertEquals("MP3", mp3First.first().title)
        val losslessFirst = TrackSections.groups(indexed.tracks, EditionPreference.LOSSLESS_FIRST)
        assertEquals("WAV", losslessFirst.first().title)
    }

    @Test
    fun whispAudioProcessorAppliesBalanceWorkGainHighPassAndNightTiers() {
        val processor = WhispAudioProcessor()
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        // Stereo balance + per-work gain (+6 dB ~ 2x).
        processor.stereoBalance = -0.5f
        processor.workGainLinear = WhispAudioProcessor.dbToLinear(6.02f)
        processor.queueInput(pcm16Buffer(4000, 4000))
        val balOut = processor.output
        val left = balOut.short.toInt()
        val right = balOut.short.toInt()
        assertTrue("Left should be ~8000, got $left", abs(left - 8000) < 100)
        assertTrue("Right should be ~4000, got $right", abs(right - 4000) < 100)

        // 80 Hz high-pass filter attenuates DC offset.
        processor.stereoBalance = 0f
        processor.workGainLinear = 1f
        processor.highPassFilter = true
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val dc = ShortArray(1000) { 10000 }
        processor.queueInput(pcm16Buffer(*dc))
        val hpOut = processor.output
        var tail = 0
        while (hpOut.remaining() >= 2) tail = abs(hpOut.short.toInt())
        assertTrue("DC should decay below 600, got $tail", tail < 600)

        // Strong night tier compresses louder than standard tier.
        processor.highPassFilter = false
        processor.nightDynamics = true
        processor.nightDynamicsStrength = 2
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val burst = ShortArray(400) { 32000 }
        processor.queueInput(pcm16Buffer(*burst))
        var stdTail = 0
        val outStd = processor.output
        while (outStd.remaining() >= 2) stdTail = abs(outStd.short.toInt())

        processor.nightDynamicsStrength = 3
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(pcm16Buffer(*burst))
        var strongTail = 0
        val outStrong = processor.output
        while (outStrong.remaining() >= 2) strongTail = abs(outStrong.short.toInt())

        assertTrue(
            "Strong tier ($strongTail) should be quieter than standard ($stdTail)",
            strongTail < stdTail,
        )
    }

    @Test
    fun sqlNativeSearchMultiDimFiltersPlaylistsAndBackupRoundtripWork() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dao = db.library()
            dao.putSource(SourceEntity("s1", "NAS", "smb", "smb://nas.example/audio/"))
            dao.putWorks(
                listOf(
                    WorkEntity(
                        id = "w1",
                        rj = "RJ100001",
                        title = "雨夜耳语",
                        originalTitle = "雨夜耳语",
                        artist = "花泽",
                        originalArtist = "花泽",
                        sourceId = "s1",
                        searchMetadata = "星月社团 掏耳 催眠",
                        userTags = "助眠, 最爱",
                        volumeOffsetDb = 3f,
                    ),
                    WorkEntity(
                        id = "w2",
                        rj = "VJ200002",
                        title = "午后咖啡馆",
                        originalTitle = "午后咖啡馆",
                        artist = "悠木",
                        originalArtist = "悠木",
                        sourceId = "s1",
                        searchMetadata = "白昼社团 日常",
                    ),
                ),
            )
            dao.putTracks(
                listOf(
                    TrackEntity(
                        id = "t1",
                        workId = "w1",
                        title = "序幕",
                        fileName = "01.flac",
                        relativePath = "01.flac",
                        order = 1,
                        durationMs = 60_000L,
                        uri = "content://t1",
                    ),
                    TrackEntity(
                        id = "t2",
                        workId = "w2",
                        title = "开场",
                        fileName = "01.mp3",
                        relativePath = "01.mp3",
                        order = 1,
                        durationMs = 60_000L,
                        uri = "content://t2",
                    ),
                ),
            )
            dao.putAttachments(
                listOf(
                    AttachmentEntity(
                        id = "a1",
                        workId = "w1",
                        title = "01",
                        fileName = "01.zh.ass",
                        relativePath = "01.zh.ass",
                        kind = "subtitle",
                        uri = "content://a1",
                    ),
                ),
            )
            // Mark w1 finished, w2 unplayed.
            dao.saveProgressIfValid("w1", "t1", 60_000L, 1000L)

            suspend fun loadIds(
                likePattern: String? = null,
                tagFilter: String? = null,
                hasSubtitlesOnly: Boolean = false,
                listenFilter: String = ListenFilter.ALL,
            ): List<String> {
                val page =
                    dao.pagingWorks(
                            hasOrphanOffline = false,
                            orphanOfflineIds = listOf(""),
                            favoriteOnly = false,
                            offlineOnly = false,
                            series = null,
                            tagFilter = tagFilter,
                            hasSubtitlesOnly = hasSubtitlesOnly,
                            listenFilter = listenFilter,
                            likePattern = likePattern,
                            sort = LibrarySort.ADDED_DESC,
                        )
                        .load(PagingSource.LoadParams.Refresh(null, 20, false))
                        as PagingSource.LoadResult.Page
                return page.data.map { it.id }
            }

            // CJK search matches DLsite metadata & user tags directly in SQLite.
            assertEquals(listOf("w1"), loadIds(likePattern = "%星月社团%"))
            assertEquals(listOf("w1"), loadIds(tagFilter = "%助眠%"))
            assertEquals(listOf("w1"), loadIds(hasSubtitlesOnly = true))
            assertEquals(listOf("w1"), loadIds(listenFilter = ListenFilter.FINISHED))
            assertEquals(listOf("w2"), loadIds(listenFilter = ListenFilter.UNPLAYED))

            // Playlists persist and summarize cleanly.
            dao.putPlaylist(PlaylistEntity("pl1", "睡前合集", 100L, 200L))
            dao.putPlaylistItems(
                listOf(
                    PlaylistItemEntity(
                        id = "pi1",
                        playlistId = "pl1",
                        workId = "w1",
                        trackId = "t1",
                        position = 0,
                        startMs = 5_000L,
                        endMs = 20_000L,
                        label = "温柔耳语段",
                    ),
                ),
            )
            val summaries = dao.observePlaylistSummaries().first()
            assertEquals(1, summaries.size)
            assertEquals("睡前合集", summaries.single().name)
            assertEquals(1, summaries.single().itemCount)

            // Persisted sleep anchor roundtrip via SettingsStore.
            val settingsStore = SettingsStore(context, scope)
            val anchor = PersistedSleepAnchor("w1", "t1", 12_345L, 999L)
            settingsStore.saveSleepStartAnchor(anchor)
            assertEquals(anchor, settingsStore.sleepStartAnchor.first { it != null })

            // Backup encode/decode preserves playlists, tags, volumeOffsetDb & sleepAnchor.
            val snapshot =
                WhispBackupSnapshot(
                    works =
                        listOf(
                            BackupWorkCustomization(
                                workId = "w1",
                                rj = "RJ100001",
                                title = "雨夜耳语",
                                originalTitle = "雨夜耳语",
                                artist = "花泽",
                                originalArtist = "花泽",
                                favorite = true,
                                userTags = "助眠",
                                volumeOffsetDb = 3f,
                            ),
                        ),
                    sleepStartAnchor = anchor,
                    playlists =
                        listOf(
                            BackupPlaylist(
                                id = "pl1",
                                name = "睡前合集",
                                items =
                                    listOf(
                                        BackupPlaylistItem(
                                            id = "pi1",
                                            workId = "w1",
                                            rj = "RJ100001",
                                            trackId = "t1",
                                            trackRelativePath = "01.flac",
                                            position = 0,
                                            startMs = 5_000L,
                                            endMs = 20_000L,
                                            label = "温柔耳语段",
                                        ),
                                    ),
                            ),
                        ),
                )
            val roundtripped = LibraryBackup.decode(LibraryBackup.encode(snapshot))
            assertEquals(anchor, roundtripped.sleepStartAnchor)
            assertEquals(3f, roundtripped.works.single().volumeOffsetDb, 0.001f)
            assertEquals("助眠", roundtripped.works.single().userTags)
            assertEquals(1, roundtripped.playlists.size)
            assertEquals("温柔耳语段", roundtripped.playlists.single().items.single().label)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun asmrOneClientSendsBearerTokenToCustomKikoeruServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"works":[],"pagination":{"totalCount":0}}""")
                .build(),
        )
        server.start()
        try {
            val client =
                AsmrOneClient(
                    baseHttp = OkHttpClient(),
                    root = server.url("/"),
                    bearerToken = "kikoeru-secret-token",
                )
            val page = client.catalog(page = 1)
            assertTrue(page.works.isEmpty())
            val recorded = server.takeRequest()
            assertEquals("Bearer kikoeru-secret-token", recorded.headers["Authorization"])
        } finally {
            server.close()
        }
    }
}
