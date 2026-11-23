// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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

@Entity(tableName = "works")
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
    @ColumnInfo(defaultValue = "'local'") val origin: String = "local",
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

@Entity(tableName = "tracks", indices = [Index("workId")])
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
)

@Entity(tableName = "attachments", indices = [Index("workId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val title: String,
    val fileName: String,
    val relativePath: String,
    val kind: String,
)

@Entity(tableName = "locations", indices = [Index("assetId"), Index("sourceId")])
data class LocationEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val sourceId: String,
    val uri: String,
    val size: Long,
    val modified: Long = 0,
    val generation: Long = 0,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val workId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "bookmarks", indices = [Index("workId")])
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

@Dao
interface LibraryDao {
    @Query(
        "SELECT * FROM dlsite_purchases WHERE sourceId = :sourceId ORDER BY purchasedAt DESC, workno DESC",
    )
    fun observeDlsitePurchases(sourceId: String): Flow<List<DlsitePurchaseEntity>>

    @Query("SELECT * FROM dlsite_purchases WHERE sourceId = :sourceId AND workno = :workno")
    suspend fun dlsitePurchase(sourceId: String, workno: String): DlsitePurchaseEntity?

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

    @Query("DELETE FROM locations WHERE sourceId = :id")
    suspend fun deleteSourceLocations(id: String)

    @Transaction
    @Query(
        "SELECT * FROM works ORDER BY addedAt DESC",
    )
    fun observeLibrary(): Flow<List<WorkDetails>>

    @Query(
        "SELECT DISTINCT tracks.workId FROM tracks JOIN locations ON locations.assetId = tracks.id JOIN sources ON sources.id = locations.sourceId",
    )
    fun observeLocatedWorks(): Flow<List<String>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM works WHERE coverId = :assetId AND offlineAttachments = 1 AND id != :excludingWork)",
    )
    suspend fun otherOfflineCover(assetId: String, excludingWork: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM works WHERE coverId = :assetId AND id != :excludingWork)")
    suspend fun otherCover(assetId: String, excludingWork: String): Boolean

    @Query("SELECT * FROM locations WHERE sourceId = :sourceId")
    suspend fun sourceLocations(sourceId: String): List<LocationEntity>

    @Transaction
    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun details(id: String): WorkDetails?

    @Query("SELECT * FROM works WHERE id = :id") suspend fun work(id: String): WorkEntity?

    @Query("SELECT * FROM works WHERE coverId = :id LIMIT 1")
    suspend fun workByCover(id: String): WorkEntity?

    @Query("SELECT * FROM works") suspend fun allWorks(): List<WorkEntity>

    @Query("SELECT id FROM attachments WHERE kind = 'image'")
    suspend fun imageAttachments(): List<String>

    @Query("SELECT * FROM tracks") suspend fun allTracks(): List<TrackEntity>

    @Query(
        "SELECT DISTINCT locations.assetId FROM locations JOIN sources ON sources.id = locations.sourceId WHERE locations.assetId IN (:ids)",
    )
    suspend fun locatedAssets(ids: List<String>): List<String>

    @Query(
        "SELECT * FROM tracks WHERE workId IN (:workIds) AND NOT EXISTS(SELECT 1 FROM locations JOIN sources ON sources.id = locations.sourceId WHERE locations.assetId = tracks.id)",
    )
    suspend fun unlocatedTracks(workIds: List<String>): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE id IN (:ids)") suspend fun deleteTracks(ids: List<String>)

    @Query(
        "SELECT * FROM attachments WHERE workId IN (:workIds) AND NOT EXISTS(SELECT 1 FROM locations JOIN sources ON sources.id = locations.sourceId WHERE locations.assetId = attachments.id)",
    )
    suspend fun unlocatedAttachments(workIds: List<String>): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun deleteAttachments(ids: List<String>)

    @Upsert suspend fun putWorks(works: List<WorkEntity>)

    @Upsert suspend fun putTracks(tracks: List<TrackEntity>)

    @Upsert suspend fun putAttachments(attachments: List<AttachmentEntity>)

    @Upsert suspend fun putLocations(locations: List<LocationEntity>)

    @Query("DELETE FROM locations WHERE sourceId = :id AND generation != :generation")
    suspend fun removeStaleLocations(id: String, generation: Long)

    @Query("SELECT * FROM locations WHERE assetId = :id")
    suspend fun locations(id: String): List<LocationEntity>

    @Query(
        "SELECT DISTINCT workId FROM tracks JOIN locations ON locations.assetId = tracks.id WHERE locations.sourceId = :sourceId",
    )
    suspend fun sourceWorkIds(sourceId: String): List<String>

    @Query(
        "DELETE FROM locations WHERE sourceId = :sourceId AND assetId IN (SELECT id FROM tracks WHERE workId = :workId UNION SELECT id FROM attachments WHERE workId = :workId UNION SELECT coverId FROM works WHERE id = :workId)",
    )
    suspend fun deleteWorkSourceLocations(workId: String, sourceId: String)

    @Query("DELETE FROM tracks WHERE workId = :workId AND id NOT IN (:keepIds)")
    suspend fun deleteStaleTracks(workId: String, keepIds: List<String>)

    @Query("DELETE FROM attachments WHERE workId = :workId")
    suspend fun deleteWorkAttachments(workId: String)

    @Query("DELETE FROM attachments WHERE workId = :workId AND id NOT IN (:keepIds)")
    suspend fun deleteStaleAttachments(workId: String, keepIds: List<String>)

    @Query(
        "DELETE FROM locations WHERE assetId IN (SELECT id FROM tracks WHERE workId = :workId UNION SELECT id FROM attachments WHERE workId = :workId UNION SELECT coverId FROM works WHERE id = :workId)",
    )
    suspend fun deleteWorkLocations(workId: String)

    @Query("DELETE FROM tracks WHERE workId = :workId") suspend fun deleteWorkTracks(workId: String)

    @Query("DELETE FROM works WHERE id = :workId") suspend fun deleteWork(workId: String)

    @Query("DELETE FROM progress WHERE workId = :workId")
    suspend fun deleteWorkProgress(workId: String)

    @Query("DELETE FROM bookmarks WHERE workId = :workId")
    suspend fun deleteWorkBookmarks(workId: String)

    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM works WHERE id IN (:ids)")
    suspend fun worksByIds(ids: List<String>): List<WorkEntity>

    @Query("SELECT id FROM tracks WHERE workId = :workId")
    suspend fun trackIdsForWork(workId: String): List<String>

    @Query("SELECT id FROM attachments WHERE workId = :workId")
    suspend fun attachmentIdsForWork(workId: String): List<String>

    @Query(
        "SELECT id FROM works WHERE id IN (:workIds) AND offlineAttachments = 0 AND NOT EXISTS(SELECT 1 FROM tracks JOIN locations ON locations.assetId = tracks.id JOIN sources ON sources.id = locations.sourceId WHERE tracks.workId = works.id)",
    )
    suspend fun unlocatedWorkIds(workIds: List<String>): List<String>

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

    @Query("SELECT * FROM progress WHERE workId = :id")
    suspend fun progress(id: String): ProgressEntity?

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestProgress(): ProgressEntity?

    @Upsert suspend fun saveProgress(progress: ProgressEntity)

    @Query("SELECT * FROM bookmarks ORDER BY positionMs")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Upsert suspend fun saveBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: String)
}

@Database(
    entities =
        [
            SourceEntity::class,
            WorkEntity::class,
            TrackEntity::class,
            AttachmentEntity::class,
            LocationEntity::class,
            ProgressEntity::class,
            BookmarkEntity::class,
            DlsiteMetadataEntity::class,
            DlsitePurchaseEntity::class,
        ],
    version = 4,
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
