// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.whisp.data.TrackEntity
import app.whisp.data.WorkEntity
import app.whisp.data.isVideo
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalArtworkTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalArtworkVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
internal val LocalShareArtwork = staticCompositionLocalOf { true }
internal val LocalCoverControls = staticCompositionLocalOf { false }

fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

internal fun shortTitle(value: String) = value.substringAfter('～', value).trim('～', ' ', '【', '】')

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Artwork(
    model: AppViewModel,
    id: String?,
    description: String?,
    modifier: Modifier,
    scale: ContentScale = ContentScale.Crop,
    sharedKey: String? = null,
    catalogUrl: String? = null,
    catalogSourceId: String? = null,
    officialUrl: String? = null,
) {
    // Per-asset revision avoids recomposing every visible cover whenever an unrelated cover
    // finishes.
    val globalRevision by model.repository.artworkRevision.collectAsStateWithLifecycle()
    val assetRevisionFlow =
        remember(model, id) { id?.let { model.repository.artworkAssetRevision(it) } }
    val assetRevision by
        (assetRevisionFlow?.collectAsStateWithLifecycle() ?: remember { mutableIntStateOf(0) })
    val reload by model.repository.artworkReload.collectAsStateWithLifecycle()
    val file = remember(id, globalRevision, assetRevision) { model.repository.artwork(id) }
    LaunchedEffect(id, reload, file == null, catalogUrl, catalogSourceId, officialUrl) {
        if (file == null)
            id?.let {
                try {
                    if (officialUrl != null) model.repository.ensureDlsiteImage(it, officialUrl)
                    else if (catalogUrl != null && catalogSourceId != null)
                        model.repository.ensureCatalogArtwork(it, catalogUrl, catalogSourceId)
                    else model.repository.ensureArtwork(it)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
    }
    val shared = LocalArtworkTransition.current
    val artworkModifier =
        if (
            sharedKey != null &&
                shared != null &&
                LocalShareArtwork.current &&
                !LocalReducedMotion.current
        ) {
            val visibility = LocalArtworkVisibility.current ?: LocalNavAnimatedContentScope.current
            with(shared) {
                modifier.sharedElement(
                    rememberSharedContentState("cover:$sharedKey"),
                    visibility,
                    boundsTransform = { _, _ -> tween(280, easing = FastOutSlowInEasing) },
                )
            }
        } else modifier
    // Remeasure a center-cropped image at each shared bound: never stretch the illustration.
    val context = LocalContext.current
    val fileSignature =
        remember(file, assetRevision, globalRevision) {
            file?.let { "${it.absolutePath}|${it.lastModified()}|${it.length()}" }
        }
    val request =
        remember(file, fileSignature) {
            ImageRequest.Builder(context)
                .data(file)
                // Files already live in the app cache; a second Coil copy only doubles IO.
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCacheKey(fileSignature)
                .build()
        }
    var loaded by remember(fileSignature) { mutableStateOf(false) }
    AsyncImage(
        request,
        description,
        artworkModifier.semantics { stateDescription = if (loaded) "封面已加载" else "封面加载中" },
        contentScale = scale,
        onSuccess = { loaded = true },
        onError = {
            if (file != null)
                android.util.Log.w(
                    "WhispArtwork",
                    "Image load failed: ${it.result.throwable.javaClass.simpleName}",
                )
        },
    )
}

@Composable
internal fun IconAction(icon: ImageVector, label: String, tint: Color = Cream, action: () -> Unit) =
    IconAction(icon, label, tint, true, action = action)

@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color = Cream,
    enabled: Boolean,
    feedback: Boolean = false,
    action: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val scale by
        animateFloatAsState(if (pressed && !reduced) .92f else 1f, tween(90), label = "icon-press")
    val color by
        animateColorAsState(
            if (!enabled) Muted.copy(alpha = .3f) else if (pressed) Rose else tint,
            tween(100),
            label = "icon-color",
        )
    // Animate only the glyph, preserving the full 48 dp hit area.
    IconButton(
        onClick = {
            if (feedback) haptics.confirm()
            action()
        },
        enabled = enabled,
        interactionSource = interaction,
    ) {
        Box(
            Modifier.size(40.dp)
                .clip(CircleShape)
                .background(
                    if (LocalCoverControls.current) Ink.copy(alpha = .64f) else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                label,
                tint = color,
                modifier =
                    Modifier.size(23.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

@Composable
internal fun WorkCard(
    work: WorkEntity,
    model: AppViewModel,
    featured: Boolean,
    catalogUrl: String? = null,
    catalogSourceId: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(if (featured) 1.25f else .76f)
            .clip(RoundedCornerShape(if (featured) 24.dp else 18.dp))
            .background(Surface)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            model,
            work.coverId,
            work.title,
            Modifier.matchParentSize(),
            sharedKey = work.id,
            catalogUrl = catalogUrl,
            catalogSourceId = catalogSourceId,
        )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Ink.copy(alpha = .04f), Ink.copy(alpha = .94f)),
                    ),
                ),
        )
        Text(
            shortTitle(work.title),
            color = Cream,
            fontFamily = FontFamily.Serif,
            fontSize = if (featured) 26.sp else 19.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.align(Alignment.BottomStart).padding(if (featured) 22.dp else 14.dp),
        )
    }
}

@Composable
internal fun TrackRow(track: TrackEntity, active: Boolean, click: () -> Unit) {
    val background by
        animateColorAsState(
            if (active) Raised else Color.Transparent,
            tween(100),
            label = "track-selection",
        )
    val titleColor by
        animateColorAsState(if (active) Rose else Cream, tween(100), label = "track-title")
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = click)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (track.order < 9999) "%02d".format(track.order) else "·",
            color = if (active) Rose else Muted,
            fontSize = 13.sp,
            modifier = Modifier.width(32.dp),
        )
        Text(
            track.title,
            color = titleColor,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.isVideo)
            Icon(
                Icons.Outlined.Videocam,
                "视频",
                tint = Muted,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        if (track.durationMs > 0) Text(clock(track.durationMs), color = Muted, fontSize = 12.sp)
    }
}
