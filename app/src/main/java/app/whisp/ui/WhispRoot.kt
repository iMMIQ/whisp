// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WhispRoot(graph: AppGraph, playerRequest: Int = 0) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    WhispTheme(reduceMotion = settings.reduceMotion, amoledBlack = settings.amoledBlack) {
        val bg = MaterialTheme.colorScheme.background
        val sources by graph.library.sourceList.collectAsStateWithLifecycle()
        val state by graph.playback.state.collectAsStateWithLifecycle()
        val playbackError by graph.playback.error.collectAsStateWithLifecycle()
        val cacheEpoch by graph.images.cacheEpoch.collectAsStateWithLifecycle()

        // Observe only the single active/resumable work instead of the entire library's tracks.
        val activeSession by
            remember(graph) {
                    combine(
                            graph.playback.openingTrackId,
                            graph.playback.state.map { it.trackId }.distinctUntilChanged(),
                            graph.playback.queueTracks,
                            graph.library.progress.map { it.firstOrNull() }.distinctUntilChanged(),
                        ) { openId, playingId, queued, latest ->
                            val displayedId = openId ?: playingId
                            Triple(displayedId, queued, latest)
                        }
                        .flatMapLatest { (displayedId, queued, latest) ->
                            flow {
                                val resolvedTrack =
                                    if (displayedId != null) {
                                        queued.firstOrNull { it.id == displayedId }
                                            ?: withContext(Dispatchers.IO) {
                                                graph.library.track(displayedId)
                                            }
                                    } else null
                                val workId = resolvedTrack?.workId ?: latest?.workId
                                if (workId == null) {
                                    emit(null)
                                } else {
                                    emitAll(
                                        graph.library.observeDetails(workId).map { detail ->
                                            if (detail == null) null
                                            else {
                                                val track =
                                                    resolvedTrack
                                                        ?: detail.tracks.firstOrNull {
                                                            it.id == latest?.trackId
                                                        }
                                                        ?: detail.tracks.firstOrNull()
                                                if (track != null) detail to track else null
                                            }
                                        },
                                    )
                                }
                            }
                        }
                }
                .collectAsStateWithLifecycle(initialValue = null)

        val current = activeSession?.first
        val currentTrack = activeSession?.second

        val stack = rememberNavBackStack(WhispRoute.Library)
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
        val route = (stack.lastOrNull() as? WhispRoute) ?: WhispRoute.Library
        val isPlayerRoute = route is WhispRoute.Player
        LaunchedEffect(graph, lifecycleOwner, isPlayerRoute) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                graph.playback.refreshPlayer()
                if (isPlayerRoute) graph.playback.observeTimeline()
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

        fun push(next: WhispRoute, from: WhispRoute? = null) {
            if ((from == null || stack.lastOrNull() == from) && stack.lastOrNull() != next) {
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
        LaunchedEffect(graph) { graph.messages.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(playbackError) {
            playbackError?.let {
                snackbar.showSnackbar(it)
                graph.playback.error.value = null
            }
        }

        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val shared = this
            CompositionLocalProvider(
                LocalArtworkTransition provides shared,
                LocalTouchFeedback provides settings.touchFeedback,
                LocalImageCacheEpoch provides cacheEpoch,
            ) {
                Scaffold(
                    containerColor = bg,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        SnackbarHost(snackbar) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                actionColor = MaterialTheme.colorScheme.inversePrimary,
                                shape = RoundedCornerShape(14.dp),
                            )
                        }
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            !isPlayerRoute,
                            enter = fadeIn(tween(if (settings.reduceMotion) 100 else 240)),
                            exit = fadeOut(tween(if (settings.reduceMotion) 100 else 200)),
                        ) {
                            CompositionLocalProvider(LocalArtworkVisibility provides this) {
                                Column(
                                    Modifier.fillMaxWidth().background(bg).navigationBarsPadding(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (current != null && currentTrack != null) {
                                        MiniPlayer(
                                            detail = current,
                                            track = currentTrack,
                                            state = state,
                                            playback = graph.playback,
                                            onToggle = graph.playback::togglePlay,
                                            shareCover = miniTransition,
                                            onClick = { push(WhispRoute.Player) },
                                        )
                                    }
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    WhispBottomNav(
                                        currentRoute = route,
                                        onSelect = { target ->
                                            stack.clear()
                                            stack.add(target)
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavDisplay(
                        backStack = stack,
                        onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else if (targetState.key is WhispRoute.Player) {
                                (fadeIn(tween(260)) +
                                    slideInVertically(tween(260, easing = FastOutSlowInEasing)) {
                                        it / 14
                                    }) togetherWith fadeOut(tween(180))
                            } else {
                                fadeIn(tween(260)) togetherWith fadeOut(tween(170))
                            }
                        },
                        popTransitionSpec = {
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else if (initialState.key is WhispRoute.Player) {
                                fadeIn(tween(190, delayMillis = 60)) togetherWith
                                    (fadeOut(tween(140)) +
                                        slideOutVertically(
                                            tween(200, easing = FastOutSlowInEasing),
                                        ) {
                                            it / 12
                                        })
                            } else {
                                fadeIn(tween(190, delayMillis = 90)) togetherWith fadeOut(tween(90))
                            }
                        },
                        predictivePopTransitionSpec = { edge ->
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else {
                                val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
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
                                    if (
                                        destination == route &&
                                            destination !is WhispRoute.Player &&
                                            !shared.isTransitionActive
                                    ) {
                                        SideEffect { bottom = padding.calculateBottomPadding() }
                                    }
                                    CompositionLocalProvider(
                                        LocalArtworkVisibility provides
                                            androidx.navigation3.ui.LocalNavAnimatedContentScope
                                                .current,
                                        LocalShareArtwork provides
                                            (!miniTransition || destination is WhispRoute.Player),
                                    ) {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(bg)
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
                                val asmrSource = sources.firstOrNull {
                                    it.kindType == SourceKind.ASMRONE
                                }
                                val openFacetFilter: ((AsmrFacet) -> Unit)? =
                                    asmrSource?.let { source ->
                                        { facet: AsmrFacet ->
                                            push(
                                                WhispRoute.AsmrOne(
                                                    source.id,
                                                    facet.kind,
                                                    facet.name,
                                                ),
                                            )
                                        }
                                    }
                                entry<WhispRoute.Library> { destination ->
                                    RouteFrame(destination) {
                                        LibraryPage(
                                            graph = graph,
                                            favoritesOnly = false,
                                            open = { push(WhispRoute.Detail(it), destination) },
                                            sources = { push(WhispRoute.Sources, destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Favorites> { destination ->
                                    RouteFrame(destination) {
                                        LibraryPage(
                                            graph = graph,
                                            favoritesOnly = true,
                                            open = { push(WhispRoute.Detail(it), destination) },
                                            sources = { push(WhispRoute.Sources, destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Detail> { destination ->
                                    RouteFrame(destination) {
                                        WorkDetailScreen(
                                            graph = graph,
                                            target = WorkDetailTarget.Library(destination.workId),
                                            back = { pop(destination) },
                                            player = { push(WhispRoute.Player, destination) },
                                            filter = openFacetFilter,
                                        )
                                    }
                                }
                                entry<WhispRoute.Player> { destination ->
                                    RouteFrame(destination) {
                                        PlayerPage(
                                            detail = current,
                                            track = currentTrack,
                                            graph = graph,
                                            back = { pop(destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Sources> { destination ->
                                    RouteFrame(destination) {
                                        SourcesPage(
                                            graph = graph,
                                            online = { push(WhispRoute.AsmrOne(it), destination) },
                                            dlsite = { push(WhispRoute.Dlsite(it), destination) },
                                            storage = { push(WhispRoute.Storage, destination) },
                                            openWork = { push(WhispRoute.Detail(it), destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.Dlsite> { destination ->
                                    RouteFrame(destination) {
                                        DlsiteLibraryPage(
                                            graph = graph,
                                            sourceId = destination.sourceId,
                                            back = { pop(destination) },
                                            open = { push(WhispRoute.Detail(it), destination) },
                                        )
                                    }
                                }
                                entry<WhispRoute.AsmrOne> { destination ->
                                    RouteFrame(destination) {
                                        val initialFacet =
                                            if (
                                                destination.facetKind != null &&
                                                    destination.facetName != null
                                            )
                                                AsmrFacet(
                                                    destination.facetKind,
                                                    destination.facetName,
                                                )
                                            else null
                                        AsmrOnePage(
                                            graph = graph,
                                            sourceId = destination.sourceId,
                                            initialFacet = initialFacet,
                                            back = { pop(destination) },
                                            open = { work ->
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
                                        WorkDetailScreen(
                                            graph = graph,
                                            target =
                                                WorkDetailTarget.AsmrPreview(
                                                    destination.sourceId,
                                                    destination.workId,
                                                ),
                                            back = { pop(destination) },
                                            player = { push(WhispRoute.Player, destination) },
                                            filter = openFacetFilter,
                                        )
                                    }
                                }
                                entry<WhispRoute.Storage> { destination ->
                                    RouteFrame(destination) {
                                        StoragePage(graph = graph, back = { pop(destination) })
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

private data class NavTabItem(
    val route: WhispRoute,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
)

@Composable
private fun WhispBottomNav(currentRoute: WhispRoute, onSelect: (WhispRoute) -> Unit) {
    val reduced = LocalReducedMotion.current
    val tabs =
        listOf(
            NavTabItem(
                WhispRoute.Library,
                Icons.Filled.LibraryMusic,
                Icons.Outlined.LibraryMusic,
                stringResource(R.string.nav_library),
            ),
            NavTabItem(
                WhispRoute.Favorites,
                Icons.Filled.Favorite,
                Icons.Outlined.FavoriteBorder,
                stringResource(R.string.nav_favorites),
            ),
            NavTabItem(
                WhispRoute.Sources,
                Icons.Filled.Folder,
                Icons.Outlined.FolderOpen,
                stringResource(R.string.nav_sources),
            ),
        )
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val tint by
                animateColorAsState(
                    if (selected) Rose else Muted,
                    tween(if (reduced) 0 else 160),
                    label = "nav-tint",
                )
            val pill by
                animateColorAsState(
                    if (selected) Rose.copy(alpha = 0.16f) else Color.Transparent,
                    tween(if (reduced) 0 else 160),
                    label = "nav-pill",
                )
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(pill)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        tab.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    detail: WorkDetails,
    track: TrackEntity,
    state: PlaybackState,
    playback: PlaybackCoordinator,
    onToggle: () -> Unit,
    shareCover: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.testTag("mini-player")
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(onClick) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onDragEnd = {
                        if (dragAccumulator < -48.dp.toPx()) onClick()
                        dragAccumulator = 0f
                    },
                    onDragCancel = { dragAccumulator = 0f },
                    onVerticalDrag = { _, dragAmount -> dragAccumulator += dragAmount },
                )
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                image = detail.work.coverId?.let { WhispImage.Asset(it) },
                description = null,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)),
                sharedKey = detail.work.id.takeIf { shareCover },
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    shortTitle(detail.work.title).ifBlank { detail.work.title },
                    style = MaterialTheme.typography.titleSmall,
                    color = Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Box(contentAlignment = Alignment.Center) {
                IconAction(
                    if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    playControlLabel(state),
                    Rose,
                    action = onToggle,
                )
                if (state.buffering) {
                    CircularProgressIndicator(
                        Modifier.size(34.dp),
                        color = Rose,
                        trackColor = Rose.copy(alpha = 0.2f),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        MiniPlayerProgressBar(track = track, state = state, playback = playback)
    }
}

@Composable
private fun MiniPlayerProgressBar(
    track: TrackEntity,
    state: PlaybackState,
    playback: PlaybackCoordinator,
) {
    val livePos by playback.positionMs.collectAsStateWithLifecycle()
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    val position = if (state.trackId == track.id) livePos else state.positionMs
    if (duration > 0) {
        val fraction = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(2.dp).background(Raised.copy(alpha = 0.7f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Rose))
        }
    }
}
