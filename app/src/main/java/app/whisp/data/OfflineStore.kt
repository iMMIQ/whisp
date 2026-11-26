// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.whisp.WhispApp
import app.whisp.playback.OfflineService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString

/**
 * Offline downloads (whole work, edition/section, or single track), attachment persistence,
 * external/cross-source subtitle import, and custom cover assignment.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class OfflineStore(
    private val context: Context,
    private val library: LibraryStore,
    private val media: MediaSourceRouting,
    private val images: WhispImages,
    private val detachTracks: (Set<String>) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private suspend fun withWork(id: String, block: suspend (WorkDetails) -> Unit) {
        library.withWorkLock(id) {
            val detail = library.dao.details(id) ?: return@withWorkLock
            block(detail)
        }
    }

    /**
     * Mutates the in-process [DownloadManager] directly so background cleanups (e.g. ScanWorker)
     * never fail with ForegroundServiceStartNotAllowedException on API 26–36.
     */
    fun removeTrackDownload(id: String) {
        onMain { media.downloadManager.removeDownload(id) }
    }

    fun resumeDownloads() {
        runCatching {
            DownloadService.sendResumeDownloads(context, OfflineService::class.java, true)
        }
            .onFailure { onMain { media.downloadManager.resumeDownloads() } }
    }

    fun pauseDownloads() {
        onMain { media.downloadManager.pauseDownloads() }
    }

    suspend fun downloadWork(id: String) = downloadTracks(id, null)

    /**
     * Downloads either all tracks ([trackIds] == null) or a chosen subset (e.g. MP3 edition only or
     * a single track) along with the work's cover and subtitle/script attachments.
     */
    suspend fun downloadTracks(id: String, trackIds: Collection<String>?) =
        withWork(id) { detail ->
            val targets =
                if (trackIds == null) detail.tracks
                else {
                    val wanted = trackIds.toSet()
                    detail.tracks.filter { it.id in wanted }
                }
            require(targets.isNotEmpty()) { "作品没有可下载音轨" }
            val requests = targets.map { track ->
                val uri = library.resolveTrackUri(track, detail.work)
                DownloadRequest.Builder(track.id, uri.toUri())
                    .setMimeType(MediaFormats.videoMimeType(track.fileName))
                    .setCustomCacheKey(track.id)
                    .build()
            }
            for (request in requests) {
                runCatching {
                    DownloadService.sendAddDownload(
                        context,
                        OfflineService::class.java,
                        request,
                        true,
                    )
                }
                    .onFailure { onMain { media.downloadManager.addDownload(request) } }
            }
            resumeDownloads()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "attachments-$id",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AttachmentsWorker>()
                        .setInputData(workDataOf("workId" to id))
                        .build(),
                )
        }

    suspend fun removeTrackOffline(workId: String, trackId: String) =
        withWork(workId) { detail ->
            removeTrackDownload(trackId)
            val remainingOffline =
                detail.tracks.any {
                    it.id != trackId &&
                        (media.isCompleted(it.id) || library.offlineFile(it.id) != null)
                }
            if (!remainingOffline) {
                library.dao.attachmentsReady(workId, false)
            }
            library.storageChanged()
        }

    suspend fun downloadAttachments(id: String) =
        withWork(id) { detail ->
            detail.attachments.forEach { attachment ->
                currentCoroutineContext().ensureActive()
                if (library.offlineFile(attachment.id) == null)
                    atomicFile(
                        File(library.offlineDir, attachment.id),
                        library.readAsset(attachment.id, 64 * 1024 * 1024),
                    )
            }
            currentCoroutineContext().ensureActive()
            detail.work.coverId?.let { cover ->
                val target = File(library.offlineDir, cover)
                if (!target.isFile) {
                    val custom = File(library.customArtworkDir, cover).takeIf { it.isFile }
                    atomicFile(
                        target,
                        custom?.readBytes() ?: library.readAsset(cover, 16 * 1024 * 1024),
                    )
                }
            }
            library.dao.attachmentsReady(id, true)
            library.storageChanged()
        }

    /**
     * Imports a user-selected external subtitle file (`.vtt`, `.srt`, `.lrc`, `.ass`, `.ssa`) via
     * SAF into durable app storage, registers it on [workId], and optionally selects it for
     * [trackId].
     */
    suspend fun importExternalSubtitle(
        workId: String,
        trackId: String?,
        uri: Uri,
        displayName: String,
    ): AttachmentEntity =
        withContext(Dispatchers.IO) {
            val cleanName =
                displayName.replace(Regex("[\\\\/\\u0000]"), "_").trim().ifBlank { "external.vtt" }
            val ext = MediaFormats.extension(cleanName)
            require(ext in MediaFormats.subtitles) {
                "仅支持 ${MediaFormats.subtitles.joinToString("/")} 字幕格式"
            }
            val bytes =
                context.contentResolver.openInputStream(uri)?.use {
                    boundedRead(it, 16 * 1024 * 1024)
                } ?: error("无法读取字幕文件")
            require(bytes.isNotEmpty()) { "字幕文件为空" }
            library.withWorkLock(workId) {
                library.dao.work(workId) ?: throw IOException("作品已删除")
                val digest = bytes.toByteString().sha256().substring(0, 6).hex()
                val relative = "_external/${digest}_$cleanName"
                val attachmentId = stableId("$workId|$relative")
                val targetFile = File(library.offlineDir, attachmentId)
                atomicFile(targetFile, bytes)
                val entity =
                    AttachmentEntity(
                        id = attachmentId,
                        workId = workId,
                        title = LibraryIndexer.trackTitle(cleanName),
                        fileName = cleanName,
                        relativePath = relative,
                        kind = "subtitle",
                        uri = Uri.fromFile(targetFile).toString(),
                        size = bytes.size.toLong(),
                    )
                library.database.withTransaction {
                    library.dao.putAttachments(listOf(entity))
                    if (trackId != null) {
                        val prev = library.dao.trackState(trackId)?.subtitleSelectionOrNull()
                        val nextSecondary = prev?.secondaryId?.takeIf { it != attachmentId }
                        library.dao.upsertTrackSubtitleSelection(
                            trackId = trackId,
                            primaryId = attachmentId,
                            secondaryId = nextSecondary,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
                library.storageChanged()
                entity
            }
        }

    /**
     * Copies all subtitle attachments from [sourceWorkId] (e.g. an ASMR.ONE or local sibling with
     * the same RJ/VJ code) onto [targetWorkId] into local offline storage so high-res NAS/DLsite
     * tracks can reuse community subtitles seamlessly.
     */
    suspend fun linkSiblingSubtitles(targetWorkId: String, sourceWorkId: String): Int =
        withContext(Dispatchers.IO) {
            require(targetWorkId != sourceWorkId) { "不能关联同一作品" }
            val sourceDetail = library.dao.details(sourceWorkId) ?: throw IOException("来源作品不存在")
            val subtitles = sourceDetail.attachments.filter { it.kind == "subtitle" }
            if (subtitles.isEmpty()) return@withContext 0
            val downloaded = subtitles.mapNotNull { sub ->
                currentCoroutineContext().ensureActive()
                runCatching { sub to library.readAsset(sub.id, 16 * 1024 * 1024) }.getOrNull()
            }
            if (downloaded.isEmpty()) return@withContext 0
            library.withWorkLock(targetWorkId) {
                val targetDetail = library.dao.details(targetWorkId) ?: throw IOException("目标作品已删除")
                val newAttachments = mutableListOf<AttachmentEntity>()
                for ((sub, bytes) in downloaded) {
                    val relative = "_linked/${sub.relativePath}"
                    val newId = stableId("$targetWorkId|$relative")
                    val file = File(library.offlineDir, newId)
                    atomicFile(file, bytes)
                    newAttachments +=
                        AttachmentEntity(
                            id = newId,
                            workId = targetWorkId,
                            title = sub.title,
                            fileName = sub.fileName,
                            relativePath = relative,
                            kind = "subtitle",
                            uri = Uri.fromFile(file).toString(),
                            size = bytes.size.toLong(),
                        )
                }
                val mergedAttachments = targetDetail.attachments + newAttachments
                val updatedTracks =
                    targetDetail.tracks.map { track ->
                        if (track.subtitleId != null) track
                        else {
                            val best =
                                LibraryIndexer.subtitleCandidates(track, mergedAttachments)
                                    .firstOrNull { candidate ->
                                        candidate.id in newAttachments.mapTo(HashSet()) { it.id } &&
                                            (LibraryIndexer.subtitleBaseStem(candidate.fileName)
                                                .equals(
                                                    LibraryIndexer.subtitleBaseStem(track.fileName),
                                                    ignoreCase = true,
                                                ) ||
                                                (track.order < 9999 &&
                                                    LibraryIndexer.number(candidate.fileName) ==
                                                        track.order))
                                    }
                            if (best != null) track.copy(subtitleId = best.id) else track
                        }
                    }
                library.database.withTransaction {
                    library.dao.putAttachments(newAttachments)
                    library.dao.putTracks(updatedTracks)
                }
                library.storageChanged()
                newAttachments.size
            }
        }

    suspend fun setCover(work: WorkEntity, uri: Uri) =
        withContext(Dispatchers.IO) {
            library.withWorkLock(work.id) {
                val latest = library.dao.work(work.id) ?: throw IOException("作品已删除")
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use {
                        boundedRead(it, 16 * 1024 * 1024)
                    } ?: error("无法读取图片")
                // Content-addressed ID invalidates Room & Coil automatically without revision
                // flows.
                val digest = bytes.toByteString().sha256().substring(0, 8).hex()
                val newId = stableId("${work.id}|custom-cover|$digest")
                atomicFile(File(library.customArtworkDir, newId), bytes)
                if (latest.offlineAttachments) atomicFile(File(library.offlineDir, newId), bytes)
                library.dao.updateWorkCover(work.id, newId)
                latest.coverId
                    ?.takeIf { it != newId }
                    ?.let { previous ->
                        if (!library.dao.otherCover(previous, work.id)) {
                            File(library.customArtworkDir, previous).delete()
                            images.removeFromMemoryAndDisk(previous)
                        }
                        if (!library.dao.otherOfflineCover(previous, work.id)) {
                            File(library.offlineDir, previous).delete()
                        }
                    }
                library.storageChanged()
            }
        }

    suspend fun removeOffline(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("attachments-$id")
        withWork(id) { detail ->
            detail.tracks.forEach { removeTrackDownload(it.id) }
            detail.attachments
                .filterNot {
                    it.relativePath.startsWith("_external/") ||
                        it.relativePath.startsWith("_linked/")
                }
                .forEach { File(library.offlineDir, it.id).delete() }
            detail.work.coverId?.let {
                if (!library.dao.otherOfflineCover(it, id)) File(library.offlineDir, it).delete()
            }
            library.dao.attachmentsReady(id, false)
            if (detail.work.sourceId == null) {
                detail.attachments.forEach { File(library.offlineDir, it.id).delete() }
                val trackIds = detail.tracks.map { it.id }.toSet()
                if (trackIds.isNotEmpty()) {
                    withContext(Dispatchers.Main.immediate) { detachTracks(trackIds) }
                }
                library.dao.deleteWork(id)
                images.purgeWorkAssets(
                    id,
                    (trackIds +
                            detail.attachments.map { it.id } +
                            listOfNotNull(detail.work.coverId))
                        .toSet(),
                )
            }
            library.storageChanged()
        }
    }

    fun isOffline(detail: WorkDetails): Boolean =
        detail.work.offlineAttachments &&
            detail.tracks.isNotEmpty() &&
            detail.tracks.all { media.downloads.value[it.id]?.state == Download.STATE_COMPLETED }

    fun downloadedTrackCount(detail: WorkDetails): Int =
        detail.tracks.count { media.downloads.value[it.id]?.state == Download.STATE_COMPLETED }
}

class AttachmentsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("workId") ?: return Result.failure()
        return try {
            (applicationContext as WhispApp).graph.offline.downloadAttachments(id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }
}
