// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.LibraryDatabase
import app.whisp.data.MIGRATION_4_5
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationIntegrationTest {
    @Test
    fun existingLibraryUpgradesWithProgressBookmarksAndOfflineState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema =
            JSONObject(
                    instrumentation.context.assets
                        .open("app.whisp.data.LibraryDatabase/1.json")
                        .bufferedReader()
                        .use { it.readText() },
                )
                .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(
                    entity
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")),
                )
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(
                    indices
                        .getJSONObject(i)
                        .getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")),
                )
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL(
                "INSERT INTO sources VALUES ('migration-source', '本地', 'local', 'content://test', '', '', 123, NULL)",
            )
            db.execSQL(
                "INSERT INTO works VALUES ('migration-work', 'RJ00000001', '自定义标题', '原标题', '示例声优', NULL, NULL, 1, 123, 1)",
            )
            db.execSQL(
                "INSERT INTO tracks VALUES ('migration-track', 'migration-work', '示例曲目', '01.wav', '01.wav', 1, 40000, NULL, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO locations VALUES ('migration-loc', 'migration-track', 'migration-source', 'content://test/01.wav', 1024, 1)",
            )
            db.execSQL(
                "INSERT INTO progress VALUES ('migration-work', 'migration-track', 1000, 123)",
            )
            db.execSQL(
                "INSERT INTO bookmarks VALUES ('migration-bookmark', 'migration-work', 'migration-track', 2000, '示例书签', NULL)",
            )
            db.version = 1
        }
        val database =
            Room.databaseBuilder(context, LibraryDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5)
                .build()
        try {
            val work = database.library().details("migration-work")!!
            assertEquals("自定义标题", work.work.title)
            assertTrue(work.work.favorite)
            assertTrue(work.work.offlineAttachments)
            assertEquals("", work.work.originalArtist)
            assertNull(work.work.coverUrl)
            assertEquals("migration-source", work.work.sourceId)
            assertEquals(40000L, work.tracks.single().durationMs)
            assertEquals("content://test/01.wav", work.tracks.single().uri)
            assertEquals(1024L, work.tracks.single().size)
            assertEquals(1000L, database.library().progress("migration-work")!!.positionMs)
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bookmarks").use {
                cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            assertEquals(5, database.openHelper.readableDatabase.version)
            assertEquals("local", work.work.origin)
            assertNull(database.library().dlsiteMetadata("migration-work"))
        } finally {
            database.close()
        }
        context.deleteDatabase(name)
        Unit
    }
}
