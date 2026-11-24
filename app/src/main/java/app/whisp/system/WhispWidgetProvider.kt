// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.whisp.MainActivity
import app.whisp.R
import app.whisp.WhispApp
import app.whisp.playback.SleepTimer
import app.whisp.ui.shortTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight dark-themed home screen widget for one-tap bedside resume and sleep-timer control.
 * Honors [Settings.hideArtwork] and [Settings.appLockEnabled] privacy toggles.
 */
class WhispWidgetProvider : AppWidgetProvider() {
    companion object {
        const val ACTION_TOGGLE_PLAY = "app.whisp.widget.TOGGLE_PLAY"
        const val ACTION_EXTEND_TIMER = "app.whisp.widget.EXTEND_TIMER"

        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids =
                manager.getAppWidgetIds(ComponentName(context, WhispWidgetProvider::class.java))
            if (ids.isEmpty()) return
            updateWidgetsAsync(context, manager, ids)
        }

        private fun updateWidgetsAsync(
            context: Context,
            manager: AppWidgetManager,
            appWidgetIds: IntArray,
        ) {
            val app = context.applicationContext as? WhispApp ?: return
            val graph = app.graph
            graph.scope.launch {
                val settings = graph.settings.settings.value
                val state = graph.playback.state.value
                val privateMode = settings.hideArtwork || settings.appLockEnabled
                val remaining = SleepTimer.remainingMinutes(state.timerEnd)

                val (title, subtitle) =
                    if (privateMode) {
                        val t = if (state.playWhenReady) "正在收听" else "Whisp"
                        val s =
                            when {
                                remaining > 0 -> "助眠定时剩余约 $remaining 分钟"
                                state.timerStopAfterTrack -> "播完当前曲目后停止"
                                state.playWhenReady -> "点击控制播放"
                                else -> "轻触继续收听"
                            }
                        t to s
                    } else {
                        val resolved =
                            withContext(Dispatchers.IO) {
                                val activeTrack = state.trackId?.let { graph.library.dao.track(it) }
                                val work =
                                    activeTrack?.let { graph.library.dao.work(it.workId) }
                                        ?: graph.library.dao.latestProgress()?.let {
                                            graph.library.dao.work(it.workId)
                                        }
                                work?.let {
                                    shortTitle(it.title).ifBlank { it.title } to it.artist
                                }
                            }
                        val t = resolved?.first ?: "Whisp"
                        val s =
                            when {
                                remaining > 0 -> "助眠剩余约 $remaining 分钟"
                                state.timerStopAfterTrack -> "播完当前曲目后停止"
                                !resolved?.second.isNullOrBlank() -> resolved.second
                                state.playWhenReady -> "正在播放"
                                else -> "轻触继续收听"
                            }
                        t to s
                    }

                val openPending =
                    PendingIntent.getActivity(
                        context,
                        201,
                        Intent(context, MainActivity::class.java)
                            .setAction(ExternalIntentParser.ACTION_OPEN_PLAYER)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            ),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val togglePending =
                    PendingIntent.getBroadcast(
                        context,
                        202,
                        Intent(context, WhispWidgetProvider::class.java)
                            .setAction(ACTION_TOGGLE_PLAY),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val timerPending =
                    PendingIntent.getBroadcast(
                        context,
                        203,
                        Intent(context, WhispWidgetProvider::class.java)
                            .setAction(ACTION_EXTEND_TIMER),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )

                withContext(Dispatchers.Main.immediate) {
                    appWidgetIds.forEach { widgetId ->
                        val views = RemoteViews(context.packageName, R.layout.widget_whisp_player)
                        views.setTextViewText(R.id.widget_title, title)
                        views.setTextViewText(R.id.widget_subtitle, subtitle)
                        views.setTextViewText(
                            R.id.widget_play_btn,
                            if (state.playWhenReady) "暂停" else "播放",
                        )
                        views.setTextViewText(
                            R.id.widget_timer_btn,
                            if (remaining > 0) "+15分" else "定时30分",
                        )
                        views.setOnClickPendingIntent(R.id.widget_root, openPending)
                        views.setOnClickPendingIntent(R.id.widget_play_btn, togglePending)
                        views.setOnClickPendingIntent(R.id.widget_timer_btn, timerPending)
                        manager.updateAppWidget(widgetId, views)
                    }
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateWidgetsAsync(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as? WhispApp ?: return
        when (intent.action) {
            ACTION_TOGGLE_PLAY -> {
                app.graph.playback.togglePlayFromSystem()
                requestUpdate(context)
            }
            ACTION_EXTEND_TIMER -> {
                val state = app.graph.playback.state.value
                val remaining = SleepTimer.remainingMinutes(state.timerEnd)
                if (remaining > 0) app.graph.playback.extendTimer(15)
                else app.graph.playback.startSleepTimerFromSystem(30)
                requestUpdate(context)
            }
        }
    }
}
