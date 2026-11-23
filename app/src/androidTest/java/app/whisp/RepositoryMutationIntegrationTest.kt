// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import coil3.request.ImageRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RepositoryMutationIntegrationTest {
    private fun graph(): AppGraph =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as WhispApp)
            .graph
            .also { assertTrue(it.context.packageName.endsWith(".smoketest")) }

    @Test
    fun sourcePasswordWhitespaceIsPreservedByTheKeystore() {
        val secrets = graph().secrets
        val password = " \t "
        val encrypted = secrets.encrypt(password)
        assertTrue(encrypted.isNotEmpty())
        assertNotEquals(password, encrypted)
        assertEquals(password, secrets.decrypt(encrypted))
        assertEquals("", secrets.encrypt(""))
        assertEquals("", secrets.decrypt(""))
    }

    @Test
    fun fourIntroductionImagesCanStartBeforeAnyResponseCompletes() = runBlocking {
        val graph = graph()
        val arrived = CountDownLatch(4)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("synthetic image bytes").build()
                }
            }
        server.start()
        try {
            coroutineScope {
                val requests =
                    (1..4).map { index ->
                        async(Dispatchers.IO) {
                            graph.images.imageLoader.execute(
                                ImageRequest.Builder(graph.context)
                                    .data(
                                        WhispImage.Official(
                                            "introduction-concurrency-$index",
                                            server.url("/$index").toString(),
                                        ),
                                    )
                                    .build(),
                            )
                        }
                    }
                try {
                    assertTrue(
                        "All four image lanes must reach the server",
                        arrived.await(5, TimeUnit.SECONDS),
                    )
                } finally {
                    release.countDown()
                }
                requests.awaitAll()
            }
        } finally {
            release.countDown()
            server.close()
            graph.images.clearCaches(graph.media::clearStreamingCache)
        }
    }

    @Test fun finishingScanPreservesNewSourceConfiguration() = sourceScanRace(false, 207)

    @Test fun failedScanPreservesNewSourceConfiguration() = sourceScanRace(false, 500)

    @Test fun finishingScanDoesNotRestoreRemovedSource() = sourceScanRace(true, 207)

    @Test fun failedScanDoesNotRestoreRemovedSource() = sourceScanRace(true, 500)

    private fun sourceScanRace(remove: Boolean, status: Int) = runBlocking {
        val graph = graph()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder()
                        .code(status)
                        .body(
                            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/01.m4a</d:href><d:propstat><d:prop><d:displayname>01.m4a</d:displayname><d:getcontentlength>128</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                        )
                        .build()
                }
            }
        server.start()
        val id = "scan-race-$remove-$status"
        graph.library.dao.putSource(
            SourceEntity(id, "Before", "webdav", server.url("/dav/").toString()),
        )
        try {
            coroutineScope {
                val scan =
                    async(Dispatchers.IO) {
                        try {
                            graph.sync.scan(id)
                        } catch (_: IOException) {} catch (_: IllegalArgumentException) {}
                    }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (remove) graph.sync.removeSource(id)
                    else
                        graph.library.dao.putSource(
                            graph.library.dao
                                .source(id)!!
                                .copy(
                                    name = "After",
                                    secret = graph.secrets.encrypt("synthetic-new-password"),
                                ),
                        )
                } finally {
                    release.countDown()
                }
                withTimeout(10_000) { scan.await() }
            }
            if (remove) {
                assertNull(
                    "An in-flight scan must not restore a deleted source",
                    graph.library.dao.source(id),
                )
                assertTrue(graph.library.dao.sourceWorkIds(id).isEmpty())
            } else {
                val latest = graph.library.dao.source(id)!!
                assertEquals("After", latest.name)
                assertEquals("synthetic-new-password", graph.secrets.decrypt(latest.secret))
            }
        } finally {
            release.countDown()
            graph.sync.removeSource(id)
            server.close()
        }
    }

    @Test
    fun coverChangePreservesMetadataWrittenSinceEditorOpened() = runBlocking {
        val graph = graph()
        val original = WorkEntity("cover-editor-race", null, "Before", "Before")
        graph.library.dao.putWorks(listOf(original))
        graph.library.dao.putWorks(
            listOf(original.copy(title = "After", favorite = true, origin = "dlsite")),
        )
        val picture =
            File(graph.context.cacheDir, "synthetic-cover.png").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
        try {
            graph.offline.setCover(original, android.net.Uri.fromFile(picture))
            val latest = graph.library.dao.work(original.id)!!
            assertEquals("After", latest.title)
            assertTrue(latest.favorite)
            assertEquals("dlsite", latest.origin)
            assertNotNull(latest.coverId)
        } finally {
            graph.sync.removeOnlineWork(original.id)
            picture.delete()
        }
    }

    @Test
    fun removingOfflineCopyWaitsForInFlightAttachmentWriter() = runBlocking {
        val graph = graph()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    return MockResponse.Builder().body("synthetic attachment").build()
                }
            }
        server.start()
        val sourceId = "attachment-removal-source"
        val workId = "attachment-removal-work"
        val assetId = "attachment-removal-asset"
        graph.library.dao.putSource(
            SourceEntity(sourceId, "Attachments", "webdav", server.url("/").toString()),
        )
        graph.library.dao.putWorks(
            listOf(
                WorkEntity(
                    workId,
                    null,
                    "Attachments",
                    "Attachments",
                    origin = "dlsite",
                    sourceId = sourceId,
                ),
            ),
        )
        graph.library.dao.putAttachments(
            listOf(
                AttachmentEntity(
                    assetId,
                    workId,
                    "Script",
                    "script.txt",
                    "script.txt",
                    "script",
                    uri = server.url("/script.txt").toString(),
                    size = 20,
                ),
            ),
        )
        try {
            coroutineScope {
                val writer = async(Dispatchers.IO) { graph.offline.downloadAttachments(workId) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val removal = async(Dispatchers.IO) { graph.offline.removeOffline(workId) }
                delay(100)
                release.countDown()
                withTimeout(10_000) { awaitAll(writer, removal) }
            }
            assertFalse(graph.library.dao.work(workId)!!.offlineAttachments)
            assertFalse(File(graph.context.filesDir, "offline/assets/$assetId").exists())
        } finally {
            release.countDown()
            graph.sync.removeOnlineWork(workId)
            graph.sync.removeSource(sourceId)
            server.close()
        }
    }

    @Test
    fun removingOneWorkPreservesAnOfflineCoverSharedByAnotherAccount() = runBlocking {
        val graph = graph()
        val coverId = "shared-offline-cover"
        val first =
            WorkEntity(
                "shared-cover-work-1",
                null,
                "First",
                "First",
                coverId = coverId,
                offlineAttachments = true,
                origin = "dlsite",
            )
        val second = first.copy(id = "shared-cover-work-2", title = "Second")
        graph.library.dao.putWorks(listOf(first, second))
        val cover =
            File(graph.context.filesDir, "offline/assets/$coverId").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
        try {
            graph.offline.removeOffline(first.id)
            assertTrue("The second account still owns the pinned cover", cover.isFile)
            assertTrue(graph.library.dao.work(second.id)!!.offlineAttachments)
            graph.sync.removeOnlineWork(second.id)
            assertFalse("The final owner's removal releases the offline file", cover.exists())
        } finally {
            runCatching { graph.sync.removeOnlineWork(first.id) }
            runCatching { graph.sync.removeOnlineWork(second.id) }
            cover.delete()
        }
    }

    @Test
    fun sourceRescanKeepsIdentityProgressAndFavoritesWhenTracksAreAddedOrRemoved() = runBlocking {
        val graph = graph()
        val server = MockWebServer()
        server.start()
        val sourceId = "stable-rescan-source"
        fun listing(vararg names: String) =
            MockResponse.Builder()
                .code(207)
                .body(
                    "<d:multistatus xmlns:d=\"DAV:\">" +
                        names.joinToString("") { name ->
                            "<d:response><d:href>/dav/RJ00000001%20Example/$name</d:href><d:propstat><d:prop><d:displayname>$name</d:displayname><d:getcontentlength>128</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        } +
                        "</d:multistatus>",
                )
                .build()
        graph.library.dao.putSource(
            SourceEntity(sourceId, "Rescan", "webdav", server.url("/dav/").toString()),
        )
        var workId: String? = null
        try {
            server.enqueue(listing("01.m4a"))
            graph.sync.scan(sourceId)
            workId = graph.library.dao.sourceWorkIds(sourceId).single()
            val first = graph.library.dao.details(workId)!!
            val firstTrack = first.tracks.single()
            graph.library.dao.toggleFavorite(workId)
            graph.library.dao.updateWorkIdentity(workId, "Edited title", "Edited artist")
            graph.library.dao.updateDuration(firstTrack.id, 65000)
            graph.library.dao.saveProgress(ProgressEntity(workId, firstTrack.id, 12345))
            server.enqueue(listing("01.m4a", "02.m4a"))
            graph.sync.scan(sourceId)
            assertEquals(listOf(workId), graph.library.dao.sourceWorkIds(sourceId))
            val added = graph.library.dao.details(workId)!!
            assertEquals(2, added.tracks.size)
            assertTrue(added.work.favorite)
            assertEquals("Edited title", added.work.title)
            assertEquals("Edited artist", added.work.artist)
            assertEquals(65000L, added.tracks.single { it.id == firstTrack.id }.durationMs)
            assertEquals(12345L, graph.library.dao.progress(workId)!!.positionMs)
            server.enqueue(listing("01.m4a"))
            graph.sync.scan(sourceId)
            val removed = graph.library.dao.details(workId)!!
            assertEquals(listOf(firstTrack.id), removed.tracks.map { it.id })
            assertTrue(removed.work.favorite)
            assertEquals(12345L, graph.library.dao.progress(workId)!!.positionMs)
            server.enqueue(listing())
            graph.sync.scan(sourceId)
            assertTrue(graph.library.dao.sourceWorkIds(sourceId).isEmpty())
            assertTrue(graph.library.dao.details(workId)!!.tracks.isEmpty())
            assertTrue(graph.library.dao.work(workId)!!.favorite)
        } finally {
            graph.sync.removeSource(sourceId)
            workId?.let { graph.library.dao.deleteWork(it) }
            server.close()
        }
    }

    @Test
    fun refreshingAWorkReleasesRemovedUnfinishedDownloads() = runBlocking {
        val graph = graph()
        val sourceId = "obsolete-download-source"
        val server = MockWebServer()
        server.start()
        val work = AsmrWork(98, "Download test", "RJ00000098", "Example", null)
        val old =
            TrackEntity(
                "obsolete-download-track",
                work.workId,
                "Old",
                "old.wav",
                "old.wav",
                1,
                uri = server.url("/old.wav").toString(),
                size = 20,
            )
        val current =
            old.copy(
                id = "current-download-track",
                title = "Current",
                fileName = "current.wav",
                relativePath = "current.wav",
                uri = server.url("/current.wav").toString(),
            )
        graph.library.dao.putSource(
            SourceEntity(sourceId, "Download test", "asmrone", server.url("/").toString()),
        )
        graph.library.dao.putWorks(listOf(work.entity(sourceId)))
        graph.library.dao.putTracks(listOf(old))
        val request =
            androidx.media3.exoplayer.offline.DownloadRequest.Builder(
                    old.id,
                    "whisp-asmr://$sourceId/${old.id}".toUri(),
                )
                .setCustomCacheKey(old.id)
                .build()
        (graph.media.downloadManager.downloadIndex
                as androidx.media3.exoplayer.offline.WritableDownloadIndex)
            .putDownload(
                androidx.media3.exoplayer.offline.Download(
                    request,
                    androidx.media3.exoplayer.offline.Download.STATE_STOPPED,
                    1,
                    1,
                    20,
                    1,
                    0,
                    androidx.media3.exoplayer.offline.DownloadProgress().apply {
                        bytesDownloaded = 3
                    },
                ),
            )
        val hole = graph.media.downloadCache.startReadWrite(old.id, 0, 3)
        try {
            val file = graph.media.downloadCache.startFile(old.id, 0, 3)
            file.writeBytes(byteArrayOf(1, 2, 3))
            graph.media.downloadCache.commitFile(file, 3)
        } finally {
            graph.media.downloadCache.releaseHoleSpan(hole)
        }
        try {
            val indexed =
                IndexedLibrary(listOf(work.entity(sourceId)), listOf(current), emptyList())
            graph.sync.addAsmrPreview(AsmrPreview(sourceId, work.artist, work, indexed))
            assertNull(graph.library.dao.track(old.id))
            withTimeout(15_000) {
                while (graph.media.downloadManager.downloadIndex.getDownload(old.id) != null) delay(
                    50,
                )
            }
            assertFalse(graph.media.downloadCache.keys.contains(old.id))
        } finally {
            graph.sync.removeOnlineWork(work.workId)
            graph.sync.removeSource(sourceId)
            server.close()
        }
    }
}
