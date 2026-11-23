// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

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
) {
    var drag by remember { mutableStateOf<PrecisionScrubber?>(null) }
    var preview by remember { mutableStateOf<Long?>(null) }
    var fine by remember { mutableStateOf(false) }
    val seek by rememberUpdatedState(onSeek)
    val update by rememberUpdatedState(onPreview)
    fun clear() {
        drag = null
        preview = null
        fine = false
        update(null)
    }
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.TopCenter) {
        if (preview != null) {
            Text(
                if (fine) "${clock(preview!!)} · 精细" else clock(preview!!),
                color = Rose,
                fontSize = 14.sp,
            )
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
                                seek(
                                    (duration * point.x / size.width)
                                        .toLong()
                                        .coerceIn(0, duration),
                                )
                            },
                        )
                    }
                }
                .pointerInput(duration, enabled) {
                    if (enabled) {
                        detectDragGestures(
                            onDragStart = { point ->
                                drag =
                                    PrecisionScrubber(
                                        duration,
                                        size.width.toFloat(),
                                        point.x,
                                        point.y,
                                        36.dp.toPx(),
                                    )
                                preview = drag!!.positionMs
                                update(preview)
                            },
                            onDragCancel = { clear() },
                            onDragEnd = {
                                preview?.let(seek)
                                clear()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                drag?.let { session ->
                                    preview = session.move(change.position.x, change.position.y)
                                    fine = session.fine
                                    update(preview)
                                }
                            },
                        )
                    }
                },
        ) {
            val start = if (fine) drag?.rangeStart ?: 0 else 0
            val end = if (fine) drag?.rangeEnd ?: duration else duration
            val span = (end - start).coerceAtLeast(1)
            fun x(time: Long) = ((time - start).toFloat() / span).coerceIn(0f, 1f) * size.width
            val y = 38.dp.toPx()
            val tint = if (enabled) Rose else Muted.copy(alpha = .35f)
            drawLine(Raised, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            if (loopStart >= 0) {
                if (loopEnd > loopStart && loopEnd >= start && loopStart <= end) {
                    drawLine(
                        Rose.copy(alpha = .35f),
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
                        )
                    }
            }
            val value = preview ?: positionProvider()
            drawLine(tint, Offset(0f, y), Offset(x(value), y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(tint, if (preview != null) 8.dp.toPx() else 5.dp.toPx(), Offset(x(value), y))
        }
    }
}
