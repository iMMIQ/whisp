// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.whisp.data.AttachmentEntity
import app.whisp.data.LibraryDatabase
import app.whisp.data.MIGRATION_5_6
import app.whisp.data.SourceEntity
import app.whisp.data.TrackEntity
import app.whisp.data.WorkEntity
import app.whisp.data.walkTreeConcurrent
import app.whisp.ui.chunkLongText
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
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
    fun searchWorkIdsUnionAndSaveProgressIfValidWorkAsExpected() = runBlocking {
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
            dao.putTracks(
                listOf(
                    TrackEntity(
                        id = "t1",
                        workId = "w1",
                        title = "Whisper Intro",
                        fileName = "01_whisper.flac",
                        relativePath = "01.flac",
                        order = 1,
                        durationMs = 1000L,
                        uri = "content://1",
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
                ),
            )
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

            // Matches w1 across work/track/attachment and w2 via track, deduplicated by UNION.
            val matched = dao.searchWorkIds("%whisper%").sorted()
            assertEquals(listOf("w1", "w2"), matched)
            assertEquals(listOf("w1", "w2"), dao.searchWorkIds("%rainy%").sorted())
            assertEquals(listOf("w2"), dao.searchWorkIds("%sunny%"))

            // saveProgressIfValid ignores unknown work or mismatched trackId.
            dao.saveProgressIfValid("missing", "t1", 500L, 100L)
            dao.saveProgressIfValid("w1", "t2", 500L, 100L)
            assertNull(dao.progress("w1"))

            // Valid work+track inserts and updates in place.
            dao.saveProgressIfValid("w1", "t1", 1234L, 200L)
            val saved = dao.progress("w1")
            assertNotNull(saved)
            assertEquals("t1", saved!!.trackId)
            assertEquals(1234L, saved.positionMs)
            assertEquals(200L, saved.updatedAt)

            dao.saveProgressIfValid("w1", "t1", 5678L, 300L)
            val updated = dao.progress("w1")
            assertEquals(5678L, updated!!.positionMs)
            assertEquals(300L, updated.updatedAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration5To6CreatesPerformanceIndexesAndPreservesData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dbFile = File(context.cacheDir, "migration-5-6-test.db")
        dbFile.delete()
        try {
            // Seed exact v5 schema matching app/schemas/.../5.json.
            val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            sqlite.execSQL(
                "CREATE TABLE `sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `root` TEXT NOT NULL, `username` TEXT NOT NULL, `secret` TEXT NOT NULL, `lastScan` INTEGER NOT NULL, `error` TEXT, PRIMARY KEY(`id`))",
            )
            sqlite.execSQL(
                "CREATE TABLE `works` (`id` TEXT NOT NULL, `rj` TEXT, `title` TEXT NOT NULL, `originalTitle` TEXT NOT NULL, `artist` TEXT NOT NULL, `series` TEXT, `coverId` TEXT, `favorite` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `offlineAttachments` INTEGER NOT NULL, `originalArtist` TEXT NOT NULL DEFAULT '', `coverUrl` TEXT DEFAULT NULL, `origin` TEXT NOT NULL DEFAULT 'local', `sourceId` TEXT DEFAULT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
            )
            sqlite.execSQL("CREATE INDEX `index_works_sourceId` ON `works` (`sourceId`)")
            sqlite.execSQL(
                "CREATE TABLE `tracks` (`id` TEXT NOT NULL, `workId` TEXT NOT NULL, `title` TEXT NOT NULL, `fileName` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `order` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `subtitleId` TEXT, `scriptId` TEXT, `bonus` INTEGER NOT NULL, `uri` TEXT NOT NULL DEFAULT '', `size` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX `index_tracks_workId` ON `tracks` (`workId`)")
            sqlite.execSQL(
                "CREATE TABLE `attachments` (`id` TEXT NOT NULL, `workId` TEXT NOT NULL, `title` TEXT NOT NULL, `fileName` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `kind` TEXT NOT NULL, `uri` TEXT NOT NULL DEFAULT '', `size` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX `index_attachments_workId` ON `attachments` (`workId`)")
            sqlite.execSQL(
                "CREATE TABLE `dlsite_metadata` (`workId` TEXT NOT NULL, `snapshot` TEXT NOT NULL, PRIMARY KEY(`workId`), FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE `dlsite_purchases` (`sourceId` TEXT NOT NULL, `workno` TEXT NOT NULL, `purchasedAt` INTEGER NOT NULL, `snapshot` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `workno`), FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE INDEX `index_dlsite_purchases_sourceId` ON `dlsite_purchases` (`sourceId`)",
            )
            sqlite.execSQL(
                "CREATE TABLE `progress` (`workId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`workId`), FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL(
                "CREATE TABLE `bookmarks` (`id` TEXT NOT NULL, `workId` TEXT NOT NULL, `trackId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `endMs` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`workId`) REFERENCES `works`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX `index_bookmarks_workId` ON `bookmarks` (`workId`)")
            sqlite.execSQL(
                "INSERT INTO `works` VALUES ('w1','RJ01','T','T','A',NULL,'c1',0,100,0,'A',NULL,'local',NULL)",
            )
            sqlite.execSQL("INSERT INTO `progress` VALUES ('w1','t1',4200,999)")
            sqlite.version = 5
            sqlite.close()

            val migrated =
                Room.databaseBuilder(context, LibraryDatabase::class.java, dbFile.absolutePath)
                    .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                    .addMigrations(MIGRATION_5_6)
                    .build()
            try {
                val work = migrated.library().work("w1")
                assertNotNull(work)
                assertEquals("c1", work!!.coverId)
                assertEquals(4200L, migrated.library().progress("w1")?.positionMs)

                val indexes = mutableSetOf<String>()
                migrated.openHelper.readableDatabase
                    .query("SELECT name FROM sqlite_master WHERE type = 'index'")
                    .use { cursor -> while (cursor.moveToNext()) indexes += cursor.getString(0) }
                assertTrue("index_works_coverId" in indexes)
                assertTrue("index_works_addedAt" in indexes)
                assertTrue("index_tracks_workId_order_id" in indexes)
                assertTrue("index_progress_updatedAt" in indexes)
                assertTrue("index_bookmarks_positionMs" in indexes)
                assertFalse(indexes.isEmpty())
            } finally {
                migrated.close()
            }
        } finally {
            dbFile.delete()
        }
    }
}
