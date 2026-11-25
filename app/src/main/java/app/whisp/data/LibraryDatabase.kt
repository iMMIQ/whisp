// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.paging.PagingSource
import androidx.room.*
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

val TrackEntity.isVideo: Boolean
    get() = MediaFormats.isVideo(fileName)

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
    val originalArtist: String = "",
    val coverUrl: String? = null,
    val origin: String = SourceKind.LOCAL.raw,
    val sourceId: String? = null,
)

@Fts4(
    contentEntity = WorkEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["separators=_-./()[]【】「」『』"],
)
@Entity(tableName = "works_fts")
data class WorkFtsEntity(
    val title: String,
    val rj: String?,
    val artist: String,
    val originalTitle: String,
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
    indices = [Index(value = ["workId", "order", "id"])],
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
    val uri: String = "",
    val size: Long = 0,
)

@Fts4(
    contentEntity = TrackEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["separators=_-./()[]【】「」『』"],
)
@Entity(tableName = "tracks_fts")
data class TrackFtsEntity(val title: String, val fileName: String)

@Entity(
    tableName = "track_state",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class TrackStateEntity(
    @PrimaryKey val trackId: String,
    val positionMs: Long = 0L,
    val subtitleOffsetMs: Long = 0L,
    val primarySubtitleId: String? = null,
    val secondarySubtitleId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun subtitleSelectionOrNull(): TrackSubtitleSelection? =
        if (primarySubtitleId != null || secondarySubtitleId != null) {
            TrackSubtitleSelection(primarySubtitleId, secondarySubtitleId)
        } else null
}

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
    val uri: String = "",
    val size: Long = 0,
)

@Fts4(
    contentEntity = AttachmentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["separators=_-./()[]【】「」『』"],
)
@Entity(tableName = "attachments_fts")
data class AttachmentFtsEntity(val fileName: String)

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

data class WorkProgressSummary(
    val workId: String,
    val trackId: String,
    val positionMs: Long,
    val updatedAt: Long,
    val trackCount: Int,
    val knownDurationCount: Int,
    val totalDurationMs: Long,
    val currentTrackDurationMs: Long,
    val trackIndex: Int,
    val beforeDurationMs: Long,
) {
    fun fraction(): Float? {
        if (trackCount <= 0) return null
        if (trackIndex == 0 && positionMs <= 0L) return null
        return if (knownDurationCount == trackCount && totalDurationMs > 0L) {
            val current = positionMs.coerceIn(0L, currentTrackDurationMs)
            ((beforeDurationMs + current).toFloat() / totalDurationMs.toFloat()).coerceIn(0.04f, 1f)
        } else if (trackCount > 1) {
            val inTrack =
                if (currentTrackDurationMs > 0L)
                    (positionMs.toFloat() / currentTrackDurationMs).coerceIn(0f, 1f)
                else if (positionMs > 0L) 0.35f else 0f
            ((trackIndex + inTrack) / trackCount.toFloat()).coerceIn(0.04f, 0.98f)
        } else {
            if (currentTrackDurationMs > 0L)
                (positionMs.toFloat() / currentTrackDurationMs).coerceIn(0.04f, 1f)
            else 0.12f
        }
    }
}

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

    @Query(
        """
        SELECT w.* FROM works w
        LEFT JOIN progress p ON p.workId = w.id
        WHERE (w.sourceId IS NOT NULL OR w.offlineAttachments = 1 OR (:hasOrphanOffline = 1 AND w.id IN (:orphanOfflineIds)))
          AND (:favoriteOnly = 0 OR w.favorite = 1)
          AND (:offlineOnly = 0 OR w.offlineAttachments = 1)
          AND (:series IS NULL OR w.series = :series)
          AND (:filterByIds = 0 OR w.id IN (:matchedIds))
        ORDER BY
          CASE WHEN :sort = 'added_asc' THEN w.addedAt END ASC,
          CASE WHEN :sort = 'recent' THEN COALESCE(p.updatedAt, 0) END DESC,
          CASE WHEN :sort = 'title_asc' THEN LOWER(w.title) END ASC,
          CASE WHEN :sort = 'title_desc' THEN LOWER(w.title) END DESC,
          CASE WHEN :sort = 'offline_first' THEN w.offlineAttachments END DESC,
          w.addedAt DESC
        """,
    )
    fun pagingWorks(
        hasOrphanOffline: Boolean,
        orphanOfflineIds: List<String>,
        favoriteOnly: Boolean,
        offlineOnly: Boolean,
        series: String?,
        filterByIds: Boolean,
        matchedIds: List<String>,
        sort: String,
    ): PagingSource<Int, WorkEntity>

    @Query("SELECT id, workId FROM tracks") fun observeTrackWorkPairs(): Flow<List<TrackWorkPair>>

    @Query(
        """
        SELECT tracks.id, tracks.workId FROM tracks
        INNER JOIN works ON works.id = tracks.workId
        WHERE works.sourceId IS NULL AND works.offlineAttachments = 0
        """,
    )
    fun observeOrphanTrackWorkPairs(): Flow<List<TrackWorkPair>>

    @Query(
        """
        SELECT
            p.workId AS workId,
            p.trackId AS trackId,
            p.positionMs AS positionMs,
            p.updatedAt AS updatedAt,
            COUNT(t.id) AS trackCount,
            SUM(CASE WHEN t.durationMs > 0 THEN 1 ELSE 0 END) AS knownDurationCount,
            SUM(CASE WHEN t.durationMs > 0 THEN t.durationMs ELSE 0 END) AS totalDurationMs,
            cur.durationMs AS currentTrackDurationMs,
            SUM(CASE WHEN (t.`order` < cur.`order` OR (t.`order` = cur.`order` AND t.id < cur.id)) THEN 1 ELSE 0 END) AS trackIndex,
            SUM(CASE WHEN (t.`order` < cur.`order` OR (t.`order` = cur.`order` AND t.id < cur.id)) AND t.durationMs > 0 THEN t.durationMs ELSE 0 END) AS beforeDurationMs
        FROM progress p
        INNER JOIN tracks cur ON cur.id = p.trackId AND cur.workId = p.workId
        INNER JOIN tracks t ON t.workId = p.workId
        GROUP BY p.workId
        ORDER BY p.updatedAt DESC
        """,
    )
    fun observeWorkProgressSummaries(): Flow<List<WorkProgressSummary>>

    @Transaction
    @Query("SELECT * FROM works WHERE id = :id")
    fun observeDetails(id: String): Flow<WorkDetails?>

    @Transaction
    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun details(id: String): WorkDetails?

    @Query(
        """
        SELECT works.id FROM works
        JOIN works_fts ON works.rowid = works_fts.docid
        WHERE works_fts MATCH :ftsQuery
        UNION
        SELECT tracks.workId FROM tracks
        JOIN tracks_fts ON tracks.rowid = tracks_fts.docid
        WHERE tracks_fts MATCH :ftsQuery
        UNION
        SELECT attachments.workId FROM attachments
        JOIN attachments_fts ON attachments.rowid = attachments_fts.docid
        WHERE attachments_fts MATCH :ftsQuery
        """,
    )
    suspend fun searchWorkIdsFts(ftsQuery: String): List<String>

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

    @Query("SELECT * FROM works WHERE UPPER(rj) = UPPER(:rj) LIMIT 1")
    suspend fun workByRj(rj: String): WorkEntity?

    @Query("SELECT * FROM works WHERE favorite = 1 ORDER BY addedAt DESC LIMIT :limit")
    suspend fun favoriteWorks(limit: Int): List<WorkEntity>

    @Query("SELECT * FROM works WHERE offlineAttachments = 1 ORDER BY addedAt DESC LIMIT :limit")
    suspend fun offlineWorks(limit: Int): List<WorkEntity>

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

    @Query("SELECT * FROM track_state") fun observeTrackStates(): Flow<List<TrackStateEntity>>

    @Query("SELECT * FROM track_state WHERE trackId = :trackId")
    fun observeTrackState(trackId: String): Flow<TrackStateEntity?>

    @Query("SELECT * FROM track_state") suspend fun allTrackStates(): List<TrackStateEntity>

    @Query("SELECT * FROM track_state WHERE trackId = :trackId")
    suspend fun trackState(trackId: String): TrackStateEntity?

    @Query(
        """
        INSERT INTO track_state (trackId, positionMs, subtitleOffsetMs, primarySubtitleId, secondarySubtitleId, updatedAt)
        SELECT :trackId, :positionMs, 0, NULL, NULL, :updatedAt
        WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId)
        ON CONFLICT(trackId) DO UPDATE SET
            positionMs = excluded.positionMs,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun upsertTrackPosition(trackId: String, positionMs: Long, updatedAt: Long)

    @Query(
        """
        INSERT INTO track_state (trackId, positionMs, subtitleOffsetMs, primarySubtitleId, secondarySubtitleId, updatedAt)
        SELECT :trackId, 0, :offsetMs, NULL, NULL, :updatedAt
        WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId)
        ON CONFLICT(trackId) DO UPDATE SET
            subtitleOffsetMs = excluded.subtitleOffsetMs,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun upsertTrackSubtitleOffset(trackId: String, offsetMs: Long, updatedAt: Long)

    @Query(
        """
        INSERT INTO track_state (trackId, positionMs, subtitleOffsetMs, primarySubtitleId, secondarySubtitleId, updatedAt)
        SELECT :trackId, 0, 0, :primaryId, :secondaryId, :updatedAt
        WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId)
        ON CONFLICT(trackId) DO UPDATE SET
            primarySubtitleId = excluded.primarySubtitleId,
            secondarySubtitleId = excluded.secondarySubtitleId,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun upsertTrackSubtitleSelection(
        trackId: String,
        primaryId: String?,
        secondaryId: String?,
        updatedAt: Long,
    )

    @Query(
        """
        INSERT INTO track_state (trackId, positionMs, subtitleOffsetMs, primarySubtitleId, secondarySubtitleId, updatedAt)
        SELECT :trackId, :positionMs, :offsetMs, :primaryId, :secondaryId, :updatedAt
        WHERE EXISTS (SELECT 1 FROM tracks WHERE id = :trackId)
        ON CONFLICT(trackId) DO UPDATE SET
            positionMs = excluded.positionMs,
            subtitleOffsetMs = excluded.subtitleOffsetMs,
            primarySubtitleId = excluded.primarySubtitleId,
            secondarySubtitleId = excluded.secondarySubtitleId,
            updatedAt = excluded.updatedAt
        """,
    )
    suspend fun upsertTrackStateIfValid(
        trackId: String,
        positionMs: Long,
        offsetMs: Long,
        primaryId: String?,
        secondaryId: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC")
    fun observeProgress(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC")
    suspend fun allProgress(): List<ProgressEntity>

    @Query(
        """
        SELECT * FROM progress
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.workId = progress.workId)
        ORDER BY updatedAt DESC LIMIT :limit
        """,
    )
    suspend fun recentProgress(limit: Int): List<ProgressEntity>

    @Query(
        """
        SELECT * FROM progress
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.workId = progress.workId)
        ORDER BY updatedAt DESC LIMIT 1
        """,
    )
    fun observeLatestProgress(): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE workId = :id")
    fun observeProgressForWork(id: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE workId = :id")
    suspend fun progress(id: String): ProgressEntity?

    @Query(
        """
        SELECT * FROM progress
        WHERE EXISTS (SELECT 1 FROM tracks WHERE tracks.workId = progress.workId)
        ORDER BY updatedAt DESC LIMIT 1
        """,
    )
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

    @Query("SELECT * FROM bookmarks ORDER BY positionMs")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE workId = :workId ORDER BY positionMs")
    fun observeBookmarksForWork(workId: String): Flow<List<BookmarkEntity>>

    @Upsert suspend fun saveBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: String)
}

@Database(
    entities =
        [
            SourceEntity::class,
            WorkEntity::class,
            WorkFtsEntity::class,
            TrackEntity::class,
            TrackFtsEntity::class,
            TrackStateEntity::class,
            AttachmentEntity::class,
            AttachmentFtsEntity::class,
            ProgressEntity::class,
            BookmarkEntity::class,
            DlsiteMetadataEntity::class,
            DlsitePurchaseEntity::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
