// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import app.whisp.data.ProgressEntity
import app.whisp.data.TrackEntity
import app.whisp.data.TrackSummary
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import app.whisp.data.isVideo
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val requestData = remember(image, epoch) { image?.withEpoch(epoch) }
    val needSoftwareBitmap = onAmbientColor != null
    val currentOnAmbient by rememberUpdatedState(onAmbientColor)
    val latestDiskKey by rememberUpdatedState(requestData?.diskKey)
    val imageRequest =
        remember(requestData, reduced, platformContext, needSoftwareBitmap) {
            requestData?.let { data ->
                ImageRequest.Builder(platformContext)
                    .data(data)
                    .crossfade(if (reduced) 0 else 200)
                    .apply { if (needSoftwareBitmap) allowHardware(false) }
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
    Box(
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
                    WhispIcons.Outlined.Headphones,
                    description,
                    tint = Muted.copy(alpha = .42f),
                    modifier =
                        Modifier.fillMaxSize(0.28f)
                            .sizeIn(
                                minWidth = 20.dp,
                                minHeight = 20.dp,
                                maxWidth = 54.dp,
                                maxHeight = 54.dp,
                            ),
                )
            }
        } else {
            if (!loaded && !reduced) {
                val pulse = rememberInfiniteTransition(label = "artwork-pulse")
                val alphaState =
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
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(raised.copy(alpha = alphaState.value))
                    },
                )
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
                            currentOnAmbient?.invoke(cached)
                        } else {
                            val resultImage = success.result.image
                            scope.launch {
                                val sampled =
                                    withContext(Dispatchers.Default) {
                                        runCatching {
                                            val bmp = resultImage.toBitmap(24, 24)
                                            sampleAmbientColor(cacheKey, bmp)
                                        }
                                            .getOrNull()
                                    }
                                if (sampled != null && latestDiskKey == cacheKey) {
                                    currentOnAmbient?.invoke(sampled)
                                }
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
                            WhispIcons.Outlined.DownloadDone,
                            null,
                            tint = Cream.copy(alpha = 0.9f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (work.favorite) {
                        Icon(
                            WhispIcons.Filled.Favorite,
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
                WhispIcons.Outlined.GraphicEq,
                null,
                tint = Rose,
                modifier = Modifier.padding(end = 6.dp).size(16.dp),
            )
        }
        if (track.isVideo) {
            Icon(
                WhispIcons.Outlined.Videocam,
                "视频",
                tint = if (active) Rose.copy(alpha = 0.85f) else Muted,
                modifier = Modifier.padding(end = 6.dp).size(16.dp),
            )
        }
        if (track.subtitleId != null) {
            Icon(
                WhispIcons.Outlined.Subtitles,
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
internal fun PagedWorkGrid(
    items: androidx.paging.compose.LazyPagingItems<WorkEntity>,
    imageOf: (WorkEntity) -> WhispImage?,
    onClick: (WorkEntity) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    tag: String? = null,
    progressOf: ((WorkEntity) -> Float?)? = null,
    offlineOf: ((WorkEntity) -> Boolean)? = null,
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
        items(
            count = items.itemCount,
            key = { index -> items.peek(index)?.id ?: "placeholder-$index" },
        ) { index ->
            val item = items[index] ?: return@items
            WorkCard(
                work = item,
                image = imageOf(item),
                click = { onClick(item) },
                progressFraction = progressOf?.invoke(item),
                offline = offlineOf?.invoke(item) == true,
            )
        }
        footer?.invoke(this)
    }
}
