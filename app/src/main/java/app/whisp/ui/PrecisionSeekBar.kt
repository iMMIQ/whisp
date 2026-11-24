// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Pure fine-scrubbing gesture state: pulling vertically away from the bar zooms to a fine window.
 * Supplying [initialPositionMs] preserves the current position when grabbing near the thumb.
 */
class PrecisionScrubber(
    private val durationMs: Long,
    private val widthPx: Float,
    startX: Float,
    private val startY: Float,
    private val fineThresholdPx: Float,
    private val fineWindowMs: Long = 60_000L,
    initialPositionMs: Long? = null,
) {
    var positionMs: Long =
        (initialPositionMs ?: ((startX / widthPx.coerceAtLeast(1f)) * durationMs).toLong())
            .coerceIn(0L, durationMs)
        private set

    var fine: Boolean = false
        private set

    var rangeStart: Long = 0L
        private set

    var rangeEnd: Long = durationMs
        private set

    private var anchorX: Float = startX
    private var anchorPositionMs: Long = positionMs

    fun move(x: Float, y: Float): Long {
        val wantFine = abs(y - startY) >= fineThresholdPx
        if (wantFine != fine) {
            fine = wantFine
            anchorX = x
            anchorPositionMs = positionMs
            if (fine) {
                val half = fineWindowMs / 2
                rangeStart = (positionMs - half).coerceAtLeast(0L)
                rangeEnd = (positionMs + half).coerceAtMost(durationMs)
            } else {
                rangeStart = 0L
                rangeEnd = durationMs
            }
            return positionMs
        }
        val span = if (fine) fineWindowMs.coerceAtMost(durationMs) else durationMs
        val delta = (((x - anchorX) / widthPx.coerceAtLeast(1f)) * span).toLong()
        positionMs = (anchorPositionMs + delta).coerceIn(rangeStart, rangeEnd)
        return positionMs
    }
}

@Composable
internal fun PrecisionSeekBar(
    positionProvider: () -> Long,
    duration: Long,
    enabled: Boolean,
    loopStart: Long,
    loopEnd: Long,
    onPreview: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    bufferedProvider: () -> Long = { 0L },
    onFineBounds: ((LongRange?) -> Unit)? = null,
    previewCaptionProvider: ((Long) -> String?)? = null,
    onScrubbingChange: ((Boolean) -> Unit)? = null,
) {
    var drag by remember { mutableStateOf<PrecisionScrubber?>(null) }
    var preview by remember { mutableStateOf<Long?>(null) }
    var fine by remember { mutableStateOf(false) }
    var showFineHint by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    var pillWidthPx by remember { mutableIntStateOf(0) }
    var pillHeightPx by remember { mutableIntStateOf(0) }
    val seek by rememberUpdatedState(onSeek)
    val update by rememberUpdatedState(onPreview)
    val updateBounds by rememberUpdatedState(onFineBounds)
    val scrubbingChange by rememberUpdatedState(onScrubbingChange)
    val positionLookup by rememberUpdatedState(positionProvider)
    val haptics = rememberWhispHaptics()
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest

    val isDragging = preview != null
    LaunchedEffect(isDragging, fine) {
        if (isDragging && !fine) {
            delay(450L)
            showFineHint = true
        } else {
            showFineHint = false
        }
    }

    val motionSpec =
        if (reduced) snap<Float>()
        else
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )
    val trackStrokeDp by
        animateFloatAsState(if (isDragging) 6f else 4f, motionSpec, label = "seek-stroke")
    val thumbRadiusDp by
        animateFloatAsState(if (isDragging) 8.5f else 5.5f, motionSpec, label = "seek-thumb")
    val haloRadiusDp by
        animateFloatAsState(if (isDragging) 16f else 0f, motionSpec, label = "seek-halo")

    fun clear() {
        drag = null
        preview = null
        fine = false
        showFineHint = false
        update(null)
        updateBounds?.invoke(null)
        scrubbingChange?.invoke(false)
    }

    Box(Modifier.fillMaxWidth().height(46.dp).onSizeChanged { barWidthPx = it.width }) {
        val currentPreview = preview
        if (currentPreview != null) {
            val start = if (fine) drag?.rangeStart ?: 0L else 0L
            val end = if (fine) drag?.rangeEnd ?: duration else duration
            val span = (end - start).coerceAtLeast(1L)
            val padPx = with(density) { 8.dp.toPx() }
            val usableWidth = (barWidthPx - padPx * 2f).coerceAtLeast(1f)
            val fraction = ((currentPreview - start).toFloat() / span).coerceIn(0f, 1f)
            val centerX = padPx + fraction * usableWidth
            val maxLeft = (barWidthPx - pillWidthPx).coerceAtLeast(0)
            val pillLeft = (centerX - pillWidthPx / 2f).roundToInt().coerceIn(0, maxLeft)
            // Float cleanly above the thumb so fingers never occlude the timestamp or cue preview.
            val aboveOffsetY = -(pillHeightPx + with(density) { 26.dp.toPx() }.roundToInt())
            val caption = previewCaptionProvider?.invoke(currentPreview)?.takeIf { it.isNotBlank() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(pillLeft, aboveOffsetY),
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                Column(
                    Modifier.onSizeChanged {
                            pillWidthPx = it.width
                            pillHeightPx = it.height
                        }
                        .widthIn(max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            0.5.dp,
                            if (fine) Rose.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (caption != null) {
                        Text(
                            caption,
                            color = Cream,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (fine) "${clock(currentPreview)} · 精细" else clock(currentPreview),
                            color = Rose,
                            style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                        if (showFineHint && !fine) {
                            Text(
                                "上下拉微调",
                                color = Muted.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        val semanticPositionMs by remember {
            derivedStateOf { (positionLookup().coerceAtLeast(0L) / 1000L) * 1000L }
        }
        Canvas(
            Modifier.fillMaxSize()
                .testTag("seek-bar")
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            semanticPositionMs.toFloat(),
                            0f..duration.coerceAtLeast(1).toFloat(),
                        )
                    if (!enabled) disabled()
                    setProgress { value ->
                        if (enabled) {
                            seek(value.toLong().coerceIn(0, duration))
                            true
                        } else false
                    }
                }
                .pointerInput(duration, enabled) {
                    if (!enabled) return@pointerInput
                    val pad = 8.dp.toPx()
                    val fineThreshold = 36.dp.toPx()
                    val thumbGrabTolerance = 26.dp.toPx()
                    val adaptiveFineWindow = if (duration in 1L..300_000L) 30_000L else 60_000L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val usable = (size.width - pad * 2f).coerceAtLeast(1f)
                        val downX = (down.position.x - pad).coerceIn(0f, usable)
                        val currentPos = positionLookup().coerceIn(0L, duration)
                        val thumbX =
                            if (duration > 0L) (currentPos.toFloat() / duration) * usable else 0f
                        val grabNearThumb = abs(downX - thumbX) <= thumbGrabTolerance
                        val session =
                            PrecisionScrubber(
                                durationMs = duration,
                                widthPx = usable,
                                startX = downX,
                                startY = down.position.y,
                                fineThresholdPx = fineThreshold,
                                fineWindowMs = adaptiveFineWindow,
                                initialPositionMs = if (grabNearThumb) currentPos else null,
                            )
                        drag = session
                        preview = session.positionMs
                        fine = false
                        haptics.tick()
                        scrubbingChange?.invoke(true)
                        update(preview)
                        updateBounds?.invoke(null)
                        down.consume()

                        var committed = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change =
                                event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                            if (!change.pressed) {
                                if (change.isConsumed) committed = false
                                change.consume()
                                break
                            }
                            change.consume()
                            val wasFine = session.fine
                            val next = session.move(change.position.x - pad, change.position.y)
                            preview = next
                            fine = session.fine
                            if (fine != wasFine) {
                                haptics.tick()
                                updateBounds?.invoke(
                                    if (fine) session.rangeStart..session.rangeEnd else null,
                                )
                            }
                            update(next)
                        }
                        val finalPos = preview
                        clear()
                        if (committed && finalPos != null) seek(finalPos)
                    }
                },
        ) {
            val pad = 8.dp.toPx()
            val usableWidth = (size.width - pad * 2f).coerceAtLeast(1f)
            val start = if (fine) drag?.rangeStart ?: 0L else 0L
            val end = if (fine) drag?.rangeEnd ?: duration else duration
            val span = (end - start).coerceAtLeast(1L)
            fun x(time: Long) =
                pad + ((time - start).toFloat() / span).coerceIn(0f, 1f) * usableWidth
            val y = size.height * 0.54f
            val trackStroke = trackStrokeDp.dp.toPx()
            val tint = if (enabled) Rose else Muted.copy(alpha = .35f)

            // Base track
            drawLine(
                trackBg,
                Offset(pad, y),
                Offset(size.width - pad, y),
                trackStroke,
                StrokeCap.Round,
            )

            // Fine-mode 5-second scale ticks
            if (fine && end > start) {
                val firstTick = ((start / 5_000L) + 1) * 5_000L
                var tick = firstTick
                while (tick < end) {
                    val tx = x(tick)
                    val major = tick % 15_000L == 0L
                    val halfH = if (major) 6.dp.toPx() else 3.5.dp.toPx()
                    drawLine(
                        Muted.copy(alpha = if (major) 0.45f else 0.25f),
                        Offset(tx, y - halfH),
                        Offset(tx, y + halfH),
                        1.dp.toPx(),
                        StrokeCap.Round,
                    )
                    tick += 5_000L
                }
            }

            // Buffered range (only in normal non-fine scale)
            val buffered = bufferedProvider()
            if (!fine && buffered > 0L && duration > 0L) {
                drawLine(
                    Muted.copy(alpha = 0.26f),
                    Offset(pad, y),
                    Offset(x(buffered.coerceAtMost(duration)), y),
                    trackStroke,
                    StrokeCap.Round,
                )
            }

            // A-B loop range and markers
            if (loopStart >= 0) {
                if (loopEnd > loopStart && loopEnd >= start && loopStart <= end) {
                    drawLine(
                        Rose.copy(alpha = .34f),
                        Offset(x(loopStart), y),
                        Offset(x(loopEnd), y),
                        10.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
                listOf(loopStart, loopEnd)
                    .filter { it in start..end }
                    .forEach { marker ->
                        drawLine(
                            Rose,
                            Offset(x(marker), y - 8.dp.toPx()),
                            Offset(x(marker), y + 8.dp.toPx()),
                            2.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
            }

            // Played progress + thumb
            val value = preview ?: positionProvider()
            val thumbX = x(value)
            drawLine(tint, Offset(pad, y), Offset(thumbX, y), trackStroke, StrokeCap.Round)
            if (haloRadiusDp > 0.5f) {
                drawCircle(Rose.copy(alpha = 0.22f), haloRadiusDp.dp.toPx(), Offset(thumbX, y))
            }
            drawCircle(tint, thumbRadiusDp.dp.toPx(), Offset(thumbX, y))
        }
    }
}
