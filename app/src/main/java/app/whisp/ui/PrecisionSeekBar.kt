// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure fine-scrubbing gesture state: pulling vertically away from the bar zooms to a 60s window.
 */
class PrecisionScrubber(
    private val durationMs: Long,
    private val widthPx: Float,
    startX: Float,
    private val startY: Float,
    private val fineThresholdPx: Float,
    private val fineWindowMs: Long = 60_000L,
) {
    var positionMs: Long =
        ((startX / widthPx.coerceAtLeast(1f)) * durationMs).toLong().coerceIn(0L, durationMs)
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
) {
    var drag by remember { mutableStateOf<PrecisionScrubber?>(null) }
    var preview by remember { mutableStateOf<Long?>(null) }
    var fine by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }
    var pillWidthPx by remember { mutableIntStateOf(0) }
    val seek by rememberUpdatedState(onSeek)
    val update by rememberUpdatedState(onPreview)
    val updateBounds by rememberUpdatedState(onFineBounds)
    val haptics = rememberWhispHaptics()
    val density = LocalDensity.current
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest

    fun clear() {
        drag = null
        preview = null
        fine = false
        update(null)
        updateBounds?.invoke(null)
    }

    Box(
        Modifier.fillMaxWidth().height(58.dp).onSizeChanged { barWidthPx = it.width },
    ) {
        if (preview != null) {
            val start = if (fine) drag?.rangeStart ?: 0L else 0L
            val end = if (fine) drag?.rangeEnd ?: duration else duration
            val span = (end - start).coerceAtLeast(1L)
            val padPx = with(density) { 8.dp.toPx() }
            val usableWidth = (barWidthPx - padPx * 2f).coerceAtLeast(1f)
            val fraction = ((preview!! - start).toFloat() / span).coerceIn(0f, 1f)
            val centerX = padPx + fraction * usableWidth
            val maxLeft = (barWidthPx - pillWidthPx).coerceAtLeast(0)
            val pillLeft = (centerX - pillWidthPx / 2f).roundToInt().coerceIn(0, maxLeft)
            Box(
                Modifier.align(Alignment.TopStart)
                    .offset { IntOffset(pillLeft, 0) }
                    .onSizeChanged { pillWidthPx = it.width }
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        0.5.dp,
                        if (fine) Rose.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    if (fine) "${clock(preview!!)} · 精细" else clock(preview!!),
                    color = Rose,
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumeralStyle),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .testTag("seek-bar")
                .semantics {
                    contentDescription = "播放进度"
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(
                            positionProvider().toFloat(),
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
                    if (enabled) {
                        detectTapGestures(
                            onTap = { point ->
                                val pad = 8.dp.toPx()
                                val usable = (size.width - pad * 2f).coerceAtLeast(1f)
                                val frac = ((point.x - pad) / usable).coerceIn(0f, 1f)
                                seek((duration * frac).toLong().coerceIn(0, duration))
                            },
                        )
                    }
                }
                .pointerInput(duration, enabled) {
                    if (enabled) {
                        detectDragGestures(
                            onDragStart = { point ->
                                val pad = 8.dp.toPx()
                                val usable = (size.width - pad * 2f).coerceAtLeast(1f)
                                drag =
                                    PrecisionScrubber(
                                        duration,
                                        usable,
                                        (point.x - pad).coerceIn(0f, usable),
                                        point.y,
                                        36.dp.toPx(),
                                    )
                                preview = drag!!.positionMs
                                update(preview)
                                updateBounds?.invoke(null)
                            },
                            onDragCancel = { clear() },
                            onDragEnd = {
                                preview?.let(seek)
                                clear()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                drag?.let { session ->
                                    val pad = 8.dp.toPx()
                                    val wasFine = session.fine
                                    preview =
                                        session.move(change.position.x - pad, change.position.y)
                                    fine = session.fine
                                    if (fine != wasFine) {
                                        haptics.tick()
                                        updateBounds?.invoke(
                                            if (fine) session.rangeStart..session.rangeEnd
                                            else null,
                                        )
                                    }
                                    update(preview)
                                }
                            },
                        )
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
            val y = 40.dp.toPx()
            val isDragging = preview != null
            val trackStroke = if (isDragging) 5.5.dp.toPx() else 4.dp.toPx()
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
            if (isDragging) {
                drawCircle(Rose.copy(alpha = 0.22f), 14.dp.toPx(), Offset(thumbX, y))
            }
            drawCircle(
                tint,
                if (isDragging) 7.5.dp.toPx() else 5.5.dp.toPx(),
                Offset(thumbX, y),
            )
        }
    }
}
