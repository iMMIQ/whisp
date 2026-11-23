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
import androidx.media3.exoplayer.ExoPlayer
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
    ): AudioOffloadPreferences =
        if (enabled)
            AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(speedChangeRequired)
                .build()
        else AudioOffloadPreferences.DEFAULT

    fun needsPreciseDeadline(loopStart: Long, loopEnd: Long, timerEnd: Long): Boolean =
        (loopStart >= 0 && loopEnd > loopStart) || timerEnd > 0

    internal fun apply(player: ExoPlayer, loop: AbLoopController, timer: SleepTimerController) {
        if (!supported(Build.VERSION.SDK_INT)) return
        val speedChanged = abs(player.playbackParameters.speed - 1f) > 0.01f
        val enabled = !needsPreciseDeadline(loop.startMs, loop.endMs, timer.handlerDeadlineMs)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(preferences(enabled, speedChanged))
                .build()
    }
}

object SleepTimer {
    fun deadlineMs(nowMs: Long, minutes: Int): Long =
        if (minutes <= 0) 0 else nowMs + minutes.coerceAtMost(180) * 60_000L
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
) {
    companion object {
        private const val SLEEP_ALARM_REQUEST_CODE = 1702
    }

    var deadlineMs: Long = 0L
        private set

    var handledByAlarm: Boolean = false
        private set

    val handlerDeadlineMs: Long
        get() = if (handledByAlarm) 0L else deadlineMs

    private var timerJob: Job? = null
    private var receiverRegistered = false
    private val action = "${context.packageName}.ACTION_SLEEP_TIMER"

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action && deadlineMs > 0L) expire()
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
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = SleepTimer.deadlineMs(SystemClock.elapsedRealtime(), minutes)
        if (deadlineMs > 0L) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val scheduled =
                alarmManager != null &&
                    runCatching {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            deadlineMs,
                            pendingIntent(),
                        )
                    }
                        .isSuccess
            handledByAlarm = scheduled
            if (!scheduled) {
                timerJob = scope.launch {
                    while (isActive && deadlineMs > SystemClock.elapsedRealtime()) {
                        delay(deadlineMs - SystemClock.elapsedRealtime())
                    }
                    expire()
                }
            }
        }
        onChanged()
    }

    private fun expire() {
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = 0L
        onExpired()
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
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
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
        startMs = segment?.startMs ?: -1L
        endMs = segment?.endMs ?: -1L
        player.setPauseAtEndOfMediaItems(active)
        if (active) {
            scheduleMessage()
            if (player.isPlaying && player.currentPosition >= endMs) seekToLoopStart()
        }
        onChanged()
    }

    fun clear() {
        if (!active && endMessage == null) return
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
        startMs = -1L
        endMs = -1L
        player.setPauseAtEndOfMediaItems(false)
        onChanged()
    }

    fun onTrackTransition() {
        cancelMessage()
        pendingLoopSeekTo = null
        suppressAutoProgress = false
        if (active) {
            startMs = -1L
            endMs = -1L
            player.setPauseAtEndOfMediaItems(false)
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
            clear()
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
