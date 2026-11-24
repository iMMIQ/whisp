// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.get
import app.whisp.R
import app.whisp.data.ProgressEntity
import app.whisp.data.TextEncoding
import app.whisp.data.TrackEntity
import app.whisp.data.TrackSummary
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import app.whisp.data.isVideo
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.toBitmap
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalArtworkTransition = compositionLocalOf<SharedTransitionScope?> { null }

internal val LocalArtworkVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

internal val LocalShareArtwork = compositionLocalOf { true }

internal val LocalCoverControls = compositionLocalOf { false }

internal val LocalImageCacheEpoch = compositionLocalOf { 0 }

internal val LocalSnackbarHost = compositionLocalOf<SnackbarHostState?> { null }

internal fun workProgressFraction(
    tracks: List<TrackSummary>,
    progress: ProgressEntity?,
): Float? {
    if (progress == null) return null
    if (tracks.isEmpty()) return null
    val index = tracks.indexOfFirst { it.id == progress.trackId }
    if (index < 0) return null
    if (index == 0 && progress.positionMs <= 0L) return null
    val totalDuration = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
    val allKnown = tracks.all { it.durationMs > 0L }
    return if (allKnown && totalDuration > 0L) {
        val before = tracks.take(index).sumOf { it.durationMs }
        val current = progress.positionMs.coerceIn(0L, tracks[index].durationMs)
        ((before + current).toFloat() / totalDuration.toFloat()).coerceIn(0.04f, 1f)
    } else if (tracks.size > 1) {
        val trackDur = tracks[index].durationMs
        val inTrack =
            if (trackDur > 0L) (progress.positionMs.toFloat() / trackDur).coerceIn(0f, 1f)
            else if (progress.positionMs > 0L) 0.35f else 0f
        ((index + inTrack) / tracks.size.toFloat()).coerceIn(0.04f, 0.98f)
    } else {
        val dur = tracks[0].durationMs
        if (dur > 0L) (progress.positionMs.toFloat() / dur).coerceIn(0.04f, 1f) else 0.12f
    }
}

private val ambientColorCache = LruCache<String, Int>(48)

internal fun sampleAmbientColor(key: String, bitmap: Bitmap): Color {
    ambientColorCache[key]?.let {
        return Color(it)
    }
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return RoseContainer
    val stepX = (width / 8).coerceAtLeast(1)
    val stepY = (height / 8).coerceAtLeast(1)
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var weightSum = 0L
    val hsv = FloatArray(3)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap[x, y]
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val weight = (1 + (hsv[1] * 4f).toInt()).toLong()
            redSum += r * weight
            greenSum += g * weight
            blueSum += b * weight
            weightSum += weight
            x += stepX
        }
        y += stepY
    }
    if (weightSum <= 0L) return RoseContainer
    val avgR = (redSum / weightSum).toInt().coerceIn(0, 255)
    val avgG = (greenSum / weightSum).toInt().coerceIn(0, 255)
    val avgB = (blueSum / weightSum).toInt().coerceIn(0, 255)
    android.graphics.Color.RGBToHSV(avgR, avgG, avgB, hsv)
    // Strictly clamp saturation and brightness so ambient backdrop remains calm and nocturnal.
    hsv[1] = hsv[1].coerceIn(0.18f, 0.52f)
    hsv[2] = hsv[2].coerceIn(0.18f, 0.34f)
    val color = Color(android.graphics.Color.HSVToColor(hsv))
    ambientColorCache.put(key, color.toArgb())
    return color
}

internal fun cachedAmbientColor(key: String?): Color? =
    key?.let { ambientColorCache[it] }?.let { Color(it) }

private fun WhispImage.withEpoch(epoch: Int): WhispImage =
    when (this) {
        is WhispImage.Asset -> copy(epoch = epoch)
        is WhispImage.Catalog -> copy(epoch = epoch)
        is WhispImage.Official -> copy(epoch = epoch)
    }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Artwork(
    image: WhispImage?,
    description: String?,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    sharedKey: String? = null,
    onAmbientColor: ((Color) -> Unit)? = null,
    onAspectRatio: ((Float) -> Unit)? = null,
) {
    val epoch = LocalImageCacheEpoch.current
    val reduced = LocalReducedMotion.current
    val platformContext = LocalPlatformContext.current
    val requestData = remember(image, epoch) { image?.withEpoch(epoch) }
    val imageRequest =
        remember(requestData, reduced, platformContext) {
            requestData?.let { data ->
                ImageRequest.Builder(platformContext)
                    .data(data)
                    .crossfade(if (reduced) 0 else 200)
                    .build()
            }
        }
    var loaded by remember(requestData) { mutableStateOf(false) }
    var failed by remember(requestData) { mutableStateOf(false) }
    val transition = LocalArtworkTransition.current
    val visibility = LocalArtworkVisibility.current
    val share = LocalShareArtwork.current && !reduced
    val sharedModifier =
        if (transition != null && visibility != null && sharedKey != null && share) {
            with(transition) {
                Modifier.sharedBounds(
                    rememberSharedContentState("cover:$sharedKey"),
                    visibility,
                    boundsTransform = { _, _ -> tween(280) },
                )
            }
        } else Modifier
    val surface = MaterialTheme.colorScheme.surface
    val raised = MaterialTheme.colorScheme.surfaceContainerHighest
    BoxWithConstraints(
        modifier
            .then(sharedModifier)
            .background(surface)
            .then(
                if (requestData != null)
                    Modifier.semantics {
                        stateDescription = if (loaded) "封面已加载" else "封面加载中"
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = (minOf(maxWidth, maxHeight) * 0.28f).coerceIn(20.dp, 54.dp)
        if (requestData == null || failed) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(raised.copy(alpha = 0.72f), surface),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Headphones,
                    description,
                    tint = Muted.copy(alpha = .42f),
                    modifier = Modifier.size(iconSize),
                )
            }
        } else {
            if (!loaded && !reduced) {
                val pulse = rememberInfiniteTransition(label = "artwork-pulse")
                val alpha by
                    pulse.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 0.65f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(900, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "artwork-pulse-alpha",
                    )
                Box(Modifier.fillMaxSize().background(raised.copy(alpha = alpha)))
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = scale,
                onLoading = {
                    loaded = false
                    failed = false
                },
                onError = {
                    loaded = false
                    failed = true
                },
                onSuccess = { success ->
                    loaded = true
                    failed = false
                    if (onAspectRatio != null) {
                        val w = success.result.image.width
                        val h = success.result.image.height
                        if (w > 0 && h > 0) onAspectRatio(w.toFloat() / h.toFloat())
                    }
                    if (onAmbientColor != null) {
                        val cacheKey = requestData.diskKey
                        val cached = cachedAmbientColor(cacheKey)
                        if (cached != null) {
                            onAmbientColor(cached)
                        } else {
                            runCatching {
                                val bmp = success.result.image.toBitmap(24, 24)
                                onAmbientColor(sampleAmbientColor(cacheKey, bmp))
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun WorkCard(
    work: WorkEntity,
    image: WhispImage?,
    click: () -> Unit,
    progressFraction: Float? = null,
    offline: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val surface = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(surface)
            .border(0.5.dp, borderColor, shape)
            .clickable(onClick = click),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
            Artwork(
                image = image,
                description = work.title,
                modifier = Modifier.matchParentSize(),
                sharedKey = work.id,
            )
            if (work.favorite || offline) {
                Row(
                    Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Ink.copy(alpha = .62f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (offline) {
                        Icon(
                            Icons.Outlined.DownloadDone,
                            null,
                            tint = Cream.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (work.favorite) {
                        Icon(
                            Icons.Filled.Favorite,
                            null,
                            tint = Rose,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
            if (progressFraction != null && progressFraction > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Ink.copy(alpha = 0.55f)),
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0.04f, 1f))
                            .background(Rose),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                shortTitle(work.title).ifBlank { work.title },
                style = MaterialTheme.typography.titleSmall,
                color = Cream,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = work.artist.ifBlank { work.rj.orEmpty() }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun <T> WorkGrid(
    items: List<T>,
    key: (T) -> Any,
    workOf: (T) -> WorkEntity,
    imageOf: (T) -> WhispImage?,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    tag: String? = null,
    progressOf: ((T) -> Float?)? = null,
    offlineOf: ((T) -> Boolean)? = null,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier =
            modifier.fillMaxSize().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header?.invoke(this)
        items(items = items, key = { item -> key(item) }) { item ->
            WorkCard(
                work = workOf(item),
                image = imageOf(item),
                click = { onClick(item) },
                progressFraction = progressOf?.invoke(item),
                offline = offlineOf?.invoke(item) == true,
            )
        }
        footer?.invoke(this)
    }
}

@Composable
internal fun TrackRow(track: TrackEntity, active: Boolean, click: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val rowBg = if (active) Rose.copy(alpha = 0.11f) else Color.Transparent
    val rowBorder = if (active) Rose.copy(alpha = 0.24f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(shape)
            .background(rowBg)
            .border(0.5.dp, rowBorder, shape)
            .clickable(onClick = click)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track.order < 9999) {
            Text(
                "%02d".format(track.order),
                color = if (active) Rose else Muted,
                style = MaterialTheme.typography.labelLarge.merge(TabularNumeralStyle),
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.width(30.dp),
            )
        }
        Text(
            track.title,
            color = if (active) Rose else Cream,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        if (active) {
            Icon(
                Icons.Outlined.GraphicEq,
                null,
                tint = Rose,
                modifier = Modifier.padding(end = 6.dp).size(16.dp),
            )
        }
        if (track.isVideo) {
            Icon(
                Icons.Outlined.Videocam,
                "视频",
                tint = if (active) Rose.copy(alpha = 0.85f) else Muted,
                modifier = Modifier.padding(end = 6.dp).size(16.dp),
            )
        }
        if (track.subtitleId != null) {
            Icon(
                Icons.Outlined.Subtitles,
                "含字幕",
                tint = if (active) Rose.copy(alpha = 0.85f) else Muted,
                modifier = Modifier.padding(end = 8.dp).size(16.dp),
            )
        }
        if (track.durationMs > 0) {
            Text(
                clock(track.durationMs),
                color = if (active) Rose.copy(alpha = 0.9f) else Muted,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumeralStyle),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
internal fun WhispSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    clearLabel: String = "清空搜索",
    submitLabel: String = "搜索",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Cream, fontSize = 15.sp),
        placeholder = { Text(placeholder, color = Muted.copy(alpha = 0.8f), fontSize = 15.sp) },
        leadingIcon = {
            Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconAction(Icons.Outlined.Close, clearLabel, Muted) {
                        if (onClear != null) onClear() else onValueChange("")
                    }
                }
                if (onSubmit != null) {
                    IconAction(Icons.Outlined.Search, submitLabel, Rose, action = onSubmit)
                }
            }
        },
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = Rose.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = Rose,
            ),
        keyboardOptions =
            if (onSubmit != null) KeyboardOptions(imeAction = ImeAction.Search)
            else KeyboardOptions.Default,
        keyboardActions =
            if (onSubmit != null) KeyboardActions(onSearch = { onSubmit() })
            else KeyboardActions.Default,
    )
}

@Composable
internal fun <T> WhispTabBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                val bg by
                    animateColorAsState(
                        if (isSelected) Rose.copy(alpha = 0.16f) else Color.Transparent,
                        tween(if (reduced) 0 else 160),
                        label = "tab-bg",
                    )
                val fg by
                    animateColorAsState(
                        if (isSelected) Rose else Muted,
                        tween(if (reduced) 0 else 160),
                        label = "tab-fg",
                    )
                val border by
                    animateColorAsState(
                        if (isSelected) Rose.copy(alpha = 0.30f) else Color.Transparent,
                        tween(if (reduced) 0 else 160),
                        label = "tab-border",
                    )
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .border(0.5.dp, border, RoundedCornerShape(999.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun WhispEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Rose.copy(alpha = 0.85f), modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Cream,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
internal fun WhispSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = Rose,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (enabled) tint.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (enabled) tint else Muted.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Cream else Cream.copy(alpha = 0.4f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Muted else Muted.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Outlined.ChevronRight,
            null,
            tint = if (enabled) Muted.copy(alpha = 0.7f) else Muted.copy(alpha = 0.25f),
            modifier = Modifier.size(18.dp),
        )
    }
}

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

    val dismissProgress =
        (abs(dismissY.value) / (containerSize.height.coerceAtLeast(1) * 0.32f)).coerceIn(0f, 1f)
    val bgAlpha = (0.96f * (1f - dismissProgress * 0.65f)).coerceIn(0.2f, 0.96f)
    val dismissScale = (1f - dismissProgress * 0.14f).coerceIn(0.84f, 1f)

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { containerSize = it }
                .background(ScrimDark.copy(alpha = bgAlpha))
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
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        var crossedThreshold = false
                        var dismissing = false
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount == 0) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
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
                            } else if (abs(panChange.y) > abs(panChange.x) && panChange.y != 0f) {
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
                        val effectiveScale = scaleAnim.value * dismissScale
                        scaleX = effectiveScale
                        scaleY = effectiveScale
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value + dismissY.value
                    },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = showChrome && dismissProgress < 0.12f,
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
                    IconAction(Icons.Outlined.Close, "关闭预览", Cream, action = onDismiss)
                }
            }
        }
    }
}

@Composable
internal fun WhispToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberWhispHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { next ->
                    haptics.tick()
                    onCheckedChange(next)
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Cream else Cream.copy(alpha = 0.45f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun WhispConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = true,
    busy: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, color = Cream) },
        text = { Text(message, color = Muted) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else Rose,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color = Cream,
    enabled: Boolean = true,
    feedback: Boolean = false,
    haptic: HapticKind = if (feedback) HapticKind.Confirm else HapticKind.None,
    action: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    val backdrop = LocalCoverControls.current
    IconButton(
        onClick = {
            when (haptic) {
                HapticKind.Confirm -> haptics.confirm()
                HapticKind.Tick -> haptics.tick()
                HapticKind.None -> Unit
            }
            action()
        },
        enabled = enabled,
        modifier =
            if (backdrop)
                Modifier.padding(2.dp)
                    .clip(CircleShape)
                    .background(Ink.copy(alpha = .46f))
                    .border(0.5.dp, Cream.copy(alpha = 0.12f), CircleShape)
            else Modifier,
    ) {
        Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = .35f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhispSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val sheet =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss = {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    sheet.hide()
                    if (!sheet.isVisible) onDismissRequest()
                } finally {
                    closing = false
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = Cream,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Muted.copy(alpha = 0.45f)) },
    ) {
        content(dismiss)
    }
}

@Composable
internal fun SyncedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    val sliderState = rememberSliderState(value = value, trackRange = range, steps = steps)
    sliderState.value = value
    Slider(state = sliderState, onValueChange = onValueChange)
}

@Composable
internal fun EncodingChips(
    selected: String,
    onSelect: (String) -> Unit,
    horizontalPadding: Dp = 0.dp,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(TextEncoding.supported, key = { it }) { candidate ->
            FilterChip(
                selected = selected == candidate,
                onClick = { onSelect(candidate) },
                label = {
                    Text(
                        if (candidate == TextEncoding.AUTO)
                            stringResource(R.string.label_encoding_auto)
                        else candidate,
                        fontSize = 13.sp,
                    )
                },
            )
        }
    }
}

internal fun clock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

internal fun shortTitle(title: String): String {
    val trimmed = title.trim()
    val body =
        if ('～' in trimmed && !trimmed.startsWith('～')) trimmed.substringAfter('～') else trimmed
    return body.trim(' ', '～', '~', '【', '】', '「', '」', '『', '』')
}
