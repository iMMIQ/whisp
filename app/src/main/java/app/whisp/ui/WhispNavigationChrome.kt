// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class NavTabItem(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
)

@Composable
internal fun WhispNavRail(activeTabIndex: Int, onSelect: (Int) -> Unit) {
    val haptics = rememberWhispHaptics()
    val tabs =
        listOf(
            NavTabItem(
                WhispIcons.Filled.LibraryMusic,
                WhispIcons.Outlined.LibraryMusic,
                stringResource(R.string.nav_library),
            ),
            NavTabItem(
                WhispIcons.Filled.Folder,
                WhispIcons.Outlined.FolderOpen,
                stringResource(R.string.nav_sources),
            ),
            NavTabItem(
                WhispIcons.Filled.Settings,
                WhispIcons.Outlined.Settings,
                stringResource(R.string.nav_settings),
            ),
        )
    Row(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = Cream,
        ) {
            Spacer(Modifier.height(16.dp))
            tabs.forEachIndexed { index, tab ->
                val selected = activeTabIndex == index
                NavigationRailItem(
                    selected = selected,
                    onClick = {
                        haptics.tick()
                        onSelect(index)
                    },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                    colors =
                        NavigationRailItemDefaults.colors(
                            selectedIconColor = Rose,
                            selectedTextColor = Rose,
                            indicatorColor = Rose.copy(alpha = 0.16f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                )
            }
        }
        VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun WhispBottomNav(activeTabIndex: Int, onSelect: (Int) -> Unit) {
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val tabs =
        listOf(
            NavTabItem(
                WhispIcons.Filled.LibraryMusic,
                WhispIcons.Outlined.LibraryMusic,
                stringResource(R.string.nav_library),
            ),
            NavTabItem(
                WhispIcons.Filled.Folder,
                WhispIcons.Outlined.FolderOpen,
                stringResource(R.string.nav_sources),
            ),
            NavTabItem(
                WhispIcons.Filled.Settings,
                WhispIcons.Outlined.Settings,
                stringResource(R.string.nav_settings),
            ),
        )
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = activeTabIndex == index
            val tint by
                animateColorAsState(
                    if (selected) Rose else Muted,
                    tween(if (reduced) 0 else 160),
                    label = "nav-tint",
                )
            val pill by
                animateColorAsState(
                    if (selected) Rose.copy(alpha = 0.16f) else Color.Transparent,
                    tween(if (reduced) 0 else 160),
                    label = "nav-pill",
                )
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        haptics.tick()
                        onSelect(index)
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(pill)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        tab.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private enum class MiniDragAxis {
    None,
    Vertical,
    Horizontal,
}

@Composable
internal fun MiniPlayer(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    fallbackPosition: Long,
    playback: PlaybackCoordinator,
    onToggle: () -> Unit,
    shareCover: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val offsetX = remember { Animatable(0f) }
    val expandThresholdPx = with(density) { 44.dp.toPx() }
    val swipeTrackThresholdPx = with(density) { 54.dp.toPx() }
    val currentState by rememberUpdatedState(state)
    val currentClick by rememberUpdatedState(onClick)

    val springSpec: AnimationSpec<Float> =
        if (reduced) snap()
        else
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )

    val liftedHighlight by
        remember(expandThresholdPx) {
            derivedStateOf {
                abs(offsetY.value) / expandThresholdPx.coerceAtLeast(1f) > 0.6f
            }
        }
    Column(
        Modifier.testTag("mini-player")
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .graphicsLayer {
                val liftFraction =
                    (abs(offsetY.value) / expandThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                translationY = offsetY.value * 0.55f
                val liftScale = 1f + liftFraction * 0.018f
                scaleX = liftScale
                scaleY = liftScale
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                0.5.dp,
                if (liftedHighlight) Rose.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .pointerInput(expandThresholdPx, swipeTrackThresholdPx, reduced, haptics) {
                val slopPx = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocityTracker = VelocityTracker()
                    var axis = MiniDragAxis.None
                    var totalDx = 0f
                    var totalDy = 0f
                    var crossed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change =
                            event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (axis == MiniDragAxis.None) {
                            if (abs(totalDy) > slopPx && abs(totalDy) > abs(totalDx)) {
                                if (totalDy < 0f) axis = MiniDragAxis.Vertical
                            } else if (abs(totalDx) > slopPx && abs(totalDx) > abs(totalDy)) {
                                val canSwipeLeft =
                                    currentState.trackId != null && currentState.hasNext
                                val canSwipeRight =
                                    currentState.trackId != null &&
                                        (currentState.hasPrevious ||
                                            playback.currentPosition() > 3000L)
                                if (
                                    (totalDx < 0f && canSwipeLeft) ||
                                        (totalDx > 0f && canSwipeRight)
                                ) {
                                    axis = MiniDragAxis.Horizontal
                                }
                            }
                        }
                        when (axis) {
                            MiniDragAxis.Vertical -> {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val nextY = totalDy.coerceIn(-expandThresholdPx * 1.5f, 0f)
                                scope.launch { offsetY.snapTo(nextY) }
                                val nowCrossed = nextY <= -expandThresholdPx
                                if (nowCrossed && !crossed) {
                                    crossed = true
                                    haptics.tick()
                                } else if (!nowCrossed) crossed = false
                            }
                            MiniDragAxis.Horizontal -> {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val canLeft = currentState.trackId != null && currentState.hasNext
                                val canRight =
                                    currentState.trackId != null &&
                                        (currentState.hasPrevious ||
                                            playback.currentPosition() > 3000L)
                                val minX = if (canLeft) -swipeTrackThresholdPx * 1.2f else 0f
                                val maxX = if (canRight) swipeTrackThresholdPx * 1.2f else 0f
                                val nextX = totalDx.coerceIn(minX, maxX)
                                scope.launch { offsetX.snapTo(nextX) }
                                val nowCrossed = abs(nextX) >= swipeTrackThresholdPx
                                if (nowCrossed && !crossed) {
                                    crossed = true
                                    haptics.tick()
                                } else if (!nowCrossed) crossed = false
                            }
                            MiniDragAxis.None -> Unit
                        }
                    }
                    when (axis) {
                        MiniDragAxis.Vertical -> {
                            val vy = velocityTracker.calculateVelocity().y
                            val expand = offsetY.value <= -expandThresholdPx || vy < -900f
                            scope.launch { offsetY.animateTo(0f, springSpec) }
                            if (expand) currentClick()
                        }
                        MiniDragAxis.Horizontal -> {
                            val vx = velocityTracker.calculateVelocity().x
                            val cur = offsetX.value
                            scope.launch { offsetX.animateTo(0f, springSpec) }
                            if (
                                (cur <= -swipeTrackThresholdPx || vx < -1000f) &&
                                    currentState.hasNext
                            ) {
                                playback.next()
                            } else if (
                                (cur >= swipeTrackThresholdPx || vx > 1000f) &&
                                    currentState.trackId != null &&
                                    (currentState.hasPrevious || playback.currentPosition() > 3000L)
                            ) {
                                playback.previous()
                            }
                        }
                        MiniDragAxis.None -> Unit
                    }
                }
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val swipeFraction =
                    (offsetX.value / swipeTrackThresholdPx.coerceAtLeast(1f)).coerceIn(-1f, 1f)
                if (swipeFraction > 0.15f) {
                    Icon(
                        WhispIcons.Outlined.SkipPrevious,
                        null,
                        tint = Rose.copy(alpha = swipeFraction.coerceIn(0f, 1f)),
                        modifier =
                            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp),
                    )
                } else if (swipeFraction < -0.15f) {
                    Icon(
                        WhispIcons.Outlined.SkipNext,
                        null,
                        tint = Rose.copy(alpha = (-swipeFraction).coerceIn(0f, 1f)),
                        modifier =
                            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(20.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { translationX = offsetX.value * 0.45f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        image = detail.work.coverId?.let { WhispImage.Asset(it) },
                        description = null,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)),
                        sharedKey = detail.work.id.takeIf { shareCover },
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            shortTitle(detail.work.title).ifBlank { detail.work.title },
                            style = MaterialTheme.typography.titleSmall,
                            color = Cream,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
            // Dedicated separated tap zone to reduce accidental full-player expansion.
            Box(
                Modifier.width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier.padding(start = 4.dp).size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconAction(
                    if (state.playWhenReady) WhispIcons.Filled.Pause
                    else WhispIcons.Filled.PlayArrow,
                    playControlLabel(state),
                    Rose,
                    haptic = HapticKind.Confirm,
                    action = onToggle,
                )
                if (state.buffering) {
                    CircularProgressIndicator(
                        Modifier.size(34.dp),
                        color = Rose,
                        trackColor = Rose.copy(alpha = 0.2f),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        MiniPlayerProgressBar(
            track = track,
            state = state,
            fallbackPosition = fallbackPosition,
            playback = playback,
        )
    }
}

@Composable
private fun MiniPlayerProgressBar(
    track: TrackEntity,
    state: PlaybackState,
    fallbackPosition: Long,
    playback: PlaybackCoordinator,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var liveDisplayPos by
        remember(track.id, state.trackId, state.positionMs, fallbackPosition) {
            mutableLongStateOf(
                if (state.trackId == track.id) playback.currentPosition() else fallbackPosition,
            )
        }
    // Sample directly from ExoPlayer locally without mutating PlaybackCoordinator.positionMs.
    LaunchedEffect(track.id, state.trackId, state.playing, lifecycleOwner) {
        if (state.trackId == track.id && state.playing) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    liveDisplayPos = playback.currentPosition()
                    delay(1000L)
                }
            }
        } else if (state.trackId == track.id) {
            liveDisplayPos = playback.currentPosition()
        }
    }
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    if (duration > 0) {
        val trackBg = Raised.copy(alpha = 0.7f)
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val pos = if (state.trackId == track.id) liveDisplayPos else fallbackPosition
            val fraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            drawRect(color = trackBg)
            if (fraction > 0f) {
                drawRect(
                    color = Rose,
                    size = size.copy(width = size.width * fraction),
                )
            }
        }
    }
}
