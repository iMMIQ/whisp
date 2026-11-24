// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import app.whisp.AppGraph
import app.whisp.R
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WhispRoot(
    graph: AppGraph,
    playerRequest: Int = 0,
    requestNotifications: () -> Unit = {},
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    WhispTheme(reduceMotion = settings.reduceMotion, amoledBlack = settings.amoledBlack) {
        val bg = MaterialTheme.colorScheme.background
        val sources by graph.library.sourceList.collectAsStateWithLifecycle()
        val state by graph.playback.state.collectAsStateWithLifecycle()
        val openingId by graph.playback.openingTrackId.collectAsStateWithLifecycle()
        val playbackError by graph.playback.error.collectAsStateWithLifecycle()
        val cacheEpoch by graph.images.cacheEpoch.collectAsStateWithLifecycle()
        val hasActiveDownloads by
            remember(graph) {
                    graph.media.downloads
                        .map { map ->
                            map.values.any {
                                it.state == Download.STATE_DOWNLOADING ||
                                    it.state == Download.STATE_QUEUED
                            }
                        }
                        .distinctUntilChanged()
                }
                .collectAsStateWithLifecycle(initialValue = false)

        // Defer notification permission request until playback or downloads actually begin.
        LaunchedEffect(state.playWhenReady, openingId, hasActiveDownloads) {
            if (state.playWhenReady || openingId != null || hasActiveDownloads) {
                requestNotifications()
            }
        }

        // Observe only the single active/resumable work; ignore periodic positionMs ticks when
        // already playing a track so Room observeDetails() is never torn down every 15 seconds.
        val activeSession by
            remember(graph) {
                    val latestIdentity =
                        graph.library
                            .observeLatestProgress()
                            .map { it?.let { p -> p.workId to p.trackId } }
                            .distinctUntilChanged()
                    combine(
                            graph.playback.openingTrackId,
                            graph.playback.state.map { it.trackId }.distinctUntilChanged(),
                            graph.playback.queueTracks,
                            latestIdentity,
                        ) { openId, playingId, queued, latest ->
                            val displayedId = openId ?: playingId
                            val resolvedTrack = displayedId?.let { id ->
                                queued.firstOrNull { it.id == id }
                            }
                            val fallback = if (displayedId == null) latest else null
                            Triple(displayedId, resolvedTrack, fallback)
                        }
                        .distinctUntilChanged()
                        .flatMapLatest { (displayedId, queuedTrack, fallback) ->
                            flow {
                                val resolvedTrack =
                                    queuedTrack
                                        ?: if (displayedId != null) {
                                            withContext(Dispatchers.IO) {
                                                graph.library.track(displayedId)
                                            }
                                        } else null
                                val workId = resolvedTrack?.workId ?: fallback?.first
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
                                                            it.id == fallback?.second
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

        // Independent saved back stacks per top-level tab preserve navigation & scroll state.
        val libraryStack = rememberNavBackStack(WhispRoute.Library)
        val sourcesStack = rememberNavBackStack(WhispRoute.Sources)
        val settingsStack = rememberNavBackStack(WhispRoute.Settings)
        var activeTabIndex by rememberSaveable { mutableIntStateOf(0) }
        var libraryReselect by remember { mutableIntStateOf(0) }
        var sourcesReselect by remember { mutableIntStateOf(0) }
        var settingsReselect by remember { mutableIntStateOf(0) }

        val stack =
            when (activeTabIndex) {
                1 -> sourcesStack
                2 -> settingsStack
                else -> libraryStack
            }

        var handledPlayerRequest by rememberSaveable { mutableIntStateOf(0) }
        val dispatcher =
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!
                .onBackPressedDispatcher
        val lifecycleOwner = LocalLifecycleOwner.current
        // Register before NavDisplay so its gesture handler takes precedence. Enable this
        // fallback synchronously with push: the system can send Back before recomposition.
        val backFallback =
            remember(dispatcher, libraryStack, sourcesStack, settingsStack) {
                object : androidx.activity.OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() {
                        val active =
                            when (activeTabIndex) {
                                1 -> sourcesStack
                                2 -> settingsStack
                                else -> libraryStack
                            }
                        if (active.size > 1) {
                            active.removeAt(active.lastIndex)
                        } else if (activeTabIndex != 0) {
                            activeTabIndex = 0
                        }
                    }
                }
            }
        DisposableEffect(dispatcher, lifecycleOwner, backFallback) {
            dispatcher.addCallback(lifecycleOwner, backFallback)
            onDispose { backFallback.remove() }
        }
        SideEffect { backFallback.isEnabled = stack.size > 1 || activeTabIndex != 0 }
        val route = (stack.lastOrNull() as? WhispRoute) ?: WhispRoute.Library
        val isPlayerRoute = route is WhispRoute.Player
        val showBottomNav = route.isTopLevelTab
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
        val scope = rememberCoroutineScope()

        fun push(next: WhispRoute, from: WhispRoute? = null) {
            if ((from == null || stack.lastOrNull() == from) && stack.lastOrNull() != next) {
                backFallback.isEnabled = true
                stack.add(next)
            }
        }
        fun pop(expected: WhispRoute) {
            if (stack.size > 1 && stack.lastOrNull() == expected) stack.removeAt(stack.lastIndex)
        }
        fun openDetailFromPlayer(workId: String) {
            if (stack.lastOrNull() is WhispRoute.Player && stack.size > 1) {
                stack.removeAt(stack.lastIndex)
            }
            val target = WhispRoute.Detail(workId)
            if (stack.lastOrNull() != target) {
                backFallback.isEnabled = true
                stack.add(target)
            }
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

        val showMini = !isPlayerRoute && current != null && currentTrack != null
        val showAnyBottom = showMini || showBottomNav
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val shared = this
            CompositionLocalProvider(
                LocalArtworkTransition provides shared,
                LocalTouchFeedback provides settings.touchFeedback,
                LocalImageCacheEpoch provides cacheEpoch,
                LocalSnackbarHost provides snackbar,
            ) {
                Scaffold(
                    containerColor = bg,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbar,
                            modifier =
                                if (!showAnyBottom) Modifier.navigationBarsPadding() else Modifier,
                        ) { data ->
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
                            showAnyBottom,
                            enter = fadeIn(tween(if (settings.reduceMotion) 100 else 200)),
                            exit = fadeOut(tween(if (settings.reduceMotion) 100 else 160)),
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
                                    AnimatedVisibility(
                                        visible = showBottomNav,
                                        enter =
                                            fadeIn(tween(if (settings.reduceMotion) 0 else 160)) +
                                                expandVertically(),
                                        exit =
                                            fadeOut(tween(if (settings.reduceMotion) 0 else 120)) +
                                                shrinkVertically(),
                                    ) {
                                        Column(
                                            Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant,
                                            )
                                            WhispBottomNav(
                                                activeTabIndex = activeTabIndex,
                                                onSelect = { index ->
                                                    if (index == activeTabIndex) {
                                                        if (stack.size > 1) {
                                                            while (stack.size > 1) {
                                                                stack.removeAt(stack.lastIndex)
                                                            }
                                                        } else {
                                                            when (index) {
                                                                0 -> libraryReselect++
                                                                1 -> sourcesReselect++
                                                                2 -> settingsReselect++
                                                            }
                                                        }
                                                    } else {
                                                        activeTabIndex = index
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    val navEntryProvider =
                        entryProvider<androidx.navigation3.runtime.NavKey> {
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
                                    val bottomModifier =
                                        when {
                                            destination is WhispRoute.Player -> Modifier
                                            bottom > 0.dp -> Modifier.padding(bottom = bottom)
                                            else -> Modifier.navigationBarsPadding()
                                        }
                                    Box(
                                        Modifier.fillMaxSize().background(bg).then(bottomModifier),
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
                                        activeMiniWorkId = current?.work?.id,
                                        reselectTick = libraryReselect,
                                        open = { push(WhispRoute.Detail(it), destination) },
                                        resumePlay = { workId ->
                                            scope.launch {
                                                val details =
                                                    withContext(Dispatchers.IO) {
                                                        graph.library.workDetails(workId)
                                                    }
                                                if (details != null) {
                                                    graph.playback.playWork(details)
                                                    push(WhispRoute.Player, destination)
                                                } else {
                                                    push(WhispRoute.Detail(workId), destination)
                                                }
                                            }
                                        },
                                        sources = { activeTabIndex = 1 },
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
                                        openDetail = ::openDetailFromPlayer,
                                    )
                                }
                            }
                            entry<WhispRoute.Sources> { destination ->
                                RouteFrame(destination) {
                                    SourcesPage(
                                        graph = graph,
                                        reselectTick = sourcesReselect,
                                        online = { push(WhispRoute.AsmrOne(it), destination) },
                                        dlsite = { push(WhispRoute.Dlsite(it), destination) },
                                        storage = { push(WhispRoute.Storage, destination) },
                                        openWork = { push(WhispRoute.Detail(it), destination) },
                                    )
                                }
                            }
                            entry<WhispRoute.Settings> { destination ->
                                RouteFrame(destination) {
                                    SettingsPage(
                                        graph = graph,
                                        openStorage = { push(WhispRoute.Storage, destination) },
                                        reselectTick = settingsReselect,
                                    )
                                }
                            }
                            entry<WhispRoute.Dlsite> { destination ->
                                RouteFrame(destination) {
                                    DlsiteLibraryPage(
                                        graph = graph,
                                        sourceId = destination.sourceId,
                                        back = { pop(destination) },
                                        openOwned = { workno ->
                                            push(
                                                WhispRoute.DlsiteWork(
                                                    destination.sourceId,
                                                    workno,
                                                ),
                                                destination,
                                            )
                                        },
                                    )
                                }
                            }
                            entry<WhispRoute.DlsiteWork> { destination ->
                                RouteFrame(destination) {
                                    WorkDetailScreen(
                                        graph = graph,
                                        target =
                                            WorkDetailTarget.DlsiteOwned(
                                                destination.sourceId,
                                                destination.workno,
                                            ),
                                        back = { pop(destination) },
                                        player = { push(WhispRoute.Player, destination) },
                                        filter = openFacetFilter,
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
                                    val scopeKey = initialFacet?.let {
                                        "${destination.sourceId}|${it.kind}|${it.name}"
                                    }
                                    AsmrOnePage(
                                        graph = graph,
                                        sourceId = destination.sourceId,
                                        initialFacet = initialFacet,
                                        catalogScopeKey = scopeKey,
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
                                    StoragePage(
                                        graph = graph,
                                        backLabel =
                                            if (activeTabIndex == 2)
                                                stringResource(R.string.action_back_settings)
                                            else stringResource(R.string.action_back_sources),
                                        back = { pop(destination) },
                                    )
                                }
                            }
                        }
                    // Keep one SaveableStateHolder decorator per top-level tab stack so switching
                    // tabs preserves each tab's rememberSaveable scroll/filter/query state.
                    val libraryEntries =
                        rememberDecoratedNavEntries(
                            backStack = libraryStack,
                            entryDecorators =
                                listOf(rememberSaveableStateHolderNavEntryDecorator()),
                            entryProvider = navEntryProvider,
                        )
                    val sourcesEntries =
                        rememberDecoratedNavEntries(
                            backStack = sourcesStack,
                            entryDecorators =
                                listOf(rememberSaveableStateHolderNavEntryDecorator()),
                            entryProvider = navEntryProvider,
                        )
                    val settingsEntries =
                        rememberDecoratedNavEntries(
                            backStack = settingsStack,
                            entryDecorators =
                                listOf(rememberSaveableStateHolderNavEntryDecorator()),
                            entryProvider = navEntryProvider,
                        )
                    val activeEntries =
                        when (activeTabIndex) {
                            1 -> sourcesEntries
                            2 -> settingsEntries
                            else -> libraryEntries
                        }
                    NavDisplay(
                        entries = activeEntries,
                        onBack = {
                            if (stack.size > 1) stack.removeAt(stack.lastIndex)
                            else if (activeTabIndex != 0) activeTabIndex = 0
                        },
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else if (targetState.key is WhispRoute.Player) {
                                (fadeIn(tween(240)) +
                                    slideInVertically(
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ) {
                                        it / 12
                                    }) togetherWith fadeOut(tween(160))
                            } else {
                                fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                            }
                        },
                        popTransitionSpec = {
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else if (initialState.key is WhispRoute.Player) {
                                fadeIn(tween(180, delayMillis = 40)) togetherWith
                                    (fadeOut(tween(140)) +
                                        slideOutVertically(
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        ) {
                                            it / 10
                                        })
                            } else {
                                fadeIn(tween(180, delayMillis = 60)) togetherWith
                                    fadeOut(tween(100))
                            }
                        },
                        predictivePopTransitionSpec = { edge ->
                            if (settings.reduceMotion) {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            } else if (initialState.key is WhispRoute.Player) {
                                // Match the bottom-sheet vertical spatial metaphor for Player.
                                (fadeIn(tween(240), initialAlpha = .72f) +
                                    scaleIn(tween(240), initialScale = .98f)) togetherWith
                                    (slideOutVertically(tween(240)) { it / 5 } +
                                        scaleOut(
                                            tween(240),
                                            targetScale = .94f,
                                            transformOrigin = TransformOrigin(0.5f, 1f),
                                        ) +
                                        fadeOut(tween(180)))
                            } else {
                                val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
                                (fadeIn(tween(260), initialAlpha = .72f) +
                                    scaleIn(tween(260), initialScale = .99f)) togetherWith
                                    (slideOutHorizontally(tween(260)) { direction * it / 6 } +
                                        scaleOut(
                                            tween(260),
                                            targetScale = .94f,
                                            transformOrigin =
                                                TransformOrigin(if (direction > 0) 0f else 1f, .5f),
                                        ) +
                                        fadeOut(tween(180)))
                            }
                        },
                    )
                }
            }
        }
    }
}

private data class NavTabItem(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String,
)

@Composable
private fun WhispBottomNav(activeTabIndex: Int, onSelect: (Int) -> Unit) {
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val tabs =
        listOf(
            NavTabItem(
                Icons.Filled.LibraryMusic,
                Icons.Outlined.LibraryMusic,
                stringResource(R.string.nav_library),
            ),
            NavTabItem(
                Icons.Filled.Folder,
                Icons.Outlined.FolderOpen,
                stringResource(R.string.nav_sources),
            ),
            NavTabItem(
                Icons.Filled.Settings,
                Icons.Outlined.Settings,
                stringResource(R.string.nav_settings),
            ),
        )
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = activeTabIndex == index
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
                    .clickable {
                        haptics.tick()
                        onSelect(index)
                    }
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

private enum class MiniDragAxis {
    None,
    Vertical,
    Horizontal,
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
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val haptics = rememberWhispHaptics()
    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val offsetX = remember { Animatable(0f) }
    val expandThresholdPx = with(density) { 44.dp.toPx() }
    val swipeTrackThresholdPx = with(density) { 54.dp.toPx() }
    val currentState by rememberUpdatedState(state)
    val currentClick by rememberUpdatedState(onClick)

    val springSpec: AnimationSpec<Float> =
        if (reduced) snap()
        else
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )

    val liftedHighlight by
        remember(expandThresholdPx) {
            derivedStateOf {
                abs(offsetY.value) / expandThresholdPx.coerceAtLeast(1f) > 0.6f
            }
        }
    Column(
        Modifier.testTag("mini-player")
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .graphicsLayer {
                val liftFraction =
                    (abs(offsetY.value) / expandThresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                translationY = offsetY.value * 0.55f
                val liftScale = 1f + liftFraction * 0.018f
                scaleX = liftScale
                scaleY = liftScale
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                0.5.dp,
                if (liftedHighlight) Rose.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .pointerInput(expandThresholdPx, swipeTrackThresholdPx, reduced, haptics) {
                val slopPx = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val velocityTracker = VelocityTracker()
                    var axis = MiniDragAxis.None
                    var totalDx = 0f
                    var totalDy = 0f
                    var crossed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change =
                            event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        if (axis == MiniDragAxis.None) {
                            if (abs(totalDy) > slopPx && abs(totalDy) > abs(totalDx)) {
                                if (totalDy < 0f) axis = MiniDragAxis.Vertical
                            } else if (abs(totalDx) > slopPx && abs(totalDx) > abs(totalDy)) {
                                axis = MiniDragAxis.Horizontal
                            }
                        }
                        when (axis) {
                            MiniDragAxis.Vertical -> {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val nextY = totalDy.coerceIn(-expandThresholdPx * 1.5f, 0f)
                                scope.launch { offsetY.snapTo(nextY) }
                                val nowCrossed = nextY <= -expandThresholdPx
                                if (nowCrossed && !crossed) {
                                    crossed = true
                                    haptics.tick()
                                } else if (!nowCrossed) crossed = false
                            }
                            MiniDragAxis.Horizontal -> {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val nextX =
                                    totalDx.coerceIn(
                                        -swipeTrackThresholdPx * 1.2f,
                                        swipeTrackThresholdPx * 1.2f,
                                    )
                                scope.launch { offsetX.snapTo(nextX) }
                                val nowCrossed = abs(nextX) >= swipeTrackThresholdPx
                                if (nowCrossed && !crossed) {
                                    crossed = true
                                    haptics.tick()
                                } else if (!nowCrossed) crossed = false
                            }
                            MiniDragAxis.None -> Unit
                        }
                    }
                    when (axis) {
                        MiniDragAxis.Vertical -> {
                            val vy = velocityTracker.calculateVelocity().y
                            val expand = offsetY.value <= -expandThresholdPx || vy < -900f
                            scope.launch { offsetY.animateTo(0f, springSpec) }
                            if (expand) currentClick()
                        }
                        MiniDragAxis.Horizontal -> {
                            val vx = velocityTracker.calculateVelocity().x
                            val cur = offsetX.value
                            scope.launch { offsetX.animateTo(0f, springSpec) }
                            if (
                                (cur <= -swipeTrackThresholdPx || vx < -1000f) &&
                                    currentState.hasNext
                            ) {
                                playback.next()
                            } else if (
                                (cur >= swipeTrackThresholdPx || vx > 1000f) &&
                                    (currentState.hasPrevious || currentState.trackId != null)
                            ) {
                                playback.previous()
                            }
                        }
                        MiniDragAxis.None -> Unit
                    }
                }
            }
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).graphicsLayer { translationX = offsetX.value * 0.45f },
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
            }
            // Dedicated separated tap zone to reduce accidental full-player expansion.
            Box(
                Modifier.width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Box(
                Modifier.padding(start = 4.dp).size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconAction(
                    if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    playControlLabel(state),
                    Rose,
                    haptic = HapticKind.Confirm,
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var liveDisplayPos by
        remember(track.id, state.trackId, state.positionMs) {
            mutableLongStateOf(
                if (state.trackId == track.id) playback.currentPosition() else state.positionMs,
            )
        }
    // Sample directly from ExoPlayer locally without mutating PlaybackCoordinator.positionMs.
    LaunchedEffect(track.id, state.trackId, state.playing, lifecycleOwner) {
        if (state.trackId == track.id && state.playing) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    liveDisplayPos = playback.currentPosition()
                    delay(1000L)
                }
            }
        } else if (state.trackId == track.id) {
            liveDisplayPos = playback.currentPosition()
        }
    }
    val duration =
        state.durationMs.takeIf { state.trackId == track.id && it > 0 } ?: track.durationMs
    if (duration > 0) {
        val trackBg = Raised.copy(alpha = 0.7f)
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            val pos = if (state.trackId == track.id) liveDisplayPos else state.positionMs
            val fraction = (pos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            drawRect(color = trackBg)
            if (fraction > 0f) {
                drawRect(
                    color = Rose,
                    size = size.copy(width = size.width * fraction),
                )
            }
        }
    }
}
