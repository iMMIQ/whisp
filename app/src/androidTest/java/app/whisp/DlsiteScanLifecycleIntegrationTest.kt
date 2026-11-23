// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whisp.data.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DlsiteScanLifecycleIntegrationTest {
    private fun graph(): AppGraph {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".smoketest"))
        return (context.applicationContext as WhispApp).graph
    }

    private fun client(graph: AppGraph, server: MockWebServer) =
        DlsiteAccountClient(
            DlsiteSession(),
            graph.http,
            root = server.url("/"),
            contentRoot = server.url("/"),
            testTransport = true,
        )

    private fun response(body: String) = MockResponse.Builder().body(body).build()

    @Test
    fun cancelledOldScanCannotOverwriteNewSessionOrClearItsBusyOwner() = runBlocking {
        val graph = graph()
        val clients = graph.sources.dlsiteClients
        val owners = graph.sync.scanOwners
        val cookieLock = graph.sources.dlsiteCookieWrites
        val source = "dlsite-scan-cancellation-test"
        MockWebServer().use { oldServer ->
            MockWebServer().use { newServer ->
                oldServer.start()
                newServer.start()
                oldServer.enqueue(
                    MockResponse.Builder()
                        .body("""{"customer_id":7}""")
                        .bodyDelay(5, TimeUnit.SECONDS)
                        .build(),
                )
                newServer.enqueue(
                    MockResponse.Builder()
                        .body("""{"customer_id":7}""")
                        .bodyDelay(5, TimeUnit.SECONDS)
                        .build(),
                )
                newServer.enqueue(response("""{"user":0,"production":0,"page_limit":100}"""))
                val oldClient = client(graph, oldServer)
                val newClient = client(graph, newServer)
                cookieLock.withLock {
                    clients[source] = oldClient
                    graph.library.dao.putSource(
                        SourceEntity(
                            source,
                            "示例账号",
                            "dlsite",
                            "https://play.dlsite.com/",
                            secret = "old-session",
                        ),
                    )
                }
                var replacement: Deferred<Result<Unit>>? = null
                val oldScan = async(Dispatchers.IO) { runCatching { graph.sync.scan(source) } }
                try {
                    assertNotNull(oldServer.takeRequest(5, TimeUnit.SECONDS))
                    val oldOwner = owners[source]
                    assertNotNull(oldOwner)
                    cookieLock.withLock {
                        oldClient.session.clear()
                        clients[source] = newClient
                        graph.library.dao.putSource(
                            graph.library.dao
                                .source(source)!!
                                .copy(secret = "new-session", error = null),
                        )
                        oldClient.http.dispatcher.cancelAll()
                        replacement =
                            async(Dispatchers.IO) { runCatching { graph.sync.scan(source) } }
                        withTimeout(5000) { while (owners[source] === oldOwner) delay(10) }
                    }
                    assertNotNull(newServer.takeRequest(5, TimeUnit.SECONDS))
                    val cancelled = withTimeout(5000) { oldScan.await() }
                    assertTrue(cancelled.exceptionOrNull() is CancellationException)
                    assertTrue(source in graph.sync.busy.value)
                    assertNull(graph.library.dao.source(source)!!.error)
                    assertEquals("new-session", graph.library.dao.source(source)!!.secret)
                    assertTrue(withTimeout(15_000) { replacement!!.await() }.isSuccess)
                    assertFalse(source in graph.sync.busy.value)
                    assertNull(graph.library.dao.source(source)!!.error)
                } finally {
                    withContext(NonCancellable) {
                        oldScan.cancelAndJoin()
                        replacement?.cancelAndJoin()
                        cookieLock.withLock {
                            clients.remove(source)?.session?.clear()
                            oldClient.http.dispatcher.cancelAll()
                            newClient.http.dispatcher.cancelAll()
                            graph.library.dao.deleteSource(source)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun completedOldPurchaseResponseCannotAdvanceTheReplacementSession() = runBlocking {
        val graph = graph()
        val clients = graph.sources.dlsiteClients
        val cookieLock = graph.sources.dlsiteCookieWrites
        val source = "dlsite-scan-commit-test"
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"customer_id":7}"""))
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"user":0,"production":0,"page_limit":100}""")
                    .bodyDelay(1, TimeUnit.SECONDS)
                    .build(),
            )
            val oldClient = client(graph, server)
            val newClient = client(graph, server)
            cookieLock.withLock {
                clients[source] = oldClient
                graph.library.dao.putSource(
                    SourceEntity(
                        source,
                        "示例账号",
                        "dlsite",
                        "https://play.dlsite.com/",
                        secret = "old-session",
                    ),
                )
            }
            val sync =
                async(Dispatchers.IO) { runCatching { graph.sync.syncDlsitePurchases(source) } }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                cookieLock.withLock {
                    oldClient.session.clear()
                    clients[source] = newClient
                    graph.library.dao.putSource(
                        graph.library.dao
                            .source(source)!!
                            .copy(secret = "new-session", lastScan = 12345, error = null),
                    )
                }
                val cancelled = withTimeout(5000) { sync.await() }
                assertTrue(cancelled.exceptionOrNull() is CancellationException)
                val stored = graph.library.dao.source(source)!!
                assertEquals(12345L, stored.lastScan)
                assertEquals("new-session", stored.secret)
                assertNull(stored.error)
            } finally {
                withContext(NonCancellable) {
                    sync.cancelAndJoin()
                    cookieLock.withLock {
                        clients.remove(source)?.session?.clear()
                        oldClient.http.dispatcher.cancelAll()
                        newClient.http.dispatcher.cancelAll()
                        graph.library.dao.deleteSource(source)
                    }
                }
            }
        }
    }
}
