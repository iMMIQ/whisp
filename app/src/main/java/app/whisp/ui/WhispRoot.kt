// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.whisp.R
import app.whisp.data.*

@OptIn(ExperimentalSharedTransitionApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WhispRoot(model: AppViewModel, playerRequest: Int = 0) {
    val settings by model.settings.collectAsStateWithLifecycle()
    WhispTheme(reduceMotion = settings.reduceMotion) {
        val library by model.library.collectAsStateWithLifecycle()
        val sources by model.sources.collectAsStateWithLifecycle()
        val state by model.player.collectAsStateWithLifecycle()
        val queueTracks by model.queueTracks.collectAsStateWithLifecycle()
        val progress by model.progress.collectAsStateWithLifecycle()
        val opening by model.openingTrackId.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val stack =
            rememberSaveable(saver = whispRouteSaver) {
                mutableStateListOf<WhispRoute>(WhispRoute.Library)
            }
        var handledPlayerRequest by rememberSaveable { mutableIntStateOf(0) }
        val dispatcher =
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!
                .onBackPressedDispatcher
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        // Register before NavDisplay so its gesture handler takes precedence. Enable this
        // fallback synchronously with push: the system can send Back before recomposition.
        val backFallback =
            remember(dispatcher, stack) {
                object : androidx.activity.OnBackPressedCallback(stack.size > 1) {
                    override fun handleOnBackPressed() {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                    }
                }
            }
        DisposableEffect(dispatcher, lifecycleOwner, backFallback) {
            dispatcher.addCallback(lifecycleOwner, backFallback)
            onDispose { backFallback.remove() }
        }
        SideEffect { backFallback.isEnabled = stack.size > 1 }
        val route = stack.last()
        val isPlayerRoute = route is WhispRoute.Player
        LaunchedEffect(model, lifecycleOwner, isPlayerRoute) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.refreshPlayer()
                if (isPlayerRoute) model.observeTimeline()
                else kotlinx.coroutines.awaitCancellation()
            }
        }
        var previousRoute by remember { mutableStateOf(route) }
        val miniTransition =
            remember(route) {
                (route is WhispRoute.Player && previousRoute.isTopLevelTab) ||
                    (previousRoute is WhispRoute.Player && route.isTopLevelTab)
            }
        SideEffect { previousRoute = route }
        val snackbar = remember { SnackbarHostState() }
        val displayedId = opening ?: state.trackId
        val worksById = remember(library) { library.associateBy { it.work.id } }
        val tracksById =
            remember(library) {
                buildMap {
                    library.forEach { work -> work.tracks.forEach { put(it.id, work to it) } }
                }
            }
        val selected =
            tracksById[displayedId]
                ?: queueTracks
                    .firstOrNull { it.id == displayedId }
                    ?.let { track ->
                        worksById[track.workId]?.let { work -> work to track }
                    }
        val current = selected?.first ?: progress.firstOrNull()?.let { worksById[it.workId] }
        val currentTrack =
            selected?.second
                ?: tracksById[progress.firstOrNull()?.trackId]?.second?.takeIf {
                    it.workId == current?.work?.id
                }
        fun push(next: WhispRoute, from: WhispRoute? = null) {
            if ((from == null || stack.lastOrNull() == from) && stack.last() != next) {
                backFallback.isEnabled = true
                stack.add(next)
            }
        }
        fun pop(expected: WhispRoute) {
            if (stack.size > 1 && stack.lastOrNull() == expected) stack.removeAt(stack.lastIndex)
        }
        LaunchedEffect(playerRequest, current?.work?.id) {
            if (playerRequest > handledPlayerRequest && current != null) {
                handledPlayerRequest = playerRequest
                push(WhispRoute.Player)
            }
        }
        LaunchedEffect(error) {
            error?.let {
                snackbar.showSnackbar(it)
                model.error.value = null
            }
        }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val shared = this
            CompositionLocalProvider(
                LocalArtworkTransition provides shared,
                LocalTouchFeedback provides settings.touchFeedback,
            ) {
                Scaffold(
                    containerColor = Ink,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        AnimatedVisibility(
                            !isPlayerRoute,
                            enter = fadeIn(tween(if (settings.reduceMotion) 100 else 280)),
                            exit = fadeOut(tween(if (settings.reduceMotion) 100 else 280)),
                        ) {
                            CompositionLocalProvider(LocalArtworkVisibility provides this) {
                                Column(Modifier.background(Ink).navigationBarsPadding()) {
                                    if (current != null && currentTrack != null)
                                        MiniPlayer(
                                            current,
                                            currentTrack,
                                            state,
                                            model,
                                            shareCover = miniTransition,
                                            onClick = { push(WhispRoute.Player) },
                                        )
                                    val tabs =
                                        listOf(
                                            Triple<WhispRoute, _, String>(
                                                WhispRoute.Library,
                                                Icons.Outlined.LibraryMusic,
                                                stringResource(R.string.nav_library),
                                            ),
                                            Triple(
                                                WhispRoute.Favorites,
                                                Icons.Outlined.FavoriteBorder,
                                                stringResource(R.string.nav_favorites),
                                            ),
                                            Triple(
                                                WhispRoute.Sources,
                                                Icons.Outlined.FolderOpen,
                                                stringResource(R.string.nav_sources),
                                            ),
                                        )
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 32.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceAround,
                                    ) {
                                        tabs.forEach { (target, icon, text) ->
                                            TextButton(
                                                onClick = {
                                                    stack.clear()
                                                    stack.add(target)
                                                },
                                                colors =
                                                    ButtonDefaults.textButtonColors(
                                                        contentColor =
                                                            if (route == target) Rose else Muted,
                                                    ),
                                            ) {
                                                Column(
                                                    horizontalAlignment =
                                                        Alignment.CenterHorizontally,
                                                ) {
                                                    Icon(icon, text, Modifier.size(22.dp))
                                                    Text(text, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavDisplay(
                        backStack = stack,
                        onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .consumeWindowInsets(
                                    PaddingValues(top = padding.calculateTopPadding()),
                                ),
                        transitionSpec = {
                            fadeIn(tween(if (settings.reduceMotion) 100 else 280)) togetherWith
                                fadeOut(tween(if (settings.reduceMotion) 100 else 180))
                        },
                        popTransitionSpec = {
                            fadeIn(
                                tween(
                                    if (settings.reduceMotion) 100 else 190,
                                    delayMillis = if (settings.reduceMotion) 0 else 90,
                                ),
                            ) togetherWith fadeOut(tween(if (settings.reduceMotion) 100 else 90))
                        },
                        predictivePopTransitionSpec = { edge ->
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else {
                                val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
                                // NavDisplay seeks this transform with gesture progress and
                                // reverses it on cancel.
                                (fadeIn(tween(280), initialAlpha = .72f) +
                                    scaleIn(tween(280), initialScale = .99f)) togetherWith
                                    (slideOutHorizontally(tween(280)) { direction * it / 6 } +
                                        scaleOut(
                                            tween(280),
                                            targetScale = .94f,
                                            transformOrigin =
                                                TransformOrigin(if (direction > 0) 0f else 1f, .5f),
                                        ) +
                                        fadeOut(tween(180)))
                            }
                        },
                        entryProvider =
                            entryProvider {
                                @Composable
                                fun RouteFrame(
                                    destination: WhispRoute,
                                    content: @Composable () -> Unit,
                                ) {
                                    var bottom by remember {
                                        mutableStateOf(padding.calculateBottomPadding())
                                    }
                                    // Keep the departing page's geometry while bottom chrome is
                                    // removed.
                                    if (
                                        destination == route &&
                                            destination !is WhispRoute.Player &&
                                            !shared.isTransitionActive
                                    )
                                        SideEffect { bottom = padding.calculateBottomPadding() }
                                    CompositionLocalProvider(
                                        LocalShareArtwork provides
                                            (!miniTransition || destination is WhispRoute.Player),
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(Ink)
                                                .padding(
                                                    bottom =
                                                        if (destination is WhispRoute.Player) 0.dp
                                                        else bottom,
                                                ),
                                        ) {
                                            content()
                                        }
                                    }
                                }
                                entry<WhispRoute.Library> { destination ->
                                    RouteFrame(destination) {
                                        LibraryPage(
                                            library,
                                            favoritesOnly = false,
                                            model,
                                            { push(WhispRoute.Detail(it), destination) },
                                            { push(WhispRoute.Sources, destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Favorites> { destination ->
                                    RouteFrame(destination) {
                                        LibraryPage(
                                            library,
                                            favoritesOnly = true,
                                            model,
                                            { push(WhispRoute.Detail(it), destination) },
                                            { push(WhispRoute.Sources, destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Detail> { destination ->
                                    RouteFrame(destination) {
                                        worksById[destination.workId]?.let {
                                            DetailPage(
                                                it,
                                                model,
                                                { pop(destination) },
                                                { push(WhispRoute.Player, destination) },
                                                sources
                                                    .firstOrNull { source ->
                                                        source.kindType == SourceKind.ASMRONE
                                                    }
                                                    ?.let { source ->
                                                        { facet: AsmrFacet ->
                                                            model.submitAsmrQuery(
                                                                AsmrQuery(
                                                                    source.id,
                                                                    facets = listOf(facet),
                                                                ),
                                                            )
                                                            push(
                                                                WhispRoute.AsmrOne(source.id),
                                                                destination,
                                                            )
                                                        }
                                                    },
                                            )
                                        }
                                    }
                                }
                                entry<WhispRoute.Player> { destination ->
                                    RouteFrame(destination) {
                                        PlayerPage(
                                            current,
                                            currentTrack,
                                            model,
                                            { pop(destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Sources> { destination ->
                                    RouteFrame(destination) {
                                        SourcesPage(
                                            model,
                                            { push(WhispRoute.AsmrOne(it), destination) },
                                            { push(WhispRoute.Dlsite(it), destination) },
                                        ) {
                                            push(WhispRoute.Storage, destination)
                                        }
                                    }
                                }
                                entry<WhispRoute.Dlsite> { destination ->
                                    RouteFrame(destination) {
                                        DlsiteLibraryPage(
                                            model,
                                            destination.sourceId,
                                            { pop(destination) },
                                            { push(WhispRoute.Detail(it), destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.AsmrOne> { destination ->
                                    RouteFrame(destination) {
                                        AsmrOnePage(
                                            model,
                                            destination.sourceId,
                                            { pop(destination) },
                                            { work ->
                                                model.prepareAsmrPreview(destination.sourceId, work)
                                                push(
                                                    WhispRoute.AsmrPreview(
                                                        destination.sourceId,
                                                        work.id,
                                                    ),
                                                    destination,
                                                )
                                            },
                                        )
                                    }
                                }
                                entry<WhispRoute.AsmrPreview> { destination ->
                                    RouteFrame(destination) {
                                        AsmrPreviewPage(
                                            model,
                                            destination.sourceId,
                                            destination.workId,
                                            { pop(destination) },
                                            { push(WhispRoute.Player, destination) },
                                            { pop(destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Storage> { destination ->
                                    RouteFrame(destination) {
                                        StoragePage(model) { pop(destination) }
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    model: AppViewModel,
    shareCover: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.testTag("mini-player")
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            model,
            detail.work.coverId,
            null,
            Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)),
            sharedKey = detail.work.id.takeIf { shareCover },
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                shortTitle(detail.work.title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
            )
            Text(
                track.title,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
            )
        }
        IconAction(
            if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            playControlLabel(state),
            Rose,
            model::togglePlay,
        )
    }
}
