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

/** Offline downloads, attachment persistence, and custom cover assignment. */
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

    suspend fun downloadWork(id: String) =
        withWork(id) { detail ->
            require(detail.tracks.isNotEmpty()) { "作品没有音轨" }
            val requests =
                detail.tracks.map { track ->
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
            detail.attachments.forEach { File(library.offlineDir, it.id).delete() }
            detail.work.coverId?.let {
                if (!library.dao.otherOfflineCover(it, id)) File(library.offlineDir, it).delete()
            }
            library.dao.attachmentsReady(id, false)
            if (detail.work.sourceId == null) {
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
            detail.tracks.all { media.downloads.value[it.id]?.state == Download.STATE_COMPLETED }
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
