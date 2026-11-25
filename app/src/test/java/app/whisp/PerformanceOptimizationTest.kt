// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.room.Room
import app.whisp.data.AttachmentEntity
import app.whisp.data.LibraryDatabase
import app.whisp.data.SourceEntity
import app.whisp.data.TrackEntity
import app.whisp.data.TrackSummary
import app.whisp.data.WorkEntity
import app.whisp.data.buildFtsQueryOrNull
import app.whisp.data.walkTreeConcurrent
import app.whisp.ui.chunkLongText
import app.whisp.ui.workProgressFraction
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PerformanceOptimizationTest {
    @Test
    fun chunkLongTextHandlesEmptyShortParagraphsAndLongSingleLines() {
        assertEquals(emptyList<String>(), chunkLongText(""))
        assertEquals(listOf("short text"), chunkLongText("short text", maxChunkChars = 100))

        val paragraphs = (1..20).joinToString("\n") { idx -> "Paragraph $idx: " + "a".repeat(30) }
        val chunks = chunkLongText(paragraphs, maxChunkChars = 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 120 })
        assertEquals(paragraphs, chunks.joinToString("\n"))

        val giantLine = "x".repeat(500)
        val splitGiant = chunkLongText(giantLine, maxChunkChars = 120)
        assertEquals(5, splitGiant.size)
        assertTrue(splitGiant.all { it.length <= 120 })
        assertEquals(giantLine, splitGiant.joinToString(""))
    }

    @Test
    fun walkTreeConcurrentVisitsAllBranchesAndCancelsOnError() = runBlocking {
        val tree =
            mapOf(
                "root" to listOf("a", "b", "c"),
                "a" to listOf("a1", "a2"),
                "b" to listOf("b1"),
                "c" to emptyList(),
                "a1" to listOf("a1x"),
            )
        val visited = mutableSetOf<String>()
        walkTreeConcurrent(
            root = "root",
            concurrency = 3,
            listOne = { _, node -> tree[node].orEmpty() },
            onDirectory = { dir, children, enqueue ->
                visited += dir
                children.forEach(enqueue)
            },
        )
        assertEquals(setOf("root", "a", "b", "c", "a1", "a2", "b1", "a1x"), visited)

        val cancelledSiblings = AtomicInteger(0)
        try {
            walkTreeConcurrent(
                root = "root",
                concurrency = 3,
                listOne = { _, node ->
                    when (node) {
                        "root" -> listOf("fail", "slow1", "slow2")
                        "fail" -> throw IOException("boom")
                        else -> {
                            try {
                                delay(5_000L)
                            } finally {
                                cancelledSiblings.incrementAndGet()
                            }
                            emptyList()
                        }
                    }
                },
                onDirectory = { _, children, enqueue -> children.forEach(enqueue) },
            )
            fail("Expected IOException")
        } catch (_: IOException) {}
        assertTrue(cancelledSiblings.get() >= 0)
    }

    @Test
    fun ftsSearchSqlProgressSummariesAndTrackStateCascadeWorkAsExpected() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.putSource(SourceEntity("s1", "Local", "local", "content://root"))
            dao.putWorks(
                listOf(
                    WorkEntity(
                        id = "w1",
                        rj = "RJ111111",
                        title = "Rainy Night",
                        originalTitle = "Rainy Night",
                        artist = "Voice A",
                        originalArtist = "Voice A",
                        sourceId = "s1",
                    ),
                    WorkEntity(
                        id = "w2",
                        rj = "RJ222222",
                        title = "Sunny Day",
                        originalTitle = "Sunny Day",
                        artist = "Voice B",
                        originalArtist = "Voice B",
                        sourceId = "s1",
                    ),
                ),
            )
            val tracks =
                listOf(
                    TrackEntity(
                        id = "t1",
                        workId = "w1",
                        title = "Whisper Intro",
                        fileName = "01_whisper.flac",
                        relativePath = "01.flac",
                        order = 1,
                        durationMs = 60_000L,
                        uri = "content://1",
                    ),
                    TrackEntity(
                        id = "t1b",
                        workId = "w1",
                        title = "Soft Rain",
                        fileName = "02_rain.flac",
                        relativePath = "02.flac",
                        order = 2,
                        durationMs = 60_000L,
                        uri = "content://1b",
                    ),
                    TrackEntity(
                        id = "t2",
                        workId = "w2",
                        title = "Whisper Bonus",
                        fileName = "02_rainy.flac",
                        relativePath = "02.flac",
                        order = 1,
                        durationMs = 2000L,
                        uri = "content://2",
                    ),
                )
            dao.putTracks(tracks)
            dao.putAttachments(
                listOf(
                    AttachmentEntity(
                        id = "a1",
                        workId = "w1",
                        title = "whisper_script.txt",
                        fileName = "whisper_script.txt",
                        relativePath = "whisper_script.txt",
                        kind = "script",
                        uri = "content://a1",
                    ),
                ),
            )

            // FTS4 inverted index query matches across works, tracks, and attachments.
            assertEquals("whisper*", buildFtsQueryOrNull("whisper"))
            assertNull(buildFtsQueryOrNull("耳かき"))
            assertEquals(listOf("w1", "w2"), dao.searchWorkIdsFts("whisper*").sorted())
            assertEquals(listOf("w1", "w2"), dao.searchWorkIdsFts("rainy*").sorted())
            assertEquals(listOf("w2"), dao.searchWorkIdsFts("sunny*"))
            assertEquals(listOf("w1", "w2"), dao.searchWorkIds("%whisper%").sorted())

            // saveProgressIfValid ignores unknown work or mismatched trackId.
            dao.saveProgressIfValid("missing", "t1", 500L, 100L)
            dao.saveProgressIfValid("w1", "t2", 500L, 100L)
            assertNull(dao.progress("w1"))

            // Valid work+track inserts and matches SQL-aggregated WorkProgressSummary.
            dao.saveProgressIfValid("w1", "t1b", 30_000L, 200L)
            val saved = dao.progress("w1")
            assertNotNull(saved)
            assertEquals("t1b", saved!!.trackId)
            assertEquals(30_000L, saved.positionMs)

            val summaries = dao.observeWorkProgressSummaries().first()
            assertEquals(1, summaries.size)
            val sqlFraction = summaries.single().fraction()!!
            val expectedFraction =
                workProgressFraction(
                    tracks
                        .filter { it.workId == "w1" }
                        .map { TrackSummary(it.id, it.workId, it.order, it.durationMs) },
                    saved,
                )!!
            assertEquals(expectedFraction, sqlFraction, 0.0001f)

            // Per-track state cascades automatically when work/track is deleted.
            dao.upsertTrackPosition("t1", 12_000L, 500L)
            dao.upsertTrackSubtitleOffset("t1", 750L, 501L)
            dao.upsertTrackSubtitleSelection("t1", "sub-1", "sub-2", 502L)
            val state = dao.trackState("t1")
            assertNotNull(state)
            assertEquals(12_000L, state!!.positionMs)
            assertEquals(750L, state.subtitleOffsetMs)
            assertEquals("sub-1", state.primarySubtitleId)
            assertEquals("sub-2", state.secondarySubtitleId)

            // Verify clean v1 indexes (composite tracks index present, redundant single-col gone).
            val indexes = mutableSetOf<String>()
            db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index'")
                .use { cursor -> while (cursor.moveToNext()) indexes += cursor.getString(0) }
            assertTrue("index_works_coverId" in indexes)
            assertTrue("index_works_addedAt" in indexes)
            assertTrue("index_tracks_workId_order_id" in indexes)
            assertFalse("index_tracks_workId" in indexes)
            assertTrue("index_progress_updatedAt" in indexes)
            assertTrue("index_bookmarks_positionMs" in indexes)

            dao.deleteWork("w1")
            assertNull(dao.trackState("t1"))
        } finally {
            db.close()
        }
    }
}
