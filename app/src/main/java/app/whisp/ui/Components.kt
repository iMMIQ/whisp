// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisp.R
import app.whisp.data.TextEncoding
import app.whisp.data.TrackEntity
import app.whisp.data.WhispImage
import app.whisp.data.WorkEntity
import app.whisp.data.isVideo
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalArtworkTransition = compositionLocalOf<SharedTransitionScope?> { null }

internal val LocalArtworkVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

internal val LocalShareArtwork = compositionLocalOf { true }

internal val LocalCoverControls = compositionLocalOf { false }

internal val LocalImageCacheEpoch = compositionLocalOf { 0 }

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
) {
    val epoch = LocalImageCacheEpoch.current
    val request = remember(image, epoch) { image?.withEpoch(epoch) }
    var loaded by remember(request) { mutableStateOf(false) }
    val transition = LocalArtworkTransition.current
    val visibility = LocalArtworkVisibility.current
    val share = LocalShareArtwork.current && !LocalReducedMotion.current
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
    Box(
        modifier
            .then(sharedModifier)
            .background(Surface)
            .then(
                if (request != null)
                    Modifier.semantics {
                        stateDescription = if (loaded) "封面已加载" else "封面加载中"
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (request == null) {
            Icon(Icons.Outlined.Headphones, description, tint = Muted.copy(alpha = .45f))
        } else {
            AsyncImage(
                model = request,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = scale,
                onLoading = { loaded = false },
                onError = { loaded = false },
                onSuccess = { loaded = true },
            )
        }
    }
}

@Composable
internal fun WorkCard(
    work: WorkEntity,
    image: WhispImage?,
    hero: Boolean,
    click: () -> Unit,
) {
    val shape = RoundedCornerShape(if (hero) 24.dp else 18.dp)
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(if (hero) 1.38f else .84f)
            .clip(shape)
            .background(Surface)
            .clickable(onClick = click),
    ) {
        Artwork(
            image = image,
            description = work.title,
            modifier = Modifier.matchParentSize(),
            sharedKey = work.id,
        )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Ink.copy(alpha = .18f), Ink.copy(alpha = .92f)),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(if (hero) 20.dp else 14.dp),
        ) {
            Text(
                shortTitle(work.title).ifBlank { work.title },
                fontFamily = if (hero) FontFamily.Serif else FontFamily.Default,
                fontSize = if (hero) 22.sp else 14.sp,
                lineHeight = if (hero) 28.sp else 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (work.artist.isNotBlank()) {
                Text(
                    work.artist,
                    color = Muted,
                    fontSize = if (hero) 13.sp else 12.sp,
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
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier =
            modifier.fillMaxSize().then(if (tag != null) Modifier.testTag(tag) else Modifier),
        state = state,
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> key(item) },
            span = { index, _ -> GridItemSpan(if (index == 0) maxLineSpan else 1) },
        ) { index, item ->
            WorkCard(
                work = workOf(item),
                image = imageOf(item),
                hero = index == 0,
                click = { onClick(item) },
            )
        }
        footer?.invoke(this)
    }
}

@Composable
internal fun TrackRow(track: TrackEntity, active: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = click)
            .padding(horizontal = 28.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track.order < 9999) {
            Text(
                "%02d".format(track.order),
                color = if (active) Rose else Muted,
                fontSize = 14.sp,
                modifier = Modifier.width(30.dp),
            )
        }
        Text(
            track.title,
            color = if (active) Rose else Cream,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        if (track.isVideo) {
            Icon(
                Icons.Outlined.Videocam,
                "视频",
                tint = Muted,
                modifier = Modifier.padding(end = 6.dp).size(17.dp),
            )
        }
        if (track.subtitleId != null) {
            Icon(
                Icons.Outlined.Subtitles,
                "含字幕",
                tint = Muted,
                modifier = Modifier.padding(end = 8.dp).size(17.dp),
            )
        }
        if (track.durationMs > 0) {
            Text(clock(track.durationMs), color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color = Cream,
    enabled: Boolean = true,
    feedback: Boolean = false,
    action: () -> Unit,
) {
    val haptics = rememberWhispHaptics()
    val backdrop = LocalCoverControls.current
    IconButton(
        onClick = {
            if (feedback) haptics.confirm()
            action()
        },
        enabled = enabled,
        modifier =
            if (backdrop)
                Modifier.padding(2.dp).clip(CircleShape).background(Ink.copy(alpha = .42f))
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
        containerColor = Surface,
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
