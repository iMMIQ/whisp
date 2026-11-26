// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import android.graphics.Rect
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import app.whisp.system.ExternalLaunchAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WhispRoot(
    graph: AppGraph,
    playerRequest: Int = 0,
    launchCounter: Int = 0,
    launchAction: ExternalLaunchAction? = null,
    inPipMode: Boolean = false,
    requestNotifications: () -> Unit = {},
    onVideoBoundsChanged: (Rect?) -> Unit = {},
) {
    val context = LocalContext.current
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val effectiveReduceMotion =
        settings.reduceMotion || remember(context) { systemPrefersReducedMotion(context) }
    WhispTheme(reduceMotion = effectiveReduceMotion, amoledBlack = settings.amoledBlack) {
        val bg = MaterialTheme.colorScheme.background
        val sources by graph.library.sourceList.collectAsStateWithLifecycle()
        val state by graph.playback.state.collectAsStateWithLifecycle()
        val openingId by graph.playback.openingTrackId.collectAsStateWithLifecycle()
        val playbackError by graph.playback.error.collectAsStateWithLifecycle()
        val speakerGuardPrompt by graph.playback.speakerGuardPrompt.collectAsStateWithLifecycle()
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

        val audioOnly by graph.playback.audioOnly.collectAsStateWithLifecycle()
        val current = activeSession?.first
        val currentTrack = activeSession?.second
        val currentWorkProgress by
            remember(current?.work?.id) {
                    current?.work?.id?.let { graph.library.observeProgress(it) } ?: flowOf(null)
                }
                .collectAsStateWithLifecycle(initialValue = null)
        val miniFallbackPosition =
            remember(currentWorkProgress, currentTrack?.id) {
                currentWorkProgress?.takeIf { it.trackId == currentTrack?.id }?.positionMs ?: 0L
            }

        if (inPipMode && currentTrack != null && state.hasVideo && !audioOnly) {
            VideoOutput(
                playback = graph.playback,
                trackId = currentTrack.id,
                active = state.trackId == currentTrack.id,
                playing = state.playing,
                modifier = Modifier.fillMaxSize(),
            )
            return@WhispTheme
        }

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
        var handledLaunchCounter by rememberSaveable { mutableIntStateOf(0) }
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
        LaunchedEffect(launchCounter, launchAction, current?.work?.id) {
            val action = launchAction ?: return@LaunchedEffect
            if (launchCounter <= handledLaunchCounter) return@LaunchedEffect
            when (action) {
                ExternalLaunchAction.OpenPlayer -> {
                    if (current != null) {
                        handledLaunchCounter = launchCounter
                        push(WhispRoute.Player)
                    }
                }
                ExternalLaunchAction.OpenStorage -> {
                    handledLaunchCounter = launchCounter
                    activeTabIndex = 2
                    if (settingsStack.lastOrNull() != WhispRoute.Storage) {
                        backFallback.isEnabled = true
                        settingsStack.add(WhispRoute.Storage)
                    }
                }
                is ExternalLaunchAction.StartSleepTimer -> {
                    handledLaunchCounter = launchCounter
                    graph.playback.startSleepTimerFromSystem(action.minutes)
                    graph.messages.tryEmit("已开启 ${action.minutes} 分钟助眠定时")
                }
                is ExternalLaunchAction.OpenWorkCode -> {
                    handledLaunchCounter = launchCounter
                    val existing =
                        withContext(Dispatchers.IO) { graph.library.dao.workByRj(action.code) }
                    if (existing != null) {
                        activeTabIndex = 0
                        val target = WhispRoute.Detail(existing.id)
                        if (libraryStack.lastOrNull() != target) {
                            backFallback.isEnabled = true
                            libraryStack.add(target)
                        }
                    } else if (action.asmrId != null) {
                        val sourceId =
                            sources.firstOrNull { it.kindType == SourceKind.ASMRONE }?.id
                                ?: runCatching { graph.library.addAsmrOne() }.getOrNull()
                        if (sourceId != null) {
                            activeTabIndex = 1
                            val target = WhispRoute.AsmrPreview(sourceId, action.asmrId)
                            if (sourcesStack.lastOrNull() != target) {
                                backFallback.isEnabled = true
                                sourcesStack.add(target)
                            }
                        } else {
                            graph.messages.tryEmit("未找到 ${action.code}")
                        }
                    } else {
                        graph.messages.tryEmit("作品库中未找到 ${action.code}")
                    }
                }
                is ExternalLaunchAction.SearchQuery -> {
                    handledLaunchCounter = launchCounter
                    val matches =
                        withContext(Dispatchers.IO) { graph.library.searchWorkIds(action.query) }
                    if (matches.size == 1) {
                        activeTabIndex = 0
                        val target = WhispRoute.Detail(matches.first())
                        if (libraryStack.lastOrNull() != target) {
                            backFallback.isEnabled = true
                            libraryStack.add(target)
                        }
                    } else {
                        val sourceId =
                            sources.firstOrNull { it.kindType == SourceKind.ASMRONE }?.id
                                ?: runCatching { graph.library.addAsmrOne() }.getOrNull()
                        if (sourceId != null) {
                            activeTabIndex = 1
                            graph.asmrCatalog.submit(
                                AsmrQuery(sourceId = sourceId, text = action.query),
                            )
                            val target = WhispRoute.AsmrOne(sourceId)
                            if (sourcesStack.lastOrNull() != target) {
                                backFallback.isEnabled = true
                                sourcesStack.add(target)
                            }
                        } else {
                            graph.messages.tryEmit("已收到关键词：${action.query}")
                        }
                    }
                }
                is ExternalLaunchAction.PlayExternalMedia -> {
                    handledLaunchCounter = launchCounter
                    graph.playback.playExternalUri(action.uri, action.mimeType)
                }
            }
        }
        LaunchedEffect(graph) { graph.messages.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(playbackError) {
            playbackError?.let {
                snackbar.showSnackbar(it)
                graph.playback.error.value = null
            }
        }
        speakerGuardPrompt?.let { promptMessage ->
            AlertDialog(
                onDismissRequest = graph.playback::dismissSpeakerGuard,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = { Text("外放提醒", color = Cream) },
                text = { Text(promptMessage, color = Muted) },
                confirmButton = {
                    Button(
                        onClick = graph.playback::confirmSpeakerPlayback,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("继续外放")
                    }
                },
                dismissButton = {
                    TextButton(onClick = graph.playback::dismissSpeakerGuard) { Text("取消") }
                },
            )
        }

        val showMini = !isPlayerRoute && current != null && currentTrack != null
        val containerWidthDp =
            with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
        val useNavRail = containerWidthDp >= 720.dp && showBottomNav
        val showBottomNavBar = showBottomNav && !useNavRail
        val showAnyBottom = showMini || showBottomNavBar
        val onSelectNavTab: (Int) -> Unit = { index ->
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
        }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            val shared = this
            CompositionLocalProvider(
                LocalArtworkTransition provides shared,
                LocalTouchFeedback provides settings.touchFeedback,
                LocalImageCacheEpoch provides cacheEpoch,
                LocalSnackbarHost provides snackbar,
                LocalVideoBoundsReporter provides onVideoBoundsChanged,
            ) {
                Row(Modifier.fillMaxSize().background(bg)) {
                    if (useNavRail) {
                        WhispNavRail(
                            activeTabIndex = activeTabIndex,
                            onSelect = onSelectNavTab,
                        )
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        containerColor = bg,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = {
                            SnackbarHost(
                                hostState = snackbar,
                                modifier =
                                    if (!showAnyBottom) Modifier.navigationBarsPadding()
                                    else Modifier,
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
                                        Modifier.fillMaxWidth()
                                            .background(bg)
                                            .navigationBarsPadding(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        if (current != null && currentTrack != null) {
                                            MiniPlayer(
                                                detail = current,
                                                track = currentTrack,
                                                state = state,
                                                fallbackPosition = miniFallbackPosition,
                                                playback = graph.playback,
                                                onToggle = graph.playback::togglePlay,
                                                shareCover = miniTransition,
                                                onClick = { push(WhispRoute.Player) },
                                            )
                                        }
                                        AnimatedVisibility(
                                            visible = showBottomNavBar,
                                            enter =
                                                fadeIn(
                                                    tween(if (settings.reduceMotion) 0 else 160),
                                                ) + expandVertically(),
                                            exit =
                                                fadeOut(
                                                    tween(if (settings.reduceMotion) 0 else 120),
                                                ) + shrinkVertically(),
                                        ) {
                                            Column(
                                                Modifier.fillMaxWidth(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                HorizontalDivider(
                                                    thickness = 0.5.dp,
                                                    color =
                                                        MaterialTheme.colorScheme.outlineVariant,
                                                )
                                                WhispBottomNav(
                                                    activeTabIndex = activeTabIndex,
                                                    onSelect = onSelectNavTab,
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
                                            Modifier.fillMaxSize()
                                                .background(bg)
                                                .then(bottomModifier),
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
                                            openPlayer = { push(WhispRoute.Player, destination) },
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
                                            openWork = { push(WhispRoute.Detail(it), destination) },
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
                        // Keep one SaveableStateHolder decorator per top-level tab stack so
                        // switching
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
                                    val direction =
                                        if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
                                    (fadeIn(tween(260), initialAlpha = .72f) +
                                        scaleIn(tween(260), initialScale = .99f)) togetherWith
                                        (slideOutHorizontally(tween(260)) { direction * it / 6 } +
                                            scaleOut(
                                                tween(260),
                                                targetScale = .94f,
                                                transformOrigin =
                                                    TransformOrigin(
                                                        if (direction > 0) 0f else 1f,
                                                        .5f,
                                                    ),
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
}
