// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.whisp.data.*
import kotlinx.coroutines.flow.first

/** System browsing reads indexed metadata and cached art only; it never opens a NAS connection. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ResumeCatalog(
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val playback: PlaybackCoordinator,
) {
    data class Snapshot(
        val work: WorkEntity,
        val tracks: List<TrackEntity>,
        val position: QueuePosition,
    )

    suspend fun snapshot(): Snapshot? {
        library.flushPlaybackProgress()
        val progress = library.dao.latestProgress() ?: return null
        val detail = library.dao.details(progress.workId) ?: return null
        val tracks =
            library.availableTracks(
                detail.work,
                TrackSections.queue(detail.tracks, progress.trackId),
            )
        if (tracks.isEmpty()) return null
        return Snapshot(detail.work, tracks, PlaybackQueue.resume(tracks, progress))
    }

    suspend fun recentItem(snapshot: Snapshot): MediaItem {
        val hide = settings.settingsUpdates.first().hideArtwork
        val track = snapshot.tracks[snapshot.position.index]
        val metadata =
            playback.mediaMetadata(track, snapshot.work, hide).buildUpon().setArtworkUri(null)
        return MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).build()
    }
}
