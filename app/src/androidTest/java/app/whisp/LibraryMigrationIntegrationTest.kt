// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationIntegrationTest {
    @Test
    fun freshV1DatabaseSupportsFtsSearchProgressSummariesAndTrackStateCascade() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        val name = "v1-schema-test.db"
        context.deleteDatabase(name)
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        try {
            val dao = database.library()
            dao.putSource(SourceEntity("s1", "本地", "local", "content://test"))
            dao.putWorks(
                listOf(
                    WorkEntity(
                        id = "w1",
                        rj = "RJ00000001",
                        title = "Whisper Demo",
                        originalTitle = "Whisper Demo",
                        artist = "Demo VA",
                        originalArtist = "Demo VA",
                        favorite = true,
                        offlineAttachments = true,
                        sourceId = "s1",
                    ),
                ),
            )
            dao.putTracks(
                listOf(
                    TrackEntity(
                        id = "t1",
                        workId = "w1",
                        title = "Intro",
                        fileName = "01_intro.wav",
                        relativePath = "01_intro.wav",
                        order = 1,
                        durationMs = 40_000L,
                        uri = "content://test/01_intro.wav",
                        size = 1024L,
                    ),
                ),
            )
            dao.saveProgress(ProgressEntity("w1", "t1", 10_000L, 123L))
            dao.saveBookmark(BookmarkEntity("b1", "w1", "t1", 2_000L, "示例书签"))
            dao.upsertTrackPosition("t1", 10_000L, 123L)
            dao.upsertTrackSubtitleOffset("t1", 500L, 124L)

            assertEquals(1, database.openHelper.readableDatabase.version)
            assertEquals(listOf("w1"), dao.searchWorkIdsFts("whisper*"))
            val summary = dao.observeWorkProgressSummaries().first().single()
            assertEquals(0.25f, summary.fraction()!!, 0.001f)
            assertEquals(500L, dao.trackState("t1")?.subtitleOffsetMs)

            dao.deleteWork("w1")
            assertNull(dao.trackState("t1"))
        } finally {
            database.close()
        }
        context.deleteDatabase(name)
        Unit
    }
}
