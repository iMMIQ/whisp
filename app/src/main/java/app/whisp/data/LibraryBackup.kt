// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val backupJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

@Serializable
data class BackupWorkCustomization(
    val workId: String,
    val rj: String? = null,
    val title: String,
    val originalTitle: String,
    val artist: String,
    val originalArtist: String,
    val favorite: Boolean,
)

@Serializable
data class BackupProgress(
    val workId: String,
    val rj: String? = null,
    val trackId: String,
    val trackRelativePath: String? = null,
    val positionMs: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupBookmark(
    val id: String,
    val workId: String,
    val rj: String? = null,
    val trackId: String,
    val trackRelativePath: String? = null,
    val positionMs: Long,
    val title: String,
    val endMs: Long? = null,
)

@Serializable
data class BackupTrackState(
    val workId: String,
    val rj: String? = null,
    val trackId: String,
    val trackRelativePath: String? = null,
    val positionMs: Long = 0L,
    val subtitleOffsetMs: Long = 0L,
    val primarySubtitleId: String? = null,
    val primarySubtitlePath: String? = null,
    val secondarySubtitleId: String? = null,
    val secondarySubtitlePath: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Privacy-preserving local backup snapshot (v2) for SAF export/import. Strictly excludes source
 * credentials, passwords, cookies, and raw remote paths, while recording relative paths so progress
 * / bookmarks / per-track subtitle settings can migrate cleanly across sources for the same RJ.
 */
@Serializable
data class WhispBackupSnapshot(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: Settings = Settings(),
    val deviceAudioProfiles: Map<String, DeviceAudioProfile> = emptyMap(),
    val works: List<BackupWorkCustomization> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val trackStates: List<BackupTrackState> = emptyList(),
)

data class BackupRestoreSummary(
    val restoredWorks: Int,
    val restoredProgress: Int,
    val restoredBookmarks: Int,
)

object LibraryBackup {
    fun encode(snapshot: WhispBackupSnapshot): String = backupJson.encodeToString(snapshot)

    fun decode(raw: String): WhispBackupSnapshot {
        val decoded = backupJson.decodeFromString<WhispBackupSnapshot>(raw)
        require(decoded.version == 2) { "不支持的备份文件版本（v${decoded.version}）" }
        return decoded
    }

    suspend fun exportSnapshot(
        library: LibraryStore,
        settingsStore: SettingsStore,
    ): WhispBackupSnapshot =
        withContext(Dispatchers.IO) {
            library.flushPlaybackProgress()
            val settings = settingsStore.settingsUpdates.first()
            val deviceProfiles = settingsStore.currentDeviceAudioProfiles()
            val allWorks = library.dao.allWorks()
            val rjByWorkId = allWorks.associate { it.id to it.rj }
            val workIds = allWorks.map { it.id }
            val tracksById =
                workIds
                    .chunked(500)
                    .flatMap { library.dao.tracksForWorks(it) }
                    .associateBy { it.id }
            val attachmentsById =
                workIds
                    .chunked(500)
                    .flatMap { library.dao.attachmentsForWorks(it) }
                    .associateBy { it.id }

            val customizedWorks =
                allWorks
                    .filter {
                        it.favorite ||
                            it.title != it.originalTitle ||
                            it.artist != it.originalArtist
                    }
                    .map {
                        BackupWorkCustomization(
                            workId = it.id,
                            rj = it.rj,
                            title = it.title,
                            originalTitle = it.originalTitle,
                            artist = it.artist,
                            originalArtist = it.originalArtist,
                            favorite = it.favorite,
                        )
                    }
            val progressList =
                library.dao.allProgress().map { p ->
                    BackupProgress(
                        workId = p.workId,
                        rj = rjByWorkId[p.workId],
                        trackId = p.trackId,
                        trackRelativePath = tracksById[p.trackId]?.relativePath,
                        positionMs = p.positionMs,
                        updatedAt = p.updatedAt,
                    )
                }
            val bookmarkList =
                library.dao.allBookmarks().map { b ->
                    BackupBookmark(
                        id = b.id,
                        workId = b.workId,
                        rj = rjByWorkId[b.workId],
                        trackId = b.trackId,
                        trackRelativePath = tracksById[b.trackId]?.relativePath,
                        positionMs = b.positionMs,
                        title = b.title,
                        endMs = b.endMs,
                    )
                }
            val trackStateList =
                library.dao.allTrackStates().mapNotNull { state ->
                    val track = tracksById[state.trackId] ?: return@mapNotNull null
                    BackupTrackState(
                        workId = track.workId,
                        rj = rjByWorkId[track.workId],
                        trackId = state.trackId,
                        trackRelativePath = track.relativePath,
                        positionMs = state.positionMs,
                        subtitleOffsetMs = state.subtitleOffsetMs,
                        primarySubtitleId = state.primarySubtitleId,
                        primarySubtitlePath =
                            state.primarySubtitleId?.let {
                                attachmentsById[it]?.relativePath
                            },
                        secondarySubtitleId = state.secondarySubtitleId,
                        secondarySubtitlePath =
                            state.secondarySubtitleId?.let {
                                attachmentsById[it]?.relativePath
                            },
                        updatedAt = state.updatedAt,
                    )
                }
            WhispBackupSnapshot(
                version = 2,
                exportedAt = System.currentTimeMillis(),
                settings = settings,
                deviceAudioProfiles = deviceProfiles,
                works = customizedWorks,
                progress = progressList,
                bookmarks = bookmarkList,
                trackStates = trackStateList,
            )
        }

    suspend fun importSnapshot(
        snapshot: WhispBackupSnapshot,
        library: LibraryStore,
        settingsStore: SettingsStore,
    ): BackupRestoreSummary {
        settingsStore.restoreBackup(
            settings = snapshot.settings,
            deviceProfiles = snapshot.deviceAudioProfiles,
        )
        return withContext(Dispatchers.IO) {
            var restoredWorks = 0
            var restoredProgress = 0
            var restoredBookmarks = 0
            library.database.withTransaction {
                val existingWorks = library.dao.allWorks()
                val byId = existingWorks.associateBy { it.id }
                val byRj =
                    existingWorks
                        .filter { !it.rj.isNullOrBlank() }
                        .associateBy { it.rj!!.uppercase() }
                val tracksCache = HashMap<String, List<TrackEntity>>()
                val attachmentsCache = HashMap<String, List<AttachmentEntity>>()

                suspend fun tracksOf(workId: String): List<TrackEntity> =
                    tracksCache.getOrPut(workId) { library.dao.tracksForWork(workId) }

                suspend fun attachmentsOf(workId: String): List<AttachmentEntity> =
                    attachmentsCache.getOrPut(workId) {
                        library.dao.attachmentsForWorks(listOf(workId))
                    }

                fun resolveWork(workId: String, rj: String?): WorkEntity? =
                    byId[workId] ?: rj?.uppercase()?.let(byRj::get)

                suspend fun resolveTrackId(
                    workId: String,
                    trackId: String,
                    relativePath: String?,
                ): String? {
                    val tracks = tracksOf(workId)
                    if (tracks.any { it.id == trackId }) return trackId
                    val path = relativePath?.takeIf(String::isNotBlank) ?: return null
                    return tracks.firstOrNull { it.relativePath == path }?.id
                }

                suspend fun resolveAttachmentId(
                    workId: String,
                    attachmentId: String?,
                    relativePath: String?,
                ): String? {
                    if (attachmentId == null && relativePath == null) return null
                    val attachments = attachmentsOf(workId)
                    if (attachmentId != null && attachments.any { it.id == attachmentId }) {
                        return attachmentId
                    }
                    val path = relativePath?.takeIf(String::isNotBlank) ?: return null
                    return attachments.firstOrNull { it.relativePath == path }?.id
                }

                for (entry in snapshot.works) {
                    val target = resolveWork(entry.workId, entry.rj) ?: continue
                    val nextTitle =
                        if (entry.title != entry.originalTitle && entry.title.isNotBlank())
                            entry.title
                        else target.title
                    val nextArtist =
                        if (entry.artist != entry.originalArtist) entry.artist else target.artist
                    if (nextTitle != target.title || nextArtist != target.artist) {
                        library.dao.updateWorkIdentity(target.id, nextTitle, nextArtist)
                    }
                    if (entry.favorite && !target.favorite) {
                        library.dao.toggleFavorite(target.id)
                    }
                    restoredWorks++
                }

                for (entry in snapshot.progress) {
                    val target = resolveWork(entry.workId, entry.rj) ?: continue
                    val resolvedTrackId =
                        resolveTrackId(target.id, entry.trackId, entry.trackRelativePath)
                            ?: continue
                    val existing = library.dao.progress(target.id)
                    if (existing == null || entry.updatedAt >= existing.updatedAt) {
                        library.dao.saveProgressIfValid(
                            target.id,
                            resolvedTrackId,
                            entry.positionMs.coerceAtLeast(0L),
                            entry.updatedAt,
                        )
                        restoredProgress++
                    }
                }

                for (entry in snapshot.bookmarks) {
                    val target = resolveWork(entry.workId, entry.rj) ?: continue
                    val resolvedTrackId =
                        resolveTrackId(target.id, entry.trackId, entry.trackRelativePath)
                            ?: continue
                    library.dao.saveBookmark(
                        BookmarkEntity(
                            id = entry.id,
                            workId = target.id,
                            trackId = resolvedTrackId,
                            positionMs = entry.positionMs.coerceAtLeast(0L),
                            title = entry.title,
                            endMs = entry.endMs,
                        ),
                    )
                    restoredBookmarks++
                }

                for (entry in snapshot.trackStates) {
                    val target = resolveWork(entry.workId, entry.rj) ?: continue
                    val resolvedTrackId =
                        resolveTrackId(target.id, entry.trackId, entry.trackRelativePath)
                            ?: continue
                    val primaryId =
                        resolveAttachmentId(
                            target.id,
                            entry.primarySubtitleId,
                            entry.primarySubtitlePath,
                        )
                    val secondaryId =
                        resolveAttachmentId(
                                target.id,
                                entry.secondarySubtitleId,
                                entry.secondarySubtitlePath,
                            )
                            ?.takeIf { it != primaryId }
                    library.dao.upsertTrackStateIfValid(
                        trackId = resolvedTrackId,
                        positionMs = entry.positionMs.coerceAtLeast(0L),
                        offsetMs = entry.subtitleOffsetMs.coerceIn(-10_000L, 10_000L),
                        primaryId = primaryId,
                        secondaryId = secondaryId,
                        updatedAt = entry.updatedAt.coerceAtLeast(0L),
                    )
                }
            }
            BackupRestoreSummary(restoredWorks, restoredProgress, restoredBookmarks)
        }
    }
}
