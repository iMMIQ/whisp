// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import app.whisp.R
import app.whisp.WhispApp

@androidx.annotation.OptIn(UnstableApi::class)
class OfflineService :
    DownloadService(
        2,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        "offline",
        R.string.download_channel,
        0,
    ) {
    private val media
        get() = (application as WhispApp).graph.media

    private val notificationHelper by lazy { DownloadNotificationHelper(this, "offline") }

    override fun getDownloadManager(): DownloadManager = media.downloadManager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, 1701)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification =
        notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_whisp,
            null,
            "离线保存",
            downloads,
            notMetRequirements,
        )

    override fun onTimeout(startId: Int, fgsType: Int) {
        media.downloadManager.pauseDownloads()
        stopSelf()
    }
}
