// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi

/** DSP audio offload keeps screen-off playback off the application processor. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object AudioOffload {
    fun supported(sdkInt: Int): Boolean = sdkInt >= 29

    /** Loop polling and the sleep timer need Handler deadlines that stall in offload sleep. */
    fun needsPreciseDeadline(loopStart: Long, loopEnd: Long, timerEnd: Long): Boolean =
        (loopStart >= 0 && loopEnd > loopStart) || timerEnd > 0

    fun preferences(enabled: Boolean = true): TrackSelectionParameters.AudioOffloadPreferences =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (enabled) {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                } else {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                },
            )
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(false)
            .build()
}
