// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.media3.common.Player
import app.whisp.data.AsmrPreview
import app.whisp.data.AsmrWork
import app.whisp.data.DlsiteDetails

data class DlsiteUiState(
    val details: DlsiteDetails? = null,
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

data class AsmrPreviewState(
    val sourceId: String = "",
    val seed: AsmrWork? = null,
    val preview: AsmrPreview? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class PlaybackState(
    val trackId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val timerEnd: Long = 0,
    val loopStart: Long = -1,
    val loopEnd: Long = -1,
    val playWhenReady: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
    val queueIds: List<String> = emptyList(),
    val hasVideo: Boolean = false,
    val videoAspectRatio: Float = 16f / 9f,
    val tracksKnown: Boolean = false,
    val speed: Float = 1f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

internal fun playControlLabel(state: PlaybackState): String =
    if (state.playWhenReady) "暂停"
    else if (state.failed) "重试播放" else if (state.ended) "重新播放作品" else "播放"
