// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whisp.AppGraph
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Subtitle auto-scroll policy: animate only single-step transitions while visible. */
internal object SubtitleFollow {
    fun animate(previous: Int, current: Int, visible: Boolean, reducedMotion: Boolean): Boolean =
        !reducedMotion &&
            visible &&
            previous >= 0 &&
            previous != current &&
            abs(current - previous) <= 1
}

@Composable
internal fun EmbeddedScriptPane(
    scripts: List<AttachmentEntity>,
    preferredId: String?,
    graph: AppGraph,
    encoding: String,
    fontSizeSp: Int,
    modifier: Modifier,
) {
    var selectedId by
        rememberSaveable(scripts.map { it.id }) {
            mutableStateOf(preferredId ?: scripts.first().id)
        }
    LaunchedEffect(preferredId) {
        if (preferredId != null && scripts.any { it.id == preferredId }) {
            selectedId = preferredId
        }
    }
    val selected = scripts.firstOrNull { it.id == selectedId } ?: scripts.first()
    val scrollStates = remember { mutableMapOf<String, LazyListState>() }
    val listState = scrollStates.getOrPut(selected.id) { LazyListState() }
    var rawBytes by remember(selected.id) { mutableStateOf<ByteArray?>(null) }
    var chunks by remember(selected.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(selected.id) { mutableStateOf(true) }
    var error by remember(selected.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(selected.id) {
        loading = true
        error = null
        chunks = null
        try {
            val bytes = graph.library.readAsset(selected.id, 64 * 1024 * 1024)
            rawBytes = bytes
            chunks =
                withContext(Dispatchers.Default) {
                    chunkLongText(TextEncoding.decode(bytes, encoding))
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "台本读取失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(rawBytes, encoding) {
        val bytes = rawBytes ?: return@LaunchedEffect
        chunks =
            withContext(Dispatchers.Default) {
                chunkLongText(TextEncoding.decode(bytes, encoding))
            }
    }

    Column(modifier.testTag("script-pane")) {
        if (scripts.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scripts, key = { it.id }) { item ->
                    FilterChip(
                        selected = item.id == selected.id,
                        onClick = { selectedId = item.id },
                        label = {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                loading ->
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        color = Rose,
                        strokeWidth = 2.dp,
                    )
                error != null ->
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                chunks != null ->
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            itemsIndexed(chunks!!, key = { idx, _ -> idx }) { _, chunk ->
                                Text(
                                    chunk,
                                    fontSize = (fontSizeSp - 2).coerceIn(14, 26).sp,
                                    lineHeight = (fontSizeSp + 8).coerceIn(22, 38).sp,
                                    color = Cream.copy(alpha = 0.92f),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
internal fun PlayerSubtitles(
    track: TrackEntity,
    activeTrackId: String?,
    cues: List<Cue>,
    displayCues: List<DisplayCue>,
    loading: Boolean,
    subtitleError: String?,
    embeddedSubtitle: String,
    settings: Settings,
    trackOffsetMs: Long,
    fallbackPosition: Long,
    scrubPreviewState: State<Long?>,
    followSignal: Int,
    playback: PlaybackCoordinator,
    onDirectSeek: (Cue) -> Unit,
    onLongPress: (Cue) -> Unit,
    modifier: Modifier,
) {
    if (
        cues.isEmpty() &&
            track.subtitleId == null &&
            embeddedSubtitle.isNotBlank() &&
            activeTrackId == track.id
    ) {
        Box(modifier.testTag("subtitle-pane").padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                embeddedSubtitle,
                color = Cream,
                fontSize = settings.subtitleSize.sp,
                lineHeight = (settings.subtitleSize + 10).sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // Collecting positionMs & reading scrubPreviewState inside PlayerSubtitles isolates
    // high-frequency
    // ticks and seek-bar drags from recomposing PlayerControls or PlayerPage.
    val timeline = playback.positionMs.collectAsStateWithLifecycle()
    key(track.id) {
        val previewing by
            remember(scrubPreviewState) { derivedStateOf { scrubPreviewState.value != null } }
        val cueState by
            remember(
                cues,
                track.id,
                activeTrackId,
                fallbackPosition,
                trackOffsetMs,
                scrubPreviewState,
            ) {
                derivedStateOf(structuralEqualityPolicy()) {
                    val pos =
                        scrubPreviewState.value
                            ?: if (activeTrackId == track.id) timeline.value else fallbackPosition
                    val position = (pos - trackOffsetMs).coerceAtLeast(0)
                    SubtitleTimeline.anchor(cues, position) to
                        SubtitleTimeline.current(cues, position)
                }
            }
        val effectiveDisplay =
            remember(cues, displayCues) {
                if (displayCues.size == cues.size) displayCues
                else cues.map { DisplayCue(it.startMs, it.endMs, it.text) }
            }
        SubtitlePane(
            cues = effectiveDisplay,
            loading = loading,
            error = subtitleError,
            anchor = cueState.first,
            active = cueState.second,
            size = settings.subtitleSize,
            followSignal = followSignal,
            previewing = previewing,
            modifier = modifier,
            onDirectSeek = onDirectSeek,
            onRetry = { playback.subtitles.retry(track.id) },
            onLongPress = onLongPress,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubtitlePane(
    cues: List<DisplayCue>,
    loading: Boolean,
    error: String?,
    anchor: Int,
    active: Int,
    size: Int,
    followSignal: Int,
    previewing: Boolean,
    modifier: Modifier,
    onDirectSeek: (Cue) -> Unit,
    onRetry: () -> Unit,
    onLongPress: (Cue) -> Unit,
) {
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    var previousAnchor by remember { mutableIntStateOf(-1) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(dragging) { if (dragging) following = false }
    LaunchedEffect(followSignal) { if (followSignal > 0) following = true }
    BoxWithConstraints(modifier.testTag("subtitle-pane"), contentAlignment = Alignment.Center) {
        val inset = maxHeight * .24f
        val effectiveFollowing = following || previewing
        LaunchedEffect(anchor, effectiveFollowing, cues, inset, reduced, previewing) {
            if (!effectiveFollowing) {
                previousAnchor = -1
                return@LaunchedEffect
            }
            if (anchor < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor }
            val animate =
                !previewing && SubtitleFollow.animate(previousAnchor, anchor, item != null, reduced)
            previousAnchor = anchor
            if (animate && item != null) {
                list.animateScrollBy(
                    item.offset.toFloat(),
                    tween(180, easing = FastOutSlowInEasing),
                )
            } else {
                list.scrollToItem(anchor)
            }
        }
        if (cues.isEmpty()) {
            if (loading)
                CircularProgressIndicator(Modifier.size(24.dp), color = Rose, strokeWidth = 2.dp)
            else if (error != null)
                FilledTonalButton(onClick = onRetry) { Text("重试字幕", fontSize = 15.sp) }
            else Text("暂无字幕", color = Muted.copy(alpha = 0.7f), fontSize = 16.sp)
        } else {
            val bg = MaterialTheme.colorScheme.background
            val cueShape = remember { RoundedCornerShape(12.dp) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = inset,
                        bottom = maxHeight * .65f,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(cues, key = { index, _ -> index }) { index, cue ->
                    val isActive = index == active
                    val targetColor =
                        when {
                            isActive -> Cream
                            active >= 0 && index < active -> Muted.copy(alpha = 0.42f)
                            else -> Muted.copy(alpha = 0.68f)
                        }
                    val color by
                        animateColorAsState(
                            targetColor,
                            tween(if (reduced) 0 else 140),
                            label = "subtitle-highlight",
                        )
                    val highlightAlphaState =
                        animateFloatAsState(
                            if (isActive) 0.11f else 0f,
                            tween(if (reduced) 0 else 160),
                            label = "subtitle-bg",
                        )
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(cueShape)
                            .drawBehind {
                                val alpha = highlightAlphaState.value
                                if (alpha > 0.001f) drawRect(Rose.copy(alpha = alpha))
                            }
                            .combinedClickable(
                                hapticFeedbackEnabled = LocalTouchFeedback.current,
                                onClickLabel = "跳转到这句",
                                onClick = { onDirectSeek(cue.toCue()) },
                                onLongClickLabel = "字幕操作",
                                onLongClick = { onLongPress(cue.toCue()) },
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            cue.text,
                            color = color,
                            fontSize = size.sp,
                            lineHeight = (size + 10).sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("subtitle-$index"),
                        )
                        if (!cue.secondaryText.isNullOrBlank()) {
                            Text(
                                cue.secondaryText,
                                color = color.copy(alpha = if (isActive) 0.78f else 0.56f),
                                fontSize = (size - 4).coerceAtLeast(13).sp,
                                lineHeight = (size + 3).sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            Box(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Brush.verticalGradient(listOf(bg, Color.Transparent))),
            )
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, bg))),
            )
        }
        AnimatedVisibility(
            visible = !following && !previewing && cues.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
            enter =
                fadeIn(tween(if (reduced) 0 else 150)) +
                    slideInVertically(tween(if (reduced) 0 else 150)) { it / 2 },
            exit =
                fadeOut(tween(if (reduced) 0 else 120)) +
                    slideOutVertically(tween(if (reduced) 0 else 120)) { it / 2 },
        ) {
            val pillShape = RoundedCornerShape(999.dp)
            Row(
                Modifier.clip(pillShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(0.5.dp, Rose.copy(alpha = 0.35f), pillShape)
                    .clickable { following = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    WhispIcons.Outlined.MyLocation,
                    "跟随字幕",
                    tint = Rose,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "回到当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = Cream,
                )
            }
        }
    }
}
