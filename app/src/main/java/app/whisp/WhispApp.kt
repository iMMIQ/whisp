// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import android.app.Application
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import app.whisp.data.*
import app.whisp.playback.PlaybackCoordinator
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
 * Flat dependency container replacing the monolithic LibraryRepository and AppViewModel God
 * objects. Dependencies form an acyclic DAG from storage primitives up to playback coordination.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, "whisp.db")
            .addMigrations(MIGRATION_4_5)
            .build()

    val secrets = SecretStore()
    val settings = SettingsStore(context, scope)
    val dlsiteDiagnostics = DlsiteDiagnostics(context)

    val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    val onlineHttp: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val storageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val offlineDir = File(context.filesDir, "offline/assets")
    private val customArtworkDir = File(context.filesDir, "custom-artwork")

    val sources =
        SourceRegistry(
            context,
            scope,
            database.library(),
            secrets,
            http,
            onlineHttp,
            dlsiteDiagnostics,
        )

    val library: LibraryStore
    val media: MediaSourceRouting
    val images: WhispImages
    val offline: OfflineStore
    val playback: PlaybackCoordinator
    val sync: LibrarySync
    val dlsiteLoader: app.whisp.ui.DlsiteDetailsLoader
    val asmrCatalog: app.whisp.ui.AsmrCatalogController
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

    init {
        var libraryRef: LibraryStore? = null
        media =
            MediaSourceRouting(
                context = context,
                scope = scope,
                sources = sources,
                headersFor = { uri ->
                    sources.headersFor(uri, libraryRef?.sourceList?.value.orEmpty())
                },
                storageChanged = { storageChanges.tryEmit(Unit) },
                streamingCacheMiBUpdates = settings.streamingCacheMiBUpdates,
                messages = messages,
            )
        library =
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
        libraryRef = library
        images =
            WhispImages(
                context = context,
                scope = scope,
                dao = database.library(),
                settings = settings,
                sources = sources,
                offlineDir = offlineDir,
                customArtworkDir = customArtworkDir,
                readAsset = { id, limit -> library.readAsset(id, limit) },
                storageChanged = { storageChanges.tryEmit(Unit) },
            )
        offline = OfflineStore(context, library, media, images)
        playback = PlaybackCoordinator(context, mainScope, library, images, settings, messages)
        sync =
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
        dlsiteLoader = app.whisp.ui.DlsiteDetailsLoader(mainScope, library, sources.dlsite)
        asmrCatalog =
            app.whisp.ui.AsmrCatalogController(
                scope = mainScope,
                fetchPage = sync::asmrPage,
                rememberSearch = settings::rememberAsmrSearch,
            )
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
