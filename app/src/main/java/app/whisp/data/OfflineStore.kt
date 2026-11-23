// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.content.Context
import android.net.Uri
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
import app.whisp.playback.OfflineService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Offline downloads and attachments, behind the repository facade. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OfflineStore(private val repository: LibraryRepository) {
    private suspend fun withWorkLock(id: String, block: suspend (WorkDetails) -> Unit) {
        repository.withWorkLock(id) {
            val detail = repository.dao.details(id) ?: return@withWorkLock
            block(detail)
        }
    }

    fun removeTrackDownload(id: String) {
        DownloadService.sendRemoveDownload(
            repository.context,
            OfflineService::class.java,
            id,
            false,
        )
    }

    suspend fun downloadWork(id: String) =
        withWorkLock(id) { detail ->
            require(detail.tracks.isNotEmpty()) { "作品没有音轨" }
            for (track in detail.tracks) {
                val uri = repository.resolve(track.id)
                val request =
                    DownloadRequest.Builder(track.id, uri.toUri())
                        .setMimeType(MediaFormats.videoMimeType(track.fileName))
                        .setCustomCacheKey(track.id)
                        .build()
                DownloadService.sendAddDownload(
                    repository.context,
                    OfflineService::class.java,
                    request,
                    false,
                )
            }
            WorkManager.getInstance(repository.context)
                .enqueueUniqueWork(
                    "attachments-$id",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AttachmentsWorker>()
                        .setInputData(workDataOf("workId" to id))
                        .build(),
                )
        }

    suspend fun downloadAttachments(id: String) =
        withWorkLock(id) { detail ->
            detail.attachments.forEach { attachment ->
                currentCoroutineContext().ensureActive()
                if (repository.offlineFile(attachment.id) == null)
                    atomicFile(
                        File(repository.offlineDir, attachment.id),
                        repository.readAsset(attachment.id, 64 * 1024 * 1024),
                    )
            }
            currentCoroutineContext().ensureActive()
            detail.work.coverId?.let { cover ->
                val target = File(repository.offlineDir, cover)
                if (!target.isFile) {
                    val custom =
                        File(repository.customArtworkDir, cover).takeIf { it.isFile }
                            ?: File(repository.artworkDir, cover).takeIf {
                                cover == stableId("${detail.work.id}|custom-cover") && it.isFile
                            }
                    atomicFile(
                        target,
                        custom?.readBytes() ?: repository.readAsset(cover, 16 * 1024 * 1024),
                    )
                }
            }
            repository.dao.attachmentsReady(id, true)
            repository.storageChanged()
        }

    suspend fun setCover(work: WorkEntity, uri: Uri) =
        withContext(Dispatchers.IO) {
            repository.withWorkLock(work.id) {
                val latest = repository.dao.work(work.id) ?: throw IOException("作品已删除")
                val id = stableId("${work.id}|custom-cover")
                val bytes =
                    repository.context.contentResolver.openInputStream(uri)?.use {
                        boundedRead(it, 16 * 1024 * 1024)
                    } ?: error("无法读取图片")
                atomicFile(File(repository.customArtworkDir, id), bytes)
                if (latest.offlineAttachments) atomicFile(File(repository.offlineDir, id), bytes)
                repository.dao.updateWorkCover(work.id, id)
                repository.artworkStore.notifyAssetChanged(id)
                latest.coverId?.let { repository.artworkStore.notifyAssetChanged(it) }
                repository.storageChanged()
            }
        }

    suspend fun removeOffline(id: String) {
        WorkManager.getInstance(repository.context).cancelUniqueWork("attachments-$id")
        withWorkLock(id) { detail ->
            detail.tracks.forEach { track -> removeTrackDownload(track.id) }
            detail.attachments.forEach { File(repository.offlineDir, it.id).delete() }
            detail.work.coverId?.let {
                if (!repository.dao.otherOfflineCover(it, id))
                    File(repository.offlineDir, it).delete()
            }
            repository.dao.attachmentsReady(id, false)
            repository.storageChanged()
        }
    }

    fun isOffline(detail: WorkDetails): Boolean =
        detail.work.offlineAttachments &&
            detail.tracks.all {
                repository.downloads.value[it.id]?.state == Download.STATE_COMPLETED
            }
}

class AttachmentsWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("workId") ?: return Result.failure()
        return try {
            (applicationContext as app.whisp.WhispApp)
                .repository
                .offlineStore
                .downloadAttachments(id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCountCountExceeded()) Result.failure() else Result.retry()
        }
    }

    private fun runAttemptCountCountExceeded(): Boolean = runAttemptCount >= 3
}
