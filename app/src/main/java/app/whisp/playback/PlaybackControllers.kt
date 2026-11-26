// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.whisp.data.DlsiteLoginRequired
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DSP audio offload policy: enabled overnight, disabled only when precise AP deadlines or DSP
 * effects exist.
 */
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

    fun needsPreciseDeadline(
        loopStart: Long,
        loopEnd: Long,
        timerEnd: Long,
        dspEffectsActive: Boolean = false,
    ): Boolean = (loopStart >= 0 && loopEnd > loopStart) || timerEnd > 0 || dspEffectsActive

    internal fun apply(
        player: ExoPlayer,
        loop: AbLoopController,
        timer: SleepTimerController,
        dspEffectsActive: Boolean = false,
        gaplessRequired: Boolean = false,
    ) {
        if (!supported(Build.VERSION.SDK_INT)) return
        val speedChanged = abs(player.playbackParameters.speed - 1f) > 0.01f
        val enabled =
            !needsPreciseDeadline(
                loop.startMs,
                loop.endMs,
                timer.handlerDeadlineMs,
                dspEffectsActive,
            )
        val desired = preferences(enabled, speedChanged, gaplessRequired)
        if (player.trackSelectionParameters.audioOffloadPreferences == desired) return
        player.trackSelectionParameters =
            player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(desired).build()
    }
}

/**
 * Network-resilient retry policy: allows 5 retries with smooth backoff for transient Wi-Fi/cellular
 * handoffs while failing fast on unrecoverable auth/404 errors.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class WhispLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val cause = loadErrorInfo.exception
        val unrecoverable =
            generateSequence<Throwable>(cause) { it.cause }
                .any { err ->
                    err is DlsiteLoginRequired ||
                        err is FileNotFoundException ||
                        (err is HttpDataSource.InvalidResponseCodeException &&
                            err.responseCode in setOf(401, 403, 404, 410))
                }
        if (unrecoverable) return androidx.media3.common.C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

data class PlaybackErrorInfo(
    val message: String,
    val canRetry: Boolean = true,
    val canRefreshSource: Boolean = false,
    val requiresDlsiteLogin: Boolean = false,
)

internal object PlaybackErrorFormatter {
    fun describe(error: PlaybackException, uriScheme: String? = null): PlaybackErrorInfo {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        if (chain.any { it is DlsiteLoginRequired }) {
            return PlaybackErrorInfo(
                message = "DLsite 登录已过期，请在来源页重新登录",
                canRetry = false,
                requiresDlsiteLogin = true,
            )
        }
        val httpError =
            chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (httpError != null) {
            val code = httpError.responseCode
            val isAsmr = uriScheme == "whisp-asmr"
            return when (code) {
                401,
                403,
                410 ->
                    PlaybackErrorInfo(
                        message =
                            if (isAsmr) "在线音频链接已失效（HTTP $code），可尝试刷新作品地址"
                            else "资源访问被拒绝（HTTP $code），请检查来源凭据",
                        canRetry = true,
                        canRefreshSource = isAsmr,
                    )
                404 ->
                    PlaybackErrorInfo(
                        message = "音频文件不存在（HTTP 404），请重新扫描来源",
                        canRetry = true,
                        canRefreshSource = isAsmr,
                    )
                in 500..599 ->
                    PlaybackErrorInfo(
                        message = "服务器暂时不可用（HTTP $code），请稍后重试",
                        canRetry = true,
                    )
                else ->
                    PlaybackErrorInfo(
                        message = "网络请求失败（HTTP $code），请检查连接后重试",
                        canRetry = true,
                    )
            }
        }
        if (
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        ) {
            return PlaybackErrorInfo(
                message = "当前设备不支持此音频或视频编码格式",
                canRetry = false,
            )
        }
        if (
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                chain.any { it is FileNotFoundException }
        ) {
            return PlaybackErrorInfo(
                message = "音轨文件已移动或删除，请检查文件授权或重新扫描",
                canRetry = true,
            )
        }
        val specificMessage =
            chain.filterIsInstance<IOException>().firstNotNullOfOrNull { io ->
                io.message?.takeIf {
                    it.startsWith("SMB：") || it.startsWith("NFS：") || it.startsWith("DLsite")
                }
            }
        if (specificMessage != null) {
            return PlaybackErrorInfo(message = specificMessage, canRetry = true)
        }
        if (
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                chain.any { it is java.net.SocketTimeoutException }
        ) {
            return PlaybackErrorInfo(message = "连接超时，请检查网络或 NAS 状态后重试", canRetry = true)
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
            return PlaybackErrorInfo(message = "网络连接中断，请检查网络后点击重试", canRetry = true)
        }
        return PlaybackErrorInfo(message = "播放失败，请检查文件授权或网络连接", canRetry = true)
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

data class TimerStartAnchor(
    val trackId: String,
    val positionMs: Long,
    val workId: String = "",
)

/**
 * AlarmManager-backed sleep timer with coroutine fallback, perceptual quadratic fade-out curve,
 * deferred volume restoration (preventing post-pause burst), and persisted sleep-start anchor.
 */
internal class SleepTimerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onExpired: () -> Unit,
    private val onChanged: () -> Unit,
    private val getVolume: () -> Float = { 1f },
    private val setVolume: (Float) -> Unit = {},
    private val captureStartAnchor: () -> TimerStartAnchor? = { null },
    initialStartAnchor: TimerStartAnchor? = null,
    private val onStartAnchorChanged: (TimerStartAnchor?) -> Unit = {},
) {
    companion object {
        private const val SLEEP_ALARM_REQUEST_CODE = 1702
        private const val DOZE_WAKE_MARGIN_MS = 3_000L

        internal fun perceptualFadeFactor(linearFraction: Float): Float {
            val clamped = linearFraction.coerceIn(0f, 1f)
            return clamped * clamped
        }
    }

    var fadeDurationMs: Long = 15_000L

    var deadlineMs: Long = 0L
        private set

    var stopAfterTrack: Boolean = false
        private set

    var handledByAlarm: Boolean = false
        private set

    var fading: Boolean = false
        private set

    var lastStartAnchor: TimerStartAnchor? = initialStartAnchor
        private set

    private fun updateStartAnchor(next: TimerStartAnchor?) {
        if (lastStartAnchor != next) {
            lastStartAnchor = next
            onStartAnchorChanged(next)
        }
    }

    val handlerDeadlineMs: Long
        get() = if (handledByAlarm && !fading) 0L else deadlineMs

    private var timerJob: Job? = null
    private var fadeJob: Job? = null
    private var fadeWakeLock: PowerManager.WakeLock? = null
    private var savedVolume: Float? = null
    private var pendingRestoreVolume: Float? = null
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

    fun restorePendingVolumeIfNeeded() {
        val pending = pendingRestoreVolume ?: return
        pendingRestoreVolume = null
        if (!fading) setVolume(pending)
    }

    fun clearStartAnchor() {
        if (lastStartAnchor != null) {
            updateStartAnchor(null)
            onChanged()
        }
    }

    fun setMinutes(minutes: Int) {
        cancelFade(restoreVolume = true)
        restorePendingVolumeIfNeeded()
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        stopAfterTrack = false
        deadlineMs = SleepTimer.deadlineMs(SystemClock.elapsedRealtime(), minutes)
        if (deadlineMs > 0L) {
            updateStartAnchor(captureStartAnchor() ?: lastStartAnchor)
            scheduleCurrentDeadline()
        }
        onChanged()
    }

    fun extendMinutes(deltaMinutes: Int) {
        if (deltaMinutes <= 0) return
        val now = SystemClock.elapsedRealtime()
        val base = if (deadlineMs > now) deadlineMs else now
        cancelFade(restoreVolume = true)
        restorePendingVolumeIfNeeded()
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        stopAfterTrack = false
        if (lastStartAnchor == null) updateStartAnchor(captureStartAnchor())
        deadlineMs = (base + deltaMinutes * 60_000L).coerceAtMost(now + 180 * 60_000L)
        scheduleCurrentDeadline()
        onChanged()
    }

    fun setStopAfterTrack(enabled: Boolean) {
        cancelFade(restoreVolume = true)
        restorePendingVolumeIfNeeded()
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = 0L
        stopAfterTrack = enabled
        if (enabled) updateStartAnchor(captureStartAnchor() ?: lastStartAnchor)
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
        val fadeLead = fadeDurationMs.coerceIn(5_000L, 45_000L)
        val wakeLead = fadeLead + DOZE_WAKE_MARGIN_MS
        val wakeAt =
            if (remaining > wakeLead + 2_000L) deadlineMs - wakeLead
            else deadlineMs - fadeLead.coerceAtMost(remaining / 2)
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
        val fadeLead = fadeDurationMs.coerceIn(5_000L, 45_000L)
        val initialVolume = getVolume().coerceIn(0f, 1f)
        savedVolume = initialVolume
        fading = true
        acquireFadeWakeLock(remaining + 2_000L)
        onChanged()
        fadeJob = scope.launch {
            try {
                // If Doze woke early due to safety margin, wait until the fade window starts.
                val preFadeWait = remaining - fadeLead
                if (preFadeWait > 200L) delay(preFadeWait)
                val fadeSpan = (target - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toFloat()
                while (isActive) {
                    val left = target - SystemClock.elapsedRealtime()
                    if (left <= 0L) break
                    if (initialVolume > 0f) {
                        val factor = perceptualFadeFactor(left / fadeSpan)
                        setVolume(initialVolume * factor)
                    }
                    delay(minOf(150L, left))
                }
                if (isActive && deadlineMs == target) expire()
            } finally {
                if (fadeJob === kotlin.coroutines.coroutineContext[Job]) {
                    fading = false
                    releaseFadeWakeLock()
                }
            }
        }
    }

    private fun acquireFadeWakeLock(timeoutMs: Long) {
        releaseFadeWakeLock()
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:sleep-fade")
                .apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs.coerceIn(1_000L, 60_000L))
                }
                .also { fadeWakeLock = it }
        }
    }

    private fun releaseFadeWakeLock() {
        val lock = fadeWakeLock
        fadeWakeLock = null
        if (lock != null) {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun cancelFade(restoreVolume: Boolean) {
        fadeJob?.cancel()
        fadeJob = null
        fading = false
        releaseFadeWakeLock()
        val prev = savedVolume
        savedVolume = null
        if (restoreVolume && prev != null) setVolume(prev)
    }

    private fun expire() {
        val prev = savedVolume
        fadeJob?.cancel()
        fadeJob = null
        fading = false
        releaseFadeWakeLock()
        savedVolume = null
        timerJob?.cancel()
        timerJob = null
        cancelAlarm()
        deadlineMs = 0L
        stopAfterTrack = false
        if (prev != null) setVolume(0f)
        onExpired()
        // Defer volume restoration until AudioTrack fully drains / next playback starts to avoid
        // tail clicks.
        if (prev != null) pendingRestoreVolume = prev
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
        restorePendingVolumeIfNeeded()
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
        if (!active) return
        val duration = player.duration.takeIf { it > 0 }
        if (duration != null) {
            val validated = LoopState.validate(startMs, endMs, duration, hasTrack = true)
            if (validated == null) {
                clear()
                return
            }
            if (validated.startMs != startMs || validated.endMs != endMs) {
                startMs = validated.startMs
                endMs = validated.endMs
                cancelMessage()
                onChanged()
            }
        }
        if (endMessage == null) scheduleMessage()
        if (player.isPlaying && player.currentPosition >= endMs) seekToLoopStart()
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
        val sameTrack = oldPosition.mediaItem?.mediaId == newPosition.mediaItem?.mediaId
        // Seeking compressed audio/video often snaps backward to the nearest keyframe/frame
        // boundary via SEEK_ADJUSTMENT; keep the active loop intact while playback enters [A, B].
        val keyframeSnapBeforeStart =
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT &&
                sameTrack &&
                newPosition.positionMs <= endMs
        if (
            active &&
                !keyframeSnapBeforeStart &&
                (!sameTrack || newPosition.positionMs !in startMs..endMs)
        ) {
            clear(recordAsSeekClear = sameTrack)
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
