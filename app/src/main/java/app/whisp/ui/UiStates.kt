// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.media3.common.Player
import app.whisp.data.DlsiteDetails
import app.whisp.playback.PlaybackErrorInfo
import app.whisp.playback.TimerStartAnchor

data class DlsiteUiState(
    val details: DlsiteDetails? = null,
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

data class PlaybackState(
    val trackId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val timerEnd: Long = 0,
    val timerStopAfterTrack: Boolean = false,
    val timerStartAnchor: TimerStartAnchor? = null,
    val loopStart: Long = -1,
    val loopEnd: Long = -1,
    val playWhenReady: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
    val errorInfo: PlaybackErrorInfo? = null,
    val queueIds: List<String> = emptyList(),
    val hasVideo: Boolean = false,
    val videoAspectRatio: Float = 16f / 9f,
    val tracksKnown: Boolean = false,
    val speed: Float = 1f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
)

internal fun playControlLabel(state: PlaybackState): String =
    if (state.playWhenReady) "暂停"
    else if (state.failed) "重试播放" else if (state.ended) "重新播放作品" else "播放"
