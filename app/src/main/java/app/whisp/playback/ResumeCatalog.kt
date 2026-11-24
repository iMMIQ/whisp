// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.whisp.data.*
import kotlinx.coroutines.flow.first

/**
 * System browsing reads indexed metadata and cached art only; it never opens a NAS connection.
 * Respects [Settings.hideArtwork] privacy by collapsing browsable catalogs to an anonymized item.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ResumeCatalog(
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val playback: PlaybackCoordinator,
) {
    companion object {
        const val ROOT = "whisp.root"
        const val RECENT_ROOT = "whisp.recent"
        const val FAVORITES_ROOT = "whisp.favorites"
        const val OFFLINE_ROOT = "whisp.offline"
        private const val BROWSE_LIMIT = 12
    }

    data class Snapshot(
        val work: WorkEntity,
        val tracks: List<TrackEntity>,
        val position: QueuePosition,
    )

    suspend fun snapshot(): Snapshot? {
        library.flushPlaybackProgress()
        val progress = library.dao.latestProgress() ?: return null
        return snapshotFor(progress.workId, progress)
    }

    private suspend fun snapshotFor(workId: String, progress: ProgressEntity? = null): Snapshot? {
        val detail = library.dao.details(workId) ?: return null
        val tracks =
            library.availableTracks(
                detail.work,
                TrackSections.queue(detail.tracks, progress?.trackId),
            )
        if (tracks.isEmpty()) return null
        val position =
            if (progress != null) PlaybackQueue.resume(tracks, progress) else QueuePosition(0, 0L)
        return Snapshot(detail.work, tracks, position)
    }

    suspend fun recentItem(snapshot: Snapshot): MediaItem {
        val hide = settings.settingsUpdates.first().hideArtwork
        val track = snapshot.tracks[snapshot.position.index]
        val metadata =
            playback.mediaMetadata(track, snapshot.work, hide).buildUpon().setArtworkUri(null)
        return MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).build()
    }

    suspend fun rootChildren(): List<MediaItem> {
        val hide = settings.settingsUpdates.first().hideArtwork
        if (hide) {
            val latest = snapshot() ?: return emptyList()
            return listOf(recentItem(latest))
        }
        return listOf(
            folderItem(RECENT_ROOT, "最近收听"),
            folderItem(FAVORITES_ROOT, "收藏作品"),
            folderItem(OFFLINE_ROOT, "离线作品"),
        )
    }

    suspend fun childrenFor(parentId: String): List<MediaItem> {
        val hide = settings.settingsUpdates.first().hideArtwork
        if (hide) {
            if (parentId != RECENT_ROOT && parentId != ROOT) return emptyList()
            val latest = snapshot() ?: return emptyList()
            return listOf(recentItem(latest))
        }
        return when (parentId) {
            ROOT -> rootChildren()
            RECENT_ROOT -> {
                library.flushPlaybackProgress()
                library.dao.recentProgress(BROWSE_LIMIT).mapNotNull { prog ->
                    snapshotFor(prog.workId, prog)?.let { recentItem(it) }
                }
            }
            FAVORITES_ROOT -> {
                library.dao.favoriteWorks(BROWSE_LIMIT).mapNotNull { work ->
                    val prog = library.dao.progress(work.id)
                    snapshotFor(work.id, prog)?.let { recentItem(it) }
                }
            }
            OFFLINE_ROOT -> {
                library.dao.offlineWorks(BROWSE_LIMIT).mapNotNull { work ->
                    val prog = library.dao.progress(work.id)
                    snapshotFor(work.id, prog)?.let { recentItem(it) }
                }
            }
            else -> emptyList()
        }
    }

    suspend fun itemFor(mediaId: String): MediaItem? {
        when (mediaId) {
            ROOT -> return folderItem(ROOT, "Whisp")
            RECENT_ROOT -> return folderItem(RECENT_ROOT, "最近收听")
            FAVORITES_ROOT -> return folderItem(FAVORITES_ROOT, "收藏作品")
            OFFLINE_ROOT -> return folderItem(OFFLINE_ROOT, "离线作品")
        }
        val track = library.dao.track(mediaId) ?: return null
        val work = library.dao.work(track.workId) ?: return null
        val hide = settings.settingsUpdates.first().hideArtwork
        val metadata = playback.mediaMetadata(track, work, hide).buildUpon().setArtworkUri(null)
        return MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).build()
    }

    private fun folderItem(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
}
