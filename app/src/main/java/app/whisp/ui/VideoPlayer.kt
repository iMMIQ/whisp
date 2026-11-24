// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoOutput(
    playback: PlaybackCoordinator,
    trackId: String,
    active: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val player by playback.playerFlow.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    var resumed by remember { mutableStateOf(false) }
    val owner = remember { Any() }
    val attached = if (active) player else null
    LifecycleResumeEffect(playback, attached) {
        resumed = true
        playback.videoVisibility(owner, attached != null)
        onPauseOrDispose {
            resumed = false
            playback.videoVisibility(owner, false)
        }
    }
    DisposableEffect(view, resumed, playing) {
        val previous = view.keepScreenOn
        view.keepScreenOn = resumed && active && playing
        onDispose { view.keepScreenOn = previous }
    }
    Box(
        modifier.background(Color.Black).testTag("video-output").semantics {
            contentDescription = "视频画面"
        },
    ) {
        key(trackId) {
            PlayerSurface(
                attached,
                Modifier.matchParentSize()
                    .resizeWithContentScale(ContentScale.Fit, Size(state.videoAspectRatio, 1f)),
            )
        }
    }
}

@Composable
internal fun FullScreenVideo(
    playback: PlaybackCoordinator,
    trackId: String,
    title: String,
    state: PlaybackState,
    active: Boolean,
    caption: String,
    subtitleSize: Int,
    exit: () -> Unit,
    timeline: @Composable () -> Unit,
) {
    val activity = LocalActivity.current
    val originalOrientation = rememberSaveable {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(activity, state.videoAspectRatio >= 1f) {
        activity?.requestedOrientation =
            if (state.videoAspectRatio >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
    DisposableEffect(activity) {
        val bars = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        val oldBehavior = bars?.systemBarsBehavior
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) bars.systemBarsBehavior = oldBehavior
            if (activity?.isChangingConfigurations == false)
                activity.requestedOrientation = originalOrientation
        }
    }
    var controls by rememberSaveable(trackId) { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var foreground by remember { mutableStateOf(false) }
    LifecycleResumeEffect(trackId) {
        foreground = true
        controls = true
        interaction++
        onPauseOrDispose { foreground = false }
    }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(controls, state.playing, interaction, foreground) {
        if (controls && state.playing && foreground) {
            delay(3500)
            controls = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoOutput(playback, trackId, active, state.playing, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize()
                .semantics { contentDescription = "视频画面" }
                .clickable(
                    onClickLabel = if (controls) "隐藏播放控制" else "显示播放控制",
                ) {
                    controls = !controls
                    interaction++
                },
        )
        AnimatedVisibility(
            controls || !state.playing,
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
                IconAction(Icons.Outlined.FullscreenExit, "退出全屏", action = exit)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Cream,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconAction(Icons.Outlined.Headphones, "只听声音") {
                    playback.setAudioOnly(true)
                    exit()
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (caption.isNotBlank()) {
                Box(
                    Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink.copy(alpha = 0.66f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        caption,
                        color = Cream,
                        fontSize = subtitleSize.sp,
                        lineHeight = (subtitleSize + 8).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            AnimatedVisibility(
                controls || !state.playing,
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
                        .padding(top = 6.dp, bottom = 10.dp),
                ) {
                    timeline()
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAction(Icons.Outlined.Replay10, "后退十秒", enabled = active) {
                            playback.skip(-10_000)
                            interaction++
                        }
                        FilledIconButton(
                            onClick = {
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
                                if (state.playWhenReady) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                if (state.playWhenReady) "暂停" else "播放",
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        IconAction(Icons.Outlined.Forward10, "前进十秒", enabled = active) {
                            playback.skip(10_000)
                            interaction++
                        }
                    }
                }
            }
        }
    }
}
