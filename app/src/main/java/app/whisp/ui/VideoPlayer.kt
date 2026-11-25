// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoOutput(
    playback: PlaybackCoordinator,
    trackId: String,
    active: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val player by playback.playerFlow.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val reportBounds = LocalVideoBoundsReporter.current
    var resumed by remember { mutableStateOf(false) }
    val owner = remember { Any() }
    val attached = if (active) player else null
    LifecycleResumeEffect(playback, attached) {
        resumed = true
        playback.videoVisibility(owner, attached != null)
        onPauseOrDispose {
            resumed = false
            playback.videoVisibility(owner, false)
            reportBounds(null)
        }
    }
    DisposableEffect(view, resumed, active, playing) {
        val previous = view.keepScreenOn
        view.keepScreenOn = resumed && active && playing
        onDispose { view.keepScreenOn = previous }
    }
    Box(
        modifier
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                if (resumed && active) {
                    val b = coords.boundsInWindow()
                    if (b.width > 1f && b.height > 1f) {
                        reportBounds(
                            android.graphics.Rect(
                                b.left.roundToInt(),
                                b.top.roundToInt(),
                                b.right.roundToInt(),
                                b.bottom.roundToInt(),
                            ),
                        )
                    }
                }
            }
            .testTag("video-output")
            .semantics { contentDescription = "视频画面" },
    ) {
        key(trackId) {
            PlayerSurface(
                attached,
                Modifier.matchParentSize()
                    .resizeWithContentScale(contentScale, Size(state.videoAspectRatio, 1f)),
            )
        }
    }
}

private enum class FullscreenDragMode {
    None,
    Seek,
    Brightness,
    Volume,
    Speed2x,
}

@Composable
internal fun FullScreenVideo(
    playback: PlaybackCoordinator,
    trackId: String,
    title: String,
    state: PlaybackState,
    active: Boolean,
    caption: String,
    secondaryCaption: String? = null,
    subtitleSize: Int,
    skipSeconds: Int,
    hasQueue: Boolean = false,
    previewCaptionProvider: ((Long) -> String?)? = null,
    onOpenQueue: (() -> Unit)? = null,
    exit: () -> Unit,
    timeline:
        @Composable
        (onScrubPreview: (Long?) -> Unit, onScrubbingChange: (Boolean) -> Unit) -> Unit,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(activity, state.videoAspectRatio >= 1f) {
        activity?.requestedOrientation =
            if (state.videoAspectRatio >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        val bars = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        val oldBehavior = bars?.systemBarsBehavior
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (window != null && originalBrightness != null) {
                val lp = window.attributes
                if (lp.screenBrightness != originalBrightness) {
                    lp.screenBrightness = originalBrightness
                    window.attributes = lp
                }
            }
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) bars.systemBarsBehavior = oldBehavior
            if (activity?.isChangingConfigurations == false)
                activity.requestedOrientation = originalOrientation
        }
    }
    var controls by rememberSaveable(trackId) { mutableStateOf(true) }
    var locked by rememberSaveable(trackId) { mutableStateOf(false) }
    var fillScreen by rememberSaveable { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }
    var scrubbing by remember(trackId) { mutableStateOf(false) }
    var scrubPreviewMs by remember(trackId) { mutableStateOf<Long?>(null) }
    var draggingHud by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(false) }
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudSubText by remember { mutableStateOf<String?>(null) }
    var hudToken by remember { mutableIntStateOf(0) }
    val currentState by rememberUpdatedState(state)
    val latestPreviewCaption by rememberUpdatedState(previewCaptionProvider)

    fun flashHud(text: String, sub: String? = null) {
        hudText = text
        hudSubText = sub
        hudToken++
    }

    LaunchedEffect(hudToken, draggingHud) {
        if (!draggingHud && hudText != null) {
            delay(1100)
            hudText = null
            hudSubText = null
        }
    }
    LaunchedEffect(showUnlockHint) {
        if (showUnlockHint) {
            delay(2500)
            showUnlockHint = false
        }
    }

    LifecycleResumeEffect(trackId, activity) {
        foreground = true
        controls = !locked
        interaction++
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        onPauseOrDispose { foreground = false }
    }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(controls, state.playing, interaction, foreground, scrubbing, locked) {
        if (controls && state.playing && foreground && !scrubbing && !locked) {
            delay(3500)
            controls = false
        }
    }
    val effectiveCaption =
        scrubPreviewMs?.let { latestPreviewCaption?.invoke(it) }?.takeIf { it.isNotBlank() }
            ?: caption
    val effectiveSecondary = if (scrubPreviewMs != null) null else secondaryCaption

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoOutput(
            playback = playback,
            trackId = trackId,
            active = active,
            playing = state.playing,
            contentScale = if (fillScreen) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = "视频画面" }
                .pointerInput(locked, active, skipSeconds) {
                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                showUnlockHint = !showUnlockHint
                            } else {
                                controls = !controls
                                interaction++
                            }
                        },
                        onDoubleTap = { offset ->
                            if (locked || !active) return@detectTapGestures
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            when {
                                offset.x < width * 0.34f -> {
                                    playback.skip(-skipSeconds * 1000L)
                                    haptics.tick()
                                    flashHud("−${skipSeconds} 秒")
                                }
                                offset.x > width * 0.66f -> {
                                    playback.skip(skipSeconds * 1000L)
                                    haptics.tick()
                                    flashHud("+${skipSeconds} 秒")
                                }
                                else -> {
                                    val wasPlaying = currentState.playWhenReady
                                    playback.togglePlay()
                                    haptics.confirm()
                                    flashHud(if (wasPlaying) "已暂停" else "继续播放")
                                }
                            }
                            interaction++
                        },
                    )
                }
                .pointerInput(locked, active, state.durationMs) {
                    if (locked) return@pointerInput
                    val slopPx = viewConfiguration.touchSlop
                    val audioManager =
                        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    try {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var mode = FullscreenDragMode.None
                            val startPos = playback.currentPosition()
                            var seekTarget = startPos
                            val maxVol =
                                audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                            var floatVol =
                                audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat()
                                    ?: 0f
                            val window = activity?.window
                            var floatBrightness =
                                window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                                    ?: runCatching {
                                            Settings.System.getInt(
                                                context.contentResolver,
                                                Settings.System.SCREEN_BRIGHTNESS,
                                            ) / 255f
                                        }
                                        .getOrDefault(0.5f)
                            // Hold anywhere for 420 ms without dragging to engage temporary 2x
                            // fast-forward.
                            val speedHoldJob =
                                if (active && currentState.playing) {
                                    scope.launch {
                                        delay(420L)
                                        if (mode == FullscreenDragMode.None) {
                                            mode = FullscreenDragMode.Speed2x
                                            draggingHud = true
                                            playback.setTemporarySpeed(2.0f)
                                            haptics.tick()
                                            hudText = "2.0x 倍速播放中"
                                            hudSubText = "松手恢复原速"
                                        }
                                    }
                                } else null
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change =
                                        event.changes.firstOrNull { it.id == down.id }
                                            ?: event.changes.firstOrNull()
                                            ?: break
                                    if (!change.pressed) {
                                        if (mode == FullscreenDragMode.Seek && active) {
                                            playback.seek(seekTarget)
                                        }
                                        break
                                    }
                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y
                                    if (
                                        mode == FullscreenDragMode.None &&
                                            (abs(totalDx) > slopPx || abs(totalDy) > slopPx)
                                    ) {
                                        speedHoldJob?.cancel()
                                    }
                                    if (mode == FullscreenDragMode.None) {
                                        if (
                                            abs(totalDx) > slopPx &&
                                                abs(totalDx) > abs(totalDy) * 1.2f
                                        ) {
                                            if (active && state.durationMs > 0L) {
                                                mode = FullscreenDragMode.Seek
                                                draggingHud = true
                                                haptics.tick()
                                            }
                                        } else if (
                                            abs(totalDy) > slopPx &&
                                                abs(totalDy) > abs(totalDx) * 1.2f
                                        ) {
                                            mode =
                                                if (down.position.x < size.width / 2f)
                                                    FullscreenDragMode.Brightness
                                                else FullscreenDragMode.Volume
                                            draggingHud = true
                                        }
                                    }
                                    when (mode) {
                                        FullscreenDragMode.Seek -> {
                                            change.consume()
                                            val spanMs =
                                                minOf(state.durationMs, 180_000L)
                                                    .coerceAtLeast(30_000L)
                                            val shift =
                                                ((totalDx /
                                                        size.width.toFloat().coerceAtLeast(1f)) *
                                                        spanMs)
                                                    .toLong()
                                            seekTarget =
                                                (startPos + shift).coerceIn(0L, state.durationMs)
                                            val diffSec = ((seekTarget - startPos) / 1000L).toInt()
                                            val sign = if (diffSec >= 0) "+" else "−"
                                            hudText =
                                                "${clock(seekTarget)} (${sign}${abs(diffSec)}s)"
                                            hudSubText =
                                                latestPreviewCaption?.invoke(seekTarget)?.takeIf {
                                                    it.isNotBlank()
                                                }
                                        }
                                        FullscreenDragMode.Brightness -> {
                                            change.consume()
                                            val step =
                                                -delta.y / (size.height * 0.65f).coerceAtLeast(1f)
                                            floatBrightness =
                                                (floatBrightness + step).coerceIn(0.02f, 1f)
                                            window?.let { win ->
                                                val lp = win.attributes
                                                lp.screenBrightness = floatBrightness
                                                win.attributes = lp
                                            }
                                            hudText = "亮度 ${(floatBrightness * 100).roundToInt()}%"
                                            hudSubText = null
                                        }
                                        FullscreenDragMode.Volume -> {
                                            change.consume()
                                            val step =
                                                (-delta.y /
                                                    (size.height * 0.65f).coerceAtLeast(1f)) *
                                                    maxVol
                                            floatVol =
                                                (floatVol + step).coerceIn(0f, maxVol.toFloat())
                                            val nextVol = floatVol.roundToInt()
                                            audioManager?.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                nextVol,
                                                0,
                                            )
                                            val pct =
                                                if (maxVol > 0) (nextVol * 100 / maxVol) else 0
                                            hudText = "音量 $pct%"
                                            hudSubText = null
                                        }
                                        FullscreenDragMode.Speed2x -> {
                                            change.consume()
                                        }
                                        FullscreenDragMode.None -> Unit
                                    }
                                }
                            } finally {
                                speedHoldJob?.cancel()
                                if (mode == FullscreenDragMode.Speed2x) {
                                    playback.restoreConfiguredSpeed()
                                }
                                if (draggingHud) {
                                    draggingHud = false
                                    hudToken++
                                }
                            }
                        }
                    } finally {
                        if (draggingHud) {
                            draggingHud = false
                            hudToken++
                        }
                    }
                },
        )
        AnimatedVisibility(
            visible = hudText != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(if (reduced) 0 else 100)),
            exit = fadeOut(tween(if (reduced) 0 else 150)),
        ) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink.copy(alpha = 0.80f))
                    .border(0.5.dp, Rose.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    hudText.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumeralStyle),
                    color = Cream,
                    fontWeight = FontWeight.SemiBold,
                )
                hudSubText
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sub ->
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = Cream.copy(alpha = 0.82f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
            }
        }
        AnimatedVisibility(
            visible = locked && showUnlockHint,
            modifier = Modifier.align(Alignment.CenterStart).displayCutoutPadding().padding(20.dp),
            enter = fadeIn(tween(if (reduced) 0 else 140)),
            exit = fadeOut(tween(if (reduced) 0 else 140)),
        ) {
            val pillShape = RoundedCornerShape(999.dp)
            Row(
                Modifier.clip(pillShape)
                    .background(Ink.copy(alpha = 0.78f))
                    .border(0.5.dp, Rose.copy(alpha = 0.45f), pillShape)
                    .clickable {
                        locked = false
                        showUnlockHint = false
                        controls = true
                        interaction++
                        haptics.confirm()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(WhispIcons.Outlined.Lock, "解锁画面", tint = Rose, modifier = Modifier.size(18.dp))
                Text("点击解锁", style = MaterialTheme.typography.labelLarge, color = Cream)
            }
        }
        AnimatedVisibility(
            !locked && (controls || !state.playing),
            Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(if (reduced) 0 else 140)),
            exit = fadeOut(tween(if (reduced) 0 else 140)),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Ink.copy(alpha = .78f), Color.Transparent)),
                    )
                    .displayCutoutPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(WhispIcons.Outlined.FullscreenExit, "退出全屏", action = exit)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Cream,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconAction(
                    if (fillScreen) WhispIcons.Outlined.FitScreen
                    else WhispIcons.Outlined.AspectRatio,
                    if (fillScreen) "适应画面" else "填满屏幕",
                    tint = if (fillScreen) Rose else Cream,
                    haptic = HapticKind.Tick,
                ) {
                    fillScreen = !fillScreen
                    flashHud(if (fillScreen) "画面：填满屏幕" else "画面：原始比例")
                    interaction++
                }
                IconAction(
                    WhispIcons.Outlined.LockOpen,
                    "锁定防误触",
                    haptic = HapticKind.Confirm,
                ) {
                    locked = true
                    controls = false
                    showUnlockHint = true
                }
                IconAction(WhispIcons.Outlined.Headphones, "只听声音", haptic = HapticKind.Tick) {
                    playback.setAudioOnly(true)
                    exit()
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (effectiveCaption.isNotBlank()) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink.copy(alpha = 0.66f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        effectiveCaption,
                        color = Cream,
                        fontSize = subtitleSize.sp,
                        lineHeight = (subtitleSize + 8).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!effectiveSecondary.isNullOrBlank()) {
                        Text(
                            effectiveSecondary,
                            color = Cream.copy(alpha = 0.78f),
                            fontSize = (subtitleSize - 3).coerceAtLeast(13).sp,
                            lineHeight = (subtitleSize + 3).sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(
                !locked && (controls || !state.playing),
                enter = fadeIn(tween(if (reduced) 0 else 140)),
                exit = fadeOut(tween(if (reduced) 0 else 140)),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Ink.copy(alpha = .82f)),
                            ),
                        )
                        .displayCutoutPadding()
                        .navigationBarsPadding()
                        .padding(top = 6.dp, bottom = 10.dp),
                ) {
                    timeline(
                        { preview -> scrubPreviewMs = preview },
                        { isScrubbing ->
                            scrubbing = isScrubbing
                            interaction++
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val quickSpeeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
                        val speedShape = RoundedCornerShape(999.dp)
                        Box(
                            Modifier.clip(speedShape)
                                .background(Ink.copy(alpha = 0.55f))
                                .border(
                                    0.5.dp,
                                    if (abs(state.speed - 1f) > 0.01f) Rose.copy(alpha = 0.5f)
                                    else Cream.copy(alpha = 0.25f),
                                    speedShape,
                                )
                                .clickable {
                                    val idx = quickSpeeds.indexOfFirst {
                                        abs(it - state.speed) < 0.05f
                                    }
                                    val next = quickSpeeds[(idx + 1) % quickSpeeds.size]
                                    haptics.tick()
                                    playback.setPlaybackSpeed(next)
                                    flashHud("倍速 ${formatSpeedLabel(next)}")
                                    interaction++
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                formatSpeedLabel(state.speed),
                                style =
                                    MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                                color = if (abs(state.speed - 1f) > 0.01f) Rose else Cream,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconAction(
                                WhispIcons.Outlined.SkipPrevious,
                                "上一曲",
                                enabled = active && (state.hasPrevious || state.trackId != null),
                                haptic = HapticKind.Tick,
                            ) {
                                playback.previous()
                                interaction++
                            }
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = active) {
                                    haptics.tick()
                                    playback.skip(-skipSeconds * 1000L)
                                    interaction++
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                SkipSecondsIcon(
                                    seconds = skipSeconds,
                                    forward = false,
                                    tint = if (active) Cream else Cream.copy(alpha = 0.35f),
                                )
                            }
                            FilledIconButton(
                                onClick = {
                                    haptics.confirm()
                                    playback.togglePlay()
                                    interaction++
                                },
                                enabled = active,
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Rose,
                                        contentColor = Ink,
                                    ),
                            ) {
                                Icon(
                                    if (state.playWhenReady) WhispIcons.Filled.Pause
                                    else WhispIcons.Filled.PlayArrow,
                                    if (state.playWhenReady) "暂停" else "播放",
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = active) {
                                    haptics.tick()
                                    playback.skip(skipSeconds * 1000L)
                                    interaction++
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                SkipSecondsIcon(
                                    seconds = skipSeconds,
                                    forward = true,
                                    tint = if (active) Cream else Cream.copy(alpha = 0.35f),
                                )
                            }
                            IconAction(
                                WhispIcons.Outlined.SkipNext,
                                "下一曲",
                                enabled = active && state.hasNext,
                                haptic = HapticKind.Tick,
                            ) {
                                playback.next()
                                interaction++
                            }
                        }
                        if (hasQueue && onOpenQueue != null) {
                            IconAction(
                                WhispIcons.AutoMirrored.Outlined.QueueMusic,
                                "播放队列",
                                Cream,
                                haptic = HapticKind.Tick,
                            ) {
                                onOpenQueue()
                                interaction++
                            }
                        } else {
                            Spacer(Modifier.size(44.dp))
                        }
                    }
                }
            }
        }
    }
}
