// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.room.withTransaction

/**
 * Shared atomic merge transaction for online catalog works (DLsite & ASMR.ONE). Avoids duplicate
 * transaction code and deletes stale tracks/attachments in bounded chunks so works with >999 files
 * never hit SQLite's variable limit.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OnlineWorkMerger(private val repository: LibraryRepository) {
    suspend fun mergeOnlineWork(
        sourceId: String,
        expectedKind: SourceKind,
        workId: String,
        freshWork: WorkEntity,
        indexed: IndexedLibrary,
        catalogArtistFallback: String = "",
        metadata: DlsiteDetails? = null,
        extraTransactionWork: suspend (savedWork: WorkEntity, latestSource: SourceEntity) -> Unit =
            { _, _ ->
            },
    ): List<String> {
        var obsoleteTracks = emptyList<String>()
        repository.database.withTransaction {
            val latestSource = repository.dao.source(sourceId) ?: error("来源已移除")
            require(latestSource.kindType == expectedKind) { "来源已移除" }
            val old = repository.dao.details(workId)
            val savedWork =
                old?.work?.let {
                    freshWork.copy(
                        title = if (it.title != it.originalTitle) it.title else freshWork.title,
                        artist =
                            if (it.artist != it.originalArtist.ifBlank { catalogArtistFallback })
                                it.artist
                            else freshWork.artist,
                        favorite = it.favorite,
                        coverId =
                            it.coverId?.takeIf { cover ->
                                cover == stableId("${it.id}|custom-cover")
                            } ?: freshWork.coverId,
                        addedAt = it.addedAt,
                        offlineAttachments = it.offlineAttachments,
                    )
                } ?: freshWork
            val retainedWork =
                savedWork.copy(
                    offlineAttachments =
                        savedWork.offlineAttachments &&
                            repository.offlineFilesPresent(savedWork, indexed.attachments),
                )
            repository.dao.putWorks(listOf(retainedWork))
            repository.dao.deleteWorkSourceLocations(workId, sourceId)
            val oldTracks = old?.tracks.orEmpty().associateBy { it.id }
            val pinnedTracks =
                oldTracks.values
                    .filter {
                        repository.downloadManager.downloadIndex.getDownload(it.id)?.state ==
                            Download.STATE_COMPLETED
                    }
                    .map { it.id }
            val keepTracks = (indexed.tracks.map { it.id } + pinnedTracks).toSet()
            val existingTrackIds = repository.dao.trackIdsForWork(workId)
            obsoleteTracks = existingTrackIds.filter { it !in keepTracks }
            obsoleteTracks.chunked(500).forEach { repository.dao.deleteTracks(it) }
            repository.dao.putTracks(
                indexed.tracks.map { track ->
                    track.copy(
                        durationMs =
                            track.durationMs.takeIf { it > 0 }
                                ?: oldTracks[track.id]?.durationMs
                                ?: 0,
                    )
                },
            )
            val pinnedAttachments =
                old?.attachments
                    .orEmpty()
                    .filter { repository.offlineFile(it.id) != null }
                    .map {
                        it.id
                    }
            val keepAttachments = (indexed.attachments.map { it.id } + pinnedAttachments).toSet()
            val staleAttachments =
                repository.dao.attachmentIdsForWork(workId).filter { it !in keepAttachments }
            staleAttachments.chunked(500).forEach { repository.dao.deleteAttachments(it) }
            repository.dao.putAttachments(indexed.attachments)
            repository.dao.putLocations(indexed.locations)
            metadata?.let { fresh ->
                val previous = decodeDlsiteDetails(repository.dao.dlsiteMetadata(workId)?.snapshot)
                repository.dao.putDlsiteMetadata(
                    DlsiteMetadataEntity(workId, fresh.preserving(previous).encode()),
                )
            }
            extraTransactionWork(retainedWork, latestSource)
        }
        return obsoleteTracks
    }
}
