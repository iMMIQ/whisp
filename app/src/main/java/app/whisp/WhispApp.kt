// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
import app.whisp.system.AudioDeviceMonitor
import coil3.ImageLoader
import coil3.SingletonImageLoader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient

/**
 * Flat dependency container forming an acyclic DAG from storage primitives up to playback
 * coordination, with lazy initialization for non-cold-start protocol clients and services.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "whisp.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    val secrets by lazy { SecretStore() }
    val settings = SettingsStore(context, scope)
    val audioDevices by lazy { AudioDeviceMonitor(context) }
    val dlsiteDiagnostics by lazy { DlsiteDiagnostics(context) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    // Share underlying ConnectionPool & Dispatcher threads with [http] to cut memory and TLS churn.
    val onlineHttp: OkHttpClient by lazy {
        http
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val storageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val offlineDir = File(context.filesDir, "offline/assets")
    private val customArtworkDir = File(context.filesDir, "custom-artwork")

    val sources by lazy {
        SourceRegistry(
            context,
            scope,
            database.library(),
            secrets,
            { http },
            { onlineHttp },
            { dlsiteDiagnostics },
        )
    }

    val media: MediaSourceRouting by lazy {
        MediaSourceRouting(
            context = context,
            scope = scope,
            sources = sources,
            headersFor = { uri -> sources.headersFor(uri, library.sourceList.value) },
            storageChanged = { storageChanges.tryEmit(Unit) },
            streamingCacheMiBUpdates = settings.streamingCacheMiBUpdates,
            wifiOnlyDownloadsUpdates = settings.wifiOnlyDownloadsUpdates,
            messages = messages,
        )
    }

    val library: LibraryStore by lazy {
        LibraryStore(
            context = context,
            scope = scope,
            database = database,
            sources = sources,
            media = media,
            offlineDir = offlineDir,
            customArtworkDir = customArtworkDir,
            storageChanged = { storageChanges.tryEmit(Unit) },
        )
    }

    val images: WhispImages by lazy {
        WhispImages(
            context = context,
            scope = scope,
            dao = database.library(),
            sources = sources,
            offlineDir = offlineDir,
            customArtworkDir = customArtworkDir,
            readAsset = { id, limit -> library.readAsset(id, limit) },
            streamAsset = { id, sink, limit -> library.streamAssetToSink(id, sink, limit) },
            streamingCacheBytes = { media.streamingCache.cacheSpace },
            downloadCacheBytes = { media.downloadCache.cacheSpace },
            storageChanged = { storageChanges.tryEmit(Unit) },
        )
    }

    val offline: OfflineStore by lazy {
        OfflineStore(context, library, media, images) { playback.detachTracks(it) }
    }

    val playback: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            context,
            mainScope,
            library,
            images,
            settings,
            audioDevices,
            messages,
        )
    }

    val sync: LibrarySync by lazy {
        LibrarySync(
            context = context,
            library = library,
            sources = sources,
            media = media,
            offline = offline,
            images = images,
            messages = messages,
            detachTracks = { playback.detachTracks(it) },
        )
    }

    val dlsiteLoader: app.whisp.ui.DlsiteDetailsLoader by lazy {
        app.whisp.ui.DlsiteDetailsLoader(mainScope, library, sources.dlsite)
    }

    val asmrCatalog: app.whisp.ui.AsmrCatalogController by lazy {
        app.whisp.ui.AsmrCatalogController(
            scope = mainScope,
            fetchPage = sync::asmrPage,
            rememberSearch = settings::rememberAsmrSearch,
        )
    }

    private val scopedAsmrCatalogs =
        object : LinkedHashMap<String, app.whisp.ui.AsmrCatalogController>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, app.whisp.ui.AsmrCatalogController>?,
            ): Boolean = size > 6
        }

    fun asmrCatalogFor(scopeKey: String?): app.whisp.ui.AsmrCatalogController {
        if (scopeKey == null) return asmrCatalog
        return synchronized(scopedAsmrCatalogs) {
            scopedAsmrCatalogs.getOrPut(scopeKey) {
                app.whisp.ui.AsmrCatalogController(
                    scope = mainScope,
                    fetchPage = sync::asmrPage,
                    rememberSearch = settings::rememberAsmrSearch,
                )
            }
        }
    }
}

class WhispApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = graph.images.imageLoader
}
