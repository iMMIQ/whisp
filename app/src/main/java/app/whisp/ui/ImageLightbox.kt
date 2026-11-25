// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.get
import app.whisp.data.WhispImage
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
internal fun WhispImageLightbox(
    image: WhispImage,
    title: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    val dismissY = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var showChrome by remember { mutableStateOf(true) }

    fun clampOffset(rawX: Float, rawY: Float, currentScale: Float): Offset {
        if (currentScale <= 1.02f || containerSize.width == 0 || containerSize.height == 0) {
            return Offset.Zero
        }
        val maxX = ((containerSize.width * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((containerSize.height * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
        return Offset(rawX.coerceIn(-maxX, maxX), rawY.coerceIn(-maxY, maxY))
    }

    val showChromeVisible by remember {
        derivedStateOf {
            val h = containerSize.height.coerceAtLeast(1) * 0.32f
            val progress = (abs(dismissY.value) / h).coerceIn(0f, 1f)
            showChrome && progress < 0.12f
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { containerSize = it }
                .drawBehind {
                    val h = (size.height.coerceAtLeast(1f) * 0.32f)
                    val progress = (abs(dismissY.value) / h).coerceIn(0f, 1f)
                    val bgAlpha = (0.96f * (1f - progress * 0.65f)).coerceIn(0.2f, 0.96f)
                    drawRect(ScrimDark.copy(alpha = bgAlpha))
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showChrome = !showChrome },
                        onDoubleTap = { tap ->
                            scope.launch {
                                val spec: AnimationSpec<Float> =
                                    if (reduced) snap()
                                    else
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        )
                                if (scaleAnim.value > 1.2f) {
                                    launch { scaleAnim.animateTo(1f, spec) }
                                    launch { offsetXAnim.animateTo(0f, spec) }
                                    launch { offsetYAnim.animateTo(0f, spec) }
                                } else {
                                    val targetScale = 2.4f
                                    val cx = containerSize.width / 2f
                                    val cy = containerSize.height / 2f
                                    val desired =
                                        clampOffset(
                                            (cx - tap.x) * (targetScale - 1f),
                                            (cy - tap.y) * (targetScale - 1f),
                                            targetScale,
                                        )
                                    launch { scaleAnim.animateTo(targetScale, spec) }
                                    launch { offsetXAnim.animateTo(desired.x, spec) }
                                    launch { offsetYAnim.animateTo(desired.y, spec) }
                                }
                            }
                        },
                    )
                }
                .pointerInput(onDismiss) {
                    val thresholdPx = 110.dp.toPx()
                    val slopPx = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        var crossedThreshold = false
                        var dismissing = false
                        var totalDx = 0f
                        var totalDy = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount == 0) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            totalDx += panChange.x
                            totalDy += panChange.y
                            if (
                                pressedCount > 1 ||
                                    scaleAnim.value > 1.02f ||
                                    abs(zoomChange - 1f) > 0.01f
                            ) {
                                dismissing = false
                                val nextScale = (scaleAnim.value * zoomChange).coerceIn(1f, 4f)
                                val clamped =
                                    clampOffset(
                                        offsetXAnim.value + panChange.x,
                                        offsetYAnim.value + panChange.y,
                                        nextScale,
                                    )
                                scope.launch {
                                    scaleAnim.snapTo(nextScale)
                                    offsetXAnim.snapTo(clamped.x)
                                    offsetYAnim.snapTo(clamped.y)
                                    if (dismissY.value != 0f) dismissY.snapTo(0f)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (
                                dismissing || (abs(totalDy) > slopPx && abs(totalDy) > abs(totalDx))
                            ) {
                                dismissing = true
                                val nextY = dismissY.value + panChange.y
                                event.changes.firstOrNull()?.let {
                                    velocityTracker.addPosition(it.uptimeMillis, it.position)
                                    if (it.positionChanged()) it.consume()
                                }
                                scope.launch { dismissY.snapTo(nextY) }
                                val nowCrossed = abs(nextY) >= thresholdPx
                                if (nowCrossed && !crossedThreshold) {
                                    crossedThreshold = true
                                    haptics.tick()
                                } else if (!nowCrossed) {
                                    crossedThreshold = false
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (dismissing) {
                            val velocityY = velocityTracker.calculateVelocity().y
                            if (abs(dismissY.value) >= thresholdPx || abs(velocityY) > 1200f) {
                                onDismiss()
                            } else {
                                scope.launch {
                                    dismissY.animateTo(
                                        0f,
                                        if (reduced) snap()
                                        else
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                },
        ) {
            Artwork(
                image = image,
                description = title,
                scale = ContentScale.Fit,
                modifier =
                    Modifier.fillMaxSize().graphicsLayer {
                        val h = size.height.coerceAtLeast(1f) * 0.32f
                        val progress = (abs(dismissY.value) / h).coerceIn(0f, 1f)
                        val dismissScale = (1f - progress * 0.14f).coerceIn(0.84f, 1f)
                        val effectiveScale = scaleAnim.value * dismissScale
                        scaleX = effectiveScale
                        scaleY = effectiveScale
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value + dismissY.value
                    },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = showChromeVisible,
                enter = androidx.compose.animation.fadeIn(tween(if (reduced) 0 else 150)),
                exit = androidx.compose.animation.fadeOut(tween(if (reduced) 0 else 120)),
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Ink.copy(alpha = 0.78f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Cream,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    IconAction(WhispIcons.Outlined.Close, "关闭预览", Cream, action = onDismiss)
                }
            }
        }
    }
}
