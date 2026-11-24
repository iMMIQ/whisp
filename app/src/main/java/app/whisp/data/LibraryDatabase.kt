// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Source kinds persisted as [SourceEntity.kind] and [WorkEntity.origin] strings. */
enum class SourceKind(val raw: String) {
    LOCAL("local"),
    SMB("smb"),
    NFS("nfs"),
    WEBDAV("webdav"),
    ASMRONE("asmrone"),
    DLSITE("dlsite");

    companion object {
        fun of(raw: String): SourceKind? = entries.firstOrNull { it.raw == raw }
    }
}

val SourceEntity.kindType: SourceKind?
    get() = SourceKind.of(kind)

val WorkEntity.originKind: SourceKind
    get() = SourceKind.of(origin) ?: SourceKind.LOCAL

val WorkEntity.isRemovableOnline: Boolean
    get() = originKind == SourceKind.ASMRONE || originKind == SourceKind.DLSITE

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val root: String,
    val username: String = "",
    val secret: String = "",
    val lastScan: Long = 0,
    val error: String? = null,
)

@Entity(
    tableName = "works",
    foreignKeys =
        [
            ForeignKey(
                entity = SourceEntity::class,
                parentColumns = ["id"],
                childColumns = ["sourceId"],
                onDelete = ForeignKey.SET_NULL,
            ),
        ],
    indices = [Index("sourceId"), Index("coverId"), Index("addedAt")],
)
data class WorkEntity(
    @PrimaryKey val id: String,
    val rj: String?,
    val title: String,
    val originalTitle: String,
    val artist: String = "",
    val series: String? = null,
    val coverId: String? = null,
    val favorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val offlineAttachments: Boolean = false,
    @ColumnInfo(defaultValue = "''") val originalArtist: String = "",
    @ColumnInfo(defaultValue = "NULL") val coverUrl: String? = null,
    @ColumnInfo(defaultValue = "'local'") val origin: String = SourceKind.LOCAL.raw,
    @ColumnInfo(defaultValue = "NULL") val sourceId: String? = null,
)

@Entity(
    tableName = "dlsite_metadata",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkEntity::class,
                parentColumns = ["id"],
                childColumns = ["workId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class DlsiteMetadataEntity(@PrimaryKey val workId: String, val snapshot: String)

@Entity(
    tableName = "dlsite_purchases",
    primaryKeys = ["sourceId", "workno"],
    foreignKeys =
        [
            ForeignKey(
                entity = SourceEntity::class,
                parentColumns = ["id"],
                childColumns = ["sourceId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("sourceId")],
)
data class DlsitePurchaseEntity(
    val sourceId: String,
    val workno: String,
    val purchasedAt: Long,
    val snapshot: String,
)

@Entity(
    tableName = "tracks",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkEntity::class,
                parentColumns = ["id"],
                childColumns = ["workId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("workId"), Index(value = ["workId", "order", "id"])],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val title: String,
    val fileName: String,
    val relativePath: String,
    val order: Int,
    val durationMs: Long = 0,
    val subtitleId: String? = null,
    val scriptId: String? = null,
    val bonus: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uri: String = "",
    @ColumnInfo(defaultValue = "0") val size: Long = 0,
)

@Entity(
    tableName = "attachments",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkEntity::class,
                parentColumns = ["id"],
                childColumns = ["workId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("workId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val title: String,
    val fileName: String,
    val relativePath: String,
    val kind: String,
    @ColumnInfo(defaultValue = "''") val uri: String = "",
    @ColumnInfo(defaultValue = "0") val size: Long = 0,
)

@Entity(
    tableName = "progress",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkEntity::class,
                parentColumns = ["id"],
                childColumns = ["workId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("updatedAt")],
)
data class ProgressEntity(
    @PrimaryKey val workId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "bookmarks",
    foreignKeys =
        [
            ForeignKey(
                entity = WorkEntity::class,
                parentColumns = ["id"],
                childColumns = ["workId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("workId"), Index("positionMs")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val trackId: String,
    val positionMs: Long,
    val title: String,
    val endMs: Long? = null,
)

data class WorkDetails(
    @Embedded val work: WorkEntity,
    @Relation(parentColumn = "id", entityColumn = "workId") val tracks: List<TrackEntity>,
    @Relation(parentColumn = "id", entityColumn = "workId") val attachments: List<AttachmentEntity>,
)

data class TrackWorkPair(val id: String, val workId: String)

data class TrackSummary(
    val id: String,
    val workId: String,
    val order: Int,
    val durationMs: Long,
)

@Dao
interface LibraryDao {
    @Query(
        "SELECT * FROM dlsite_purchases WHERE sourceId = :sourceId ORDER BY purchasedAt DESC, workno DESC",
    )
    fun observeDlsitePurchases(sourceId: String): Flow<List<DlsitePurchaseEntity>>

    @Query("SELECT * FROM dlsite_purchases WHERE sourceId = :sourceId AND workno = :workno")
    suspend fun dlsitePurchase(sourceId: String, workno: String): DlsitePurchaseEntity?

    @Query("SELECT * FROM dlsite_purchases WHERE sourceId = :sourceId")
    suspend fun dlsitePurchasesForSource(sourceId: String): List<DlsitePurchaseEntity>

    @Upsert suspend fun putDlsitePurchases(values: List<DlsitePurchaseEntity>)

    @Query("SELECT * FROM dlsite_metadata WHERE workId = :id")
    fun observeDlsiteMetadata(id: String): Flow<DlsiteMetadataEntity?>

    @Query("SELECT * FROM dlsite_metadata WHERE workId = :id")
    suspend fun dlsiteMetadata(id: String): DlsiteMetadataEntity?

    @Upsert suspend fun putDlsiteMetadata(value: DlsiteMetadataEntity)

    @Query("SELECT * FROM sources ORDER BY name") fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id") suspend fun source(id: String): SourceEntity?

    @Upsert suspend fun putSource(source: SourceEntity)

    @Query("UPDATE sources SET secret = :secret WHERE id = :id")
    suspend fun updateSourceSecret(id: String, secret: String)

    @Query("UPDATE sources SET error = :error WHERE id = :id")
    suspend fun updateSourceError(id: String, error: String?)

    @Query("DELETE FROM sources WHERE id = :id") suspend fun deleteSource(id: String)

    @Query("SELECT * FROM works ORDER BY addedAt DESC") fun observeWorks(): Flow<List<WorkEntity>>

    @Query("SELECT id, workId FROM tracks") fun observeTrackWorkPairs(): Flow<List<TrackWorkPair>>

    @Query(
        """
        SELECT tracks.id, tracks.workId FROM tracks
        INNER JOIN works ON works.id = tracks.workId
        WHERE works.sourceId IS NULL AND works.offlineAttachments = 0
        """,
    )
    fun observeOrphanTrackWorkPairs(): Flow<List<TrackWorkPair>>

    @Query("SELECT id, workId, `order`, durationMs FROM tracks ORDER BY `order`, id")
    fun observeTrackSummaries(): Flow<List<TrackSummary>>

    @Transaction
    @Query("SELECT * FROM works WHERE id = :id")
    fun observeDetails(id: String): Flow<WorkDetails?>

    @Transaction
    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun details(id: String): WorkDetails?

    @Query(
        """
        SELECT id FROM works
        WHERE title LIKE :pattern
           OR rj LIKE :pattern
           OR artist LIKE :pattern
           OR originalTitle LIKE :pattern
        UNION
        SELECT workId FROM tracks
        WHERE title LIKE :pattern
           OR fileName LIKE :pattern
        UNION
        SELECT workId FROM attachments
        WHERE fileName LIKE :pattern
        """,
    )
    suspend fun searchWorkIds(pattern: String): List<String>

    @Query("SELECT * FROM works WHERE id = :id") suspend fun work(id: String): WorkEntity?

    @Query("SELECT * FROM works WHERE coverId = :id LIMIT 1")
    suspend fun workByCover(id: String): WorkEntity?

    @Query("SELECT * FROM works WHERE sourceId = :sourceId")
    suspend fun sourceWorks(sourceId: String): List<WorkEntity>

    @Query("SELECT id FROM works WHERE sourceId = :sourceId")
    suspend fun sourceWorkIds(sourceId: String): List<String>

    @Query("SELECT * FROM works") suspend fun allWorks(): List<WorkEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM works WHERE coverId = :assetId AND offlineAttachments = 1 AND id != :excludingWork)",
    )
    suspend fun otherOfflineCover(assetId: String, excludingWork: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM works WHERE coverId = :assetId AND id != :excludingWork)")
    suspend fun otherCover(assetId: String, excludingWork: String): Boolean

    @Upsert suspend fun putWorks(works: List<WorkEntity>)

    @Upsert suspend fun putTracks(tracks: List<TrackEntity>)

    @Upsert suspend fun putAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM works WHERE id = :workId") suspend fun deleteWork(workId: String)

    @Query("DELETE FROM tracks WHERE id IN (:ids)") suspend fun deleteTracks(ids: List<String>)

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun deleteAttachments(ids: List<String>)

    @Query("DELETE FROM tracks WHERE workId = :workId") suspend fun deleteWorkTracks(workId: String)

    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): TrackEntity?

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM works WHERE id IN (:ids)")
    suspend fun worksByIds(ids: List<String>): List<WorkEntity>

    @Query("SELECT * FROM tracks WHERE workId = :workId")
    suspend fun tracksForWork(workId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE workId IN (:workIds)")
    suspend fun tracksForWorks(workIds: List<String>): List<TrackEntity>

    @Query("SELECT * FROM attachments WHERE workId IN (:workIds)")
    suspend fun attachmentsForWorks(workIds: List<String>): List<AttachmentEntity>

    @Query("SELECT id FROM attachments WHERE workId = :workId")
    suspend fun attachmentIdsForWork(workId: String): List<String>

    @Query("UPDATE tracks SET durationMs = :duration WHERE id = :id AND durationMs != :duration")
    suspend fun updateDuration(id: String, duration: Long)

    @Query("UPDATE works SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE works SET title = :title, artist = :artist WHERE id = :id")
    suspend fun updateWorkIdentity(id: String, title: String, artist: String)

    @Query("UPDATE works SET coverId = :coverId WHERE id = :id")
    suspend fun updateWorkCover(id: String, coverId: String)

    @Query("UPDATE works SET offlineAttachments = :ready WHERE id = :id")
    suspend fun attachmentsReady(id: String, ready: Boolean)

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC")
    fun observeProgress(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestProgress(): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE workId = :id")
    fun observeProgressForWork(id: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE workId = :id")
    suspend fun progress(id: String): ProgressEntity?

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestProgress(): ProgressEntity?

    @Upsert suspend fun saveProgress(progress: ProgressEntity)

    @Query(
        """
        INSERT OR REPLACE INTO progress (workId, trackId, positionMs, updatedAt)
        SELECT :workId, :trackId, :positionMs, :updatedAt
        WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId AND workId = :workId)
        """,
    )
    suspend fun saveProgressIfValid(
        workId: String,
        trackId: String,
        positionMs: Long,
        updatedAt: Long,
    )

    @Query("SELECT * FROM bookmarks ORDER BY positionMs")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE workId = :workId ORDER BY positionMs")
    fun observeBookmarksForWork(workId: String): Flow<List<BookmarkEntity>>

    @Upsert suspend fun saveBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: String)
}

val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE dlsite_metadata_tmp (workId TEXT NOT NULL, snapshot TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO dlsite_metadata_tmp SELECT workId, snapshot FROM dlsite_metadata",
            )
            db.execSQL("DROP TABLE dlsite_metadata")

            db.execSQL(
                """
                CREATE TABLE works_new (
                    id TEXT NOT NULL,
                    rj TEXT,
                    title TEXT NOT NULL,
                    originalTitle TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    series TEXT,
                    coverId TEXT,
                    favorite INTEGER NOT NULL,
                    addedAt INTEGER NOT NULL,
                    offlineAttachments INTEGER NOT NULL,
                    originalArtist TEXT NOT NULL DEFAULT '',
                    coverUrl TEXT DEFAULT NULL,
                    origin TEXT NOT NULL DEFAULT 'local',
                    sourceId TEXT DEFAULT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(sourceId) REFERENCES sources(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """,
            )
            db.execSQL(
                """
                INSERT INTO works_new (
                    id, rj, title, originalTitle, artist, series, coverId, favorite,
                    addedAt, offlineAttachments, originalArtist, coverUrl, origin, sourceId
                )
                SELECT
                    w.id, w.rj, w.title, w.originalTitle, w.artist, w.series, w.coverId, w.favorite,
                    w.addedAt, w.offlineAttachments, w.originalArtist, w.coverUrl, w.origin,
                    (SELECT l.sourceId FROM tracks t
                     JOIN locations l ON l.assetId = t.id
                     JOIN sources s ON s.id = l.sourceId
                     WHERE t.workId = w.id LIMIT 1)
                FROM works w
                """,
            )
            db.execSQL("DROP TABLE works")
            db.execSQL("ALTER TABLE works_new RENAME TO works")
            db.execSQL("CREATE INDEX index_works_sourceId ON works(sourceId)")

            db.execSQL(
                """
                CREATE TABLE dlsite_metadata (
                    workId TEXT NOT NULL,
                    snapshot TEXT NOT NULL,
                    PRIMARY KEY(workId),
                    FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
            )
            db.execSQL(
                "INSERT INTO dlsite_metadata SELECT workId, snapshot FROM dlsite_metadata_tmp WHERE workId IN (SELECT id FROM works)",
            )
            db.execSQL("DROP TABLE dlsite_metadata_tmp")

            db.execSQL(
                """
                CREATE TABLE tracks_new (
                    id TEXT NOT NULL,
                    workId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    `order` INTEGER NOT NULL,
                    durationMs INTEGER NOT NULL,
                    subtitleId TEXT,
                    scriptId TEXT,
                    bonus INTEGER NOT NULL,
                    uri TEXT NOT NULL DEFAULT '',
                    size INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id),
                    FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
            )
            db.execSQL(
                """
                INSERT INTO tracks_new (
                    id, workId, title, fileName, relativePath, `order`, durationMs,
                    subtitleId, scriptId, bonus, uri, size
                )
                SELECT
                    t.id, t.workId, t.title, t.fileName, t.relativePath, t.`order`, t.durationMs,
                    t.subtitleId, t.scriptId, t.bonus,
                    COALESCE((SELECT l.uri FROM locations l WHERE l.assetId = t.id LIMIT 1), ''),
                    COALESCE((SELECT l.size FROM locations l WHERE l.assetId = t.id LIMIT 1), 0)
                FROM tracks t WHERE t.workId IN (SELECT id FROM works)
                """,
            )
            db.execSQL("DROP TABLE tracks")
            db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
            db.execSQL("CREATE INDEX index_tracks_workId ON tracks(workId)")

            db.execSQL(
                """
                CREATE TABLE attachments_new (
                    id TEXT NOT NULL,
                    workId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    relativePath TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    uri TEXT NOT NULL DEFAULT '',
                    size INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(id),
                    FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
            )
            db.execSQL(
                """
                INSERT INTO attachments_new (id, workId, title, fileName, relativePath, kind, uri, size)
                SELECT
                    a.id, a.workId, a.title, a.fileName, a.relativePath, a.kind,
                    COALESCE((SELECT l.uri FROM locations l WHERE l.assetId = a.id LIMIT 1), ''),
                    COALESCE((SELECT l.size FROM locations l WHERE l.assetId = a.id LIMIT 1), 0)
                FROM attachments a WHERE a.workId IN (SELECT id FROM works)
                """,
            )
            db.execSQL("DROP TABLE attachments")
            db.execSQL("ALTER TABLE attachments_new RENAME TO attachments")
            db.execSQL("CREATE INDEX index_attachments_workId ON attachments(workId)")

            db.execSQL(
                """
                CREATE TABLE progress_new (
                    workId TEXT NOT NULL,
                    trackId TEXT NOT NULL,
                    positionMs INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(workId),
                    FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
            )
            db.execSQL(
                "INSERT INTO progress_new SELECT workId, trackId, positionMs, updatedAt FROM progress WHERE workId IN (SELECT id FROM works)",
            )
            db.execSQL("DROP TABLE progress")
            db.execSQL("ALTER TABLE progress_new RENAME TO progress")

            db.execSQL(
                """
                CREATE TABLE bookmarks_new (
                    id TEXT NOT NULL,
                    workId TEXT NOT NULL,
                    trackId TEXT NOT NULL,
                    positionMs INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    endMs INTEGER,
                    PRIMARY KEY(id),
                    FOREIGN KEY(workId) REFERENCES works(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """,
            )
            db.execSQL(
                "INSERT INTO bookmarks_new SELECT id, workId, trackId, positionMs, title, endMs FROM bookmarks WHERE workId IN (SELECT id FROM works)",
            )
            db.execSQL("DROP TABLE bookmarks")
            db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")
            db.execSQL("CREATE INDEX index_bookmarks_workId ON bookmarks(workId)")

            db.execSQL("DROP TABLE IF EXISTS locations")
        }
    }

val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_works_coverId ON works(coverId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_works_addedAt ON works(addedAt)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tracks_workId_order_id ON tracks(workId, `order`, id)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_progress_updatedAt ON progress(updatedAt)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bookmarks_positionMs ON bookmarks(positionMs)",
            )
        }
    }

@Database(
    entities =
        [
            SourceEntity::class,
            WorkEntity::class,
            TrackEntity::class,
            AttachmentEntity::class,
            ProgressEntity::class,
            BookmarkEntity::class,
            DlsiteMetadataEntity::class,
            DlsitePurchaseEntity::class,
        ],
    version = 6,
    autoMigrations =
        [
            AutoMigration(from = 1, to = 2),
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
        ],
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
