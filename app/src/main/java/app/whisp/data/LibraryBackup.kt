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
    val positionMs: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupBookmark(
    val id: String,
    val workId: String,
    val rj: String? = null,
    val trackId: String,
    val positionMs: Long,
    val title: String,
    val endMs: Long? = null,
)

/**
 * Privacy-preserving local backup snapshot for SAF export/import. Strictly excludes source
 * credentials, passwords, cookies, and raw remote paths.
 */
@Serializable
data class WhispBackupSnapshot(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: Settings = Settings(),
    val trackSubtitleOffsets: Map<String, Long> = emptyMap(),
    val trackSubtitleSelections: Map<String, TrackSubtitleSelection> = emptyMap(),
    val trackPositions: Map<String, Long> = emptyMap(),
    val deviceAudioProfiles: Map<String, DeviceAudioProfile> = emptyMap(),
    val works: List<BackupWorkCustomization> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
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
        require(decoded.version in 1..2) { "不支持的备份文件版本（v${decoded.version}）" }
        return decoded
    }

    suspend fun exportSnapshot(
        library: LibraryStore,
        settingsStore: SettingsStore,
    ): WhispBackupSnapshot =
        withContext(Dispatchers.IO) {
            library.flushPlaybackProgress()
            val settings = settingsStore.settingsUpdates.first()
            val allWorks = library.dao.allWorks()
            val rjByWorkId = allWorks.associate { it.id to it.rj }
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
                        positionMs = b.positionMs,
                        title = b.title,
                        endMs = b.endMs,
                    )
                }
            WhispBackupSnapshot(
                version = 1,
                exportedAt = System.currentTimeMillis(),
                settings = settings,
                trackSubtitleOffsets = settingsStore.trackSubtitleOffsets.value,
                trackSubtitleSelections = settingsStore.trackSubtitleSelections.value,
                trackPositions = settingsStore.trackPositions.value,
                deviceAudioProfiles = settingsStore.deviceAudioProfiles.value,
                works = customizedWorks,
                progress = progressList,
                bookmarks = bookmarkList,
            )
        }

    suspend fun importSnapshot(
        snapshot: WhispBackupSnapshot,
        library: LibraryStore,
        settingsStore: SettingsStore,
    ): BackupRestoreSummary {
        settingsStore.replaceBackupMaps(
            settings = snapshot.settings,
            subtitleOffsets = snapshot.trackSubtitleOffsets,
            subtitleSelections = snapshot.trackSubtitleSelections,
            trackPositions = snapshot.trackPositions,
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

                fun resolveWork(workId: String, rj: String?): WorkEntity? =
                    byId[workId] ?: rj?.uppercase()?.let(byRj::get)

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
                    val tracks = library.dao.tracksForWork(target.id)
                    if (tracks.any { it.id == entry.trackId }) {
                        val existing = library.dao.progress(target.id)
                        if (existing == null || entry.updatedAt >= existing.updatedAt) {
                            library.dao.saveProgressIfValid(
                                target.id,
                                entry.trackId,
                                entry.positionMs.coerceAtLeast(0L),
                                entry.updatedAt,
                            )
                            restoredProgress++
                        }
                    }
                }

                for (entry in snapshot.bookmarks) {
                    val target = resolveWork(entry.workId, entry.rj) ?: continue
                    val tracks = library.dao.tracksForWork(target.id)
                    if (tracks.any { it.id == entry.trackId }) {
                        library.dao.saveBookmark(
                            BookmarkEntity(
                                id = entry.id,
                                workId = target.id,
                                trackId = entry.trackId,
                                positionMs = entry.positionMs.coerceAtLeast(0L),
                                title = entry.title,
                                endMs = entry.endMs,
                            ),
                        )
                        restoredBookmarks++
                    }
                }
            }
            BackupRestoreSummary(restoredWorks, restoredProgress, restoredBookmarks)
        }
    }
}
