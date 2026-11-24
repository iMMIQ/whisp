// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.whisp.AppGraph
import app.whisp.MainActivity
import app.whisp.R
import app.whisp.ui.shortTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes privacy-aware dynamic Android App Shortcuts (Resume, 30m Sleep Timer, Storage).
 * Automatically anonymizes titles whenever [Settings.hideArtwork] or [Settings.appLockEnabled] is
 * enabled.
 */
object WhispShortcuts {
    const val ID_RESUME = "whisp_resume"
    const val ID_SLEEP_30 = "whisp_sleep_30"
    const val ID_STORAGE = "whisp_storage"

    fun reportUsed(context: Context, shortcutId: String) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching { manager.reportShortcutUsed(shortcutId) }
    }

    suspend fun refresh(context: Context, graph: AppGraph) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val settings = graph.settings.settings.value
        val privateMode = settings.hideArtwork || settings.appLockEnabled
        val latestTitle =
            if (privateMode) null
            else
                withContext(Dispatchers.IO) {
                    val progress = graph.library.dao.latestProgress() ?: return@withContext null
                    val work = graph.library.dao.work(progress.workId) ?: return@withContext null
                    shortTitle(work.title).ifBlank { work.title }.take(24)
                }

        val icon = Icon.createWithResource(context, R.drawable.ic_whisp)
        val resumeShortcut =
            ShortcutInfo.Builder(context, ID_RESUME)
                .setShortLabel("继续收听")
                .setLongLabel(latestTitle?.let { "继续收听 · $it" } ?: "继续收听上次作品")
                .setIcon(icon)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ExternalIntentParser.ACTION_OPEN_PLAYER)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                .build()

        val sleepShortcut =
            ShortcutInfo.Builder(context, ID_SLEEP_30)
                .setShortLabel("助眠定时")
                .setLongLabel("开启 30 分钟助眠定时")
                .setIcon(icon)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ExternalIntentParser.ACTION_START_SLEEP_TIMER)
                        .putExtra(ExternalIntentParser.EXTRA_MINUTES, 30)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                .build()

        val storageShortcut =
            ShortcutInfo.Builder(context, ID_STORAGE)
                .setShortLabel("存储与缓存")
                .setLongLabel("查看离线下载与在线缓存")
                .setIcon(icon)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(ExternalIntentParser.ACTION_OPEN_STORAGE)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                .build()

        runCatching {
            manager.dynamicShortcuts = listOf(resumeShortcut, sleepShortcut, storageShortcut)
        }
    }
}
