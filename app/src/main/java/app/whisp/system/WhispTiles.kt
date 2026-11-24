// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.system

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.whisp.WhispApp
import app.whisp.playback.SleepTimer

/** Quick Settings tile to cycle a bedtime sleep timer (30m -> 60m -> Off) without unlocking. */
class WhispSleepTimerTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val graph = (application as WhispApp).graph
        val state = graph.playback.state.value
        val remaining = SleepTimer.remainingMinutes(state.timerEnd)
        val nextMinutes =
            when {
                remaining <= 0 -> 30
                remaining <= 35 -> 60
                else -> 0
            }
        graph.playback.startSleepTimerFromSystem(nextMinutes)
        updateTileUi(nextMinutes)
    }

    private fun syncTile() {
        val state = (application as WhispApp).graph.playback.state.value
        val remaining = SleepTimer.remainingMinutes(state.timerEnd)
        updateTileUi(remaining)
    }

    private fun updateTileUi(minutes: Int) {
        val tile = qsTile ?: return
        tile.state = if (minutes > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Whisp 定时"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (minutes > 0) "剩余约 $minutes 分钟" else "点击开启 30 分钟"
        }
        tile.updateTile()
    }
}

/** Quick Settings tile to resume or pause the latest Whisp playback directly from shade. */
class WhispResumeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val graph = (application as WhispApp).graph
        val wasPlaying = graph.playback.state.value.playWhenReady
        graph.playback.togglePlayFromSystem()
        updateTileUi(!wasPlaying)
    }

    private fun syncTile() {
        val playing = (application as WhispApp).graph.playback.state.value.playWhenReady
        updateTileUi(playing)
    }

    private fun updateTileUi(playing: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (playing) "正在收听" else "继续收听"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (playing) "点击暂停" else "Whisp"
        }
        tile.updateTile()
    }
}
