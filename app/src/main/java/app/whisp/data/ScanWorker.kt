// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.whisp.MainActivity
import app.whisp.R
import app.whisp.WhispApp
import kotlinx.coroutines.CancellationException

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "library-scan"
        applicationContext
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(channel, "作品扫描", NotificationManager.IMPORTANCE_LOW),
            )
        val open =
            PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_whisp)
                .setContentTitle("正在扫描作品库")
                .setContentIntent(open)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    0,
                    "停止",
                    WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
                )
                .build()
        val notificationId = 1000 + (id.hashCode() and 0x0fffffff)
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        else ForegroundInfo(notificationId, notification)
    }

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString("sourceId") ?: return Result.failure()
        val graph = (applicationContext as WhispApp).graph
        if (graph.library.dao.source(sourceId) == null) return Result.success()
        var scanStarted = false
        return try {
            try {
                setForeground(getForegroundInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // Foreground service start can be disallowed for background/out-of-quota workers
                // on API 31+; continue scanning inside the WorkManager JobScheduler window.
            }
            scanStarted = true
            graph.sync.scan(sourceId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!scanStarted)
                graph.library.dao.source(sourceId)?.let { source ->
                    if (source.error == null)
                        graph.library.dao.updateSourceError(source.id, "扫描未完成，请重新刷新")
                }
            Result.failure()
        }
    }
}
