// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.*
import org.junit.Test

class AudioOffloadTest {
    @Test
    fun offloadRequiresApi29() {
        assertFalse(AudioOffload.supported(26))
        assertFalse(AudioOffload.supported(28))
        assertTrue(AudioOffload.supported(29))
        assertTrue(AudioOffload.supported(35))
    }

    @Test
    fun preferencesStayOffloadFirst() {
        val preferences = AudioOffload.preferences()
        assertEquals(
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            preferences.audioOffloadMode,
        )
        assertFalse(preferences.isGaplessSupportRequired)
        assertFalse(preferences.isSpeedChangeSupportRequired)
        assertTrue(AudioOffload.preferences(gaplessRequired = true).isGaplessSupportRequired)
    }

    @Test
    fun whispLoadControlProvidesBurstHysteresis() {
        assertNotNull(WhispLoadControl.create())
        assertTrue(
            WhispLoadControl.STREAMING_MAX_BUFFER_MS > WhispLoadControl.STREAMING_MIN_BUFFER_MS,
        )
        assertTrue(WhispLoadControl.LOCAL_MAX_BUFFER_MS > WhispLoadControl.LOCAL_MIN_BUFFER_MS)
        assertTrue(WhispLoadControl.TARGET_BUFFER_BYTES >= 16 * 1024 * 1024)
    }

    @Test
    fun preciseDeadlinesDisableOffload() {
        assertEquals(
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
            AudioOffload.preferences(false).audioOffloadMode,
        )
    }

    @Test
    fun loopOrTimerNeedsPreciseDeadline() {
        assertTrue(AudioOffload.needsPreciseDeadline(1_000, 5_000, 0))
        assertTrue(AudioOffload.needsPreciseDeadline(-1, -1, 1_000_000))
        assertFalse(AudioOffload.needsPreciseDeadline(-1, -1, 0))
        assertFalse(AudioOffload.needsPreciseDeadline(5_000, 5_000, 0))
        assertTrue(
            AudioOffload.preferences(enabled = true, speedChangeRequired = true)
                .isSpeedChangeSupportRequired,
        )
    }
}
