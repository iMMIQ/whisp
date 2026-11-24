// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.PlayerMessage
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** DSP audio offload policy: enabled overnight, disabled only when precise AP deadlines exist. */
@androidx.annotation.OptIn(UnstableApi::class)
object AudioOffload {
    fun supported(sdkInt: Int): Boolean = sdkInt >= 29

    fun preferences(
        enabled: Boolean = true,
        speedChangeRequired: Boolean = false,
        gaplessRequired: Boolean = false,
    ): AudioOffloadPreferences =
        if (enabled)
            AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsGaplessSupportRequired(gaplessRequired)
                .setIsSpeedChangeSupportRequired(speedChangeRequired)
                .build()
        else AudioOffloadPreferences.DEFAULT

    fun needsPreciseDeadline(loopStart: Long, loopEnd: Long, timerEnd: Long): Boolean =
        (loopStart >= 0 && loopEnd > loopStart) || timerEnd > 0

    internal fun apply(player: ExoPlayer, loop: AbLoopController, timer: SleepTimerController) {
        if (!supported(Build.VERSION.SDK_INT)) return
        val speedChanged = abs(player.playbackParameters.speed - 1f) > 0.01f
        val enabled = !needsPreciseDeadline(loop.startMs, loop.endMs, timer.handlerDeadlineMs)
        val desired = preferences(enabled, speedChanged)
        if (player.trackSelectionParameters.audioOffloadPreferences == desired) return
        player.trackSelectionParameters =
            player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(desired).build()
    }
}

/**
 * Burst-friendly LoadControl with wide hysteresis (25s..180s streaming, 15s..60s local) and a 32
 * MiB memory cap so audio streams fetch in long bursts and let Wi-Fi/cellular radios sleep, while
 * keeping video memory bounded.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object WhispLoadControl {
    const val STREAMING_MIN_BUFFER_MS = 25_000
    const val STREAMING_MAX_BUFFER_MS = 180_000
    const val STREAMING_PLAYBACK_MS = 1_500
    const val STREAMING_REBUFFER_MS = 2_500
    const val LOCAL_MIN_BUFFER_MS = 15_000
    const val LOCAL_MAX_BUFFER_MS = 60_000
    const val LOCAL_PLAYBACK_MS = 1_000
    const val LOCAL_REBUFFER_MS = 1_500
    const val TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    fun create(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                STREAMING_MIN_BUFFER_MS,
                STREAMING_MAX_BUFFER_MS,
                STREAMING_PLAYBACK_MS,
                STREAMING_REBUFFER_MS,
            )
            .setBufferDurationsMsForLocalPlayback(
                LOCAL_MIN_BUFFER_MS,
                LOCAL_MAX_BUFFER_MS,
                LOCAL_PLAYBACK_MS,
                LOCAL_REBUFFER_MS,
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(false)
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
            .build()
}

object SleepTimer {
    fun deadlineMs(nowMs: Long, minutes: Int): Long =
        if (minutes <= 0) 0 else nowMs + minutes.coerceAtMost(180) * 60_000L

    fun remainingMinutes(
        deadlineMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Int =
        if (deadlineMs > nowElapsedMs)
            kotlin.math.ceil((deadlineMs - nowElapsedMs) / 60_000.0).toInt()
        else 0
}

data class LoopState(val startMs: Long, val endMs: Long) {
    companion object {
        fun validate(
            startMs: Long,
            endMs: Long,
            durationMs: Long?,
            hasTrack: Boolean,
        ): LoopState? {
            if (!hasTrack || startMs < 0) return null
            val clampedEnd = durationMs?.let { endMs.coerceAtMost(it) } ?: endMs
            if (durationMs != null && startMs >= durationMs) return null
            if (clampedEnd - startMs < 500L) return null
            return LoopState(startMs, clampedEnd)
        }
    }
}

/**
 * AlarmManager-backed sleep timer with coroutine fallback. Using AlarmManager keeps DSP audio
 * offload enabled overnight until the AP wakes to pause playback.
 */
internal class SleepTimerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
    private val onChanged: () -> Unit,
    private val getVolume: () -> Float = { 1f },
    private val setVolume: (Float) -> Unit = {},
) {
    companion object {
        private const val SLEEP_ALARM_REQUEST_CODE = 1702
        private const val FADE_LEAD_MS = 12_000L
    }

    var deadlineMs: Long = 0L
        private set

    var stopAfterTrack: Boolean = false
        private set

    var handledByAlarm: Boolean = false
        private set

    val handlerDeadlineMs: Long
        get() = if (handledByAlarm) 0L else deadlineMs

    private var timerJob: Job? = null
    private var fadeJob: Job? = null
    private var savedVolume: Float? = null
    private var receiverRegistered = false
    private val action = "${context.packageName}.ACTION_SLEEP_TIMER"

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action && deadlineMs > 0L) onWakeForDeadline()
            }
        }

    fun register() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun setMinutes(minutes: Int) {
        cancelFade(restoreVolume = true)
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        stopAfterTrack = false
        deadlineMs = SleepTimer.deadlineMs(SystemClock.elapsedRealtime(), minutes)
        if (deadlineMs > 0L) scheduleCurrentDeadline()
        onChanged()
    }

    fun extendMinutes(deltaMinutes: Int) {
        if (deltaMinutes <= 0) return
        val now = SystemClock.elapsedRealtime()
        val base = if (deadlineMs > now) deadlineMs else now
        cancelFade(restoreVolume = true)
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        stopAfterTrack = false
        deadlineMs = (base + deltaMinutes * 60_000L).coerceAtMost(now + 180 * 60_000L)
        scheduleCurrentDeadline()
        onChanged()
    }

    fun setStopAfterTrack(enabled: Boolean) {
        cancelFade(restoreVolume = true)
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = 0L
        stopAfterTrack = enabled
        onChanged()
    }

    fun consumeStopAfterTrack(): Boolean {
        if (!stopAfterTrack) return false
        stopAfterTrack = false
        onChanged()
        return true
    }

    private fun scheduleCurrentDeadline() {
        val now = SystemClock.elapsedRealtime()
        val remaining = deadlineMs - now
        val wakeAt =
            if (remaining > FADE_LEAD_MS + 3_000L) deadlineMs - FADE_LEAD_MS else deadlineMs
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val scheduled =
            alarmManager != null &&
                runCatching {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        wakeAt,
                        pendingIntent(),
                    )
                }
                    .isSuccess
        handledByAlarm = scheduled
        if (!scheduled) {
            timerJob = scope.launch {
                val wait = wakeAt - SystemClock.elapsedRealtime()
                if (wait > 0L) delay(wait)
                if (isActive && deadlineMs > 0L) onWakeForDeadline()
            }
        }
    }

    private fun onWakeForDeadline() {
        val target = deadlineMs
        if (target <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val remaining = target - now
        if (remaining <= 250L) {
            expire()
            return
        }
        cancelFade(restoreVolume = true)
        val initialVolume = getVolume().coerceIn(0f, 1f)
        savedVolume = initialVolume
        fadeJob = scope.launch {
            val fadeSpan = remaining.toFloat().coerceAtLeast(1f)
            while (isActive) {
                val left = target - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                if (initialVolume > 0f) {
                    val factor = (left / fadeSpan).coerceIn(0f, 1f)
                    setVolume(initialVolume * factor)
                }
                delay(minOf(200L, left))
            }
            if (isActive && deadlineMs == target) expire()
        }
    }

    private fun cancelFade(restoreVolume: Boolean) {
        fadeJob?.cancel()
        fadeJob = null
        val prev = savedVolume
        savedVolume = null
        if (restoreVolume && prev != null) setVolume(prev)
    }

    private fun expire() {
        val prev = savedVolume
        fadeJob?.cancel()
        fadeJob = null
        savedVolume = null
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = 0L
        stopAfterTrack = false
        onExpired()
        if (prev != null) setVolume(prev)
        onChanged()
    }

    private fun pendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            SLEEP_ALARM_REQUEST_CODE,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun cancelAlarm() {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent())
        handledByAlarm = false
    }

    fun release() {
        cancelFade(restoreVolume = true)
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        stopAfterTrack = false
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }
}

/**
 * ExoPlayer native [PlayerMessage]-driven A-B loop controller. Replaces background polling
 * coroutines while keeping exact EOF handling and automatic-seek progress write suppression.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AbLoopController(
    private val player: ExoPlayer,
    private val onChanged: () -> Unit,
) {
    var startMs: Long = -1L
        private set

    var endMs: Long = -1L
        private set

    val active: Boolean
        get() = startMs >= 0L && endMs > startMs

    var suppressAutoProgress: Boolean = false
        private set

    var lastClearedBySeek: LoopState? = null
        private set

    private var endMessage: PlayerMessage? = null
    private var pendingLoopSeekTo: Pair<String?, Long>? = null

    fun set(start: Long, end: Long) {
        val segment =
            LoopState.validate(
                start,
                end,
                player.duration.takeIf { it > 0 },
                player.currentMediaItem != null,
            )
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
        lastClearedBySeek = null
        startMs = segment?.startMs ?: -1L
        endMs = segment?.endMs ?: -1L
        if (active) {
            scheduleMessage()
            if (player.isPlaying && player.currentPosition >= endMs) seekToLoopStart()
        }
        onChanged()
    }

    fun clear(recordAsSeekClear: Boolean = false) {
        if (!active && endMessage == null) return
        val previous = if (recordAsSeekClear && active) LoopState(startMs, endMs) else null
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
        startMs = -1L
        endMs = -1L
        lastClearedBySeek = previous
        onChanged()
    }

    fun dismissClearedHistory() {
        if (lastClearedBySeek != null) {
            lastClearedBySeek = null
            onChanged()
        }
    }

    fun onTrackTransition() {
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
        lastClearedBySeek = null
        if (active) {
            startMs = -1L
            endMs = -1L
            onChanged()
        }
    }

    fun onReady() {
        if (active && endMessage == null) scheduleMessage()
        if (active && player.isPlaying && player.currentPosition >= endMs) seekToLoopStart()
    }

    fun onEndOfMediaItem(): Boolean {
        if (!active) return false
        seekToLoopStart()
        player.play()
        return true
    }

    fun onPauseRequested() {
        suppressAutoProgress = false
    }

    fun onEvents() {
        if (player.isPlaying && pendingLoopSeekTo == null) suppressAutoProgress = false
    }

    /** Returns true if this discontinuity was triggered by an automatic A-B wrap seek. */
    fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ): Boolean {
        val loopSeek =
            reason == Player.DISCONTINUITY_REASON_SEEK &&
                pendingLoopSeekTo == (newPosition.mediaItem?.mediaId to newPosition.positionMs)
        pendingLoopSeekTo = null
        if (!loopSeek) suppressAutoProgress = false
        if (
            active &&
                (oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId ||
                    newPosition.positionMs !in startMs..endMs)
        ) {
            clear(
                recordAsSeekClear =
                    oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId,
            )
        }
        return loopSeek
    }

    fun release() {
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
    }

    private fun scheduleMessage() {
        if (!active || player.currentTimeline.isEmpty) return
        val index = player.currentMediaItemIndex
        if (index < 0 || index >= player.currentTimeline.windowCount) return
        val targetEnd = endMs
        endMessage =
            player
                .createMessage { _, _ -> onBoundaryReached() }
                .setLooper(player.applicationLooper)
                .setPosition(index, targetEnd)
                .setDeleteAfterDelivery(false)
                .send()
    }

    private fun onBoundaryReached() {
        if (!active || !player.playWhenReady || pendingLoopSeekTo != null) return
        seekToLoopStart()
    }

    private fun seekToLoopStart() {
        suppressAutoProgress = true
        pendingLoopSeekTo = player.currentMediaItem?.mediaId to startMs
        player.seekTo(startMs)
    }

    private fun cancelMessage() {
        endMessage?.cancel()
        endMessage = null
    }
}
